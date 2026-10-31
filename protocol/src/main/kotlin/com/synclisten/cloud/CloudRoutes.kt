package com.synclisten.cloud

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class CloudSearchRequest(val source: String, val keyword: String, val page: Int = 1)
@Serializable
data class CloudSearchResponse(val tracks: List<CloudTrack>)
@Serializable
data class CloudError(val code: String, val message: String)
@Serializable
data class CloudErrorResponse(val error: CloudError)

fun Route.cloudMusicRoutes(music: CloudMusicClient = CloudMusicClient()) {
    post("/api/rooms/{roomId}/cloud/lyrics") {
        val track = call.receive<CloudTrack>()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        try {
            val result = withContext(Dispatchers.IO) { music.lyrics(track) }
            call.respond(result)
        } catch (error: CloudMusicException) {
            call.respond(HttpStatusCode.BadGateway, CloudErrorResponse(CloudError("CLOUD_UNAVAILABLE", error.message ?: "Music service unavailable")))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            call.respond(HttpStatusCode.BadGateway, CloudErrorResponse(CloudError("CLOUD_UNAVAILABLE", "Invalid music service response")))
        }
    }
    post("/api/rooms/{roomId}/cloud/search") {
        val input = call.receive<CloudSearchRequest>()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (input.source !in setOf("netease", "qq", "kugou", "kuwo") || input.keyword.isBlank() || input.keyword.length > 200 || input.page !in 1..10000) {
            call.respond(HttpStatusCode.BadRequest, CloudErrorResponse(CloudError("VALIDATION_ERROR", "Invalid search")))
        } else try {
            val result = withContext(Dispatchers.IO) { music.search(input.source, input.keyword.trim(), input.page) }
            call.respond(CloudSearchResponse(result))
        } catch (error: CloudMusicException) {
            call.respond(HttpStatusCode.BadGateway, CloudErrorResponse(CloudError("CLOUD_UNAVAILABLE", error.message ?: "Music service unavailable")))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            call.respond(HttpStatusCode.BadGateway, CloudErrorResponse(CloudError("CLOUD_UNAVAILABLE", "Invalid music service response")))
        }
    }
    post("/api/rooms/{roomId}/cloud/resolve") {
        val track = call.receive<CloudTrack>()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (track.source !in setOf("netease", "qq", "kugou", "kuwo") || track.id.isEmpty() || track.id.length > 128 || track.title.length > 500 || track.album.length > 500 || track.artists.size > 30 || track.artists.any { it.length > 500 } || track.mediaId.length > 128 || track.songType !in 0..1000 || (track.durationMs ?: 0) < 0 || track.availableQualities.size > 20 || track.availableQualities.any { it.length > 40 } || track.coverUrl.length > 2048) {
            call.respond(HttpStatusCode.BadRequest, CloudErrorResponse(CloudError("VALIDATION_ERROR", "Invalid track")))
        } else try {
            val result = withContext(Dispatchers.IO) { music.resolve(track) }
            call.respond(result)
        } catch (error: CloudMusicException) {
            call.respond(HttpStatusCode.BadGateway, CloudErrorResponse(CloudError("CLOUD_UNAVAILABLE", error.message ?: "Music service unavailable")))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            call.respond(HttpStatusCode.BadGateway, CloudErrorResponse(CloudError("CLOUD_UNAVAILABLE", "Invalid music service response")))
        }
    }
}
