package app.zendiary.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Hardware-backed (Android Keystore) AES-256-GCM key. Protects the DB passphrase, images and stored secrets. */
class KeyVault(private val ctx: Context) {
    private val alias = "zen_diary_master"
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    fun encryptString(s: String): String = Base64.encodeToString(encrypt(s.toByteArray()), Base64.NO_WRAP)
    fun decryptString(s: String): String? = runCatching { String(decrypt(Base64.decode(s, Base64.NO_WRAP))) }.getOrNull()

    fun encryptStream(out: OutputStream): OutputStream {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        out.write(c.iv)
        return CipherOutputStream(out, c)
    }

    fun decryptStream(input: InputStream): InputStream {
        val iv = ByteArray(12)
        var read = 0
        while (read < 12) { val n = input.read(iv, read, 12 - read); if (n < 0) error("Corrupt file"); read += n }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return CipherInputStream(input, c)
    }

    /** Random 256-bit SQLCipher passphrase, stored wrapped by the Keystore key. */
    fun dbPassphrase(): ByteArray {
        val f = File(ctx.noBackupFilesDir, "db.key")
        if (f.exists()) return decrypt(f.readBytes())
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        f.writeBytes(encrypt(pass))
        return pass
    }
}
