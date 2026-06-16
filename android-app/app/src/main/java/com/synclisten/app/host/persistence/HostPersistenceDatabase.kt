package com.synclisten.app.host.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        HostRoomEntity::class,
        HostMemberEntity::class,
        HostTrackEntity::class,
        HostPlaybackEntity::class,
        RecoveryMarkerEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class HostPersistenceDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
}
