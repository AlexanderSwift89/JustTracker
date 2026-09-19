package io.treklog.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.treklog.app.TrekLogApplication
import io.treklog.app.domain.model.AppSettings
import io.treklog.app.ui.common.LocalAppContainer
import io.treklog.app.ui.theme.TrekLogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TrekLogApplication).container
        setContent {
            // Wait for the first settings emission so a returning user never sees the onboarding flash.
            val settings by produceState<AppSettings?>(initialValue = null) {
                container.settingsRepository.settings.collect { value = it }
            }
            val current = settings ?: return@setContent
            CompositionLocalProvider(LocalAppContainer provides container) {
                TrekLogTheme(themeMode = current.theme) {
                    TrekLogApp(settings = current)
                }
            }
        }
    }
}
