import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { uniqueName, useWorkspaces } from "../src/useWorkspaces";

describe("uniqueName", () => {
  it("keeps a free name", () => {
    expect(uniqueName("New Workspace", ["Starter"])).toBe("New Workspace");
  });

  it("numbers a taken one from 2", () => {
    expect(uniqueName("A", ["A", "A 2"])).toBe("A 3");
  });
});

describe("useWorkspaces", () => {
  it("starts with a Starter workspace selected", () => {
    const { result } = renderHook(() => useWorkspaces());
    expect(result.current.workspaces.map((w) => w.name)).toEqual(["Starter"]);
    expect(result.current.selected?.name).toBe("Starter");
  });

  it("restores what an earlier visit stored", () => {
    localStorage.setItem(
      "workspaced.workspaces",
      JSON.stringify([{ id: "a", name: "Kept" }, { broken: true }]),
    );
    const { result } = renderHook(() => useWorkspaces());
    expect(result.current.workspaces).toEqual([{ id: "a", name: "Kept" }]);
  });

  it("falls back to the starter set when storage holds garbage", () => {
    localStorage.setItem("workspaced.workspaces", "{not json");
    const { result } = renderHook(() => useWorkspaces());
    expect(result.current.workspaces.map((w) => w.name)).toEqual(["Starter"]);
  });

  it("still works when storage throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const { result } = renderHook(() => useWorkspaces());
    act(() => result.current.create());
    expect(result.current.workspaces).toHaveLength(2);
  });

  it("ignores a blank rename", () => {
    const { result } = renderHook(() => useWorkspaces());
    const id = result.current.workspaces[0].id;
    act(() => result.current.rename(id, "   "));
    expect(result.current.workspaces[0].name).toBe("Starter");
  });

  it("keeps the selection when an unselected workspace is deleted", () => {
    const { result } = renderHook(() => useWorkspaces());
    const starter = result.current.workspaces[0].id;
    act(() => result.current.create());
    act(() => result.current.remove(starter));
    expect(result.current.selected?.name).toBe("New Workspace");
  });

  it("selects nothing once the last workspace is gone", () => {
    const { result } = renderHook(() => useWorkspaces());
    act(() => result.current.remove(result.current.workspaces[0].id));
    expect(result.current.selected).toBeNull();
  });
});
