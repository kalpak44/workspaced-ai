import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { UserMenu } from "../src/components/organisms/UserMenu";
import { App } from "../src/App";
import { resetAccount } from "../src/hooks/useAccount";
import { installFakeApi } from "./fakeApi";

/** The settings page as the app shows it: signed in, with the account menu beside it. */
async function renderSettings() {
  globalThis.history.replaceState(null, "", "/settings");
  const view = render(<App />);
  await screen.findByLabelText("First name");
  return view;
}

describe("general settings", () => {
  it("opens the account settings route from the menu", async () => {
    const user = userEvent.setup();
    render(<UserMenu theme="light" onThemeChange={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(screen.getByRole("menuitem", { name: "Settings" }));
    expect(location.pathname).toBe("/settings");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("saves the chosen time zone with the profile and previews the time there", async () => {
    installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    const zone = screen.getByRole("combobox", { name: "Time zone" });
    expect(zone).toHaveValue("");

    await user.selectOptions(zone, "Asia/Tokyo");
    // Picked but not yet saved: the clock follows, the browser does not.
    expect(localStorage.getItem("workspaced.timeZone")).toBeNull();
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(localStorage.getItem("workspaced.timeZone")).toBe("Asia/Tokyo");
    expect(screen.getByRole("status")).toHaveTextContent("Profile updated.");
    // Either side of a minute boundary the clock may have ticked between render and assertion.
    const tokyo = (at: number) => new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Tokyo", hour: "2-digit", minute: "2-digit" }).format(at);
    const shown = screen.getByLabelText("Current time in the chosen zone").textContent;
    expect([tokyo(Date.now()), tokyo(Date.now() - 60_000)].some((time) => shown?.includes(time))).toBe(true);

    await user.selectOptions(zone, "");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(localStorage.getItem("workspaced.timeZone")).toBe("");
    expect(zone).toHaveValue("");
  });

  it("saves names to the account when updated", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    const view = await renderSettings();
    await user.type(screen.getByLabelText("First name"), "Ada");
    await user.type(screen.getByLabelText("Last name"), "Lovelace");
    expect(api.auth.account.firstName).toBe("");
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("Profile updated.")).toBeInTheDocument();
    expect(api.auth.account).toMatchObject({ firstName: "Ada", lastName: "Lovelace" });
    expect(localStorage.getItem("workspaced:general-settings")).toBeNull();
    view.unmount();
    resetAccount();
    await renderSettings();
    expect(screen.getByLabelText("Last name")).toHaveValue("Lovelace");
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled();
  });

  it("cancels an unsaved name edit", async () => {
    installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    await user.type(screen.getByLabelText("First name"), "Ada");
    expect(screen.getByRole("button", { name: "Save changes" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByLabelText("First name")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();
  });

  it("saves a photo as soon as it is picked, apart from an unsaved name, then removes it", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    await user.type(screen.getByLabelText("First name"), "Draft");
    const file = new File(["image"], "avatar.png", { type: "image/png" });
    await user.upload(screen.getByLabelText("Upload avatar"), file);
    expect(await screen.findByText("Photo updated.")).toBeInTheDocument();
    // In the page and in the account menu.
    await waitFor(() => expect(screen.getAllByAltText("Your avatar")).toHaveLength(2));
    expect(api.auth.account.avatar).toMatch(/^data:image\/png;base64,/);
    expect(api.auth.account.firstName).toBe("");
    expect(screen.getByLabelText("First name")).toHaveValue("Draft");

    await user.click(screen.getByRole("button", { name: "Remove" }));
    expect(await screen.findByText("Photo removed.")).toBeInTheDocument();
    expect(screen.queryByAltText("Your avatar")).not.toBeInTheDocument();
    expect(api.auth.account.avatar).toBeNull();
  });

  it("rejects oversized avatars and reports save failures honestly", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    await user.upload(screen.getByLabelText("Upload avatar"), new File([new Uint8Array(2 * 1024 * 1024 + 1)], "large.png", { type: "image/png" }));
    expect(screen.getByRole("alert")).toHaveTextContent("smaller than 2 MB");
    await user.type(screen.getByLabelText("First name"), "Ada");
    api.auth.failProfile = true;
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText(/Could not save your profile/)).toBeInTheDocument();
    expect(screen.getByLabelText("First name")).toHaveValue("Ada");
  });

  it("changes the password only with the current one", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    await user.click(screen.getByRole("switch", { name: "Change password" }));
    await user.type(screen.getByLabelText("Current password"), "wrong");
    await user.type(screen.getByLabelText("New password", { exact: true }), "short");
    await user.type(screen.getByLabelText("Confirm new password"), "short");
    await user.click(screen.getByRole("button", { name: "Change password" }));
    expect(screen.getByRole("alert")).toHaveTextContent("at least 8 characters");

    await user.clear(screen.getByLabelText("New password", { exact: true }));
    await user.type(screen.getByLabelText("New password", { exact: true }), "long-enough");
    await user.click(screen.getByRole("button", { name: "Change password" }));
    expect(screen.getByRole("alert")).toHaveTextContent("do not match");

    await user.clear(screen.getByLabelText("Confirm new password"));
    await user.type(screen.getByLabelText("Confirm new password"), "long-enough");
    await user.click(screen.getByRole("button", { name: "Change password" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("current password is wrong");
    expect(api.auth.password).toBe("admin");

    await user.clear(screen.getByLabelText("Current password"));
    await user.type(screen.getByLabelText("Current password"), "admin");
    await user.click(screen.getByRole("button", { name: "Change password" }));
    expect(await screen.findByText("Password changed.")).toBeInTheDocument();
    expect(api.auth.password).toBe("long-enough");
    expect(localStorage.getItem("workspaced:general-settings")).toBeNull();
    // Closed and emptied once it has done its job.
    expect(screen.queryByLabelText("New password", { exact: true })).not.toBeInTheDocument();
  });
});
