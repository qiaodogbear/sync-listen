import { loadServerConfig } from "../config.js";
import { initializeDatabase } from "./database.js";

const config = loadServerConfig();
const database = initializeDatabase({
  databasePath: config.databasePath,
  audioStoragePath: config.audioStoragePath,
  tempUploadPath: config.tempUploadPath,
});

database.close();
console.log(`Database initialized at ${config.databasePath}`);

