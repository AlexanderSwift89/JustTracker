package io.treklog.app.util

import android.content.Context
import io.treklog.app.R
import io.treklog.app.domain.model.UnitSystem
import java.util.Locale

/** Converts SI values to user-facing strings. The only place where units are applied (ADR-05). */
class UnitFormatter(private val context: Context, val units: UnitSystem) {
    private val locale: Locale get() = context.resources.configuration.locales[0] ?: Locale.getDefault()

    fun distance(meters: Double): String = when (units) {
        UnitSystem.METRIC -> if (meters < 1000) {
            "${meters.toInt()} ${context.getString(R.string.unit_m)}"
        } else {
            "${fmt(meters / 1000, 2)} ${context.getString(R.string.unit_km)}"
        }
        UnitSystem.IMPERIAL -> {
            val miles = meters / METERS_PER_MILE
            if (miles < 0.1) {
                "${(meters * FEET_PER_METER).toInt()} ${context.getString(R.string.unit_ft)}"
            } else {
                "${fmt(miles, 2)} ${context.getString(R.string.unit_mi)}"
            }
        }
    }

    /** Speed value without unit, one decimal — for the big live number. */
    fun speedValue(mps: Double): String = fmt(speedInUnits(mps), 1)

    fun speedUnit(): String = when (units) {
        UnitSystem.METRIC -> context.getString(R.string.unit_kmh)
        UnitSystem.IMPERIAL -> context.getString(R.string.unit_mph)
    }

    fun speed(mps: Double): String = "${speedValue(mps)} ${speedUnit()}"

    fun elevation(meters: Double, signed: Boolean = false): String {
        val value = when (units) {
            UnitSystem.METRIC -> meters
            UnitSystem.IMPERIAL -> meters * FEET_PER_METER
        }
        val unit = when (units) {
            UnitSystem.METRIC -> context.getString(R.string.unit_m)
            UnitSystem.IMPERIAL -> context.getString(R.string.unit_ft)
        }
        val prefix = if (signed && value > 0) "+" else ""
        return "$prefix${value.toInt()} $unit"
    }

    /** Pace as m:ss per km/mile; null when no distance. */
    fun pace(secPerMeter: Double?): String {
        if (secPerMeter == null || secPerMeter <= 0) return "—"
        val secPerUnit = when (units) {
            UnitSystem.METRIC -> secPerMeter * 1000
            UnitSystem.IMPERIAL -> secPerMeter * METERS_PER_MILE
        }
        if (secPerUnit > 99 * 60) return "—"
        val m = (secPerUnit / 60).toInt()
        val s = (secPerUnit % 60).toInt()
        val unit = when (units) {
            UnitSystem.METRIC -> context.getString(R.string.unit_pace_km)
            UnitSystem.IMPERIAL -> context.getString(R.string.unit_pace_mi)
        }
        return String.format(Locale.US, "%d:%02d", m, s) + unit
    }

    private fun speedInUnits(mps: Double) = when (units) {
        UnitSystem.METRIC -> mps * 3.6
        UnitSystem.IMPERIAL -> mps * MPH_PER_MPS
    }

    private fun fmt(v: Double, decimals: Int) = String.format(locale, "%.${decimals}f", v)

    companion object {
        const val METERS_PER_MILE = 1609.344
        const val FEET_PER_METER = 3.28084
        const val MPH_PER_MPS = 2.23694
    }
}
