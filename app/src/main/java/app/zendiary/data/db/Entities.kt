package app.zendiary.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "entries", indices = [Index("date"), Index(value = ["uid"], unique = true)])
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val date: Long,            // epoch day
    val minutes: Int,          // minutes since midnight
    val mood: Int,
    val title: String,
    val body: String,
    val event: String,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "images",
    foreignKeys = [ForeignKey(EntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("entryId")],
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val fileName: String,
    val position: Int,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "entry_tags",
    primaryKeys = ["entryId", "tagId"],
    foreignKeys = [
        ForeignKey(EntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class EntryTagCrossRef(val entryId: Long, val tagId: Long)

@Entity(tableName = "cycle_days")
data class CycleDayEntity(
    @PrimaryKey val date: Long,
    val flow: Int,
    val symptoms: String,   // comma separated
    val note: String,
)

data class EntryWithDetails(
    @Embedded val entry: EntryEntity,
    @Relation(parentColumn = "id", entityColumn = "entryId") val images: List<ImageEntity>,
    @Relation(
        parentColumn = "id", entityColumn = "id",
        associateBy = Junction(EntryTagCrossRef::class, parentColumn = "entryId", entityColumn = "tagId"),
    ) val tags: List<TagEntity>,
)

data class MoodRow(val id: Long, val date: Long, val mood: Int)
data class TagMoodRow(val date: Long, val mood: Int, val tag: String)
