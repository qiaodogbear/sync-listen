package com.synclisten.app.host.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "host_tracks",
    primaryKeys = ["trackId"],
    foreignKeys = [
        ForeignKey(
            entity = HostRoomEntity::class,
            parentColumns = ["roomId"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("roomId")],
)
data class HostTrackEntity(
    val trackId: String,
    val roomId: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileName: String,
    val fileSize: Long,
    val fileHash: String,
    val storagePath: String,
    val uploaderId: String,
    val uploaderName: String,
    val orderIndex: Int,
    val status: String,
    val createdAt: Long,
)
