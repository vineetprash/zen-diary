package app.zendiary.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.zendiary.data.crypto.KeyVault
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Photos live encrypted (AES-GCM, Keystore key) in app-private storage, downscaled on import. */
class ImageStore(ctx: Context, private val vault: KeyVault) {
    private val dir = File(ctx.filesDir, "images").apply { mkdirs() }

    suspend fun importUri(resolver: ContentResolver, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { resolver.openInputStream(uri)?.use { importStream(it) } }.getOrNull()
    }

    /** Decode -> fix EXIF rotation -> downscale -> JPEG -> encrypt. Returns stored file name. */
    fun importStream(input: InputStream, maxEdge: Int = 1600): String? {
        val bytes = input.readBytes()
        val bmp = decode(bytes, maxEdge) ?: return null
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        bmp.recycle()
        val name = UUID.randomUUID().toString() + ".jpg"
        File(dir, name).outputStream().use { vault.encryptStream(it).use { enc -> enc.write(jpeg) } }
        return name
    }

    fun readBytes(name: String): ByteArray =
        File(dir, name).inputStream().use { vault.decryptStream(it).readBytes() }

    /** Smaller copy used for the PDF payload. */
    fun downscaled(name: String, maxEdge: Int, quality: Int): ByteArray? = runCatching {
        val bmp = decode(readBytes(name), maxEdge) ?: return null
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it); bmp.recycle() }.toByteArray()
    }.getOrNull()

    fun delete(name: String) { File(dir, name).delete() }

    private fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdge || bounds.outHeight / (sample * 2) >= maxEdge) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(bmp.width, bmp.height))
        if (rot != 0f || scale < 1f) {
            val m = Matrix().apply { if (rot != 0f) postRotate(rot); if (scale < 1f) postScale(scale, scale) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        if (bmp.hasAlpha()) {   // flatten transparency onto white so JPEG doesn't go black
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp.recycle(); bmp = flat
        }
        return bmp
    }
}

/** Coil model for an encrypted stored image. */
data class EncryptedImage(val name: String)

class EncryptedImageFetcher(
    private val data: EncryptedImage,
    private val options: Options,
    private val store: ImageStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = withContext(Dispatchers.IO) { store.readBytes(data.name) }
        val buf = okio.Buffer().write(bytes)
        return SourceResult(ImageSource(buf, options.context), "image/jpeg", DataSource.DISK)
    }

    class Factory(private val store: ImageStore) : Fetcher.Factory<EncryptedImage> {
        override fun create(data: EncryptedImage, options: Options, imageLoader: ImageLoader): Fetcher =
            EncryptedImageFetcher(data, options, store)
    }
}

val EncryptedImageKeyer = Keyer<EncryptedImage> { data, _ -> "enc:" + data.name }
