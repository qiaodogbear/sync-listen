import "dotenv/config";

import { z } from "zod";

const environmentSchema = z.object({
  HOST: z.string().default("0.0.0.0"),
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_PATH: z.string().default("./data/sync-listen.sqlite"),
  AUDIO_STORAGE_PATH: z.string().default("./data/audio"),
  TEMP_UPLOAD_PATH: z.string().default("./data/tmp"),
});

export type AppConfig = {
  host: string;
  port: number;
  databasePath: string;
  audioStoragePath: string;
  tempUploadPath: string;
};

export function loadServerConfig(
  environment: NodeJS.ProcessEnv = process.env,
): AppConfig {
  const parsed = environmentSchema.parse(environment);

  return {
    host: parsed.HOST,
    port: parsed.PORT,
    databasePath: parsed.DATABASE_PATH,
    audioStoragePath: parsed.AUDIO_STORAGE_PATH,
    tempUploadPath: parsed.TEMP_UPLOAD_PATH,
  };
}
