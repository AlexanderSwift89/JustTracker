package com.justtracker.app.data.repo

import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.track.TrackLine
import com.justtracker.app.domain.track.TrackLineBuilder
import com.justtracker.app.util.traced

/**
 * The active recording's line, kept up to date from the database tail (ADR-25). Each call reads only the points
 * recorded since the previous one and extends a [TrackLineBuilder]; a full read happens once per track and when the
 * stored line changed under it (a stray start deleted, a point older than the known ones). Before 1.1.2 every fix
 * re-read and rebuilt the whole track — O(n²) over a recording.
 *
 * Not thread-safe: one coroutine calls [update] at a time (a `map` over the active-track flow).
 */
class LiveTrackLine(private val repo: TrackRepository) {
    private val builder = TrackLineBuilder()
    private var trackId = NO_TRACK
    private var lastId = 0L
    private var lastTimestamp = Long.MIN_VALUE
    private var current: TrackLine = TrackLine.EMPTY

    /** Whole-track reads so far (tests, diagnostics). */
    var fullLoads = 0
        private set

    /** The line of [track] — the active recording, null when there is none. */
    suspend fun update(track: Track?): TrackLine {
        if (track == null) {
            if (trackId != NO_TRACK) clear()
            return current
        }
        if (track.id != trackId) return reload(track.id)
        val tail = repo.pointsAfterIfIntact(track.id, lastId) ?: return reload(track.id)
        if (tail.isEmpty()) return current
        if (!continuesInTime(tail)) return reload(track.id)
        current = traced("record.trackLine") {
            builder.append(tail)
            builder.snapshot()
        }
        lastId = tail.last().id
        lastTimestamp = tail.last().timestamp
        return current
    }

    private suspend fun reload(id: Long): TrackLine {
        val points = repo.getPoints(id)
        fullLoads++
        builder.reset()
        trackId = id
        current = traced("record.trackLine") {
            builder.append(points)
            builder.snapshot()
        }
        lastId = points.maxOfOrNull { it.id } ?: 0L
        lastTimestamp = points.lastOrNull()?.timestamp ?: Long.MIN_VALUE
        return current
    }

    /** The full read orders by time: a tail that goes back in time would not extend that order, so it is re-read. */
    private fun continuesInTime(tail: List<TrackPoint>): Boolean {
        var t = lastTimestamp
        for (p in tail) {
            if (p.timestamp < t) return false
            t = p.timestamp
        }
        return true
    }

    private fun clear() {
        builder.reset()
        trackId = NO_TRACK
        lastId = 0L
        lastTimestamp = Long.MIN_VALUE
        current = TrackLine.EMPTY
    }

    private companion object {
        const val NO_TRACK = -1L
    }
}
