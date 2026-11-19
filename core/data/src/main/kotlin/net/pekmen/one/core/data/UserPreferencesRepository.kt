package net.pekmen.one.core.data

import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.UserPreferences

interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setThemePreference(preference: ThemePreference)
    suspend fun setDayRolloverHour(hour: Int)
    suspend fun setSelectedProfileId(profileId: String?)
    suspend fun setAutoStartBreaks(enabled: Boolean)
    suspend fun setAutoStartNextFocus(enabled: Boolean)
    suspend fun setDefaultCustomMinutes(minutes: Int)
    suspend fun setCustomBreakEnabled(enabled: Boolean)
    suspend fun setActiveOwnerUserId(ownerUserId: String?)
    suspend fun setZenIntegrationEnabled(enabled: Boolean)
    suspend fun setRoutineKeywordEnabled(enabled: Boolean)
}
