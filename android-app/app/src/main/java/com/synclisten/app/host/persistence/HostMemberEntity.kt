package com.synclisten.app.host.persistence

import androidx.room.Entity

@Entity(
    tableName = "host_members",
    primaryKeys = ["roomId", "userId"],
)
data class HostMemberEntity(
    val roomId: String,
    val userId: String,
    val displayName: String,
    val role: String,
    val connected: Boolean,
    val joinedAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "''") val credentialHash: String = "",
)
