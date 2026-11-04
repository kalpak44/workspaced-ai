import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { GeneralSettingsPage } from "../src/components/pages/GeneralSettingsPage";
import { UserMenu } from "../src/components/organisms/UserMenu";

describe("general settings", () => {
  it("opens the account settings route from the menu", async () => {
    const user = userEvent.setup();
    render(<UserMenu theme="light" onThemeChange={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(screen.getByRole("menuitem", { name: "Settings" }));
    expect(location.pathname).toBe("/settings");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("keeps drafts across sections and persists names only when updated", async () => {
    const user = userEvent.setup();
    const view = render(<GeneralSettingsPage />);
    await user.type(screen.getByLabelText("First name"), "Ada");
    await user.type(screen.getByLabelText("Last name"), "Lovelace");
    expect(localStorage.getItem("workspaced:general-settings")).toBeNull();
    await user.click(screen.getByRole("button", { name: "General AI" }));
    await user.click(screen.getByRole("button", { name: "Profile" }));
    expect(screen.getByLabelText("First name")).toHaveValue("Ada");
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    expect(screen.getByRole("status")).toHaveTextContent("Profile updated");
    view.unmount();
    render(<GeneralSettingsPage />);
    expect(screen.getByLabelText("Last name")).toHaveValue("Lovelace");
    expect(screen.getByRole("button", { name: "Update profile" })).toBeDisabled();
  });

  it("round trips raw Markdown through the rich editor without losing unsaved edits", async () => {
    const user = userEvent.setup();
    render(<GeneralSettingsPage />);
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
    expect(JSON.parse(localStorage.getItem("workspaced:general-settings")!).instructions).toBe(markdown);
    expect(screen.getByRole("button", { name: "Save instructions" })).toBeDisabled();
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
    const user = userEvent.setup();
    render(<><GeneralSettingsPage /><UserMenu theme="light" onThemeChange={() => {}} /></>);
    const file = new File(["image"], "avatar.png", { type: "image/png" });
    await user.upload(screen.getByLabelText("Upload avatar"), file);
    await waitFor(() => expect(screen.getAllByAltText("Your avatar")).toHaveLength(1));
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    expect(screen.getAllByAltText("Your avatar")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Remove" }));
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    expect(screen.queryByAltText("Your avatar")).not.toBeInTheDocument();
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

  it("rejects oversized avatars and reports storage failures honestly", async () => {
    const user = userEvent.setup();
    render(<GeneralSettingsPage />);
    await user.upload(screen.getByLabelText("Upload avatar"), new File([new Uint8Array(2 * 1024 * 1024 + 1)], "large.png", { type: "image/png" }));
    expect(screen.getByRole("alert")).toHaveTextContent("smaller than 2 MB");
    await user.type(screen.getByLabelText("First name"), "Ada");
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("Full"); });
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    expect(screen.getByRole("status")).toHaveTextContent("Could not save");
    expect(screen.getByLabelText("First name")).toHaveValue("Ada");
  });

  it("never persists password fields and clears them when the switch closes", async () => {
    const user = userEvent.setup();
    render(<GeneralSettingsPage />);
    await user.click(screen.getByRole("switch", { name: "Change password" }));
    await user.type(screen.getByLabelText("New password", { exact: true }), "never-store-this");
    await user.type(screen.getByLabelText("First name"), "Ada");
    await user.click(screen.getByRole("button", { name: "Update profile" }));
    expect(localStorage.getItem("workspaced:general-settings")).not.toContain("never-store-this");
    await user.click(screen.getByRole("switch", { name: "Change password" }));
    await user.click(screen.getByRole("switch", { name: "Change password" }));
    expect(screen.getByLabelText("New password", { exact: true })).toHaveValue("");
  });
});
