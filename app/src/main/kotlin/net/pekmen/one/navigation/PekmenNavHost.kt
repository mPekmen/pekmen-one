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
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.map
import net.pekmen.one.core.cloud.CirclesRepository
import net.pekmen.one.core.cloud.CloudAccountRepository
import net.pekmen.one.core.cloud.CloudRlsGateProvider
import net.pekmen.one.core.data.FocusHistoryRepository
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.FocusSessionRepository
import net.pekmen.one.core.data.LearningRepository
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.data.planner.CalendarInsertGateway
import net.pekmen.one.core.data.planner.PlatformCalendarInsertGateway
import net.pekmen.one.core.data.planner.PlannerRepository
import net.pekmen.one.core.designsystem.R
import net.pekmen.one.core.designsystem.component.PekmenAdaptiveScaffold
import net.pekmen.one.core.designsystem.component.PekmenNavDestination
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.CloudRlsGateStatus
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.SessionLearningLink
import net.pekmen.one.core.portability.BackupPortabilityService
import net.pekmen.one.feature.circles.CirclesRoute
import net.pekmen.one.feature.circles.CirclesTabRoute
import net.pekmen.one.feature.circles.CirclesViewModel
import net.pekmen.one.feature.focus.FocusRoute
import net.pekmen.one.feature.focus.FocusViewModel
import net.pekmen.one.feature.insights.InsightsRoute
import net.pekmen.one.feature.insights.InsightsViewModel
import net.pekmen.one.feature.learning.LearningRoute as LearningScreenRoute
import net.pekmen.one.feature.learning.LearningViewModel
import net.pekmen.one.feature.planner.PlannerScreenRoute
import net.pekmen.one.feature.planner.PlannerViewModel
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
    modifier: Modifier = Modifier,
    plannerRepository: PlannerRepository? = null,
    calendarInsertGateway: CalendarInsertGateway = PlatformCalendarInsertGateway(),
    backupPortabilityService: BackupPortabilityService? = null,
    cloudAccountRepository: CloudAccountRepository? = null,
    circlesRepository: CirclesRepository? = null,
    cloudRlsGateProvider: CloudRlsGateProvider? = null
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

    val destinations = remember(plannerRepository != null) {
        buildTopLevelDestinations(includePlanner = plannerRepository != null)
    }

    val selectedId = resolveSelectedNavId(currentDestination)

    PekmenAdaptiveScaffold(
        destinations = destinations,
        selectedDestinationId = selectedId,
        useNavigationRail = widthSizeClass != WindowWidthSizeClass.Compact,
        onSelectDestination = { targetId ->
            navigateToTopLevel(resolveRouteForNavId(targetId))
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
                    vm.prepareLearningLink(link.subjectId, link.bookId, link.topicId, link.taskId)
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
            if (plannerRepository != null) {
                composable<PlannerRoute> {
                    val vm: PlannerViewModel = viewModel {
                        PlannerViewModel(
                            plannerRepository = plannerRepository,
                            diagnosticsRepository = diagnosticsRepository,
                            calendarInsertGateway = calendarInsertGateway
                        )
                    }
                    PlannerScreenRoute(
                        viewModel = vm,
                        onFocusOnTask = { taskId, subjectId ->
                            pendingFocusLink = SessionLearningLink(
                                subjectId = subjectId,
                                bookId = null,
                                topicId = null,
                                taskId = taskId,
                                bookStartPage = null
                            )
                            navigateToTopLevel(FocusRoute)
                        }
                    )
                }
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
                val openCirclesAction: (() -> Unit)? = if (circlesRepository != null) {
                    {
                        navController.navigate(CirclesRoute) {
                            launchSingleTop = true
                        }
                    }
                } else {
                    null
                }
                InsightsRoute(viewModel = vm, onOpenCircles = openCirclesAction)
            }
            if (circlesRepository != null) {
                composable<CirclesRoute> {
                    val vm: CirclesViewModel = viewModel {
                        CirclesViewModel(
                            circlesRepository = circlesRepository,
                            preferencesRepository = userPreferencesRepository,
                            diagnosticsRepository = diagnosticsRepository,
                            gateStatusProvider = { resolveRlsGateStatus(cloudRlsGateProvider) },
                            availableSubjectsFlow = learningRepository.observeSubjects()
                                .map { subjects -> subjects.map { it.title } },
                            isCurrentlyFocusingFlow = sessionRepository.activeSession
                                .map { snapshot -> snapshot?.session?.status == FocusSessionStatus.RUNNING }
                        )
                    }
                    CirclesTabRoute(viewModel = vm)
                }
            }
            composable<SettingsRoute> {
                val vm: SettingsViewModel = viewModel {
                    SettingsViewModel(
                        preferencesRepository = userPreferencesRepository,
                        profileRepository = profileRepository,
                        diagnosticsRepository = diagnosticsRepository,
                        backupPortabilityService = backupPortabilityService,
                        cloudAccountRepository = cloudAccountRepository
                    )
                }
                SettingsRoute(viewModel = vm)
            }
        }
    }
}

private fun buildTopLevelDestinations(includePlanner: Boolean): List<PekmenNavDestination> = buildList {
    add(PekmenNavDestination(id = NAV_ID_FOCUS, labelRes = R.string.nav_focus, iconRes = PekmenIcons.Focus))
    add(PekmenNavDestination(id = NAV_ID_LEARNING, labelRes = R.string.nav_learning, iconRes = PekmenIcons.Book))
    if (includePlanner) {
        add(PekmenNavDestination(id = NAV_ID_PLANNER, labelRes = R.string.nav_planner, iconRes = PekmenIcons.Calendar))
    }
    add(PekmenNavDestination(id = NAV_ID_INSIGHTS, labelRes = R.string.nav_insights, iconRes = PekmenIcons.Insights))
    add(PekmenNavDestination(id = NAV_ID_SETTINGS, labelRes = R.string.nav_settings, iconRes = PekmenIcons.Settings))
}

private fun resolveSelectedNavId(currentDestination: NavDestination?): String = when {
    currentDestination?.hasRoute<LearningRoute>() == true -> NAV_ID_LEARNING
    currentDestination?.hasRoute<PlannerRoute>() == true -> NAV_ID_PLANNER
    currentDestination?.hasRoute<InsightsRoute>() == true -> NAV_ID_INSIGHTS
    currentDestination?.hasRoute<CirclesRoute>() == true -> NAV_ID_INSIGHTS
    currentDestination?.hasRoute<SettingsRoute>() == true -> NAV_ID_SETTINGS
    else -> NAV_ID_FOCUS
}

private fun resolveRouteForNavId(targetId: String): Any = when (targetId) {
    NAV_ID_LEARNING -> LearningRoute
    NAV_ID_PLANNER -> PlannerRoute
    NAV_ID_INSIGHTS -> InsightsRoute
    NAV_ID_SETTINGS -> SettingsRoute
    else -> FocusRoute
}

private fun resolveRlsGateStatus(provider: CloudRlsGateProvider?): CloudRlsGateStatus =
    provider?.currentGateStatus() ?: CloudRlsGateStatus(
        compiledWithCloudFlag = false,
        backendEndpointConfigured = false,
        rlsVerified = false
    )

private const val NAV_ID_FOCUS = "focus"
private const val NAV_ID_LEARNING = "learning"
private const val NAV_ID_PLANNER = "planner"
private const val NAV_ID_INSIGHTS = "insights"
private const val NAV_ID_SETTINGS = "settings"
