package com.synclisten.app.host.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "host_room")
data class HostRoomEntity(
    @PrimaryKey val roomId: String,
    val roomCode: String,
    val name: String,
    val hostUserId: String,
    val status: String,
    val joinToken: String,
    val createdAt: Long,
)
