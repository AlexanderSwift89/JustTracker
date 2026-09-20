package com.justtracker.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.justtracker.app.JustTrackerApplication
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.ui.common.LocalAppContainer
import com.justtracker.app.ui.theme.JustTrackerTheme
import com.justtracker.app.util.AppLocale

/** AppCompatActivity (not ComponentActivity) so the per-app language works down to API 26. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as JustTrackerApplication).container
        setContent {
            // Wait for the first settings emission so a returning user never sees the onboarding flash.
            val settings by produceState<AppSettings?>(initialValue = null) {
                container.settingsRepository.settings.collect { value = it }
            }
            val current = settings ?: return@setContent
            // Stored choice wins over whatever AppCompat remembers (reinstall / restore); no-op when equal.
            LaunchedEffect(current.language) { AppLocale.sync(current.language) }
            CompositionLocalProvider(LocalAppContainer provides container) {
                JustTrackerTheme(themeMode = current.theme) {
                    JustTrackerApp(settings = current)
                }
            }
        }
    }
}
