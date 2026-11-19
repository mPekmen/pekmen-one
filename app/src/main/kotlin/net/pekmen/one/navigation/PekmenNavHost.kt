package net.pekmen.one.navigation

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import net.pekmen.one.core.data.LearningRepository
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.designsystem.R
import net.pekmen.one.core.designsystem.component.PekmenAdaptiveScaffold
import net.pekmen.one.core.designsystem.component.PekmenNavDestination
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.SessionLearningLink
import net.pekmen.one.feature.focus.FocusRoute
import net.pekmen.one.feature.focus.FocusViewModel
import net.pekmen.one.feature.insights.InsightsRoute
import net.pekmen.one.feature.insights.InsightsViewModel
import net.pekmen.one.feature.learning.LearningRoute as LearningScreenRoute
import net.pekmen.one.feature.learning.LearningViewModel
import net.pekmen.one.feature.settings.SettingsRoute
import net.pekmen.one.feature.settings.SettingsViewModel

@Composable
fun PekmenAppShell(
    widthSizeClass: WindowWidthSizeClass,
    sessionRepository: FocusSessionRepository,
    profileRepository: FocusProfileRepository,
    historyRepository: FocusHistoryRepository,
    learningRepository: LearningRepository,
    userPreferencesRepository: UserPreferencesRepository,
    diagnosticsRepository: SystemDiagnosticsRepository,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    var pendingFocusLink by remember { mutableStateOf<SessionLearningLink?>(null) }

    val navigateToTopLevel: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val destinations = listOf(
        PekmenNavDestination(
            id = NAV_ID_FOCUS,
            labelRes = R.string.nav_focus,
            iconRes = PekmenIcons.Focus
        ),
        PekmenNavDestination(
            id = NAV_ID_LEARNING,
            labelRes = R.string.nav_learning,
            iconRes = PekmenIcons.Book
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
        currentDestination?.hasRoute<LearningRoute>() == true -> NAV_ID_LEARNING
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
                NAV_ID_LEARNING -> LearningRoute
                NAV_ID_INSIGHTS -> InsightsRoute
                NAV_ID_SETTINGS -> SettingsRoute
                else -> FocusRoute
            }
            navigateToTopLevel(route)
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
                        diagnosticsRepository = diagnosticsRepository,
                        learningRepository = learningRepository
                    )
                }
                LaunchedEffect(pendingFocusLink) {
                    val link = pendingFocusLink ?: return@LaunchedEffect
                    vm.prepareLearningLink(link.subjectId, link.bookId, link.topicId)
                    pendingFocusLink = null
                }
                FocusRoute(viewModel = vm)
            }
            composable<LearningRoute> {
                val vm: LearningViewModel = viewModel {
                    LearningViewModel(
                        learningRepository = learningRepository,
                        preferencesRepository = userPreferencesRepository,
                        diagnosticsRepository = diagnosticsRepository
                    )
                }
                LearningScreenRoute(
                    viewModel = vm,
                    onFocusOnSubject = { subjectId, bookId, topicId ->
                        pendingFocusLink = SessionLearningLink(
                            subjectId = subjectId,
                            bookId = bookId,
                            topicId = topicId,
                            bookStartPage = null
                        )
                        navigateToTopLevel(FocusRoute)
                    }
                )
            }
            composable<InsightsRoute> {
                val vm: InsightsViewModel = viewModel {
                    InsightsViewModel(
                        historyRepository = historyRepository,
                        profileRepository = profileRepository,
                        preferencesRepository = userPreferencesRepository,
                        diagnosticsRepository = diagnosticsRepository,
                        learningRepository = learningRepository
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
private const val NAV_ID_LEARNING = "learning"
private const val NAV_ID_INSIGHTS = "insights"
private const val NAV_ID_SETTINGS = "settings"
