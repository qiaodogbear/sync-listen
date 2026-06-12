package com.synclisten.app.cache

import javax.inject.Inject
import javax.inject.Singleton

data class CacheRegistration(
    val trackId: String,
    val fileHash: String,
    val localPath: String,
    val fileName: String,
    val fileSize: Long,
    val durationMs: Long,
    val roomId: String,
)

@Singleton
class CacheIndex @Inject constructor(
    private val dao: CacheDao,
) {
    suspend fun register(input: CacheRegistration, cachedAt: Long = System.currentTimeMillis()): CacheEntity {
        val sharedPath = dao.findByHash(input.fileHash).firstOrNull()?.localPath ?: input.localPath
        val entity = CacheEntity(
            trackId = input.trackId,
            fileHash = input.fileHash,
            localPath = sharedPath,
            fileName = input.fileName,
            fileSize = input.fileSize,
            durationMs = input.durationMs,
            cachedAt = cachedAt,
            verifyStatus = VerifyStatus.PENDING,
            roomId = input.roomId,
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun markVerified(trackId: String) = dao.updateVerifyStatus(trackId, VerifyStatus.VERIFIED)

    suspend fun markFailed(trackId: String) = dao.updateVerifyStatus(trackId, VerifyStatus.FAILED)

    suspend fun removeReference(trackId: String): String? {
        val entry = dao.findByTrackId(trackId) ?: return null
        dao.deleteByTrackId(trackId)
        return entry.localPath.takeIf { dao.findByHash(entry.fileHash).isEmpty() }
    }
}
