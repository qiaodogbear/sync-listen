import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { WebSocket } from "ws";

import { afterEach, describe, expect, it } from "vitest";

import { authHeaders, inject, injectWS } from "./client.js";
import { buildApp } from "../src/app.js";
import { initializeDatabase } from "../src/db/database.js";

const temporaryDirectories: string[] = [];

async function createTestApp() {
  const root = await mkdtemp(join(tmpdir(), "sync-listen-ws-"));
  temporaryDirectories.push(root);
  const database = initializeDatabase({
    databasePath: join(root, "data", "test.sqlite"),
    audioStoragePath: join(root, "data", "audio"),
    tempUploadPath: join(root, "data", "tmp"),
  });
  const app = await buildApp({ database });
  await app.ready();
  return app;
}

function eventCollector() {
  const events: Array<{ type: string; payload: Record<string, unknown> }> = [];
  const waiters: Array<
    (event: { type: string; payload: Record<string, unknown> }) => void
  > = [];

  return {
    onInit(socket: WebSocket) {
      socket.on("message", (data) => {
        const event = JSON.parse(data.toString()) as {
          type: string;
          payload: Record<string, unknown>;
        };
        const waiter = waiters.shift();
        if (waiter === undefined) events.push(event);
        else waiter(event);
      });
    },
    next(): Promise<{ type: string; payload: Record<string, unknown> }> {
      const event = events.shift();
      if (event !== undefined) return Promise.resolve(event);
      return new Promise((resolve, reject) => {
        const timeout = setTimeout(
          () => reject(new Error("Timed out waiting for collected event")),
          3000,
        );
        waiters.push((collected) => {
          clearTimeout(timeout);
          resolve(collected);
        });
      });
    },
  };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe("room websocket", () => {
  it("broadcasts cloud preparation and commits only after both members report ready", async () => {
    const app = await createTestApp();
    const created = await inject(app, { method: "POST", url: "/api/rooms", payload: { name: "Room", userId: "host", displayName: "Alice", protocolVersion: 2 } });
    const { room, joinToken } = created.json<{ room: { roomId: string }; joinToken: string }>();
    await inject(app, { method: "POST", url: `/api/rooms/${room.roomId}/join`, payload: { userId: "member", displayName: "Bob", joinToken, protocolVersion: 2 } });
    const hostEvents = eventCollector();
    const host = await injectWS(app, `/ws/rooms/${room.roomId}?token=${joinToken}&userId=host`, undefined, { onInit: hostEvents.onInit });
    expect((await hostEvents.next()).type).toBe("ROOM_JOINED");
    const memberEvents = eventCollector();
    const member = await injectWS(app, `/ws/rooms/${room.roomId}?token=${joinToken}&userId=member`, undefined, { onInit: memberEvents.onInit });
    expect((await memberEvents.next()).type).toBe("ROOM_JOINED");
    expect((await hostEvents.next()).type).toBe("MEMBER_JOINED");
    try {
      const added = await inject(app, { method: "POST", url: `/api/rooms/${room.roomId}/cloud/tracks`, payload: {
        track: { source: "qq", id: "song", title: "Song", artists: ["Artist"], mediaId: "media" }, format: "mp3", bitrate: 128,
      } });
      const trackId = added.json().track.trackId as string;
      for (const events of [hostEvents, memberEvents]) {
        expect((await events.next()).type).toBe("TRACK_READY");
        expect((await events.next()).type).toBe("PLAYLIST_UPDATED");
      }
      const preparing = await inject(app, { method: "POST", url: `/api/rooms/${room.roomId}/cloud/prepare`, payload: { trackId, positionMs: 0 } });
      const generation = preparing.json().state.preparation.generation as number;
      expect((await hostEvents.next()).type).toBe("CLOUD_PREPARE");
      expect((await memberEvents.next()).type).toBe("CLOUD_PREPARE");
      const ready = { trackId, generation, ready: true };
      const first = await app.inject({ method: "POST", url: `/api/rooms/${room.roomId}/cloud/ready`, headers: authHeaders("host"), payload: ready });
      expect(first.json().state.isPlaying).toBe(false);
      expect((await hostEvents.next()).type).toBe("CLOUD_STATUS");
      expect((await memberEvents.next()).type).toBe("CLOUD_STATUS");
      const second = await app.inject({ method: "POST", url: `/api/rooms/${room.roomId}/cloud/ready`, headers: authHeaders("member"), payload: ready });
      expect(second.json().state.isPlaying).toBe(true);
      const hostPlay = await hostEvents.next();
      const memberPlay = await memberEvents.next();
      expect(hostPlay.type).toBe("PLAY");
      expect(memberPlay.payload.executeAtServerTimeMs).toBe(hostPlay.payload.executeAtServerTimeMs);
    } finally { host.terminate(); member.terminate(); await app.close(); }
  }, 10_000);
  it("sends a snapshot and broadcasts member connection changes", async () => {
    const app = await createTestApp();
    const created = await inject(app, {
      method: "POST",
      url: "/api/rooms",
      payload: { name: "Room", userId: "host", displayName: "Alice" },
    });
    const { room, joinToken } = created.json<{
      room: { roomId: string };
      joinToken: string;
    }>();
    await inject(app, {
      method: "POST",
      url: `/api/rooms/${room.roomId}/join`,
      payload: { userId: "member", displayName: "Bob", joinToken },
    });

    const hostEvents = eventCollector();
    const host = await injectWS(app,
      `/ws/rooms/${room.roomId}?token=${joinToken}&userId=host`,
      undefined,
      { onInit: hostEvents.onInit },
    );
    const hostSnapshot = await hostEvents.next();
    expect(hostSnapshot.type).toBe("ROOM_JOINED");

    const memberEvents = eventCollector();
    const member = await injectWS(app,
      `/ws/rooms/${room.roomId}?token=${joinToken}&userId=member`,
      undefined,
      { onInit: memberEvents.onInit },
    );
    const memberSnapshot = await memberEvents.next();
    const joined = await hostEvents.next();

    expect(memberSnapshot.type).toBe("ROOM_JOINED");
    expect(joined).toMatchObject({
      type: "MEMBER_JOINED",
      payload: { member: { userId: "member", connected: true } },
    });

    const leftPromise = hostEvents.next();
    member.terminate();
    const left = await leftPromise;
    expect(left).toMatchObject({
      type: "MEMBER_LEFT",
      payload: { userId: "member" },
    });

    host.terminate();
    await app.close();
  }, 10_000);

  it("rejects an invalid identity before websocket connection", async () => {
    const app = await createTestApp();
    const created = await inject(app, {
      method: "POST",
      url: "/api/rooms",
      payload: { name: "Room", userId: "host", displayName: "Alice" },
    });
    const { room } = created.json<{ room: { roomId: string } }>();

    await expect(
      injectWS(app, `/ws/rooms/${room.roomId}?token=wrong&userId=host`),
    ).rejects.toThrow();

    await app.close();
  });

  it("keeps a member online while another socket for the same user remains open", async () => {
    const app = await createTestApp();
    const created = await inject(app, {
      method: "POST",
      url: "/api/rooms",
      payload: { name: "Room", userId: "host", displayName: "Alice" },
    });
    const { room, joinToken } = created.json<{
      room: { roomId: string };
      joinToken: string;
    }>();
    await inject(app, {
      method: "POST",
      url: `/api/rooms/${room.roomId}/join`,
      payload: { userId: "member", displayName: "Bob", joinToken },
    });
    const first = await injectWS(app, `/ws/rooms/${room.roomId}?token=${joinToken}&userId=member`);
    const second = await injectWS(app, `/ws/rooms/${room.roomId}?token=${joinToken}&userId=member`);

    first.terminate();
    await new Promise((resolve) => setTimeout(resolve, 50));
    const snapshot = await inject(app, { method: "GET", url: `/api/rooms/${room.roomId}` });

    expect(snapshot.json()).toMatchObject({
      members: expect.arrayContaining([
        expect.objectContaining({ userId: "member", connected: true }),
      ]),
    });
    second.terminate();
    await app.close();
  });
});
