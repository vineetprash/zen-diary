package app.zendiary.io.importer

import android.util.Base64
import app.zendiary.io.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private fun parseClock(s: String): Int = runCatching {
    val t = s.trim()
    listOf("H:mm", "HH:mm", "h:mm a", "hh:mm a", "h:mm:ss a", "H:mm:ss").firstNotNullOfOrNull { f ->
        runCatching { java.time.LocalTime.parse(t, DateTimeFormatter.ofPattern(f, Locale.ENGLISH)) }.getOrNull()
    }?.let { it.hour * 60 + it.minute } ?: (12 * 60)
}.getOrDefault(12 * 60)

/**
 * Daylio CSV export (no images).
 * Columns: full_date,date,weekday,time,mood,activities,note_title,note
 * Moods: rad/good/meh/bad/awful. Activities are separated by " | ".
 */
class DaylioCsvImporter : Importer {
    override val id = "daylio_csv"; override val label = "Daylio CSV"

    override suspend fun probe(file: File, displayName: String): Boolean {
        if (file.isZip()) return false
        val head = file.inputStream().use { val b = ByteArray(300); String(b, 0, maxOf(it.read(b), 0)) }.lowercase()
        return head.contains("full_date") && head.contains("activities")
    }

    override suspend fun read(file: File): ImportBatch {
        val rows = Csv.parse(file.readText())
        val h = rows.first().map { it.trim().lowercase() }
        fun col(r: List<String>, n: String) = h.indexOf(n).let { if (it in r.indices) r[it] else "" }
        val warnings = mutableListOf<String>()
        val unknownMoods = mutableSetOf<String>()
        val entries = rows.drop(1).mapNotNull { r ->
            val date = runCatching { LocalDate.parse(col(r, "full_date").trim()) }.getOrNull()
                ?: run { warnings += "Skipped row with unreadable date '${col(r, "full_date")}'"; return@mapNotNull null }
            val moodName = col(r, "mood").trim().lowercase()
            val mood = when (moodName) {
                "rad" -> 5; "good" -> 4; "meh" -> 3; "bad" -> 2; "awful" -> 1
                else -> { unknownMoods += moodName; 3 }
            }
            val minutes = parseClock(col(r, "time"))
            val title = col(r, "note_title").trim()
            val body = col(r, "note").replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").trim()
            ImportedEntry(
                uid = contentUid(date, minutes, mood, title, body), date = date, minutes = minutes, mood = mood,
                title = title, body = body,
                tags = col(r, "activities").split("|").map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
        if (unknownMoods.isNotEmpty()) warnings += "Custom mood names (${unknownMoods.joinToString()}) were imported as 'Meh'. Use the .daylio backup for exact mood mapping."
        return ImportBatch(entries, emptyList(), warnings)
    }
}

/**
 * Daylio backup (.daylio): a ZIP holding `backup.daylio` (Base64-encoded JSON) plus the photos under assets/.
 * The format is undocumented; this importer follows the community-reverse-engineered layout and is tolerant:
 * unknown fields are ignored, photos are matched to entries by checksum or id, anything unmatched is reported.
 */
class DaylioBackupImporter : Importer {
    override val id = "daylio_backup"; override val label = "Daylio backup (.daylio)"

    override suspend fun probe(file: File, displayName: String): Boolean =
        displayName.endsWith(".daylio", true) ||
            (file.isZip() && runCatching { ZipFile(file).use { it.getEntry("backup.daylio") != null } }.getOrDefault(false))

    override suspend fun read(file: File): ImportBatch {
        val zip: ZipFile? = if (file.isZip()) ZipFile(file) else null
        val raw: ByteArray = if (zip != null) {
            val e = zip.getEntry("backup.daylio") ?: error("backup.daylio not found inside the archive")
            zip.getInputStream(e).readBytes()
        } else file.readBytes()
        val jsonText = String(decode(raw), Charsets.UTF_8)
        val root = JSONObject(jsonText)
        val warnings = mutableListOf<String>()

        // mood id -> level 1..5 (Daylio: group 1 = rad ... 5 = awful; ours is inverted)
        val groupOf = HashMap<Int, Int>()
        root.optJSONArray("customMoods")?.forEachObj { m ->
            val id = m.optInt("id", -1)
            groupOf[id] = m.optInt("mood_group_id", m.optInt("predefined_name_id", id))
        }
        val tagName = HashMap<Int, String>()
        root.optJSONArray("tags")?.forEachObj { t -> tagName[t.optInt("id")] = t.optString("name") }

        // photo index: asset id -> zip entry (match by checksum or id in the file name)
        val files: List<ZipEntry> = zip?.entries()?.toList()?.filter { !it.isDirectory && it.name != "backup.daylio" } ?: emptyList()
        val byBase = files.associateBy { File(it.name).nameWithoutExtension.lowercase() }
        val assetEntry = HashMap<Int, ZipEntry>()
        root.optJSONArray("assets")?.forEachObj { a ->
            val id = a.optInt("id", -1)
            val checksum = a.optString("checksum").lowercase()
            val found = (if (checksum.isNotEmpty()) byBase[checksum] ?: files.firstOrNull { it.name.lowercase().contains(checksum) } else null)
                ?: byBase[id.toString()]
            if (found != null) assetEntry[id] = found
        }

        var missingPhotos = 0
        val entries = ArrayList<ImportedEntry>()
        root.optJSONArray("dayEntries")?.forEachObj { o ->
            val date = runCatching { LocalDate.of(o.getInt("year"), o.getInt("month") + 1, o.getInt("day")) }.getOrNull()
                ?: runCatching { Instant.ofEpochMilli(o.getLong("datetime")).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
                ?: run { warnings += "Skipped an entry with unreadable date"; return@forEachObj }
            val minutes = o.optInt("hour", 12) * 60 + o.optInt("minute", 0)
            val rawMood = o.optInt("mood", 3)
            val level = (6 - (groupOf[rawMood] ?: rawMood)).coerceIn(1, 5)
            val title = o.optString("note_title").trim()
            val body = o.optString("note").replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").trim()
            val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { tagName[a.optInt(it)] } } ?: emptyList()
            val photos = ArrayList<ImageOpener>()
            o.optJSONArray("assets")?.let { a ->
                for (i in 0 until a.length()) {
                    val ze = assetEntry[a.optInt(i)]
                    if (ze != null && zip != null) photos += ImageOpener { zip.getInputStream(ze) } else missingPhotos++
                }
            }
            entries += ImportedEntry(
                uid = contentUid(date, minutes, level, title, body), date = date, minutes = minutes, mood = level,
                title = title, body = body, tags = tags, images = photos,
            )
        }
        if (missingPhotos > 0) warnings += "$missingPhotos photo reference(s) could not be matched to a file in the backup."
        if (entries.isEmpty()) warnings += "No entries were found. If this is a valid Daylio backup, please report the file layout so the importer can be adjusted."
        return ImportBatch(entries, emptyList(), warnings) { zip?.close() }
    }

    private fun decode(raw: ByteArray): ByteArray {
        val first = raw.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
        return if (first == '{') raw else Base64.decode(raw, Base64.DEFAULT)
    }

    private inline fun JSONArray.forEachObj(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }
}
