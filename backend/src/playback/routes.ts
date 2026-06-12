import type { FastifyInstance } from "fastify";
import { z } from "zod";

import type { PlaybackService } from "./playbackService.js";

const trackCommandSchema = z.object({
  userId: z.string().min(1),
  trackId: z.string().min(1),
  positionMs: z.number().int().nonnegative(),
});

const nextCommandSchema = z.object({
  userId: z.string().min(1),
  positionMs: z.number().int().nonnegative().default(0),
});

export async function registerPlaybackRoutes(
  app: FastifyInstance,
  playback: PlaybackService,
): Promise<void> {
  app.get("/api/time", async () => ({ serverTimeMs: Date.now() }));

  app.post<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/playback/play",
    async (request) => {
      const body = trackCommandSchema.parse(request.body);
      return { state: playback.play(request.params.roomId, body.userId, body.trackId, body.positionMs) };
    },
  );
  app.post<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/playback/pause",
    async (request) => {
      const body = trackCommandSchema.parse(request.body);
      return { state: playback.pause(request.params.roomId, body.userId, body.trackId, body.positionMs) };
    },
  );
  app.post<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/playback/seek",
    async (request) => {
      const body = trackCommandSchema.parse(request.body);
      return { state: playback.seek(request.params.roomId, body.userId, body.trackId, body.positionMs) };
    },
  );
  app.post<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/playback/next",
    async (request) => {
      const body = nextCommandSchema.parse(request.body);
      return { state: playback.next(request.params.roomId, body.userId, body.positionMs) };
    },
  );
}

