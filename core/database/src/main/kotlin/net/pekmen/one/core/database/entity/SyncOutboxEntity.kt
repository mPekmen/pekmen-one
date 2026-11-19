package net.pekmen.one.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mutation queue reserved for M4/M5 eventual cloud synchronization.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["entity_table", "entity_id"], unique = true),
        Index(value = ["owner_user_id"])
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "mutation_id")
    val mutationId: String,
    @ColumnInfo(name = "entity_table")
    val entityTable: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "op_type")
    val opType: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "base_version")
    val baseVersion: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: String? = null,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null
)
