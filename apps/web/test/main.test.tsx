import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { installFakeApi } from "./fakeApi";

describe("main", () => {
  it("mounts the app into #root", async () => {
    vi.resetModules();
    installFakeApi();
    document.body.innerHTML = '<div id="root"></div>';
    await import("../src/main");
    expect(await screen.findByRole("button", { name: "Starter" })).toBeInTheDocument();
  });

  it("does nothing on a page without a root", async () => {
    vi.resetModules();
    document.body.innerHTML = "";
    await import("../src/main");
    expect(document.body).toBeEmptyDOMElement();
  });
});
