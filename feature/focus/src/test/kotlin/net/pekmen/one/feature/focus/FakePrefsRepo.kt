package net.pekmen.one.feature.focus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.UserPreferences

internal class FakePrefsRepo : UserPreferencesRepository {
    override val preferences: Flow<UserPreferences> = flowOf(UserPreferences())
    override suspend fun setThemePreference(preference: ThemePreference) = Unit
    override suspend fun setDayRolloverHour(hour: Int) = Unit
    override suspend fun setSelectedProfileId(profileId: String?) = Unit
    override suspend fun setAutoStartBreaks(enabled: Boolean) = Unit
    override suspend fun setAutoStartNextFocus(enabled: Boolean) = Unit
    override suspend fun setDefaultCustomMinutes(minutes: Int) = Unit
    override suspend fun setCustomBreakEnabled(enabled: Boolean) = Unit
    override suspend fun setActiveOwnerUserId(ownerUserId: String?) = Unit
    override suspend fun setZenIntegrationEnabled(enabled: Boolean) = Unit
    override suspend fun setRoutineKeywordEnabled(enabled: Boolean) = Unit
}
