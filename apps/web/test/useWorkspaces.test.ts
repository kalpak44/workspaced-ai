import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { uniqueName, useWorkspaces } from "../src/hooks/useWorkspaces";
import { FakeEventSource, installFakeApi, REJECTED_KEY } from "./fakeApi";

const request = (name: string, apiKey = "sk-or-1") => ({ name, modelProvider: "openrouter", apiKey });

describe("uniqueName", () => {
  it("keeps a free name", () => {
    expect(uniqueName("New Workspace", ["Starter"])).toBe("New Workspace");
  });

  it("numbers a taken one from 2", () => {
    expect(uniqueName("A", ["A", "A 2"])).toBe("A 3");
  });
});

async function loaded() {
  const hook = renderHook(() => useWorkspaces());
  await waitFor(() => expect(hook.result.current.loading).toBe(false));
  return hook.result;
}

describe("useWorkspaces", () => {
  it("suggests a name no workspace has yet", async () => {
    installFakeApi();
    const result = await loaded();
    expect(result.current.suggestName()).toBe("New Workspace");
    expect(result.current.suggestName("Starter")).toBe("Starter 2");
  });

  it("trims the name and reports whether the workspace was created", async () => {
    const api = installFakeApi();
    const result = await loaded();
    let created = "";
    await act(async () => {
      created = await result.current.create(request("  Docs  "));
    });
    expect(created).toBe("created");
    expect(api.rows.at(-1)?.name).toBe("Docs");

    // A rejected key is the form's to show, so it does not reach the banner.
    await act(async () => {
      created = await result.current.create(request("Nope", REJECTED_KEY));
    });
    expect(created).toBe("rejected");
    expect(result.current.error).toBeNull();

    api.state.fail = 500;
    await act(async () => {
      created = await result.current.create(request("Nope"));
    });
    expect(created).toBe("failed");
    expect(result.current.error).toBe("Could not create the workspace");
  });

  it("loads from the API and selects the first", async () => {
    installFakeApi([
      { id: "a", name: "A", modelProvider: "openrouter", model: null, status: "READY" },
      { id: "b", name: "B", modelProvider: "openrouter", model: null, status: "READY" },
    ]);
    const result = await loaded();
    expect(result.current.workspaces.map((w) => w.name)).toEqual(["A", "B"]);
    expect(result.current.selected?.id).toBe("a");
  });

  it("reports a failed load", async () => {
    installFakeApi().state.fail = 500;
    const result = await loaded();
    expect(result.current.error).toBe("Could not load workspaces");
    act(() => result.current.dismissError());
    expect(result.current.error).toBeNull();
  });

  it("does not call the API for a blank or unchanged name", async () => {
    const api = installFakeApi();
    const result = await loaded();
    act(() => result.current.rename("starter", "   "));
    act(() => result.current.rename("starter", " Starter "));
    expect(api.fetchFn).toHaveBeenCalledTimes(1);
  });

  it("keeps the list as it was when a change fails", async () => {
    const api = installFakeApi();
    const result = await loaded();
    api.state.fail = 503;

    act(() => void result.current.create(request("New Workspace")));
    await waitFor(() => expect(result.current.error).toBe("Could not create the workspace"));
    act(() => result.current.rename("starter", "X"));
    await waitFor(() => expect(result.current.error).toBe("Could not rename the workspace"));
    act(() => result.current.remove("starter"));
    await waitFor(() => expect(result.current.error).toBe("Could not delete the workspace"));

    expect(result.current.workspaces).toEqual([
      { id: "starter", name: "Starter", modelProvider: "openrouter", model: null, status: "READY" },
    ]);
  });

  it("selects the first remaining once the selected one is deleted", async () => {
    installFakeApi();
    const result = await loaded();
    act(() => void result.current.create(request("New Workspace")));
    await waitFor(() => expect(result.current.selected?.name).toBe("New Workspace"));
    act(() => result.current.remove(result.current.selected!.id));
    await waitFor(() => expect(result.current.selected?.name).toBe("Starter"));
  });

  it("selects nothing once the last workspace is gone", async () => {
    installFakeApi();
    const result = await loaded();
    act(() => result.current.remove("starter"));
    await waitFor(() => expect(result.current.selected).toBeNull());
  });

  it("subscribes to status events only once the list has loaded", async () => {
    installFakeApi();
    const hook = renderHook(() => useWorkspaces());
    expect(FakeEventSource.instances).toHaveLength(0);
    await waitFor(() => expect(hook.result.current.loading).toBe(false));
    expect(FakeEventSource.latest().url).toBe("/api/v1/workspaces/events");

    FakeEventSource.emit("starter", "FAILED");
    expect(hook.result.current.workspaces[0].status).toBe("FAILED");

    hook.unmount();
    expect(FakeEventSource.latest().closed).toBe(true);
  });

  it("applies a status that arrived before the create response", async () => {
    const api = installFakeApi();
    api.state.createStatus = "INITIALIZING";
    const result = await loaded();
    FakeEventSource.emit("ws-1", "READY");
    act(() => void result.current.create(request("New Workspace")));
    await waitFor(() => expect(result.current.selected?.id).toBe("ws-1"));
    expect(result.current.selected?.status).toBe("READY");
  });
});
