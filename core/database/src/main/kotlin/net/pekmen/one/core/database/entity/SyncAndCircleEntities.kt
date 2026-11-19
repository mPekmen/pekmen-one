package net.pekmen.one.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CircleRole
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.SocialCircle

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey
    @ColumnInfo(name = "entity_table")
    val entityTable: String,
    @ColumnInfo(name = "last_server_updated_at_ms")
    val lastServerUpdatedAtMs: Long,
    @ColumnInfo(name = "last_entity_id")
    val lastEntityId: String,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long
)

@Entity(
    tableName = "social_circles",
    indices = [
        Index(value = ["deleted_at_ms"])
    ]
)
data class SocialCircleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "invite_code")
    val inviteCode: String,
    @ColumnInfo(name = "created_by_user_id")
    val createdByUserId: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "deleted_at_ms")
    val deletedAtMs: Long? = null
) {
    fun toDomain(): SocialCircle = SocialCircle(
        id = id,
        name = name,
        inviteCode = inviteCode,
        createdByUserId = createdByUserId,
        createdAtMs = createdAtMs
    )
}

@Entity(
    tableName = "social_circle_memberships",
    indices = [
        Index(value = ["circle_id", "user_id"], unique = true),
        Index(value = ["circle_id"])
    ]
)
data class SocialCircleMembershipEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "circle_id")
    val circleId: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "share_enabled")
    val shareEnabled: Boolean = false,
    @ColumnInfo(name = "share_live_focus_status")
    val shareLiveFocusStatus: Boolean = false,
    @ColumnInfo(name = "allowed_subject_titles_csv")
    val allowedSubjectTitlesCsv: String = "",
    @ColumnInfo(name = "joined_at_ms")
    val joinedAtMs: Long,
    @ColumnInfo(name = "left_at_ms")
    val leftAtMs: Long? = null
) {
    fun toDomain(): CircleMembership {
        val allowed = allowedSubjectTitlesCsv
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return CircleMembership(
            id = id,
            circleId = circleId,
            userId = userId,
            displayName = displayName,
            role = CircleRole.entries.firstOrNull { it.name == role } ?: CircleRole.MEMBER,
            sharingConfig = CircleSharingConfig(
                shareEnabled = shareEnabled,
                shareLiveFocusStatus = shareLiveFocusStatus,
                allowedSubjectTitles = allowed
            ),
            joinedAtMs = joinedAtMs,
            leftAtMs = leftAtMs
        )
    }
}

@Entity(
    tableName = "social_member_summaries",
    indices = [
        Index(value = ["circle_id", "user_id", "period_type", "period_start_date"], unique = true)
    ]
)
data class SocialMemberSummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "circle_id")
    val circleId: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "period_type")
    val periodType: String,
    @ColumnInfo(name = "period_start_date")
    val periodStartDate: String,
    @ColumnInfo(name = "live_timer_focus_sec")
    val liveTimerFocusSec: Long,
    @ColumnInfo(name = "manual_entry_focus_sec")
    val manualEntryFocusSec: Long,
    @ColumnInfo(name = "subject_totals_json")
    val subjectTotalsJson: String,
    @ColumnInfo(name = "is_currently_focusing")
    val isCurrentlyFocusing: Boolean,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long
)
