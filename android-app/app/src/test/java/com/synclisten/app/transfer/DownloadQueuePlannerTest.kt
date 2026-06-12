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

    private fun track(id: String, order: Int) = Track(
        id, "room", id, null, 1000, "$id.mp3", 10, id.repeat(64).take(64),
        "host", "Alice", order, TrackStatus.READY, 1,
    )
}
