package app.zendiary.io.importer

import app.zendiary.domain.CycleDay
import app.zendiary.io.*
import app.zendiary.io.export.PdfPayload
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipFile

private fun ZipFile.has(name: String) = getEntry(name) != null
private fun File.zipHas(name: String) = isZip() && runCatching { ZipFile(this).use { it.has(name) } }.getOrDefault(false)
private fun File.head(n: Int = 200): String = inputStream().use { val b = ByteArray(n); val r = it.read(b); if (r <= 0) "" else String(b, 0, r, Charsets.ISO_8859_1) }

private fun parseTime(s: String): Int = runCatching {
    val t = s.trim()
    val fmt = listOf("H:mm", "HH:mm", "h:mm a", "hh:mm a", "h:mm:ss a", "H:mm:ss")
    fmt.firstNotNullOfOrNull { f -> runCatching { LocalTime.parse(t, java.time.format.DateTimeFormatter.ofPattern(f, java.util.Locale.ENGLISH)) }.getOrNull() }
        ?.let { it.hour * 60 + it.minute } ?: (12 * 60)
}.getOrDefault(12 * 60)

/** Lossless .zdb backups (ZIP with manifest.json). */
class ZenBackupImporter : Importer {
    override val id = "zdb"; override val label = "Zen Diary backup (.zdb)"
    override suspend fun probe(file: File, displayName: String) = file.zipHas("manifest.json")
    override suspend fun read(file: File): ImportBatch { val z = ZipFile(file); return ZenBackupFormat.read(z) { z.close() } }
}

/** PDFs exported by Zen Diary (payload hidden in comment lines). */
class ZenPdfImporter(private val cacheDir: File) : Importer {
    override val id = "pdf"; override val label = "Zen Diary PDF"
    override suspend fun probe(file: File, displayName: String) = file.head(5).startsWith("%PDF")
    override suspend fun read(file: File): ImportBatch {
        val tmp = File.createTempFile("pdfpayload", ".zip", cacheDir)
        if (!PdfPayload.extract(file, tmp)) {
            tmp.delete()
            error("This PDF has no Zen Diary data. Only PDFs exported from Zen Diary can be imported (PDF is a display format; other PDFs can't be parsed reliably).")
        }
        val z = ZipFile(tmp)
        return ZenBackupFormat.read(z) { z.close(); tmp.delete() }
    }
}

/** Zen Diary CSV (plain or ZIP bundle with images/). */
class ZenCsvImporter : Importer {
    override val id = "zen_csv"; override val label = "Zen Diary CSV"
    override suspend fun probe(file: File, displayName: String): Boolean =
        file.zipHas("entries.csv") || (!file.isZip() && file.head().startsWith("date,time,mood,title"))

    override suspend fun read(file: File): ImportBatch {
        val zip = if (file.isZip()) ZipFile(file) else null
        val text = if (zip != null) zip.getInputStream(zip.getEntry("entries.csv")).bufferedReader().readText() else file.readText()
        val rows = Csv.parse(text)
        val h = rows.first().map { it.trim().lowercase() }
        fun col(r: List<String>, n: String) = h.indexOf(n).let { if (it in r.indices) r[it] else "" }
        val warnings = mutableListOf<String>()
        val entries = rows.drop(1).mapNotNull { r ->
            val date = runCatching { LocalDate.parse(col(r, "date").trim()) }.getOrNull()
            if (date == null) { warnings += "Skipped a row with invalid date '${col(r, "date")}'"; return@mapNotNull null }
            val minutes = parseTime(col(r, "time")); val mood = col(r, "mood").trim().toIntOrNull() ?: 3
            val title = col(r, "title"); val body = col(r, "body")
            ImportedEntry(
                uid = col(r, "uid").ifBlank { contentUid(date, minutes, mood, title, body) },
                date = date, minutes = minutes, mood = mood, title = title, body = body, event = col(r, "event"),
                favorite = col(r, "favorite").trim() == "1",
                tags = col(r, "tags").split("|").map { it.trim() }.filter { it.isNotEmpty() },
                images = col(r, "images").split("|").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { n ->
                    zip?.getEntry("images/$n")?.let { ze -> ImageOpener { zip.getInputStream(ze) } }
                },
            )
        }
        return ImportBatch(entries, emptyList(), warnings) { zip?.close() }
    }
}

/** Zen Diary TXT (plain or ZIP bundle with images/). */
class ZenTxtImporter : Importer {
    override val id = "zen_txt"; override val label = "Zen Diary text"
    override suspend fun probe(file: File, displayName: String): Boolean =
        file.zipHas("entries.txt") || (!file.isZip() && file.head().contains("ZEN DIARY EXPORT"))

    override suspend fun read(file: File): ImportBatch {
        val zip = if (file.isZip()) ZipFile(file) else null
        val text = if (zip != null) zip.getInputStream(zip.getEntry("entries.txt")).bufferedReader().readText() else file.readText()
        val warnings = mutableListOf<String>()
        val out = mutableListOf<ImportedEntry>()
        var cur: MutableMap<String, String>? = null
        var inBody = false
        val body = StringBuilder()

        fun finish() {
            val c = cur ?: return
            val date = runCatching { LocalDate.parse(c["date"]?.trim()) }.getOrNull()
            if (date == null) { warnings += "Skipped an entry with invalid date"; return }
            val minutes = parseTime(c["time"] ?: ""); val mood = Regex("\\d").find(c["mood"] ?: "")?.value?.toInt() ?: 3
            val b = body.toString().trimEnd('\n'); val title = c["title"] ?: ""
            out += ImportedEntry(
                uid = (c["uid"] ?: "").ifBlank { contentUid(date, minutes, mood, title, b) },
                date = date, minutes = minutes, mood = mood, title = title, body = b, event = c["event"] ?: "",
                favorite = (c["favorite"] ?: "").equals("yes", true),
                tags = (c["tags"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                images = (c["images"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { n ->
                    zip?.getEntry("images/$n")?.let { ze -> ImageOpener { zip.getInputStream(ze) } }
                },
            )
        }

        for (line in text.lines()) {
            when {
                line == "#ENTRY" -> { cur = mutableMapOf(); inBody = false; body.clear() }
                line == "#END" -> { finish(); cur = null }
                cur == null -> {}
                inBody -> body.append(if (line.startsWith("\\")) line.substring(1) else line).append('\n')
                line == "---" -> inBody = true
                else -> { val i = line.indexOf(':'); if (i > 0) cur[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim() }
            }
        }
        return ImportBatch(out, emptyList<CycleDay>(), warnings) { zip?.close() }
    }
}
