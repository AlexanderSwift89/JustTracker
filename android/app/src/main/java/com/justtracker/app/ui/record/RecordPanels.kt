package com.justtracker.app.ui.record

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.justtracker.app.R
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.FittedText
import com.justtracker.app.ui.common.labelRes
import com.justtracker.app.ui.theme.TrackColors
import com.justtracker.app.ui.theme.tabular
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.UnitFormatter
import kotlinx.coroutines.flow.first

@Composable
internal fun GpsOffBanner(onEnable: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.record_gps_off_banner),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEnable) { Text(stringResource(R.string.record_gps_off_action)) }
        }
    }
}

@Composable
internal fun PermissionPanel(deniedForever: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Text(stringResource(R.string.record_permission_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(if (deniedForever) R.string.record_permission_denied_forever else R.string.record_permission_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
    if (deniedForever) {
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
    } else {
        Button(onClick = onAllow) { Text(stringResource(R.string.action_allow)) }
    }
}

@Composable
internal fun IdlePanel(onStart: () -> Unit) {
    Text(stringResource(R.string.record_ready), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    FilledIconButton(
        onClick = onStart,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(Icons.Filled.FiberManualRecord, contentDescription = stringResource(R.string.record_start), modifier = Modifier.size(36.dp))
    }
    Text(stringResource(R.string.record_start), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
}

/**
 * Compact live HUD (~110 dp): speed on top, distance + recording time below, pause/stop stacked on
 * the right. Every number carries its unit and a text label, so nothing needs to be guessed from
 * position alone. Tapping the speed shows or hides the acceleration indicator (US-23); tapping the
 * rest of the panel reveals the secondary row (avg / max / moving time).
 */
@Composable
internal fun RecordingPanel(
    state: RecordUiState,
    formatter: UnitFormatter,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleAcceleration: () -> Unit,
    onAccelerationHintShown: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val track = state.track ?: return
    val paused = state.status == RecordStatus.PAUSED
    val speedText = if (state.gpsSearching || paused) stringResource(R.string.record_speed_unavailable) else formatter.speedValue(state.live.currentSpeedMps.toDouble())
    val contentAlpha = if (paused) 0.6f else 1f
    val (distanceValue, distanceUnit) = formatter.distanceParts(track.distanceM)
    val recordingText = TimeFormat.durationClock(state.recordingTimeMs)
    val movingText = TimeFormat.durationClock(track.movingTimeMs)
    val durationUnit = formatter.durationUnit()
    val distanceLabel = stringResource(R.string.record_label_distance)
    val recordingLabel = stringResource(R.string.record_label_recording_time)
    val acceleration = state.acceleration
    // The speed is its own TalkBack node (a button); the panel reads out the rest, acceleration included while shown.
    val accelerationSummary = when {
        !state.showAcceleration -> null
        acceleration == null -> stringResource(R.string.cd_acceleration_unavailable)
        else -> stringResource(
            R.string.cd_acceleration,
            formatter.accelerationMagnitude(acceleration.mps2.toDouble()),
            formatter.accelerationUnitSpoken(),
            stringResource(acceleration.state.labelRes()),
        )
    }
    val summary = listOfNotNull("$distanceLabel $distanceValue $distanceUnit", "$recordingLabel $recordingText", accelerationSummary).joinToString(", ")
    val toggleLabel = stringResource(if (expanded) R.string.record_collapse_stats else R.string.record_expand_stats)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = toggleLabel, onClick = onToggleExpanded),
    ) {
        // Drag-handle-like affordance for the expandable secondary row.
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(18.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = summary },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(Modifier.weight(1f)) {
                // A wide panel (landscape) keeps the indicator beside the speed, so the map does not lose height.
                val inline = maxWidth >= ACCELERATION_INLINE_MIN_WIDTH
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccelerationHint(pending = state.accelerationHintPending, onShown = onAccelerationHintShown) {
                            SpeedBlock(
                                speedText = speedText,
                                speedUnit = formatter.speedUnit(),
                                paused = paused,
                                gpsSearching = state.gpsSearching,
                                activityType = track.activityType,
                                contentAlpha = contentAlpha,
                                showAcceleration = state.showAcceleration,
                                onToggleAcceleration = onToggleAcceleration,
                            )
                        }
                        if (inline) {
                            AnimatedVisibility(visible = state.showAcceleration, modifier = Modifier.weight(1f)) {
                                AccelerationIndicator(
                                    acceleration = acceleration,
                                    trace = state.live.accelerationTrace,
                                    formatter = formatter,
                                    contentAlpha = contentAlpha,
                                    modifier = Modifier.padding(start = 20.dp),
                                )
                            }
                        }
                    }
                    if (!inline) {
                        AnimatedVisibility(visible = state.showAcceleration) {
                            AccelerationIndicator(
                                acceleration = acceleration,
                                trace = state.live.accelerationTrace,
                                formatter = formatter,
                                contentAlpha = contentAlpha,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        MetricCell(distanceValue, distanceUnit, distanceLabel, contentAlpha, Modifier.weight(1f))
                        // Recording time = Start → Stop including pauses; moving time lives in the expanded row.
                        MetricCell(recordingText, durationUnit, recordingLabel, contentAlpha, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedContent(targetState = paused, label = "pauseResume") { isPaused ->
                    if (isPaused) {
                        FilledIconButton(onClick = onResume, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.record_resume))
                        }
                    } else {
                        FilledTonalIconButton(onClick = onPause, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.record_pause))
                        }
                    }
                }
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.record_stop))
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                MetricCell(formatter.speedValue(track.avgSpeedMps), formatter.speedUnit(), stringResource(R.string.record_label_avg_speed), contentAlpha, Modifier.weight(1f), compact = true)
                MetricCell(formatter.speedValue(track.maxSpeedMps), formatter.speedUnit(), stringResource(R.string.record_label_max_speed), contentAlpha, Modifier.weight(1f), compact = true)
                MetricCell(movingText, durationUnit, stringResource(R.string.record_label_moving_time), contentAlpha, Modifier.weight(1.3f), compact = true)
            }
        }
    }
}

