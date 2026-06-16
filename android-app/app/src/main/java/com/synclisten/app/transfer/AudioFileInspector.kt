package com.synclisten.app.transfer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalAudioFile(
    val uri: Uri?,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val sha256: String,
)

sealed interface AudioSelectionResult {
    data class Ready(val file: LocalAudioFile) : AudioSelectionResult
    data object Unsupported : AudioSelectionResult
    data class Failed(val message: String) : AudioSelectionResult
}

fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    input.use {
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "ogg", "aac", "wav", "opus", "m4a", "wma")
private val SUPPORTED_MIME_TYPES = setOf(
    "audio/mpeg", "audio/mp3", "audio/flac", "audio/x-flac",
    "audio/ogg", "audio/aac", "audio/wav", "audio/x-wav",
    "audio/opus", "audio/mp4", "audio/x-ms-wma",
)
private const val MAX_FILE_SIZE_BYTES = 200L * 1024 * 1024

fun isSupportedAudio(fileName: String, mimeType: String?): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in SUPPORTED_EXTENSIONS &&
        (mimeType == null || mimeType in SUPPORTED_MIME_TYPES)
}

private const val DEFAULT_BUFFER_SIZE = 8192

@Singleton
class AudioFileInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun inspect(uri: Uri): AudioSelectionResult = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val metadata = resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) error("无法读取文件信息")
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to
                    (if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L)
            } ?: error("无法读取文件信息")
            val fileName = metadata.first ?: error("文件名不可用")
            val fileSize = metadata.second
            if (fileSize > MAX_FILE_SIZE_BYTES) return@withContext AudioSelectionResult.Failed("文件过大（超过 200MB），请选择较小的音频文件")
            val mimeType = resolver.getType(uri)
            if (!isSupportedAudio(fileName, mimeType)) return@withContext AudioSelectionResult.Unsupported
            val hash = resolver.openInputStream(uri)?.let(::sha256) ?: error("无法打开文件")
            val retriever = MediaMetadataRetriever()
            val audioMetadata = try {
                retriever.setDataSource(context, uri)
                Triple(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?: fileName.substringBeforeLast('.'),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                )
            } finally {
                retriever.release()
            }
            AudioSelectionResult.Ready(
                LocalAudioFile(uri, fileName, metadata.second, mimeType, audioMetadata.first, audioMetadata.second, audioMetadata.third, hash),
            )
        }.getOrElse { AudioSelectionResult.Failed(it.message ?: "读取音频失败") }
    }
}
