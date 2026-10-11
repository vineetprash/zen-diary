package app.zendiary.io

import app.zendiary.domain.CycleDay
import app.zendiary.domain.DiaryEntry
import app.zendiary.domain.ExportData
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Lossless format: ZIP with manifest.json + images/. Used for backups, Drive and the PDF payload. */
object ZenBackupFormat {
    fun write(out: OutputStream, data: ExportData, imageBytes: ((String) -> ByteArray?)?) {
        val zip = ZipOutputStream(out)
        val entries = JSONArray()
        data.entries.forEach { e ->
            entries.put(JSONObject().apply {
                put("uid", e.uid); put("date", e.date.toString()); put("minutes", e.minutes); put("mood", e.mood)
                put("title", e.title); put("body", e.body); put("event", e.event); put("favorite", e.favorite)
                put("tags", JSONArray(e.tags)); put("images", JSONArray(if (imageBytes != null) e.images else emptyList<String>()))
                put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
            })
        }
        val cycle = JSONArray()
        data.cycle.forEach { c ->
            cycle.put(JSONObject().put("date", c.date.toString()).put("flow", c.flow)
                .put("symptoms", JSONArray(c.symptoms)).put("note", c.note))
        }
        val manifest = JSONObject().put("format", "zendiary").put("version", 1)
            .put("exportedAt", System.currentTimeMillis()).put("entries", entries).put("cycle", cycle)
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(manifest.toString().toByteArray())
        zip.closeEntry()
        if (imageBytes != null) {
            data.entries.flatMap { it.images }.distinct().forEach { n ->
                imageBytes(n)?.let { zip.putNextEntry(ZipEntry("images/$n")); zip.write(it); zip.closeEntry() }
            }
        }
        zip.finish()
    }

    fun read(zip: ZipFile, onClose: () -> Unit = {}): ImportBatch {
        val m = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
        val warnings = mutableListOf<String>()
        val entries = m.getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val date = LocalDate.parse(o.getString("date"))
                val minutes = o.optInt("minutes", 12 * 60)
                val mood = o.optInt("mood", 3)
                val title = o.optString("title"); val body = o.optString("body")
                val imgs = o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                ImportedEntry(
                    uid = o.optString("uid").ifBlank { contentUid(date, minutes, mood, title, body) },
                    date = date, minutes = minutes, mood = mood, title = title, body = body,
                    event = o.optString("event"), favorite = o.optBoolean("favorite"),
                    tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    images = imgs.mapNotNull { n -> zip.getEntry("images/$n")?.let { ze -> ImageOpener { zip.getInputStream(ze) } } },
                    createdAt = o.optLong("createdAt"),
                )
            }
        }
        val cycle = m.optJSONArray("cycle")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { c ->
                CycleDay(LocalDate.parse(c.getString("date")), c.optInt("flow"),
                    c.optJSONArray("symptoms")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList(), c.optString("note"))
            }
        } ?: emptyList()
        return ImportBatch(entries, cycle, warnings, onClose)
    }
}
