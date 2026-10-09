package com.justtracker.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.justtracker.app.R
import com.justtracker.app.domain.stats.AccelerationState

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
