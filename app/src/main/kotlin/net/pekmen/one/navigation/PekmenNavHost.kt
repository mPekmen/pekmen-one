package net.pekmen.one.navigation

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.pekmen.one.core.data.FocusHistoryRepository
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.FocusSessionRepository
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.designsystem.R
import net.pekmen.one.core.designsystem.component.PekmenAdaptiveScaffold
import net.pekmen.one.core.designsystem.component.PekmenNavDestination
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.feature.focus.FocusRoute
import net.pekmen.one.feature.focus.FocusViewModel
import net.pekmen.one.feature.insights.InsightsRoute
import net.pekmen.one.feature.insights.InsightsViewModel
import net.pekmen.one.feature.settings.SettingsRoute
import net.pekmen.one.feature.settings.SettingsViewModel

@Composable
fun PekmenAppShell(
    widthSizeClass: WindowWidthSizeClass,
    sessionRepository: FocusSessionRepository,
    profileRepository: FocusProfileRepository,
    historyRepository: FocusHistoryRepository,
    userPreferencesRepository: UserPreferencesRepository,
    diagnosticsRepository: SystemDiagnosticsRepository,
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
                val vm: FocusViewModel = viewModel {
                    FocusViewModel(
                        sessionRepository = sessionRepository,
                        profileRepository = profileRepository,
                        historyRepository = historyRepository,
                        preferencesRepository = userPreferencesRepository,
                        diagnosticsRepository = diagnosticsRepository
                    )
                }
                FocusRoute(viewModel = vm)
            }
            composable<InsightsRoute> {
                val vm: InsightsViewModel = viewModel {
                    InsightsViewModel(
                        historyRepository = historyRepository,
                        profileRepository = profileRepository,
                        preferencesRepository = userPreferencesRepository,
                        diagnosticsRepository = diagnosticsRepository
                    )
                }
                InsightsRoute(viewModel = vm)
            }
            composable<SettingsRoute> {
                val vm: SettingsViewModel = viewModel {
                    SettingsViewModel(
                        preferencesRepository = userPreferencesRepository,
                        profileRepository = profileRepository,
                        diagnosticsRepository = diagnosticsRepository
                    )
                }
                SettingsRoute(viewModel = vm)
            }
        }
    }
}

private const val NAV_ID_FOCUS = "focus"
private const val NAV_ID_INSIGHTS = "insights"
private const val NAV_ID_SETTINGS = "settings"
