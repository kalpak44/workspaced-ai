import { vi } from "vitest";
import type { Workspace } from "../src/workspacesApi";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/**
 * Stands in for the API: an in-memory workspace table behind the same routes, and a ready
 * health probe. `fail` makes every later workspace call answer with that status.
 */
export function installFakeApi(initial: Workspace[] = [{ id: "starter", name: "Starter" }]) {
  const rows = [...initial];
  const state = { fail: 0, nextId: 1 };

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
        const created = { id: `ws-${state.nextId++}`, name: body!.name };
        rows.push(created);
        return json(created, 201);
      }
      case "PUT":
        if (index < 0) return json({}, 404);
        rows[index] = { id: id!, name: body!.name };
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
  return { rows, state, fetchFn };
}
