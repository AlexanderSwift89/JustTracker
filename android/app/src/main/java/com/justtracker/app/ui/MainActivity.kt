package com.justtracker.app.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.justtracker.app.JustTrackerApplication
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.domain.model.ThemeMode
import com.justtracker.app.ui.common.LocalAppContainer
import com.justtracker.app.ui.theme.JustTrackerTheme
import com.justtracker.app.util.AppLocale

/** AppCompatActivity (not ComponentActivity) so the per-app language works down to API 26. */
class MainActivity : AppCompatActivity() {
    @Volatile
    private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hold the system splash until the first settings emission: no theme/onboarding flash.
        splash.setKeepOnScreenCondition { !settingsLoaded }
        val container = (application as JustTrackerApplication).container
        setContent {
            val settings by produceState<AppSettings?>(initialValue = null) {
                container.settingsRepository.settings.collect {
                    value = it
                    settingsLoaded = true
                }
            }
            val current = settings ?: return@setContent
            // Stored choice wins over whatever AppCompat remembers (reinstall / restore); no-op when equal.
            LaunchedEffect(current.language) { AppLocale.sync(current.language) }
            // System bar icons must follow the *app* theme, which may differ from the system one.
            val dark = when (current.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                JustTrackerTheme(themeMode = current.theme) {
                    JustTrackerApp(settings = current)
                }
            }
        }
    }
}
