package net.pekmen.one.feature.learning

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.pekmen.one.core.data.SystemDiagnostics
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.UserPreferences
import java.time.ZoneId

internal class FakePrefsRepo : UserPreferencesRepository {
    private val flow = MutableStateFlow(UserPreferences())
    override val preferences: Flow<UserPreferences> = flow
    override suspend fun setThemePreference(preference: ThemePreference) = Unit
    override suspend fun setDayRolloverHour(hour: Int) = Unit
    override suspend fun setSelectedProfileId(profileId: String?) = Unit
    override suspend fun setAutoStartBreaks(enabled: Boolean) = Unit
    override suspend fun setAutoStartNextFocus(enabled: Boolean) = Unit
    override suspend fun setDefaultCustomMinutes(minutes: Int) = Unit
    override suspend fun setActiveOwnerUserId(ownerUserId: String?) = Unit
    override suspend fun setZenIntegrationEnabled(enabled: Boolean) = Unit
    override suspend fun setRoutineKeywordEnabled(enabled: Boolean) = Unit
}

internal class FakeDiagnosticsRepo : SystemDiagnosticsRepository {
    override fun refreshDiagnostics(): SystemDiagnostics = SystemDiagnostics(true, true)
    override fun currentWallTimeMs(): Long = 1_760_000_000_000L
    override fun currentElapsedRealtimeMs(): Long = 10_000L
    override fun currentBootCount(): Int = 1
    override fun currentZoneId(): ZoneId = ZoneId.of("Europe/Istanbul")
}
