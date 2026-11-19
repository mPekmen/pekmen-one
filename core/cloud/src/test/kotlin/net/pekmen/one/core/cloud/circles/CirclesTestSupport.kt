package net.pekmen.one.core.cloud.circles

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CircleRole
import net.pekmen.one.core.model.CircleSharingConfig
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

internal data class CirclesTestEnv(
    val scope: TestScope,
    val context: Context,
    val tempFolder: TemporaryFolder,
    val openDatabases: MutableList<PekmenDatabase>,
    val fakeTime: TimeProvider,
    val server: InMemoryCirclesServerDouble
)

internal data class CircleAccountHarness(
    val userId: String,
    val db: PekmenDatabase,
    val dataStore: UserPreferencesDataStore,
    val repo: CirclesRepositoryImpl
)

internal class CircleFakeTimeProvider(
    private var wallMs: Long,
    private var elapsedMs: Long,
    private var boot: Int,
    private val zone: ZoneId
) : TimeProvider {
    override fun wallTimeMs(): Long = wallMs
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun bootCount(): Int = boot
    override fun zoneId(): ZoneId = zone
}

internal object CirclesTestSupport {

    suspend fun buildAccount(
        env: CirclesTestEnv,
        userId: String,
        gateProvider: () -> CloudRlsGateStatus
    ): CircleAccountHarness {
        val db = PekmenDatabase.createInMemory(env.context)
        env.openDatabases.add(db)
        seedBuiltInProfiles(db, env.fakeTime.wallTimeMs())

        val prefsFile = File(env.tempFolder.newFolder(), "$userId.preferences_pb")
        val rawStore = PreferenceDataStoreFactory.create(scope = env.scope.backgroundScope) { prefsFile }
        val dataStore = UserPreferencesDataStore(rawStore)
        dataStore.setActiveOwnerUserId(userId)

        val repo = CirclesRepositoryImpl(
            database = db,
            preferencesDataStore = dataStore,
            remoteGateway = env.server,
            timeProvider = env.fakeTime,
            gateStatusProvider = gateProvider
        )
        return CircleAccountHarness(userId = userId, db = db, dataStore = dataStore, repo = repo)
    }

    private suspend fun seedBuiltInProfiles(db: PekmenDatabase, now: Long) {
        val entities = BuiltInProfiles.defaults().map { profile ->
            FocusProfileEntity(
                id = profile.id,
                builtInSlug = profile.builtInSlug.name,
                customName = profile.customName,
                colorHex = profile.colorHex,
                iconKey = profile.iconKey,
                defaultMode = profile.defaultMode.name,
                workMinutes = profile.workMinutes,
                shortBreakMinutes = profile.shortBreakMinutes,
                longBreakMinutes = profile.longBreakMinutes,
                cyclesBeforeLongBreak = profile.cyclesBeforeLongBreak,
                dndEnabled = profile.dndEnabled,
                sortOrder = profile.sortOrder,
                isArchived = profile.isArchived,
                sync = SyncMetadataEntity(createdAtMs = now, clientUpdatedAtMs = now)
            )
        }
        db.focusProfileDao().insertAllIgnore(entities)
    }

    suspend fun insertSubject(db: PekmenDatabase, ownerUserId: String, title: String, now: Long): String {
        val id = UuidV7.generate(now)
        db.learningDao().upsertSubject(
            LearningSubjectEntity(
                id = id,
                title = title,
                colorHex = 0xFF8B7CF6,
                weeklyGoalMinutes = 150,
                sync = SyncMetadataEntity(
                    ownerUserId = ownerUserId,
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )
        return id
    }

    suspend fun insertBook(
        db: PekmenDatabase,
        ownerUserId: String,
        subjectId: String,
        title: String,
        now: Long
    ): String {
        val id = UuidV7.generate(now)
        db.learningDao().upsertBook(
            LearningBookEntity(
                id = id,
                subjectId = subjectId,
                title = title,
                totalPages = 240,
                currentPage = 40,
                sync = SyncMetadataEntity(
                    ownerUserId = ownerUserId,
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )
        return id
    }

    suspend fun insertTask(
        db: PekmenDatabase,
        ownerUserId: String,
        subjectId: String,
        title: String,
        now: Long
    ): String {
        val id = UuidV7.generate(now)
        db.plannerTaskDao().upsertTask(
            PlannerTaskEntity(
                id = id,
                title = title,
                subjectId = subjectId,
                sync = SyncMetadataEntity(
                    ownerUserId = ownerUserId,
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )
        return id
    }

    suspend fun insertSession(
        db: PekmenDatabase,
        ownerUserId: String,
        focusSec: Long,
        isManualEntry: Boolean,
        effectiveDate: LocalDate,
        now: Long,
        subjectId: String? = null,
        bookId: String? = null,
        taskId: String? = null,
        rating: Int? = null,
        note: String? = null,
        status: FocusSessionStatus = FocusSessionStatus.COMPLETED
    ) {
        db.focusSessionDao().insertSession(
            FocusSessionEntity(
                id = UuidV7.generate(now),
                profileId = BuiltInProfiles.STUDY_ID,
                subjectId = subjectId,
                bookId = bookId,
                taskId = taskId,
                bookStartPage = if (bookId != null) 40 else null,
                bookEndPage = if (bookId != null) 52 else null,
                sessionMode = FocusSessionMode.POMODORO.name,
                status = status.name,
                currentPhase = FocusPhase.FOCUS.name,
                completedFocusCycles = 1,
                startedAtWallMs = now - focusSec * 1_000L,
                endedAtWallMs = now,
                effectiveDayDate = effectiveDate.toString(),
                plannedFocusSec = focusSec,
                actualFocusSec = focusSec,
                actualBreakSec = 0L,
                pauseCount = 0,
                isManualEntry = isManualEntry,
                focusRating = rating,
                reflectionNote = note,
                sync = SyncMetadataEntity(
                    ownerUserId = ownerUserId,
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )
    }

    fun testMembership(config: CircleSharingConfig) = CircleMembership(
        id = "m-1",
        circleId = "c-1",
        userId = "user-bob",
        displayName = "Bob",
        role = CircleRole.MEMBER,
        sharingConfig = config,
        joinedAtMs = 1_000L,
        leftAtMs = null
    )
}
