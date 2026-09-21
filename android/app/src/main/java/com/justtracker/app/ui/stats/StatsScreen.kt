package com.justtracker.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.justtracker.app.R
import com.justtracker.app.data.db.ActivityAggregate
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.EmptyState
import com.justtracker.app.ui.common.StatTile
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.UnitFormatter
import com.justtracker.app.util.labelRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class PeriodSummary(val count: Int, val distanceM: Double, val movingTimeMs: Long)

data class StatsUiState(
    val loaded: Boolean = false,
    val total: PeriodSummary = PeriodSummary(0, 0.0, 0),
    val week: PeriodSummary = PeriodSummary(0, 0.0, 0),
    val month: PeriodSummary = PeriodSummary(0, 0.0, 0),
    val byType: List<ActivityAggregate> = emptyList(),
    val longest: Track? = null,
    val fastest: Track? = null,
    val units: UnitSystem = UnitSystem.METRIC,
)

class StatsViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.trackRepository

    val state = combine(
        repo.observeFinishedTracks(),
        repo.observeActivityAggregates(),
        container.settingsRepository.settings,
    ) { tracks, byType, settings ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        StatsUiState(
            loaded = true,
            total = summarize(tracks),
            week = summarize(tracks.filter { it.startedAt >= weekStart }),
            month = summarize(tracks.filter { it.startedAt >= monthStart }),
            byType = byType,
            longest = tracks.maxByOrNull { it.distanceM },
            fastest = tracks.filter { it.distanceM >= 500 }.maxByOrNull { it.avgSpeedMps },
            units = settings.units,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun summarize(tracks: List<Track>) =
        PeriodSummary(tracks.size, tracks.sumOf { it.distanceM }, tracks.sumOf { it.movingTimeMs })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = appViewModel { StatsViewModel(it) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.stats_title)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        if (state.loaded && state.total.count == 0) {
            EmptyState(
                icon = Icons.Filled.BarChart,
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryRow(state.total, formatter, showCount = true) }
            item { SectionTitle(stringResource(R.string.stats_this_week)) }
            item { SummaryRow(state.week, formatter, showCount = true) }
            item { SectionTitle(stringResource(R.string.stats_this_month)) }
            item { SummaryRow(state.month, formatter, showCount = true) }
            state.longest?.let { t ->
                item { SectionTitle(stringResource(R.string.stats_longest)) }
                item { RecordRow(t, formatter.distance(t.distanceM)) }
            }
            state.fastest?.let { t ->
                item { SectionTitle(stringResource(R.string.stats_fastest)) }
                item { RecordRow(t, formatter.speed(t.avgSpeedMps)) }
            }
            item { SectionTitle(stringResource(R.string.stats_by_type)) }
            items(state.byType, key = { it.activityType }) { agg ->
                val type = runCatching { ActivityType.valueOf(agg.activityType) }.getOrDefault(ActivityType.UNKNOWN)
                ListItem(
                    leadingContent = { ActivityBadge(type, size = 36) },
                    headlineContent = { Text(stringResource(type.labelRes())) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.stats_tracks_count, agg.count, agg.count) + " · " + TimeFormat.duration(agg.movingTimeMs))
                    },
                    trailingContent = { Text(formatter.distance(agg.distanceM), style = MaterialTheme.typography.titleMedium) },
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(summary: PeriodSummary, formatter: UnitFormatter, showCount: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showCount) StatTile(stringResource(R.string.stats_total_tracks), summary.count.toString(), Modifier.weight(1f))
        StatTile(stringResource(R.string.stats_total_distance), formatter.distance(summary.distanceM), Modifier.weight(1f))
        StatTile(stringResource(R.string.stats_total_time), TimeFormat.duration(summary.movingTimeMs), Modifier.weight(1f))
    }
}

@Composable
private fun RecordRow(track: Track, value: String) {
    ListItem(
        leadingContent = { ActivityBadge(track.activityType, size = 36) },
        headlineContent = { Text(track.name, maxLines = 1) },
        supportingContent = { Text(TimeFormat.dateTime(track.startedAt)) },
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium) },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
