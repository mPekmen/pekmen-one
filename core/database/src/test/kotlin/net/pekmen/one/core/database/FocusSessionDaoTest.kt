package net.pekmen.one.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.UuidV7
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FocusSessionDaoTest {

    private lateinit var database: PekmenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PekmenDatabase.createInMemory(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `active session query returns details and excludes soft deleted or completed sessions`() = runTest {
        val now = 1_760_000_000_000L
        val profile = BuiltInProfiles.defaults().first().let {
            FocusProfileEntity(
                id = it.id,
                builtInSlug = it.builtInSlug.name,
                customName = null,
                colorHex = it.colorHex,
                iconKey = it.iconKey,
                defaultMode = it.defaultMode.name,
                workMinutes = it.workMinutes,
                shortBreakMinutes = it.shortBreakMinutes,
                longBreakMinutes = it.longBreakMinutes,
                cyclesBeforeLongBreak = it.cyclesBeforeLongBreak,
                dndEnabled = it.dndEnabled,
                sortOrder = it.sortOrder,
                isArchived = false,
                sync = SyncMetadataEntity(createdAtMs = now, clientUpdatedAtMs = now)
            )
        }
        database.focusProfileDao().insertAllIgnore(listOf(profile))

        val sessionId = UuidV7.generate(now)
        val session = FocusSessionEntity(
            id = sessionId,
            profileId = profile.id,
            sessionMode = FocusSessionMode.POMODORO.name,
            status = FocusSessionStatus.RUNNING.name,
            currentPhase = FocusPhase.FOCUS.name,
            completedFocusCycles = 0,
            startedAtWallMs = now,
            effectiveDayDate = "2026-11-19",
            plannedFocusSec = 1500L,
            actualFocusSec = 0L,
            actualBreakSec = 0L,
            pauseCount = 0,
            sync = SyncMetadataEntity(createdAtMs = now, clientUpdatedAtMs = now)
        )
        val segment = FocusSessionSegmentEntity(
            id = UuidV7.generate(now + 1),
            sessionId = sessionId,
            segmentIndex = 0,
            phase = FocusPhase.FOCUS.name,
            startedAtWallMs = now,
            startedAtElapsedMs = 10_000L,
            bootCount = 1,
            lastCheckpointedDurationMs = 0L,
            plannedDurationMs = 1_500_000L
        )
        database.focusSessionDao().insertSession(session)
        database.focusSessionDao().insertSegment(segment)

        val active = database.focusSessionDao().observeActiveSession().first()
        assertNotNull(active)
        assertEquals(1, active?.segments?.size)
        assertEquals(1, database.focusSessionDao().countActiveSessions())

        database.focusSessionDao().softDeleteSession(sessionId, now + 5000L, now + 5000L)
        assertNull(database.focusSessionDao().observeActiveSession().first())
        assertEquals(0, database.focusSessionDao().countActiveSessions())
    }
}
