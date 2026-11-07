import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import { deleteTask, listTasks, saveTask, type TaskContent, type TaskFile } from "../api/tasksApi";

export type { TaskContent, TaskFile } from "../api/tasksApi";
export { TASK_NAME } from "../api/tasksApi";

/**
 * A workspace's scheduled tasks, read from the workspace itself, where the agent may change them
 * too; so they need it running, and `stopped` says when it is not.
 */
export function useTasks(workspaceId: string) {
  const [tasks, setTasks] = useState<TaskFile[] | null>(null);
  const [stopped, setStopped] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(() => {
    const current = ++generation.current;
    setTasks(null);
    setStopped(false);
    setError(null);
    listTasks(workspaceId).then(
      (list) => current === generation.current && setTasks(list),
      (failure: unknown) => {
        if (current !== generation.current) return;
        if (failure instanceof ApiError && failure.status === 503) setStopped(true);
        else setError("Could not load the tasks.");
      },
    );
  }, [workspaceId]);

  useEffect(() => {
    load();
    return () => {
      generation.current++;
    };
  }, [load]);

  /** False when the API refused or could not be reached; the list is left as it was. */
  async function save(name: string, task: TaskContent): Promise<boolean> {
    try {
      const saved = await saveTask(workspaceId, name, task);
      setTasks((current) =>
        [...(current ?? []).filter((file) => file.name !== name), saved].sort((a, b) => a.name.localeCompare(b.name)),
      );
      return true;
    } catch {
      return false;
    }
  }

  async function remove(name: string): Promise<boolean> {
    try {
      await deleteTask(workspaceId, name);
      setTasks((current) => (current ?? []).filter((file) => file.name !== name));
      return true;
    } catch {
      return false;
    }
  }

  return { tasks, stopped, error, loading: tasks === null && !stopped && error === null, retry: load, save, remove };
}
