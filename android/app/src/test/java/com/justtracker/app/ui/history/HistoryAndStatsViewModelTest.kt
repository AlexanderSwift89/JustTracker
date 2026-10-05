package com.justtracker.app.ui.history

import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.testing.FakeTrackDao
import com.justtracker.app.testing.finishedTrack
import com.justtracker.app.testing.testSettings
import com.justtracker.app.ui.stats.StatsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryAndStatsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = TrackRepository(FakeTrackDao())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `History lists finished tracks newest first, not the active one`() = runTest(dispatcher) {
        repo.finishedTrack("old", startedAt = 1_000)
        repo.finishedTrack("new", startedAt = 2_000)
        repo.createTrack("recording", startedAt = 3_000)
        val vm = HistoryViewModel(repo, testSettings(tmp.root, backgroundScope))
        val state = vm.state.first { it.loaded }
        assertEquals(listOf("new", "old"), state.tracks.map { it.name })
    }

    @Test
    fun `History renames within the limit and deletes`() = runTest(dispatcher) {
        val t = repo.finishedTrack("old", startedAt = 1_000)
        val vm = HistoryViewModel(repo, testSettings(tmp.root, backgroundScope))
        vm.rename(t.id, "  " + "x".repeat(150) + "  ").join()
        assertEquals("x".repeat(Track.MAX_NAME_LENGTH), repo.getTrack(t.id)!!.name)
        vm.delete(t.id).join()
        assertTrue(vm.state.first { it.loaded }.tracks.isEmpty())
    }

    @Test
    fun `Statistics sums periods and activity types in the given zone`() = runTest(dispatcher) {
        val zone = ZoneId.of("UTC")
        val now = LocalDateTime.now(zone)
        fun at(daysAgo: Long) = now.minusDays(daysAgo).atZone(zone).toInstant().toEpochMilli()
        repo.finishedTrack("walk", startedAt = at(0), distanceM = 1000.0, type = ActivityType.WALK)
        repo.finishedTrack("ride", startedAt = at(0), distanceM = 20_000.0, type = ActivityType.BIKE, avgSpeedMps = 5.0)
        repo.finishedTrack("last year", startedAt = at(400), distanceM = 3000.0, type = ActivityType.WALK)
        val vm = StatsViewModel(repo, testSettings(tmp.root, backgroundScope)) { zone }
        val state = vm.state.first { it.loaded }
        assertEquals(3, state.summaries.total.count)
        assertEquals(2, state.summaries.week.count)
        assertEquals("ride", state.summaries.longest!!.name)
        assertEquals(listOf(ActivityType.BIKE, ActivityType.WALK), state.byType.map { it.type })
        assertEquals(2, state.byType.first { it.type == ActivityType.WALK }.count)
    }
}
