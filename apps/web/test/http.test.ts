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

    await send("/api/v1/account/profile", { method: "PUT", body: "{}" });
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


describe("bounded request retries", () => {
  it("retries a disconnected read and a temporary response, preserving the successful body", async () => {
    vi.useFakeTimers();
    try {
      const fetch = vi.fn().mockRejectedValueOnce(new TypeError("connection reset"))
        .mockResolvedValueOnce(json({ detail: "starting" }, 503)).mockResolvedValue(json({ ready: true }));
      vi.stubGlobal("fetch", fetch);
      const pending = send("/api/v1/workspaces");
      await vi.runAllTimersAsync();
      expect(await (await pending).json()).toEqual({ ready: true });
      expect(fetch).toHaveBeenCalledTimes(3);
    } finally { vi.useRealTimers(); }
  });

  it("retries an empty JSON read and stops after three attempts", async () => {
    vi.useFakeTimers();
    try {
      const fetch = vi.fn(async () => new Response("", { headers: { "Content-Type": "application/json" } }));
      vi.stubGlobal("fetch", fetch);
      const failed = expect(send("/api/v1/recipes")).rejects.toMatchObject({ status: 502, detail: expect.stringContaining("empty response") });
      await vi.runAllTimersAsync();
      await failed;
      expect(fetch).toHaveBeenCalledTimes(3);
    } finally { vi.useRealTimers(); }
  });

  it("recovers an empty JSON read without consuming the returned response", async () => {
    vi.useFakeTimers();
    try {
      const fetch = vi.fn().mockResolvedValueOnce(new Response("", { headers: { "Content-Type": "application/json" } }))
        .mockResolvedValue(new Response('{"items":[]}', { headers: { "Content-Type": "application/json" } }));
      vi.stubGlobal("fetch", fetch);
      const pending = send("/api/v1/recipes");
      await vi.runAllTimersAsync();
      expect(await (await pending).json()).toEqual({ items: [] });
      expect(fetch).toHaveBeenCalledTimes(2);
    } finally { vi.useRealTimers(); }
  });

  it("honours a short Retry-After and does not hammer a longer rate limit", async () => {
    vi.useFakeTimers();
    try {
      const fetch = vi.fn().mockResolvedValueOnce(new Response(null, { status: 429, headers: { "Retry-After": "1" } }))
        .mockResolvedValueOnce(json({ ok: true }));
      vi.stubGlobal("fetch", fetch);
      const pending = send("/api/v1/workspaces");
      await vi.advanceTimersByTimeAsync(999);
      expect(fetch).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(1);
      await pending;
      expect(fetch).toHaveBeenCalledTimes(2);
      fetch.mockClear().mockResolvedValue(new Response(null, { status: 429, headers: { "Retry-After": "120" } }));
      await expect(send("/api/v1/workspaces")).rejects.toMatchObject({ status: 429 });
      expect(fetch).toHaveBeenCalledOnce();
    } finally { vi.useRealTimers(); }
  });

  it("does not retry a mutation whose response was lost", async () => {
    const fetch = vi.fn(async (url: string) => {
      if (url.endsWith("/csrf")) return json({ token: "csrf" });
      throw new TypeError("response lost after accepting the message");
    });
    vi.stubGlobal("fetch", fetch);
    await expect(send("/api/v1/workspaces/demo/sessions/chat/messages", { method: "POST", body: '{"text":"hello"}' })).rejects.toThrow("response lost");
    expect(fetch.mock.calls.filter(([url]) => url.endsWith("/messages"))).toHaveLength(1);
  });

  it("retries explicitly idempotent tour saves with the same payload", async () => {
    vi.useFakeTimers();
    try {
      let changes = 0;
      const fetch = vi.fn(async (url: string) => url.endsWith("/csrf") ? json({ token: "csrf" })
        : new Response(null, { status: ++changes === 1 ? 503 : 204 }));
      vi.stubGlobal("fetch", fetch);
      const pending = send("/api/v1/account/tour", { method: "PUT", body: '{"step":9,"paused":false}' }, { retry: "idempotent" });
      await vi.runAllTimersAsync();
      expect((await pending).status).toBe(204);
      expect(changes).toBe(2);
      expect(fetch.mock.calls.filter(([url]) => url.endsWith("/tour"))).toHaveLength(2);
    } finally { vi.useRealTimers(); }
  });

  it("does not retry missing resources or an aborted read", async () => {
    const fetch = vi.fn(async () => new Response(null, { status: 404 }));
    vi.stubGlobal("fetch", fetch);
    await expect(send("/api/v1/missing")).rejects.toMatchObject({ status: 404 });
    expect(fetch).toHaveBeenCalledOnce();
    await expect(send("/api/v1/workspaces", { signal: AbortSignal.abort() })).rejects.toMatchObject({ name: "AbortError" });
    expect(fetch).toHaveBeenCalledOnce();
  });
});


it("retries a truncated JSON response instead of leaving its page empty", async () => {
  vi.useFakeTimers();
  try {
    const fetch = vi.fn().mockResolvedValueOnce(new Response('{"items":', { headers: { "Content-Type": "application/json" } }))
      .mockResolvedValue(new Response('{"items":[]}', { headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    const pending = send("/api/v1/recipes");
    await vi.runAllTimersAsync();
    expect(await (await pending).json()).toEqual({ items: [] });
    expect(fetch).toHaveBeenCalledTimes(2);
  } finally { vi.useRealTimers(); }
});
