package net.pekmen.one.core.cloud.circles

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pekmen.one.core.cloud.CirclesRepository
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.SocialCircleEntity
import net.pekmen.one.core.database.entity.SocialCircleMembershipEntity
import net.pekmen.one.core.database.entity.SocialMemberSummaryEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.CircleMemberStanding
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.CircleRole
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.SocialCircle
import net.pekmen.one.core.model.SubjectSharedTotal
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CirclesRepositoryImpl(
    private val database: PekmenDatabase,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val remoteGateway: CirclesRemoteGateway,
    private val timeProvider: TimeProvider,
    private val gateStatusProvider: () -> CloudRlsGateStatus,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : CirclesRepository, CirclesSyncSupport {

    private val circleDao = database.socialCircleDao()
    private val portabilityDao = database.portabilityDao()
    private val learningDao = database.learningDao()

    private val activeOwnerFlow: Flow<String?> = preferencesDataStore.preferences
        .map { it.activeOwnerUserId?.trim()?.ifEmpty { null } }
        .distinctUntilChanged()

    override val joinedCircles: Flow<List<SocialCircle>> = activeOwnerFlow.flatMapLatest { ownerId ->
        if (ownerId == null) {
            flowOf(emptyList())
        } else {
            circleDao.observeJoinedCircles(ownerId).map { entities ->
                entities.map { it.toDomain() }
            }
        }
    }

    override fun observeMemberships(circleId: String): Flow<List<CircleMembership>> {
        return combine(
            activeOwnerFlow,
            circleDao.observeActiveMemberships(circleId)
        ) { ownerId, rows ->
            if (ownerId == null) {
                emptyList()
            } else {
                val isMember = rows.any { it.userId == ownerId && it.leftAtMs == null }
                if (isMember) rows.map { it.toDomain() } else emptyList()
            }
        }
    }

    override fun observeLeaderboard(
        circleId: String,
        periodType: CirclePeriodType,
        periodStartDate: String
    ): Flow<List<CircleMemberStanding>> {
        return combine(
            activeOwnerFlow,
            circleDao.observeActiveMemberships(circleId),
            circleDao.observeSummaries(circleId, periodType.name, periodStartDate)
        ) { ownerId, memberships, summaries ->
            if (ownerId == null) {
                emptyList()
            } else {
                val activeMemberships = memberships
                    .filter { it.leftAtMs == null }
                    .associateBy { it.userId }
                if (ownerId !in activeMemberships) {
                    emptyList()
                } else {
                    val eligible = summaries.filter { summary ->
                        activeMemberships[summary.userId]?.shareEnabled == true
                    }
                    eligible
                        .sortedWith(
                            compareByDescending<SocialMemberSummaryEntity> {
                                it.liveTimerFocusSec + it.manualEntryFocusSec
                            }.thenBy { it.userId }
                        )
                        .mapIndexed { index, entity ->
                            entity.toStanding(rank = index + 1)
                        }
                }
            }
        }
    }

    override suspend fun createCircle(
        name: String,
        creatorDisplayName: String
    ): Result<SocialCircle> = runCatching {
        requireCloudGateEnabled()
        val userId = requireSignedInUserId()
        val nowMs = timeProvider.wallTimeMs()
        val inviteCode = CircleInviteCodeGenerator.generate()
        val snapshot = remoteGateway.createCircle(
            actingUserId = userId,
            name = name,
            creatorDisplayName = creatorDisplayName,
            inviteCode = inviteCode,
            nowMs = nowMs
        ).getOrThrow()

        persistCircleSnapshot(snapshot, nowMs)
        snapshot.circle
    }

    override suspend fun joinCircle(
        inviteCode: String,
        memberDisplayName: String
    ): Result<SocialCircle> = runCatching {
        requireCloudGateEnabled()
        val userId = requireSignedInUserId()
        val nowMs = timeProvider.wallTimeMs()
        val snapshot = remoteGateway.joinCircle(
            actingUserId = userId,
            inviteCode = inviteCode,
            memberDisplayName = memberDisplayName,
            nowMs = nowMs
        ).getOrThrow()

        val effectiveToday = currentEffectiveDate(nowMs)
        val daily = fetchLeaderboardForDate(userId, snapshot.circle.id, CirclePeriodType.DAILY, effectiveToday)
        val weekly = fetchLeaderboardForDate(userId, snapshot.circle.id, CirclePeriodType.WEEKLY, effectiveToday)
        persistSnapshotAndSummaries(snapshot, daily + weekly, nowMs)
        snapshot.circle
    }

    override suspend fun leaveCircle(circleId: String): Result<Unit> = runCatching {
        requireCloudGateEnabled()
        val userId = requireSignedInUserId()
        val nowMs = timeProvider.wallTimeMs()

        remoteGateway.leaveCircle(
            actingUserId = userId,
            circleId = circleId,
            nowMs = nowMs
        ).getOrThrow()

        database.withTransaction {
            val activeRows = circleDao.observeActiveMemberships(circleId).first()
            val mine = activeRows.firstOrNull { it.userId == userId }
            if (mine != null) {
                circleDao.upsertMembership(
                    mine.copy(
                        leftAtMs = nowMs,
                        shareEnabled = false,
                        shareLiveFocusStatus = false,
                        allowedSubjectTitlesCsv = ""
                    )
                )
            }
            circleDao.deleteMemberSummaries(circleId, userId)
            circleDao.clearCircleSummaries(circleId)
        }
    }

    override suspend fun updateSharingConfig(
        circleId: String,
        config: CircleSharingConfig
    ): Result<Unit> = runCatching {
        requireCloudGateEnabled()
        val userId = requireSignedInUserId()
        val nowMs = timeProvider.wallTimeMs()

        val updatedMembership = remoteGateway.updateSharingConfig(
            actingUserId = userId,
            circleId = circleId,
            config = config,
            nowMs = nowMs
        ).getOrThrow()

        database.withTransaction {
            circleDao.upsertMembership(updatedMembership.toEntity())
            if (!updatedMembership.sharingConfig.shareEnabled) {
                circleDao.deleteMemberSummaries(circleId, userId)
            }
        }

        val effectiveToday = currentEffectiveDate(nowMs)
        val daily = fetchLeaderboardForDate(userId, circleId, CirclePeriodType.DAILY, effectiveToday)
        val weekly = fetchLeaderboardForDate(userId, circleId, CirclePeriodType.WEEKLY, effectiveToday)
        replaceCircleSummaries(circleId, daily + weekly)
    }

    override suspend fun publishLocalSummarySnapshot(
        circleId: String,
        isCurrentlyFocusing: Boolean
    ): Result<Unit> = runCatching {
        requireCloudGateEnabled()
        val userId = requireSignedInUserId()
        val nowMs = timeProvider.wallTimeMs()
        val prefs = preferencesDataStore.preferences.first()
        val effectiveToday = EffectiveDayCalculator.effectiveDateFor(
            epochMs = nowMs,
            zoneId = timeProvider.zoneId(),
            rolloverHour = prefs.dayRolloverHour
        )

        val remoteDetails = remoteGateway.fetchCircleDetails(userId, circleId).getOrThrow()
        val myMembership = remoteDetails.memberships.firstOrNull {
            it.userId == userId && it.leftAtMs == null
        } ?: throw CircleAccessDeniedException("Active membership required to publish summary")

        if (!myMembership.sharingConfig.shareEnabled) {
            remoteGateway.deleteMySummary(userId, circleId).getOrThrow()
            database.withTransaction {
                circleDao.upsertMembership(myMembership.toEntity())
                circleDao.deleteMemberSummaries(circleId, userId)
            }
        } else {
            val dailyWindow = CircleSummaryProjector.periodWindow(CirclePeriodType.DAILY, effectiveToday)
            val weeklyWindow = CircleSummaryProjector.periodWindow(CirclePeriodType.WEEKLY, effectiveToday)
            val sessions = portabilityDao.getCompletedSessionsOnce(
                startDate = weeklyWindow.startDate.toString(),
                endDate = weeklyWindow.endDate.toString(),
                ownerUserId = userId
            ).map { it.session.toDomain() }
            val subjectsById = learningDao.getAllActiveSubjects()
                .filter { it.sync.ownerUserId == userId }
                .associate { it.id to it.toDomain() }

            val dailyProjected = CircleSummaryProjector.project(
                circleId = circleId,
                membership = myMembership,
                periodWindow = dailyWindow,
                completedSessions = sessions,
                subjectsById = subjectsById,
                activeFocusRunning = isCurrentlyFocusing,
                nowMs = nowMs
            )
            val weeklyProjected = CircleSummaryProjector.project(
                circleId = circleId,
                membership = myMembership,
                periodWindow = weeklyWindow,
                completedSessions = sessions,
                subjectsById = subjectsById,
                activeFocusRunning = isCurrentlyFocusing,
                nowMs = nowMs
            )

            dailyProjected?.let {
                remoteGateway.publishSummary(userId, it.toRemoteDto(UuidV7.generate(nowMs))).getOrThrow()
            }
            weeklyProjected?.let {
                remoteGateway.publishSummary(userId, it.toRemoteDto(UuidV7.generate(nowMs + 1L))).getOrThrow()
            }

            val daily = fetchLeaderboardForDate(userId, circleId, CirclePeriodType.DAILY, effectiveToday)
            val weekly = fetchLeaderboardForDate(userId, circleId, CirclePeriodType.WEEKLY, effectiveToday)
            persistSnapshotAndSummaries(remoteDetails, daily + weekly, nowMs)
        }
    }

    override suspend fun refreshCircle(circleId: String): Result<Unit> = runCatching {
        requireCloudGateEnabled()
        val userId = requireSignedInUserId()
        val nowMs = timeProvider.wallTimeMs()
        val snapshot = remoteGateway.fetchCircleDetails(userId, circleId).getOrThrow()
        val effectiveToday = currentEffectiveDate(nowMs)
        val daily = fetchLeaderboardForDate(userId, circleId, CirclePeriodType.DAILY, effectiveToday)
        val weekly = fetchLeaderboardForDate(userId, circleId, CirclePeriodType.WEEKLY, effectiveToday)
        persistSnapshotAndSummaries(snapshot, daily + weekly, nowMs)
    }

    override suspend fun refreshJoinedCircles(): Result<Unit> = runCatching {
        requireCloudGateEnabled()
        val userId = requireSignedInUserId()
        val nowMs = timeProvider.wallTimeMs()
        val snapshots = remoteGateway.fetchJoinedCircles(userId).getOrThrow()
        val effectiveToday = currentEffectiveDate(nowMs)
        for (snapshot in snapshots) {
            val daily = fetchLeaderboardForDate(userId, snapshot.circle.id, CirclePeriodType.DAILY, effectiveToday)
            val weekly = fetchLeaderboardForDate(userId, snapshot.circle.id, CirclePeriodType.WEEKLY, effectiveToday)
            persistSnapshotAndSummaries(snapshot, daily + weekly, nowMs)
        }
    }

    private fun requireCloudGateEnabled() {
        val status = gateStatusProvider()
        if (!status.isLiveCloudEnabled) {
            throw CirclesGatedException(status)
        }
    }

    private suspend fun requireSignedInUserId(): String {
        return preferencesDataStore.preferences.first().activeOwnerUserId
            ?.trim()
            ?.ifEmpty { null }
            ?: throw CirclesUnauthenticatedException()
    }

    private suspend fun currentEffectiveDate(nowMs: Long): LocalDate {
        val prefs = preferencesDataStore.preferences.first()
        return EffectiveDayCalculator.effectiveDateFor(
            epochMs = nowMs,
            zoneId = timeProvider.zoneId(),
            rolloverHour = prefs.dayRolloverHour
        )
    }

    private suspend fun fetchLeaderboardForDate(
        userId: String,
        circleId: String,
        periodType: CirclePeriodType,
        anchorDate: LocalDate
    ): List<RemoteMemberSummaryDto> {
        val window = CircleSummaryProjector.periodWindow(periodType, anchorDate)
        return remoteGateway.fetchLeaderboard(
            actingUserId = userId,
            circleId = circleId,
            periodType = periodType,
            periodStartDate = window.periodStartDate
        ).getOrThrow()
    }

    private suspend fun persistCircleSnapshot(snapshot: RemoteCircleSnapshot, nowMs: Long) {
        database.withTransaction {
            upsertSnapshotMemberships(snapshot, nowMs)
        }
    }

    private suspend fun persistSnapshotAndSummaries(
        snapshot: RemoteCircleSnapshot,
        summaries: List<RemoteMemberSummaryDto>,
        nowMs: Long
    ) {
        database.withTransaction {
            upsertSnapshotMemberships(snapshot, nowMs)
            circleDao.clearCircleSummaries(snapshot.circle.id)
            if (summaries.isNotEmpty()) {
                circleDao.upsertSummaries(summaries.map { it.toEntity() })
            }
        }
    }

    private suspend fun replaceCircleSummaries(
        circleId: String,
        summaries: List<RemoteMemberSummaryDto>
    ) {
        database.withTransaction {
            circleDao.clearCircleSummaries(circleId)
            if (summaries.isNotEmpty()) {
                circleDao.upsertSummaries(summaries.map { it.toEntity() })
            }
        }
    }

    private suspend fun upsertSnapshotMemberships(snapshot: RemoteCircleSnapshot, nowMs: Long) {
        circleDao.upsertCircle(snapshot.circle.toEntity())
        val remoteIds = snapshot.memberships.map { it.userId }.toSet()
        val existingLocalActive = circleDao.observeActiveMemberships(snapshot.circle.id).first()
        for (local in existingLocalActive) {
            if (local.userId !in remoteIds) {
                circleDao.upsertMembership(
                    local.copy(
                        leftAtMs = nowMs,
                        shareEnabled = false,
                        shareLiveFocusStatus = false,
                        allowedSubjectTitlesCsv = ""
                    )
                )
            }
        }
        for (membership in snapshot.memberships) {
            circleDao.upsertMembership(membership.toEntity())
        }
    }

    private fun SocialCircleEntity.toDomain(): SocialCircle = SocialCircle(
        id = id,
        name = name,
        inviteCode = inviteCode,
        createdByUserId = createdByUserId,
        createdAtMs = createdAtMs
    )

    private fun SocialCircle.toEntity(): SocialCircleEntity = SocialCircleEntity(
        id = id,
        name = name,
        inviteCode = inviteCode,
        createdByUserId = createdByUserId,
        createdAtMs = createdAtMs,
        deletedAtMs = null
    )

    private fun SocialCircleMembershipEntity.toDomain(): CircleMembership = CircleMembership(
        id = id,
        circleId = circleId,
        userId = userId,
        displayName = displayName,
        role = CircleRole.entries.firstOrNull { it.name == role } ?: CircleRole.MEMBER,
        sharingConfig = CircleSharingConfig(
            shareEnabled = shareEnabled,
            shareLiveFocusStatus = shareLiveFocusStatus,
            allowedSubjectTitles = decodeAllowedSubjects(allowedSubjectTitlesCsv)
        ),
        joinedAtMs = joinedAtMs,
        leftAtMs = leftAtMs
    )

    private fun CircleMembership.toEntity(): SocialCircleMembershipEntity = SocialCircleMembershipEntity(
        id = id,
        circleId = circleId,
        userId = userId,
        displayName = displayName,
        role = role.name,
        shareEnabled = sharingConfig.shareEnabled,
        shareLiveFocusStatus = sharingConfig.shareLiveFocusStatus,
        allowedSubjectTitlesCsv = encodeAllowedSubjects(sharingConfig.allowedSubjectTitles),
        joinedAtMs = joinedAtMs,
        leftAtMs = leftAtMs
    )

    private fun RemoteMemberSummaryDto.toEntity(): SocialMemberSummaryEntity = SocialMemberSummaryEntity(
        id = id,
        circleId = circleId,
        userId = userId,
        displayName = displayName,
        periodType = periodType.name,
        periodStartDate = periodStartDate,
        liveTimerFocusSec = liveTimerFocusSec,
        manualEntryFocusSec = manualEntryFocusSec,
        subjectTotalsJson = json.encodeToString(subjectTotals),
        isCurrentlyFocusing = isCurrentlyFocusing,
        updatedAtMs = updatedAtMs
    )

    private fun SocialMemberSummaryEntity.toStanding(rank: Int): CircleMemberStanding {
        val decodedSubjects = runCatching {
            json.decodeFromString<List<RemoteSubjectTotalDto>>(subjectTotalsJson)
        }.getOrDefault(emptyList())
        return CircleMemberStanding(
            rank = rank,
            circleId = circleId,
            userId = userId,
            displayName = displayName,
            periodType = CirclePeriodType.entries.firstOrNull { it.name == periodType }
                ?: CirclePeriodType.DAILY,
            periodStartDate = periodStartDate,
            liveTimerFocusSec = liveTimerFocusSec,
            manualEntryFocusSec = manualEntryFocusSec,
            subjectTotals = decodedSubjects.map {
                SubjectSharedTotal(
                    subjectTitle = it.subjectTitle,
                    focusSeconds = it.focusSeconds
                )
            },
            isCurrentlyFocusing = isCurrentlyFocusing,
            updatedAtMs = updatedAtMs
        )
    }

    private fun encodeAllowedSubjects(titles: Set<String>): String {
        val cleaned = titles.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        return if (cleaned.isEmpty()) "" else json.encodeToString(cleaned)
    }

    private fun decodeAllowedSubjects(raw: String): Set<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptySet()
        return if (trimmed.startsWith("[")) {
            runCatching { json.decodeFromString<List<String>>(trimmed) }
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } else {
            trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }
}
