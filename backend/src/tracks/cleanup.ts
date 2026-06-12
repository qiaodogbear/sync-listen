import { readdir, rm, stat } from "node:fs/promises";
import { join } from "node:path";

export async function cleanupTemporaryFiles(
  directory: string,
  maxAgeMs: number,
): Promise<number> {
  const now = Date.now();
  let removed = 0;
  for (const name of await readdir(directory)) {
    const path = join(directory, name);
    const info = await stat(path);
    if (info.isFile() && now - info.mtimeMs > maxAgeMs) {
      await rm(path, { force: true });
      removed += 1;
    }
  }
  return removed;
}

