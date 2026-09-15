import { randomUUID } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";

import { AppError } from "../errors.js";
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

  removeTrack(roomId: string, trackId: string) {
    findActiveRoom(this.database, roomId);
    const current = this.database.prepare("SELECT track_id FROM playback_states WHERE room_id = ?").get(roomId) as { track_id: string | null };
    if (current.track_id === trackId) throw new AppError(409, "CURRENT_TRACK_PROTECTED", "Switch tracks before removing the current track");
    const result = this.database.prepare("DELETE FROM tracks WHERE track_id = ? AND room_id = ?").run(trackId, roomId);
    if (result.changes === 0) throw new AppError(404, "TRACK_NOT_FOUND", "Track does not exist");
    this.roomHub?.broadcast(roomId, "TRACK_REMOVED", { trackId });
    this.roomHub?.broadcast(roomId, "PLAYLIST_UPDATED", { playlist: this.getPlaylist(roomId) });
  }

  reorderPlaylist(roomId: string, orderedTrackIds: string[]) {
    const current = this.getPlaylist(roomId);
    const ids = new Set(orderedTrackIds);
    if (orderedTrackIds.length !== current.length || ids.size !== current.length || current.some((track) => !ids.has(track.trackId))) {
      throw new AppError(409, "PLAYLIST_CONFLICT", "Reorder must contain each current track exactly once");
    }
    this.database.exec("BEGIN IMMEDIATE");
    try {
      // Move all rows outside the final index range before applying a permutation.
      this.database.prepare("UPDATE tracks SET order_index = -order_index - 1 WHERE room_id = ?").run(roomId);
      for (let i = 0; i < orderedTrackIds.length; i++) {
        this.database.prepare("UPDATE tracks SET order_index = ? WHERE track_id = ? AND room_id = ?")
          .run(i, orderedTrackIds[i]!, roomId);
      }
      this.database.exec("COMMIT");
    } catch (e) {
      this.database.exec("ROLLBACK");
      throw e;
    }
    this.roomHub?.broadcast(roomId, "PLAYLIST_UPDATED", { playlist: this.getPlaylist(roomId) });
  }
}
