import { describe, expect, it } from "vitest";
import {
  ApiError,
  createWorkspace,
  deleteWorkspace,
  listWorkspaces,
  renameWorkspace,
} from "../src/api/workspacesApi";
import { installFakeApi } from "./fakeApi";

describe("workspacesApi", () => {
  it("sends each change to its route and returns what the API answered", async () => {
    const api = installFakeApi([]);
    const created = await createWorkspace({ name: "Docs", modelProvider: "openrouter", apiKey: "k" });
    const notes = { id: created.id, name: "Notes", modelProvider: "openrouter", model: null, status: "READY" };
    expect(await renameWorkspace(created.id, "Notes")).toEqual(notes);
    expect(await listWorkspaces()).toEqual([notes]);
    await deleteWorkspace(created.id);
    expect(api.rows).toEqual([]);

    const [, init] = api.fetchFn.mock.calls[0];
    expect(init?.method).toBe("POST");
    expect(new Headers(init?.headers).get("Content-Type")).toBe("application/json");
  });

  it("turns an error status into an ApiError carrying it", async () => {
    installFakeApi([]);
    await expect(deleteWorkspace("missing")).rejects.toEqual(new ApiError(404));
  });

  it("refuses an id that would escape the workspace route", async () => {
    const api = installFakeApi([]);
    await expect(renameWorkspace("../admin", "Notes")).rejects.toThrow("Invalid workspace id");
    await expect(deleteWorkspace("a/b")).rejects.toThrow("Invalid workspace id");
    expect(api.fetchFn).not.toHaveBeenCalled();
  });
});
