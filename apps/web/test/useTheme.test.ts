import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useTheme } from "../src/hooks/useTheme";

function stubMedia(dark: boolean) {
  const listeners = new Set<() => void>();
  const query = {
    matches: dark,
    addEventListener: (_: string, fn: () => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: () => void) => listeners.delete(fn),
  };
  vi.stubGlobal("matchMedia", () => query);
  return { query, listeners };
}

const isDark = () => document.documentElement.classList.contains("dark");

describe("useTheme", () => {
  it("defaults to system and follows the OS", () => {
    const media = stubMedia(false);
    const { result } = renderHook(() => useTheme());
    expect(result.current[0]).toBe("system");
    expect(isDark()).toBe(false);

    media.query.matches = true;
    act(() => media.listeners.forEach((fn) => fn()));
    expect(isDark()).toBe(true);
  });

  it("stops following the OS once a theme is chosen, and remembers it", () => {
    const media = stubMedia(true);
    const { result } = renderHook(() => useTheme());
    act(() => result.current[1]("light"));
    expect(isDark()).toBe(false);
    expect(media.listeners.size).toBe(0);
    expect(localStorage.getItem("workspaced.theme")).toBe("light");
  });

  it("restores a stored choice", () => {
    stubMedia(false);
    localStorage.setItem("workspaced.theme", "dark");
    const { result } = renderHook(() => useTheme());
    expect(result.current[0]).toBe("dark");
    expect(isDark()).toBe(true);
  });

  it("still works when storage throws", () => {
    stubMedia(false);
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const { result } = renderHook(() => useTheme());
    act(() => result.current[1]("dark"));
    expect(isDark()).toBe(true);
  });

  it("treats a browser without matchMedia as dark", () => {
    vi.stubGlobal("matchMedia", undefined);
    renderHook(() => useTheme());
    expect(isDark()).toBe(true);
  });
});
