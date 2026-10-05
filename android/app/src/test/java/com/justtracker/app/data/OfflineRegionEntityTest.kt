package com.justtracker.app.data

import com.justtracker.app.data.db.OfflineRegionEntity
import com.justtracker.app.domain.maps.RegionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class OfflineRegionEntityTest {
    private fun row(status: String) = OfflineRegionEntity(
        id = "malta", nameEn = "Malta", nameRu = "Мальта", fileName = "malta.map", sizeBytes = 1,
        minLat = 35.8, minLon = 14.2, maxLat = 36.1, maxLon = 14.6, source = "CATALOG", status = status,
        downloadId = null, errorReason = null, updatedAt = 0,
    )

    @Test
    fun `stored statuses are read back`() {
        for (s in RegionStatus.entries) assertEquals(s, row(s.name).regionStatus)
    }

    @Test
    fun `an unknown status reads as an error instead of throwing`() {
        val r = row("FROM_THE_FUTURE")
        assertEquals(RegionStatus.ERROR, r.regionStatus)
        assertNull(r.toDomain(File("maps")).file)
    }
}
