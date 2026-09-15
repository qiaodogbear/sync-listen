package com.synclisten.desktop.audio

import com.synclisten.shared.playback.CacheFileResolver
import java.io.File
import com.synclisten.shared.domain.model.Track
import com.synclisten.protocol.DeviceCredential
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves cached audio files on the Desktop platform.
 * Files are stored in %APPDATA%/SyncListen/cache/{trackId}.
 */
class DesktopCacheResolver(private val cacheDir: File) : CacheFileResolver {
    private val verified = java.util.concurrent.ConcurrentHashMap<String, File>()
    init {
        cacheDir.mkdirs()
    }

    override fun resolvePath(trackId: String): String? {
        return verified[trackId]?.takeIf { it.isFile }?.absolutePath
    }

    suspend fun ensureDownloaded(track: Track, serverUrl: String, client: OkHttpClient) = withContext(Dispatchers.IO) {
        require(track.fileHash.matches(Regex("[a-f0-9]{64}")) && track.fileSize in 1..(500L * 1024 * 1024)) { "Invalid audio metadata" }
        val target = File(cacheDir, track.fileHash)
        fun fingerprint(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.isFile && target.length() == track.fileSize && fingerprint(target) == track.fileHash) {
            verified[track.trackId] = target
            return@withContext
        }
        verified.remove(track.trackId)
        val temp = File.createTempFile("download-", ".part", cacheDir)
        try {
            val call = client.newCall(Request.Builder().url("${serverUrl.trimEnd('/')}/api/tracks/${track.trackId}/download").build())
            call.execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("Empty download")
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var received = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            check(received <= track.fileSize) { "Download exceeds declared size" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
            check(temp.length() == track.fileSize && fingerprint(temp) == track.fileHash) { "Audio integrity check failed" }
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            verified[track.trackId] = target
        } finally { temp.delete() }
    }

    fun getCacheFile(trackId: String, extension: String = ""): File {
        cacheDir.mkdirs()
        val name = if (extension.isNotEmpty() && !extension.startsWith(".")) {
            "$trackId.$extension"
        } else {
            trackId + extension
        }
        return File(cacheDir, name)
    }

    fun clearCache() {
        verified.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun cacheSizeBytes(): Long =
        cacheDir.listFiles()?.sumOf { it.length() } ?: 0
}
