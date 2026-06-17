package com.synclisten.shared.host.server

import com.synclisten.shared.data.CreateRoomRequest
import com.synclisten.shared.data.JoinRoomRequest
import com.synclisten.shared.data.NextPlaybackCommand
import com.synclisten.shared.data.PlaybackResponse
import com.synclisten.shared.data.PlaylistResponse
import com.synclisten.shared.data.ServerTimeResponse
import com.synclisten.shared.data.TrackPlaybackCommand
import com.synclisten.shared.domain.model.ApiError
import com.synclisten.shared.domain.model.ErrorResponse
import com.synclisten.shared.domain.model.WebSocketEventType
import com.synclisten.shared.domain.model.Track
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HostStorage(
    val root: File,
    val maxUploadBytes: Long = 500L * 1024 * 1024,
) {
    val audioDir: File = File(root, "audio").apply { mkdirs() }
    val uploadDir: File = File(root, "uploads").apply { mkdirs() }
}

@Serializable
data class HostUploadResponse(val track: Track, val deduplicated: Boolean)

fun Application.hostServerModule(
    store: HostRoomStore,
    hub: HostRoomHub,
    jsonCodec: Json = Json { ignoreUnknownKeys = true },
    clock: () -> Long = System::currentTimeMillis,
    storage: HostStorage? = null,
) {
    install(ContentNegotiation) { json(jsonCodec) }
    install(WebSockets)
    install(StatusPages) {
        exception<HostServerError> { call, error ->
            call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(ApiError(error.code, error.message)))
        }
        exception<Throwable> { call, error ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ApiError("VALIDATION_ERROR", error.message ?: "Invalid request")),
            )
        }
    }
    launch {
        while (isActive) {
            delay(5_000)
            store.syncState()?.let { (roomId, state) ->
                hub.broadcast(roomId, WebSocketEventType.SYNC, jsonCodec.objectPayload(state))
            }
        }
    }
    routing {
        get("/health") {
            call.respond(buildJsonObject {
                put("status", "ok")
                put("serverTimeMs", clock())
            })
        }
        get("/api/time") { call.respond(ServerTimeResponse(clock())) }
        post("/api/rooms") {
            call.respond(HttpStatusCode.Created, store.createRoom(call.receive<CreateRoomRequest>()))
        }
        post("/api/rooms/join") {
            val request = call.receive<JoinRoomRequest>()
            if (request.roomCode == null) throw HostServerError(400, "ROOM_CODE_REQUIRED", "Room code is required")
            val joined = store.joinByCode(request)
            hub.broadcast(joined.room.roomId, WebSocketEventType.MEMBER_JOINED, jsonCodec.memberPayload(joined.member))
            call.respond(joined)
        }
        post("/api/rooms/{roomId}/join") {
            val joined = store.joinById(call.roomId(), call.receive<JoinRoomRequest>())
            hub.broadcast(joined.room.roomId, WebSocketEventType.MEMBER_JOINED, jsonCodec.memberPayload(joined.member))
            call.respond(joined)
        }
        get("/api/rooms/{roomId}") { call.respond(store.snapshot(call.roomId())) }
        delete("/api/rooms/{roomId}/members/{userId}") {
            store.leave(call.roomId(), call.parameters["userId"] ?: "")
            call.respond(HttpStatusCode.NoContent)
        }
        get("/api/rooms/{roomId}/playlist") { call.respond(PlaylistResponse(store.playlist(call.roomId()))) }
        if (storage != null) {
            post("/api/rooms/{roomId}/tracks") {
                val fields = mutableMapOf<String, String>()
                var temp: File? = null
                var filename: String? = null
                var actualHash: String? = null
                try {
                    call.receiveMultipart().forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> fields[part.name.orEmpty()] = part.value
                            is PartData.FileItem -> {
                                filename = part.originalFileName
                                val extension = filename?.substringAfterLast('.', "")?.lowercase()
                                if (extension !in setOf("mp3", "flac", "ogg", "aac", "wav", "opus", "m4a", "wma", "x-flac")) {
                                    throw HostServerError(415, "UNSUPPORTED_AUDIO_TYPE", "Only MP3, FLAC, OGG, AAC, WAV, OPUS, M4A, WMA are supported")
                                }
                                val file = File.createTempFile("upload-", ".part", storage.uploadDir)
                                temp = file
                                val digest = MessageDigest.getInstance("SHA-256")
                                var total = 0L
                                withContext(Dispatchers.IO) {
                                    part.provider().toInputStream().use { input ->
                                        file.outputStream().use { output ->
                                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                            while (true) {
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                total += count
                                                if (total > storage.maxUploadBytes) {
                                                    throw HostServerError(413, "FILE_TOO_LARGE", "Audio file exceeds the size limit")
                                                }
                                                digest.update(buffer, 0, count)
                                                output.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                }
                                actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                            }
                            else -> Unit
                        }
                        part.dispose()
                    }
                    val source = temp ?: throw HostServerError(400, "FILE_REQUIRED", "Audio file is required")
                    val hash = fields["fileHash"]?.lowercase()
                        ?: throw HostServerError(400, "VALIDATION_ERROR", "fileHash is required")
                    if (actualHash != hash) throw HostServerError(422, "HASH_MISMATCH", "Uploaded file hash does not match")
                    val extension = filename!!.substringAfterLast('.').lowercase()
                    val target = File(storage.audioDir, hash)
                    val deduplicated = target.exists()
                    if (!deduplicated && !source.renameTo(target)) {
                        source.copyTo(target, overwrite = true)
                    }
                    val track = store.addReadyTrack(
                        AddHostTrack(
                            roomId = call.roomId(),
                            title = fields["title"]?.takeIf(String::isNotBlank)
                                ?: throw HostServerError(400, "VALIDATION_ERROR", "title is required"),
                            artist = fields["artist"]?.trim()?.ifEmpty { null },
                            durationMs = fields["durationMs"]?.toLongOrNull()?.coerceAtLeast(0)
                                ?: throw HostServerError(400, "VALIDATION_ERROR", "durationMs is invalid"),
                            fileName = filename!!,
                            fileSize = target.length(),
                            fileHash = hash,
                            storagePath = target.path,
                            uploaderId = fields["uploaderId"].orEmpty(),
                            uploaderName = fields["uploaderName"].orEmpty(),
                        ),
                    )
                    hub.broadcast(call.roomId(), WebSocketEventType.TRACK_READY, jsonCodec.objectPayload(track))
                    hub.broadcast(
                        call.roomId(),
                        WebSocketEventType.PLAYLIST_UPDATED,
                        jsonCodec.objectPayload(PlaylistResponse(store.playlist(call.roomId()))),
                    )
                    call.respond(HttpStatusCode.Created, HostUploadResponse(track, deduplicated))
                } finally {
                    temp?.delete()
                }
            }
            get("/api/tracks/{trackId}/download") {
                val stored = store.storedTrack(call.parameters["trackId"].orEmpty())
                val file = File(stored.storagePath)
                if (!file.exists()) throw HostServerError(404, "TRACK_FILE_NOT_FOUND", "Track file does not exist")
                call.respondFile(file)
            }
        }
        post("/api/rooms/{roomId}/playback/play") {
            val state = store.play(call.roomId(), call.receive<TrackPlaybackCommand>())
            hub.broadcast(call.roomId(), WebSocketEventType.PLAY, jsonCodec.objectPayload(state))
            call.respond(PlaybackResponse(state))
        }
        post("/api/rooms/{roomId}/playback/pause") {
            val state = store.pause(call.roomId(), call.receive<TrackPlaybackCommand>())
            hub.broadcast(call.roomId(), WebSocketEventType.PAUSE, jsonCodec.objectPayload(state))
            call.respond(PlaybackResponse(state))
        }
        post("/api/rooms/{roomId}/playback/seek") {
            val state = store.seek(call.roomId(), call.receive<TrackPlaybackCommand>())
            hub.broadcast(call.roomId(), WebSocketEventType.SEEK, jsonCodec.objectPayload(state))
            call.respond(PlaybackResponse(state))
        }
        post("/api/rooms/{roomId}/playback/next") {
            val state = store.next(call.roomId(), call.receive<NextPlaybackCommand>())
            hub.broadcast(call.roomId(), WebSocketEventType.NEXT, jsonCodec.objectPayload(state))
            call.respond(PlaybackResponse(state))
        }
        put("/api/rooms/{roomId}/members/{userId}/role") {
            val targetUserId = call.parameters["userId"].orEmpty()
            val body = call.receive<Map<String, String>>()
            val role = com.synclisten.shared.domain.model.MemberRole.valueOf(body["role"] ?: "MEMBER")
            val updated = store.changeRole(call.roomId(), targetUserId, role)
            call.respond(mapOf("userId" to updated.userId, "role" to updated.role.name))
        }
        delete("/api/rooms/{roomId}/tracks/{trackId}") {
            store.removeTrack(call.roomId(), call.parameters["trackId"].orEmpty())
            hub.broadcast(call.roomId(), WebSocketEventType.TRACK_REMOVED,
                buildJsonObject { put("trackId", JsonPrimitive(call.parameters["trackId"].orEmpty())) })
            hub.broadcast(call.roomId(), WebSocketEventType.PLAYLIST_UPDATED,
                jsonCodec.objectPayload(com.synclisten.shared.data.PlaylistResponse(store.playlist(call.roomId()))))
            call.respond(mapOf("ok" to true))
        }
        put("/api/rooms/{roomId}/playlist/reorder") {
            val body = call.receive<Map<String, List<String>>>()
            store.reorderPlaylist(call.roomId(), body["orderedTrackIds"].orEmpty())
            hub.broadcast(call.roomId(), WebSocketEventType.PLAYLIST_UPDATED,
                jsonCodec.objectPayload(com.synclisten.shared.data.PlaylistResponse(store.playlist(call.roomId()))))
            call.respond(mapOf("ok" to true))
        }
        webSocket("/ws/rooms/{roomId}") {
            val roomId = call.roomId()
            val userId = call.request.queryParameters["userId"]
                ?: throw HostServerError(400, "VALIDATION_ERROR", "userId is required")
            val token = call.request.queryParameters["token"]
                ?: throw HostServerError(400, "VALIDATION_ERROR", "token is required")
            val snapshot = store.authenticate(roomId, userId, token)
            hub.add(roomId, this)
            try {
                hub.send(this, WebSocketEventType.ROOM_JOINED, jsonCodec.objectPayload(snapshot))
                val connectedMember = snapshot.members.first { it.userId == userId }
                hub.broadcast(roomId, WebSocketEventType.MEMBER_JOINED, jsonCodec.memberPayload(connectedMember))
                for (frame in incoming) if (frame is Frame.Text) frame.readText()
            } finally {
                hub.remove(roomId, this)
                runCatching { store.setConnected(roomId, userId, false) }
                hub.broadcast(
                    roomId,
                    WebSocketEventType.MEMBER_LEFT,
                    buildJsonObject { put("userId", userId) },
                )
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.roomId(): String =
    parameters["roomId"] ?: throw HostServerError(400, "VALIDATION_ERROR", "roomId is required")

private inline fun <reified T> Json.objectPayload(value: T) =
    encodeToJsonElement(value) as kotlinx.serialization.json.JsonObject

private fun Json.memberPayload(member: com.synclisten.shared.domain.model.Member) =
    buildJsonObject { put("member", encodeToJsonElement(member)) }
