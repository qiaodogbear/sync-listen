import type { DatabaseSync } from "node:sqlite";

import sensible from "@fastify/sensible";
import multipart from "@fastify/multipart";
import websocket from "@fastify/websocket";
import Fastify, { type FastifyError, type FastifyInstance } from "fastify";
import { ZodError } from "zod";

import { loadServerConfig } from "./config.js";
import { initializeDatabase } from "./db/database.js";
import { AppError } from "./errors.js";
import { PlaybackService } from "./playback/playbackService.js";
import { registerPlaybackRoutes } from "./playback/routes.js";
import { registerRoomRoutes } from "./rooms/routes.js";
import { PlaylistService } from "./tracks/playlistService.js";
import { registerTrackFileRoutes, type StorageConfig } from "./tracks/fileRoutes.js";
import { registerPlaylistRoutes } from "./tracks/routes.js";
import { RoomHub } from "./websocket/roomHub.js";

type BuildAppOptions = {
  database?: DatabaseSync;
  storage?: StorageConfig;
  playback?: {
    leadTimeMs: number;
    syncIntervalMs: number;
  };
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
  await app.register(websocket);
  await app.register(multipart);

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

  app.get("/health", async () => ({
    status: "ok",
    serverTimeMs: Date.now(),
  }));

  await registerRoomRoutes(app, database);
  const roomHub = new RoomHub(database);
  roomHub.register(app);
  const playlistService = new PlaylistService(database, roomHub);
  await registerPlaylistRoutes(app, playlistService);
  await registerTrackFileRoutes(app, database, playlistService, {
    audioStoragePath: options.storage?.audioStoragePath ?? config.audioStoragePath,
    tempUploadPath: options.storage?.tempUploadPath ?? config.tempUploadPath,
    ...(options.storage?.maxUploadBytes === undefined
      ? {}
      : { maxUploadBytes: options.storage.maxUploadBytes }),
  });
  const playbackService = new PlaybackService(
    database,
    roomHub,
    options.playback?.leadTimeMs,
    options.playback?.syncIntervalMs,
  );
  playbackService.start();
  await registerPlaybackRoutes(app, playbackService);
  app.addHook("onClose", async () => {
    playbackService.stop();
    roomHub.shutdown();
    database.close();
  });

  return app;
}
