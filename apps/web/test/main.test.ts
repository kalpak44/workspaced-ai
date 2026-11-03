import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("main", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.body.innerHTML = "";
  });

  it("checks the API into the status line on load", async () => {
    document.body.innerHTML = '<span id="api-dot"></span><span id="api-status"></span>';
    await import("../src/main");
    await vi.waitFor(() =>
      expect(document.querySelector("#api-status")?.textContent).toBe("ready"),
    );
  });

  it("does nothing on a page without the status line", async () => {
    await import("../src/main");
    expect(fetch).not.toHaveBeenCalled();
  });
});
