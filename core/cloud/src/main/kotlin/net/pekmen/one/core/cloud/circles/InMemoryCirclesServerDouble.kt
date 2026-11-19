package net.pekmen.one.core.cloud.circles

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleRole
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.SocialCircle
import net.pekmen.one.core.model.UuidV7
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stateful in-memory server simulation mirroring PostgreSQL + strict RLS rules for M6 Private Circles.
 * Every read and write validates the acting user's active membership and sharing opt-in state.
 */
class InMemoryCirclesServerDouble(
    private val json: Json = Json
) : CirclesRemoteGateway {

    private val mutex = Mutex()
    private val callCounter = AtomicInteger(0)
    private val circlesById = linkedMapOf<String, StoredCircle>()
    private val memberships = linkedMapOf<MembershipKey, CircleMembership>()
    private val summaries = linkedMapOf<SummaryKey, RemoteMemberSummaryDto>()

    fun totalGatewayCalls(): Int = callCounter.get()

    suspend fun storedSummaryCount(circleId: String): Int = mutex.withLock {
        summaries.values.count { it.circleId == circleId }
    }

    suspend fun dumpCircleSummariesJson(circleId: String): String = mutex.withLock {
        val list = summaries.values.filter { it.circleId == circleId }
        json.encodeToString(list)
    }

    override suspend fun createCircle(
        actingUserId: String,
        name: String,
        creatorDisplayName: String,
        inviteCode: String,
        nowMs: Long
    ): Result<RemoteCircleSnapshot> = withLockedResult {
        val trimmedName = name.trim()
        val trimmedDisplay = creatorDisplayName.trim()
        val normalizedInvite = CircleInviteCodeGenerator.normalize(inviteCode)
        require(trimmedName.isNotEmpty()) { "Circle name cannot be empty" }
        require(trimmedDisplay.isNotEmpty()) { "Display name cannot be empty" }
        require(normalizedInvite.length >= MIN_INVITE_CODE_LENGTH) { "Invite code too short" }
        check(circlesById.values.none { it.deletedAtMs == null && it.circle.inviteCode == normalizedInvite }) {
            "Invite code collision"
        }

        val circleId = UuidV7.generate(nowMs)
        val circle = SocialCircle(
            id = circleId,
            name = trimmedName,
            inviteCode = normalizedInvite,
            createdByUserId = actingUserId,
            createdAtMs = nowMs
        )
        val creatorMembership = CircleMembership(
            id = UuidV7.generate(nowMs + 1L),
            circleId = circleId,
            userId = actingUserId,
            displayName = trimmedDisplay,
            role = CircleRole.OWNER,
            sharingConfig = CircleSharingConfig(
                shareEnabled = false,
                shareLiveFocusStatus = false,
                allowedSubjectTitles = emptySet()
            ),
            joinedAtMs = nowMs,
            leftAtMs = null
        )
        circlesById[circleId] = StoredCircle(circle = circle, deletedAtMs = null)
        memberships[MembershipKey(circleId, actingUserId)] = creatorMembership
        RemoteCircleSnapshot(circle = circle, memberships = listOf(creatorMembership))
    }

    override suspend fun joinCircle(
        actingUserId: String,
        inviteCode: String,
        memberDisplayName: String,
        nowMs: Long
    ): Result<RemoteCircleSnapshot> = withLockedResult {
        val trimmedDisplay = memberDisplayName.trim()
        require(trimmedDisplay.isNotEmpty()) { "Display name cannot be empty" }
        val normalizedInvite = CircleInviteCodeGenerator.normalize(inviteCode)
        val stored = circlesById.values.firstOrNull {
            it.deletedAtMs == null && it.circle.inviteCode == normalizedInvite
        } ?: throw CircleNotFoundException("No active circle matches invite code")

        val key = MembershipKey(stored.circle.id, actingUserId)
        val existing = memberships[key]
        val membership = if (existing != null && existing.leftAtMs == null) {
            existing.copy(displayName = trimmedDisplay)
        } else {
            CircleMembership(
                id = existing?.id ?: UuidV7.generate(nowMs),
                circleId = stored.circle.id,
                userId = actingUserId,
                displayName = trimmedDisplay,
                role = existing?.role ?: CircleRole.MEMBER,
                sharingConfig = CircleSharingConfig(
                    shareEnabled = false,
                    shareLiveFocusStatus = false,
                    allowedSubjectTitles = emptySet()
                ),
                joinedAtMs = nowMs,
                leftAtMs = null
            )
        }
        memberships[key] = membership
        RemoteCircleSnapshot(
            circle = stored.circle,
            memberships = activeMembershipsFor(stored.circle.id)
        )
    }

    override suspend fun leaveCircle(
        actingUserId: String,
        circleId: String,
        nowMs: Long
    ): Result<Unit> = withLockedResult {
        val existing = requireActiveMembership(actingUserId, circleId)
        memberships[MembershipKey(circleId, actingUserId)] = existing.copy(
            leftAtMs = nowMs,
            sharingConfig = CircleSharingConfig(
                shareEnabled = false,
                shareLiveFocusStatus = false,
                allowedSubjectTitles = emptySet()
            )
        )
        purgeUserSummaries(circleId = circleId, userId = actingUserId)
    }

    override suspend fun updateSharingConfig(
        actingUserId: String,
        circleId: String,
        config: CircleSharingConfig,
        nowMs: Long
    ): Result<CircleMembership> = withLockedResult {
        val existing = requireActiveMembership(actingUserId, circleId)
        val normalizedTitles = config.allowedSubjectTitles
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val normalizedConfig = CircleSharingConfig(
            shareEnabled = config.shareEnabled,
            shareLiveFocusStatus = config.shareEnabled && config.shareLiveFocusStatus,
            allowedSubjectTitles = normalizedTitles
        )
        val updated = existing.copy(sharingConfig = normalizedConfig)
        memberships[MembershipKey(circleId, actingUserId)] = updated

        if (!normalizedConfig.shareEnabled) {
            purgeUserSummaries(circleId = circleId, userId = actingUserId)
        } else {
            sanitizeUserSummaries(circleId = circleId, userId = actingUserId, config = normalizedConfig)
        }
        updated
    }

    override suspend fun publishSummary(
        actingUserId: String,
        summary: RemoteMemberSummaryDto
    ): Result<Unit> = withLockedResult {
        if (summary.userId != actingUserId) {
            throw CircleAccessDeniedException("Cannot publish summary for another user")
        }
        val membership = requireActiveMembership(actingUserId, summary.circleId)
        if (!membership.sharingConfig.shareEnabled) {
            throw CircleAccessDeniedException("Cannot publish summary while shareEnabled is false")
        }
        val sanitized = sanitizeSummary(summary, membership.sharingConfig)
        val key = SummaryKey(
            circleId = sanitized.circleId,
            userId = sanitized.userId,
            periodType = sanitized.periodType,
            periodStartDate = sanitized.periodStartDate
        )
        summaries[key] = sanitized
    }

    override suspend fun deleteMySummary(
        actingUserId: String,
        circleId: String
    ): Result<Unit> = withLockedResult {
        requireActiveMembership(actingUserId, circleId)
        purgeUserSummaries(circleId = circleId, userId = actingUserId)
    }

    override suspend fun fetchCircleDetails(
        actingUserId: String,
        circleId: String
    ): Result<RemoteCircleSnapshot> = withLockedResult {
        requireActiveMembership(actingUserId, circleId)
        val stored = circlesById[circleId]
            ?.takeIf { it.deletedAtMs == null }
            ?: throw CircleNotFoundException("Circle not found")
        RemoteCircleSnapshot(
            circle = stored.circle,
            memberships = activeMembershipsFor(circleId)
        )
    }

    override suspend fun fetchJoinedCircles(
        actingUserId: String
    ): Result<List<RemoteCircleSnapshot>> = withLockedResult {
        val activeCircleIds = memberships.values
            .filter { it.userId == actingUserId && it.leftAtMs == null }
            .map { it.circleId }
            .toSet()

        circlesById.values
            .filter { it.deletedAtMs == null && it.circle.id in activeCircleIds }
            .sortedByDescending { it.circle.createdAtMs }
            .map { stored ->
                RemoteCircleSnapshot(
                    circle = stored.circle,
                    memberships = activeMembershipsFor(stored.circle.id)
                )
            }
    }

    override suspend fun fetchLeaderboard(
        actingUserId: String,
        circleId: String,
        periodType: CirclePeriodType,
        periodStartDate: String
    ): Result<List<RemoteMemberSummaryDto>> = withLockedResult {
        requireActiveMembership(actingUserId, circleId)
        val activeSharingUsers = memberships.values
            .filter {
                it.circleId == circleId &&
                    it.leftAtMs == null &&
                    it.sharingConfig.shareEnabled
            }
            .associateBy { it.userId }

        summaries.values
            .filter { summary ->
                summary.circleId == circleId &&
                    summary.periodType == periodType &&
                    summary.periodStartDate == periodStartDate &&
                    summary.userId in activeSharingUsers
            }
            .map { summary ->
                val config = activeSharingUsers.getValue(summary.userId).sharingConfig
                sanitizeSummary(summary, config)
            }
            .sortedWith(
                compareByDescending<RemoteMemberSummaryDto> {
                    it.liveTimerFocusSec + it.manualEntryFocusSec
                }.thenBy { it.userId }
            )
    }

    private suspend fun <T> withLockedResult(
        block: () -> T
    ): Result<T> {
        callCounter.incrementAndGet()
        return mutex.withLock {
            runCatching { block() }
        }
    }

    private fun requireActiveMembership(actingUserId: String, circleId: String): CircleMembership {
        val circle = circlesById[circleId]
        if (circle == null || circle.deletedAtMs != null) {
            throw CircleAccessDeniedException("Circle access denied")
        }
        val membership = memberships[MembershipKey(circleId, actingUserId)]
        if (membership == null || membership.leftAtMs != null) {
            throw CircleAccessDeniedException("Active circle membership required")
        }
        return membership
    }

    private fun activeMembershipsFor(circleId: String): List<CircleMembership> {
        return memberships.values
            .filter { it.circleId == circleId && it.leftAtMs == null }
            .sortedBy { it.joinedAtMs }
    }

    private fun purgeUserSummaries(circleId: String, userId: String) {
        val iterator = summaries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().key
            if (entry.circleId == circleId && entry.userId == userId) {
                iterator.remove()
            }
        }
    }

    private fun sanitizeUserSummaries(
        circleId: String,
        userId: String,
        config: CircleSharingConfig
    ) {
        val matchingKeys = summaries.keys.filter { it.circleId == circleId && it.userId == userId }
        for (key in matchingKeys) {
            val existing = summaries[key] ?: continue
            summaries[key] = sanitizeSummary(existing, config)
        }
    }

    private fun sanitizeSummary(
        summary: RemoteMemberSummaryDto,
        config: CircleSharingConfig
    ): RemoteMemberSummaryDto {
        val allowedSubjects = config.allowedSubjectTitles
        val filteredSubjects = if (allowedSubjects.isEmpty()) {
            emptyList()
        } else {
            summary.subjectTotals.filter { it.subjectTitle.trim() in allowedSubjects }
        }
        return summary.copy(
            subjectTotals = filteredSubjects,
            isCurrentlyFocusing = config.shareLiveFocusStatus && summary.isCurrentlyFocusing
        )
    }

    private data class StoredCircle(
        val circle: SocialCircle,
        val deletedAtMs: Long?
    )

    private data class MembershipKey(
        val circleId: String,
        val userId: String
    )

    private data class SummaryKey(
        val circleId: String,
        val userId: String,
        val periodType: CirclePeriodType,
        val periodStartDate: String
    )

    private companion object {
        const val MIN_INVITE_CODE_LENGTH = 8
    }
}
