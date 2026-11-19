package net.pekmen.one.core.cloud

import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.model.CircleMemberStanding
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.SocialCircle

interface CirclesRepository {
    val joinedCircles: Flow<List<SocialCircle>>

    fun observeMemberships(circleId: String): Flow<List<CircleMembership>>

    fun observeLeaderboard(
        circleId: String,
        periodType: CirclePeriodType,
        periodStartDate: String
    ): Flow<List<CircleMemberStanding>>

    suspend fun createCircle(name: String, creatorDisplayName: String): Result<SocialCircle>
    suspend fun joinCircle(inviteCode: String, memberDisplayName: String): Result<SocialCircle>
    suspend fun leaveCircle(circleId: String): Result<Unit>
    suspend fun updateSharingConfig(circleId: String, config: CircleSharingConfig): Result<Unit>
    suspend fun publishLocalSummarySnapshot(circleId: String, isCurrentlyFocusing: Boolean): Result<Unit>
}
