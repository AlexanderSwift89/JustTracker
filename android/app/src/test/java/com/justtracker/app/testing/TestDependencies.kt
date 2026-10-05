package com.justtracker.app.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.service.LiveTrackingState
import com.justtracker.app.service.TrackingControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Settings on a real Preferences DataStore in a temporary file, owned by [scope] (the test's background scope). */
fun testSettings(dir: File, scope: CoroutineScope): SettingsRepository =
    SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })

/** Records the commands a view model sends to the recording service and lets a test set the live state. */
class FakeTrackingControl : TrackingControl {
    val commands = mutableListOf<String>()
    val state = MutableStateFlow(LiveTrackingState())
    override val live: StateFlow<LiveTrackingState> = state

    override fun start() { commands += "start" }
    override fun pause() { commands += "pause" }
    override fun resume() { commands += "resume" }
    override fun stop() { commands += "stop" }
    override fun recover() { commands += "recover" }

    override fun seedPosition(lat: Double, lon: Double) = state.update {
        if (it.lastLat == null) it.copy(lastLat = lat, lastLon = lon) else it
    }
}
