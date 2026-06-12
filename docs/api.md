# REST API

## Conventions

- Base path: `/api`
- Content type: `application/json` unless an endpoint states otherwise.
- Times are Unix epoch milliseconds.
- IDs are opaque strings.
- Nullable fields are returned explicitly as `null`.

Error response:

```json
{
  "error": {
    "code": "ROOM_NOT_FOUND",
    "message": "Room does not exist",
    "details": null
  }
}
```

`details` is optional and may contain structured validation information.

## Shared models

### Room

```json
{
  "roomId": "room-1",
  "roomCode": "ABCD12",
  "name": "Alice's room",
  "hostUserId": "user-1",
  "status": "ACTIVE",
  "createdAt": 1710000000000
}
```

`status`: `ACTIVE | CLOSED`.

### Member

```json
{
  "userId": "user-1",
  "displayName": "Alice",
  "role": "HOST",
  "connected": true,
  "joinedAt": 1710000000000
}
```

`role`: `HOST | MEMBER`.

### Track

```json
{
  "trackId": "track-1",
  "roomId": "room-1",
  "title": "Test Song",
  "artist": null,
  "durationMs": 123000,
  "fileName": "test.flac",
  "fileSize": 456,
  "fileHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "uploaderId": "user-1",
  "uploaderName": "Alice",
  "orderIndex": 0,
  "status": "READY",
  "createdAt": 1710000000000
}
```

`status`: `UPLOADING | READY | FAILED`.

### PlaybackState

```json
{
  "trackId": "track-1",
  "positionMs": 32000,
  "isPlaying": true,
  "serverTimeMs": 1710000000000,
  "executeAtServerTimeMs": null
}
```

## Implemented endpoints

### `GET /health`

Returns process health and current server time.

```json
{
  "status": "ok",
  "serverTimeMs": 1710000000000
}
```

### `POST /api/rooms`

Creates a room and its Host member.

```json
{
  "name": "Friday listening",
  "userId": "user-host",
  "displayName": "Alice"
}
```

Returns HTTP 201 with `{ room, member, joinToken }`.

### `POST /api/rooms/{roomId}/join`

Joins using either `joinToken` or `roomCode`.

```json
{
  "userId": "user-member",
  "displayName": "Bob",
  "joinToken": "opaque-token"
}
```

Returns `{ room, member }`.

### `POST /api/rooms/join`

Joins an active room using only `userId`, `displayName`, and `roomCode`. This is the manual-code entry point used before a client knows the room ID.

### `GET /api/rooms/{roomId}`

Returns the authoritative `{ room, members, playlist, playbackState }` snapshot. Closed rooms return HTTP 410 with code `ROOM_CLOSED`.

### `DELETE /api/rooms/{roomId}/members/{userId}`

Leaves a room. If the user is the Host, the room is closed. Returns HTTP 204.

### `GET /api/rooms/{roomId}/playlist`

Returns `{ playlist }` ordered by the server-assigned `orderIndex`.

### `POST /api/rooms/{roomId}/tracks`

Accepts a multipart upload with metadata fields before the `file` field. The server streams the file, verifies its SHA-256 hash, deduplicates physical storage, and returns HTTP 201 with `{ track }`.

### `GET /api/tracks/{trackId}/download`

Downloads a ready track from server storage.

### Playback control

Host-only endpoints:

- `POST /api/rooms/{roomId}/playback/play`
- `POST /api/rooms/{roomId}/playback/pause`
- `POST /api/rooms/{roomId}/playback/seek`
- `POST /api/rooms/{roomId}/playback/next`

PLAY, SEEK, and NEXT responses include a shared future `executeAtServerTimeMs`. Member requests return HTTP 403 with code `HOST_REQUIRED`.

### `GET /api/time`

Returns `{ serverTimeMs }` for client clock offset estimation.
