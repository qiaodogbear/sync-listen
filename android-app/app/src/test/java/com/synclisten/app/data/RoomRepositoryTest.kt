package com.synclisten.app.data

import com.synclisten.app.domain.model.Member
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.domain.model.Room
import com.synclisten.app.domain.model.RoomStatus
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRepositoryTest {
    @Test
    fun createRoomReturnsRemoteRoom() = runBlocking {
        val expected = CreateRoomResponse(
            room = room(),
            member = member(),
            joinToken = "join-token",
        )
        val repository = RoomRepository(
            remote = object : RoomRemoteDataSource {
                override suspend fun createRoom(request: CreateRoomRequest) = expected
            },
        )

        val result = repository.createRoom("Friday", "user-1", "Alice")

        assertEquals(expected, (result as RepositoryResult.Success).value)
    }

    @Test
    fun createRoomMapsNetworkFailureToReadableMessage() = runBlocking {
        val repository = RoomRepository(
            remote = object : RoomRemoteDataSource {
                override suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse {
                    throw IOException("offline")
                }
            },
        )

        val result = repository.createRoom("Friday", "user-1", "Alice")

        assertTrue(result is RepositoryResult.Failure)
        assertEquals("无法连接服务器，请检查地址和网络", (result as RepositoryResult.Failure).message)
    }

    @Test
    fun joinRoomPassesManualRoomCodeAndIdentity() = runBlocking {
        var captured: JoinRoomRequest? = null
        val expected = JoinRoomResponse(room(), member().copy(role = MemberRole.MEMBER))
        val repository = RoomRepository(
            remote = object : RoomRemoteDataSource {
                override suspend fun joinRoom(roomId: String, request: JoinRoomRequest): JoinRoomResponse {
                    captured = request
                    return expected
                }
            },
        )

        val result = repository.joinRoom("room-1", "ABC123", "user-2", "Bob")

        assertEquals(expected, (result as RepositoryResult.Success).value)
        assertEquals(
            JoinRoomRequest(userId = "user-2", displayName = "Bob", roomCode = "ABC123"),
            captured,
        )
    }

    private fun room() = Room(
        roomId = "room-1",
        roomCode = "ABC123",
        name = "Friday",
        hostUserId = "user-1",
        status = RoomStatus.ACTIVE,
        createdAt = 1,
    )

    private fun member() = Member(
        userId = "user-1",
        displayName = "Alice",
        role = MemberRole.HOST,
        connected = false,
        joinedAt = 1,
    )
}
