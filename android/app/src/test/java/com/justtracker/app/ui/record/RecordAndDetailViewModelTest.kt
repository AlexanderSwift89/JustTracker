package com.justtracker.app.ui.record

import android.location.Location
import androidx.lifecycle.SavedStateHandle
import com.justtracker.app.data.export.GpxExporter
import com.justtracker.app.data.location.LocationSource
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.di.AppDispatchers
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.testing.FakeTrackDao
import com.justtracker.app.testing.FakeTrackingControl
import com.justtracker.app.testing.finishedTrack
import com.justtracker.app.testing.testSettings
import com.justtracker.app.testing.walk
import com.justtracker.app.ui.detail.TrackDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RecordAndDetailViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = FakeTrackDao()
    private val repo = TrackRepository(dao)
    private val tracking = FakeTrackingControl()
    private val noLocation = object : LocationSource {
        override fun updates(intervalMs: Long): Flow<Location> = emptyFlow()
        override suspend fun lastKnown(): Location? = null
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun recordViewModel(settings: com.justtracker.app.data.repo.SettingsRepository) =
        RecordViewModel(repo, settings, tracking, noLocation, AppDispatchers(dispatcher, dispatcher))

    @Test
    fun `Record is idle without an active track and sends commands to the service`() = runTest(dispatcher) {
        val vm = recordViewModel(testSettings(tmp.root, backgroundScope))
        val state = vm.state.first { it.status == RecordStatus.IDLE }
        assertTrue(state.line.isEmpty)
        vm.start()
        vm.pause()
        vm.stop()
        assertEquals(listOf("start", "pause", "stop"), tracking.commands)
    }

    @Test
    fun `Record shows the active track's line, position, map mode and resolves a tap`() = runTest(dispatcher) {
        val settings = testSettings(tmp.root, backgroundScope)
        settings.setMapMode(MapMode.OFFLINE)
        val track = repo.createTrack("now", startedAt = 0)
        repo.walk(track, 20)
        tracking.state.update { it.copy(serviceRunning = true, lastLat = 55.001, lastLon = 37.0) }
        val vm = recordViewModel(settings)
        val state = vm.state.first { it.line.size == 20 && it.mapMode == MapMode.OFFLINE }
        assertEquals(RecordStatus.RECORDING, state.status)
        assertEquals(55.001, state.position!!.lat, 0.0)
        vm.onTrackTap(10)
        val tap = vm.state.first { it.tapped != null }.tapped!!
        assertEquals(30.0, tap.distanceFromStartM, 0.5)
        assertEquals(10_000L, tap.elapsedMs)
        // the line grows with the stored points; the tap stays on it
        repo.walk(track.copy(pointCount = 20), 5, from = 20)
        assertNotNull(vm.state.first { it.line.size == 25 }.tapped)
    }

    @Test
    fun `a tap on one recording is not shown on the next one`() = runTest(dispatcher) {
        val first = repo.createTrack("first", startedAt = 0)
        repo.walk(first, 20)
        tracking.state.update { it.copy(serviceRunning = true) }
        val vm = recordViewModel(testSettings(tmp.root, backgroundScope))
        vm.state.first { it.line.size == 20 }
        vm.onTrackTap(10)
        assertNotNull(vm.state.first { it.tapped != null }.tapped)
        repo.updateTrack(repo.getTrack(first.id)!!.copy(status = TrackStatus.FINISHED, finishedAt = 60_000))
        val second = repo.createTrack("second", startedAt = 120_000)
        repo.walk(second, 15)
        val state = vm.state.first { it.track?.id == second.id && it.line.size == 15 }
        assertNull(state.tapped)
    }

    @Test
    fun `Detail loads the line once, scrubs, steps and exports through the exporter`() = runTest(dispatcher) {
        val track = repo.finishedTrack("walk", startedAt = 0)
        repo.walk(track, 30)
        var exported: Long? = null
        val exporter = GpxExporter { id -> exported = id; null }
        val vm = TrackDetailViewModel(repo, testSettings(tmp.root, backgroundScope), exporter, track.id, SavedStateHandle(), AppDispatchers(dispatcher, dispatcher))
        val state = vm.state.first { it.loaded }
        assertEquals(30, state.line.size)
        assertEquals(0, vm.cursor.first { it != null }!!.index)
        vm.scrubToFraction(1f)
        assertEquals(29, vm.cursor.first { it?.index == 29 }!!.index)
        vm.stepCursor(+5)
        assertEquals(29, vm.cursor.value!!.index)
        vm.stepCursor(-2)
        assertEquals(27, vm.cursor.first { it?.index == 27 }!!.index)
        assertNull(vm.shareIntent())
        assertEquals(track.id, exported)
    }

    @Test
    fun `Detail writes back the gain and loss of the current algorithm once`() = runTest(dispatcher) {
        val track = repo.finishedTrack("walk", startedAt = 0)
        repo.walk(track, 30)
        // the row as an older version stored it: GPS noise counted as climbing
        repo.updateTrack(repo.getTrack(track.id)!!.copy(elevationGainM = 120.0, elevationLossM = 80.0))
        val vm = TrackDetailViewModel(repo, testSettings(tmp.root, backgroundScope), { null }, track.id, SavedStateHandle(), AppDispatchers(dispatcher, dispatcher))
        assertEquals(0.0, vm.state.first { it.loaded }.track!!.elevationGainM, 0.0)
        val stored = repo.getTrack(track.id)!!
        assertEquals(0.0, stored.elevationGainM, 0.0)
        assertEquals(TrackStatus.FINISHED, stored.status)
    }
}
