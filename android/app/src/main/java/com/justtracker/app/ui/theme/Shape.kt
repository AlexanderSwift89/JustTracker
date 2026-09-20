package com.justtracker.app.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 shape scale (docs/04_ux_design.md §3): cards 16 dp (large), overlays 12 dp (medium),
 * the Record bottom panel 28 dp (extraLarge, top corners only), buttons round (their own defaults).
 */
val JustTrackerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Bottom-sheet silhouette: only the top corners rounded. */
fun CornerBasedShape.topOnly(): CornerBasedShape =
    copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
