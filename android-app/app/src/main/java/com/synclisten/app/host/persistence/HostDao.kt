package com.synclisten.app.host.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HostDao {
    // ── 房间 ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoom(room: HostRoomEntity)

    @Query("SELECT * FROM host_room LIMIT 1")
    suspend fun getRoom(): HostRoomEntity?

    @Query("DELETE FROM host_room")
    suspend fun deleteRoom()

    // ── 成员 ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: HostMemberEntity)

    @Query("SELECT * FROM host_members WHERE roomId = :roomId")
    suspend fun getMembers(roomId: String): List<HostMemberEntity>

    @Query("DELETE FROM host_members WHERE roomId = :roomId AND userId = :userId")
    suspend fun deleteMember(roomId: String, userId: String)

    @Query("UPDATE host_members SET connected = :connected WHERE roomId = :roomId")
    suspend fun updateAllConnected(roomId: String, connected: Boolean)

    @Query("DELETE FROM host_members")
    suspend fun deleteAllMembers()

    // ── 曲目 ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: HostTrackEntity)

    @Query("SELECT * FROM host_tracks WHERE roomId = :roomId ORDER BY orderIndex")
    suspend fun getTracks(roomId: String): List<HostTrackEntity>

    @Query("DELETE FROM host_tracks WHERE trackId = :trackId")
    suspend fun deleteTrack(trackId: String)

    @Query("DELETE FROM host_tracks")
    suspend fun deleteAllTracks()

    // ── 播放状态 ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayback(playback: HostPlaybackEntity)

    @Query("SELECT * FROM host_playback WHERE roomId = :roomId")
    suspend fun getPlayback(roomId: String): HostPlaybackEntity?

    @Query("DELETE FROM host_playback")
    suspend fun deleteAllPlayback()

    // ── 恢复标记 ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecoveryMarker(marker: RecoveryMarkerEntity)

    @Query("SELECT * FROM recovery_marker LIMIT 1")
    suspend fun getRecoveryMarker(): RecoveryMarkerEntity?

    @Query("DELETE FROM recovery_marker")
    suspend fun deleteRecoveryMarker()
}
