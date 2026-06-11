import type { DatabaseSync } from "node:sqlite";

import sensible from "@fastify/sensible";
import Fastify, { type FastifyError, type FastifyInstance } from "fastify";
import { ZodError } from "zod";

import { loadServerConfig } from "./config.js";
import { initializeDatabase } from "./db/database.js";
import { AppError } from "./errors.js";
import { registerRoomRoutes } from "./rooms/routes.js";

type BuildAppOptions = {
  database?: DatabaseSync;
};

export async function buildApp(
  options: BuildAppOptions = {},
): Promise<FastifyInstance> {
  const app = Fastify({
    logger: process.env.NODE_ENV !== "test",
  });
  const config = loadServerConfig();
  const database =
    options.database ??
    initializeDatabase({
      databasePath: config.databasePath,
      audioStoragePath: config.audioStoragePath,
      tempUploadPath: config.tempUploadPath,
    });

  await app.register(sensible);

  app.setErrorHandler((error: FastifyError, request, reply) => {
    request.log.error(error);
    const statusCode =
      error instanceof AppError
        ? error.statusCode
        : error instanceof ZodError
          ? 400
          : (error.statusCode ?? 500);
    const code =
      error instanceof AppError
        ? error.code
        : error instanceof ZodError
          ? "VALIDATION_ERROR"
          : (error.code ?? "INTERNAL_SERVER_ERROR");

    void reply.status(statusCode).send({
      error: {
        code,
        message: error.message,
        ...(error instanceof AppError && error.details !== undefined
          ? { details: error.details }
          : {}),
      },
    });
  });

  app.addHook("onClose", async () => {
    database.close();
  });

  app.get("/health", async () => ({
    status: "ok",
    serverTimeMs: Date.now(),
  }));

  await registerRoomRoutes(app, database);

  return app;
}
