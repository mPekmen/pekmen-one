package net.pekmen.one.core.platform.samsung

import android.content.Context
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.platform.R

/**
 * Formats optional machine-readable notification keyword tags `[PekmenFocus:<SLUG>:<STATE>]`
 * for Samsung One UI Modes & Routines ("Notification received -> Keyword in notification").
 *
 * Zero Knox Device Owner or undocumented Samsung APIs are used.
 *
 * One UI Modes & Routines Setup:
 * 1. Triggering a Samsung Routine when Pekmen Focus starts:
 *    - Enable "Routine notification tags" in Pekmen Settings.
 *    - In Samsung Modes & Routines -> Routines -> (+) -> If -> Notification received:
 *      App = Pekmen One, Keyword = `[PekmenFocus:STUDY:RUNNING]` (or `WORK` / `SLEEP`).
 *    - Under Then -> enable Do Not Disturb, Grayscale, Dark mode, or App restrictions.
 * 2. Reverting when paused or ended:
 *    - Create a companion Routine matching `[PekmenFocus:STUDY:PAUSED]` or
 *      `[PekmenFocus:STUDY:STOPPED]` (or rely on Modes & Routines reverse actions).
 * 3. Launching Pekmen One from a Samsung Routine:
 *    - Under Then -> Apps -> Open app or Do app action -> select a Pekmen One dynamic shortcut
 *      (Study Pomodoro, Work Focus, Sleep Custom, or Toggle Focus).
 */
object SamsungRoutineBridgeHelper {

    fun resolveRoutineState(
        status: FocusSessionStatus,
        phase: FocusPhase
    ): RoutineFocusState {
        return when (status) {
            FocusSessionStatus.RUNNING -> if (phase == FocusPhase.FOCUS) {
                RoutineFocusState.RUNNING
            } else {
                RoutineFocusState.BREAK
            }
            FocusSessionStatus.PAUSED -> RoutineFocusState.PAUSED
            FocusSessionStatus.AWAITING_NEXT_PHASE -> RoutineFocusState.AWAITING
            FocusSessionStatus.COMPLETED,
            FocusSessionStatus.ABANDONED -> RoutineFocusState.STOPPED
        }
    }

    fun formatKeywordTag(
        routineKeywordEnabled: Boolean,
        slug: BuiltInProfileSlug,
        state: RoutineFocusState
    ): String? {
        if (!routineKeywordEnabled) return null
        return "[PekmenFocus:${slug.name}:${state.token}]"
    }

    fun formatKeywordTag(
        routineKeywordEnabled: Boolean,
        slug: BuiltInProfileSlug,
        status: FocusSessionStatus,
        phase: FocusPhase
    ): String? {
        return formatKeywordTag(
            routineKeywordEnabled = routineKeywordEnabled,
            slug = slug,
            state = resolveRoutineState(status = status, phase = phase)
        )
    }

    fun appendKeywordSubtext(
        baseSubtext: String?,
        routineKeywordEnabled: Boolean,
        slug: BuiltInProfileSlug,
        status: FocusSessionStatus,
        phase: FocusPhase
    ): String? {
        val tag = formatKeywordTag(
            routineKeywordEnabled = routineKeywordEnabled,
            slug = slug,
            status = status,
            phase = phase
        ) ?: return baseSubtext

        return if (baseSubtext.isNullOrBlank()) {
            tag
        } else {
            "$baseSubtext · $tag"
        }
    }

    fun oneUiSetupSteps(context: Context): List<String> {
        val appContext = context.applicationContext
        return listOf(
            appContext.getString(R.string.routine_guide_step_trigger),
            appContext.getString(R.string.routine_guide_step_effects),
            appContext.getString(R.string.routine_guide_step_shortcut)
        )
    }
}

enum class RoutineFocusState(val token: String) {
    RUNNING("RUNNING"),
    BREAK("BREAK"),
    PAUSED("PAUSED"),
    AWAITING("AWAITING"),
    STOPPED("STOPPED")
}
