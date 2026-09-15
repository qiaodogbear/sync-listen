import { createHash, timingSafeEqual } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { AppError } from "./errors.js";
import { findActiveRoom, findMember } from "./rooms/snapshot.js";

export function credentialHash(credential: string): string {
  return createHash("sha256").update(credential).digest("hex");
}

export function readIdentity(request: FastifyRequest) {
  const userId = request.headers["x-user-id"];
  const credential = request.headers.authorization?.match(/^Bearer ([a-f0-9]{64})$/)?.[1];
  if (typeof userId !== "string" || !userId.trim() || userId.length > 128 || !credential) {
    throw new AppError(401, "AUTH_REQUIRED", "A device identity and credential are required");
  }
  return { userId, credential };
}

export function requireActor(request: FastifyRequest, claimedUserId: string): void {
  if (readIdentity(request).userId !== claimedUserId) {
    throw new AppError(403, "IDENTITY_MISMATCH", "Cannot act as another member");
  }
}

export function verifyMember(database: DatabaseSync, roomId: string, userId: string, credential: string) {
  findActiveRoom(database, roomId);
  const member = findMember(database, roomId, userId);
  const stored = Buffer.from(member.credential_hash, "hex");
  const supplied = Buffer.from(credentialHash(credential), "hex");
  if (stored.length !== supplied.length || !timingSafeEqual(stored, supplied)) {
    throw new AppError(403, "INVALID_CREDENTIAL", "Member credential is invalid; re-create legacy rooms");
  }
  return member;
}

export function requireRole(database: DatabaseSync, request: FastifyRequest, roomId: string, roles: string[]) {
  const identity = readIdentity(request);
  const member = verifyMember(database, roomId, identity.userId, identity.credential);
  if (!roles.includes(member.role)) throw new AppError(403, "HOST_REQUIRED", "Insufficient room permissions");
  return member;
}

export function registerAuthentication(app: FastifyInstance, database: DatabaseSync): void {
  app.addHook("preValidation", async (request) => {
    const route = request.routeOptions.url ?? "";
    if (route === "/health" || route === "/api/time" || !route) return;
    const identity = readIdentity(request);
    if (route === "/api/rooms" || route.endsWith("/join")) return;
    const params = request.params as { roomId?: string; trackId?: string };
    let roomId = params.roomId;
    if (!roomId && params.trackId) {
      const track = database.prepare("SELECT room_id FROM tracks WHERE track_id = ?").get(params.trackId) as
        { room_id: string } | undefined;
      if (!track) throw new AppError(404, "TRACK_FILE_NOT_FOUND", "Track file does not exist");
      roomId = track.room_id;
    }
    if (roomId) verifyMember(database, roomId, identity.userId, identity.credential);
  });
}
