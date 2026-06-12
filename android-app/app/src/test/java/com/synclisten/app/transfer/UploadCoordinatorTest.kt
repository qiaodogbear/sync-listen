package com.synclisten.app.transfer

import com.synclisten.app.domain.model.Track
import com.synclisten.app.domain.model.TrackStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCoordinatorTest {
    @Test
    fun preventsDuplicateUploadWhileSameHashIsRunning() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = UploadCoordinator(
            transport = object : UploadTransport {
                override suspend fun upload(request: UploadRequest, onProgress: (Int) -> Unit): UploadResult {
                    calls += 1
                    entered.complete(Unit)
                    release.await()
                    return UploadResult(track(), deduplicated = false)
                }
            },
        )
        val request = request()

        val first = async { coordinator.upload(request) }
        entered.await()
        val duplicate = coordinator.upload(request)
        release.complete(Unit)

        assertTrue(duplicate is UploadState.AlreadyRunning)
        assertTrue(first.await() is UploadState.Success)
        assertEquals(1, calls)
    }

    @Test
    fun failedUploadCanRetry() = runBlocking {
        var calls = 0
        val coordinator = UploadCoordinator(
            transport = object : UploadTransport {
                override suspend fun upload(request: UploadRequest, onProgress: (Int) -> Unit): UploadResult {
                    calls += 1
                    if (calls == 1) error("network")
                    onProgress(100)
                    return UploadResult(track(), deduplicated = false)
                }
            },
        )

        assertTrue(coordinator.upload(request()) is UploadState.Failed)
        assertTrue(coordinator.upload(request()) is UploadState.Success)
        assertEquals(2, calls)
        assertEquals(100, coordinator.state.value.progress)
    }

    private fun request() = UploadRequest(
        roomId = "room-1",
        uploaderId = "user-1",
        uploaderName = "Alice",
        file = LocalAudioFile(
            uri = null,
            fileName = "song.mp3",
            fileSize = 1,
            mimeType = "audio/mpeg",
            title = "Song",
            artist = null,
            durationMs = 1,
            sha256 = "a".repeat(64),
        ),
    )

    private fun track() = Track(
        "track-1", "room-1", "Song", null, 1, "song.mp3", 1, "a".repeat(64),
        "user-1", "Alice", 0, TrackStatus.READY, 1,
    )
}
