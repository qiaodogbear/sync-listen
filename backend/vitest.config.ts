import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Cold Fastify/plugin loading on Windows exceeds the default five seconds.
    testTimeout: 20_000,
    hookTimeout: 20_000,
    maxWorkers: 2,
  },
});
