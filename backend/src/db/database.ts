import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";

export type DatabasePaths = {
  databasePath: string;
  audioStoragePath: string;
  tempUploadPath: string;
};

const initialMigration = `
  PRAGMA foreign_keys = ON;

  CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS rooms (
    room_id TEXT PRIMARY KEY,
    room_code TEXT NOT NULL UNIQUE,
    join_token TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    host_user_id TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    closed_at INTEGER
  );

  CREATE TABLE IF NOT EXISTS members (
    room_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL,
    connected INTEGER NOT NULL DEFAULT 0,
    joined_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    credential_hash TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (room_id, user_id),
    FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE
  );

  CREATE TABLE IF NOT EXISTS tracks (
    track_id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL,
    title TEXT NOT NULL,
    artist TEXT,
    duration_ms INTEGER NOT NULL,
    file_name TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    file_hash TEXT NOT NULL,
    storage_path TEXT,
    uploader_id TEXT NOT NULL,
    uploader_name TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
    UNIQUE (room_id, order_index)
  );

  CREATE INDEX IF NOT EXISTS idx_tracks_file_hash ON tracks(file_hash);
  CREATE INDEX IF NOT EXISTS idx_tracks_room_order ON tracks(room_id, order_index);

  CREATE TABLE IF NOT EXISTS playback_states (
    room_id TEXT PRIMARY KEY,
    track_id TEXT,
    position_ms INTEGER NOT NULL DEFAULT 0,
    is_playing INTEGER NOT NULL DEFAULT 0,
    server_time_ms INTEGER NOT NULL,
    execute_at_server_time_ms INTEGER,
    FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES tracks(track_id) ON DELETE SET NULL
  );
`;

export function initializeDatabase(paths: DatabasePaths): DatabaseSync {
  mkdirSync(dirname(paths.databasePath), { recursive: true });
  mkdirSync(paths.audioStoragePath, { recursive: true });
  mkdirSync(paths.tempUploadPath, { recursive: true });

  const database = new DatabaseSync(paths.databasePath);
  database.exec(initialMigration);
  const memberColumns = database.prepare("PRAGMA table_info(members)").all() as { name: string }[];
  if (!memberColumns.some((column) => column.name === "credential_hash")) {
    database.exec("ALTER TABLE members ADD COLUMN credential_hash TEXT NOT NULL DEFAULT ''");
  }
  database.exec("UPDATE members SET connected = 0");
  database
    .prepare(
      "INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (?, ?)",
    )
    .run(1, Date.now());

  return database;
}
