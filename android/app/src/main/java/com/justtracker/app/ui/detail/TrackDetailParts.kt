package com.justtracker.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justtracker.app.R
import com.justtracker.app.domain.geo.LatLon
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.LineMetric
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.track.TrackAcceleration
import com.justtracker.app.domain.track.TrackCursor
import com.justtracker.app.ui.common.AccelerationColorScale
import com.justtracker.app.ui.common.AccelerationLegend
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.FittedText
import com.justtracker.app.ui.common.LineColoring
import com.justtracker.app.ui.common.MapModeBadge
import com.justtracker.app.ui.common.SpeedLegend
import com.justtracker.app.ui.common.StatTile
import com.justtracker.app.ui.common.TrackMap
import com.justtracker.app.ui.common.icon
import com.justtracker.app.ui.common.labelRes
import com.justtracker.app.ui.common.tint
import com.justtracker.app.ui.theme.tabular
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.UnitFormatter
import com.justtracker.app.util.labelRes

/**
 * Scrubber under (portrait) or beside (landscape) the detail map (docs/04_ux_design.md §2.9): slider
 * over the track distance with "elapsed · %" caption, arrows to step one point, and the values at the cursor.
 * The first value is what the line is coloured by ([metric]): with acceleration the time of day gives way —
 * the caption already has the time from the start (§2.13).
 */
@Composable
internal fun TrackCursorPanel(
    cursor: TrackCursor,
    metric: LineMetric,
    formatter: UnitFormatter,
    onFraction: (Float) -> Unit,
    onStep: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(-1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.detail_cursor_prev))
            }
            Slider(
                value = cursor.fraction,
                onValueChange = onFraction,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onStep(1) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.detail_cursor_next))
            }
        }
        Text(
            stringResource(R.string.detail_cursor_caption, TimeFormat.duration(cursor.elapsedMs), (cursor.fraction * 100).toInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        // Four equal cells whose text scales down when it does not fit: at a large font in the narrow landscape
        // pane the values ran into each other ("20:36:36150") and the labels broke mid-word.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            val cell = Modifier.weight(1f)
            if (metric == LineMetric.ACCELERATION) AccelerationCursorValue(cursor, formatter, cell)
            CursorValue(stringResource(R.string.detail_cursor_speed), formatter.speed(cursor.speedMps.toDouble()), cell)
            CursorValue(stringResource(R.string.detail_distance), formatter.distance(cursor.distanceFromStartM), cell)
            if (metric == LineMetric.SPEED) CursorValue(stringResource(R.string.detail_cursor_time), TimeFormat.timeOfDay(cursor.timestamp), cell)
            CursorValue(stringResource(R.string.detail_cursor_altitude), cursor.altitudeM?.let { formatter.elevation(it) } ?: "—", cell)
        }
    }
}

