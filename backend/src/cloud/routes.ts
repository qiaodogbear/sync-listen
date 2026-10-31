import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { CloudMusicError, type CloudMusicClient } from "./music.js";
import { readIdentity } from "../auth.js";
import type { PlaylistService } from "../tracks/playlistService.js";
import type { PlaybackService } from "../playback/playbackService.js";
import { cloudSourceSchema, cloudTrackSchema, cloudReferenceSchema } from "./schema.js";

const search = z.object({ source: cloudSourceSchema, keyword: z.string().trim().min(1).max(200), page: z.number().int().min(1).max(10000).default(1) });

export async function registerCloudRoutes(app: FastifyInstance, music: CloudMusicClient, playlist: PlaylistService, playback: PlaybackService): Promise<void> {
  app.post<{ Params: { roomId: string } }>("/api/rooms/:roomId/cloud/prepare", async request => {
    const input = z.object({ trackId: z.string().min(1), positionMs: z.number().int().nonnegative().default(0), playAfterReady: z.boolean().default(true) }).parse(request.body);
    return { state: playback.prepareCloud(request.params.roomId, readIdentity(request).userId, input.trackId, input.positionMs, input.playAfterReady) };
  });
  app.post<{ Params: { roomId: string } }>("/api/rooms/:roomId/cloud/ready", async request => {
    const input = z.object({ trackId: z.string().min(1), generation: z.number().int().safe(), ready: z.boolean() }).parse(request.body);
    return { state: playback.reportCloudReady(request.params.roomId, readIdentity(request).userId, input.trackId, input.generation, input.ready) };
  });
  app.post<{ Params: { roomId: string } }>("/api/rooms/:roomId/cloud/commit", async request => {
    const input = z.object({ generation: z.number().int().safe(), force: z.boolean().default(false) }).parse(request.body);
    return { state: playback.commitCloud(request.params.roomId, readIdentity(request).userId, input.generation, input.force) };
  });
  app.post<{ Params: { roomId: string } }>("/api/rooms/:roomId/cloud/tracks", async (request, reply) => {
    const input = cloudReferenceSchema.parse(request.body);
    const added = playlist.addCloudTrack(request.params.roomId, readIdentity(request).userId, input);
    return reply.code(201).send({ track: added });
  });
  app.post<{ Params: { roomId: string } }>("/api/rooms/:roomId/cloud/search", async (request, reply) => {
    const input = search.parse(request.body);
    try {
      return reply.header("Cache-Control", "no-store").send({ tracks: await music.search(input.source, input.keyword, input.page) });
    } catch (error) {
      if (error instanceof CloudMusicError) return reply.code(502).header("Cache-Control", "no-store").send({ error: { code: "CLOUD_UNAVAILABLE", message: error.message } });
      return reply.code(502).header("Cache-Control", "no-store").send({ error: { code: "CLOUD_UNAVAILABLE", message: "Invalid music service response" } });
    }
  });
  app.post<{ Params: { roomId: string } }>("/api/rooms/:roomId/cloud/resolve", async (request, reply) => {
    const input = cloudTrackSchema.parse(request.body);
    try {
      return reply.header("Cache-Control", "no-store").send(await music.resolve(input));
    } catch (error) {
      if (error instanceof CloudMusicError) return reply.code(502).header("Cache-Control", "no-store").send({ error: { code: "CLOUD_UNAVAILABLE", message: error.message } });
      return reply.code(502).header("Cache-Control", "no-store").send({ error: { code: "CLOUD_UNAVAILABLE", message: "Invalid music service response" } });
    }
  });
  app.post<{ Params: { roomId: string } }>("/api/rooms/:roomId/cloud/lyrics", async (request, reply) => {
    const input = cloudTrackSchema.parse(request.body);
    try {
      return reply.header("Cache-Control", "no-store").send(await music.lyrics(input));
    } catch (error) {
      if (error instanceof CloudMusicError) return reply.code(502).header("Cache-Control", "no-store").send({ error: { code: "CLOUD_UNAVAILABLE", message: error.message } });
      return reply.code(502).header("Cache-Control", "no-store").send({ error: { code: "CLOUD_UNAVAILABLE", message: "Invalid music service response" } });
    }
  });
}
