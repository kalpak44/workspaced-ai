import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { GeneralSettingsPage } from "../src/components/pages/GeneralSettingsPage";
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

  it("keeps drafts across sections and saves names to the account when updated", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    const view = await renderSettings();
    await user.type(screen.getByLabelText("First name"), "Ada");
    await user.type(screen.getByLabelText("Last name"), "Lovelace");
    expect(api.auth.account.firstName).toBe("");
    await user.click(screen.getByRole("button", { name: "General AI" }));
    await user.click(screen.getByRole("button", { name: "Profile" }));
    expect(screen.getByLabelText("First name")).toHaveValue("Ada");
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

  it("round trips raw Markdown through the rich editor and saves it to the account", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(api.apiCalls().some(([url]) => String(url).endsWith("/account/instructions"))).toBe(true));
    await user.click(screen.getByRole("button", { name: "General AI" }));
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    const markdown = "## Working style\n\nUse **clear language**.\n\n- Run tests\n- Be concise";
    fireEvent.change(screen.getByRole("textbox", { name: "AI instructions Markdown" }), { target: { value: markdown } });
    await user.click(screen.getByRole("button", { name: "Editor" }));
    expect(screen.getByRole("textbox", { name: "AI instructions editor" })).toHaveTextContent("Working style");
    expect(screen.getByText("clear language").tagName).toBe("STRONG");
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    expect(screen.getByRole("textbox", { name: "AI instructions Markdown" })).toHaveValue(markdown);
    await user.click(screen.getByRole("button", { name: "Save instructions" }));
    expect(await screen.findByText(/Instructions saved/)).toBeInTheDocument();
    expect(api.auth.instructions).toBe(markdown);
    expect(screen.getByRole("button", { name: "Save instructions" })).toBeDisabled();
  });

  it("offers instructions this browser kept before as a draft until they are saved", async () => {
    localStorage.setItem("workspaced:general-settings", JSON.stringify({ instructions: "Be kind." }));
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<GeneralSettingsPage />);
    await user.click(screen.getByRole("button", { name: "General AI" }));
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "AI instructions Markdown" })).toHaveValue("Be kind."));
    expect(api.auth.instructions).toBe("");
    await user.click(screen.getByRole("button", { name: "Save instructions" }));
    await waitFor(() => expect(api.auth.instructions).toBe("Be kind."));
    expect(localStorage.getItem("workspaced:general-settings")).toBeNull();
  });

  it("updates the formatted document and serializes real edits as Markdown", async () => {
    const user = userEvent.setup();
    render(<GeneralSettingsPage />);
    await user.click(screen.getByRole("button", { name: "General AI" }));
    const textbox = screen.getByRole("textbox", { name: "AI instructions editor" });
    // ProseMirror observes DOM mutations, like a native browser edit.
    await act(async () => {
      textbox.innerHTML = "<h2>Preferences</h2><p>Write <strong>clearly</strong>.</p>";
      fireEvent.input(textbox);
    });
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "AI instructions Markdown" })).toHaveValue("## Preferences\n\nWrite **clearly**."));
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

  it("preserves tables, checklists, and code when formatted text is edited", async () => {
    const user = userEvent.setup();
    render(<GeneralSettingsPage />);
    await user.click(screen.getByRole("button", { name: "General AI" }));
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    fireEvent.change(screen.getByRole("textbox", { name: "AI instructions Markdown" }), { target: {
      value: "| Rule | Value |\n| --- | --- |\n| Style | Clear |\n\n- [x] Run tests\n\n```sh\nnpm test\n```",
    } });
    await user.click(screen.getByRole("button", { name: "Editor" }));
    const textbox = screen.getByRole("textbox", { name: "AI instructions editor" });
    expect(textbox.querySelector("table")).toHaveTextContent("Clear");
    expect(textbox.querySelector('input[type="checkbox"]')).toBeChecked();
    await act(async () => {
      const paragraph = document.createElement("p");
      paragraph.textContent = "Be concise.";
      textbox.append(paragraph);
      fireEvent.input(textbox);
    });
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    const source = (screen.getByRole("textbox", { name: "AI instructions Markdown" }) as HTMLTextAreaElement).value;
    expect(source).toContain("| Style | Clear |");
    expect(source).toContain("[x] Run tests");
    expect(source).toContain("```sh\nnpm test\n```");
    expect(source).toContain("Be concise.");
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
