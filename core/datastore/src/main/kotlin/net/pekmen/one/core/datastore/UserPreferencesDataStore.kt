package net.pekmen.one.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.UserPreferences
import java.io.IOException

private val Context.pekmenPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pekmen_user_preferences"
)

class UserPreferencesDataStore(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.pekmenPreferencesDataStore)

    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserPreferences(
                themePreference = prefs[KEY_THEME]
                    ?.let { raw -> ThemePreference.entries.firstOrNull { it.name == raw } }
                    ?: ThemePreference.SYSTEM,
                dayRolloverHour = (prefs[KEY_DAY_ROLLOVER_HOUR] ?: EffectiveDayCalculator.DEFAULT_ROLLOVER_HOUR)
                    .coerceIn(
                        EffectiveDayCalculator.MIN_ROLLOVER_HOUR,
                        EffectiveDayCalculator.MAX_ROLLOVER_HOUR
                    ),
                selectedProfileId = prefs[KEY_SELECTED_PROFILE_ID],
                autoStartBreaks = prefs[KEY_AUTO_START_BREAKS] ?: false,
                autoStartNextFocus = prefs[KEY_AUTO_START_NEXT_FOCUS] ?: false,
                defaultCustomMinutes = (prefs[KEY_DEFAULT_CUSTOM_MINUTES] ?: DEFAULT_CUSTOM_MINUTES)
                    .coerceIn(MIN_CUSTOM_MINUTES, MAX_CUSTOM_MINUTES),
                activeOwnerUserId = prefs[KEY_ACTIVE_OWNER_USER_ID],
                zenIntegrationEnabled = prefs[KEY_ZEN_INTEGRATION_ENABLED] ?: false,
                routineKeywordEnabled = prefs[KEY_ROUTINE_KEYWORD_ENABLED] ?: false
            )
        }

    suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { it[KEY_THEME] = preference.name }
    }

    suspend fun setDayRolloverHour(hour: Int) {
        val clamped = hour.coerceIn(
            EffectiveDayCalculator.MIN_ROLLOVER_HOUR,
            EffectiveDayCalculator.MAX_ROLLOVER_HOUR
        )
        dataStore.edit { it[KEY_DAY_ROLLOVER_HOUR] = clamped }
    }

    suspend fun setSelectedProfileId(profileId: String?) {
        dataStore.edit { prefs ->
            if (profileId == null) {
                prefs.remove(KEY_SELECTED_PROFILE_ID)
            } else {
                prefs[KEY_SELECTED_PROFILE_ID] = profileId
            }
        }
    }

    suspend fun setAutoStartBreaks(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_START_BREAKS] = enabled }
    }

    suspend fun setAutoStartNextFocus(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_START_NEXT_FOCUS] = enabled }
    }

    suspend fun setDefaultCustomMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_CUSTOM_MINUTES, MAX_CUSTOM_MINUTES)
        dataStore.edit { it[KEY_DEFAULT_CUSTOM_MINUTES] = clamped }
    }

    suspend fun setActiveOwnerUserId(ownerUserId: String?) {
        val normalized = ownerUserId?.trim()?.ifEmpty { null }
        dataStore.edit { prefs ->
            if (normalized == null) {
                prefs.remove(KEY_ACTIVE_OWNER_USER_ID)
            } else {
                prefs[KEY_ACTIVE_OWNER_USER_ID] = normalized
            }
        }
    }

    suspend fun setZenIntegrationEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ZEN_INTEGRATION_ENABLED] = enabled }
    }

    suspend fun setRoutineKeywordEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ROUTINE_KEYWORD_ENABLED] = enabled }
    }

    private companion object {
        const val DEFAULT_CUSTOM_MINUTES = 45
        const val MIN_CUSTOM_MINUTES = 1
        const val MAX_CUSTOM_MINUTES = 480

        val KEY_THEME = stringPreferencesKey("theme_preference")
        val KEY_DAY_ROLLOVER_HOUR = intPreferencesKey("day_rollover_hour")
        val KEY_SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        val KEY_AUTO_START_BREAKS = booleanPreferencesKey("auto_start_breaks")
        val KEY_AUTO_START_NEXT_FOCUS = booleanPreferencesKey("auto_start_next_focus")
        val KEY_DEFAULT_CUSTOM_MINUTES = intPreferencesKey("default_custom_minutes")
        val KEY_ACTIVE_OWNER_USER_ID = stringPreferencesKey("active_owner_user_id")
        val KEY_ZEN_INTEGRATION_ENABLED = booleanPreferencesKey("zen_integration_enabled")
        val KEY_ROUTINE_KEYWORD_ENABLED = booleanPreferencesKey("routine_keyword_enabled")
    }
}
