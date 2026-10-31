package com.synclisten.app.host.persistence

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.synclisten.app.data.CreateRoomRequest
import com.synclisten.app.data.TrackPlaybackCommand
import com.synclisten.app.host.server.AddHostTrack
import com.synclisten.app.host.server.HostRoomStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostRecoveryDatabaseTest {
    @Test
    fun migrationFromVersionTwoKeepsLocalTracks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "host-migration-${java.util.UUID.randomUUID()}.db"
        try {
            val first = Room.databaseBuilder(context, HostPersistenceDatabase::class.java, name).build()
            val store = HostRoomStore(dao = first.hostDao(), transaction = { block -> first.withTransaction { block() } })
            val room = store.createRoom(CreateRoomRequest("Test", "host", "Alice"), "a".repeat(64)).room
            val track = store.addReadyTrack(AddHostTrack(room.roomId, "Local", null, 1_000, "local.wav", 100,
                "b".repeat(64), "/test/local.wav", "host", "Alice"))
            first.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("ALTER TABLE host_tracks DROP COLUMN cloudJson")
                db.execSQL("ALTER TABLE host_members DROP COLUMN protocolVersion")
                db.version = 2
            }
            val migrated = Room.databaseBuilder(context, HostPersistenceDatabase::class.java, name)
                .addMigrations(HostPersistenceDatabase.MIGRATION_2_3).build()
            try {
                val restored = migrated.hostDao().getTracks(room.roomId).single()
                assertEquals(track.trackId, restored.trackId)
                assertEquals(null, restored.cloudJson)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun repeatedRecoveryAndShutdownPreserveTracksInRealDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, HostPersistenceDatabase::class.java).build()
        try {
            val dao = database.hostDao()
            fun store() = HostRoomStore(dao = dao, transaction = { block -> database.withTransaction { block() } })
            var current = store()
            val credential = "a".repeat(64)
            val room = current.createRoom(CreateRoomRequest(name = "Recovery test", userId = "host", displayName = "Alice"), credential).room
            val track = current.addReadyTrack(
                AddHostTrack(room.roomId, "Test tone", null, 45_000, "test.wav", 100,
                    "b".repeat(64), "/test/test.wav", "host", "Alice"),
            )
            current.pause(room.roomId, TrackPlaybackCommand("host", track.trackId, 15_000))

            repeat(3) { cycle ->
                // Cover both abrupt process loss and graceful emergency shutdown.
                if (cycle > 0) current.emergencyShutdown()
                current = store()
                val snapshot = current.recoverRoom()
                assertEquals(listOf(track.trackId), snapshot.playlist.map { it.trackId })
                assertEquals(listOf(track.trackId), dao.getTracks(room.roomId).map { it.trackId })
                assertEquals(15_000L, snapshot.playbackState.positionMs)
                assertFalse(snapshot.playbackState.isPlaying)
                assertEquals("host", current.authorize(room.roomId, "host", credential).userId)
            }
            current.closeAndCleanup()
            assertEquals(emptyList<HostTrackEntity>(), dao.getTracks(room.roomId))
            assertEquals(null, dao.getRecoveryMarker())
        } finally {
            database.close()
        }
    }
}
