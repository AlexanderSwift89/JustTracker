package com.justtracker.app.domain.recording

import com.justtracker.app.domain.geo.FilterResult
import com.justtracker.app.domain.geo.JumpStreak
import com.justtracker.app.domain.geo.LatLon
import com.justtracker.app.domain.geo.LocationFilter
import com.justtracker.app.domain.geo.Sample
import com.justtracker.app.domain.geo.StartCheck
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.stats.Acceleration
import com.justtracker.app.domain.stats.AccelerationTrace
import com.justtracker.app.domain.stats.IncrementalStats
import com.justtracker.app.domain.stats.LiveMotion

/** One GPS fix as the recorder needs it; the service maps `android.location.Location` to it. */
data class Fix(
    /** UTC time, position, horizontal accuracy (Float.MAX_VALUE when unknown) and reported speed. */
    val sample: Sample,
    /** Fix time on the monotonic clock: unlike the UTC time it never steps when the clock is corrected. */
    val monotonicMs: Long,
    val speedAccuracyMps: Float? = null,
    val altitudeM: Double? = null,
    /** Vertical accuracy of [altitudeM]; null when not reported (quality gate of the elevation gain, §3.4). */
    val verticalAccuracyM: Float? = null,
    val bearingDeg: Float? = null,
)

/** Running totals of a recording, written into the track row with every stored point. */
data class TrackTotals(
    val distanceM: Double = 0.0,
    val movingTimeMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val pointCount: Int = 0,
)

/** Where the recorder stores points: Room in the service, an in-memory fake in tests. */
interface RecorderStore {
    /** Stores [point] and [totals] of its track in one transaction; false when the storage is full. */
    suspend fun insert(point: TrackPoint, totals: TrackTotals): Boolean

    /** Deletes [segment] when it holds at most [maxPoints] points; returns how many were deleted (0 for a real segment). */
    suspend fun deleteSegmentIfShort(trackId: Long, segment: Int, maxPoints: Int): Int
}

enum class StopReason { STORAGE_FULL, POINT_LIMIT }

/** What one fix changed: everything the live state needs, applied as one update. */
data class FixOutcome(
    /** Position for the map marker; null keeps the previous one (a fix too coarse for it). */
    val marker: LatLon?,
    /** Live speed; null keeps the previous value. */
    val speedMps: Float?,
    /**
     * The fix passed the accuracy gate (rule 1) and is newer than the last one that did: the receiver is delivering
     * positions. Coarse or repeated fixes alone mean "searching GPS" (US-06) — underground they kept the panel on a
     * 10-minute-old speed beside "GPS" (TC-157, D-39).
     */
    val usable: Boolean,
    val acceleration: Acceleration?,
    val accelerationTrace: AccelerationTrace,
    /** Points stored by this fix: 0, 1, or 2 (a confirmed segment start and the fix itself). */
    val stored: Int,
    /** Set when the recording must finish now. */
    val stop: StopReason?,
)

/**
 * The recording rules for one active track (docs/06_system_analysis.md §3.1, §3.3, ADR-27): live speed and
 * acceleration from every fix (OBS-12, ADR-20), the segment's first fix confirmed by the next one (rule 6), the
 * storage filter (rules 1–5), re-anchoring after a run of consistent jumps (rule 7, a short stray start deleted),
 * running totals, the point limit. Pure Kotlin: [TrackingService] owns the lifecycle, the notification and I/O.
 *
 * @param startSegment segment of the next stored point (0 for a new track, max + 1 after recovery).
 * @param totals totals of the points stored so far (from the track row after recovery).
 * @param log debug-only geo log (positions never reach release logs, SEC-12).
 */
