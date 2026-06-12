import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { mkdtemp, readdir, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import FormData from "form-data";
import { afterEach, describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { initializeDatabase } from "../src/db/database.js";

const temporaryDirectories: string[] = [];

async function setup() {
  const root = await mkdtemp(join(tmpdir(), "sync-listen-tracks-"));
  temporaryDirectories.push(root);
  const paths = {
    databasePath: join(root, "data", "test.sqlite"),
    audioStoragePath: join(root, "data", "audio"),
    tempUploadPath: join(root, "data", "tmp"),
  };
  const database = initializeDatabase(paths);
  const app = await buildApp({ database, storage: paths });
  const created = await app.inject({
    method: "POST",
    url: "/api/rooms",
    payload: { name: "Room", userId: "host", displayName: "Alice" },
  });
  const { room } = created.json<{ room: { roomId: string } }>();
  return { app, paths, roomId: room.roomId };
}

function uploadForm(content: Buffer, hash: string, fileName = "song.mp3") {
  const form = new FormData();
  form.append("title", "Song");
  form.append("artist", "");
  form.append("durationMs", "120000");
  form.append("fileHash", hash);
  form.append("uploaderId", "host");
  form.append("uploaderName", "Alice");
  form.append("file", content, { filename: fileName, contentType: "audio/mpeg" });
  return form;
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe("track upload and download", () => {
  it("uploads, verifies, and downloads the same bytes", async () => {
    const { app, roomId } = await setup();
    const content = Buffer.from("fake mp3 test content");
    const hash = createHash("sha256").update(content).digest("hex");
    const form = uploadForm(content, hash);

    const uploaded = await app.inject({
      method: "POST",
      url: `/api/rooms/${roomId}/tracks`,
      headers: form.getHeaders(),
      payload: form.getBuffer(),
    });
    const track = uploaded.json<{ track: { trackId: string; fileHash: string } }>().track;
    const downloaded = await app.inject({
      method: "GET",
      url: `/api/tracks/${track.trackId}/download`,
    });

    expect(uploaded.statusCode).toBe(201);
    expect(track.fileHash).toBe(hash);
    expect(downloaded.statusCode).toBe(200);
    expect(downloaded.rawPayload).toEqual(content);
    await app.close();
  });

  it("reuses one physical file for duplicate hashes", async () => {
    const { app, paths, roomId } = await setup();
    const content = Buffer.from("duplicate content");
    const hash = createHash("sha256").update(content).digest("hex");

    for (let index = 0; index < 2; index += 1) {
      const form = uploadForm(content, hash);
      const response = await app.inject({
        method: "POST",
        url: `/api/rooms/${roomId}/tracks`,
        headers: form.getHeaders(),
        payload: form.getBuffer(),
      });
      expect(response.statusCode).toBe(201);
    }

    expect((await readdir(paths.audioStoragePath)).length).toBe(1);
    await app.close();
  });

  it("rejects a mismatched hash and removes the temporary file", async () => {
    const { app, paths, roomId } = await setup();
    const form = uploadForm(Buffer.from("bad hash"), "a".repeat(64));

    const response = await app.inject({
      method: "POST",
      url: `/api/rooms/${roomId}/tracks`,
      headers: form.getHeaders(),
      payload: form.getBuffer(),
    });

    expect(response.statusCode).toBe(422);
    expect(response.json()).toMatchObject({ error: { code: "HASH_MISMATCH" } });
    expect(existsSync(paths.tempUploadPath)).toBe(true);
    expect(await readdir(paths.tempUploadPath)).toHaveLength(0);
    await app.close();
  });
});

