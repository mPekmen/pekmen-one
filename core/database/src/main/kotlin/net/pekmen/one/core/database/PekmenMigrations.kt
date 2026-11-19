package net.pekmen.one.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object PekmenMigrations {

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            alterM1Tables(db)
            createLearningTables(db)
            createPlannerTables(db)
            createSyncAndSocialTables(db)
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE learning_subjects ADD COLUMN daily_goal_minutes INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "ALTER TABLE learning_topics ADD COLUMN parent_topic_id TEXT " +
                    "REFERENCES learning_topics(id) ON DELETE SET NULL"
            )
            db.execSQL("CREATE INDEX index_learning_topics_parent_topic_id ON learning_topics(parent_topic_id)")
            db.execSQL("CREATE INDEX index_focus_sessions_topic_id ON focus_sessions(topic_id)")
        }
    }

    private fun alterM1Tables(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `focus_sessions` ADD COLUMN `book_start_page` INTEGER")
        db.execSQL("ALTER TABLE `focus_sessions` ADD COLUMN `book_end_page` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_subject_id` ON `focus_sessions` (`subject_id`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_sessions_owner_user_id` " +
                "ON `focus_sessions` (`owner_user_id`)"
        )

        db.execSQL("ALTER TABLE `sync_outbox` ADD COLUMN `owner_user_id` TEXT")
        db.execSQL("ALTER TABLE `sync_outbox` ADD COLUMN `last_error` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_owner_user_id` ON `sync_outbox` (`owner_user_id`)")
    }

    private fun createLearningTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_subjects` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `color_hex` INTEGER NOT NULL,
                `weekly_goal_minutes` INTEGER NOT NULL,
                `sort_order` INTEGER NOT NULL,
                `is_archived` INTEGER NOT NULL,
                `owner_user_id` TEXT,
                `created_at_ms` INTEGER NOT NULL,
                `client_updated_at_ms` INTEGER NOT NULL,
                `server_updated_at_ms` INTEGER,
                `deleted_at_ms` INTEGER,
                `version` INTEGER NOT NULL,
                `sync_status` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_subjects_deleted_at_ms` " +
                "ON `learning_subjects` (`deleted_at_ms`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_subjects_owner_user_id` " +
                "ON `learning_subjects` (`owner_user_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_subjects_sort_order` " +
                "ON `learning_subjects` (`sort_order`)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_books` (
                `id` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `author` TEXT,
                `total_pages` INTEGER NOT NULL,
                `current_page` INTEGER NOT NULL,
                `total_chapters` INTEGER NOT NULL,
                `completed_chapters` INTEGER NOT NULL,
                `is_completed` INTEGER NOT NULL,
                `owner_user_id` TEXT,
                `created_at_ms` INTEGER NOT NULL,
                `client_updated_at_ms` INTEGER NOT NULL,
                `server_updated_at_ms` INTEGER,
                `deleted_at_ms` INTEGER,
                `version` INTEGER NOT NULL,
                `sync_status` TEXT NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`subject_id`) REFERENCES `learning_subjects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_books_subject_id` ON `learning_books` (`subject_id`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_books_deleted_at_ms` ON `learning_books` (`deleted_at_ms`)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_topics` (
                `id` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `book_id` TEXT,
                `title` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `sort_order` INTEGER NOT NULL,
                `owner_user_id` TEXT,
                `created_at_ms` INTEGER NOT NULL,
                `client_updated_at_ms` INTEGER NOT NULL,
                `server_updated_at_ms` INTEGER,
                `deleted_at_ms` INTEGER,
                `version` INTEGER NOT NULL,
                `sync_status` TEXT NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`subject_id`) REFERENCES `learning_subjects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_topics_subject_id` ON `learning_topics` (`subject_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_topics_book_id` ON `learning_topics` (`book_id`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_topics_deleted_at_ms` ON `learning_topics` (`deleted_at_ms`)"
        )
    }

    private fun createPlannerTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `planner_tasks` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `notes` TEXT,
                `subject_id` TEXT,
                `due_at_wall_ms` INTEGER,
                `reminder_at_wall_ms` INTEGER,
                `recurrence_rule` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `completed_at_wall_ms` INTEGER,
                `priority` INTEGER NOT NULL,
                `owner_user_id` TEXT,
                `created_at_ms` INTEGER NOT NULL,
                `client_updated_at_ms` INTEGER NOT NULL,
                `server_updated_at_ms` INTEGER,
                `deleted_at_ms` INTEGER,
                `version` INTEGER NOT NULL,
                `sync_status` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_planner_tasks_due_at_wall_ms` ON `planner_tasks` (`due_at_wall_ms`)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_planner_tasks_status` ON `planner_tasks` (`status`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_planner_tasks_deleted_at_ms` ON `planner_tasks` (`deleted_at_ms`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_planner_tasks_owner_user_id` ON `planner_tasks` (`owner_user_id`)"
        )
    }

    private fun createSyncAndSocialTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_cursors` (
                `entity_table` TEXT NOT NULL,
                `last_server_updated_at_ms` INTEGER NOT NULL,
                `last_entity_id` TEXT NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`entity_table`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `social_circles` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `invite_code` TEXT NOT NULL,
                `created_by_user_id` TEXT NOT NULL,
                `created_at_ms` INTEGER NOT NULL,
                `deleted_at_ms` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_social_circles_deleted_at_ms` ON `social_circles` (`deleted_at_ms`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `social_circle_memberships` (
                `id` TEXT NOT NULL,
                `circle_id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `share_enabled` INTEGER NOT NULL,
                `share_live_focus_status` INTEGER NOT NULL,
                `allowed_subject_titles_csv` TEXT NOT NULL,
                `joined_at_ms` INTEGER NOT NULL,
                `left_at_ms` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_social_circle_memberships_circle_id_user_id` " +
                "ON `social_circle_memberships` (`circle_id`, `user_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_social_circle_memberships_circle_id` " +
                "ON `social_circle_memberships` (`circle_id`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `social_member_summaries` (
                `id` TEXT NOT NULL,
                `circle_id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `period_type` TEXT NOT NULL,
                `period_start_date` TEXT NOT NULL,
                `live_timer_focus_sec` INTEGER NOT NULL,
                `manual_entry_focus_sec` INTEGER NOT NULL,
                `subject_totals_json` TEXT NOT NULL,
                `is_currently_focusing` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_social_member_summaries_circle_id_user_id_period_type_period_start_date` " +
                "ON `social_member_summaries` (`circle_id`, `user_id`, `period_type`, `period_start_date`)"
        )
    }
}
