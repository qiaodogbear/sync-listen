package com.synclisten.cloud

import kotlinx.serialization.Serializable

@Serializable
data class CloudReadinessSnapshot(
    val generation: Long,
    val trackId: String,
    val waiting: List<String>,
    val failed: List<String>,
    val committed: Boolean,
    val playAfterReady: Boolean = true,
)

@Serializable
data class CloudPrepareRequest(val trackId: String, val positionMs: Long = 0, val playAfterReady: Boolean = true)
@Serializable
data class CloudReadyRequest(val generation: Long, val trackId: String, val ready: Boolean)
@Serializable
data class CloudCommitRequest(val generation: Long, val force: Boolean = false)

/** A generation is valid only for one track preparation in one active room. */
class CloudReadiness private constructor(private var generation: Long) {
    constructor() : this(java.security.SecureRandom().nextLong().ushr(12))
    private var trackId: String? = null
    private var committed = false
    private var playAfterReady = true
    private val members = linkedMapOf<String, Boolean?>()

    @Synchronized
    fun copy(): CloudReadiness = CloudReadiness(generation).also {
        it.trackId = trackId
        it.committed = committed
        it.playAfterReady = playAfterReady
        it.members.putAll(members)
    }

    @Synchronized
    fun cancel() {
        generation++
        trackId = null
        committed = false
        members.clear()
    }

    @Synchronized
    fun begin(trackId: String, onlineMembers: Collection<String>, playAfterReady: Boolean = true): CloudReadinessSnapshot {
        require(trackId.isNotBlank())
        generation++
        this.trackId = trackId
        committed = false
        this.playAfterReady = playAfterReady
        members.clear()
        onlineMembers.forEach { members[it] = null }
        return snapshot()!!
    }

    @Synchronized
    fun join(userId: String): CloudReadinessSnapshot? {
        if (trackId != null && !committed && userId !in members) members[userId] = null
        return snapshot()
    }

    @Synchronized
    fun disconnect(userId: String): CloudReadinessSnapshot? {
        if (!committed) members.remove(userId)
        return snapshot()
    }

    @Synchronized
    fun report(expectedGeneration: Long, expectedTrackId: String, userId: String, ready: Boolean): CloudReadinessSnapshot {
        check(trackId == expectedTrackId && generation == expectedGeneration && !committed) { "Preparation is stale" }
        check(members.containsKey(userId)) { "Member is not participating" }
        members[userId] = ready
        return snapshot()!!
    }

    @Synchronized
    fun commit(expectedGeneration: Long, force: Boolean): CloudReadinessSnapshot {
        check(trackId != null && generation == expectedGeneration && !committed) { "Preparation is stale" }
        check(force || members.values.all { it == true }) { "Members are not ready" }
        committed = true
        return snapshot()!!
    }

    @Synchronized
    fun snapshot(): CloudReadinessSnapshot? = trackId?.let { current ->
        CloudReadinessSnapshot(generation, current, members.filterValues { it != true }.keys.toList(),
            members.filterValues { it == false }.keys.toList(), committed, playAfterReady)
    }
}
