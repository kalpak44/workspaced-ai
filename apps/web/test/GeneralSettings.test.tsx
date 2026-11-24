import { render, screen, within } from "@testing-library/react";
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

  it("shows the account's picture from Auth0, and the default without one", async () => {
    const api = installFakeApi();
    api.auth.account = { ...api.auth.account, avatar: "https://s.gravatar.com/avatar/abc?s=480&d=mp" };
    const view = await renderSettings();
    const profile = screen.getByRole("region", { name: "Profile" });
    expect(within(profile).getByAltText("Your avatar")).toHaveAttribute("src", "https://s.gravatar.com/avatar/abc?s=480&d=mp");
    expect(within(profile).getByText(/Your Gravatar for this email address/)).toBeInTheDocument();
    // Auth0 owns the picture, so there is nothing here to upload or remove.
    expect(screen.queryByLabelText("Upload avatar")).not.toBeInTheDocument();
    expect(within(profile).queryByRole("button", { name: /photo|Remove/ })).not.toBeInTheDocument();

    view.unmount();
    resetAccount();
    api.auth.account = { ...api.auth.account, avatar: null };
    await renderSettings();
    expect(within(screen.getByRole("region", { name: "Profile" })).getByRole("img", { name: "Default avatar" })).toBeInTheDocument();
  });

  it("sends only the names, and reports a save failure honestly", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    await user.type(screen.getByLabelText("First name"), "Ada");
    api.auth.failProfile = true;
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText(/Could not save your profile/)).toBeInTheDocument();
    expect(screen.getByLabelText("First name")).toHaveValue("Ada");

    api.auth.failProfile = false;
    await user.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("Profile updated.")).toBeInTheDocument();
    const [, init] = api.fetchFn.mock.calls.findLast(([url]) => String(url).endsWith("/account/profile"))!;
    expect(JSON.parse(String(init?.body))).toEqual({ firstName: "Ada", lastName: "" });
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

  it("turns balance reminder emails off and on, keeping the choice on the account", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    const view = await renderSettings();
    const section = screen.getByRole("region", { name: "Email" });
    const reminders = within(section).getByRole("checkbox", { name: /Email me balance reminders/ });
    expect(reminders).toBeChecked();

    await user.click(reminders);
    expect(await screen.findByText("Balance reminders will only show here.")).toBeInTheDocument();
    expect(api.auth.account.mail).toEqual({ balanceReminders: false });
    view.unmount();
    resetAccount();
    await renderSettings();
    expect(screen.getByRole("checkbox", { name: /Email me balance reminders/ })).not.toBeChecked();
  });

  it("keeps balance reminder emails on when the choice cannot be saved", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await renderSettings();
    api.auth.failMail = true;
    await user.click(screen.getByRole("checkbox", { name: /Email me balance reminders/ }));
    expect(await screen.findByText(/Could not save your email settings/)).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /Email me balance reminders/ })).toBeChecked();
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
