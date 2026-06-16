package com.synclisten.app.host.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "host_playback")
data class HostPlaybackEntity(
    @PrimaryKey val roomId: String,
    val trackId: String?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val serverTimeMs: Long,
    val executeAtServerTimeMs: Long?,
)
