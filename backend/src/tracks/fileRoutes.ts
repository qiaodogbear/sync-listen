import { createHash, randomUUID } from "node:crypto";
import { createReadStream, createWriteStream, existsSync } from "node:fs";
import { rename, rm, stat } from "node:fs/promises";
import { extname, join } from "node:path";
import type { DatabaseSync } from "node:sqlite";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";

import type { FastifyInstance } from "fastify";
import { z } from "zod";

import { AppError } from "../errors.js";
import type { TrackRow } from "./model.js";
import type { PlaylistService } from "./playlistService.js";

export type StorageConfig = {
  audioStoragePath: string;
  tempUploadPath: string;
  maxUploadBytes?: number;
};

const metadataSchema = z.object({
  title: z.string().trim().min(1),
  artist: z.string().transform((value) => value.trim() || null),
  durationMs: z.coerce.number().int().nonnegative(),
  fileHash: z.string().regex(/^[a-fA-F0-9]{64}$/).transform((value) => value.toLowerCase()),
  uploaderId: z.string().trim().min(1),
  uploaderName: z.string().trim().min(1),
});

export async function registerTrackFileRoutes(
  app: FastifyInstance,
  database: DatabaseSync,
  playlistService: PlaylistService,
  storage: StorageConfig,
): Promise<void> {
  app.post<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/tracks",
    async (request, reply) => {
      const fields: Record<string, string> = {};
      let tempPath: string | undefined;
      let fileName: string | undefined;
      let actualHash: string | undefined;

      try {
        for await (const part of request.parts({
          limits: { fileSize: storage.maxUploadBytes ?? 512 * 1024 * 1024, files: 1 },
        })) {
          if (part.type === "field") {
            fields[part.fieldname] = String(part.value);
            continue;
          }
          if (fileName !== undefined) {
            throw new AppError(400, "MULTIPLE_FILES", "Only one audio file is allowed");
          }
          fileName = part.filename;
          const extension = extname(fileName).toLowerCase();
          if (extension !== ".mp3" && extension !== ".flac") {
            throw new AppError(415, "UNSUPPORTED_AUDIO_TYPE", "Only MP3 and FLAC are supported");
          }
          tempPath = join(storage.tempUploadPath, `${randomUUID()}.upload`);
          const hash = createHash("sha256");
          const hashingStream = new Transform({
            transform(chunk: Buffer, _encoding, callback) {
              hash.update(chunk);
              callback(null, chunk);
            },
          });
          await pipeline(part.file, hashingStream, createWriteStream(tempPath));
          if (part.file.truncated) {
            throw new AppError(413, "FILE_TOO_LARGE", "Audio file exceeds the size limit");
          }
          actualHash = hash.digest("hex");
        }

        if (tempPath === undefined || fileName === undefined || actualHash === undefined) {
          throw new AppError(400, "FILE_REQUIRED", "Audio file is required");
        }
        const metadata = metadataSchema.parse(fields);
        if (metadata.fileHash !== actualHash) {
          throw new AppError(422, "HASH_MISMATCH", "Uploaded file hash does not match");
        }

        const existing = database
          .prepare(
            "SELECT storage_path FROM tracks WHERE file_hash = ? AND storage_path IS NOT NULL LIMIT 1",
          )
          .get(actualHash) as { storage_path: string } | undefined;
        const storagePath =
          existing?.storage_path ??
          join(storage.audioStoragePath, `${actualHash}${extname(fileName).toLowerCase()}`);
        if (existing === undefined && !existsSync(storagePath)) {
          await rename(tempPath, storagePath);
          tempPath = undefined;
        }
        const info = await stat(storagePath);
        const track = await playlistService.addReadyTrack({
          roomId: request.params.roomId,
          title: metadata.title,
          artist: metadata.artist,
          durationMs: metadata.durationMs,
          fileName,
          fileSize: info.size,
          fileHash: actualHash,
          storagePath,
          uploaderId: metadata.uploaderId,
          uploaderName: metadata.uploaderName,
        });
        return reply.status(201).send({ track, deduplicated: existing !== undefined });
      } finally {
        if (tempPath !== undefined) {
          await rm(tempPath, { force: true });
        }
      }
    },
  );

  app.get<{ Params: { trackId: string } }>(
    "/api/tracks/:trackId/download",
    async (request, reply) => {
      const track = database
        .prepare("SELECT * FROM tracks WHERE track_id = ?")
        .get(request.params.trackId) as TrackRow | undefined;
      if (track === undefined || track.storage_path === null || !existsSync(track.storage_path)) {
        throw new AppError(404, "TRACK_FILE_NOT_FOUND", "Track file does not exist");
      }
      const storagePath: string = track.storage_path;
      const stat = await import("node:fs/promises").then((f) => f.stat(storagePath));
      const fileSize = Number(stat.size);
      reply.header("accept-ranges", "bytes");
      reply.header("content-type", "application/octet-stream");
      reply.header(
        "content-disposition",
        `attachment; filename*=UTF-8''${encodeURIComponent(track.file_name)}`,
      );

      const rangeHeader = request.headers.range;
      if (rangeHeader) {
        const match = rangeHeader.match(/bytes=(\d+)-/);
        if (match && match[1]) {
          const start = parseInt(match[1], 10);
          const end = fileSize - 1;
          reply.header("content-range", `bytes ${start}-${end}/${fileSize}`);
          reply.header("content-length", end - start + 1);
          reply.code(206);
          return reply.send(createReadStream(storagePath, { start, end }));
        }
      }
      reply.header("content-length", fileSize);
      return reply.send(createReadStream(storagePath));
    },
  );
}
