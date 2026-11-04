import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SessionTtlPanel } from "../src/components/organisms/SessionTtlPanel";

describe("workspace session TTL settings", () => {
  it("saves preferences only on submit and isolates them by workspace", async () => {
    const user = userEvent.setup();
    const view = render(<SessionTtlPanel key="first" workspaceId="first" />);
    expect(screen.getByRole("button", { name: "Disabled" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("combobox")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save session settings" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Enabled" }));
    await user.selectOptions(screen.getByRole("combobox"), "24");
    expect(localStorage.getItem("workspaced:session-ttl:first")).toBeNull();
    await user.click(screen.getByRole("button", { name: "Save session settings" }));
    expect(screen.getByRole("status")).toHaveTextContent("Session settings saved");
    view.rerender(<SessionTtlPanel key="second" workspaceId="second" />);
    expect(screen.getByRole("button", { name: "Disabled" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("combobox")).toHaveValue("168");
    view.rerender(<SessionTtlPanel key="first" workspaceId="first" />);
    expect(screen.getByRole("button", { name: "Enabled" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("combobox")).toHaveValue("24");
    expect(screen.getByRole("button", { name: "Save session settings" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Disabled" }));
    await user.click(screen.getByRole("button", { name: "Save session settings" }));
    expect(JSON.parse(localStorage.getItem("workspaced:session-ttl:first")!)).toEqual({ sessionTtlEnabled: false, sessionTtlHours: 24 });
    expect(localStorage.getItem("workspaced:general-settings")).toBeNull();
  });

  it("defaults invalid preferences safely and reports storage failures", async () => {
    localStorage.setItem("workspaced:session-ttl:first", JSON.stringify({ sessionTtlEnabled: "true", sessionTtlHours: -1 }));
    const user = userEvent.setup();
    render(<SessionTtlPanel workspaceId="first" />);
    expect(screen.getByRole("button", { name: "Disabled" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("combobox")).toHaveValue("168");
    await user.click(screen.getByRole("button", { name: "Enabled" }));
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("Full"); });
    await user.click(screen.getByRole("button", { name: "Save session settings" }));
    expect(screen.getByRole("status")).toHaveTextContent("Could not save");
    expect(screen.getByRole("button", { name: "Save session settings" })).toBeEnabled();
  });
});
