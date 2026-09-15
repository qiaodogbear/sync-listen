import { z } from "zod";

export const memberRoleSchema = z.enum(["HOST", "ADMIN", "MEMBER"]);
export type MemberRole = z.infer<typeof memberRoleSchema>;

export const roomStatusSchema = z.enum(["ACTIVE", "CLOSED"]);
export type RoomStatus = z.infer<typeof roomStatusSchema>;

export const trackStatusSchema = z.enum(["UPLOADING", "READY", "FAILED"]);
export type TrackStatus = z.infer<typeof trackStatusSchema>;

export const transferStatusSchema = z.enum([
  "QUEUED",
  "TRANSFERRING",
  "VERIFYING",
  "COMPLETED",
  "FAILED",
]);
export type TransferStatus = z.infer<typeof transferStatusSchema>;

export const memberSchema = z.object({
  userId: z.string().min(1),
  displayName: z.string().min(1),
  role: memberRoleSchema,
  connected: z.boolean(),
  joinedAt: z.number().int().nonnegative(),
});
export type Member = z.infer<typeof memberSchema>;

export const playbackStateSchema = z.object({
  trackId: z.string().min(1).nullable(),
  positionMs: z.number().int().nonnegative(),
  isPlaying: z.boolean(),
  serverTimeMs: z.number().int().nonnegative(),
  executeAtServerTimeMs: z.number().int().nonnegative().nullable(),
});
export type PlaybackState = z.infer<typeof playbackStateSchema>;

export const roomSchema = z.object({
  roomId: z.string().min(1),
  roomCode: z.string().min(1),
  name: z.string().min(1),
  hostUserId: z.string().min(1),
  status: roomStatusSchema,
  createdAt: z.number().int().nonnegative(),
});
export type Room = z.infer<typeof roomSchema>;

export const trackSchema = z.object({
  trackId: z.string().min(1),
  roomId: z.string().min(1),
  title: z.string().min(1),
  artist: z.string().min(1).nullable(),
  durationMs: z.number().int().nonnegative(),
  fileName: z.string().min(1),
  fileSize: z.number().int().nonnegative(),
  fileHash: z.string().regex(/^[a-fA-F0-9]{64}$/),
  uploaderId: z.string().min(1),
  uploaderName: z.string().min(1),
  orderIndex: z.number().int().nonnegative(),
  status: trackStatusSchema,
  createdAt: z.number().int().nonnegative(),
});
export type Track = z.infer<typeof trackSchema>;

export const errorResponseSchema = z.object({
  error: z.object({
    code: z.string().min(1),
    message: z.string().min(1),
    details: z.unknown().optional(),
  }),
});
export type ErrorResponse = z.infer<typeof errorResponseSchema>;

export const websocketEventTypeSchema = z.enum([
  "ROOM_JOINED",
  "MEMBER_JOINED",
  "MEMBER_LEFT",
  "ROLE_CHANGED",
  "TRACK_ADDED",
  "TRACK_UPLOAD_PROGRESS",
  "TRACK_READY",
  "TRACK_REMOVED",
  "PLAYLIST_UPDATED",
  "PLAY",
  "PAUSE",
  "SEEK",
  "NEXT",
  "SYNC",
  "DOWNLOAD_HINT",
  "ERROR",
]);
export type WebSocketEventType = z.infer<typeof websocketEventTypeSchema>;

export const websocketEnvelopeSchema = z.object({
  type: websocketEventTypeSchema,
  payload: z.record(z.string(), z.unknown()),
  serverTimeMs: z.number().int().nonnegative(),
});
export type WebSocketEnvelope = z.infer<typeof websocketEnvelopeSchema>;
