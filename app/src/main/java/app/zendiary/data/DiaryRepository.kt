package app.zendiary.data

import androidx.room.withTransaction
import app.zendiary.core.nowMinutes
import app.zendiary.core.toLocalDate
import app.zendiary.data.db.*
import app.zendiary.domain.CycleDay
import app.zendiary.io.ImportBatch
import app.zendiary.io.ImportResult
import app.zendiary.domain.DiaryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class DiaryRepository(private val db: ZenDatabase, val images: ImageStore) {
    private val dao = db.entryDao()
    private val tagDao = db.tagDao()

    fun observeMonth(m: YearMonth): Flow<List<DiaryEntry>> = observeDays(m.atDay(1), m.atEndOfMonth())
    fun observeDays(from: LocalDate, to: LocalDate): Flow<List<DiaryEntry>> =
        dao.observeRange(from.toEpochDay(), to.toEpochDay()).map { l -> l.map { it.toDomain() } }

    fun observeMoodRows() = dao.observeMoodRows()
    fun observeTagMoodRows() = dao.observeTagMoodRows()
    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()
    fun observeFirstMonth(): Flow<YearMonth?> =
        dao.observeMinDate().map { it?.let { d -> YearMonth.from(d.toLocalDate()) } }

    suspend fun get(id: Long): DiaryEntry? = dao.get(id)?.toDomain()
    suspend fun forDate(d: LocalDate): List<DiaryEntry> = dao.forDate(d.toEpochDay()).map { it.toDomain() }
    suspend fun search(q: String): List<DiaryEntry> =
        if (q.isBlank()) emptyList() else dao.search("%${q.trim()}%").map { it.toDomain() }
    suspend fun exportAll(): List<DiaryEntry> = dao.allDetails().map { it.toDomain() }
    suspend fun toggleFavorite(id: Long) = dao.toggleFavorite(id)

    suspend fun save(e: DiaryEntry): Long {
        val now = System.currentTimeMillis()
        var removed: List<String> = emptyList()
        val id = db.withTransaction {
            val id: Long
            if (e.id == 0L) {
                id = dao.insert(
                    EntryEntity(
                        uid = e.uid.ifBlank { UUID.randomUUID().toString() }, date = e.date.toEpochDay(),
                        minutes = e.minutes, mood = e.mood, title = e.title.trim(), body = e.body.trim(),
                        event = e.event.trim(), favorite = e.favorite,
                        createdAt = if (e.createdAt > 0) e.createdAt else now, updatedAt = now,
                    )
                )
            } else {
                val old = dao.getEntity(e.id)!!
                id = e.id
                dao.update(old.copy(
                    date = e.date.toEpochDay(), minutes = e.minutes, mood = e.mood, title = e.title.trim(),
                    body = e.body.trim(), event = e.event.trim(), favorite = e.favorite, updatedAt = now,
                ))
                removed = dao.imageNames(id) - e.images.toSet()
                dao.clearImages(id); dao.clearTags(id)
            }
            dao.insertImages(e.images.mapIndexed { i, n -> ImageEntity(entryId = id, fileName = n, position = i) })
            dao.insertTagRefs(e.tags.map { EntryTagCrossRef(id, ensureTag(it)) })
            id
        }
        removed.forEach(images::delete)
        return id
    }

    suspend fun delete(id: Long) {
        val names = dao.imageNames(id)
        dao.delete(id)
        names.forEach(images::delete)
    }

    /** Widget one-tap: set today's mood (creates an entry if none). */
    suspend fun quickMood(mood: Int) {
        val today = LocalDate.now()
        val existing = dao.forDate(today.toEpochDay()).firstOrNull()?.toDomain()
        if (existing != null) save(existing.copy(mood = mood))
        else save(DiaryEntry(date = today, minutes = nowMinutes(), mood = mood))
    }

    // ---- tags
    private suspend fun ensureTag(name: String): Long {
        val n = name.trim()
        tagDao.byName(n)?.let { return it.id }
        val id = tagDao.insert(TagEntity(name = n, position = tagDao.count()))
        return if (id > 0) id else tagDao.byName(n)!!.id
    }
    suspend fun addTag(name: String) { if (name.isNotBlank()) ensureTag(name) }
    suspend fun renameTag(id: Long, name: String) { if (name.isNotBlank()) tagDao.rename(id, name.trim()) }
    suspend fun deleteTag(id: Long) = tagDao.delete(id)

    // ---- import
    suspend fun importBatch(batch: ImportBatch): ImportResult {
        val existing = dao.allUids().toHashSet()
        var imported = 0; var skipped = 0; var imgOk = 0; var imgMissing = 0
        for (e in batch.entries) {
            if (e.uid in existing) { skipped++; continue }
            val names = mutableListOf<String>()
            for (opener in e.images) {
                val n = runCatching { opener.open()?.use { images.importStream(it) } }.getOrNull()
                if (n != null) { names += n; imgOk++ } else imgMissing++
            }
            save(DiaryEntry(
                uid = e.uid, date = e.date, minutes = e.minutes, mood = e.mood.coerceIn(1, 5), title = e.title,
                body = e.body, event = e.event, favorite = e.favorite, images = names, tags = e.tags, createdAt = e.createdAt,
            ))
            existing += e.uid; imported++
        }
        return ImportResult(imported, skipped, imgOk, imgMissing, batch.warnings.toList())
    }

    private fun EntryWithDetails.toDomain() = DiaryEntry(
        id = entry.id, uid = entry.uid, date = entry.date.toLocalDate(), minutes = entry.minutes, mood = entry.mood,
        title = entry.title, body = entry.body, event = entry.event, favorite = entry.favorite,
        images = images.sortedBy { it.position }.map { it.fileName },
        tags = tags.map { it.name }.sorted(), createdAt = entry.createdAt, updatedAt = entry.updatedAt,
    )
}

class CycleRepository(private val db: ZenDatabase) {
    private val dao = db.cycleDao()
    fun observeAll(): Flow<List<CycleDay>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    suspend fun all(): List<CycleDay> = dao.all().map { it.toDomain() }

    suspend fun set(date: LocalDate, flow: Int, symptoms: List<String>, note: String = "") {
        if (flow == 0 && symptoms.isEmpty() && note.isBlank()) dao.delete(date.toEpochDay())
        else dao.upsert(CycleDayEntity(date.toEpochDay(), flow, symptoms.joinToString(","), note))
    }

    suspend fun importDays(days: List<CycleDay>) =
        dao.insertIfAbsent(days.map { CycleDayEntity(it.date.toEpochDay(), it.flow, it.symptoms.joinToString(","), it.note) })

    private fun CycleDayEntity.toDomain() = CycleDay(
        date.toLocalDate(), flow, symptoms.split(",").map { it.trim() }.filter { it.isNotEmpty() }, note,
    )
}
