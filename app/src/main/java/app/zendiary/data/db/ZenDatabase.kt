package app.zendiary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.zendiary.data.crypto.KeyVault
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [EntryEntity::class, ImageEntity::class, TagEntity::class, EntryTagCrossRef::class, CycleDayEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ZenDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun tagDao(): TagDao
    abstract fun cycleDao(): CycleDao

    companion object {
        private val defaultTags = listOf(
            "Sleep", "Exercise", "Family", "Friends", "Work", "Reading", "Nature",
            "Meditation", "Cooking", "Travel", "Self-care", "Creative",
        )

        fun create(ctx: Context, vault: KeyVault): ZenDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(vault.dbPassphrase())
            return Room.databaseBuilder(ctx, ZenDatabase::class.java, "zen.db")
                .openHelperFactory(factory)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        defaultTags.forEachIndexed { i, n -> db.execSQL("INSERT INTO tags(name, position) VALUES ('$n', $i)") }
                    }
                })
                .build()
        }
    }
}
