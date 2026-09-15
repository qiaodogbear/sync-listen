import type { DatabaseSync } from "node:sqlite";

import { AppError } from "../errors.js";
import { findActiveRoom, findMember } from "../rooms/snapshot.js";
import type { TrackRow } from "../tracks/model.js";

type BroadcastTarget = {
  broadcast(roomId: string, type: string, payload: Record<string, unknown>): void;
};

type PlaybackRow = {
  room_id: string;
  track_id: string | null;
  position_ms: number;
  is_playing: number;
  server_time_ms: number;
  execute_at_server_time_ms: number | null;
};

export class PlaybackService {
  private timer: NodeJS.Timeout | undefined;

  constructor(
    private readonly database: DatabaseSync,
    private readonly broadcaster: BroadcastTarget,
    private readonly leadTimeMs = 1500,
    private readonly syncIntervalMs = 5000,
  ) {}

  start(): void {
    if (this.timer !== undefined) return;
    this.timer = setInterval(() => this.broadcastSync(), this.syncIntervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer !== undefined) clearInterval(this.timer);
    this.timer = undefined;
  }

  play(roomId: string, userId: string, trackId: string, positionMs: number) {
    this.requireHostOrAdmin(roomId, userId);
    this.requireReadyTrack(roomId, trackId);
    return this.schedule(roomId, "PLAY", trackId, positionMs);
  }

  seek(roomId: string, userId: string, trackId: string, positionMs: number) {
    this.requireHostOrAdmin(roomId, userId);
    this.requireReadyTrack(roomId, trackId);
    return this.schedule(roomId, "SEEK", trackId, positionMs, this.getState(roomId).isPlaying);
  }

  pause(roomId: string, userId: string, trackId: string, positionMs: number) {
    this.requireHostOrAdmin(roomId, userId);
    this.requireReadyTrack(roomId, trackId);
    const now = Date.now();
    this.updateState(roomId, trackId, positionMs, false, now, null);
    const state = this.getState(roomId);
    this.broadcaster.broadcast(roomId, "PAUSE", state);
    return state;
  }

  next(roomId: string, userId: string, positionMs: number) {
    this.requireHostOrAdmin(roomId, userId);
    const current = this.getState(roomId);
    const currentTrack =
      current.trackId === null
        ? undefined
        : (this.database
            .prepare("SELECT * FROM tracks WHERE track_id = ? AND room_id = ?")
            .get(current.trackId, roomId) as TrackRow | undefined);
    const next = this.database
      .prepare(
        `SELECT * FROM tracks
         WHERE room_id = ? AND status = 'READY' AND order_index > ?
         ORDER BY order_index LIMIT 1`,
      )
      .get(roomId, currentTrack?.order_index ?? -1) as TrackRow | undefined;
    if (next === undefined) {
      throw new AppError(409, "NO_NEXT_TRACK", "No next ready track exists");
    }
    return this.schedule(roomId, "NEXT", next.track_id, positionMs);
  }

  getState(roomId: string) {
    const row = this.database
      .prepare("SELECT * FROM playback_states WHERE room_id = ?")
      .get(roomId) as PlaybackRow;
    return {
      trackId: row.track_id,
      positionMs: row.position_ms,
      isPlaying: row.is_playing === 1,
      serverTimeMs: row.server_time_ms,
      executeAtServerTimeMs: row.execute_at_server_time_ms,
    };
  }

  private schedule(
    roomId: string,
    type: "PLAY" | "SEEK" | "NEXT",
    trackId: string,
    positionMs: number,
    isPlaying = true,
  ) {
    const executeAt = Date.now() + this.leadTimeMs;
    this.updateState(roomId, trackId, positionMs, isPlaying, executeAt, executeAt);
    const state = this.getState(roomId);
    this.broadcaster.broadcast(roomId, type, state);
    return state;
  }

  private updateState(
    roomId: string,
    trackId: string,
    positionMs: number,
    isPlaying: boolean,
    serverTimeMs: number,
    executeAtServerTimeMs: number | null,
  ): void {
    this.database
      .prepare(
        `UPDATE playback_states SET
           track_id = ?, position_ms = ?, is_playing = ?, server_time_ms = ?,
           execute_at_server_time_ms = ?
         WHERE room_id = ?`,
      )
      .run(
        trackId,
        positionMs,
        isPlaying ? 1 : 0,
        serverTimeMs,
        executeAtServerTimeMs,
        roomId,
      );
  }

  private requireHostOrAdmin(roomId: string, userId: string): void {
    findActiveRoom(this.database, roomId);
    const member = findMember(this.database, roomId, userId);
    if (member.role !== "HOST" && member.role !== "ADMIN") {
      throw new AppError(403, "HOST_REQUIRED", "Only the host or an admin can control playback");
    }
  }

  private requireReadyTrack(roomId: string, trackId: string): void {
    const track = this.database
      .prepare("SELECT status FROM tracks WHERE room_id = ? AND track_id = ?")
      .get(roomId, trackId) as { status: string } | undefined;
    if (track?.status !== "READY") {
      throw new AppError(409, "TRACK_NOT_READY", "Track is not ready for playback");
    }
  }

  private broadcastSync(): void {
    const now = Date.now();
    const rows = this.database
      .prepare(
        `SELECT playback_states.* FROM playback_states
         JOIN rooms ON rooms.room_id = playback_states.room_id
         WHERE playback_states.is_playing = 1 AND rooms.status = 'ACTIVE'`,
      )
      .all() as PlaybackRow[];
    for (const row of rows) {
      if (row.execute_at_server_time_ms !== null && now < row.execute_at_server_time_ms) continue;
      const positionMs = Math.max(0, row.position_ms + now - row.server_time_ms);
      this.broadcaster.broadcast(row.room_id, "SYNC", {
        trackId: row.track_id,
        positionMs,
        serverTimeMs: now,
        isPlaying: true,
      });
    }
  }
}
