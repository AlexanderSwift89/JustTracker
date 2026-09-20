package com.justtracker.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.justtracker.app.domain.maps.LatLonBox
import com.justtracker.app.domain.maps.OfflineRegion
import com.justtracker.app.domain.maps.RegionError
import com.justtracker.app.domain.maps.RegionSource
import com.justtracker.app.domain.maps.RegionStatus
import kotlinx.coroutines.flow.Flow
import java.io.File

/** One offline map region (downloaded from the catalogue or imported by the user). */
@Entity(tableName = "offline_regions")
data class OfflineRegionEntity(
    @PrimaryKey val id: String,
    val nameEn: String,
    val nameRu: String,
    /** File name inside the maps directory (`<id>.map`, or `<id>.map.part` while downloading). */
    val fileName: String,
    val sizeBytes: Long,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val source: String,
    val status: String,
    /** DownloadManager id while QUEUED / DOWNLOADING. */
    val downloadId: Long?,
    val errorReason: String?,
    val updatedAt: Long,
) {
    fun toDomain(mapsDir: File, progress: Float? = null, waitingForNetwork: Boolean = false): OfflineRegion {
        val status = runCatching { RegionStatus.valueOf(this.status) }.getOrDefault(RegionStatus.ERROR)
        return OfflineRegion(
            id = id,
            nameEn = nameEn,
            nameRu = nameRu,
            file = if (status == RegionStatus.READY) File(mapsDir, fileName).absolutePath else null,
            sizeBytes = sizeBytes,
            box = LatLonBox(minLat, minLon, maxLat, maxLon),
            source = runCatching { RegionSource.valueOf(source) }.getOrDefault(RegionSource.IMPORT),
            status = status,
            progress = progress,
            waitingForNetwork = waitingForNetwork,
            error = errorReason?.let { r -> runCatching { RegionError.valueOf(r) }.getOrNull() },
            updatedAt = updatedAt,
        )
    }
}

@Dao
interface OfflineRegionDao {
    @Query("SELECT * FROM offline_regions ORDER BY nameEn")
    fun observeAll(): Flow<List<OfflineRegionEntity>>

    @Query("SELECT * FROM offline_regions")
    suspend fun getAll(): List<OfflineRegionEntity>

    @Query("SELECT * FROM offline_regions WHERE id = :id")
    suspend fun getById(id: String): OfflineRegionEntity?

    @Query("SELECT * FROM offline_regions WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getByDownloadId(downloadId: Long): OfflineRegionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(region: OfflineRegionEntity)

    @Query("DELETE FROM offline_regions WHERE id = :id")
    suspend fun delete(id: String)
}
