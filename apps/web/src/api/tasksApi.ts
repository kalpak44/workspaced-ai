import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

/**
 * A file in the workspace that has the agent do something on a schedule: when the cron expression
 * matches, in its time zone, a new session is started with the prompt.
 */
export interface TaskFile {
  /** The file's name, without ".md"; also the title of the sessions it starts. */
  readonly name: string;
  /** Five fields: minute, hour, day of month, month, day of week. */
  readonly cron: string;
  /** An IANA zone, for example Europe/Sofia. */
  readonly timeZone: string;
  readonly prompt: string;
}

export type TaskContent = Omit<TaskFile, "name">;

// The same rule as instruction names, so a name never becomes a path.
export const TASK_NAME = /^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$/;

function fileUrl(workspaceId: string, name: string): string {
  if (!TASK_NAME.test(name)) {
    throw new Error("Invalid task name");
  }
  return `${workspaceUrl(workspaceId)}/tasks/${name}`;
}

export async function listTasks(workspaceId: string): Promise<TaskFile[]> {
  return (await send(`${workspaceUrl(workspaceId)}/tasks`)).json();
}

export async function saveTask(workspaceId: string, name: string, task: TaskContent): Promise<TaskFile> {
  const body = JSON.stringify(task);
  return (await send(fileUrl(workspaceId, name), { method: "PUT", body })).json();
}

export async function deleteTask(workspaceId: string, name: string): Promise<void> {
  await send(fileUrl(workspaceId, name), { method: "DELETE" });
}
