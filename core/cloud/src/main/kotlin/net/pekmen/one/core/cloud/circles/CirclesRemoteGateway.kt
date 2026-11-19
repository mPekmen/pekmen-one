package net.pekmen.one.core.cloud.circles

import kotlinx.serialization.Serializable
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.SocialCircle
import java.security.SecureRandom
import java.util.Locale

@Serializable
data class RemoteSubjectTotalDto(
    val subjectTitle: String,
    val focusSeconds: Long
)

@Serializable
data class RemoteMemberSummaryDto(
    val id: String,
    val circleId: String,
    val userId: String,
    val displayName: String,
    val periodType: CirclePeriodType,
    val periodStartDate: String,
    val liveTimerFocusSec: Long,
    val manualEntryFocusSec: Long,
    val subjectTotals: List<RemoteSubjectTotalDto>,
    val isCurrentlyFocusing: Boolean,
    val updatedAtMs: Long
)

data class RemoteCircleSnapshot(
    val circle: SocialCircle,
    val memberships: List<CircleMembership>
)

class CirclesGatedException(
    val gateStatus: CloudRlsGateStatus
) : IllegalStateException(
    "Live private circles are disabled until cloud flag, endpoint, and RLS verification are enabled"
)

class CirclesUnauthenticatedException(
    message: String = "Sign in is required to access Private Circles"
) : IllegalStateException(message)

class CircleAccessDeniedException(
    message: String
) : SecurityException(message)

class CircleNotFoundException(
    message: String
) : NoSuchElementException(message)

interface CirclesRemoteGateway {
    suspend fun createCircle(
        actingUserId: String,
        name: String,
        creatorDisplayName: String,
        inviteCode: String,
        nowMs: Long
    ): Result<RemoteCircleSnapshot>

    suspend fun joinCircle(
        actingUserId: String,
        inviteCode: String,
        memberDisplayName: String,
        nowMs: Long
    ): Result<RemoteCircleSnapshot>

    suspend fun leaveCircle(
        actingUserId: String,
        circleId: String,
        nowMs: Long
    ): Result<Unit>

    suspend fun updateSharingConfig(
        actingUserId: String,
        circleId: String,
        config: CircleSharingConfig,
        nowMs: Long
    ): Result<CircleMembership>

    suspend fun publishSummary(
        actingUserId: String,
        summary: RemoteMemberSummaryDto
    ): Result<Unit>

    suspend fun deleteMySummary(
        actingUserId: String,
        circleId: String
    ): Result<Unit>

    suspend fun fetchCircleDetails(
        actingUserId: String,
        circleId: String
    ): Result<RemoteCircleSnapshot>

    suspend fun fetchJoinedCircles(
        actingUserId: String
    ): Result<List<RemoteCircleSnapshot>>

    suspend fun fetchLeaderboard(
        actingUserId: String,
        circleId: String,
        periodType: CirclePeriodType,
        periodStartDate: String
    ): Result<List<RemoteMemberSummaryDto>>
}

interface CirclesSyncSupport {
    suspend fun refreshCircle(circleId: String): Result<Unit>
    suspend fun refreshJoinedCircles(): Result<Unit>
}

object CircleInviteCodeGenerator {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val GROUP_COUNT = 4
    private const val GROUP_LENGTH = 4
    private val random = SecureRandom()

    fun generate(): String {
        val groups = List(GROUP_COUNT) {
            buildString(GROUP_LENGTH) {
                repeat(GROUP_LENGTH) {
                    append(ALPHABET[random.nextInt(ALPHABET.length)])
                }
            }
        }
        return groups.joinToString(separator = "-")
    }

    fun normalize(raw: String): String {
        return raw.trim().uppercase(Locale.US)
    }
}
