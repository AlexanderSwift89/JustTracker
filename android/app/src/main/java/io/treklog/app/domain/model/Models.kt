package io.treklog.app.domain.model

enum class TrackStatus { RECORDING, PAUSED, FINISHED }

enum class ActivityType { WALK, RUN, BIKE, CAR, OTHER, UNKNOWN }

enum class UnitSystem { METRIC, IMPERIAL }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A persisted GPS sample. All values are SI (meters, m/s, epoch millis, WGS84 degrees). */
data class TrackPoint(
    val id: Long = 0,
    val trackId: Long,
    val segment: Int,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
)

data class Track(
    val id: Long = 0,
    val name: String,
    val status: TrackStatus,
    val activityType: ActivityType,
    val activityManual: Boolean,
    val startedAt: Long,
    val finishedAt: Long?,
    val distanceM: Double = 0.0,
    val movingTimeMs: Long = 0,
    val totalTimeMs: Long = 0,
    val pausedTimeMs: Long = 0,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val pointCount: Int = 0,
) {
    /**
     * Recording time: wall-clock from Start to Stop (or to [nowMs] while the track is active),
     * pauses included — the primary time shown to the user. Derived, not stored, so older rows
     * are correct too. [movingTimeMs] stays the secondary "time in motion"; [totalTimeMs] is the
     * recording time without pauses.
     */
    fun recordingTimeMs(nowMs: Long = System.currentTimeMillis()): Long =
        ((finishedAt ?: nowMs) - startedAt).coerceAtLeast(0)
}

/** Result of a full statistics pass over a track's points. */
data class TrackStats(
    val distanceM: Double,
    val movingTimeMs: Long,
    val totalTimeMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val pointCount: Int,
) {
    /** Seconds per meter; null when no distance. */
    val paceSecPerMeter: Double?
        get() = if (distanceM > 0) (movingTimeMs / 1000.0) / distanceM else null

    companion object {
        val EMPTY = TrackStats(0.0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0)
    }
}

data class AppSettings(
    val units: UnitSystem = UnitSystem.METRIC,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val maxAccuracyM: Int = 50,
    val keepScreenOn: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Show Wikipedia-backed places around the user / the track (Overpass). */
    val poiEnabled: Boolean = true,
    /** Read a place aloud automatically when the user gets within 150 m while recording. Off by default. */
    val poiAutoSpeak: Boolean = false,
)
