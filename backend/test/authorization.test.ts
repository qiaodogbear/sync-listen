import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { FastifyInstance } from "fastify";
import { afterEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { initializeDatabase } from "../src/db/database.js";
import { PlaylistService } from "../src/tracks/playlistService.js";
import { authHeaders } from "./client.js";

const resources: Array<{ app: FastifyInstance; root: string }> = [];
afterEach(async () => {
  for (const { app, root } of resources.splice(0)) {
    await app.close();
    await rm(root, { recursive: true, force: true });
  }
});

async function setup() {
  const root = await mkdtemp(join(tmpdir(), "sync-listen-auth-"));
  const paths = { databasePath: join(root, "test.sqlite"), audioStoragePath: join(root, "audio"), tempUploadPath: join(root, "tmp") };
  const db = initializeDatabase(paths);
  const app = await buildApp({ database: db, storage: paths });
  resources.push({ app, root });
  const response = await app.inject({ method: "POST", url: "/api/rooms", headers: authHeaders("host"), payload: { name: "Audit", userId: "host", displayName: "Alice" } });
  expect(response.statusCode).toBe(201);
  const created = response.json<{ room: { roomId: string; roomCode: string }; joinToken: string }>();
  const roomId = created.room.roomId;
  await app.inject({ method: "POST", url: "/api/rooms/join", headers: authHeaders("member"), payload: { roomCode: created.room.roomCode, userId: "member", displayName: "Bob" } });
  const service = new PlaylistService(db);
  const tracks = await Promise.all(["one", "two", "three"].map((name) => service.addReadyTrack({ roomId, title: name, artist: null, durationMs: 10_000, fileName: `${name}.mp3`, fileSize: 100, fileHash: name.padEnd(64, "0"), storagePath: join(root, name), uploaderId: "host", uploaderName: "Alice" })));
  return { app, roomId, created, tracks, service };
}

describe("device authentication and room permissions", () => {
  it("rejects missing credentials and public userId impersonation on REST and WebSocket", async () => {
    const { app, roomId, created, tracks } = await setup();
    expect((await app.inject({ method: "GET", url: `/api/rooms/${roomId}` })).statusCode).toBe(401);
    const forged = { ...authHeaders("member"), "x-user-id": "host" };
    expect((await app.inject({ method: "POST", url: `/api/rooms/${roomId}/playback/play`, headers: forged, payload: { userId: "host", trackId: tracks[0]!.trackId, positionMs: 0 } })).statusCode).toBe(403);
    expect((await app.inject({ method: "POST", url: "/api/rooms/join", headers: forged, payload: { roomCode: created.room.roomCode, userId: "host", displayName: "Impostor" } })).statusCode).toBe(403);
    await expect(app.injectWS(`/ws/rooms/${roomId}?token=${created.joinToken}&userId=host`, { headers: forged })).rejects.toThrow();
    const snapshot = await app.inject({ method: "GET", url: `/api/rooms/${roomId}`, headers: authHeaders() });
    expect(snapshot.body).not.toContain("credential");
  });

  it("checks the authenticated actor for roles, playlist mutations, and leaving", async () => {
    const { app, roomId, tracks } = await setup();
    for (const request of [
      { method: "PUT" as const, url: `/api/rooms/${roomId}/members/member/role`, payload: { role: "ADMIN" } },
      { method: "PUT" as const, url: `/api/rooms/${roomId}/playlist/reorder`, payload: { orderedTrackIds: tracks.map((track) => track.trackId) } },
      { method: "DELETE" as const, url: `/api/rooms/${roomId}/tracks/${tracks[0]!.trackId}` },
      { method: "DELETE" as const, url: `/api/rooms/${roomId}/members/host` },
    ]) expect((await app.inject({ ...request, headers: authHeaders("member") })).statusCode).toBe(403);
    expect((await app.inject({ method: "PUT", url: `/api/rooms/${roomId}/members/host/role`, headers: authHeaders(), payload: { role: "MEMBER" } })).statusCode).toBe(400);
  });

  it("preserves ADMIN on an authenticated rejoin and permits playback", async () => {
    const { app, roomId, created, tracks } = await setup();
    expect((await app.inject({ method: "PUT", url: `/api/rooms/${roomId}/members/member/role`, headers: authHeaders(), payload: { role: "ADMIN" } })).statusCode).toBe(200);
    const joined = await app.inject({ method: "POST", url: "/api/rooms/join", headers: authHeaders("member"), payload: { roomCode: created.room.roomCode, userId: "member", displayName: "Bob" } });
    expect(joined.json().member.role).toBe("ADMIN");
    expect((await app.inject({ method: "POST", url: `/api/rooms/${roomId}/playback/play`, headers: authHeaders("member"), payload: { userId: "member", trackId: tracks[0]!.trackId, positionMs: 0 } })).statusCode).toBe(200);
  });

  it("reorders atomically and rejects duplicate, missing and foreign track ids", async () => {
    const { app, roomId, tracks, service } = await setup();
    const ids = tracks.map((track) => track.trackId).reverse();
    for (const invalid of [ids.slice(1), [ids[0], ids[0], ids[2]], [ids[0], ids[1], "foreign"]]) {
      expect((await app.inject({ method: "PUT", url: `/api/rooms/${roomId}/playlist/reorder`, headers: authHeaders(), payload: { orderedTrackIds: invalid } })).statusCode).toBe(409);
      expect(service.getPlaylist(roomId).map((track) => track.trackId)).toEqual(tracks.map((track) => track.trackId));
    }
    expect((await app.inject({ method: "PUT", url: `/api/rooms/${roomId}/playlist/reorder`, headers: authHeaders(), payload: { orderedTrackIds: ids } })).statusCode).toBe(200);
    expect(service.getPlaylist(roomId).map((track) => track.trackId)).toEqual(ids);
    expect(service.getPlaylist(roomId).map((track) => track.orderIndex)).toEqual([0, 1, 2]);
  });

  it("keeps paused seeks paused and protects the selected track from deletion", async () => {
    const { app, roomId, tracks } = await setup();
    const trackId = tracks[0]!.trackId;
    const sought = await app.inject({ method: "POST", url: `/api/rooms/${roomId}/playback/seek`, headers: authHeaders(), payload: { userId: "host", trackId, positionMs: 400 } });
    expect(sought.json().state).toMatchObject({ isPlaying: false, positionMs: 400 });
    expect((await app.inject({ method: "DELETE", url: `/api/rooms/${roomId}/tracks/${trackId}`, headers: authHeaders() })).statusCode).toBe(409);
  });
});
