import { randomUUID } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";

import { findActiveRoom } from "../rooms/snapshot.js";
import type { RoomHub } from "../websocket/roomHub.js";
import { toTrack, type TrackRow } from "./model.js";

export type AddReadyTrackInput = {
  roomId: string;
  title: string;
  artist: string | null;
  durationMs: number;
  fileName: string;
  fileSize: number;
  fileHash: string;
  storagePath: string;
  uploaderId: string;
  uploaderName: string;
};

export class PlaylistService {
  constructor(
    private readonly database: DatabaseSync,
    private readonly roomHub?: RoomHub,
  ) {}

  async addReadyTrack(input: AddReadyTrackInput) {
    findActiveRoom(this.database, input.roomId);
    const trackId = randomUUID();
    const createdAt = Date.now();

    this.database.exec("BEGIN IMMEDIATE");
    try {
      const row = this.database
        .prepare(
          "SELECT COALESCE(MAX(order_index), -1) + 1 AS next_index FROM tracks WHERE room_id = ?",
        )
        .get(input.roomId) as { next_index: number };
      this.database
        .prepare(
          `INSERT INTO tracks
           (track_id, room_id, title, artist, duration_ms, file_name, file_size,
            file_hash, storage_path, uploader_id, uploader_name, order_index, status, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?)`,
        )
        .run(
          trackId,
          input.roomId,
          input.title,
          input.artist,
          input.durationMs,
          input.fileName,
          input.fileSize,
          input.fileHash,
          input.storagePath,
          input.uploaderId,
          input.uploaderName,
          row.next_index,
          createdAt,
        );
      this.database.exec("COMMIT");
    } catch (error) {
      this.database.exec("ROLLBACK");
      throw error;
    }

    const track = this.getTrack(trackId);
    this.roomHub?.broadcast(input.roomId, "TRACK_ADDED", { track });
    this.roomHub?.broadcast(input.roomId, "TRACK_READY", { track });
    this.roomHub?.broadcast(input.roomId, "PLAYLIST_UPDATED", {
      playlist: this.getPlaylist(input.roomId),
    });
    return track;
  }

  getPlaylist(roomId: string) {
    findActiveRoom(this.database, roomId);
    return (
      this.database
        .prepare("SELECT * FROM tracks WHERE room_id = ? ORDER BY order_index")
        .all(roomId) as TrackRow[]
    ).map(toTrack);
  }

  getTrack(trackId: string) {
    const row = this.database
      .prepare("SELECT * FROM tracks WHERE track_id = ?")
      .get(trackId) as TrackRow;
    return toTrack(row);
  }
}
