package com.justtracker.app.data.maps

import android.content.Context
import android.net.Uri
import com.justtracker.app.data.db.OfflineRegionDao
import com.justtracker.app.data.db.OfflineRegionEntity
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.domain.maps.OfflineRegion
import com.justtracker.app.domain.maps.RegionCoverage
import com.justtracker.app.domain.maps.RegionError
import com.justtracker.app.domain.maps.RegionEvent
import com.justtracker.app.domain.maps.RegionSource
import com.justtracker.app.domain.maps.RegionStatus
import com.justtracker.app.domain.maps.RegionTransitions
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Single façade for offline regions (docs/05_architecture.md §12): the UI lists and manages them,
 * the map consumes [readyCoverage]. The Room table is the source of truth; live download progress
 * is merged in from the system DownloadManager.
 */
class OfflineRegionStore(
    context: Context,
    private val dao: OfflineRegionDao,
    private val catalog: RegionCatalog,
    private val downloader: RegionDownloader,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    val mapsDir: File = MapsDirectory.resolve(appContext)

    /** False on the rare device without external storage: only Import works then. */
    val canDownload: Boolean = MapsDirectory.supportsDownloads(appContext)

    private val liveProgress = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    val regions: Flow<List<OfflineRegion>> = combine(dao.observeAll(), liveProgress) { rows, live ->
        rows.map { row ->
            val s = live[row.id]
            row.toDomain(mapsDir, progress = s?.progress, waitingForNetwork = s?.waitingForNetwork == true)
        }
    }

    /** READY regions only; the map rebuilds its tile provider when this changes. */
    val readyCoverage: StateFlow<List<RegionCoverage>> = dao.observeAll()
        .map { rows -> rows.mapNotNull { it.toDomain(mapsDir).coverage() } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Poll DownloadManager while anything is in flight (it has no progress broadcast).
        scope.launch {
            dao.observeAll()
                .map { rows -> rows.filter { it.status == RegionStatus.QUEUED.name || it.status == RegionStatus.DOWNLOADING.name } }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active.isEmpty()) {
                        liveProgress.value = emptyMap()
                        return@collectLatest
                    }
                    while (true) {
                        val snapshot = HashMap<String, DownloadStatus>()
                        for (row in active) {
                            val id = row.downloadId ?: continue
                            val s = downloader.status(id)
                            if (s == null) {
                                markLost(row)
                                continue
                            }
                            snapshot[row.id] = s
                            when {
                                s.successful || s.failed -> onDownloadFinished(id)
                                s.running && row.status != RegionStatus.DOWNLOADING.name ->
                                    dao.upsert(row.copy(status = RegionStatus.DOWNLOADING.name, updatedAt = now()))
                            }
                        }
                        liveProgress.value = snapshot
                        delay(POLL_MS)
                    }
                }
        }
    }

    suspend fun catalog(): List<CatalogRegion> = catalog.load()

    fun freeBytes(): Long = MapsDirectory.freeBytes(mapsDir)

    /** Starts (or retries) a catalogue download; returns the error that prevented it, or null. */
    suspend fun download(id: String): RegionError? {
        if (!canDownload) return RegionError.UNKNOWN
        val region = catalog.find(id) ?: return RegionError.UNKNOWN
        val existing = dao.getById(id)
        val next = RegionTransitions.next(existing?.status?.let { RegionStatus.valueOf(it) }, if (existing == null) RegionEvent.DOWNLOAD else RegionEvent.RETRY)
            ?: return null // already in progress or ready — nothing to do
        if (freeBytes() < region.sizeBytes + SPACE_MARGIN_BYTES) return RegionError.NO_SPACE

        val fileName = id + MapsDirectory.PART_SUFFIX
        existing?.downloadId?.let(downloader::cancel)
        File(mapsDir, fileName).delete()
        val current = settings.current()
        val title = region.name(current.language ?: AppLanguage.forDevice())
        val downloadId = downloader.enqueue(region.url, fileName, title, wifiOnly = current.mapsWifiOnly)
            ?: return RegionError.UNKNOWN
        dao.upsert(
            OfflineRegionEntity(
                id = id,
                nameEn = region.nameEn,
                nameRu = region.nameRu,
                fileName = fileName,
                sizeBytes = region.sizeBytes,
                minLat = region.box.minLat, minLon = region.box.minLon, maxLat = region.box.maxLat, maxLon = region.box.maxLon,
                source = RegionSource.CATALOG.name,
                status = next.name,
                downloadId = downloadId,
                errorReason = null,
                updatedAt = now(),
            ),
        )
        return null
    }

    suspend fun cancel(id: String) {
        val row = dao.getById(id) ?: return
        row.downloadId?.let(downloader::cancel)
        File(mapsDir, row.fileName).delete()
        dao.delete(id)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val row = dao.getById(id) ?: return@withContext
        row.downloadId?.let(downloader::cancel)
        File(mapsDir, row.fileName).delete()
        dao.delete(id)
    }

    /** Copies a user-picked `.map` into the maps dir, verifies its header and registers it. */
    suspend fun import(uri: Uri, displayName: String?): Result<OfflineRegion> = withContext(Dispatchers.IO) {
        val id = "import-" + UUID.randomUUID().toString().take(8)
        val part = File(mapsDir, id + MapsDirectory.PART_SUFFIX)
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
            } ?: error("Cannot open $uri")
            val info = MapFileInspector.inspect(part) ?: error("Not a Mapsforge map")
            val final = File(mapsDir, id + MapsDirectory.MAP_SUFFIX)
            if (!part.renameTo(final)) error("Rename failed")
            val name = (displayName ?: final.name).removeSuffix(MapsDirectory.MAP_SUFFIX).ifBlank { id }
            val row = OfflineRegionEntity(
                id = id, nameEn = name, nameRu = name, fileName = final.name, sizeBytes = info.sizeBytes,
                minLat = info.box.minLat, minLon = info.box.minLon, maxLat = info.box.maxLat, maxLon = info.box.maxLon,
                source = RegionSource.IMPORT.name, status = RegionStatus.READY.name,
                downloadId = null, errorReason = null, updatedAt = now(),
            )
            dao.upsert(row)
            row.toDomain(mapsDir)
        }.onFailure {
            AppLog.w("Map import failed", it)
            part.delete()
        }
    }

    /** Entry point for [DownloadCompleteReceiver] and the poller. */
    suspend fun onDownloadFinished(downloadId: Long) = withContext(Dispatchers.IO) {
        val row = dao.getByDownloadId(downloadId) ?: return@withContext
        val current = RegionStatus.valueOf(row.status)
        if (current == RegionStatus.READY || current == RegionStatus.ERROR) return@withContext
        val status = downloader.status(downloadId)
        when {
            status == null -> markLost(row)
            status.successful -> verify(row.copy(status = RegionStatus.VERIFYING.name, updatedAt = now()).also { dao.upsert(it) })
            status.failed -> {
                downloader.cancel(downloadId) // removes the partial file
                dao.upsert(row.copy(status = RegionStatus.ERROR.name, errorReason = RegionDownloader.errorFor(status.reason).name, downloadId = null, updatedAt = now()))
            }
            else -> Unit // still running
        }
    }

    /** Called on app start: reconciles rows with files on disk and with DownloadManager. */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val rows = dao.getAll()
        for (row in rows) {
            when (RegionStatus.valueOf(row.status)) {
                RegionStatus.READY -> if (!File(mapsDir, row.fileName).isFile) dao.delete(row.id)
                RegionStatus.QUEUED, RegionStatus.DOWNLOADING -> {
                    val id = row.downloadId
                    val s = id?.let(downloader::status)
                    if (s == null) markLost(row) else if (s.successful || s.failed) onDownloadFinished(id)
                }
                RegionStatus.VERIFYING -> verify(row)
                RegionStatus.ERROR -> Unit
            }
        }
        val known = dao.getAll().map { it.fileName }.toSet()
        mapsDir.listFiles()?.forEach { f ->
            when {
                f.name in known -> Unit
                f.name.endsWith(MapsDirectory.PART_SUFFIX) -> f.delete()
                f.name.endsWith(MapsDirectory.MAP_SUFFIX) -> adoptFile(f)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun verify(row: OfflineRegionEntity) {
        val part = File(mapsDir, row.fileName)
        val final = File(mapsDir, row.id + MapsDirectory.MAP_SUFFIX)
        val info = MapFileInspector.inspect(part)
        if (info == null || !(part == final || part.renameTo(final))) {
            part.delete()
            dao.upsert(row.copy(status = RegionStatus.ERROR.name, errorReason = RegionError.CORRUPT.name, downloadId = null, updatedAt = now()))
            return
        }
        dao.upsert(
            row.copy(
                fileName = final.name, sizeBytes = info.sizeBytes,
                minLat = info.box.minLat, minLon = info.box.minLon, maxLat = info.box.maxLat, maxLon = info.box.maxLon,
                status = RegionStatus.READY.name, downloadId = null, errorReason = null, updatedAt = now(),
            ),
        )
    }

    private suspend fun markLost(row: OfflineRegionEntity) {
        File(mapsDir, row.fileName).delete()
        dao.upsert(row.copy(status = RegionStatus.ERROR.name, errorReason = RegionError.LOST.name, downloadId = null, updatedAt = now()))
    }

    /** A `.map` file that appeared without a row (manual copy via USB, restored storage). */
    private suspend fun adoptFile(file: File) {
        val info = MapFileInspector.inspect(file) ?: return
        val id = "import-" + file.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val name = file.nameWithoutExtension
        dao.upsert(
            OfflineRegionEntity(
                id = id, nameEn = name, nameRu = name, fileName = file.name, sizeBytes = info.sizeBytes,
                minLat = info.box.minLat, minLon = info.box.minLon, maxLat = info.box.maxLat, maxLon = info.box.maxLon,
                source = RegionSource.IMPORT.name, status = RegionStatus.READY.name,
                downloadId = null, errorReason = null, updatedAt = now(),
            ),
        )
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val POLL_MS = 1_000L
        private const val COPY_BUFFER = 256 * 1024
        /** Keep some headroom: the system, the tile cache and the database need space too. */
        private const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
    }
}
