import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { describe, expect, it, vi } from "vitest";

import { initializeDatabase } from "../src/db/database.js";
import { PlaybackService } from "../src/playback/playbackService.js";

describe("periodic playback sync", () => {
  it("broadcasts sync for playing rooms", async () => {
    const root = await mkdtemp(join(tmpdir(), "sync-listen-sync-"));
    const database = initializeDatabase({
      databasePath: join(root, "data", "test.sqlite"),
      audioStoragePath: join(root, "data", "audio"),
      tempUploadPath: join(root, "data", "tmp"),
    });
    database.exec(`
      INSERT INTO rooms(room_id, room_code, join_token, name, host_user_id, status, created_at)
      VALUES ('room', 'ABC123', 'token', 'Room', 'host', 'ACTIVE', 1);
      INSERT INTO playback_states(room_id, track_id, position_ms, is_playing, server_time_ms)
      VALUES ('room', NULL, 100, 1, 1);
    `);
    const broadcast = vi.fn();
    const service = new PlaybackService(database, { broadcast }, 100, 20);

    service.start();
    await new Promise((resolve) => setTimeout(resolve, 50));
    service.stop();

    expect(broadcast).toHaveBeenCalledWith(
      "room",
      "SYNC",
      expect.objectContaining({ positionMs: expect.any(Number), isPlaying: true }),
    );
    database.close();
    await rm(root, { recursive: true, force: true });
  });
});

