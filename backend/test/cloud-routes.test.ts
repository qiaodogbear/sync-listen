import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { CloudMusicClient, CloudMusicError } from "../src/cloud/music.js";
import { initializeDatabase } from "../src/db/database.js";
import { authHeaders } from "./client.js";

const resources: Array<{ app: FastifyInstance; root: string }> = [];
afterEach(async () => {
  for (const { app, root } of resources.splice(0)) {
    await app.close();
    await rm(root, { recursive: true, force: true });
  }
});

async function setup() {
  const root = await mkdtemp(join(tmpdir(), "sync-listen-cloud-"));
  const database = initializeDatabase({ databasePath: join(root, "test.sqlite"), audioStoragePath: join(root, "audio"), tempUploadPath: join(root, "tmp") });
  const cloud = new CloudMusicClient();
  const search = vi.spyOn(cloud, "search").mockResolvedValue([]);
  const resolve = vi.spyOn(cloud, "resolve");
  const app = await buildApp({ database, cloudMusic: cloud });
  resources.push({ app, root });
  const response = await app.inject({ method: "POST", url: "/api/rooms", headers: authHeaders(), payload: { name: "Room", userId: "host", displayName: "Host" } });
  return { app, database, roomId: response.json().room.roomId as string, roomCode: response.json().room.roomCode as string, search, resolve };
}

