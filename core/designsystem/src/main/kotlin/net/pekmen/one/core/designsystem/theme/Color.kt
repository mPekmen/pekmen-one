package net.pekmen.one.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Midnight Violet (Dark)
val MidnightBackground = Color(0xFF10111A)
val MidnightSurface = Color(0xFF1B1D2A)
val MidnightSurfaceElevated = Color(0xFF252838)
val MidnightSurfaceBright = Color(0xFF2E3246)
val MidnightPrimary = Color(0xFF8B7CF6)
val MidnightOnPrimary = Color(0xFF10111A)
val MidnightPrimaryContainer = Color(0xFF2A2550)
val MidnightOnPrimaryContainer = Color(0xFFE5DEFF)
val MidnightSecondary = Color(0xFF6EE7B7)
val MidnightOnSecondary = Color(0xFF0A291F)
val MidnightSecondaryContainer = Color(0xFF183B30)
val MidnightOnSecondaryContainer = Color(0xFFBDF7DF)
val MidnightTertiary = Color(0xFFF472B6)
val MidnightOnTertiary = Color(0xFF2D0A1D)
val MidnightOnSurface = Color(0xFFF4F4F8)
val MidnightOnSurfaceVariant = Color(0xFFA5A9BE)
val MidnightOutline = Color(0xFF454A66)
val MidnightOutlineVariant = Color(0xFF2E3246)
val MidnightError = Color(0xFFFF6B81)
val MidnightOnError = Color(0xFF2B060E)

// Daylight Violet (Light)
val DaylightBackground = Color(0xFFF6F6FB)
val DaylightSurface = Color(0xFFFFFFFF)
val DaylightSurfaceContainer = Color(0xFFEFEFF8)
val DaylightSurfaceElevated = Color(0xFFE6E6F4)
val DaylightPrimary = Color(0xFF5B4AE6)
val DaylightOnPrimary = Color(0xFFFFFFFF)
val DaylightPrimaryContainer = Color(0xFFE6E1FF)
val DaylightOnPrimaryContainer = Color(0xFF1B1145)
val DaylightSecondary = Color(0xFF0F9D76)
val DaylightOnSecondary = Color(0xFFFFFFFF)
val DaylightSecondaryContainer = Color(0xFFD2F5E8)
val DaylightOnSecondaryContainer = Color(0xFF063829)
val DaylightTertiary = Color(0xFFC02677)
val DaylightOnTertiary = Color(0xFFFFFFFF)
val DaylightOnSurface = Color(0xFF141522)
val DaylightOnSurfaceVariant = Color(0xFF565A70)
val DaylightOutline = Color(0xFF8A8EA6)
val DaylightOutlineVariant = Color(0xFFDCDCE8)
val DaylightError = Color(0xFFBA1A3A)
val DaylightOnError = Color(0xFFFFFFFF)

val MidnightVioletColorScheme = darkColorScheme(
    primary = MidnightPrimary,
    onPrimary = MidnightOnPrimary,
    primaryContainer = MidnightPrimaryContainer,
    onPrimaryContainer = MidnightOnPrimaryContainer,
    secondary = MidnightSecondary,
    onSecondary = MidnightOnSecondary,
    secondaryContainer = MidnightSecondaryContainer,
    onSecondaryContainer = MidnightOnSecondaryContainer,
    tertiary = MidnightTertiary,
    onTertiary = MidnightOnTertiary,
    background = MidnightBackground,
    onBackground = MidnightOnSurface,
    surface = MidnightSurface,
    onSurface = MidnightOnSurface,
    surfaceVariant = MidnightSurfaceElevated,
    onSurfaceVariant = MidnightOnSurfaceVariant,
    surfaceContainerLowest = MidnightBackground,
    surfaceContainerLow = Color(0xFF151722),
    surfaceContainer = MidnightSurface,
    surfaceContainerHigh = MidnightSurfaceElevated,
    surfaceContainerHighest = MidnightSurfaceBright,
    outline = MidnightOutline,
    outlineVariant = MidnightOutlineVariant,
    error = MidnightError,
    onError = MidnightOnError
)

val DaylightVioletColorScheme = lightColorScheme(
    primary = DaylightPrimary,
    onPrimary = DaylightOnPrimary,
    primaryContainer = DaylightPrimaryContainer,
    onPrimaryContainer = DaylightOnPrimaryContainer,
    secondary = DaylightSecondary,
    onSecondary = DaylightOnSecondary,
    secondaryContainer = DaylightSecondaryContainer,
    onSecondaryContainer = DaylightOnSecondaryContainer,
    tertiary = DaylightTertiary,
    onTertiary = DaylightOnTertiary,
    background = DaylightBackground,
    onBackground = DaylightOnSurface,
    surface = DaylightSurface,
    onSurface = DaylightOnSurface,
    surfaceVariant = DaylightSurfaceContainer,
    onSurfaceVariant = DaylightOnSurfaceVariant,
    surfaceContainerLowest = DaylightSurface,
    surfaceContainerLow = DaylightBackground,
    surfaceContainer = DaylightSurfaceContainer,
    surfaceContainerHigh = DaylightSurfaceElevated,
    surfaceContainerHighest = Color(0xFFDCDCEE),
    outline = DaylightOutline,
    outlineVariant = DaylightOutlineVariant,
    error = DaylightError,
    onError = DaylightOnError
)
