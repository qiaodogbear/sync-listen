package com.synclisten.app.host.server

import com.synclisten.app.data.CreateRoomRequest
import com.synclisten.app.data.JoinRoomRequest
import com.synclisten.app.data.NextPlaybackCommand
import com.synclisten.app.data.PlaybackResponse
import com.synclisten.app.data.PlaylistResponse
import com.synclisten.app.data.ServerTimeResponse
import com.synclisten.app.data.TrackPlaybackCommand
import com.synclisten.app.domain.model.ApiError
import com.synclisten.app.domain.model.ErrorResponse
import com.synclisten.app.domain.model.WebSocketEventType
import com.synclisten.app.domain.model.Track
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.path
import io.ktor.server.plugins.partialcontent.PartialContent
import kotlinx.coroutines.CancellationException
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.application.Application
import io.ktor.server.application.log
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

@Serializable
data class ChangeRoleBody(val role: String)

@Serializable
data class ReorderBody(val orderedTrackIds: List<String>)

fun Application.hostServerModule(
    store: HostRoomStore,
    hub: HostRoomHub,
    jsonCodec: Json = Json { ignoreUnknownKeys = true },
    clock: () -> Long = System::currentTimeMillis,
    storage: HostStorage? = null,
) {
    install(ContentNegotiation) { json(jsonCodec) }
    install(WebSockets) {
        pingPeriodMillis = 30_000
        timeoutMillis = 45_000
        maxFrameSize = 1_048_576
    }
    install(PartialContent)
    install(StatusPages) {
        exception<HostServerError> { call, error ->
            call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(ApiError(error.code, error.message)))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError("VALIDATION_ERROR", "Invalid request")))
        }
        exception<kotlinx.serialization.SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError("VALIDATION_ERROR", "Invalid request")))
        }
        exception<Throwable> { call, error ->
            if (error is CancellationException) throw error
            call.application.log.error("Host request failed", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ApiError("INTERNAL_SERVER_ERROR", "Internal server error")))
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
    val authorization = createRouteScopedPlugin("RoomCredentials") {
        onCall { call ->
            val path = call.request.path()
            if (path != "/health" && path != "/api/time") {
                val credential = call.credential()
                val userId = call.actor()
                if (path != "/api/rooms" && !path.endsWith("/join")) {
                    val roomId = call.parameters["roomId"] ?: call.parameters["trackId"]?.let { store.storedTrack(it).track.roomId }
                    if (roomId != null) store.authorize(roomId, userId, credential)
                }
            }
        }
    }
    routing {
        install(authorization)
        get("/health") {
            call.respond(buildJsonObject {
                put("status", "ok")
                put("serverTimeMs", clock())
            })
        }
        get("/api/time") { call.respond(ServerTimeResponse(clock())) }
        post("/api/rooms") {
            val request = call.receive<CreateRoomRequest>()
            call.requireActor(request.userId)
            call.respond(HttpStatusCode.Created, store.createRoom(request, call.credential()))
        }
        post("/api/rooms/join") {
            val request = call.receive<JoinRoomRequest>()
            if (request.roomCode == null) throw HostServerError(400, "ROOM_CODE_REQUIRED", "Room code is required")
            call.requireActor(request.userId)
            val joined = store.joinByCode(request, call.credential())
            hub.broadcast(joined.room.roomId, WebSocketEventType.MEMBER_JOINED, jsonCodec.memberPayload(joined.member))
            call.respond(joined)
        }
        post("/api/rooms/{roomId}/join") {
            val request = call.receive<JoinRoomRequest>()
            call.requireActor(request.userId)
            val joined = store.joinById(call.roomId(), request, call.credential())
            hub.broadcast(joined.room.roomId, WebSocketEventType.MEMBER_JOINED, jsonCodec.memberPayload(joined.member))
            call.respond(joined)
        }
        get("/api/rooms/{roomId}") { call.respond(store.snapshot(call.roomId())) }
        delete("/api/rooms/{roomId}/members/{userId}") {
            val userId = call.parameters["userId"].orEmpty()
            call.requireActor(userId)
            val closing = store.snapshot(call.roomId()).room.hostUserId == userId
            store.leave(call.roomId(), userId)
            if (closing) hub.closeAll() else hub.closeMember(call.roomId(), userId)
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
                    call.receiveMultipart(formFieldLimit = storage.maxUploadBytes).forEachPart { part ->
                        try {
                        when (part) {
                            is PartData.FormItem -> fields[part.name.orEmpty()] = part.value
                            is PartData.FileItem -> {
                                if (temp != null) throw HostServerError(400, "MULTIPLE_FILES", "Only one audio file is allowed")
                                filename = part.originalFileName
                                val extension = filename?.substringAfterLast('.', "")?.lowercase()
                                if (extension !in setOf("mp3", "flac", "ogg", "aac", "wav", "opus", "m4a", "wma")) {
                                    throw HostServerError(415, "UNSUPPORTED_AUDIO_TYPE", "Only MP3/FLAC/OGG/AAC/WAV/Opus/M4A/WMA are supported")
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
                        } finally { part.dispose() }
                    }
                    val source = temp ?: throw HostServerError(400, "FILE_REQUIRED", "Audio file is required")
                    val hash = fields["fileHash"]?.lowercase()
                        ?: throw HostServerError(400, "VALIDATION_ERROR", "fileHash is required")
                    if (actualHash != hash) throw HostServerError(422, "HASH_MISMATCH", "Uploaded file hash does not match")
                    call.requireActor(fields["uploaderId"].orEmpty())
                    if (fields["title"].isNullOrBlank() || fields["durationMs"]?.toLongOrNull() == null) {
                        throw HostServerError(400, "VALIDATION_ERROR", "title and durationMs are required")
                    }
                    val target = File(storage.audioDir, hash)
                    val deduplicated = target.exists()
                    if (!deduplicated) {
                        try { java.nio.file.Files.move(source.toPath(), target.toPath()) }
                        catch (error: java.nio.file.FileAlreadyExistsException) {
                            if (!target.isFile) throw error
                        }
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
                    hub.broadcast(call.roomId(), WebSocketEventType.TRACK_READY, buildJsonObject { put("track", jsonCodec.encodeToJsonElement(track)) })
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
            val command = call.receive<TrackPlaybackCommand>()
            call.requireActor(command.userId)
            val state = store.play(call.roomId(), command)
            hub.broadcast(call.roomId(), WebSocketEventType.PLAY, jsonCodec.objectPayload(state))
            call.respond(PlaybackResponse(state))
        }
        post("/api/rooms/{roomId}/playback/pause") {
            val command = call.receive<TrackPlaybackCommand>()
            call.requireActor(command.userId)
            val state = store.pause(call.roomId(), command)
            hub.broadcast(call.roomId(), WebSocketEventType.PAUSE, jsonCodec.objectPayload(state))
            call.respond(PlaybackResponse(state))
        }
        post("/api/rooms/{roomId}/playback/seek") {
            val command = call.receive<TrackPlaybackCommand>()
            call.requireActor(command.userId)
            val state = store.seek(call.roomId(), command)
            hub.broadcast(call.roomId(), WebSocketEventType.SEEK, jsonCodec.objectPayload(state))
            call.respond(PlaybackResponse(state))
        }
        post("/api/rooms/{roomId}/playback/next") {
            val command = call.receive<NextPlaybackCommand>()
            call.requireActor(command.userId)
            val state = store.next(call.roomId(), command)
            hub.broadcast(call.roomId(), WebSocketEventType.NEXT, jsonCodec.objectPayload(state))
            call.respond(PlaybackResponse(state))
        }
        put("/api/rooms/{roomId}/members/{userId}/role") {
            val body = call.receive<ChangeRoleBody>()
            val role = try { com.synclisten.app.domain.model.MemberRole.valueOf(body.role) }
                catch (_: Exception) { throw HostServerError(400, "INVALID_ROLE", "Role must be ADMIN or MEMBER") }
            store.changeRole(call.roomId(), call.parameters["userId"].orEmpty(), role, call.actor())
            hub.broadcast(call.roomId(), WebSocketEventType.ROLE_CHANGED,
                buildJsonObject { put("userId", call.parameters["userId"].orEmpty()); put("role", role.name) })
            call.respond(mapOf("userId" to call.parameters["userId"].orEmpty(), "role" to role.name))
        }
        delete("/api/rooms/{roomId}/tracks/{trackId}") {
            store.removeTrack(call.roomId(), call.parameters["trackId"].orEmpty(), call.actor())
            hub.broadcast(call.roomId(), WebSocketEventType.TRACK_REMOVED,
                buildJsonObject { put("trackId", call.parameters["trackId"].orEmpty()) })
            hub.broadcast(call.roomId(), WebSocketEventType.PLAYLIST_UPDATED,
                jsonCodec.objectPayload(PlaylistResponse(store.playlist(call.roomId()))))
            call.respond(HttpStatusCode.NoContent)
        }
        put("/api/rooms/{roomId}/playlist/reorder") {
            val body = call.receive<ReorderBody>()
            store.reorderPlaylist(call.roomId(), body.orderedTrackIds, call.actor())
            hub.broadcast(call.roomId(), WebSocketEventType.PLAYLIST_UPDATED,
                jsonCodec.objectPayload(PlaylistResponse(store.playlist(call.roomId()))))
            call.respond(mapOf("ok" to true))
        }
        webSocket("/ws/rooms/{roomId}") {
            val roomId = call.roomId()
            val userId = call.request.queryParameters["userId"]
                ?: throw HostServerError(400, "VALIDATION_ERROR", "userId is required")
            val token = call.request.queryParameters["token"]
                ?: throw HostServerError(400, "VALIDATION_ERROR", "token is required")
            call.requireActor(userId)
            val snapshot = store.authenticate(roomId, userId, token)
            hub.add(roomId, this, userId)
            try {
                hub.send(this, WebSocketEventType.ROOM_JOINED, jsonCodec.objectPayload(snapshot))
                val connectedMember = snapshot.members.first { it.userId == userId }
                hub.broadcast(roomId, WebSocketEventType.MEMBER_JOINED, jsonCodec.memberPayload(connectedMember))
                for (frame in incoming) if (frame is Frame.Text) frame.readText()
            } finally {
                hub.remove(roomId, this)
                if (!hub.isConnected(roomId, userId)) {
                    runCatching { store.setConnected(roomId, userId, false) }
                    hub.broadcast(roomId, WebSocketEventType.MEMBER_LEFT, buildJsonObject { put("userId", userId) })
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.actor(): String =
    request.headers["X-User-Id"]?.takeIf { it.isNotBlank() && it.length <= 128 }
        ?: throw HostServerError(401, "AUTH_REQUIRED", "A device identity is required")

private fun io.ktor.server.application.ApplicationCall.credential(): String =
    request.headers["Authorization"]?.let { Regex("^Bearer ([a-f0-9]{64})$").matchEntire(it)?.groupValues?.get(1) }
        ?: throw HostServerError(401, "AUTH_REQUIRED", "A device credential is required")

private fun io.ktor.server.application.ApplicationCall.requireActor(userId: String) {
    if (actor() != userId) throw HostServerError(403, "IDENTITY_MISMATCH", "Cannot act as another member")
}

private fun io.ktor.server.application.ApplicationCall.roomId(): String =
    parameters["roomId"] ?: throw HostServerError(400, "VALIDATION_ERROR", "roomId is required")

private inline fun <reified T> Json.objectPayload(value: T) =
    encodeToJsonElement(value) as kotlinx.serialization.json.JsonObject

private fun Json.memberPayload(member: com.synclisten.app.domain.model.Member) =
    buildJsonObject { put("member", encodeToJsonElement(member)) }
