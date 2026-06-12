import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { initializeDatabase } from "../src/db/database.js";

describe("GET /health", () => {
  it("returns service status and server time", async () => {
    const root = await mkdtemp(join(tmpdir(), "sync-listen-health-"));
    const database = initializeDatabase({
      databasePath: join(root, "data", "test.sqlite"),
      audioStoragePath: join(root, "data", "audio"),
      tempUploadPath: join(root, "data", "tmp"),
    });
    const app = await buildApp({ database });

    try {
      const response = await app.inject({
        method: "GET",
        url: "/health",
      });

      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual({
        status: "ok",
        serverTimeMs: expect.any(Number),
      });
    } finally {
      await app.close();
      await rm(root, { recursive: true, force: true });
    }
  });
});
