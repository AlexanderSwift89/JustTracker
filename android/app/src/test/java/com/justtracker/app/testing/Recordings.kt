package com.justtracker.app.testing

import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.geo.Sample
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.recording.Fix
import com.justtracker.app.domain.recording.RecorderStore
import com.justtracker.app.domain.recording.TrackRecorder
import com.justtracker.app.domain.recording.TrackTotals
import com.justtracker.app.domain.stats.Acceleration
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToLong
import kotlin.random.Random

/** Points in memory, deleted the way the Room store deletes a short stray segment. */
class MemoryRecorderStore : RecorderStore {
    val points = mutableListOf<TrackPoint>()
    private var nextId = 1L

    override suspend fun insert(point: TrackPoint, totals: TrackTotals): Boolean {
        points += point.copy(id = nextId++)
        return true
    }

    override suspend fun deleteSegmentIfShort(trackId: Long, segment: Int, maxPoints: Int): Int {
        val n = points.count { it.segment == segment }
        if (n == 0 || n > maxPoints) return 0
        points.removeAll { it.segment == segment }
        return n
    }
}

/** What the receiver reports as its speed. */
enum class Reported {
    /** The true speed plus noise, with its accuracy. */
    DOPPLER,

    /** 0 whatever the motion — the emulator's `geo fix` without a velocity (D-02). */
    ZERO,

    /** Nothing (a network position). */
    NONE,
}

/** A recording: the points the track kept and, per fix, the live acceleration shown at that moment. */
class Recording(val points: List<TrackPoint>, val fixTimes: List<Long>, val live: List<Acceleration?>)

/**
 * Drives a [TrackRecorder] — the real storage rules, so the points are thinned as in the app — north from 55° N 37° E
 * at the true speed [speedAt] (seconds → m/s) for [seconds] at [hz] fixes a second. Fixes the receiver misses
 * ([gap]) are skipped.
 *
 * @param reported what the receiver reports; [speedOverride] replaces the reported speed at a fix (spikes).
 * @param sigma reported speed accuracy (null: none), [sigmaOverride] per fix.
 * @param noise uniform Doppler noise ±this, m/s.
 * @param positionNoise uniform position noise ±this, m. Without it (or [round6]) a Doppler speed equals the displacement
 *   speed to the last bit and passes for one derived from the position when no accuracy is stored (§3.11).
 * @param round6 positions rounded to 6 decimals, as `geo fix` sends them.
 * @param speedMissing fixes without a reported speed.
 */
fun recordDrive(
    seconds: Int,
    hz: Int = 1,
    reported: Reported = Reported.DOPPLER,
    sigma: Float? = 0.2f,
    noise: Double = 0.0,
    positionNoise: Double = 0.0,
    round6: Boolean = false,
    seed: Int = 1,
    startMs: Long = 1_000_000L,
    gap: (Double) -> Boolean = { false },
    speedOverride: (Double) -> Float? = { null },
    speedMissing: (Double) -> Boolean = { false },
    sigmaOverride: (Double) -> Float? = { null },
    speedAt: (Double) -> Double,
): Recording = runBlocking {
    val store = MemoryRecorderStore()
    val recorder = TrackRecorder(trackId = 1, startSegment = 0, totals = TrackTotals(), maxAccuracyM = 50f, store = store)
    val rnd = Random(seed)
    val stepMs = 1000L / hz
    val fixTimes = mutableListOf<Long>()
    val live = mutableListOf<Acceleration?>()
    var north = 0.0
    var previousT = 0.0
    for (i in 0..seconds * hz) {
        val t = i * stepMs / 1000.0
        // Trapezoidal distance of the true speed.
        north += (speedAt(previousT) + speedAt(t)) / 2.0 * (t - previousT)
        previousT = t
        if (gap(t)) continue
        var lat = 55.0 + (north + rnd.within(positionNoise)) / Geo.METERS_PER_DEGREE
        if (round6) lat = (lat * 1e6).roundToLong() / 1e6
        val speed = when {
            speedMissing(t) -> null
            reported == Reported.DOPPLER -> speedOverride(t) ?: maxOf(0.0, speedAt(t) + rnd.within(noise)).toFloat()
            reported == Reported.ZERO -> 0f
            else -> null
        }
        val timeMs = startMs + i * stepMs
        val fix = Fix(
            sample = Sample(timestamp = timeMs, lat = lat, lon = 37.0, accuracyM = 5f, speedMps = speed),
            monotonicMs = timeMs,
            speedAccuracyMps = sigmaOverride(t) ?: sigma.takeIf { reported == Reported.DOPPLER },
        )
        fixTimes += timeMs
        live += recorder.onFix(fix).acceleration
    }
    Recording(store.points.toList(), fixTimes, live)
}

/** Uniform in ±[range]; 0 without a range. */
private fun Random.within(range: Double): Double = if (range == 0.0) 0.0 else nextDouble(-range, range)

/**
 * The emulator's stop-and-go profile from docs/08_test_plan.md §2: standing 5 s, +2 m/s² to 20 m/s, steady 12 s,
 * −2.9 m/s² to a stop, standing to 50 s; speeds as `geo fix` sends them, positions rounded to 6 decimals, no accuracy.
 */
fun stopAndGo(): Recording {
    val v = DoubleArray(51)
    var speed = 0.0
    for (i in 0 until 50) {
        if (i in 5 until 15) speed += 2.0 else if (i >= 27) speed = maxOf(0.0, speed - 2.9)
        v[i] = speed
    }
    // The script moves by the speed set at each step: the speed holds over the second before it.
    return recordDrive(seconds = 49, sigma = null, round6 = true, speedAt = { t -> v[t.toInt()] })
}
