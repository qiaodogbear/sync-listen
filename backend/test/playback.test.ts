import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { afterEach, describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { initializeDatabase } from "../src/db/database.js";
import { PlaylistService } from "../src/tracks/playlistService.js";

const temporaryDirectories: string[] = [];

async function setup() {
  const root = await mkdtemp(join(tmpdir(), "sync-listen-playback-"));
  temporaryDirectories.push(root);
  const database = initializeDatabase({
    databasePath: join(root, "data", "test.sqlite"),
    audioStoragePath: join(root, "data", "audio"),
    tempUploadPath: join(root, "data", "tmp"),
  });
  const app = await buildApp({ database, playback: { leadTimeMs: 100, syncIntervalMs: 1000 } });
  const created = await app.inject({
    method: "POST",
    url: "/api/rooms",
    payload: { name: "Room", userId: "host", displayName: "Alice" },
  });
  const { room, joinToken } = created.json<{
    room: { roomId: string };
    joinToken: string;
  }>();
  const playlist = new PlaylistService(database);
  const first = await playlist.addReadyTrack({
    roomId: room.roomId,
    title: "First",
    artist: null,
    durationMs: 1000,
    fileName: "first.mp3",
    fileSize: 1,
    fileHash: "a".repeat(64),
    storagePath: "first.mp3",
    uploaderId: "host",
    uploaderName: "Alice",
  });
  const second = await playlist.addReadyTrack({
    roomId: room.roomId,
    title: "Second",
    artist: null,
    durationMs: 1000,
    fileName: "second.mp3",
    fileSize: 1,
    fileHash: "b".repeat(64),
    storagePath: "second.mp3",
    uploaderId: "host",
    uploaderName: "Alice",
  });
  return { app, roomId: room.roomId, joinToken, first, second };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe("playback API", () => {
  it("schedules play, seek, and next using server time", async () => {
    const { app, roomId, first, second } = await setup();
    const before = Date.now();

    const play = await app.inject({
      method: "POST",
      url: `/api/rooms/${roomId}/playback/play`,
      payload: { userId: "host", trackId: first.trackId, positionMs: 0 },
    });
    const seek = await app.inject({
      method: "POST",
      url: `/api/rooms/${roomId}/playback/seek`,
      payload: { userId: "host", trackId: first.trackId, positionMs: 500 },
    });
    const next = await app.inject({
      method: "POST",
      url: `/api/rooms/${roomId}/playback/next`,
      payload: { userId: "host", positionMs: 0 },
    });

    expect(play.json()).toMatchObject({
      state: { trackId: first.trackId, isPlaying: true, positionMs: 0 },
    });
    expect(play.json<{ state: { executeAtServerTimeMs: number } }>().state.executeAtServerTimeMs)
      .toBeGreaterThanOrEqual(before + 100);
    expect(seek.json()).toMatchObject({ state: { positionMs: 500 } });
    expect(next.json()).toMatchObject({ state: { trackId: second.trackId } });

    const snapshot = await app.inject({ method: "GET", url: `/api/rooms/${roomId}` });
    expect(snapshot.json()).toMatchObject({
      playbackState: { trackId: second.trackId, isPlaying: true },
    });
    await app.close();
  });

  it("rejects member playback control and exposes server time", async () => {
    const { app, roomId, joinToken, first } = await setup();
    await app.inject({
      method: "POST",
      url: `/api/rooms/${roomId}/join`,
      payload: { userId: "member", displayName: "Bob", joinToken },
    });

    const responses = await Promise.all([
      app.inject({
        method: "POST",
        url: `/api/rooms/${roomId}/playback/play`,
        payload: { userId: "member", trackId: first.trackId, positionMs: 100 },
      }),
      app.inject({
        method: "POST",
        url: `/api/rooms/${roomId}/playback/pause`,
        payload: { userId: "member", trackId: first.trackId, positionMs: 100 },
      }),
      app.inject({
        method: "POST",
        url: `/api/rooms/${roomId}/playback/seek`,
        payload: { userId: "member", trackId: first.trackId, positionMs: 100 },
      }),
      app.inject({
        method: "POST",
        url: `/api/rooms/${roomId}/playback/next`,
        payload: { userId: "member", positionMs: 0 },
      }),
    ]);
    const time = await app.inject({ method: "GET", url: "/api/time" });

    expect(responses.map((response) => response.statusCode)).toEqual([403, 403, 403, 403]);
    for (const response of responses) {
      expect(response.json()).toMatchObject({ error: { code: "HOST_REQUIRED" } });
    }
    expect(time.json()).toMatchObject({ serverTimeMs: expect.any(Number) });
    await app.close();
  });
});
