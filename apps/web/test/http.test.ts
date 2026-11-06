import { afterEach, describe, expect, it, vi } from "vitest";
import { send } from "../src/api/http";

describe("send", () => {
  afterEach(() => {
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("echoes the CSRF cookie on a change but not on a read", async () => {
    document.cookie = "XSRF-TOKEN=abc%3D123; path=/";
    const fetchFn = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchFn);

    await send("/api/v1/account/password", { method: "PUT", body: "{}" });
    await send("/api/v1/account");

    const headers = (call: number) => (fetchFn.mock.calls[call] as unknown as [string, RequestInit])[1].headers as Record<string, string>;
    expect(headers(0)["X-XSRF-TOKEN"]).toBe("abc=123");
    expect(headers(1)["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("announces a 401 so the app can return to sign-in", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 401 })));
    const heard = vi.fn();
    globalThis.addEventListener("workspaced:unauthorized", heard);
    await expect(send("/api/v1/workspaces")).rejects.toThrow("401");
    globalThis.removeEventListener("workspaced:unauthorized", heard);
    expect(heard).toHaveBeenCalledOnce();
  });
});
