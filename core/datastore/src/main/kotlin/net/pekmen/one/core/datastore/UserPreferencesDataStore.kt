package net.pekmen.one.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import net.pekmen.one.core.model.AccountId
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.PersonalSetup
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.UserPreferences
import java.io.IOException

private val Context.pekmenPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pekmen_user_preferences"
)

class UserPreferencesDataStore(
    private val dataStore: DataStore<Preferences>,
    private val account: AccountId? = null
) {
    constructor(context: Context) : this(context.applicationContext.pekmenPreferencesDataStore)

    val personalSetup: Flow<PersonalSetup> = dataStore.data.map { prefs ->
        PersonalSetup(
            name = prefs[SETUP_NAME].orEmpty(),
            username = prefs[SETUP_USERNAME].orEmpty(),
            purpose = prefs[SETUP_PURPOSE].orEmpty(),
            categories = prefs[SETUP_CATEGORIES].orEmpty(),
            dailyGoalMinutes = prefs[SETUP_DAILY] ?: 30,
            weeklyGoalMinutes = prefs[SETUP_WEEKLY] ?: 150,
            profileId = prefs[SETUP_PROFILE] ?: BuiltInProfiles.STUDY_ID,
            firstLesson = prefs[SETUP_LESSON].orEmpty(),
            firstLessonId = prefs[SETUP_LESSON_ID],
            step = (prefs[SETUP_STEP] ?: 0).coerceIn(0, 2),
            completed = prefs[SETUP_COMPLETED] ?: false
        )
    }

    suspend fun savePersonalSetup(setup: PersonalSetup, applySelectedProfile: Boolean = false) {
        check(account != null) { "Setup requires an account-scoped store" }
        dataStore.edit { prefs ->
            prefs[SETUP_NAME] = setup.name
            prefs[SETUP_USERNAME] = setup.username
            prefs[SETUP_PURPOSE] = setup.purpose
            prefs[SETUP_CATEGORIES] = setup.categories
            prefs[SETUP_DAILY] = setup.dailyGoalMinutes
            prefs[SETUP_WEEKLY] = setup.weeklyGoalMinutes
            prefs[SETUP_PROFILE] = setup.profileId
            prefs[SETUP_LESSON] = setup.firstLesson
            val firstLessonId = setup.firstLessonId
            if (firstLessonId == null) prefs.remove(SETUP_LESSON_ID)
            else prefs[SETUP_LESSON_ID] = firstLessonId
            prefs[SETUP_STEP] = setup.step
            prefs[SETUP_COMPLETED] = setup.completed
            if (applySelectedProfile) prefs[KEY_SELECTED_PROFILE_ID] = setup.profileId
        }
    }

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
                activeOwnerUserId = account?.value ?: prefs[KEY_ACTIVE_OWNER_USER_ID],
                zenIntegrationEnabled = prefs[KEY_ZEN_INTEGRATION_ENABLED] ?: false,
                routineKeywordEnabled = prefs[KEY_ROUTINE_KEYWORD_ENABLED] ?: false,
                customBreakEnabled = prefs[KEY_CUSTOM_BREAK] ?: false,
                dailyGoalMinutes = prefs[SETUP_DAILY].takeIf { prefs[SETUP_COMPLETED] == true } ?: 0,
                weeklyGoalMinutes = prefs[SETUP_WEEKLY].takeIf { prefs[SETUP_COMPLETED] == true } ?: 0,
                preferredProfileIds = prefs[SETUP_CATEGORIES].takeIf { prefs[SETUP_COMPLETED] == true }.orEmpty()
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

    suspend fun setCustomBreakEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CUSTOM_BREAK] = enabled }
    }

    suspend fun setActiveOwnerUserId(ownerUserId: String?) {
        check(account == null) { "The owner of an account-scoped store cannot change" }
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

    companion object {
        /** Open once per account in the application composition root. */
        fun forAccount(context: Context, account: AccountId, scope: CoroutineScope): UserPreferencesDataStore {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                appContext.preferencesDataStoreFile("pekmen_user_preferences_${account.value}")
            }
            return UserPreferencesDataStore(store, account)
        }

        private const val DEFAULT_CUSTOM_MINUTES = 45
        private const val MIN_CUSTOM_MINUTES = 1
        private const val MAX_CUSTOM_MINUTES = 480

        private val KEY_THEME = stringPreferencesKey("theme_preference")
        private val KEY_DAY_ROLLOVER_HOUR = intPreferencesKey("day_rollover_hour")
        private val KEY_SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        private val KEY_AUTO_START_BREAKS = booleanPreferencesKey("auto_start_breaks")
        private val KEY_AUTO_START_NEXT_FOCUS = booleanPreferencesKey("auto_start_next_focus")
        private val KEY_DEFAULT_CUSTOM_MINUTES = intPreferencesKey("default_custom_minutes")
        private val KEY_CUSTOM_BREAK = booleanPreferencesKey("custom_break")
        private val KEY_ACTIVE_OWNER_USER_ID = stringPreferencesKey("active_owner_user_id")
        private val KEY_ZEN_INTEGRATION_ENABLED = booleanPreferencesKey("zen_integration_enabled")
        private val KEY_ROUTINE_KEYWORD_ENABLED = booleanPreferencesKey("routine_keyword_enabled")
        private val SETUP_NAME = stringPreferencesKey("setup_name")
        private val SETUP_USERNAME = stringPreferencesKey("setup_username")
        private val SETUP_PURPOSE = stringPreferencesKey("setup_purpose")
        private val SETUP_CATEGORIES = stringSetPreferencesKey("setup_categories")
        private val SETUP_DAILY = intPreferencesKey("setup_daily_minutes")
        private val SETUP_WEEKLY = intPreferencesKey("setup_weekly_minutes")
        private val SETUP_PROFILE = stringPreferencesKey("setup_profile")
        private val SETUP_LESSON = stringPreferencesKey("setup_lesson")
        private val SETUP_LESSON_ID = stringPreferencesKey("setup_lesson_id")
        private val SETUP_STEP = intPreferencesKey("setup_step")
        private val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
    }
}
