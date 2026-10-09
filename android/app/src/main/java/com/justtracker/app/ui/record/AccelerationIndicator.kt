package com.justtracker.app.ui.record

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justtracker.app.R
import com.justtracker.app.domain.stats.Acceleration
import com.justtracker.app.domain.stats.AccelerationEstimator
import com.justtracker.app.domain.stats.AccelerationTrace
import com.justtracker.app.ui.common.FittedText
import com.justtracker.app.ui.common.icon
import com.justtracker.app.ui.common.labelRes
import com.justtracker.app.ui.common.tint
import com.justtracker.app.ui.theme.AccelerationColors
import com.justtracker.app.ui.theme.tabular
import com.justtracker.app.util.UnitFormatter
import kotlin.math.abs

/**
 * Acceleration next to the live speed (US-23, docs/04_ux_design.md §2.12): direction arrow, signed value with its unit,
 * "Acceleration · speeding up", and the last minute as bars — up while speeding up, down while slowing down. Only the
 * arrow and the bars are tinted; the number keeps the panel's text colour, so the meaning never rests on colour alone.
 */
@Composable
internal fun AccelerationIndicator(
    acceleration: Acceleration?,
    trace: AccelerationTrace,
    formatter: UnitFormatter,
    contentAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val up = AccelerationColors.up(dark)
    val down = AccelerationColors.down(dark)
    val value = acceleration?.let { formatter.accelerationValue(it.mps2.toDouble()) } ?: stringResource(R.string.record_speed_unavailable)
    val label = acceleration?.let { stringResource(R.string.record_acceleration_state, stringResource(it.state.labelRes())) }
        ?: stringResource(R.string.record_label_acceleration)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Crossfade(targetState = acceleration?.state, label = "accelerationArrow") { state ->
                    if (state != null) {
                        Icon(
                            state.icon(),
                            contentDescription = null,
                            tint = state.tint(up, down, colors.onSurfaceVariant).copy(alpha = contentAlpha),
                            modifier = Modifier
                                .padding(end = 4.dp, bottom = 4.dp)
                                .size(20.dp),
                        )
                    }
                }
                FittedText(
                    value,
                    style = MaterialTheme.typography.titleLarge.tabular().copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface.copy(alpha = contentAlpha),
                    modifier = Modifier.weight(1f, fill = false),
                )
                UnitText(formatter.accelerationUnit(), style = MaterialTheme.typography.labelMedium, bottomPadding = 3.dp)
            }
            MetricLabel(label)
        }
        Spacer(Modifier.width(12.dp))
        AccelerationChart(
            trace = trace,
            up = up,
            down = down,
            neutral = colors.outline,
            axis = colors.outlineVariant,
            alpha = contentAlpha,
            modifier = Modifier
                .weight(1f)
                .height(CHART_HEIGHT),
        )
    }
}

/**
 * The last minute, one bar per second from the zero line: up while speeding up, down while slowing down, grey inside
 * the "steady" band. Symmetric scale of at least ±1 m/s², so walking noise stays flat and a hard stop still fits.
 * Decorative for TalkBack: the panel reads the current value out. Hidden when too narrow to be readable.
 */
@Composable
private fun AccelerationChart(
    trace: AccelerationTrace,
    up: Color,
    down: Color,
    neutral: Color,
    axis: Color,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        if (maxWidth < CHART_MIN_WIDTH) return@BoxWithConstraints
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.height / 2f
            val line = 1.dp.toPx()
            drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = line, alpha = alpha)
            val slot = size.width / trace.size
            val bar = (slot - line).coerceAtLeast(line)
            val scale = trace.scaleMps2
            for (i in 0 until trace.size) {
                val a = trace[i]
                if (a.isNaN()) continue
                val h = (abs(a) / scale).coerceAtMost(1f) * (mid - line)
                if (h < 0.5f) continue
                val color = when {
                    a >= AccelerationEstimator.ENTER_MPS2 -> up
                    a <= -AccelerationEstimator.ENTER_MPS2 -> down
                    else -> neutral
                }
                drawRect(color, topLeft = Offset(i * slot + (slot - bar) / 2f, if (a > 0) mid - h else mid), size = Size(bar, h), alpha = alpha)
            }
        }
    }
}

private val CHART_HEIGHT = 32.dp
private val CHART_MIN_WIDTH = 96.dp
