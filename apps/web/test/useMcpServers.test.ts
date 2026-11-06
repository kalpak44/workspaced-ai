import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MCP_POLL_MS, useMcpServers } from "../src/hooks/useMcpServers";
import { installFakeApi } from "./fakeApi";

const listCalls = (api: ReturnType<typeof installFakeApi>) =>
  api.apiCalls().filter(([url, init]) => String(url).endsWith("/mcp-servers") && !init?.method).length;

describe("useMcpServers", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("asks the harness again while open, so a connecting server turns connected", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const api = installFakeApi();
    api.mcp.set("starter", [{ name: "github", status: "pending" }]);
    const { result, unmount } = renderHook(() => useMcpServers("starter"));
    await waitFor(() => expect(result.current.servers).toEqual([{ name: "github", status: "pending" }]));

    api.mcp.set("starter", [{ name: "github", status: "connected" }]);
    await act(() => vi.advanceTimersByTimeAsync(MCP_POLL_MS));
    expect(result.current.servers).toEqual([{ name: "github", status: "connected" }]);

    // Closed, it stops asking.
    unmount();
    const before = listCalls(api);
    await act(() => vi.advanceTimersByTimeAsync(MCP_POLL_MS * 3));
    expect(listCalls(api)).toBe(before);
  });

  it("does not ask while the page is hidden, and keeps the list when a poll fails", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const api = installFakeApi();
    api.mcp.set("starter", [{ name: "github", status: "connected" }]);
    const { result, unmount } = renderHook(() => useMcpServers("starter"));
    await waitFor(() => expect(result.current.loading).toBe(false));

    const hidden = vi.spyOn(document, "hidden", "get").mockReturnValue(true);
    const before = listCalls(api);
    await act(() => vi.advanceTimersByTimeAsync(MCP_POLL_MS * 2));
    expect(listCalls(api)).toBe(before);

    hidden.mockReturnValue(false);
    api.state.harnessDown = true;
    api.state.fail = 503;
    await act(() => vi.advanceTimersByTimeAsync(MCP_POLL_MS));
    expect(listCalls(api)).toBe(before + 1);
    expect(result.current.servers).toEqual([{ name: "github", status: "connected" }]);
    expect(result.current.error).toBeNull();
    unmount();
  });
});
