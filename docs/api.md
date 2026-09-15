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

## Device Authentication (v0.3.0 Breaking Change)

All REST endpoints except `GET /health` and `GET /api/time` require:

```http
X-User-Id: USER_ID
Authorization: Bearer LOWERCASE_64_HEX_CREDENTIAL
```

Create and join bind the supplied credential to a room member. Body `userId` / `uploaderId` and a leave URL's user ID must match the header actor. Rejoining an existing user ID requires the original credential; an invitation code/token cannot replace it.

Official clients store an installation secret locally and derive a credential for each normalized `scheme://host:port` origin and user:

```text
SHA256("sync-listen-v1\n" + origin + "\n" + userId + "\n" + deviceSecret)
```

The server stores a hash of that credential, not the installation secret. Do not send secrets in URLs, reports, screenshots, or logs. Changing the origin (including an IP/port change) changes the derived credential. Legacy rooms without a credential cannot be rejoined as the old identity: upgrade all clients and recreate them.

This is device/member binding for a trusted LAN, not an account system or TLS. Joining a new room still requires a valid invite. Download URLs are protected too.

| Role | Allowed |
|---|---|
| Member | Snapshot, playlist, upload, download, WebSocket, leave self |
| Admin | Member actions plus playback, remove non-current tracks and reorder |
| Host | Admin actions plus promote/demote others; leaving closes the room |

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

`role`: `HOST | ADMIN | MEMBER`.

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
The actual response also includes `joinToken` for invitation and WebSocket room selection; it does not replace device authentication.

### `POST /api/rooms/join`

Joins an active room using only `userId`, `displayName`, and `roomCode`. This is the manual-code entry point used before a client knows the room ID. Returns `{ room, member, joinToken }` for invitation and WebSocket room selection; device authentication headers remain required.

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
| `file` | audio bytes | One file, default maximum 500 MiB; successful storage does not guarantee every client can decode it |

Response also includes `deduplicated`, which is true when an existing physical file was reused.

### `GET /api/tracks/{trackId}/download`

Downloads a ready track from server storage.

### Playback control

Host or Admin endpoints:

- `POST /api/rooms/{roomId}/playback/play`
- `POST /api/rooms/{roomId}/playback/pause`
- `POST /api/rooms/{roomId}/playback/seek`
- `POST /api/rooms/{roomId}/playback/next`

PLAY, SEEK, and NEXT responses include a shared future `executeAtServerTimeMs`. Member requests return HTTP 403 with code `HOST_REQUIRED`.

### `GET /api/time`

Returns `{ serverTimeMs, serverReceivedAtMs?, serverSentAtMs? }` for client clock offset estimation. The optional receive/send timestamps are available in the post-v0.3.0 development branch; `serverTimeMs` remains the send timestamp for compatibility. Older responses without the optional fields are supported. See [clock and checkpoint design](sync-and-nearby.md).

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

## Playlist and Role Changes

- `PUT /api/rooms/{roomId}/members/{userId}/role`: Host only; body `{ "role": "ADMIN" }` or `{ "role": "MEMBER" }`. Cannot change the Host role.
- `PUT /api/rooms/{roomId}/playlist/reorder`: Host/Admin; body `{ "orderedTrackIds": ["track-1", "track-2"] }`. Must be an exact permutation of the current playlist with no missing, extra or duplicate IDs.
- `DELETE /api/rooms/{roomId}/tracks/{trackId}`: Host/Admin; 204. The currently selected track is protected, even while paused.

## Download Ranges

`GET /api/tracks/{trackId}/download` supports standard byte ranges and returns 206 with Content-Range for a valid partial response. Invalid/unsatisfiable ranges return 416; a client must not append a full 200 response to a partial file. Completed downloads must match declared size and SHA-256.

## Error codes

| HTTP | Code | Meaning |
|---|---|---|
| 401 | `AUTH_REQUIRED` | Missing or malformed device identity/credential |
| 403 | `IDENTITY_MISMATCH` / `INVALID_CREDENTIAL` | Claimed actor or saved credential does not match |
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
| 415 | `UNSUPPORTED_AUDIO_TYPE` | Declared audio type is unsupported |
| 422 | `HASH_MISMATCH` | Uploaded bytes do not match declared SHA-256 |
