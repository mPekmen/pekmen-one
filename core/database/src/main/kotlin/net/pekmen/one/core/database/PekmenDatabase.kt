package net.pekmen.one.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.pekmen.one.core.database.dao.AccountClaimDao
import net.pekmen.one.core.database.dao.AccountSessionDao
import net.pekmen.one.core.database.dao.FocusProfileDao
import net.pekmen.one.core.database.dao.FocusSessionDao
import net.pekmen.one.core.database.dao.LearningDao
import net.pekmen.one.core.database.dao.LearningTopicDao
import net.pekmen.one.core.database.dao.PlannerTaskDao
import net.pekmen.one.core.database.dao.PortabilityDao
import net.pekmen.one.core.database.dao.SocialCircleDao
import net.pekmen.one.core.database.dao.SyncOutboxDao
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SocialCircleEntity
import net.pekmen.one.core.database.entity.SocialCircleMembershipEntity
import net.pekmen.one.core.database.entity.SocialMemberSummaryEntity
import net.pekmen.one.core.database.entity.SyncCursorEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity

@Database(
    entities = [
        FocusProfileEntity::class,
        FocusSessionEntity::class,
        FocusSessionSegmentEntity::class,
        SyncOutboxEntity::class,
        LearningSubjectEntity::class,
        LearningBookEntity::class,
        LearningTopicEntity::class,
        PlannerTaskEntity::class,
        SyncCursorEntity::class,
        SocialCircleEntity::class,
        SocialCircleMembershipEntity::class,
        SocialMemberSummaryEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class PekmenDatabase : RoomDatabase() {
    abstract fun focusProfileDao(): FocusProfileDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun learningDao(): LearningDao
    abstract fun learningTopicDao(): LearningTopicDao
    abstract fun plannerTaskDao(): PlannerTaskDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun socialCircleDao(): SocialCircleDao
    abstract fun portabilityDao(): PortabilityDao
    abstract fun accountClaimDao(): AccountClaimDao
    abstract fun accountSessionDao(): AccountSessionDao

    companion object {
        const val DATABASE_NAME = "pekmen_one.db"

        fun create(context: Context): PekmenDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PekmenDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(PekmenMigrations.MIGRATION_1_2)
                .build()
        }

        fun createInMemory(context: Context): PekmenDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PekmenDatabase::class.java
            )
                .allowMainThreadQueries()
                .addMigrations(PekmenMigrations.MIGRATION_1_2)
                .build()
        }

        fun createFileBackedForTest(context: Context, name: String): PekmenDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PekmenDatabase::class.java,
                name
            )
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .addMigrations(PekmenMigrations.MIGRATION_1_2)
                .build()
        }
    }
}
