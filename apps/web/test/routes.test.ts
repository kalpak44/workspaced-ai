import { describe, expect, it } from "vitest";
import { parseRoute, type Route, routePath } from "../src/lib/routes";
import { timeAgo } from "../src/lib/time";

describe("routes", () => {
  const routes: Route[] = [
    { name: "home" },
    { name: "workspace", workspaceId: "w1" },
    { name: "settings", workspaceId: "w1" },
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
