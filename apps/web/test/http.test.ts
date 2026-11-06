import { describe, expect, it, vi } from "vitest";
import { send } from "../src/api/http";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const headersOf = (call: unknown[]) => (call[1] as RequestInit).headers as Record<string, string>;

describe("send", () => {
  it("fetches the CSRF token once and sends it with every change but no read", async () => {
    const fetchFn = vi.fn(async (url: string) =>
      url.endsWith("/auth/csrf") ? json({ token: "abc" }) : new Response(null, { status: 204 }),
    );
    vi.stubGlobal("fetch", fetchFn);

    await send("/api/v1/account/password", { method: "PUT", body: "{}" });
    await send("/api/v1/auth/logout", { method: "POST" });
    await send("/api/v1/account");

    const calls = fetchFn.mock.calls.filter(([url]) => !url.endsWith("/auth/csrf"));
    expect(fetchFn.mock.calls.filter(([url]) => url.endsWith("/auth/csrf"))).toHaveLength(1);
    expect(headersOf(calls[0])["X-XSRF-TOKEN"]).toBe("abc");
    expect(headersOf(calls[1])["X-XSRF-TOKEN"]).toBe("abc");
    expect(headersOf(calls[2])["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("refreshes a rejected CSRF token once and retries", async () => {
    let issued = 0;
    const fetchFn = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/auth/csrf")) return json({ token: `t${++issued}` });
      const token = (init?.headers as Record<string, string>)["X-XSRF-TOKEN"];
      return new Response(null, { status: token === "t2" ? 204 : 403 });
    });
    vi.stubGlobal("fetch", fetchFn);
    await expect(send("/api/v1/account/profile", { method: "PUT", body: "{}" })).resolves.toBeInstanceOf(Response);
    expect(issued).toBe(2);
  });

  it("fails the change when no CSRF token can be had, and tries again next time", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 503 })));
    await expect(send("/api/v1/account/profile", { method: "PUT", body: "{}" })).rejects.toThrow("503");
    const fetchFn = vi.fn(async (url: string) =>
      url.endsWith("/auth/csrf") ? json({ token: "abc" }) : new Response(null, { status: 204 }),
    );
    vi.stubGlobal("fetch", fetchFn);
    await send("/api/v1/account/profile", { method: "PUT", body: "{}" });
    expect(fetchFn).toHaveBeenCalledTimes(2);
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
