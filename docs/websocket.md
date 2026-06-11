# WebSocket Protocol

## Connection

Planned P0 endpoint:

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

