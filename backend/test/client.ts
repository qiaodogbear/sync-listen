import { createHash } from "node:crypto";
import type { FastifyInstance, InjectOptions, InjectWSOption } from "fastify";
import type { IncomingMessage } from "node:http";

const hosts = new WeakMap<FastifyInstance, string>();

export function authHeaders(userId = "host") {
  return { "x-user-id": userId, authorization: `Bearer ${createHash("sha256").update(`test-device:${userId}`).digest("hex")}` };
}

/** Existing functional scenarios use authenticated devices; security tests use raw inject. */
export async function inject(app: FastifyInstance, options: InjectOptions) {
  const body = options.payload as { userId?: string } | undefined;
  const userId = body?.userId ?? hosts.get(app) ?? "host";
  if (options.url === "/api/rooms" && body?.userId) hosts.set(app, body.userId);
  return app.inject({ ...options, headers: { ...authHeaders(userId), ...options.headers } });
}

export function injectWS(app: FastifyInstance, path: string, context?: Partial<IncomingMessage>, options?: InjectWSOption) {
  const userId = new URL(path, "http://test").searchParams.get("userId") ?? "host";
  return app.injectWS(path, { ...context, headers: { ...authHeaders(userId), ...context?.headers } }, options);
}
