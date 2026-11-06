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

  it("saves names to the account when updated", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    const view = await renderSettings();
    await user.type(screen.getByLabelText("First name"), "Ada");
    await user.type(screen.getByLabelText("Last name"), "Lovelace");
    expect(api.auth.account.firstName).toBe("");
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    expect(await screen.findByText("Profile updated.")).toBeInTheDocument();
    expect(api.auth.account).toMatchObject({ firstName: "Ada", lastName: "Lovelace" });
    expect(localStorage.getItem("workspaced:general-settings")).toBeNull();
    view.unmount();
    resetAccount();
    await renderSettings();
    expect(screen.getByLabelText("Last name")).toHaveValue("Lovelace");
    expect(screen.getByRole("button", { name: "Update profile" })).toBeDisabled();
  });

  it("previews and saves an avatar in the account menu, then restores the default", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    const file = new File(["image"], "avatar.png", { type: "image/png" });
    await user.upload(screen.getByLabelText("Upload avatar"), file);
    await waitFor(() => expect(screen.getAllByAltText("Your avatar")).toHaveLength(1));
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    await waitFor(() => expect(screen.getAllByAltText("Your avatar")).toHaveLength(2));
    expect(api.auth.account.avatar).toMatch(/^data:image\/png;base64,/);
    await user.click(screen.getByRole("button", { name: "Remove" }));
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    await waitFor(() => expect(screen.queryByAltText("Your avatar")).not.toBeInTheDocument());
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
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    expect(await screen.findByText(/Could not save the profile/)).toBeInTheDocument();
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
