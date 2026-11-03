import { beforeEach, describe, expect, it, vi } from "vitest";
import { checkApi, READINESS_URL } from "../src/apiStatus";

describe("checkApi", () => {
  let dot: HTMLSpanElement;
  let label: HTMLSpanElement;

  beforeEach(() => {
    dot = document.createElement("span");
    label = document.createElement("span");
  });

  it("asks the readiness probe, which includes the database", async () => {
    const fetchFn = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    await checkApi(dot, label, fetchFn);
    expect(fetchFn).toHaveBeenCalledWith(READINESS_URL);
  });

  it("shows ready when the API answers 200", async () => {
    await checkApi(dot, label, vi.fn().mockResolvedValue(new Response(null, { status: 200 })));
    expect(label.textContent).toBe("ready");
    expect(dot.className).toContain("bg-emerald-400");
  });

  it("shows the status when the API is up but not ready", async () => {
    await checkApi(dot, label, vi.fn().mockResolvedValue(new Response(null, { status: 503 })));
    expect(label.textContent).toBe("unavailable (503)");
    expect(dot.className).toContain("bg-amber-400");
  });

  it("shows unreachable when the request itself fails", async () => {
    await checkApi(dot, label, vi.fn().mockRejectedValue(new TypeError("network")));
    expect(label.textContent).toBe("unreachable");
    expect(dot.className).toContain("bg-rose-500");
  });
});
