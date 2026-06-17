import { randomBytes, randomUUID } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";

import type { FastifyInstance } from "fastify";
import { z } from "zod";

import { AppError } from "../errors.js";
import {
  findActiveRoom,
  findActiveRoomByCode,
  getRoomSnapshot,
  toRoom,
  type RoomRow,
} from "./snapshot.js";

const createRoomBodySchema = z.object({
  name: z.string().trim().min(1).max(80),
  userId: z.string().trim().min(1).max(128),
  displayName: z.string().trim().min(1).max(40),
});

const joinRoomBodySchema = z
  .object({
    userId: z.string().trim().min(1).max(128),
    displayName: z.string().trim().min(1).max(40),
    joinToken: z.string().trim().min(1).optional(),
    roomCode: z.string().trim().min(1).optional(),
  })
  .refine(({ joinToken, roomCode }) => joinToken !== undefined || roomCode !== undefined, {
    message: "joinToken or roomCode is required",
  });

const roleBodySchema = z.object({ role: z.enum(["ADMIN", "MEMBER"]) });

function generateRoomCode(): string {
  return randomBytes(4).toString("hex").slice(0, 6).toUpperCase();
}

function joinRoom(
  database: DatabaseSync,
  room: RoomRow,
  body: z.infer<typeof joinRoomBodySchema>,
) {
  if (
    body.joinToken !== room.join_token &&
    body.roomCode?.toUpperCase() !== room.room_code
  ) {
    throw new AppError(403, "INVALID_JOIN_TOKEN", "Join credentials are invalid");
  }

  const now = Date.now();
  database
    .prepare(
      `INSERT INTO members
       (room_id, user_id, display_name, role, connected, joined_at, last_seen_at)
       VALUES (?, ?, ?, 'MEMBER', 0, ?, ?)
       ON CONFLICT(room_id, user_id) DO UPDATE SET
         display_name = excluded.display_name,
         last_seen_at = excluded.last_seen_at`,
    )
    .run(room.room_id, body.userId, body.displayName, now, now);

  return {
    room: toRoom(room),
    member: {
      userId: body.userId,
      displayName: body.displayName,
      role: "MEMBER",
      connected: false,
      joinedAt: now,
    },
    joinToken: room.join_token,
  };
}

export async function registerRoomRoutes(
  app: FastifyInstance,
  database: DatabaseSync,
): Promise<void> {
  app.post("/api/rooms", async (request, reply) => {
    const body = createRoomBodySchema.parse(request.body);
    const roomId = randomUUID();
    const joinToken = randomUUID();
    const roomCode = generateRoomCode();
    const now = Date.now();

    database.exec("BEGIN IMMEDIATE");
    try {
      database
        .prepare(
          `INSERT INTO rooms
           (room_id, room_code, join_token, name, host_user_id, status, created_at)
           VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)`,
        )
        .run(roomId, roomCode, joinToken, body.name, body.userId, now);
      database
        .prepare(
          `INSERT INTO members
           (room_id, user_id, display_name, role, connected, joined_at, last_seen_at)
           VALUES (?, ?, ?, 'HOST', 0, ?, ?)`,
        )
        .run(roomId, body.userId, body.displayName, now, now);
      database
        .prepare(
          `INSERT INTO playback_states
           (room_id, track_id, position_ms, is_playing, server_time_ms, execute_at_server_time_ms)
           VALUES (?, NULL, 0, 0, ?, NULL)`,
        )
        .run(roomId, now);
      database.exec("COMMIT");
    } catch (error) {
      database.exec("ROLLBACK");
      throw error;
    }

    return reply.status(201).send({
      room: {
        roomId,
        roomCode,
        name: body.name,
        hostUserId: body.userId,
        status: "ACTIVE",
        createdAt: now,
      },
      member: {
        userId: body.userId,
        displayName: body.displayName,
        role: "HOST",
        connected: false,
        joinedAt: now,
      },
      joinToken,
    });
  });

  app.post<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId/join",
    async (request) => {
      const body = joinRoomBodySchema.parse(request.body);
      const room = findActiveRoom(database, request.params.roomId);
      return joinRoom(database, room, body);
    },
  );

  app.post("/api/rooms/join", async (request) => {
    const body = joinRoomBodySchema.parse(request.body);
    if (body.roomCode === undefined) {
      throw new AppError(400, "ROOM_CODE_REQUIRED", "Room code is required");
    }
    return joinRoom(database, findActiveRoomByCode(database, body.roomCode), body);
  });

  app.get<{ Params: { roomId: string } }>(
    "/api/rooms/:roomId",
    async (request) => {
      return getRoomSnapshot(database, request.params.roomId);
    },
  );

  app.delete<{ Params: { roomId: string; userId: string } }>(
    "/api/rooms/:roomId/members/:userId",
    async (request, reply) => {
      const room = findActiveRoom(database, request.params.roomId);
      if (room.host_user_id === request.params.userId) {
        database
          .prepare(
            "UPDATE rooms SET status = 'CLOSED', closed_at = ? WHERE room_id = ?",
          )
          .run(Date.now(), room.room_id);
      } else {
        database
          .prepare("DELETE FROM members WHERE room_id = ? AND user_id = ?")
          .run(room.room_id, request.params.userId);
      }

      return reply.status(204).send();
    },
  );

  app.put<{ Params: { roomId: string; userId: string } }>(
    "/api/rooms/:roomId/members/:userId/role",
    async (request, reply) => {
      const body = roleBodySchema.parse(request.body);
      const room = findActiveRoom(database, request.params.roomId);
      database.prepare("UPDATE members SET role = ? WHERE room_id = ? AND user_id = ?")
        .run(body.role, room.room_id, request.params.userId);
      return reply.code(200).send({ userId: request.params.userId, role: body.role });
    },
  );
}
