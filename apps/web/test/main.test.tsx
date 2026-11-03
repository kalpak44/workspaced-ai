import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

describe("main", () => {
  it("mounts the app into #root", async () => {
    vi.resetModules();
    vi.stubGlobal("fetch", vi.fn());
    document.body.innerHTML = '<div id="root"></div>';
    await import("../src/main");
    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent("Starter");
  });

  it("does nothing on a page without a root", async () => {
    vi.resetModules();
    document.body.innerHTML = "";
    await import("../src/main");
    expect(document.body).toBeEmptyDOMElement();
  });
});
