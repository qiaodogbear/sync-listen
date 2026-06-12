import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { afterEach, describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { initializeDatabase } from "../src/db/database.js";
import { PlaylistService } from "../src/tracks/playlistService.js";

const temporaryDirectories: string[] = [];

async function setup() {
  const root = await mkdtemp(join(tmpdir(), "sync-listen-playlist-"));
  temporaryDirectories.push(root);
  const database = initializeDatabase({
    databasePath: join(root, "data", "test.sqlite"),
    audioStoragePath: join(root, "data", "audio"),
    tempUploadPath: join(root, "data", "tmp"),
  });
  const app = await buildApp({ database });
  const created = await app.inject({
    method: "POST",
    url: "/api/rooms",
    payload: { name: "Room", userId: "host", displayName: "Alice" },
  });
  const { room } = created.json<{ room: { roomId: string } }>();
  return { app, database, roomId: room.roomId };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe("playlist", () => {
  it("allocates unique sequential order indexes", async () => {
    const { app, database, roomId } = await setup();
    const service = new PlaylistService(database);

    const tracks = await Promise.all(
      Array.from({ length: 5 }, (_, index) =>
        service.addReadyTrack({
          roomId,
          title: `Track ${index}`,
          artist: null,
          durationMs: 1000,
          fileName: `${index}.mp3`,
          fileSize: 10,
          fileHash: index.toString().padStart(64, "0"),
          storagePath: `${index}.mp3`,
          uploaderId: "host",
          uploaderName: "Alice",
        }),
      ),
    );

    expect(tracks.map((track) => track.orderIndex)).toEqual([0, 1, 2, 3, 4]);
    await app.close();
  });

  it("returns playlist sorted by server order", async () => {
    const { app, database, roomId } = await setup();
    const service = new PlaylistService(database);
    await service.addReadyTrack({
      roomId,
      title: "Track",
      artist: null,
      durationMs: 1000,
      fileName: "track.flac",
      fileSize: 10,
      fileHash: "a".repeat(64),
      storagePath: "track.flac",
      uploaderId: "host",
      uploaderName: "Alice",
    });

    const response = await app.inject({
      method: "GET",
      url: `/api/rooms/${roomId}/playlist`,
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      playlist: [{ title: "Track", orderIndex: 0, status: "READY" }],
    });
    await app.close();
  });
});
