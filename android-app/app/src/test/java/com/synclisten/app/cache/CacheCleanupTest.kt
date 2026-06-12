package com.synclisten.app.cache

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheCleanupTest {
    @Test
    fun protectsCurrentTrackAndDeletesLastPhysicalReference() = runBlocking {
        val file = Files.createTempFile("sync-cache", ".mp3").toFile().apply { writeText("audio") }
        val dao = CleanupFakeDao(
            mutableListOf(
                entity("current", "hash-current", file.path),
                entity("shared-a", "shared", file.path + ".shared"),
                entity("shared-b", "shared", file.path + ".shared"),
            ),
        )
        java.io.File(file.path + ".shared").writeText("shared")
        val cleanup = CacheCleanup(dao, CacheIndex(dao))

        val result = cleanup.clearAllExcept("current")

        assertTrue(file.exists())
        assertFalse(java.io.File(file.path + ".shared").exists())
        assertEquals(listOf("current"), dao.all().map { it.trackId })
        assertEquals(2, result.removedEntries)
    }

    private fun entity(trackId: String, hash: String, path: String) = CacheEntity(
        trackId, hash, path, "$trackId.mp3", 5, 1000, 1, VerifyStatus.VERIFIED, "room",
    )
}

private class CleanupFakeDao(private val entries: MutableList<CacheEntity>) : CacheDao {
    override suspend fun upsert(entity: CacheEntity) {
        entries.removeIf { it.trackId == entity.trackId }
        entries.add(entity)
    }
    override suspend fun findByTrackId(trackId: String) = entries.firstOrNull { it.trackId == trackId }
    override suspend fun findByHash(fileHash: String) = entries.filter { it.fileHash == fileHash }
    override suspend fun findByRoom(roomId: String) = entries.filter { it.roomId == roomId }
    override suspend fun all() = entries.toList()
    override suspend fun updateVerifyStatus(trackId: String, status: VerifyStatus) = Unit
    override suspend fun deleteByTrackId(trackId: String) { entries.removeIf { it.trackId == trackId } }
    override suspend fun deleteByHash(fileHash: String) { entries.removeIf { it.fileHash == fileHash } }
    override suspend fun totalPhysicalBytes() = entries.distinctBy { it.fileHash }.sumOf { it.fileSize }
}
