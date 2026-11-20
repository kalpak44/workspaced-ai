import { beforeEach, describe, expect, it, vi } from "vitest";

beforeEach(() => { localStorage.clear(); vi.resetModules(); });

describe("tutorial progress after removing the database credential step", () => {
  it.each([[17, 17], [18, 18], [19, 18], [21, 20], [51, 50]])(
    "resumes old step %i at step %i without losing the workspace",
    async (previous, current) => {
      localStorage.setItem("workspaced.tour.v3", JSON.stringify({ step: previous, workspaceId: "trial", sessionId: "blog" }));
      await import("../src/lib/tour");
      expect(JSON.parse(localStorage.getItem("workspaced.tour.v4")!)).toEqual({ step: current, workspaceId: "trial", sessionId: "blog" });
      expect(localStorage.getItem("workspaced.tour.v3")).toBeFalsy();
    },
  );

  it("does not migrate invalid old progress", async () => {
    localStorage.setItem("workspaced.tour.v3", JSON.stringify({ step: 99, workspaceId: "trial" }));
    await import("../src/lib/tour");
    expect(localStorage.getItem("workspaced.tour.v4")).toBeNull();
  });
});
