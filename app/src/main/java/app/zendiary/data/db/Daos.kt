package app.zendiary.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Transaction
    @Query("SELECT * FROM entries WHERE date BETWEEN :from AND :to ORDER BY favorite DESC, date DESC, minutes DESC")
    fun observeRange(from: Long, to: Long): Flow<List<EntryWithDetails>>

    @Query("SELECT id, date, mood FROM entries")
    fun observeMoodRows(): Flow<List<MoodRow>>

    @Query("SELECT e.date AS date, e.mood AS mood, t.name AS tag FROM entries e JOIN entry_tags et ON et.entryId = e.id JOIN tags t ON t.id = et.tagId")
    fun observeTagMoodRows(): Flow<List<TagMoodRow>>

    @Query("SELECT MIN(date) FROM entries")
    fun observeMinDate(): Flow<Long?>

    @Transaction @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: Long): EntryWithDetails?

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun getEntity(id: Long): EntryEntity?

    @Transaction @Query("SELECT * FROM entries WHERE date = :date ORDER BY minutes DESC")
    suspend fun forDate(date: Long): List<EntryWithDetails>

    @Transaction @Query("SELECT * FROM entries ORDER BY date ASC, minutes ASC")
    suspend fun allDetails(): List<EntryWithDetails>

    @Transaction
    @Query(
        "SELECT DISTINCT e.* FROM entries e LEFT JOIN entry_tags et ON et.entryId = e.id LEFT JOIN tags t ON t.id = et.tagId " +
            "WHERE e.title LIKE :q OR e.body LIKE :q OR e.event LIKE :q OR t.name LIKE :q ORDER BY e.date DESC, e.minutes DESC LIMIT 200"
    )
    suspend fun search(q: String): List<EntryWithDetails>

    @Query("SELECT uid FROM entries") suspend fun allUids(): List<String>
    @Query("SELECT fileName FROM images WHERE entryId = :id") suspend fun imageNames(id: Long): List<String>
    @Query("DELETE FROM images WHERE entryId = :id") suspend fun clearImages(id: Long)
    @Query("DELETE FROM entry_tags WHERE entryId = :id") suspend fun clearTags(id: Long)
    @Query("DELETE FROM entries WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE entries SET favorite = NOT favorite WHERE id = :id") suspend fun toggleFavorite(id: Long)

    @Insert suspend fun insert(e: EntryEntity): Long
    @Update suspend fun update(e: EntryEntity)
    @Insert suspend fun insertImages(list: List<ImageEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTagRefs(list: List<EntryTagCrossRef>)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY position, id") fun observeAll(): Flow<List<TagEntity>>
    @Query("SELECT * FROM tags WHERE name = :n COLLATE NOCASE LIMIT 1") suspend fun byName(n: String): TagEntity?
    @Query("SELECT COUNT(*) FROM tags") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(t: TagEntity): Long
    @Query("UPDATE tags SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface CycleDao {
    @Query("SELECT * FROM cycle_days ORDER BY date") fun observeAll(): Flow<List<CycleDayEntity>>
    @Query("SELECT * FROM cycle_days ORDER BY date") suspend fun all(): List<CycleDayEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(d: CycleDayEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfAbsent(list: List<CycleDayEntity>)
    @Query("DELETE FROM cycle_days WHERE date = :date") suspend fun delete(date: Long)
}
