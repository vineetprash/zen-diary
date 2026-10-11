package app.zendiary.io.export

import android.content.Context
import app.zendiary.data.ImageStore
import app.zendiary.domain.ExportData
import java.io.OutputStream

class ExportContext(val context: Context, val data: ExportData, val images: ImageStore, val includeImages: Boolean)

/**
 * Open/closed extension point: add a new export format by implementing this and registering it in
 * [ExporterRegistry] - no existing code changes.
 */
interface Exporter {
    val id: String
    val label: String
    val description: String
    val supportsPassword: Boolean get() = false
    fun extension(includeImages: Boolean): String
    fun mimeType(includeImages: Boolean): String
    suspend fun export(ctx: ExportContext, out: OutputStream)
}

class ExporterRegistry(val all: List<Exporter>) {
    fun byId(id: String): Exporter = all.first { it.id == id }
}
