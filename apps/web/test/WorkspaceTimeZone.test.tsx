import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Workspace } from "../src/api/workspacesApi";
import { VariablesPanel } from "../src/components/organisms/VariablesPanel";
import { installFakeApi } from "./fakeApi";

const STARTER: Workspace = { id: "starter", name: "Starter", modelProvider: "openrouter", model: null, timeZone: "UTC", status: "READY", restartRequired: false };

describe("workspace time zone", () => {
  it("saves the zone, previews the time there and asks for a restart", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    const onChanged = vi.fn();
    const onRestart = vi.fn();
    const view = render(<VariablesPanel workspace={STARTER} restartDisabled={false} onRestart={onRestart} onChanged={onChanged} />);

    const zone = screen.getByRole("combobox", { name: /Time zone/ });
    expect(zone).toHaveValue("UTC");
    expect(screen.getByRole("button", { name: "Save time zone" })).toBeDisabled();
    expect(screen.queryByRole("status", { name: "Restart required" })).not.toBeInTheDocument();

    await user.selectOptions(zone, "Asia/Tokyo");
    // Either side of a minute boundary the clock may have ticked between render and assertion.
    const tokyo = (at: number) => new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Tokyo", hour: "2-digit", minute: "2-digit" }).format(at);
    const shown = screen.getByLabelText("Current time in the chosen zone").textContent;
    expect([tokyo(Date.now()), tokyo(Date.now() - 60_000)].some((time) => shown?.includes(time))).toBe(true);

    await user.click(screen.getByRole("button", { name: "Save time zone" }));
    expect(await screen.findByText("Time zone saved.")).toBeInTheDocument();
    expect(api.rows.find((w) => w.id === "starter")?.timeZone).toBe("Asia/Tokyo");
    expect(onChanged).toHaveBeenCalledWith({ timeZone: "Asia/Tokyo", restartRequired: true });

    // The page passes the change back down; the warning stays until the workspace restarts.
    view.rerender(<VariablesPanel workspace={{ ...STARTER, timeZone: "Asia/Tokyo", restartRequired: true }} restartDisabled={false} onRestart={onRestart} onChanged={onChanged} />);
    expect(screen.getByRole("status", { name: "Restart required" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Restart workspace" }));
    expect(onRestart).toHaveBeenCalled();
  });

  it("shows why the zone could not be saved", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<VariablesPanel workspace={STARTER} restartDisabled={false} onRestart={() => {}} onChanged={() => {}} />);

    await user.selectOptions(screen.getByRole("combobox", { name: /Time zone/ }), "Europe/Sofia");
    api.state.fail = 409;
    await user.click(screen.getByRole("button", { name: "Save time zone" }));

    expect(await screen.findByText("The workspace has no VM yet. Try again once it has started.")).toBeInTheDocument();
  });

  it("keeps a variable from shadowing the zone and asks for a restart once variables are saved", async () => {
    installFakeApi();
    const user = userEvent.setup();
    const onChanged = vi.fn();
    render(<VariablesPanel workspace={STARTER} restartDisabled={false} onRestart={() => {}} onChanged={onChanged} />);

    await user.click(await screen.findByRole("button", { name: "Add variable" }));
    await user.type(screen.getByLabelText("Name 1"), "USER_TIMEZONE");
    expect(screen.getByText("Set by the time zone above.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    await user.clear(screen.getByLabelText("Name 1"));
    await user.type(screen.getByLabelText("Name 1"), "LOG_LEVEL");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Saved. The workspace starts with them from its next restart.")).toBeInTheDocument();
    expect(onChanged).toHaveBeenCalledWith({ restartRequired: true });
  });
});
