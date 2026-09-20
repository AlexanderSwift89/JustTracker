package io.treklog.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.treklog.app.R
import io.treklog.app.util.TimeFormat
import io.treklog.app.util.UnitFormatter
import kotlinx.coroutines.delay

/**
 * Compact "0 ▬▬▬▬ max" bar explaining the speed colours of the track line
 * (docs/04_ux_design.md §2.8). Hidden while there is nothing to explain.
 */
@Composable
fun SpeedLegend(maxSpeedMps: Double, formatter: UnitFormatter, modifier: Modifier = Modifier) {
    if (maxSpeedMps <= 0.0) return
    val maxText = formatter.speed(maxOf(maxSpeedMps, SpeedColorScale.MIN_RANGE_MPS))
    val cd = stringResource(R.string.map_speed_legend_cd, maxText)
    Surface(
        modifier = modifier.semantics { contentDescription = cd },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(
                Modifier
                    .padding(horizontal = 6.dp)
                    .width(72.dp)
                    .height(6.dp)
                    .background(
                        Brush.horizontalGradient(SpeedColorScale.STOPS.map { Color(it) }),
                        RoundedCornerShape(3.dp),
                    ),
            )
            Text(maxText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * Card shown after a tap on the track line: speed of that section plus where and when it was.
 * Dismisses itself after [autoHideMs] or on tap.
 */
@Composable
fun TrackTapCard(
    info: TrackTapInfo,
    formatter: UnitFormatter,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoHideMs: Long = TRACK_TAP_AUTO_HIDE_MS,
) {
    LaunchedEffect(info) {
        delay(autoHideMs)
        onDismiss()
    }
    Surface(
        modifier = modifier.clickable(onClick = onDismiss),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(stringResource(R.string.map_tap_speed_title), style = MaterialTheme.typography.labelMedium)
                Text(formatter.speed(info.speedMps.toDouble()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.map_tap_speed_detail,
                        formatter.distance(info.distanceFromStartM),
                        TimeFormat.duration(info.elapsedMs),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
        }
    }
}

const val TRACK_TAP_AUTO_HIDE_MS = 6_000L
