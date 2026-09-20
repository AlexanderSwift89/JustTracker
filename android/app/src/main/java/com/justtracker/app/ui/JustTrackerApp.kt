package com.justtracker.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.justtracker.app.R
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.ui.common.LocalAppContainer
import com.justtracker.app.ui.detail.TrackDetailScreen
import com.justtracker.app.ui.history.HistoryScreen
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
 * App shell: adaptive navigation (bottom bar on phones, rail in landscape / on tablets — M3
 * navigation suite) around the NavHost. Full-screen destinations (onboarding, track detail) hide it.
 */
@Composable
fun JustTrackerApp(settings: AppSettings) {
    val navController = rememberNavController()
    val container = LocalAppContainer.current
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val showNavigation = tabs.any { tab -> currentDestination?.hierarchy?.any { it.route == tab.route } == true }
    val activeTrack by container.trackRepository.observeActiveTrack().collectAsStateWithLifecycle(initialValue = null)
    // Fixed once per composition: changing startDestination later would rebuild the nav graph mid-flow.
    val startDestination = remember { if (settings.onboardingDone) Routes.RECORD else Routes.ONBOARDING }
    val layoutType = if (showNavigation) {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    } else {
        NavigationSuiteType.None
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            tabs.forEach { tab ->
                val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                item(
                    selected = selected,
                    onClick = {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        if (tab.route == Routes.RECORD && activeTrack != null) {
                            BadgedBox(badge = { PulsingBadge() }) { Icon(tab.icon, contentDescription = null) }
                        } else {
                            Icon(tab.icon, contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(tab.labelRes)) },
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
            composable(Routes.SETTINGS) { SettingsScreen() }
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

@Composable
private fun PulsingBadge() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "alpha",
    )
    Badge(
        containerColor = MaterialTheme.colorScheme.error,
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
    )
}
