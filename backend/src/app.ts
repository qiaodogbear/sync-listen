import sensible from "@fastify/sensible";
import Fastify, { type FastifyError, type FastifyInstance } from "fastify";

export async function buildApp(): Promise<FastifyInstance> {
  const app = Fastify({
    logger: process.env.NODE_ENV !== "test",
  });

  await app.register(sensible);

  app.setErrorHandler((error: FastifyError, request, reply) => {
    request.log.error(error);
    void reply.status(error.statusCode ?? 500).send({
      error: {
        code: error.code ?? "INTERNAL_SERVER_ERROR",
        message: error.message,
      },
    });
  });

  app.get("/health", async () => ({
    status: "ok",
    serverTimeMs: Date.now(),
  }));

  return app;
}
