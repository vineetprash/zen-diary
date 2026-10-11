package app.zendiary.data.crypto

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : Exception("Wrong password or corrupted backup")

/**
 * Password-based file encryption: PBKDF2-HMAC-SHA256 (210k iterations) -> AES-256-GCM,
 * framed in 1 MiB chunks so big backups never need to fit in memory.
 * Layout: "ZDB1" | salt(16) | baseIv(12) | { len(4) | ciphertext }* | 0(4)
 */
object BackupCrypto {
    private val MAGIC = "ZDB1".toByteArray()
    private const val CHUNK = 1 shl 20
    private const val ITER = 210_000

    fun isEncrypted(f: File): Boolean = f.inputStream().use {
        val b = ByteArray(4); it.read(b) == 4 && b.contentEquals(MAGIC)
    }

    private fun key(pw: String, salt: ByteArray): SecretKeySpec {
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun iv(base: ByteArray, counter: Int): ByteArray {
        val iv = base.copyOf()
        for (i in 0..3) iv[8 + i] = (iv[8 + i].toInt() xor (counter ushr (24 - 8 * i))).toByte()
        return iv
    }

    private fun cipher(mode: Int, key: SecretKeySpec, iv: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, iv)) }

    fun encrypt(input: InputStream, out: OutputStream, password: String) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val base = ByteArray(12).also(rnd::nextBytes)
        val k = key(password, salt)
        out.write(MAGIC); out.write(salt); out.write(base)
        val buf = ByteArray(CHUNK)
        var counter = 0
        while (true) {
            var n = 0
            while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
            if (n == 0) break
            val ct = cipher(Cipher.ENCRYPT_MODE, k, iv(base, counter++)).doFinal(buf, 0, n)
            out.write(intBytes(ct.size)); out.write(ct)
            if (n < CHUNK) break
        }
        out.write(intBytes(0))
    }

    fun decrypt(input: InputStream, out: OutputStream, password: String) {
        val din = DataInputStream(input)
        val magic = ByteArray(4).also { din.readFully(it) }
        require(magic.contentEquals(MAGIC)) { "Not an encrypted Zen Diary backup" }
        val salt = ByteArray(16).also { din.readFully(it) }
        val base = ByteArray(12).also { din.readFully(it) }
        val k = key(password, salt)
        var counter = 0
        try {
            while (true) {
                val len = din.readInt()
                if (len == 0) break
                val ct = ByteArray(len).also { din.readFully(it) }
                out.write(cipher(Cipher.DECRYPT_MODE, k, iv(base, counter++)).doFinal(ct))
            }
        } catch (e: GeneralSecurityException) { throw WrongPasswordException()
        } catch (e: EOFException) { throw WrongPasswordException() }
    }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
