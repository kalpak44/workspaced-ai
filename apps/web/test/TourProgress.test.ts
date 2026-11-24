import { beforeEach, describe, expect, it, vi } from "vitest";

beforeEach(() => { localStorage.clear(); vi.resetModules(); });

describe("progress for the simplified page tour", () => {
  it.each([2, 3, 4])("keeps an old v%i workspace and returns to the Store without reusing its recipe session", async version => {
    localStorage.setItem(`workspaced.tour.v${version}`, JSON.stringify({ step: 10, workspaceId: "trial", sessionId: "old-blog" }));
    const tour = await import("../src/lib/tour");
    expect(JSON.parse(localStorage.getItem("workspaced.tour.v6")!)).toEqual({ step: tour.TOUR_STEP.STORE, workspaceId: "trial" });
    expect(localStorage.getItem(`workspaced.tour.v${version}`)).toBeFalsy();
  });
  it("does not migrate invalid progress", async () => {
    localStorage.setItem("workspaced.tour.v4", JSON.stringify({ step: 99, workspaceId: "trial" }));
    await import("../src/lib/tour");
    expect(localStorage.getItem("workspaced.tour.v6")).toBeNull();
  });
  it.each([[13, 13], [14, 14], [16, 14], [20, 14], [21, 15], [24, 18]])(
    "migrates the previous page tour step %i to %i without losing the chat",
    async (previous, current) => {
      localStorage.setItem("workspaced.tour.v5", JSON.stringify({ step: previous, workspaceId: "trial", sessionId: "page" }));
      await import("../src/lib/tour");
      expect(JSON.parse(localStorage.getItem("workspaced.tour.v6")!)).toEqual({ step: current, workspaceId: "trial", sessionId: "page" });
      expect(localStorage.getItem("workspaced.tour.v5")).toBeFalsy();
    },
  );
});


describe("account tour requests", () => {
  it("sends an explicit paused flag for an active tour", async () => {
    const fetch = vi.fn(async (url: string) => url.endsWith("/csrf")
      ? new Response(JSON.stringify({ token: "csrf" }), { status: 200 })
      : new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetch);
    const account = await import("../src/api/accountApi");
    await account.saveTour({ step: 13, workspaceId: "demo", sessionId: "page" });
    expect(fetch).toHaveBeenLastCalledWith("/api/v1/account/tour", expect.objectContaining({
      method: "PUT", body: JSON.stringify({ step: 13, workspaceId: "demo", sessionId: "page", paused: false }),
    }));
  });

  it("saves steps in order when the previous request is slow", async () => {
    const tour = await import("../src/lib/tour");
    let release!: () => void;
    const first = new Promise<void>(resolve => { release = resolve; });
    const save = vi.fn().mockImplementationOnce(() => first).mockResolvedValue(undefined);
    tour.saveTourWith(save);
    tour.setTourProgress({ step: 1 });
    tour.setTourProgress({ step: 2 });
    await Promise.resolve();
    expect(save).toHaveBeenCalledTimes(1);
    release();
    await vi.waitFor(() => expect(save).toHaveBeenCalledTimes(2));
    expect(save.mock.calls.map(([progress]) => progress.step)).toEqual([1, 2]);
  });
});
