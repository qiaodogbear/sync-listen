package com.synclisten.shared.data

import com.synclisten.cloud.CloudSearchRequest
import com.synclisten.cloud.CloudSearchResponse
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CloudRepositoryTest {
    @Test fun retrofitDecodesCloudSearchWithOmittedOptionalFields() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"tracks":[{"source":"qq","id":"song","title":"Test","artists":["Artist"],"mediaId":"media"}]}"""))
            val settings = MemorySettingsStore(AppSettings(serverUrl = server.url("/").toString()))
            val remote = RetrofitRoomRemoteDataSource(settings, RetrofitApiFactory(OkHttpClient(), Json { ignoreUnknownKeys = true }))
            val result = RoomRepository(remote).searchCloud("room", "qq", "test") as RepositoryResult.Success
            assertEquals("media", result.value.tracks.single().mediaId)
            assertEquals("/api/rooms/room/cloud/search", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun sendsSearchToRoom() = runBlocking {
        var captured: Pair<String, CloudSearchRequest>? = null
        val repository = RoomRepository(object : RoomRemoteDataSource {
            override suspend fun searchCloud(roomId: String, request: CloudSearchRequest): CloudSearchResponse {
                captured = roomId to request
                return CloudSearchResponse(emptyList())
            }
        })
        assertTrue(repository.searchCloud("room", "qq", "test", 2) is RepositoryResult.Success)
        assertEquals("room" to CloudSearchRequest("qq", "test", 2), captured)
    }

    @Test fun explainsMissingRoomOrOldHost() = runBlocking {
        val repository = RoomRepository(object : RoomRemoteDataSource {
            override suspend fun searchCloud(roomId: String, request: CloudSearchRequest): CloudSearchResponse =
                throw HttpException(Response.error<CloudSearchResponse>(404, "".toResponseBody()))
        })
        val result = repository.searchCloud("room", "qq", "test") as RepositoryResult.Failure
        assertEquals("CLOUD_NOT_FOUND", result.code)
    }
}
