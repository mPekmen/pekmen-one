package net.pekmen.one.core.database.entity

import androidx.room.ColumnInfo
import net.pekmen.one.core.model.SyncStatus

data class SyncMetadataEntity(
    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: String? = null,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "client_updated_at_ms")
    val clientUpdatedAtMs: Long,
    @ColumnInfo(name = "server_updated_at_ms")
    val serverUpdatedAtMs: Long? = null,
    @ColumnInfo(name = "deleted_at_ms")
    val deletedAtMs: Long? = null,
    @ColumnInfo(name = "version")
    val version: Long = 1L,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.LOCAL_ONLY.name
)
