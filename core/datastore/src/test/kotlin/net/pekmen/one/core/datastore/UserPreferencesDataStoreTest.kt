package net.pekmen.one.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.model.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserPreferencesDataStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `updates theme rollover hour and profile selection persistently`() = runTest {
        val testScope = TestScope(testScheduler)
        val file = File(tempFolder.newFolder(), "test.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = testScope.backgroundScope) { file }
        val repo = UserPreferencesDataStore(dataStore)

        val initial = repo.preferences.first()
        assertEquals(ThemePreference.SYSTEM, initial.themePreference)
        assertEquals(4, initial.dayRolloverHour)

        repo.setThemePreference(ThemePreference.DARK)
        repo.setDayRolloverHour(25) // clamped to 12
        repo.setAutoStartBreaks(true)
        repo.setDefaultCustomMinutes(60)

        val updated = repo.preferences.first()
        assertEquals(ThemePreference.DARK, updated.themePreference)
        assertEquals(12, updated.dayRolloverHour)
        assertEquals(true, updated.autoStartBreaks)
        assertEquals(60, updated.defaultCustomMinutes)
    }
}
