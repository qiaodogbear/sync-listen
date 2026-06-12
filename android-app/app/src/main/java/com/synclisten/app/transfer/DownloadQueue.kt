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
import dagger.hilt.android.qualifiers.ApplicationContext
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

data class PlannedDownload(val track: Track, val priority: Int)

class DownloadQueuePlanner {
    fun plan(tracks: List<Track>, currentTrackId: String?): List<PlannedDownload> {
        val ordered = tracks.sortedBy { it.orderIndex }
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

data class DownloadQueueState(val queued: Int = 0, val lastStatus: String = "空闲")

@Singleton
class DownloadQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scheduled = mutableSetOf<String>()
    private val mutableState = MutableStateFlow(DownloadQueueState())
    val state: StateFlow<DownloadQueueState> = mutableState

    fun sync(snapshot: RoomSnapshot, serverUrl: String) {
        val planned = DownloadQueuePlanner().plan(snapshot.playlist, snapshot.playbackState.trackId)
            .filter { scheduled.add(it.track.trackId) }
        for (item in planned) {
            val request = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
                .setInputData(item.track.toDownloadData(serverUrl))
                .addTag("download:${item.track.trackId}")
                .build()
            workManager.enqueueUniqueWork(
                "room-downloads:${snapshot.room.roomId}",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
        mutableState.value = DownloadQueueState(scheduled.size, if (planned.isEmpty()) "队列已同步" else "已加入 ${planned.size} 首")
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
            val response = OkHttpClient().newCall(
                Request.Builder().url("$serverUrl/api/tracks/$trackId/download").build(),
            ).execute()
            response.use {
                if (!it.isSuccessful) return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
                val total = it.body?.contentLength()?.coerceAtLeast(1) ?: 1
                it.body?.byteStream()?.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var received = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            setProgress(workDataOf("progress" to ((received * 100) / total).toInt()))
                        }
                    }
                } ?: return@withContext Result.failure()
            }
            if (sha256(FileInputStream(tempFile)) != hash) {
                tempFile.delete()
                dao.upsert(inputData.toCacheEntity(tempFile.path, VerifyStatus.FAILED))
                return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            dao.upsert(inputData.toCacheEntity(finalFile.path, VerifyStatus.VERIFIED))
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            database.close()
        }
    }
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
