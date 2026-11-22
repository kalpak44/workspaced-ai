import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { GeneralSettingsPage } from "../src/components/pages/GeneralSettingsPage";
import { SettingsPage } from "../src/components/pages/SettingsPage";
import { installFakeApi } from "./fakeApi";

let api: ReturnType<typeof installFakeApi>;
beforeEach(() => { api = installFakeApi(); });
it("saves a named key under account settings, hides it, and deletes the connector", async () => {
  const user = userEvent.setup();
  render(<GeneralSettingsPage section="ai-connectors" />);
  await screen.findByText("No connectors yet. Add your first connection below.");
  await user.type(screen.getByRole("textbox", { name: "Connector name" }), "Work");
  await user.type(screen.getByLabelText("API key"), "sk-private");
  await user.click(screen.getByRole("checkbox", { name: /I acknowledge the third-party AI risks/ }));
  await user.click(screen.getByRole("button", { name: "Save connector" }));
  await screen.findByText("Work");
  expect(api.connectors).toEqual([{ id: "connector-1", name: "Work", provider: "openrouter" }]);
  expect(screen.getByLabelText("API key")).toHaveValue("");
  expect(screen.queryByText("sk-private")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Delete Work" }));
  expect(screen.getByRole("dialog", { name: "Remove AI connector?" })).toHaveTextContent("The API key will stay active in your OpenRouter account");
  expect(api.connectors).toHaveLength(1);
  await user.click(screen.getByRole("button", { name: "Cancel" }));
  expect(api.connectors).toHaveLength(1);
  await user.click(screen.getByRole("button", { name: "Delete Work" }));
  await user.click(screen.getByRole("button", { name: "Remove connector" }));
  await waitFor(() => expect(api.connectors).toHaveLength(0));
});
it("applies a saved connector and requests a workspace restart", async () => {
  api.connectors.push({ id: "connector-1", name: "Personal", provider: "openrouter" });
  const changed = vi.fn();
  render(<SettingsPage section="secrets" workspace={api.rows[0]} onChanged={changed} onRestart={vi.fn()} />);
  await screen.findByRole("option", { name: "Personal" });
  expect(screen.getByRole("link", { name: "Manage AI connectors in General settings" })).toHaveAttribute("href", "/settings/ai-connectors");
  expect(screen.queryByRole("button", { name: "AI connector" })).not.toBeInTheDocument();
  const user = userEvent.setup();
  await waitFor(() => expect(screen.getByRole("combobox")).toBeEnabled());
  await user.selectOptions(screen.getByRole("combobox"), "connector-1");
  await user.click(screen.getByRole("button", { name: "Save connector" }));
  await waitFor(() => expect(changed).toHaveBeenCalledWith({ restartRequired: true }));
  expect(api.workspaceConnectors.get(api.rows[0].id)).toBe("connector-1");
});

it("requires explicit acknowledgement for pasted keys and OAuth, with policy links", async () => {
  const user = userEvent.setup();
  render(<GeneralSettingsPage section="ai-connectors" />);
  await user.type(screen.getByRole("textbox", { name: "Connector name" }), "Personal");
  await user.type(screen.getByLabelText("API key"), "sk-private");
  expect(screen.getByRole("button", { name: "Save connector" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Connect OpenRouter" })).toBeDisabled();
  expect(screen.getByRole("link", { name: "Terms of service" })).toHaveAttribute("href", "https://workspaced.online/terms");
  expect(screen.getByRole("link", { name: "Privacy policy" })).toHaveAttribute("href", "https://workspaced.online/privacy");
  await user.click(screen.getByRole("checkbox", { name: /I acknowledge the third-party AI risks/ }));
  expect(screen.getByRole("button", { name: "Save connector" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Connect OpenRouter" })).toBeEnabled();
});

it("shows each selected key's usage and links to provider limit management", async () => {
  api.connectors.push({ id: "connector-1", name: "Personal", provider: "openrouter" }, { id: "connector-2", name: "Work", provider: "openrouter" });
  const user = userEvent.setup();
  render(<GeneralSettingsPage section="ai-connectors" />);
  await screen.findByRole("region", { name: "Usage for Personal" });
  await screen.findAllByText("$2.50");
  await user.click(screen.getByRole("button", { name: /^Work/ }));
  await screen.findByRole("region", { name: "Usage for Work" });
  await screen.findByText("$7.00");
  expect(screen.queryByRole("region", { name: "Usage for Personal" })).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Manage limits in OpenRouter" })).toHaveAttribute("href", "https://openrouter.ai/settings/keys");
});

it("uses separate System secrets and Secrets cards and shows the shared restart banner", async () => {
  api.connectors.push({ id: "connector-1", name: "Personal", provider: "openrouter" });
  const restart = vi.fn();
  const changed = vi.fn();
  const workspace = api.rows[0];
  const { rerender } = render(<SettingsPage section="secrets" workspace={workspace} onChanged={changed} onRestart={restart} />);
  expect(screen.getByRole("region", { name: "System secrets" })).not.toBe(screen.getByRole("region", { name: "Secrets" }));
  await waitFor(() => expect(screen.getByRole("combobox", { name: "Saved connector" })).toBeEnabled());
  const user = userEvent.setup();
  await user.selectOptions(screen.getByRole("combobox", { name: "Saved connector" }), "connector-1");
  await user.click(screen.getByRole("button", { name: "Save connector" }));
  await waitFor(() => expect(changed).toHaveBeenCalledWith({ restartRequired: true }));
  rerender(<SettingsPage section="secrets" workspace={{ ...workspace, restartRequired: true }} onChanged={changed} onRestart={restart} />);
  expect(screen.getByRole("status", { name: "Restart required" })).toHaveTextContent("Settings changed");
  await user.click(screen.getByRole("button", { name: "Restart workspace" }));
  expect(restart).toHaveBeenCalledOnce();
});

it("shows Trial as the protected default and removes personal connectors with fallback", async () => {
  api.connectors.push({ id: "trial-key", name: "Trial", provider: "openrouter", trial: true }, { id: "personal", name: "Personal", provider: "openrouter" });
  api.workspaceConnectors.set(api.rows[0].id, "personal");
  const user = userEvent.setup();
  render(<GeneralSettingsPage section="ai-connectors" />);
  await screen.findByText("Default · Trial");
  const defaultRow = screen.getByLabelText("Default Trial connector");
  expect(defaultRow.compareDocumentPosition(screen.getByRole("button", { name: /^Personal/ })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Delete Trial" })).not.toBeInTheDocument();
  await screen.findByRole("progressbar", { name: "Trial AI budget remaining" });
  expect(screen.queryByRole("region", { name: "Usage for Trial" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Delete Personal" }));
  expect(screen.getByRole("dialog")).toHaveTextContent("Workspaces using this connector will switch to Trial and require a restart");
  await user.click(screen.getByRole("button", { name: "Remove connector" }));
  await waitFor(() => expect(api.connectors).toHaveLength(1));
  expect(api.workspaceConnectors.get(api.rows[0].id)).toBe("trial-key");
  expect(api.rows[0].restartRequired).toBe(true);
});

it("offers only the user's real connectors, with Trial first and no placeholder entries", async () => {
  api.connectors.push(
    { id: "personal", name: "My key", provider: "openrouter" },
    { id: "trial", name: "Trial", provider: "openrouter", trial: true },
    { id: "other-provider", name: "Different provider", provider: "another" },
  );
  api.workspaceConnectors.set(api.rows[0].id, "personal");
  render(<SettingsPage section="secrets" workspace={api.rows[0]} onChanged={vi.fn()} onRestart={vi.fn()} />);
  const select = screen.getByRole("combobox", { name: "Saved connector" });
  await waitFor(() => expect(select).toBeEnabled());
  expect([...select.querySelectorAll("option")].map(option => option.value)).toEqual(["trial", "personal"]);
  expect(select).toHaveValue("personal");
  expect(screen.queryByRole("option", { name: "Choose a connector" })).not.toBeInTheDocument();
  expect(screen.queryByRole("option", { name: "Current workspace key" })).not.toBeInTheDocument();
});
