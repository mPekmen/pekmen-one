package net.pekmen.one.core.data

import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.UserPreferences

class UserPreferencesRepositoryImpl(
    private val dataStore: UserPreferencesDataStore
) : UserPreferencesRepository {

    override val preferences: Flow<UserPreferences> = dataStore.preferences

    override suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.setThemePreference(preference)
    }

    override suspend fun setDayRolloverHour(hour: Int) {
        dataStore.setDayRolloverHour(hour)
    }

    override suspend fun setSelectedProfileId(profileId: String?) {
        dataStore.setSelectedProfileId(profileId)
    }

    override suspend fun setAutoStartBreaks(enabled: Boolean) {
        dataStore.setAutoStartBreaks(enabled)
    }

    override suspend fun setAutoStartNextFocus(enabled: Boolean) {
        dataStore.setAutoStartNextFocus(enabled)
    }

    override suspend fun setDefaultCustomMinutes(minutes: Int) {
        dataStore.setDefaultCustomMinutes(minutes)
    }
}
