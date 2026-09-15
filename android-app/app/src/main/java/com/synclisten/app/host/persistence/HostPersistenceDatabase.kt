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
    version = 2,
    exportSchema = false,
)
abstract class HostPersistenceDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE host_members ADD COLUMN credentialHash TEXT NOT NULL DEFAULT ''")
                // Legacy rooms have no verifiable credentials; keep data but do not offer recovery.
                db.execSQL("DELETE FROM recovery_marker")
            }
        }
    }
}
