// opencode records a session's directory but never creates it, so this gives each
// session under ~/sessions its directory and removes it with the session.
import { mkdir, rm } from "node:fs/promises";
import { resolve, sep } from "node:path";

const ROOT = resolve(process.env.HOME, "sessions");

// A session pointed anywhere else is left alone, so a delete can never remove a path
// outside ~/sessions.
const managed = (directory) =>
  typeof directory === "string" && resolve(directory).startsWith(ROOT + sep);

export const SessionDirectories = async () => ({
  event: async ({ event }) => {
    const directory = event.properties?.info?.directory;
    if (!managed(directory)) {
      return;
    }
    if (event.type === "session.created") {
      await mkdir(directory, { recursive: true });
    } else if (event.type === "session.deleted") {
      await rm(directory, { recursive: true, force: true });
    }
  },
});
