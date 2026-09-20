package com.justtracker.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.ThemeMode

// Fallback seed palette (docs/04_ux_design.md §3) for devices without dynamic color.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6E3A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2B5),
    onPrimaryContainer = Color(0xFF00210B),
    secondary = Color(0xFF506353),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E8D3),
    onSecondaryContainer = Color(0xFF0E1F12),
    tertiary = Color(0xFF39656F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF727970),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD59B),
    onPrimary = Color(0xFF003919),
    primaryContainer = Color(0xFF005227),
    onPrimaryContainer = Color(0xFFA6F2B5),
    secondary = Color(0xFFB7CCB8),
    onSecondary = Color(0xFF233426),
    secondaryContainer = Color(0xFF394B3C),
    onSecondaryContainer = Color(0xFFD3E8D3),
    tertiary = Color(0xFFA1CED9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DE),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DE),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BE),
    outline = Color(0xFF8B9389),
)

object ActivityColors {
    val walk = Color(0xFF2E7D32)
    val run = Color(0xFFEF6C00)
    val bike = Color(0xFF1565C0)
    val car = Color(0xFF6A1B9A)
    val other = Color(0xFF546E7A)

    fun of(type: ActivityType): Color = when (type) {
        ActivityType.WALK -> walk
        ActivityType.RUN -> run
        ActivityType.BIKE -> bike
        ActivityType.CAR -> car
        ActivityType.OTHER, ActivityType.UNKNOWN -> other
    }
}

@Composable
fun JustTrackerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
