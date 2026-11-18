package net.pekmen.one.core.database.relation

import androidx.room.Embedded
import androidx.room.Relation
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.model.ActiveSessionSnapshot

data class FocusSessionWithDetails(
    @Embedded
    val session: FocusSessionEntity,
    @Relation(
        parentColumn = "profile_id",
        entityColumn = "id"
    )
    val profile: FocusProfileEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "session_id"
    )
    val segments: List<FocusSessionSegmentEntity>
) {
    fun toActiveSnapshot(): ActiveSessionSnapshot {
        val sortedSegments = segments.sortedBy { it.segmentIndex }.map { it.toDomain() }
        val openSegment = sortedSegments.lastOrNull { it.durationMs == null && it.endedAtWallMs == null }
        return ActiveSessionSnapshot(
            session = session.toDomain(),
            profile = profile.toDomain(),
            segments = sortedSegments,
            openSegment = openSegment
        )
    }
}

data class FocusSessionWithProfile(
    @Embedded
    val session: FocusSessionEntity,
    @Relation(
        parentColumn = "profile_id",
        entityColumn = "id"
    )
    val profile: FocusProfileEntity
)
