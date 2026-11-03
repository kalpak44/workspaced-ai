import { describe, expect, it } from "vitest";
import {
  ApiError,
  createWorkspace,
  deleteWorkspace,
  listWorkspaces,
  renameWorkspace,
} from "../src/workspacesApi";
import { installFakeApi } from "./fakeApi";

describe("workspacesApi", () => {
  it("sends each change to its route and returns what the API answered", async () => {
    const api = installFakeApi([]);
    const created = await createWorkspace("Docs");
    expect(await renameWorkspace(created.id, "Notes")).toEqual({ id: created.id, name: "Notes" });
    expect(await listWorkspaces()).toEqual([{ id: created.id, name: "Notes" }]);
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
});
