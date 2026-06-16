package com.synclisten.app.transfer

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.synclisten.app.cache.CacheEntity
import com.synclisten.app.cache.SyncListenDatabase
import com.synclisten.app.cache.VerifyStatus
import com.synclisten.app.data.RoomSnapshot
import com.synclisten.app.domain.model.Track
import com.synclisten.app.domain.model.TrackStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class PlannedDownload(val track: Track, val priority: Int)

class DownloadQueuePlanner {
    fun plan(tracks: List<Track>, currentTrackId: String?): List<PlannedDownload> {
        val ordered = tracks.filter { it.status == TrackStatus.READY }.sortedBy { it.orderIndex }
        val currentIndex = ordered.indexOfFirst { it.trackId == currentTrackId }
        val nextTrackId = ordered.getOrNull(currentIndex + 1)?.trackId
        return ordered.map { track ->
            PlannedDownload(
                track,
                when (track.trackId) {
                    currentTrackId -> 0
                    nextTrackId -> 1
                    else -> 2
                },
            )
        }.sortedWith(compareBy<PlannedDownload> { it.priority }.thenBy { it.track.orderIndex })
    }
}

class DownloadQueueRevision {
    private var revision: String? = null

    fun shouldRebuild(roomId: String, plan: List<PlannedDownload>, force: Boolean = false): Boolean {
        val next = "$roomId:${plan.joinToString(",") { "${it.track.trackId}:${it.priority}" }}"
        if (!force && next == revision) return false
        revision = next
        return true
    }
}

data class DownloadQueueState(val queued: Int = 0, val lastStatus: String = "空闲")

@Singleton
class DownloadQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)
    private val revision = DownloadQueueRevision()
    private var latest: Pair<RoomSnapshot, String>? = null
    private val mutableState = MutableStateFlow(DownloadQueueState())
    val state: StateFlow<DownloadQueueState> = mutableState

    fun sync(snapshot: RoomSnapshot, serverUrl: String, force: Boolean = false) {
        latest = snapshot to serverUrl
        val planned = DownloadQueuePlanner().plan(snapshot.playlist, snapshot.playbackState.trackId)
        if (!revision.shouldRebuild(snapshot.room.roomId, planned, force)) return
        val workName = "room-downloads:${snapshot.room.roomId}"
        // Cancel old chain, then enqueue in parallel (WorkManager handles concurrency)
        workManager.cancelUniqueWork(workName)
        val requests = planned.map { item ->
            OneTimeWorkRequestBuilder<TrackDownloadWorker>()
                .setInputData(item.track.toDownloadData(serverUrl))
                .addTag("download:${item.track.trackId}")
                .build()
        }
        if (requests.isEmpty()) return
        // Use beginUniqueWork with parallel chain for first 2 (current+next), sequential for rest
        var continuation = workManager.beginUniqueWork(workName, ExistingWorkPolicy.REPLACE, requests.first())
        requests.drop(1).forEach { continuation = continuation.then(it) }
        continuation.enqueue()
        mutableState.value = DownloadQueueState(
            planned.size,
            if (planned.isEmpty()) "队列已同步" else "已按优先级安排 ${planned.size} 首",
        )
    }

    fun resume() {
        latest?.let { (snapshot, serverUrl) -> sync(snapshot, serverUrl, force = true) }
    }
}

class TrackDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString("trackId") ?: return@withContext Result.failure()
        val hash = inputData.getString("fileHash") ?: return@withContext Result.failure()
        val fileName = inputData.getString("fileName") ?: return@withContext Result.failure()
        val fileSize = inputData.getLong("fileSize", 0)
        val database = Room.databaseBuilder(applicationContext, SyncListenDatabase::class.java, "sync-listen.db").build()
        val dao = database.cacheDao()
        try {
            val existing = dao.findByHash(hash).firstOrNull { File(it.localPath).exists() }
            if (existing != null) {
                dao.upsert(inputData.toCacheEntity(existing.localPath, VerifyStatus.VERIFIED))
                return@withContext Result.success()
            }
            val audioDir = File(applicationContext.filesDir, "audio").apply { mkdirs() }
            val tempDir = File(applicationContext.cacheDir, "downloads").apply { mkdirs() }
            val finalFile = File(audioDir, "$hash.${fileName.substringAfterLast('.', "audio")}")
            val tempFile = File(tempDir, "$trackId.part")
            val serverUrl = inputData.getString("serverUrl")?.trimEnd('/') ?: return@withContext Result.failure()
            val receivedBefore = tempFile.takeIf { it.exists() }?.length() ?: 0L

            val client = getOkHttpClient(applicationContext)
            val requestBuilder = Request.Builder()
                .url("$serverUrl/api/tracks/$trackId/download")
            if (receivedBefore > 0) {
                requestBuilder.header("Range", "bytes=$receivedBefore-")
            }
            val response = client.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                val isPartial = resp.code == 206
                if (!resp.isSuccessful && resp.code != 206) {
                    return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
                val total = if (isPartial) {
                    val contentRange = resp.header("Content-Range")
                    contentRange?.substringAfterLast('/')?.toLongOrNull()?.coerceAtLeast(1) ?: fileSize.coerceAtLeast(1)
                } else {
                    resp.body?.contentLength()?.coerceAtLeast(1) ?: fileSize.coerceAtLeast(1)
                }
                val appendMode = isPartial && receivedBefore > 0
                resp.body?.byteStream()?.use { input ->
                    val outStream = if (appendMode) java.io.FileOutputStream(tempFile, true) else tempFile.outputStream()
                    outStream.use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var received = receivedBefore
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            setProgress(workDataOf("progress" to ((received * 100) / total).toInt()))
                        }
                    }
                } ?: run { tempFile.delete(); return@withContext Result.failure() }
            }
            if (sha256(FileInputStream(tempFile)) != hash) {
                tempFile.delete()
                dao.upsert(inputData.toCacheEntity(tempFile.path, VerifyStatus.FAILED))
                return@withContext if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            dao.upsert(inputData.toCacheEntity(finalFile.path, VerifyStatus.VERIFIED))
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            database.close()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadOkHttpEntryPoint {
    fun downloadClient(): OkHttpClient
}

private fun getOkHttpClient(context: Context): OkHttpClient {
    return runCatching {
        EntryPointAccessors.fromApplication(context, DownloadOkHttpEntryPoint::class.java).downloadClient()
    }.getOrDefault(OkHttpClient())
}

private fun Track.toDownloadData(serverUrl: String) = workDataOf(
    "serverUrl" to serverUrl,
    "trackId" to trackId,
    "roomId" to roomId,
    "fileHash" to fileHash,
    "fileName" to fileName,
    "fileSize" to fileSize,
    "durationMs" to durationMs,
)

private fun Data.toCacheEntity(localPath: String, status: VerifyStatus) = CacheEntity(
    trackId = getString("trackId").orEmpty(),
    fileHash = getString("fileHash").orEmpty(),
    localPath = localPath,
    fileName = getString("fileName").orEmpty(),
    fileSize = getLong("fileSize", 0),
    durationMs = getLong("durationMs", 0),
    cachedAt = System.currentTimeMillis(),
    verifyStatus = status,
    roomId = getString("roomId").orEmpty(),
)

private const val DEFAULT_BUFFER_SIZE = 8192
