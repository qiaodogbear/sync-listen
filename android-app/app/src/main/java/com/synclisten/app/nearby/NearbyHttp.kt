package com.synclisten.app.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val NEARBY_HEADER = "X-Nearby-Session"

fun Application.nearbyModule(nonce: String, profile: () -> NearbyProfile, gate: NearbyInvitationGate) {
    val json = Json { ignoreUnknownKeys = true }
    routing {
        get("/nearby/profile") {
            if (call.request.headers[NEARBY_HEADER] != nonce) {
                call.respondText("", status = HttpStatusCode.Unauthorized)
            } else call.respondText(json.encodeToString(profile()), io.ktor.http.ContentType.Application.Json)
        }
        post("/nearby/invite") {
            if (call.request.headers[NEARBY_HEADER] != nonce) {
                call.respondText("", status = HttpStatusCode.Unauthorized)
                return@post
            }
            val channel = call.receiveChannel()
            val bytes = ByteArray(4097)
            var size = 0
            val complete = withTimeoutOrNull(3_000) {
                while (size < bytes.size) {
                    val count = channel.readAvailable(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                true
            } ?: false
            if (!complete || size > 4096) {
                call.respondText("", status = if (complete) HttpStatusCode.PayloadTooLarge else HttpStatusCode.RequestTimeout)
                return@post
            }
            val invite = runCatching { json.decodeFromString<NearbyInvitation>(bytes.decodeToString(0, size)) }.getOrNull()
            val status = invite?.let(gate::receive) ?: 400
            call.respondText("", status = HttpStatusCode.fromValue(status))
        }
    }
}
