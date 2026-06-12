import type { DatabaseSync } from "node:sqlite";

import { AppError } from "../errors.js";
import { toTrack, type TrackRow } from "../tracks/model.js";

export type RoomRow = {
  room_id: string;
  room_code: string;
  join_token: string;
  name: string;
  host_user_id: string;
  status: "ACTIVE" | "CLOSED";
  created_at: number;
};

type MemberRow = {
  user_id: string;
  display_name: string;
  role: "HOST" | "MEMBER";
  connected: number;
  joined_at: number;
};

type PlaybackRow = {
  track_id: string | null;
  position_ms: number;
  is_playing: number;
  server_time_ms: number;
  execute_at_server_time_ms: number | null;
};

export function findActiveRoom(database: DatabaseSync, roomId: string): RoomRow {
  const room = database
    .prepare("SELECT * FROM rooms WHERE room_id = ?")
    .get(roomId) as RoomRow | undefined;
  if (room === undefined) {
    throw new AppError(404, "ROOM_NOT_FOUND", "Room does not exist");
  }
  if (room.status === "CLOSED") {
    throw new AppError(410, "ROOM_CLOSED", "Room is closed");
  }
  return room;
}

export function findActiveRoomByCode(database: DatabaseSync, roomCode: string): RoomRow {
  const room = database
    .prepare("SELECT * FROM rooms WHERE room_code = ?")
    .get(roomCode.toUpperCase()) as RoomRow | undefined;
  if (room === undefined) {
    throw new AppError(404, "ROOM_NOT_FOUND", "Room does not exist");
  }
  if (room.status === "CLOSED") {
    throw new AppError(410, "ROOM_CLOSED", "Room is closed");
  }
  return room;
}

export function findMember(
  database: DatabaseSync,
  roomId: string,
  userId: string,
): MemberRow {
  const member = database
    .prepare("SELECT * FROM members WHERE room_id = ? AND user_id = ?")
    .get(roomId, userId) as MemberRow | undefined;
  if (member === undefined) {
    throw new AppError(403, "INVALID_MEMBER", "User is not a room member");
  }
  return member;
}

export function toRoom(row: RoomRow) {
  return {
    roomId: row.room_id,
    roomCode: row.room_code,
    name: row.name,
    hostUserId: row.host_user_id,
    status: row.status,
    createdAt: row.created_at,
  };
}

export function toMember(row: MemberRow) {
  return {
    userId: row.user_id,
    displayName: row.display_name,
    role: row.role,
    connected: row.connected === 1,
    joinedAt: row.joined_at,
  };
}

export function getRoomSnapshot(database: DatabaseSync, roomId: string) {
  const room = findActiveRoom(database, roomId);
  const members = database
    .prepare("SELECT * FROM members WHERE room_id = ? ORDER BY joined_at")
    .all(roomId) as MemberRow[];
  const playback = database
    .prepare("SELECT * FROM playback_states WHERE room_id = ?")
    .get(roomId) as PlaybackRow;
  const tracks = (
    database
    .prepare("SELECT * FROM tracks WHERE room_id = ? ORDER BY order_index")
      .all(roomId) as TrackRow[]
  ).map(toTrack);

  return {
    room: toRoom(room),
    members: members.map(toMember),
    playlist: tracks,
    playbackState: {
      trackId: playback.track_id,
      positionMs: playback.position_ms,
      isPlaying: playback.is_playing === 1,
      serverTimeMs: playback.server_time_ms,
      executeAtServerTimeMs: playback.execute_at_server_time_ms,
    },
  };
}
