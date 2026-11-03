import { describe, expect, it, vi } from "vitest";
import { fetchApiStatus, READINESS_URL } from "../src/apiStatus";

describe("fetchApiStatus", () => {
  it("asks the readiness probe, which includes the database", async () => {
    const fetchFn = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    await fetchApiStatus(fetchFn);
    expect(fetchFn).toHaveBeenCalledWith(READINESS_URL);
  });

  it("is ready when the API answers 200", async () => {
    const fetchFn = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    expect(await fetchApiStatus(fetchFn)).toBe("ready");
  });

  it("is unavailable when the API is up but not ready", async () => {
    const fetchFn = vi.fn().mockResolvedValue(new Response(null, { status: 503 }));
    expect(await fetchApiStatus(fetchFn)).toBe("unavailable");
  });

  it("is unreachable when the request itself fails", async () => {
    const fetchFn = vi.fn().mockRejectedValue(new TypeError("network"));
    expect(await fetchApiStatus(fetchFn)).toBe("unreachable");
  });
});
