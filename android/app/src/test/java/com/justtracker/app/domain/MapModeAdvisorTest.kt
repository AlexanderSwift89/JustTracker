package com.justtracker.app.domain

import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.maps.MapModeAdvisor
import com.justtracker.app.domain.maps.MapModeReason
import com.justtracker.app.domain.maps.MapModeSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapModeAdvisorTest {
    @Test
    fun `online mode without internet suggests offline when regions exist`() {
        assertEquals(
            MapModeSuggestion(MapMode.OFFLINE, MapModeReason.INTERNET_LOST),
            MapModeAdvisor.suggest(MapMode.ONLINE, online = false, hasReadyRegions = true, declinedFor = null),
        )
    }

    @Test
    fun `online mode without internet and without regions asks nothing`() {
        assertNull(MapModeAdvisor.suggest(MapMode.ONLINE, online = false, hasReadyRegions = false, declinedFor = null))
    }

    @Test
    fun `offline mode with internet suggests online regardless of regions`() {
        val expected = MapModeSuggestion(MapMode.ONLINE, MapModeReason.INTERNET_RESTORED)
        assertEquals(expected, MapModeAdvisor.suggest(MapMode.OFFLINE, online = true, hasReadyRegions = true, declinedFor = null))
        assertEquals(expected, MapModeAdvisor.suggest(MapMode.OFFLINE, online = true, hasReadyRegions = false, declinedFor = null))
    }

    @Test
    fun `matching mode and connectivity is quiet`() {
        assertNull(MapModeAdvisor.suggest(MapMode.ONLINE, online = true, hasReadyRegions = true, declinedFor = null))
        assertNull(MapModeAdvisor.suggest(MapMode.OFFLINE, online = false, hasReadyRegions = true, declinedFor = null))
    }

    @Test
    fun `a declined prompt is not repeated until connectivity changes`() {
        assertNull(MapModeAdvisor.suggest(MapMode.ONLINE, online = false, hasReadyRegions = true, declinedFor = false))
        // connectivity flipped back → new situation → ask again (now the other way is irrelevant: mode ONLINE + online = quiet)
        assertNull(MapModeAdvisor.suggest(MapMode.ONLINE, online = true, hasReadyRegions = true, declinedFor = false))
        // offline mode, user declined switching to online while online; still offline-declined only for online=true
        assertNull(MapModeAdvisor.suggest(MapMode.OFFLINE, online = true, hasReadyRegions = true, declinedFor = true))
        assertEquals(
            MapModeSuggestion(MapMode.ONLINE, MapModeReason.INTERNET_RESTORED),
            MapModeAdvisor.suggest(MapMode.OFFLINE, online = true, hasReadyRegions = true, declinedFor = false),
        )
    }
}
