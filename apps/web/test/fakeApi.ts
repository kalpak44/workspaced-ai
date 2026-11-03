import { act } from "@testing-library/react";
import { vi } from "vitest";
import type { Workspace, WorkspaceStatus } from "../src/api/workspacesApi";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Stands in for the browser's EventSource; `emit` plays a server-sent status event. */
export class FakeEventSource {
  static readonly instances: FakeEventSource[] = [];
  readonly listeners = new Map<string, (event: MessageEvent) => void>();
  readonly url: string;
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners.set(type, listener);
  }

  close() {
    this.closed = true;
  }

  static latest(): FakeEventSource {
    const source = FakeEventSource.instances.at(-1);
    if (!source) throw new Error("no EventSource was opened");
    return source;
  }

  static emit(id: string, status: WorkspaceStatus) {
    const event = new MessageEvent("status", { data: JSON.stringify({ id, status }) });
    act(() => FakeEventSource.latest().listeners.get("status")?.(event));
  }
}

/**
 * Stands in for the API: an in-memory workspace table behind the same routes, a ready
 * health probe and an event stream. `fail` makes every later workspace call answer with
 * that status; `createStatus` is the status a new workspace starts in.
 */
export function installFakeApi(
  initial: Workspace[] = [{ id: "starter", name: "Starter", status: "READY" }],
) {
  const rows = [...initial];
  const state = { fail: 0, nextId: 1, createStatus: "READY" as WorkspaceStatus };
  FakeEventSource.instances.length = 0;

  const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith("/actuator/health/readiness")) {
      return new Response(null, { status: 200 });
    }
    if (state.fail) {
      return new Response(null, { status: state.fail });
    }
    const id = url.split("/api/v1/workspaces/")[1];
    const body = init?.body ? (JSON.parse(String(init.body)) as { name: string }) : undefined;
    const index = rows.findIndex((w) => w.id === id);
    switch (init?.method ?? "GET") {
      case "POST": {
        const created = { id: `ws-${state.nextId++}`, name: body!.name, status: state.createStatus };
        rows.push(created);
        return json(created, 201);
      }
      case "PUT":
        if (index < 0) return json({}, 404);
        rows[index] = { ...rows[index], name: body!.name };
        return json(rows[index]);
      case "DELETE":
        if (index < 0) return json({}, 404);
        rows.splice(index, 1);
        return new Response(null, { status: 204 });
      default:
        return json(rows);
    }
  });

  vi.stubGlobal("fetch", fetchFn);
  vi.stubGlobal("EventSource", FakeEventSource);
  return { rows, state, fetchFn };
}
