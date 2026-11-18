package net.pekmen.one.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.pekmen.one.core.database.dao.FocusProfileDao
import net.pekmen.one.core.database.dao.FocusSessionDao
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity

@Database(
    entities = [
        FocusProfileEntity::class,
        FocusSessionEntity::class,
        FocusSessionSegmentEntity::class,
        SyncOutboxEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class PekmenDatabase : RoomDatabase() {
    abstract fun focusProfileDao(): FocusProfileDao
    abstract fun focusSessionDao(): FocusSessionDao

    companion object {
        private const val DATABASE_NAME = "pekmen_one.db"

        fun create(context: Context): PekmenDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PekmenDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }

        fun createInMemory(context: Context): PekmenDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PekmenDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
