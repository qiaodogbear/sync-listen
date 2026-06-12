import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { afterEach, describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { initializeDatabase } from "../src/db/database.js";

const temporaryDirectories: string[] = [];

async function createTestApp() {
  const root = await mkdtemp(join(tmpdir(), "sync-listen-rooms-"));
  temporaryDirectories.push(root);
  const database = initializeDatabase({
    databasePath: join(root, "data", "test.sqlite"),
    audioStoragePath: join(root, "data", "audio"),
    tempUploadPath: join(root, "data", "tmp"),
  });

  return buildApp({ database });
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe("room API", () => {
  it("joins an active room using only its room code", async () => {
    const app = await buildApp();
    const created = await app.inject({
      method: "POST",
      url: "/api/rooms",
      payload: { name: "Room", userId: "host", displayName: "Alice" },
    });
    const { room } = created.json<{ room: { roomId: string; roomCode: string } }>();

    const joined = await app.inject({
      method: "POST",
      url: "/api/rooms/join",
      payload: { userId: "member", displayName: "Bob", roomCode: room.roomCode },
    });

    expect(joined.statusCode).toBe(200);
    expect(joined.json()).toMatchObject({
      room: { roomId: room.roomId },
      member: { userId: "member", role: "MEMBER" },
    });
    await app.close();
  });

  it("creates a room with the creator as host", async () => {
    const app = await createTestApp();

    const response = await app.inject({
      method: "POST",
      url: "/api/rooms",
      payload: {
        name: "Friday listening",
        userId: "user-host",
        displayName: "Alice",
      },
    });

    expect(response.statusCode).toBe(201);
    expect(response.json()).toMatchObject({
      room: {
        name: "Friday listening",
        hostUserId: "user-host",
        status: "ACTIVE",
      },
      member: {
        userId: "user-host",
        displayName: "Alice",
        role: "HOST",
      },
      joinToken: expect.any(String),
    });

    await app.close();
  });

  it("joins a room and returns an authoritative snapshot", async () => {
    const app = await createTestApp();
    const created = await app.inject({
      method: "POST",
      url: "/api/rooms",
      payload: {
        name: "Friday listening",
        userId: "user-host",
        displayName: "Alice",
      },
    });
    const { room } = created.json<{
      room: { roomId: string; roomCode: string };
    }>();

    const joined = await app.inject({
      method: "POST",
      url: `/api/rooms/${room.roomId}/join`,
      payload: {
        userId: "user-member",
        displayName: "Bob",
        roomCode: room.roomCode,
      },
    });
    const snapshot = await app.inject({
      method: "GET",
      url: `/api/rooms/${room.roomId}`,
    });

    expect(joined.statusCode).toBe(200);
    expect(joined.json()).toMatchObject({
      member: {
        userId: "user-member",
        role: "MEMBER",
      },
    });
    expect(snapshot.json<{ members: unknown[] }>().members).toHaveLength(2);

    await app.close();
  });

  it("rejects an invalid join token", async () => {
    const app = await createTestApp();
    const created = await app.inject({
      method: "POST",
      url: "/api/rooms",
      payload: {
        name: "Friday listening",
        userId: "user-host",
        displayName: "Alice",
      },
    });
    const { room } = created.json<{ room: { roomId: string } }>();

    const response = await app.inject({
      method: "POST",
      url: `/api/rooms/${room.roomId}/join`,
      payload: {
        userId: "user-member",
        displayName: "Bob",
        joinToken: "wrong-token",
      },
    });

    expect(response.statusCode).toBe(403);
    expect(response.json()).toMatchObject({
      error: { code: "INVALID_JOIN_TOKEN" },
    });

    await app.close();
  });

  it("returns a clear error for a missing room", async () => {
    const app = await createTestApp();

    const response = await app.inject({
      method: "GET",
      url: "/api/rooms/missing-room",
    });

    expect(response.statusCode).toBe(404);
    expect(response.json()).toMatchObject({
      error: { code: "ROOM_NOT_FOUND" },
    });

    await app.close();
  });

  it("closes the room when the host leaves", async () => {
    const app = await createTestApp();
    const created = await app.inject({
      method: "POST",
      url: "/api/rooms",
      payload: {
        name: "Friday listening",
        userId: "user-host",
        displayName: "Alice",
      },
    });
    const { room } = created.json<{ room: { roomId: string } }>();

    const leave = await app.inject({
      method: "DELETE",
      url: `/api/rooms/${room.roomId}/members/user-host`,
    });
    const snapshot = await app.inject({
      method: "GET",
      url: `/api/rooms/${room.roomId}`,
    });

    expect(leave.statusCode).toBe(204);
    expect(snapshot.statusCode).toBe(410);
    expect(snapshot.json()).toMatchObject({
      error: { code: "ROOM_CLOSED" },
    });

    await app.close();
  });
});
