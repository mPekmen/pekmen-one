package net.pekmen.one.core.designsystem.format

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.pekmen.one.core.designsystem.R
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.FocusProfile
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object PekmenFormatters {

    fun formatClock(totalSeconds: Long, locale: Locale = Locale.getDefault()): String {
        val safeSec = totalSeconds.coerceAtLeast(0L)
        val hours = safeSec / 3600L
        val minutes = (safeSec % 3600L) / 60L
        val seconds = safeSec % 60L
        return if (hours > 0L) {
            String.format(locale, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(locale, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatHumanDuration(
        context: Context,
        totalSeconds: Long,
        locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    ): String {
        val safeSec = totalSeconds.coerceAtLeast(0L)
        val nf = NumberFormat.getIntegerInstance(locale)
        val totalMinutes = safeSec / 60L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L -> context.getString(
                R.string.duration_hours_minutes,
                nf.format(hours),
                nf.format(minutes)
            )
            totalMinutes > 0L -> context.getString(
                R.string.duration_minutes,
                nf.format(totalMinutes)
            )
            else -> context.getString(
                R.string.duration_seconds,
                nf.format(safeSec)
            )
        }
    }

    fun formatInteger(value: Number, locale: Locale = Locale.getDefault()): String {
        return NumberFormat.getIntegerInstance(locale).format(value)
    }

    fun formatShortTime(
        epochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalTime().format(formatter)
    }

    fun formatMediumDate(
        date: LocalDate,
        locale: Locale = Locale.getDefault()
    ): String {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        return date.format(formatter)
    }

    fun formatShortWeekday(
        date: LocalDate,
        locale: Locale = Locale.getDefault()
    ): String {
        val formatter = DateTimeFormatter.ofPattern("EEE", locale)
        return date.format(formatter)
    }

    fun profileDisplayName(context: Context, profile: FocusProfile): String {
        val custom = profile.customName?.trim()
        if (!custom.isNullOrEmpty()) return custom
        return when (profile.builtInSlug) {
            BuiltInProfileSlug.STUDY -> context.getString(R.string.profile_study)
            BuiltInProfileSlug.WORK -> context.getString(R.string.profile_work)
            BuiltInProfileSlug.SLEEP -> context.getString(R.string.profile_sleep)
            BuiltInProfileSlug.PERSONAL -> context.getString(R.string.profile_personal)
            BuiltInProfileSlug.CUSTOM -> context.getString(R.string.profile_custom)
        }
    }
}

@Composable
fun FocusProfile.localizedName(): String {
    val custom = customName?.trim()
    if (!custom.isNullOrEmpty()) return custom
    return when (builtInSlug) {
        BuiltInProfileSlug.STUDY -> stringResource(R.string.profile_study)
        BuiltInProfileSlug.WORK -> stringResource(R.string.profile_work)
        BuiltInProfileSlug.SLEEP -> stringResource(R.string.profile_sleep)
        BuiltInProfileSlug.PERSONAL -> stringResource(R.string.profile_personal)
        BuiltInProfileSlug.CUSTOM -> stringResource(R.string.profile_custom)
    }
}
