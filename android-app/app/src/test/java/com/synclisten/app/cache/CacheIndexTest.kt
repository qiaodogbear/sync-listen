package com.synclisten.app.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CacheIndexTest {
    @Test
    fun reusesPhysicalPathAcrossRoomsAndTracksVerification() = runBlocking {
        val dao = FakeCacheDao()
        val index = CacheIndex(dao)

        index.register(registration("track-a", "room-a", "/cache/hash.mp3"))
        val reused = index.register(registration("track-b", "room-b", "/other/path.mp3"))
        index.markVerified("track-b")

        assertEquals("/cache/hash.mp3", reused.localPath)
        assertEquals(2, dao.findByHash("hash").size)
        assertEquals(VerifyStatus.VERIFIED, dao.findByTrackId("track-b")?.verifyStatus)
    }

    @Test
    fun returnsPhysicalPathOnlyAfterLastReferenceIsDeleted() = runBlocking {
        val dao = FakeCacheDao()
        val index = CacheIndex(dao)
        index.register(registration("track-a", "room-a", "/cache/hash.mp3"))
        index.register(registration("track-b", "room-b", "/cache/hash.mp3"))

        assertNull(index.removeReference("track-a"))
        assertEquals("/cache/hash.mp3", index.removeReference("track-b"))
    }

    private fun registration(trackId: String, roomId: String, path: String) = CacheRegistration(
        trackId, "hash", path, "song.mp3", 10, 1000, roomId,
    )
}

private class FakeCacheDao : CacheDao {
    private val entries = linkedMapOf<String, CacheEntity>()

    override suspend fun upsert(entity: CacheEntity) {
        entries[entity.trackId] = entity
    }

    override suspend fun findByTrackId(trackId: String) = entries[trackId]

    override suspend fun findByHash(fileHash: String) = entries.values.filter { it.fileHash == fileHash }

    override suspend fun findByRoom(roomId: String) = entries.values.filter { it.roomId == roomId }

    override suspend fun updateVerifyStatus(trackId: String, status: VerifyStatus) {
        entries[trackId]?.let { entries[trackId] = it.copy(verifyStatus = status) }
    }

    override suspend fun deleteByTrackId(trackId: String) {
        entries.remove(trackId)
    }

    override suspend fun deleteByHash(fileHash: String) {
        entries.entries.removeIf { it.value.fileHash == fileHash }
    }

    override suspend fun totalPhysicalBytes() =
        entries.values.distinctBy { it.fileHash }.sumOf { it.fileSize }
}
