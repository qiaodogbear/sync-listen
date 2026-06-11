import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";

describe("GET /health", () => {
  it("returns service status and server time", async () => {
    const app = await buildApp();

    const response = await app.inject({
      method: "GET",
      url: "/health",
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      status: "ok",
      serverTimeMs: expect.any(Number),
    });

    await app.close();
  });
});

