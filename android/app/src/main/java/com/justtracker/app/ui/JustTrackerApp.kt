package com.justtracker.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.justtracker.app.R
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.ui.common.FittedText
import com.justtracker.app.ui.common.LocalAppContainer
import com.justtracker.app.ui.maps.MapModePromptDialog
import com.justtracker.app.ui.detail.TrackDetailScreen
import com.justtracker.app.ui.history.HistoryScreen
import com.justtracker.app.ui.maps.OfflineMapsScreen
import com.justtracker.app.ui.onboarding.OnboardingScreen
import com.justtracker.app.ui.record.RecordScreen
import com.justtracker.app.ui.settings.SettingsScreen
import com.justtracker.app.ui.stats.StatsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val RECORD = "record"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{trackId}"
    const val OFFLINE_MAPS = "offline_maps"
    fun detail(id: Long) = "detail/$id"
}

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.RECORD, R.string.nav_record, Icons.Filled.RadioButtonChecked),
    Tab(Routes.HISTORY, R.string.nav_history, Icons.Filled.History),
    Tab(Routes.STATS, R.string.nav_stats, Icons.Filled.BarChart),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/**
 * App shell: adaptive navigation (bottom bar on a phone in portrait, side rail whenever the window is
 * not compact in width — a phone in landscape, tablets; M3 navigation suite) around the NavHost.
 * Full-screen destinations (onboarding, track detail, offline maps) hide it.
 */
@Composable
fun JustTrackerApp(settings: AppSettings) {
    val navController = rememberNavController()
    val container = LocalAppContainer.current
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val showNavigation = tabs.any { tab -> currentDestination?.hierarchy?.any { it.route == tab.route } == true }
    // One flow for the whole app (not a new Room query per recomposition), emitting only when a recording starts or ends:
    // the active track's row changes every second.
    val recording by remember(container) {
        container.trackRepository.observeActiveTrack().map { it != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    // Fixed once per composition: changing startDestination later would rebuild the nav graph mid-flow.
    val startDestination = remember { if (settings.onboardingDone) Routes.RECORD else Routes.ONBOARDING }
    // Connectivity changed: ask before switching the map source (US-22); never during onboarding.
    val modeSuggestion by container.mapModeController.suggestion.collectAsStateWithLifecycle()
    if (settings.onboardingDone) {
        modeSuggestion?.let { suggestion ->
            MapModePromptDialog(
                suggestion = suggestion,
                onConfirm = { container.mapModeController.confirm(suggestion.target) },
                onDismiss = { container.mapModeController.dismissSuggestion() },
            )
        }
    }
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val layoutType = when {
        !showNavigation -> NavigationSuiteType.None
        // The default picks a bottom bar for any compact height, i.e. for a phone in landscape, where it takes a
        // fifth of the height from the map: every window that is not compact in width gets the side rail instead.
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> NavigationSuiteType.NavigationRail
        else -> NavigationSuiteType.NavigationBar
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            tabs.forEach { tab ->
                val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                item(
                    selected = selected,
                    onClick = {
                        // A tab already on the back stack (always true for the start tab, also after the
                        // activity was recreated) is popped back to; otherwise it is navigated to with
                        // its saved state restored.
                        val popped = navController.popBackStack(tab.route, inclusive = false, saveState = true)
                        if (!popped) {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = {
                        if (tab.route == Routes.RECORD && recording) {
                            BadgedBox(badge = { RecordingBadge() }) { Icon(tab.icon, contentDescription = null) }
                        } else {
                            Icon(tab.icon, contentDescription = null)
                        }
                    },
                    // One line, scaled down if needed: at a large font "Статистика" broke into "Статистик / а".
                    label = { FittedText(stringResource(tab.labelRes), style = LocalTextStyle.current) },
                )
            }
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    languageChosen = settings.language != null,
                    onDone = {
                        navController.navigate(Routes.RECORD) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.RECORD) {
                RecordScreen(onTrackFinished = { id -> navController.navigate(Routes.detail(id)) })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenTrack = { id -> navController.navigate(Routes.detail(id)) })
            }
            composable(Routes.STATS) { StatsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenOfflineMaps = { navController.navigate(Routes.OFFLINE_MAPS) })
            }
            composable(Routes.OFFLINE_MAPS) { OfflineMapsScreen(onBack = { navController.popBackStack() }) }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("trackId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("trackId") ?: return@composable
                TrackDetailScreen(trackId = id, onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * "Recording" dot on the Record tab. It blinks once a second like a REC lamp: one redraw per change instead of an
 * animation at the display rate, which kept every tab drawing 60 frames a second for the whole recording (D-29).
 * The alpha is read in the draw phase only, so a blink recomposes nothing.
 */
@Composable
private fun RecordingBadge() {
    var dim by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BADGE_BLINK_MS)
            dim = !dim
        }
    }
    Badge(
        containerColor = MaterialTheme.colorScheme.error,
        modifier = Modifier.graphicsLayer { alpha = if (dim) 0.3f else 1f },
    )
}

private const val BADGE_BLINK_MS = 1_000L
