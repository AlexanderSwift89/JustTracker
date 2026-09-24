package com.justtracker.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justtracker.app.R
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.ui.theme.ActivityColors
import com.justtracker.app.ui.theme.tabular
import com.justtracker.app.util.labelRes

fun ActivityType.icon(): ImageVector = when (this) {
    ActivityType.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.CAR -> Icons.Filled.DirectionsCar
    ActivityType.OTHER, ActivityType.UNKNOWN -> Icons.Filled.Route
}

/** Colored circle with the activity icon (docs/04_ux_design.md §2.3). */
@Composable
fun ActivityBadge(type: ActivityType, modifier: Modifier = Modifier, size: Int = 40) {
    val label = stringResource(type.labelRes())
    Box(
        modifier = modifier
            .size(size.dp)
            .background(ActivityColors.of(type), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = type.icon(),
            contentDescription = stringResource(R.string.cd_activity_icon, label),
            tint = Color.White,
            modifier = Modifier.size((size * 0.6).dp),
        )
    }
}

/**
 * Label above a prominent value; used in detail and stats grids. Both stay on one line and scale down when
 * they do not fit, so tiles of a row keep one height and their values one baseline at any font size.
 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            FittedText(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FittedText(value, style = MaterialTheme.typography.headlineSmall.tabular())
        }
    }
}

/**
 * One line that scales down (to [MIN_FIT_SCALE] of the style's size, then ends with "…") instead of being
 * clipped or broken mid-word when it does not fit — text in a narrow cell at a large font scale. With
 * `maxLines = 1` alone, "23 сент. 2026 г., 20:36" showed as "23 сент." and "150 м" as "150"; wrapping
 * labels broke into "Дистанци / я".
 *
 * Do not put it under `IntrinsicSize` measurement: the intrinsic height of auto-sized text follows its
 * current (possibly reduced) size, and the text then shrinks to fit that height.
 */
@Composable
fun FittedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val size = style.fontSize
    Text(
        text,
        modifier = modifier,
        color = color,
        // A style without a size in sp (none of the theme's) just ends with "…".
        autoSize = if (size.isSp) TextAutoSize.StepBased(minFontSize = size * MIN_FIT_SCALE, maxFontSize = size, stepSize = 0.5.sp) else null,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        softWrap = false,
        style = style,
    )
}

private const val MIN_FIT_SCALE = 0.6f

/** Centred while it fits; scrolls instead of being clipped in a short window (landscape, large font). */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.outline)
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}
