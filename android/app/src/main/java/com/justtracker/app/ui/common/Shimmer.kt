package com.justtracker.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.justtracker.app.R
import kotlinx.coroutines.delay

/**
 * Skeleton ("shimmer") placeholders shown while data is being read or fetched
 * (docs/04_ux_design.md §7): the layout of the coming content, painted in a neutral surface tone
 * with a light band sweeping across it once every [SHIMMER_PERIOD_MS]. Screens show them through
 * [rememberSkeletonVisible], which waits [SKELETON_DELAY_MS] first so a load that finishes within a
 * frame or two does not flash a skeleton.
 */
const val SHIMMER_PERIOD_MS = 1_200
const val SKELETON_DELAY_MS = 100L

/**
 * True while [loading] holds for longer than [delayMs]; false as soon as loading ends. Use it as the
 * condition for the skeleton instead of the raw loading flag.
 */
@Composable
fun rememberSkeletonVisible(loading: Boolean, delayMs: Long = SKELETON_DELAY_MS): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }
    return visible && loading
}

/** Paints the moving highlight band over a [shape]-clipped placeholder; the base colour is theme-aware. */
fun Modifier.shimmer(shape: Shape? = null): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerBand",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerLowest
    val clipped = if (shape != null) clip(shape) else this
    clipped.drawBehind {
        val band = size.width * 0.5f
        val x = -band + (size.width + 2 * band) * progress
        drawRect(base)
        drawRect(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(x, 0f),
                end = Offset(x + band, size.height),
            ),
        )
    }
}

/** Rectangular placeholder; defaults to the theme's small corner radius. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    Box(modifier.shimmer(shape))
}

/** One line of "text": [width] wide, [height] tall (≈ the line height of the style it stands in for). */
@Composable
fun SkeletonLine(width: Dp, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    SkeletonBox(
        modifier
            .width(width)
            .height(height),
        shape = MaterialTheme.shapes.extraSmall,
    )
}

/** Paragraph placeholder: [lines] lines, the last one shorter, like a real text block. */
@Composable
fun SkeletonParagraph(lines: Int, modifier: Modifier = Modifier, lineHeight: Dp = 16.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { i ->
            SkeletonBox(
                Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f)
                    .height(lineHeight),
                shape = MaterialTheme.shapes.extraSmall,
            )
        }
    }
}

/** Placeholder with the footprint of a [StatTile] (label + headline value): the card tone stays still, only the lines shimmer. */
@Composable
fun SkeletonStatTile(modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SkeletonLine(width = 72.dp, height = 12.dp)
        Spacer(Modifier.height(10.dp))
        SkeletonLine(width = 96.dp, height = 24.dp)
    }
}

/** Placeholder for a History card: badge, two text lines, a row of three values on a still card tone. */
@Composable
fun SkeletonTrackCard(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(40.dp), shape = CircleShape)
            Column(Modifier.padding(start = 12.dp)) {
                SkeletonLine(width = 160.dp, height = 16.dp)
                Spacer(Modifier.height(6.dp))
                SkeletonLine(width = 96.dp, height = 12.dp)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(3) { SkeletonLine(width = 64.dp, height = 16.dp) }
        }
        Spacer(Modifier.height(8.dp))
        SkeletonLine(width = 140.dp, height = 12.dp)
    }
}

/** Placeholder for a two-line `ListItem` with a leading icon and a trailing action. */
@Composable
fun SkeletonListItem(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(24.dp), shape = CircleShape)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            SkeletonLine(width = 180.dp, height = 16.dp)
            Spacer(Modifier.height(6.dp))
            SkeletonLine(width = 100.dp, height = 12.dp)
        }
        SkeletonBox(Modifier.size(24.dp), shape = CircleShape)
    }
}

/**
 * Wraps a skeleton layout: announces "Loading…" once to accessibility services and hides the
 * decorative boxes from them.
 */
@Composable
fun SkeletonGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cd = stringResource(R.string.cd_loading)
    Box(modifier.semantics { contentDescription = cd }) {
        Box(Modifier.clearAndSetSemantics { }) { content() }
    }
}
