package net.pekmen.one.navigation

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.designsystem.R
import net.pekmen.one.core.designsystem.component.PekmenAdaptiveScaffold
import net.pekmen.one.core.designsystem.component.PekmenNavDestination
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.feature.focus.FocusFoundationRoute
import net.pekmen.one.feature.insights.InsightsFoundationRoute
import net.pekmen.one.feature.settings.SettingsFoundationRoute

@Composable
fun PekmenAppShell(
    widthSizeClass: WindowWidthSizeClass,
    userPreferencesRepository: UserPreferencesRepository,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val destinations = listOf(
        PekmenNavDestination(
            id = NAV_ID_FOCUS,
            labelRes = R.string.nav_focus,
            iconRes = PekmenIcons.Focus
        ),
        PekmenNavDestination(
            id = NAV_ID_INSIGHTS,
            labelRes = R.string.nav_insights,
            iconRes = PekmenIcons.Insights
        ),
        PekmenNavDestination(
            id = NAV_ID_SETTINGS,
            labelRes = R.string.nav_settings,
            iconRes = PekmenIcons.Settings
        )
    )

    val selectedId = when {
        currentDestination?.hasRoute<InsightsRoute>() == true -> NAV_ID_INSIGHTS
        currentDestination?.hasRoute<SettingsRoute>() == true -> NAV_ID_SETTINGS
        else -> NAV_ID_FOCUS
    }

    PekmenAdaptiveScaffold(
        destinations = destinations,
        selectedDestinationId = selectedId,
        useNavigationRail = widthSizeClass != WindowWidthSizeClass.Compact,
        onSelectDestination = { targetId ->
            val route: Any = when (targetId) {
                NAV_ID_INSIGHTS -> InsightsRoute
                NAV_ID_SETTINGS -> SettingsRoute
                else -> FocusRoute
            }
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
        modifier = modifier
    ) {
        NavHost(
            navController = navController,
            startDestination = FocusRoute
        ) {
            composable<FocusRoute> {
                FocusFoundationRoute()
            }
            composable<InsightsRoute> {
                InsightsFoundationRoute()
            }
            composable<SettingsRoute> {
                SettingsFoundationRoute(
                    preferencesRepository = userPreferencesRepository
                )
            }
        }
    }
}

private const val NAV_ID_FOCUS = "focus"
private const val NAV_ID_INSIGHTS = "insights"
private const val NAV_ID_SETTINGS = "settings"
