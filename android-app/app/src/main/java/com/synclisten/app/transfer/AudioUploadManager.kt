package com.synclisten.app.transfer

import android.content.Context
import com.synclisten.app.data.SettingsStore
import com.synclisten.app.domain.model.ErrorResponse
import com.synclisten.app.domain.model.Track
import com.synclisten.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class UploadRequest(
    val roomId: String,
    val uploaderId: String,
    val uploaderName: String,
    val file: LocalAudioFile,
)

data class UploadResult(val track: Track, val deduplicated: Boolean)

sealed interface UploadState {
    data object Idle : UploadState
    data object Uploading : UploadState
    data object AlreadyRunning : UploadState
    data class Success(val result: UploadResult) : UploadState
    data class Failed(val message: String) : UploadState
}

data class UploadProgress(val state: UploadState = UploadState.Idle, val progress: Int = 0)

interface UploadTransport {
    suspend fun upload(request: UploadRequest, onProgress: (Int) -> Unit): UploadResult
}

@Singleton
class UploadCoordinator @Inject constructor(
    private val transport: UploadTransport,
) {
    private val runningHash = AtomicReference<String?>(null)
    private val mutableState = MutableStateFlow(UploadProgress())
    val state: StateFlow<UploadProgress> = mutableState

    suspend fun upload(request: UploadRequest): UploadState {
        if (!runningHash.compareAndSet(null, request.file.sha256)) return UploadState.AlreadyRunning
        mutableState.value = UploadProgress(UploadState.Uploading, 0)
        AppLogger.debug("Upload", "Starting ${request.file.fileName}")
        return try {
            val result = transport.upload(request) { progress ->
                mutableState.value = UploadProgress(UploadState.Uploading, progress)
            }
            UploadState.Success(result).also {
                mutableState.value = UploadProgress(it, 100)
                AppLogger.debug("Upload", "Completed ${request.file.fileName}")
            }
        } catch (error: Throwable) {
            UploadState.Failed(error.message ?: "上传失败").also {
                mutableState.value = UploadProgress(it, mutableState.value.progress)
                AppLogger.error("Upload", "Failed ${request.file.fileName}", error)
            }
        } finally {
            runningHash.set(null)
        }
    }
}

@Serializable
private data class UploadResponse(val track: Track, val deduplicated: Boolean = false)

@Singleton
class OkHttpUploadTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val settingsStore: SettingsStore,
    private val json: Json,
) : UploadTransport {
    override suspend fun upload(request: UploadRequest, onProgress: (Int) -> Unit): UploadResult {
        val file = request.file
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("title", file.title)
            .addFormDataPart("artist", file.artist.orEmpty())
            .addFormDataPart("durationMs", file.durationMs.toString())
            .addFormDataPart("fileHash", file.sha256)
            .addFormDataPart("uploaderId", request.uploaderId)
            .addFormDataPart("uploaderName", request.uploaderName)
            .addFormDataPart("file", file.fileName, ContentUriRequestBody(context, file, onProgress))
            .build()
        val serverUrl = settingsStore.settings.first().serverUrl.trimEnd('/')
        val httpRequest = Request.Builder()
            .url("$serverUrl/api/rooms/${request.roomId}/tracks")
            .post(body)
            .build()
        val response = client.newCall(httpRequest).await()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val message = runCatching { json.decodeFromString<ErrorResponse>(text).error.message }
                    .getOrDefault("上传失败 (${it.code})")
                throw IOException(message)
            }
            val decoded = json.decodeFromString<UploadResponse>(text)
            return UploadResult(decoded.track, decoded.deduplicated)
        }
    }
}

private class ContentUriRequestBody(
    private val context: Context,
    private val file: LocalAudioFile,
    private val onProgress: (Int) -> Unit,
) : RequestBody() {
    override fun contentType() = file.mimeType?.toMediaTypeOrNull()
    override fun contentLength(): Long = file.fileSize

    override fun writeTo(sink: BufferedSink) {
        val total = file.fileSize.coerceAtLeast(1)
        val uri = file.uri ?: throw IOException("文件 URI 不可用")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var sent = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
                sent += count
                onProgress(((sent * 100) / total).toInt().coerceIn(0, 100))
            }
        } ?: throw IOException("无法打开文件")
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
}