describe("cloud routes", () => {
  it("requires a protocol upgrade before a legacy member can access cloud tracks", async () => {
    const { app, database, roomId, roomCode } = await setup();
    const joinUrl = "/api/rooms/join";
    const legacy = { roomCode, userId: "member", displayName: "Member" };
    expect((await app.inject({ method: "POST", url: joinUrl, headers: authHeaders("member"), payload: legacy })).statusCode).toBe(200);
    database.prepare("UPDATE members SET protocol_version = 2 WHERE room_id = ? AND user_id = 'host'").run(roomId);
    database.prepare(`INSERT INTO tracks (track_id, room_id, title, artist, duration_ms, file_name, file_size, file_hash,
      storage_path, uploader_id, uploader_name, order_index, status, created_at, cloud_json)
      VALUES ('cloud', ?, 'Song', 'Artist', 0, '', 0, '', NULL, 'host', 'Host', 0, 'READY', 1, ?)`)
      .run(roomId, JSON.stringify({ track: { source: "qq", id: "song" }, format: "mp3", bitrate: 128 }));
    const blocked = await app.inject({ method: "GET", url: `/api/rooms/${roomId}`, headers: authHeaders("member") });
    expect(blocked.statusCode).toBe(409);
    expect(blocked.json().error.code).toBe("CLIENT_UPGRADE_REQUIRED");
    expect((await app.inject({ method: "POST", url: joinUrl, headers: authHeaders("member"), payload: legacy })).statusCode).toBe(409);
    expect((await app.inject({ method: "POST", url: joinUrl, headers: authHeaders("member"), payload: { ...legacy, protocolVersion: 2 } })).statusCode).toBe(200);
    expect((await app.inject({ method: "GET", url: `/api/rooms/${roomId}`, headers: authHeaders("member") })).statusCode).toBe(200);
  });

  it("adds stable cloud metadata for capable members without persisting an address", async () => {
    const { app, database, roomId, roomCode } = await setup();
    const reference = { track: { source: "qq", id: "song", title: "Song", artists: ["Artist"], mediaId: "media" }, format: "mp3", bitrate: 128 };
    const url = `/api/rooms/${roomId}/cloud/tracks`;
    expect((await app.inject({ method: "POST", url, headers: authHeaders(), payload: reference })).statusCode).toBe(409);
    database.prepare("UPDATE members SET protocol_version = 2 WHERE room_id = ? AND user_id = 'host'").run(roomId);
    const added = await app.inject({ method: "POST", url, headers: authHeaders(), payload: reference });
    expect(added.statusCode).toBe(201);
    expect(added.json().track.cloud.track.id).toBe("song");
    expect(added.body).not.toContain("https://");
    const play = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/playback/play`, headers: authHeaders(),
      payload: { userId: "host", trackId: added.json().track.trackId, positionMs: 0 } });
    expect(play.statusCode).toBe(409);
    expect(play.json().error.code).toBe("CLOUD_SYNC_UNAVAILABLE");
    const rejected = await app.inject({ method: "POST", url: "/api/rooms/join", headers: authHeaders("old"), payload: { roomCode, userId: "old", displayName: "Old" } });
    expect(rejected.statusCode).toBe(409);
    expect(rejected.json().error.code).toBe("CLIENT_UPGRADE_REQUIRED");
  });

  it("waits for online members and only lets the host force playback", async () => {
    const { app, database, roomId, roomCode } = await setup();
    database.prepare("UPDATE members SET protocol_version = 2, connected = 1 WHERE room_id = ? AND user_id = 'host'").run(roomId);
    await app.inject({ method: "POST", url: "/api/rooms/join", headers: authHeaders("member"),
      payload: { roomCode, userId: "member", displayName: "Member", protocolVersion: 2 } });
    database.prepare("UPDATE members SET connected = 1, role = 'ADMIN' WHERE room_id = ? AND user_id = 'member'").run(roomId);
    const reference = { track: { source: "qq", id: "song", title: "Song", artists: ["Artist"], mediaId: "media" }, format: "mp3", bitrate: 128 };
    const added = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/tracks`, headers: authHeaders(), payload: reference });
    const trackId = added.json().track.trackId as string;
    const preparing = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/prepare`, headers: authHeaders(), payload: { trackId, positionMs: 0 } });
    const generation = preparing.json().state.preparation.generation as number;
    expect(preparing.json().state.preparation.waiting).toEqual(["host", "member"]);
    const hostReady = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/ready`, headers: authHeaders(), payload: { trackId, generation, ready: true } });
    expect(hostReady.json().state.preparation.waiting).toEqual(["member"]);
    const denied = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/commit`, headers: authHeaders("member"), payload: { generation, force: true } });
    expect(denied.statusCode).toBe(403);
    const failed = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/ready`, headers: authHeaders("member"), payload: { trackId, generation, ready: false } });
    expect(failed.json().state.preparation.failed).toEqual(["member"]);
    const committed = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/commit`, headers: authHeaders(), payload: { generation, force: true } });
    expect(committed.json().state.isPlaying).toBe(true);
    expect(committed.json().state.executeAtServerTimeMs).toBeGreaterThan(0);
    const stale = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/ready`, headers: authHeaders("member"), payload: { trackId, generation, ready: true } });
    expect(stale.statusCode).toBe(409);
    const pausedSeek = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/prepare`, headers: authHeaders(),
      payload: { trackId, positionMs: 30_000, playAfterReady: false } });
    const seekGeneration = pausedSeek.json().state.preparation.generation as number;
    await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/ready`, headers: authHeaders(), payload: { trackId, generation: seekGeneration, ready: true } });
    const seekReady = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/ready`, headers: authHeaders("member"), payload: { trackId, generation: seekGeneration, ready: true } });
    expect(seekReady.json().state).toMatchObject({ isPlaying: false, positionMs: 30_000, preparation: { committed: true, playAfterReady: false } });
  });
  it("requires room membership before making an external request", async () => {
    const { app, roomId, search } = await setup();
    const url = `/api/rooms/${roomId}/cloud/search`;
    const payload = { source: "qq", keyword: "test" };
    expect((await app.inject({ method: "POST", url, payload })).statusCode).toBe(401);
    expect((await app.inject({ method: "POST", url, headers: authHeaders("stranger"), payload })).statusCode).toBe(403);
    expect(search).not.toHaveBeenCalled();
    const response = await app.inject({ method: "POST", url, headers: authHeaders(), payload });
    expect(response.statusCode).toBe(200);
    expect(response.headers["cache-control"]).toBe("no-store");
    expect(search).toHaveBeenCalledWith("qq", "test", 1);
  });

  it("validates inputs and reports upstream failures without a URL", async () => {
    const { app, roomId, search } = await setup();
    const url = `/api/rooms/${roomId}/cloud/search`;
    expect((await app.inject({ method: "POST", url, headers: authHeaders(), payload: { source: "other", keyword: "test" } })).statusCode).toBe(400);
    search.mockRejectedValueOnce(new CloudMusicError("Music service unavailable or timed out"));
    const response = await app.inject({ method: "POST", url, headers: authHeaders(), payload: { source: "qq", keyword: "test" } });
    expect(response.statusCode).toBe(502);
    expect(response.json().error.code).toBe("CLOUD_UNAVAILABLE");
    expect(response.body).not.toContain("http");
    search.mockRejectedValueOnce(new Error("https://example.test/audio?token=secret"));
    const unexpected = await app.inject({ method: "POST", url, headers: authHeaders(), payload: { source: "qq", keyword: "test" } });
    expect(unexpected.statusCode).toBe(502);
    expect(unexpected.body).not.toContain("secret");
  });

  it("returns temporary candidates only to a member and disables caching", async () => {
    const { app, roomId, resolve } = await setup();
    const track = { source: "qq" as const, id: "song", title: "Test", artists: ["Artist"], album: "", durationMs: null, mediaId: "media", songType: 0 };
    resolve.mockResolvedValueOnce({ track, candidates: [{ url: "https://example.test/audio?token=secret", bitrate: 128, format: "mp3" }], resolvedTitle: "", resolvedArtist: "", method: "qq-direct-vkey" });
    const response = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/resolve`, headers: authHeaders(), payload: track });
    expect(response.statusCode).toBe(200);
    expect(response.headers["cache-control"]).toBe("no-store");
    expect(response.json().track.id).toBe("song");
    expect(response.json().candidates).toHaveLength(1);
  });

  it("allows two authenticated members to search without mutating the existing playlist", async () => {
    const { app, roomId, roomCode, search } = await setup();
    const join = await app.inject({ method: "POST", url: "/api/rooms/join", headers: authHeaders("member"), payload: { roomCode, userId: "member", displayName: "Member" } });
    expect(join.statusCode).toBe(200);
    const results = await Promise.all(["host", "member"].map(user => app.inject({ method: "POST", url: `/api/rooms/${roomId}/cloud/search`, headers: authHeaders(user), payload: { source: "qq", keyword: "test" } })));
    expect(results.map(response => response.statusCode)).toEqual([200, 200]);
    expect(search).toHaveBeenCalledTimes(2);
    const playlist = await app.inject({ method: "GET", url: `/api/rooms/${roomId}/playlist`, headers: authHeaders() });
    expect(playlist.json().playlist).toEqual([]);
  });
});
