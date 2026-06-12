import type { FastifyInstance } from "fastify";

import type { PlaylistService } from "./playlistService.js";

export async function registerPlaylistRoutes(
  app: FastifyInstance,
  playlistService: PlaylistService,
): Promise<void> {
  app.get<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/playlist",
    async (request) => ({
      playlist: playlistService.getPlaylist(request.params.roomId),
    }),
  );
}
