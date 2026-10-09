package com.justtracker.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.justtracker.app.R
import com.justtracker.app.domain.stats.AccelerationState
import com.justtracker.app.util.UnitFormatter

/** "speeding up" / "slowing down" / "steady" / "standing still" — the live indicator, the track detail and TalkBack. */
internal fun AccelerationState.labelRes(): Int = when (this) {
    AccelerationState.ACCELERATING -> R.string.record_accel_accelerating
    AccelerationState.DECELERATING -> R.string.record_accel_decelerating
    AccelerationState.STEADY -> R.string.record_accel_steady
    AccelerationState.STATIONARY -> R.string.record_accel_stationary
}

/** Up while speeding up, down while slowing down, a dash otherwise: the direction never rests on colour alone. */
internal fun AccelerationState.icon(): ImageVector = when (this) {
    AccelerationState.ACCELERATING -> Icons.Filled.ArrowUpward
    AccelerationState.DECELERATING -> Icons.Filled.ArrowDownward
    AccelerationState.STEADY, AccelerationState.STATIONARY -> Icons.Filled.Remove
}

internal fun AccelerationState.tint(up: Color, down: Color, neutral: Color): Color = when (this) {
    AccelerationState.ACCELERATING -> up
    AccelerationState.DECELERATING -> down
    AccelerationState.STEADY, AccelerationState.STATIONARY -> neutral
}

/**
 * Compact "−S ▬▬▬▬ +S m/s²" bar explaining the line coloured by acceleration (docs/04_ux_design.md §2.13): slowing down
 * on the left, steady in the middle, speeding up on the right — the same place as the speed legend.
 */
@Composable
fun AccelerationLegend(scaleMps2: Float, palette: AccelerationColorScale.Palette, formatter: UnitFormatter, modifier: Modifier = Modifier) {
    val scale = scaleMps2.toDouble()
    val cd = stringResource(
        R.string.map_acceleration_legend_cd,
        formatter.accelerationMagnitude(scale),
        formatter.accelerationMagnitude(scale),
        formatter.accelerationUnitSpoken(),
    )
    val label = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.semantics { contentDescription = cd },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatter.accelerationValue(-scale), style = label, color = labelColor, maxLines = 1)
            Spacer(
                Modifier
                    .padding(horizontal = 6.dp)
                    .width(72.dp)
                    .height(6.dp)
                    .background(
                        Brush.horizontalGradient(listOf(Color(palette.down), Color(palette.neutral), Color(palette.up))),
                        RoundedCornerShape(3.dp),
                    ),
            )
            Text("${formatter.accelerationValue(scale)} ${formatter.accelerationUnit()}", style = label, color = labelColor, maxLines = 1)
        }
    }
}
