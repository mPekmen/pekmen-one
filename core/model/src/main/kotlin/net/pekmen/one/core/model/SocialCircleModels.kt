package net.pekmen.one.core.model

enum class CircleRole {
    OWNER,
    MEMBER
}

enum class CirclePeriodType {
    DAILY,
    WEEKLY
}

data class SocialCircle(
    val id: String,
    val name: String,
    val inviteCode: String,
    val createdByUserId: String,
    val createdAtMs: Long
)

data class CircleMembership(
    val id: String,
    val circleId: String,
    val userId: String,
    val displayName: String,
    val role: CircleRole,
    val sharingConfig: CircleSharingConfig,
    val joinedAtMs: Long,
    val leftAtMs: Long? = null
)

data class CircleSharingConfig(
    val shareEnabled: Boolean = false,
    val shareLiveFocusStatus: Boolean = false,
    val allowedSubjectTitles: Set<String> = emptySet()
)

data class SubjectSharedTotal(
    val subjectTitle: String,
    val focusSeconds: Long
)

data class CircleMemberStanding(
    val rank: Int,
    val circleId: String,
    val userId: String,
    val displayName: String,
    val periodType: CirclePeriodType,
    val periodStartDate: String,
    val liveTimerFocusSec: Long,
    val manualEntryFocusSec: Long,
    val subjectTotals: List<SubjectSharedTotal>,
    val isCurrentlyFocusing: Boolean,
    val updatedAtMs: Long
) {
    val totalSharedFocusSec: Long
        get() = liveTimerFocusSec + manualEntryFocusSec
}
