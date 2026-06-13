import type { DatabaseSync } from "node:sqlite";

import type { FastifyInstance } from "fastify";
import type { WebSocket } from "ws";

import { AppError } from "../errors.js";
import {
  findActiveRoom,
  findMember,
  getRoomSnapshot,
  toMember,
} from "../rooms/snapshot.js";

type Client = {
  socket: WebSocket;
  userId: string;
};

export class RoomHub {
  private readonly rooms = new Map<string, Set<Client>>();
  private shuttingDown = false;

  constructor(private readonly database: DatabaseSync) {}

  register(app: FastifyInstance): void {
    app.get<{
      Params: { roomId: string };
      Querystring: { token?: string; userId?: string };
    }>(
      "/ws/rooms/:roomId",
      {
        websocket: true,
        preValidation: async (request) => {
          const { roomId } = request.params;
          const { token, userId } = request.query;
          if (token === undefined || userId === undefined) {
            throw new AppError(400, "MISSING_WEBSOCKET_AUTH", "token and userId are required");
          }
          const room = findActiveRoom(this.database, roomId);
          if (room.join_token !== token) {
            throw new AppError(403, "INVALID_JOIN_TOKEN", "Join token is invalid");
          }
          findMember(this.database, roomId, userId);
        },
      },
      (socket, request) => {
        const { roomId } = request.params;
        const userId = request.query.userId as string;
        const client = { socket, userId };
        const clients = this.rooms.get(roomId) ?? new Set<Client>();
        this.rooms.set(roomId, clients);
        clients.add(client);

        this.database
          .prepare(
            "UPDATE members SET connected = 1, last_seen_at = ? WHERE room_id = ? AND user_id = ?",
          )
          .run(Date.now(), roomId, userId);
        const member = findMember(this.database, roomId, userId);

        this.send(socket, "ROOM_JOINED", getRoomSnapshot(this.database, roomId));
        this.broadcast(roomId, "MEMBER_JOINED", { member: toMember(member) }, socket);

        const heartbeat = setInterval(() => {
          if (socket.readyState === socket.OPEN) {
            socket.ping();
          }
        }, 30_000);
        heartbeat.unref();

        socket.once("close", () => {
          clearInterval(heartbeat);
          clients.delete(client);
          if (clients.size === 0) {
            this.rooms.delete(roomId);
          }
          const userStillConnected = [...clients].some((remaining) => remaining.userId === userId);
          if (!this.shuttingDown && !userStillConnected) {
            this.database
              .prepare(
                "UPDATE members SET connected = 0, last_seen_at = ? WHERE room_id = ? AND user_id = ?",
              )
              .run(Date.now(), roomId, userId);
            this.broadcast(roomId, "MEMBER_LEFT", { userId });
          }
        });

        socket.on("error", (error) => {
          request.log.error({ error, roomId, userId }, "Room websocket error");
        });
      },
    );
  }

  shutdown(): void {
    this.shuttingDown = true;
    for (const clients of this.rooms.values()) {
      for (const client of clients) {
        client.socket.terminate();
      }
    }
    this.rooms.clear();
  }

  broadcast(
    roomId: string,
    type: string,
    payload: Record<string, unknown>,
    excludedSocket?: WebSocket,
  ): void {
    for (const client of this.rooms.get(roomId) ?? []) {
      if (client.socket !== excludedSocket) {
        this.send(client.socket, type, payload);
      }
    }
  }

  private send(socket: WebSocket, type: string, payload: unknown): void {
    if (socket.readyState === socket.OPEN) {
      socket.send(JSON.stringify({ type, payload, serverTimeMs: Date.now() }));
    }
  }
}
