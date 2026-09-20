package com.justtracker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Material 3 type scale (Roboto / system default) — declared explicitly so every screen shares the
 * same roles (docs/04_ux_design.md §3). Live metrics use [tabular] so digits keep their width.
 */
val JustTrackerTypography: Typography = Typography().let { base ->
    base.copy(
        // Instant speed on the Record screen: bold, tabular, easy to read on the move.
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold).tabular(),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

private const val TABULAR_FIGURES = "tnum"

/** Tabular figures so numbers don't jitter horizontally as digits change (HUD, stat tiles, cursors). */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)
