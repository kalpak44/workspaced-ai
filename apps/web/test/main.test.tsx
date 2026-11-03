import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { installFakeApi } from "./fakeApi";

let unmount: (() => void) | undefined;

afterEach(() => {
  unmount?.();
  unmount = undefined;
});

describe("main", () => {
  it("mounts the app into #root", async () => {
    vi.resetModules();
    installFakeApi();
    document.body.innerHTML = '<div id="root"></div>';
    const { appRoot } = await import("../src/main");
    unmount = () => appRoot?.unmount();
    expect(await screen.findByRole("button", { name: "Starter" })).toBeInTheDocument();
  });

  it("does nothing on a page without a root", async () => {
    vi.resetModules();
    document.body.innerHTML = "";
    await import("../src/main");
    expect(document.body).toBeEmptyDOMElement();
  });
});
