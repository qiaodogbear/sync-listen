package com.synclisten.app.cache

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CacheSummary(val entries: Int = 0, val physicalBytes: Long = 0)
data class CleanupResult(val removedEntries: Int, val deletedFiles: Int)

@Singleton
class CacheCleanup @Inject constructor(
    private val dao: CacheDao,
    private val index: CacheIndex,
) {
    suspend fun summary(): CacheSummary = CacheSummary(dao.all().size, dao.totalPhysicalBytes())

    suspend fun removeTrack(trackId: String, currentTrackId: String?): CleanupResult {
        if (trackId == currentTrackId) return CleanupResult(0, 0)
        val path = index.removeReference(trackId)
        return CleanupResult(1, if (path != null && File(path).delete()) 1 else 0)
    }

    suspend fun clearAllExcept(currentTrackId: String?): CleanupResult {
        var removed = 0
        var files = 0
        for (entry in dao.all().filterNot { it.trackId == currentTrackId }) {
            val result = removeTrack(entry.trackId, currentTrackId)
            removed += result.removedEntries
            files += result.deletedFiles
        }
        return CleanupResult(removed, files)
    }
}
