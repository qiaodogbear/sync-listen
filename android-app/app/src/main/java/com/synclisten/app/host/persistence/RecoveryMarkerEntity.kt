package com.synclisten.app.host.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recovery_marker")
data class RecoveryMarkerEntity(
    @PrimaryKey val roomId: String,
    val hostUserId: String,
    val roomName: String,
    val roomCode: String,
    val memberCount: Int,
    val trackCount: Int,
    val disconnectedAt: Long,
)
