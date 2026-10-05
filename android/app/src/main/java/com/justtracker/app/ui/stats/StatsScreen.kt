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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.stats.PeriodSummary
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.EmptyState
import com.justtracker.app.ui.common.SkeletonGroup
import com.justtracker.app.ui.common.SkeletonLine
import com.justtracker.app.ui.common.SkeletonListItem
import com.justtracker.app.ui.common.SkeletonStatTile
import com.justtracker.app.ui.common.StatTile
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.ui.common.rememberSkeletonVisible
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.UnitFormatter
import com.justtracker.app.util.labelRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = appViewModel { c -> StatsViewModel(c.trackRepository, c.settingsRepository) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.stats_title)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        if (rememberSkeletonVisible(!state.loaded)) {
            StatsSkeleton(Modifier.padding(padding))
            return@Scaffold
        }
        if (state.loaded && state.summaries.total.count == 0) {
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
            val s = state.summaries
            item { SummaryRow(s.total, formatter) }
            item { SectionTitle(stringResource(R.string.stats_this_week)) }
            item { SummaryRow(s.week, formatter) }
            item { SectionTitle(stringResource(R.string.stats_this_month)) }
            item { SummaryRow(s.month, formatter) }
            s.longest?.let { t ->
                item { SectionTitle(stringResource(R.string.stats_longest)) }
                item { RecordRow(t, formatter.distance(t.distanceM)) }
            }
            s.fastest?.let { t ->
                item { SectionTitle(stringResource(R.string.stats_fastest)) }
                item { RecordRow(t, formatter.speed(t.avgSpeedMps)) }
            }
            item { SectionTitle(stringResource(R.string.stats_by_type)) }
            items(state.byType, key = { it.type }) { agg ->
                ListItem(
                    leadingContent = { ActivityBadge(agg.type, size = 36) },
                    headlineContent = { Text(stringResource(agg.type.labelRes())) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.stats_tracks_count, agg.count, agg.count) + " · " + TimeFormat.duration(agg.movingTimeMs))
                    },
                    trailingContent = { Text(formatter.distance(agg.distanceM), style = MaterialTheme.typography.titleMedium) },
                )
            }
        }
    }
}

/** Same rhythm as the loaded screen: three tiles, a section title, three tiles, a list (docs/04_ux_design.md §7). */
@Composable
private fun StatsSkeleton(modifier: Modifier = Modifier) {
    SkeletonGroup(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(2) { block ->
                if (block > 0) SkeletonLine(width = 96.dp, height = 16.dp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { SkeletonStatTile(Modifier.weight(1f)) }
                }
            }
            SkeletonLine(width = 96.dp, height = 16.dp, modifier = Modifier.padding(top = 8.dp))
            repeat(3) { SkeletonListItem() }
        }
    }
}

@Composable
private fun SummaryRow(summary: PeriodSummary, formatter: UnitFormatter) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(stringResource(R.string.stats_total_tracks), summary.count.toString(), Modifier.weight(1f))
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
