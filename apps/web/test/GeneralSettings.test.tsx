import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { browser } from "../src/api/accountApi";
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

  it("has no time zone of its own, as each workspace keeps one", async () => {
    installFakeApi();
    await renderSettings();
    expect(screen.queryByRole("combobox", { name: /Time zone/ })).not.toBeInTheDocument();
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

  it("emails a password reset link and says where it went", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    api.auth.failReset = true;
    await user.click(screen.getByRole("button", { name: "Send reset email" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not send the email");
    expect(api.auth.resetRequests).toBe(0);

    api.auth.failReset = false;
    await user.click(screen.getByRole("button", { name: "Send reset email" }));
    expect(await screen.findByText(/A reset link is on its way to admin@example.com/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(api.auth.resetRequests).toBe(1);
    expect(api.fetchFn).toHaveBeenCalledWith("/api/v1/account/password-reset", expect.objectContaining({ method: "POST" }));
    expect(screen.getByRole("button", { name: "Send again" })).toBeInTheDocument();
  });

  it("sets up two-factor authentication through a sign-in", async () => {
    installFakeApi();
    const opened = vi.spyOn(browser, "open").mockImplementation(() => {});
    const user = userEvent.setup();
    await renderSettings();
    const section = screen.getByRole("region", { name: "Two-factor authentication" });
    await user.click(within(section).getByRole("button", { name: "Set up" }));
    expect(opened).toHaveBeenCalledWith("/api/v1/oauth2/authorization/auth0?mfa=enroll");
  });
});
