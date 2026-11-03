import { useEffect, useState } from "react";
import { readStored, writeStored } from "./storage";

export interface Workspace {
  readonly id: string;
  readonly name: string;
}

// Kept in the browser until the API has workspaces of its own.
const KEY = "workspaced.workspaces";
export const DEFAULT_NAME = "New Workspace";

function isWorkspace(value: unknown): value is Workspace {
  const candidate = value as Partial<Workspace> | null;
  return typeof candidate?.id === "string" && typeof candidate.name === "string";
}

function initialWorkspaces(): Workspace[] {
  const raw = readStored(KEY);
  if (raw) {
    try {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        return parsed.filter(isWorkspace);
      }
    } catch {
      // A corrupt entry is replaced by the starter set below.
    }
  }
  return [{ id: crypto.randomUUID(), name: "Starter" }];
}

/** "New Workspace", then "New Workspace 2", 3… so two never share a name. */
export function uniqueName(base: string, taken: readonly string[]): string {
  if (!taken.includes(base)) {
    return base;
  }
  let n = 2;
  while (taken.includes(`${base} ${n}`)) {
    n += 1;
  }
  return `${base} ${n}`;
}

export function useWorkspaces() {
  const [workspaces, setWorkspaces] = useState<Workspace[]>(initialWorkspaces);
  const [selectedId, setSelectedId] = useState<string | null>(
    () => workspaces.at(-1)?.id ?? null,
  );

  useEffect(() => writeStored(KEY, JSON.stringify(workspaces)), [workspaces]);

  const create = (name: string = DEFAULT_NAME) => {
    const workspace = {
      id: crypto.randomUUID(),
      name: uniqueName(
        name,
        workspaces.map((w) => w.name),
      ),
    };
    setWorkspaces([...workspaces, workspace]);
    setSelectedId(workspace.id);
  };

  const rename = (id: string, name: string) => {
    const trimmed = name.trim();
    if (trimmed) {
      setWorkspaces(workspaces.map((w) => (w.id === id ? { ...w, name: trimmed } : w)));
    }
  };

  const remove = (id: string) => {
    const remaining = workspaces.filter((w) => w.id !== id);
    setWorkspaces(remaining);
    if (selectedId === id) {
      setSelectedId(remaining.at(0)?.id ?? null);
    }
  };

  const selected = workspaces.find((w) => w.id === selectedId) ?? null;

  return { workspaces, selected, select: setSelectedId, create, rename, remove };
}
