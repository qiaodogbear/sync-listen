package com.synclisten.app.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

enum class VerifyStatus {
    PENDING,
    VERIFIED,
    FAILED,
}

@Entity(
    tableName = "cache_entries",
    primaryKeys = ["trackId"],
    indices = [Index("fileHash"), Index("roomId")],
)
data class CacheEntity(
    val trackId: String,
    val fileHash: String,
    val localPath: String,
    val fileName: String,
    val fileSize: Long,
    val durationMs: Long,
    val cachedAt: Long,
    val verifyStatus: VerifyStatus,
    val roomId: String,
)

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CacheEntity)

    @Query("SELECT * FROM cache_entries WHERE trackId = :trackId")
    suspend fun findByTrackId(trackId: String): CacheEntity?

    @Query("SELECT * FROM cache_entries WHERE fileHash = :fileHash ORDER BY cachedAt")
    suspend fun findByHash(fileHash: String): List<CacheEntity>

    @Query("SELECT * FROM cache_entries WHERE roomId = :roomId ORDER BY cachedAt")
    suspend fun findByRoom(roomId: String): List<CacheEntity>

    @Query("UPDATE cache_entries SET verifyStatus = :status WHERE trackId = :trackId")
    suspend fun updateVerifyStatus(trackId: String, status: VerifyStatus)

    @Query("DELETE FROM cache_entries WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)

    @Query("DELETE FROM cache_entries WHERE fileHash = :fileHash")
    suspend fun deleteByHash(fileHash: String)

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM cache_entries WHERE trackId IN (SELECT MIN(trackId) FROM cache_entries GROUP BY fileHash)")
    suspend fun totalPhysicalBytes(): Long
}

class CacheConverters {
    @TypeConverter
    fun fromStatus(value: VerifyStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): VerifyStatus = VerifyStatus.valueOf(value)
}

@Database(entities = [CacheEntity::class], version = 1, exportSchema = false)
@TypeConverters(CacheConverters::class)
abstract class SyncListenDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}
