import { existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { mkdtemp, rm } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";

import { afterEach, describe, expect, it } from "vitest";

import { initializeDatabase } from "../src/db/database.js";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe("initializeDatabase", () => {
  it("creates runtime directories and the initial schema", async () => {
    const root = await mkdtemp(join(tmpdir(), "sync-listen-db-"));
    temporaryDirectories.push(root);
    const databasePath = join(root, "data", "sync-listen.sqlite");
    const audioStoragePath = join(root, "data", "audio");
    const tempUploadPath = join(root, "data", "tmp");

    const database = initializeDatabase({
      databasePath,
      audioStoragePath,
      tempUploadPath,
    });

    const tableRows = database
      .prepare(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
      )
      .all() as Array<{ name: string }>;
    database.close();

    expect(tableRows.map(({ name }) => name)).toEqual(
      expect.arrayContaining([
        "members",
        "playback_states",
        "rooms",
        "tracks",
      ]),
    );
    expect(existsSync(audioStoragePath)).toBe(true);
    expect(existsSync(tempUploadPath)).toBe(true);

    const reopened = new DatabaseSync(databasePath);
    expect(
      reopened
        .prepare("PRAGMA index_list('tracks')")
        .all()
        .map((row) => (row as { name: string }).name),
    ).toEqual(expect.arrayContaining(["idx_tracks_file_hash"]));
    reopened.close();
  });
});

