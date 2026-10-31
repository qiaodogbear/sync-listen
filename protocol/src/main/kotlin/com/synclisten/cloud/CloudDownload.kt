package com.synclisten.cloud

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CloudDownloadProgress(val received: Long, val total: Long?)
data class CloudDownloadResult(val file: File, val sha256: String, val size: Long, val candidate: CloudCandidate)

class CloudDownloadException(message: String) : Exception(message)

class CloudDownloader(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()) {
    /** The caller must confirm each resolution before any candidate is fetched. */
    suspend fun download(
        initial: CloudResolution,
        destination: File,
        refresh: suspend () -> CloudResolution,
        accept: suspend (CloudResolution) -> Boolean,
        progress: (CloudDownloadProgress) -> Unit = {},
    ): CloudDownloadResult = withContext(Dispatchers.IO) {
        val parent = destination.absoluteFile.parentFile ?: throw CloudDownloadException("保存位置无效")
        if (!parent.isDirectory || destination.exists()) throw CloudDownloadException("保存位置不存在或文件已存在")
        var resolution = initial
        repeat(2) { round ->
            if (resolution.track.source != initial.track.source || resolution.track.id != initial.track.id ||
                resolution.track.mediaId != initial.track.mediaId) throw CloudDownloadException("刷新后曲目标识不一致")
            if (!accept(resolution)) throw CloudDownloadException("未确认解析结果")
            for (candidate in resolution.candidates) {
                currentCoroutineContext().ensureActive()
                val url = candidate.url.toHttpUrlOrNull()
                if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty()) continue
                val temp = File.createTempFile("cloud-download-", ".part", parent)
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.code != 200) return@use
                        val body = response.body ?: return@use
                        val length = body.contentLength().takeIf { it >= 0 }
                        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                        if (contentType == "text/html" || contentType == "application/json" || length == 0L ||
                            (length != null && length > MAX_BYTES)) return@use
                        val digest = MessageDigest.getInstance("SHA-256")
                        var received = 0L
                        body.byteStream().use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > MAX_BYTES) throw CloudDownloadException("音频文件过大")
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                progress(CloudDownloadProgress(received, length))
                            }
                        } }
                        if (received == 0L || (length != null && received != length) || !hasAudioHeader(temp)) return@use
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (temp.length() != received) throw CloudDownloadException("保存文件长度不一致")
                        val storedDigest = MessageDigest.getInstance("SHA-256")
                        temp.inputStream().use { saved ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = saved.read(buffer)
                                if (count < 0) break
                                storedDigest.update(buffer, 0, count)
                            }
                        }
                        val storedHash = storedDigest.digest().joinToString("") { "%02x".format(it) }
                        if (hash != storedHash) throw CloudDownloadException("保存文件校验失败")
                        Files.move(temp.toPath(), destination.toPath())
                        return@withContext CloudDownloadResult(destination, hash, received, candidate)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: java.nio.file.FileAlreadyExistsException) { throw CloudDownloadException("保存文件已存在") }
                catch (error: CloudDownloadException) { throw error }
                catch (_: java.io.IOException) { /* The next candidate is a fresh request. */ }
                finally { temp.delete() }
            }
            if (round == 0) resolution = refresh()
        }
        throw CloudDownloadException("没有可下载的音频地址，请重试")
    }

    private fun hasAudioHeader(file: File): Boolean {
        val bytes = ByteArray(12)
        val count = file.inputStream().use { it.read(bytes) }
        if (count < 3) return false
        fun starts(value: String) = bytes.copyOfRange(0, value.length).contentEquals(value.toByteArray(Charsets.US_ASCII))
        val frame = (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0
        val mp4 = count >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
        return starts("ID3") || starts("fLaC") || starts("OggS") || starts("RIFF") || frame || mp4
    }

    private companion object { const val MAX_BYTES = 500L * 1024 * 1024 }
}
