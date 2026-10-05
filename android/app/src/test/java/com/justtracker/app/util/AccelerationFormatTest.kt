package com.justtracker.app.util

import com.justtracker.app.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AccelerationFormatTest {
    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun `sign is explicit and the minus keeps the width of the plus`() {
        assertEquals("+1,8", UnitFormatter.signedDecimal(1.84, ru))
        assertEquals("−0.4", UnitFormatter.signedDecimal(-0.44, Locale.US))
    }

    @Test
    fun `a value that rounds to zero has no sign`() {
        assertEquals("0,0", UnitFormatter.signedDecimal(0.04, ru))
        assertEquals("0.0", UnitFormatter.signedDecimal(-0.04, Locale.US))
        assertEquals("0.0", UnitFormatter.signedDecimal(0.0, Locale.US))
    }

    @Test
    fun `imperial units are feet per second squared`() {
        assertEquals(2.0, UnitFormatter.accelerationInUnits(2.0, UnitSystem.METRIC), 0.0)
        assertEquals(6.56168, UnitFormatter.accelerationInUnits(2.0, UnitSystem.IMPERIAL), 1e-5)
    }
}