@Composable
private fun CursorValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FittedText(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        FittedText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Acceleration at the cursor: the signed value with its unit, "—" without an estimate; the direction arrow sits on the
 * label line, so the value keeps the cell's whole width. TalkBack reads it in words, as on the recording panel.
 */
@Composable
private fun AccelerationCursorValue(cursor: TrackCursor, formatter: UnitFormatter, modifier: Modifier = Modifier) {
    val a = cursor.accelerationMps2
    val state = cursor.accelerationState
    val colors = MaterialTheme.colorScheme
    val palette = AccelerationColorScale.palette(colors.background.luminance() < 0.5f)
    val spoken = if (a == null || state == null) {
        stringResource(R.string.cd_acceleration_unavailable)
    } else {
        stringResource(R.string.cd_acceleration, formatter.accelerationMagnitude(a.toDouble()), formatter.accelerationUnitSpoken(), stringResource(state.labelRes()))
    }
    Column(
        modifier
            .padding(horizontal = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val value = a?.let { "${formatter.accelerationValue(it.toDouble())} ${formatter.accelerationUnit()}" } ?: "—"
        FittedText(value, style = MaterialTheme.typography.titleMedium.tabular().copy(fontWeight = FontWeight.SemiBold))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state != null) {
                Icon(
                    state.icon(),
                    contentDescription = null,
                    tint = state.tint(Color(palette.up), Color(palette.down), colors.onSurfaceVariant),
                    modifier = Modifier.size(14.dp),
                )
            }
            FittedText(
                stringResource(R.string.detail_cursor_acceleration),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * Map with the whole track, coloured by speed or acceleration ([TrackDetailUiState.lineMetric]); over it the switch
 * between the two (only when the track has acceleration to show), the legend of the shown one and the offline badge.
 * They flow into one row on a wide map and stack on a narrow one (docs/04_ux_design.md §2.13).
 */
@Composable
internal fun DetailMap(
    state: TrackDetailUiState,
    formatter: UnitFormatter,
    offline: Boolean,
    onTrackTap: (index: Int) -> Unit,
    onMetricChange: (LineMetric) -> Unit,
    highlight: () -> LatLon?,
    modifier: Modifier = Modifier,
) {
    val track = state.track ?: return
    val acceleration = state.acceleration
    val palette = AccelerationColorScale.palette(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val coloring = remember(state.lineMetric, acceleration, palette, track.maxSpeedMps) {
        if (state.lineMetric == LineMetric.ACCELERATION) {
            LineColoring.ByAcceleration(acceleration, palette)
        } else {
            LineColoring.BySpeed(track.maxSpeedMps)
        }
    }
    // The fitted track goes below the overlays, which cover the top of the map.
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    Box(modifier) {
        TrackMap(
            line = state.line,
            modifier = Modifier.fillMaxSize(),
            coloring = coloring,
            highlight = highlight(),
            fitToTrack = true,
            fitTopInsetPx = overlayHeightPx,
            showStartFinish = true,
            onTrackTap = onTrackTap,
        )
        FlowRow(
            modifier = Modifier
                .align(Alignment.TopStart)
                .onSizeChanged { overlayHeightPx = it.height }
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (acceleration.isAvailable) LineMetricSwitch(state.lineMetric, onMetricChange)
            when (state.lineMetric) {
                LineMetric.SPEED -> SpeedLegend(maxSpeedMps = track.maxSpeedMps, formatter = formatter)
                LineMetric.ACCELERATION -> AccelerationLegend(acceleration.scaleMps2, palette, formatter)
            }
            if (offline) MapModeBadge()
        }
    }
}

/**
 * "Speed | Acceleration" over the map. The inactive segment gets the overlays' surface: M3 leaves it transparent,
 * unreadable over map tiles. No check mark — the filled segment is the selection. As wide as its labels, within the
 * map's width (a label ends with "…" only when a large font does not fit even that).
 */
@Composable
private fun LineMetricSwitch(metric: LineMetric, onChange: (LineMetric) -> Unit) {
    val options = listOf(LineMetric.SPEED to R.string.detail_line_speed, LineMetric.ACCELERATION to R.string.detail_line_acceleration)
    val colors = SegmentedButtonDefaults.colors(inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { i, (option, label) ->
            SegmentedButton(
                selected = metric == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = colors,
                icon = {},
                label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}


/** Activity type (tap → type sheet) and the button that folds the stats grid away. */
@Composable
internal fun ActivityRow(type: ActivityType, statsVisible: Boolean, onTypeClick: () -> Unit, onToggleStats: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityBadge(type, size = 32)
        Text(
            stringResource(type.labelRes()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
                .clickable(onClick = onTypeClick),
        )
        IconButton(onClick = onToggleStats) {
            Icon(
                if (statsVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(if (statsVisible) R.string.detail_hide_stats else R.string.detail_show_stats),
            )
        }
    }
}

/** One tile of the stats grid; a [wide] one takes a whole row; [spoken] replaces what TalkBack reads. */
internal data class StatItem(val label: String, val value: String, val wide: Boolean = false, val spoken: String? = null)

/**
 * Tiles of the stats grid; recording time (Start → Stop) is the primary time, moving time secondary (US-08). With
 * speeding-up or slowing-down episodes their two peaks follow the average speed as a pair of their own (US-24).
 */
@Composable
internal fun statTiles(track: Track, acceleration: TrackAcceleration, formatter: UnitFormatter): List<StatItem> {
    val pace = if (track.showsPace) formatter.pace(track.paceSecPerMeter) else null
    return buildList {
        add(StatItem(stringResource(R.string.detail_distance), formatter.distance(track.distanceM)))
        add(StatItem(stringResource(R.string.detail_recording_time), TimeFormat.duration(track.recordingTimeMs())))
        add(StatItem(stringResource(R.string.detail_moving_time), TimeFormat.duration(track.movingTimeMs)))
        add(StatItem(stringResource(R.string.detail_avg_speed), formatter.speed(track.avgSpeedMps)))
        if (acceleration.episodes.isNotEmpty()) {
            add(accelerationTile(stringResource(R.string.detail_max_acceleration), acceleration.maxAcceleration?.peakMps2, formatter))
            add(accelerationTile(stringResource(R.string.detail_max_deceleration), acceleration.maxDeceleration?.peakMps2, formatter))
        }
        add(StatItem(stringResource(R.string.detail_max_speed), formatter.speed(track.maxSpeedMps)))
        add(StatItem(stringResource(R.string.detail_elevation_gain), formatter.elevation(track.elevationGainM, signed = true)))
        add(StatItem(stringResource(R.string.detail_elevation_loss), formatter.elevation(-track.elevationLossM, signed = true)))
        if (pace != null) add(StatItem(stringResource(R.string.detail_pace), pace))
        add(StatItem(stringResource(R.string.detail_points), track.pointCount.toString()))
        if (track.pausedTimeMs > 0) add(StatItem(stringResource(R.string.detail_paused_time), TimeFormat.duration(track.pausedTimeMs)))
        // "23 сент. 2026 г., 20:36" does not fit half a phone's width: a half-width tile showed "23 сент." only.
        add(StatItem(stringResource(R.string.detail_started), TimeFormat.dateTime(track.startedAt), wide = true))
    }
}

@Composable
private fun accelerationTile(label: String, mps2: Float?, formatter: UnitFormatter): StatItem {
    if (mps2 == null) return StatItem(label, "—")
    val value = "${formatter.accelerationValue(mps2.toDouble())} ${formatter.accelerationUnit()}"
    val spoken = stringResource(R.string.cd_stat_acceleration, label, formatter.accelerationMagnitude(mps2.toDouble()), formatter.accelerationUnitSpoken())
    return StatItem(label, value, spoken = spoken)
}

/**
 * Rows of the two-column stats grid: items pair up in order, a [wide] one takes a row of its own (the item
 * before it then stays alone) — the same placement as `GridItemSpan(maxLineSpan)` in the portrait grid.
 */
internal fun <T> gridRows(items: List<T>, wide: (T) -> Boolean): List<List<T>> = buildList {
    var pending = emptyList<T>()
    items.forEach { item ->
        if (wide(item)) {
            if (pending.isNotEmpty()) add(pending)
            pending = emptyList()
            add(listOf(item))
        } else {
            pending = pending + item
            if (pending.size == 2) {
                add(pending)
                pending = emptyList()
            }
        }
    }
    if (pending.isNotEmpty()) add(pending)
}

/** Two tiles per row (a wide tile alone) without a lazy container, for the scrollable side pane of the landscape layout. */
@Composable
internal fun StatTileRows(tiles: List<StatItem>, modifier: Modifier = Modifier) {
    val rows = gridRows(tiles) { it.wide }
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    StatTile(label = item.label, value = item.value, modifier = Modifier.weight(1f), contentDescription = item.spoken)
                }
                if (row.size == 1 && !row[0].wide) Spacer(Modifier.weight(1f))
            }
        }
    }
}
