package io.treklog.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object TimeFormat {
    /** "h:mm:ss" or "mm:ss" for durations. */
    fun duration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val dateShort: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
    private val timeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun dateTime(epochMs: Long): String = dateTime.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun dateShort(epochMs: Long): String = dateShort.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** "HH:mm:ss" wall-clock time in the device zone (track cursor). */
    fun timeOfDay(epochMs: Long): String = timeOfDay.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
