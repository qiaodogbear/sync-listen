package com.synclisten.app.transfer

import com.synclisten.app.domain.model.Track
import com.synclisten.app.domain.model.TrackStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueuePlannerTest {
    @Test
    fun prioritizesCurrentThenNextThenRemainingTracks() {
        val tracks = listOf(track("a", 0), track("b", 1), track("c", 2), track("d", 3))

        val planned = DownloadQueuePlanner().plan(tracks, currentTrackId = "b")

        assertEquals(listOf("b", "c", "a", "d"), planned.map { it.track.trackId })
        assertEquals(listOf(0, 1, 2, 2), planned.map { it.priority })
    }

    @Test
    fun ignoresTracksThatAreNotReady() {
        val planned = DownloadQueuePlanner().plan(
            listOf(track("ready", 0), track("uploading", 1, TrackStatus.UPLOADING)),
            currentTrackId = "ready",
        )

        assertEquals(listOf("ready"), planned.map { it.track.trackId })
    }

    @Test
    fun revisionChangesForPlaybackPriorityAndCanBeForcedForRecovery() {
        val revision = DownloadQueueRevision()
        val first = listOf(PlannedDownload(track("a", 0), 0), PlannedDownload(track("b", 1), 1))
        val reprioritized = listOf(PlannedDownload(track("b", 1), 0), PlannedDownload(track("a", 0), 2))

        assertEquals(true, revision.shouldRebuild("room", first))
        assertEquals(false, revision.shouldRebuild("room", first))
        assertEquals(true, revision.shouldRebuild("room", reprioritized))
        assertEquals(true, revision.shouldRebuild("room", reprioritized, force = true))
    }

    private fun track(id: String, order: Int, status: TrackStatus = TrackStatus.READY) = Track(
        id, "room", id, null, 1000, "$id.mp3", 10, id.repeat(64).take(64),
        "host", "Alice", order, status, 1,
    )
}
