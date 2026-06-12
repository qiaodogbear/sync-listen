import { mkdtemp, rm, stat, utimes, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { describe, expect, it } from "vitest";

import { cleanupTemporaryFiles } from "../src/tracks/cleanup.js";

describe("temporary file cleanup", () => {
  it("removes files older than the configured age", async () => {
    const root = await mkdtemp(join(tmpdir(), "sync-listen-cleanup-"));
    const oldFile = join(root, "old.tmp");
    const currentFile = join(root, "current.tmp");
    await writeFile(oldFile, "old");
    await writeFile(currentFile, "current");
    const old = new Date(Date.now() - 60_000);
    await utimes(oldFile, old, old);

    const removed = await cleanupTemporaryFiles(root, 30_000);

    expect(removed).toBe(1);
    await expect(stat(oldFile)).rejects.toThrow();
    await expect(stat(currentFile)).resolves.toBeDefined();
    await rm(root, { recursive: true, force: true });
  });
});