class TrackRecorder(
    val trackId: Long,
    startSegment: Int,
    totals: TrackTotals,
    maxAccuracyM: Float,
    private val store: RecorderStore,
    private val maxPoints: Int = MAX_POINTS_PER_TRACK,
    private val log: (() -> String) -> Unit = {},
) {
    private class PendingFix(val fix: Fix)

    private val filter = LocationFilter(maxAccuracyM = maxAccuracyM)
    private val jumps = JumpStreak(filter)
    private val stats = IncrementalStats(
        distanceM = totals.distanceM,
        movingTimeMs = totals.movingTimeMs,
        maxSpeedMps = totals.maxSpeedMps,
        pointCount = totals.pointCount,
    )
    private val motion = LiveMotion()
    private var pendingStart: PendingFix? = null
    private var lastUsableMs = Long.MIN_VALUE

    /** Segment of the next stored point. */
    var segment: Int = startSegment
        private set

    val totals: TrackTotals
        get() = TrackTotals(stats.distanceM, stats.movingTimeMs, stats.maxSpeedMps, stats.avgSpeedMps, stats.pointCount)

    suspend fun onFix(fix: Fix): FixOutcome {
        val sample = fix.sample
        val accurate = filter.isAccurate(sample)
        val usable = accurate && fix.monotonicMs > lastUsableMs
        if (usable) lastUsableMs = fix.monotonicMs
        // Every fix feeds the live speed and acceleration (an inaccurate one only as "no speed") before the storage
        // rules drop near-duplicates.
        motion.onFix(fix.monotonicMs, sample.speedMps, fix.speedAccuracyMps, accurate)
        val result = store(fix)
        val doppler = motion.hasDopplerSpeed(fix.monotonicMs)
        // Doppler speed of every fix when the receiver gives a trustworthy one, the stored points' speed otherwise.
        val speed = when {
            doppler -> motion.speedMps
            result.stored > 0 -> stats.currentSpeedMps
            else -> null
        }
        return FixOutcome(
            marker = if (sample.accuracyM <= POSITION_MARKER_ACCURACY_M) LatLon(sample.lat, sample.lon) else null,
            speedMps = speed,
            usable = usable,
            acceleration = motion.acceleration,
            accelerationTrace = motion.trace(),
            stored = result.stored,
            stop = result.stop,
        )
    }

    /** The user paused: the next fix starts a new segment that is not joined to this one. */
    fun pause() {
        stats.breakSegment()
        motion.reset()
        pendingStart = null
        jumps.reset()
    }

    fun resume() {
        segment += 1
    }

    private class Stored(var stored: Int = 0, var stop: StopReason? = null)

    private suspend fun store(fix: Fix): Stored {
        val out = Stored()
        val sample = fix.sample
        if (stats.lastSample == null) {
            // First fix of a segment (start, resume, recovery): nothing to check it against, so it waits for the
            // next fix (rule 6) instead of becoming the reference unchecked.
            val pending = pendingStart
            if (pending == null) {
                if (filter.evaluate(null, sample) is FilterResult.Accepted) pendingStart = PendingFix(fix)
                return out
            }
            when (filter.confirmStart(pending.fix.sample, sample)) {
                StartCheck.IGNORE -> return out
                StartCheck.REPLACE -> {
                    log { "segment start replaced: lat=${pending.fix.sample.lat} lon=${pending.fix.sample.lon} -> lat=${sample.lat} lon=${sample.lon}" }
                    pendingStart = PendingFix(fix)
                    return out
                }
                StartCheck.CONFIRMED -> {
                    pendingStart = null
                    if (!record(pending.fix, distanceM = 0.0, speedMps = pending.fix.sample.speedMps ?: 0f, out)) return out
                }
            }
        }
        when (val result = filter.evaluate(stats.lastSample, sample)) {
            is FilterResult.Accepted -> {
                jumps.reset()
                record(fix, result.distanceM, result.speedMps, out)
            }
            is FilterResult.Rejected -> {
                log { "rejected ${result.reason} acc=${sample.accuracyM} t=${sample.timestamp} lat=${sample.lat} lon=${sample.lon} prev=${stats.lastSample?.lat}" }
                when (result.reason) {
                    FilterResult.Reason.IMPLAUSIBLE_SPEED -> if (jumps.onImplausible(sample)) reanchor(fix, out)
                    FilterResult.Reason.TOO_CLOSE -> jumps.reset()
                    FilterResult.Reason.INACCURATE, FilterResult.Reason.NOT_NEWER -> Unit
                }
            }
        }
        return out
    }

    /**
     * Consistent fixes keep arriving far from the last recorded one (rule 7): that one was the outlier. The track
     * continues from [fix] in a new segment, so no line joins the two places; a stray start of a few points is
     * deleted instead, so it neither shows on the map nor widens the fit.
     */
    private suspend fun reanchor(fix: Fix, out: Stored) {
        log { "re-anchored at lat=${fix.sample.lat} lon=${fix.sample.lon}" }
        val deleted = store.deleteSegmentIfShort(trackId, segment, STRAY_SEGMENT_MAX_POINTS)
        if (deleted == 0) segment += 1
        stats.pointCount -= deleted
        stats.breakSegment()
        pendingStart = null
        record(fix, distanceM = 0.0, speedMps = fix.sample.speedMps ?: 0f, out)
    }

    /** Stores an accepted fix; false when the recording must finish because of it (storage full, point limit). */
    private suspend fun record(fix: Fix, distanceM: Double, speedMps: Float, out: Stored): Boolean {
        val sample = fix.sample
        stats.accept(sample, distanceM, speedMps)
        motion.onRecorded(fix.monotonicMs, sample.speedMps, speedMps)
        val point = TrackPoint(
            trackId = trackId,
            segment = segment,
            timestamp = sample.timestamp,
            lat = sample.lat,
            lon = sample.lon,
            altitudeM = fix.altitudeM,
            accuracyM = sample.accuracyM,
            speedMps = speedMps,
            bearingDeg = fix.bearingDeg,
            verticalAccuracyM = fix.verticalAccuracyM,
            // Only for the receiver's own speed: a non-null accuracy then proves a Doppler speed (§3.11).
            speedAccuracyMps = fix.speedAccuracyMps.takeIf { sample.speedMps != null && speedMps == sample.speedMps },
        )
        if (!store.insert(point, totals)) {
            out.stop = StopReason.STORAGE_FULL
            return false
        }
        out.stored++
        if (stats.pointCount >= maxPoints) {
            out.stop = StopReason.POINT_LIMIT
            return false
        }
        return true
    }

    companion object {
        const val MAX_POINTS_PER_TRACK = 100_000

        /** Fixes coarser than this do not move the position marker. */
        const val POSITION_MARKER_ACCURACY_M = 100f

        /** A segment this short that the track moved away from is a stray start, not a part of the route. */
        const val STRAY_SEGMENT_MAX_POINTS = 3
    }
}
