package net.pekmen.one.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionSegment

@Entity(
    tableName = "focus_session_segments",
    foreignKeys = [
        ForeignKey(
            entity = FocusSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["session_id", "segment_index"], unique = true)
    ]
)
data class FocusSessionSegmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "segment_index")
    val segmentIndex: Int,
    @ColumnInfo(name = "phase")
    val phase: String,
    @ColumnInfo(name = "started_at_wall_ms")
    val startedAtWallMs: Long,
    @ColumnInfo(name = "started_at_elapsed_ms")
    val startedAtElapsedMs: Long,
    @ColumnInfo(name = "boot_count")
    val bootCount: Int,
    @ColumnInfo(name = "last_checkpointed_duration_ms")
    val lastCheckpointedDurationMs: Long,
    @ColumnInfo(name = "planned_duration_ms")
    val plannedDurationMs: Long? = null,
    @ColumnInfo(name = "ended_at_wall_ms")
    val endedAtWallMs: Long? = null,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null
) {
    fun toDomain(): FocusSessionSegment = FocusSessionSegment(
        id = id,
        sessionId = sessionId,
        segmentIndex = segmentIndex,
        phase = FocusPhase.valueOf(phase),
        startedAtWallMs = startedAtWallMs,
        startedAtElapsedMs = startedAtElapsedMs,
        bootCount = bootCount,
        lastCheckpointedDurationMs = lastCheckpointedDurationMs,
        plannedDurationMs = plannedDurationMs,
        endedAtWallMs = endedAtWallMs,
        durationMs = durationMs
    )
}