/**
 * The live speed with its label, GPS state and activity — the "speed widget". Tapping it shows or hides the
 * acceleration indicator (US-23); while the indicator is hidden, a small chart icon hints that there is more here.
 */
@Composable
private fun SpeedBlock(
    speedText: String,
    speedUnit: String,
    paused: Boolean,
    gpsSearching: Boolean,
    activityType: ActivityType,
    contentAlpha: Float,
    showAcceleration: Boolean,
    onToggleAcceleration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedLabel = stringResource(R.string.record_label_speed)
    val description = "$speedLabel $speedText $speedUnit"
    val clickLabel = stringResource(if (showAcceleration) R.string.record_hide_acceleration else R.string.record_show_acceleration)
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggleAcceleration)
            .semantics { contentDescription = description }
            .padding(end = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            FittedText(
                speedText,
                style = MaterialTheme.typography.headlineLarge.tabular().copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                modifier = Modifier.weight(1f, fill = false),
            )
            UnitText(speedUnit, style = MaterialTheme.typography.titleSmall, bottomPadding = 5.dp)
            if (!showAcceleration) {
                Icon(
                    Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp, bottom = 7.dp)
                        .size(16.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricLabel(speedLabel)
            Spacer(Modifier.width(8.dp))
            if (paused) {
                Text(
                    stringResource(R.string.record_paused),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                GpsIndicator(searching = gpsSearching)
            }
            if (activityType != ActivityType.UNKNOWN) {
                Spacer(Modifier.width(8.dp))
                ActivityBadge(activityType, size = 20)
            }
        }
    }
}

/**
 * One-time tooltip above the speed, "Tap the speed to see acceleration", shown the first time a recording panel
 * appears after the feature arrived (US-23). It stays until the user taps anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccelerationHint(pending: Boolean, onShown: () -> Unit, content: @Composable () -> Unit) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val pendingNow by rememberUpdatedState(pending)
    val onShownNow by rememberUpdatedState(onShown)
    // Keyed on the state, not on [pending]: marking the hint as shown flips [pending] and must not cancel the tooltip.
    LaunchedEffect(tooltipState) {
        snapshotFlow { pendingNow }.first { it }
        onShownNow()
        tooltipState.show()
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(stringResource(R.string.record_acceleration_hint)) } },
        state = tooltipState,
        enableUserInput = false,
        content = content,
    )
}

@Composable
private fun GpsIndicator(searching: Boolean) {
    val color = if (searching) MaterialTheme.colorScheme.outline else TrackColors.gpsOk
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (searching) Icons.Filled.GpsNotFixed else Icons.Filled.GpsFixed,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            stringResource(if (searching) R.string.record_gps_searching else R.string.record_gps_ok),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Value + unit on one line, text label underneath: "1,25 km / Distance". The unit is measured first and
 * the value scales down when both do not fit (a large font in portrait clipped "ч:мм:сс" to "ч:мм:с").
 */
@Composable
private fun MetricCell(
    value: String,
    unit: String,
    label: String,
    contentAlpha: Float,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            FittedText(
                value,
                style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).tabular()
                    .copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                modifier = Modifier.weight(1f, fill = false),
            )
            UnitText(unit, style = MaterialTheme.typography.labelMedium, bottomPadding = if (compact) 2.dp else 3.dp)
        }
        MetricLabel(label)
    }
}

@Composable
internal fun UnitText(unit: String, style: TextStyle, bottomPadding: Dp) {
    Text(
        unit,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(start = 4.dp, bottom = bottomPadding),
    )
}

@Composable
internal fun MetricLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** From this width of the metrics column (a panel ≥ ~480 dp, i.e. landscape) acceleration sits beside the speed, not under it. */
private val ACCELERATION_INLINE_MIN_WIDTH = 380.dp
