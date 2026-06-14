# REST API

## Conventions

- Base path: `/api`
- Content type: `application/json` unless an endpoint states otherwise.
- Times are Unix epoch milliseconds.
- IDs are opaque strings.
- Nullable fields are returned explicitly as `null`.

The same REST and WebSocket contract is served by the computer backend and Android mobile-host
mode. Mobile-host mode listens on the Host phone's reachable LAN address at port `38571`.

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
The actual response also includes `joinToken` so the client can authenticate WebSocket recovery.

### `POST /api/rooms/join`

Joins an active room using only `userId`, `displayName`, and `roomCode`. This is the manual-code entry point used before a client knows the room ID. Returns `{ room, member, joinToken }` so the client can authenticate its WebSocket connection.

### `GET /api/rooms/{roomId}`

Returns the authoritative `{ room, members, playlist, playbackState }` snapshot. Closed rooms return HTTP 410 with code `ROOM_CLOSED`.

### `DELETE /api/rooms/{roomId}/members/{userId}`

Leaves a room. If the user is the Host, the room is closed. Returns HTTP 204.

### `GET /api/rooms/{roomId}/playlist`

Returns `{ playlist }` ordered by the server-assigned `orderIndex`.

### `POST /api/rooms/{roomId}/tracks`

Accepts a multipart upload with metadata fields before the `file` field. The server streams the file, verifies its SHA-256 hash, deduplicates physical storage, and returns HTTP 201 with `{ track }`.

Fields:

| Field | Type | Notes |
|---|---|---|
| `title` | string | Required |
| `artist` | string | Empty becomes `null` |
| `durationMs` | integer | Non-negative |
| `fileHash` | string | 64-character SHA-256 hex |
| `uploaderId` | string | Required |
| `uploaderName` | string | Required |
| `file` | MP3/FLAC | One file, default maximum 512 MiB |

Response also includes `deduplicated`, which is true when an existing physical file was reused.

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

## Playback request bodies

PLAY, PAUSE and SEEK:

```json
{
  "userId": "user-host",
  "trackId": "track-1",
  "positionMs": 12000
}
```

NEXT:

```json
{
  "userId": "user-host",
  "positionMs": 0
}
```

## Error codes

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Request shape or field validation failed |
| 400 | `ROOM_CODE_REQUIRED` | Manual join omitted a room code |
| 400 | `FILE_REQUIRED` / `MULTIPLE_FILES` | Invalid upload file count |
| 403 | `INVALID_JOIN_TOKEN` | Join credential is invalid |
| 403 | `HOST_REQUIRED` | Member attempted Host-only playback control |
| 404 | `ROOM_NOT_FOUND` / `MEMBER_NOT_FOUND` | Requested identity does not exist |
| 404 | `TRACK_FILE_NOT_FOUND` | Download file is absent |
| 409 | `TRACK_NOT_READY` / `NO_NEXT_TRACK` | Playback precondition failed |
| 410 | `ROOM_CLOSED` | Room is no longer active |
| 413 | `FILE_TOO_LARGE` | Upload exceeds the configured default limit |
| 415 | `UNSUPPORTED_AUDIO_TYPE` | File is not MP3 or FLAC |
| 422 | `HASH_MISMATCH` | Uploaded bytes do not match declared SHA-256 |
