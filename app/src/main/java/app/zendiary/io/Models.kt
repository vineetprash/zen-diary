package app.zendiary.io

import app.zendiary.domain.CycleDay
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate

fun interface ImageOpener { fun open(): InputStream? }

data class ImportedEntry(
    val uid: String,
    val date: LocalDate,
    val minutes: Int,
    val mood: Int,
    val title: String,
    val body: String,
    val event: String = "",
    val favorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val images: List<ImageOpener> = emptyList(),
    val createdAt: Long = 0,
)

class ImportBatch(
    val entries: List<ImportedEntry>,
    val cycle: List<CycleDay> = emptyList(),
    val warnings: MutableList<String> = mutableListOf(),
    private val onClose: () -> Unit = {},
) : Closeable { override fun close() = onClose() }

data class ImportResult(val imported: Int, val skipped: Int, val images: Int, val imagesMissing: Int, val warnings: List<String>)

/** Stable id derived from content so re-importing the same data (even via a different format) never duplicates. */
fun contentUid(date: LocalDate, minutes: Int, mood: Int, title: String, body: String): String {
    val s = "$date|$minutes|$mood|${title.trim()}|${body.trim()}"
    return "h:" + MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun File.isZip(): Boolean = length() > 4 && inputStream().use { it.read() == 0x50 && it.read() == 0x4B }

object Csv {
    fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun parse(input: String): List<List<String>> {
        val s = input.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var inQ = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQ) {
                if (c == '"') { if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else inQ = false }
                else f.append(c)
            } else when (c) {
                '"' -> inQ = true
                ',' -> { row.add(f.toString()); f.clear() }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.clear(); rows.add(row); row = ArrayList() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows.filter { it.size > 1 || (it.size == 1 && it[0].isNotBlank()) }
    }
}
