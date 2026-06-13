# WebSocket Protocol

## Connection

Endpoint:

```text
/ws/rooms/{roomId}?token=JOIN_TOKEN&userId=USER_ID
```

The server validates the room, join token, and user before accepting the connection.

## Common envelope

Every event uses the same envelope:

```json
{
  "type": "TRACK_READY",
  "payload": {
    "trackId": "track-1"
  },
  "serverTimeMs": 1710000000000
}
```

- `type` is one of the event names below.
- `payload` is always a JSON object.
- `serverTimeMs` is the server time when the event was emitted.

## P0 event types

```text
ROOM_JOINED
MEMBER_JOINED
MEMBER_LEFT
TRACK_ADDED
TRACK_UPLOAD_PROGRESS
TRACK_READY
TRACK_REMOVED
PLAYLIST_UPDATED
PLAY
PAUSE
SEEK
NEXT
SYNC
DOWNLOAD_HINT
ERROR
```

## Playback examples

PLAY:

```json
{
  "type": "PLAY",
  "payload": {
    "trackId": "track-1",
    "positionMs": 0,
    "executeAtServerTimeMs": 1710000001500
  },
  "serverTimeMs": 1710000000000
}
```

SYNC:

```json
{
  "type": "SYNC",
  "payload": {
    "trackId": "track-1",
    "positionMs": 32000,
    "isPlaying": true
  },
  "serverTimeMs": 1710000032000
}
```

ERROR:

```json
{
  "type": "ERROR",
  "payload": {
    "code": "FORBIDDEN",
    "message": "Only the host can control playback"
  },
  "serverTimeMs": 1710000000000
}
```

## Reconnection rule

After reconnecting, the server sends `ROOM_JOINED` with the authoritative room, member, playlist, and playback snapshot. Clients replace local room state with this snapshot before applying later events.

The client ignores stale `PLAYLIST_UPDATED` events using envelope `serverTimeMs`, then deduplicates
Track IDs and sorts by server `orderIndex`. The server may temporarily see multiple sockets for one
user during reconnect; the member remains online until the final socket closes.

## Payload summary

| Event | Payload |
|---|---|
| `ROOM_JOINED` | Full `{ room, members, playlist, playbackState }` snapshot |
| `MEMBER_JOINED` | `{ member }` |
| `MEMBER_LEFT` | `{ userId }` |
| `TRACK_ADDED`, `TRACK_READY` | `{ track }` |
| `PLAYLIST_UPDATED` | `{ playlist }` |
| `PLAY`, `PAUSE`, `SEEK`, `NEXT`, `SYNC` | `PlaybackState` |

Current clients treat unrecognized protocol events as no-ops so a later authoritative snapshot can
restore state.

## Playback timing

- PLAY, SEEK and NEXT include a future `executeAtServerTimeMs`.
- PAUSE is applied immediately.
- SYNC omits the scheduled time and reports the current authoritative position.
- Clients continue local playback while disconnected and apply the next snapshot/SYNC after reconnect.
