import { describe, expect, it } from "vitest";
import { parseRoute, type Route, routePath } from "../src/lib/routes";
import { formatPrice, formatTokens } from "../src/lib/format";
import { timeAgo } from "../src/lib/time";

describe("routes", () => {
  const routes: Route[] = [
    { name: "home" },
    { name: "workspace", workspaceId: "w1" },
    { name: "settings", workspaceId: "w1" },
    { name: "terminal", workspaceId: "w1" },
    { name: "session", workspaceId: "w1", sessionId: "s1" },
  ];

  it("reads back every path it writes", () => {
    for (const route of routes) {
      expect(parseRoute(routePath(route))).toEqual(route);
    }
  });

  it("tolerates a trailing slash and sends anything else home", () => {
    expect(parseRoute("/workspaces/w1/")).toEqual({ name: "workspace", workspaceId: "w1" });
    expect(parseRoute("/workspaces/../admin")).toEqual({ name: "home" });
    expect(parseRoute("/nope")).toEqual({ name: "home" });
    expect(parseRoute("/knowledge")).toEqual({ name: "home" });
  });
});

describe("timeAgo", () => {
  const now = Date.parse("2026-11-03T12:00:00Z");

  it("says how long ago, in the largest whole unit", () => {
    expect(timeAgo("2026-11-03T11:59:40Z", now)).toBe("just now");
    expect(timeAgo("2026-11-03T11:55:00Z", now)).toBe("5 minutes ago");
    expect(timeAgo("2026-11-03T09:00:00Z", now)).toBe("3 hours ago");
    expect(timeAgo("2026-11-02T12:00:00Z", now)).toBe("yesterday");
    expect(timeAgo("2026-10-20T12:00:00Z", now)).toBe("2 weeks ago");
  });
});

describe("format", () => {
  it("says token counts the way people do", () => {
    expect(formatTokens(1_000_000)).toBe("1M");
    expect(formatTokens(1_048_576)).toBe("1M");
    expect(formatTokens(128_000)).toBe("128K");
    expect(formatTokens(512)).toBe("512");
  });

  it("prices per million input and output tokens", () => {
    expect(formatPrice(2, 10)).toBe("$2 / $10");
    expect(formatPrice(0.15, 0.6)).toBe("$0.15 / $0.6");
    expect(formatPrice(0, 0)).toBe("free");
    expect(formatPrice(null, 1)).toBeNull();
  });
});
