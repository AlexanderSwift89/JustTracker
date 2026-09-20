package com.justtracker.app.util

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

    /** Always "h:mm:ss" (e.g. "0:12:03") so a glance never confuses mm:ss with h:mm — used by the live HUD. */
    fun durationClock(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    private val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val dateShort: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
    private val timeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Localized date + time; [locale] defaults to the process locale, which AppLocale keeps equal to the app language. */
    fun dateTime(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        dateTime.withLocale(locale).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** "d MMM, HH:mm" with month names in [locale] (track auto-names). */
    fun dateShort(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        dateShort.withLocale(locale).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** "HH:mm:ss" wall-clock time in the device zone (track cursor). */
    fun timeOfDay(epochMs: Long): String = timeOfDay.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
