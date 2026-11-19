package net.pekmen.one.core.platform.samsung

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.platform.R

/**
 * Publishes dynamic launcher shortcuts via [ShortcutManagerCompat] so Samsung Modes & Routines
 * ("App action") and Android launchers can trigger Pekmen One focus presets without Knox/undocumented APIs.
 */
class AppShortcutsPublisher(
    context: Context,
    private val launcherActivityClass: Class<out Activity>
) {

    private val appContext = context.applicationContext

    fun publishDynamicShortcuts(): Boolean {
        return ShortcutManagerCompat.setDynamicShortcuts(appContext, buildShortcuts())
    }

    fun buildShortcuts(): List<ShortcutInfoCompat> {
        return listOf(
            buildProfileShortcut(
                id = SHORTCUT_STUDY_POMODORO,
                action = ACTION_START_STUDY_POMODORO,
                profileId = BuiltInProfiles.STUDY_ID,
                mode = FocusSessionMode.POMODORO,
                shortLabelRes = R.string.shortcut_study_pomodoro_short,
                longLabelRes = R.string.shortcut_study_pomodoro_long
            ),
            buildProfileShortcut(
                id = SHORTCUT_WORK_FOCUS,
                action = ACTION_START_WORK_FOCUS,
                profileId = BuiltInProfiles.WORK_ID,
                mode = FocusSessionMode.POMODORO,
                shortLabelRes = R.string.shortcut_work_focus_short,
                longLabelRes = R.string.shortcut_work_focus_long
            ),
            buildProfileShortcut(
                id = SHORTCUT_SLEEP_CUSTOM,
                action = ACTION_START_SLEEP_CUSTOM,
                profileId = BuiltInProfiles.SLEEP_ID,
                mode = FocusSessionMode.CUSTOM,
                shortLabelRes = R.string.shortcut_sleep_custom_short,
                longLabelRes = R.string.shortcut_sleep_custom_long
            ),
            buildToggleShortcut()
        )
    }

    private fun buildProfileShortcut(
        id: String,
        action: String,
        profileId: String,
        mode: FocusSessionMode,
        shortLabelRes: Int,
        longLabelRes: Int
    ): ShortcutInfoCompat {
        val intent = Intent(appContext, launcherActivityClass).apply {
            this.action = action
            `package` = appContext.packageName
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PROFILE_ID, profileId)
            putExtra(EXTRA_SESSION_MODE, mode.name)
        }
        return ShortcutInfoCompat.Builder(appContext, id)
            .setShortLabel(appContext.getString(shortLabelRes))
            .setLongLabel(appContext.getString(longLabelRes))
            .setIcon(IconCompat.createWithResource(appContext, R.drawable.ic_stat_focus))
            .setIntent(intent)
            .build()
    }

    private fun buildToggleShortcut(): ShortcutInfoCompat {
        val intent = Intent(appContext, launcherActivityClass).apply {
            action = ACTION_TOGGLE_FOCUS
            `package` = appContext.packageName
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return ShortcutInfoCompat.Builder(appContext, SHORTCUT_TOGGLE_FOCUS)
            .setShortLabel(appContext.getString(R.string.shortcut_toggle_focus_short))
            .setLongLabel(appContext.getString(R.string.shortcut_toggle_focus_long))
            .setIcon(IconCompat.createWithResource(appContext, R.drawable.ic_stat_focus))
            .setIntent(intent)
            .build()
    }

    companion object {
        const val SHORTCUT_STUDY_POMODORO = "pekmen_start_study_pomodoro"
        const val SHORTCUT_WORK_FOCUS = "pekmen_start_work_focus"
        const val SHORTCUT_SLEEP_CUSTOM = "pekmen_start_sleep_custom"
        const val SHORTCUT_TOGGLE_FOCUS = "pekmen_toggle_focus"

        const val ACTION_START_STUDY_POMODORO = "net.pekmen.one.action.SHORTCUT_STUDY_POMODORO"
        const val ACTION_START_WORK_FOCUS = "net.pekmen.one.action.SHORTCUT_WORK_FOCUS"
        const val ACTION_START_SLEEP_CUSTOM = "net.pekmen.one.action.SHORTCUT_SLEEP_CUSTOM"
        const val ACTION_TOGGLE_FOCUS = "net.pekmen.one.action.SHORTCUT_TOGGLE_FOCUS"

        const val EXTRA_PROFILE_ID = "extra_shortcut_profile_id"
        const val EXTRA_SESSION_MODE = "extra_shortcut_session_mode"

        fun parseShortcutIntent(intent: Intent?): FocusShortcutCommand? {
            val action = intent?.action ?: return null
            return when (action) {
                ACTION_START_STUDY_POMODORO -> FocusShortcutCommand.StartProfile(
                    profileId = resolveProfileExtra(intent, BuiltInProfiles.STUDY_ID),
                    mode = resolveModeExtra(intent, FocusSessionMode.POMODORO)
                )
                ACTION_START_WORK_FOCUS -> FocusShortcutCommand.StartProfile(
                    profileId = resolveProfileExtra(intent, BuiltInProfiles.WORK_ID),
                    mode = resolveModeExtra(intent, FocusSessionMode.POMODORO)
                )
                ACTION_START_SLEEP_CUSTOM -> FocusShortcutCommand.StartProfile(
                    profileId = resolveProfileExtra(intent, BuiltInProfiles.SLEEP_ID),
                    mode = resolveModeExtra(intent, FocusSessionMode.CUSTOM)
                )
                ACTION_TOGGLE_FOCUS -> FocusShortcutCommand.ToggleActiveFocus
                else -> null
            }
        }

        private fun resolveProfileExtra(intent: Intent, fallbackId: String): String {
            return intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() } ?: fallbackId
        }

        private fun resolveModeExtra(intent: Intent, fallbackMode: FocusSessionMode): FocusSessionMode {
            val raw = intent.getStringExtra(EXTRA_SESSION_MODE) ?: return fallbackMode
            return FocusSessionMode.entries.firstOrNull { it.name == raw } ?: fallbackMode
        }
    }
}

sealed interface FocusShortcutCommand {
    data class StartProfile(
        val profileId: String,
        val mode: FocusSessionMode
    ) : FocusShortcutCommand

    data object ToggleActiveFocus : FocusShortcutCommand
}
