import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { PlaylistService } from "./playlistService.js";

const reorderBody = z.object({ orderedTrackIds: z.array(z.string().min(1)).min(1) });

export async function registerPlaylistRoutes(
  app: FastifyInstance,
  playlistService: PlaylistService,
): Promise<void> {
  app.get<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/playlist",
    async (request) => ({ playlist: playlistService.getPlaylist(request.params.roomId) }),
  );

  app.put<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/playlist/reorder",
    async (request, reply) => {
      const { orderedTrackIds } = reorderBody.parse(request.body);
      playlistService.reorderPlaylist(request.params.roomId, orderedTrackIds);
      return reply.code(200).send({ ok: true });
    },
  );

  app.delete<{ Params: { roomId: string; trackId: string } }>(
    "/api/rooms/:roomId/tracks/:trackId",
    async (request, reply) => {
      playlistService.removeTrack(request.params.roomId, request.params.trackId);
      return reply.code(204).send();
    },
  );
}
