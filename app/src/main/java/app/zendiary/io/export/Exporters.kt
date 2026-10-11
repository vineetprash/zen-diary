package app.zendiary.io.export

import app.zendiary.core.asClock
import app.zendiary.domain.DiaryEntry
import app.zendiary.io.Csv
import app.zendiary.io.ZenBackupFormat
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Full-fidelity backup (.zdb = ZIP). Optionally password-encrypted by ExportService. */
class ZenBackupExporter : Exporter {
    override val id = "zdb"
    override val label = "Full backup (.zdb)"
    override val description = "Everything incl. photos, lossless. Encrypted if you set a backup password."
    override val supportsPassword = true
    override fun extension(includeImages: Boolean) = "zdb"
    override fun mimeType(includeImages: Boolean) = "application/octet-stream"
    override suspend fun export(ctx: ExportContext, out: OutputStream) =
        ZenBackupFormat.write(out, ctx.data) { n -> ctx.images.readBytes(n) }
}

/** Shared "text + optional images folder" packaging for CSV/TXT. */
abstract class TextBundleExporter(private val fileName: String, private val ext: String, private val mime: String) : Exporter {
    override fun extension(includeImages: Boolean) = if (includeImages) "zip" else ext
    override fun mimeType(includeImages: Boolean) = if (includeImages) "application/zip" else mime
    abstract fun render(entries: List<DiaryEntry>, includeImages: Boolean): String

    override suspend fun export(ctx: ExportContext, out: OutputStream) {
        val text = render(ctx.data.entries, ctx.includeImages).toByteArray()
        if (!ctx.includeImages) { out.write(text); return }
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(fileName)); zip.write(text); zip.closeEntry()
        ctx.data.entries.flatMap { it.images }.distinct().forEach { n ->
            runCatching { ctx.images.readBytes(n) }.getOrNull()?.let { zip.putNextEntry(ZipEntry("images/$n")); zip.write(it); zip.closeEntry() }
        }
        zip.finish()
    }
}

class CsvExporter : TextBundleExporter("entries.csv", "csv", "text/csv") {
    override val id = "csv"
    override val label = "CSV"
    override val description = "Spreadsheet-friendly. With images it becomes a .zip (entries.csv + images/)."
    override fun render(entries: List<DiaryEntry>, includeImages: Boolean): String = buildString {
        append("date,time,mood,title,body,event,tags,favorite,images,uid\n")
        entries.forEach { e ->
            append(listOf(
                e.date.toString(), e.minutes.asClock(), e.mood.toString(), e.title, e.body, e.event,
                e.tags.joinToString("|"), if (e.favorite) "1" else "0",
                if (includeImages) e.images.joinToString("|") else "", e.uid,
            ).joinToString(",") { Csv.escape(it) })
            append('\n')
        }
    }
}

class TxtExporter : TextBundleExporter("entries.txt", "txt", "text/plain") {
    override val id = "txt"
    override val label = "Plain text"
    override val description = "Human-readable. With images it becomes a .zip (entries.txt + images/)."
    override fun render(entries: List<DiaryEntry>, includeImages: Boolean): String = buildString {
        append("=== ZEN DIARY EXPORT v1 ===\n\n")
        entries.forEach { e ->
            append("#ENTRY\n")
            append("Date: ${e.date}\nTime: ${e.minutes.asClock()}\nMood: ${e.mood}\n")
            append("Title: ${e.title.replace('\n', ' ')}\nEvent: ${e.event.replace('\n', ' ')}\n")
            append("Tags: ${e.tags.joinToString(", ")}\nFavorite: ${if (e.favorite) "yes" else "no"}\n")
            if (includeImages) append("Images: ${e.images.joinToString(", ")}\n")
            append("UID: ${e.uid}\n---\n")
            e.body.lines().forEach { l -> append(if (l.startsWith("#") || l.startsWith("\\")) "\\$l" else l).append('\n') }
            append("#END\n\n")
        }
    }
}
