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
  render(<SettingsPage section="profile" workspace={api.rows[0]} onChanged={changed} onRestart={vi.fn()} onLeave={vi.fn()} />);
  await screen.findByRole("option", { name: "Personal" });
  expect(screen.getByRole("link", { name: "Manage AI connectors in General settings" })).toHaveAttribute("href", "/settings/ai-connectors");
  expect(screen.queryByRole("button", { name: "AI connector" })).not.toBeInTheDocument();
  const user = userEvent.setup();
  await waitFor(() => expect(screen.getByRole("combobox")).toBeEnabled());
  await user.selectOptions(screen.getByRole("combobox"), "connector-1");
  await user.click(screen.getByRole("button", { name: "Save connector" }));
  await waitFor(() => expect(changed).toHaveBeenCalledWith({ restartRequired: true }));
  expect(api.pendingConnectors.get(api.rows[0].id)).toBe("connector-1");
  expect(api.workspaceConnectors.get(api.rows[0].id)).toBeUndefined();
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

it("shows the profile separately from Secrets and keeps its restart action", async () => {
  api.connectors.push({ id: "connector-1", name: "Personal", provider: "openrouter" });
  const restart = vi.fn();
  const changed = vi.fn();
  const workspace = api.rows[0];
  const { rerender } = render(<SettingsPage section="profile" workspace={workspace} onChanged={changed} onRestart={restart} onLeave={vi.fn()} />);
  expect(await screen.findByRole("region", { name: "Profile" })).toBeVisible();
  expect(screen.queryByRole("region", { name: "Secrets" })).not.toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole("combobox", { name: "Saved connector" })).toBeEnabled());
  const user = userEvent.setup();
  await user.selectOptions(screen.getByRole("combobox", { name: "Saved connector" }), "connector-1");
  await user.click(screen.getByRole("button", { name: "Save connector" }));
  await waitFor(() => expect(changed).toHaveBeenCalledWith({ restartRequired: true }));
  rerender(<SettingsPage section="profile" workspace={{ ...workspace, restartRequired: true }} onChanged={changed} onRestart={restart} onLeave={vi.fn()} />);
  expect(screen.getByRole("status", { name: "Restart required" })).toHaveTextContent("Settings changed");
  await user.click(screen.getByRole("button", { name: "Restart workspace" }));
  expect(restart).toHaveBeenCalledOnce();
});

it("protects Trial and refuses to disconnect a connector still used by a workspace", async () => {
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
  expect(screen.getByRole("dialog")).toHaveTextContent("Workspaces must activate another connector before this one can be removed");
  await user.click(screen.getByRole("button", { name: "Remove connector" }));
  expect(await screen.findAllByText("This connector is still active or pending in a workspace. Activate another connector there first.")).not.toHaveLength(0);
  expect(api.connectors).toHaveLength(2);
  expect(api.workspaceConnectors.get(api.rows[0].id)).toBe("personal");
});

it("offers only the user's real connectors, with Trial first and no placeholder entries", async () => {
  api.connectors.push(
    { id: "personal", name: "My key", provider: "openrouter" },
    { id: "trial", name: "Trial", provider: "openrouter", trial: true },
    { id: "other-provider", name: "Different provider", provider: "another" },
  );
  api.workspaceConnectors.set(api.rows[0].id, "personal");
  render(<SettingsPage section="profile" workspace={api.rows[0]} onChanged={vi.fn()} onRestart={vi.fn()} onLeave={vi.fn()} />);
  const select = await screen.findByRole("combobox", { name: "Saved connector" });
  await waitFor(() => expect(select).toBeEnabled());
  expect([...select.querySelectorAll("option")].map(option => option.value)).toEqual(["trial", "personal"]);
  expect(select).toHaveValue("personal");
  expect(screen.queryByRole("option", { name: "Choose a connector" })).not.toBeInTheDocument();
  expect(screen.queryByRole("option", { name: "Current workspace key" })).not.toBeInTheDocument();
});

it("keeps the active connector distinct from a pending same-provider key and waits for server readiness", async () => {
  api.connectors.push({ id: "old", name: "Old key", provider: "openrouter" }, { id: "new", name: "New key", provider: "openrouter" });
  api.workspaceConnectors.set(api.rows[0].id, "old");
  render(<SettingsPage section="profile" workspace={api.rows[0]} onChanged={vi.fn()} onRestart={vi.fn()} onLeave={vi.fn()} />);
  await screen.findByText("Active (last confirmed): Old key · OpenRouter");
  const user = userEvent.setup();
  await user.selectOptions(screen.getByRole("combobox", { name: "Saved connector" }), "new");
  await user.click(screen.getByRole("button", { name: "Save connector" }));
  await screen.findByText("Pending: New key · OpenRouter");
  expect(screen.getByLabelText("Active AI connector")).toHaveTextContent("Old key");
  api.providerPhases.set(api.rows[0].id, { phase: "APPLYING", error: null });
  globalThis.dispatchEvent(new Event("focus"));
  await screen.findByText(/Waiting for verified readiness/);
  expect(screen.getByLabelText("Active AI connector")).toHaveTextContent("Old key");
  expect(screen.getByRole("combobox", { name: "Saved connector" })).toBeDisabled();
  api.workspaceConnectors.set(api.rows[0].id, "new");
  api.pendingConnectors.delete(api.rows[0].id);
  api.providerPhases.set(api.rows[0].id, { phase: "NONE", error: null });
  globalThis.dispatchEvent(new Event("focus"));
  await screen.findByText("Active (last confirmed): New key · OpenRouter");
  expect(screen.queryByLabelText("Pending AI connector")).not.toBeInTheDocument();
});

it("reports rollback and blocked recovery without claiming the pending connector is active", async () => {
  api.connectors.push({ id: "old", name: "Original", provider: "openrouter" }, { id: "new", name: "Target", provider: "openrouter" });
  api.workspaceConnectors.set(api.rows[0].id, "old");
  api.pendingConnectors.set(api.rows[0].id, "new");
  api.providerPhases.set(api.rows[0].id, { phase: "ROLLING_BACK", error: "RESTART_FAILED" });
  render(<SettingsPage section="profile" workspace={api.rows[0]} onChanged={vi.fn()} onRestart={vi.fn()} onLeave={vi.fn()} />);
  await screen.findByText(/Restoring the previous connector/);
  api.providerPhases.set(api.rows[0].id, { phase: "STAGED", error: "RESTART_FAILED_ROLLED_BACK" });
  globalThis.dispatchEvent(new Event("focus"));
  await screen.findByText(/The previous connector was restored/);
  expect(screen.getByLabelText("Active AI connector")).toHaveTextContent("Original");
  api.providerPhases.set(api.rows[0].id, { phase: "BLOCKED", error: "PREVIOUS_BINDING_UNAVAILABLE" });
  globalThis.dispatchEvent(new Event("focus"));
  await screen.findByText(/Restart to retry recovery/);
  expect(screen.queryByRole("button", { name: "Connect ChatGPT" })).not.toBeInTheDocument();
});

it("lets the owner inspect Trial token usage and keeps API-key disconnect provider-specific", async () => {
  api.connectors.push({ id: "trial", name: "Trial", provider: "openrouter", trial: true }, { id: "openai", name: "Local key", provider: "openai" });
  api.usage.push({ workspaceId: "w", workspaceName: "Trial workspace", connectorId: "trial", provider: "openrouter", model: "measured", steps: 1, input: 20, output: 3, reasoning: 0, cacheRead: 0, cacheWrite: 0 });
  render(<GeneralSettingsPage section="ai-connectors" />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Show Trial token usage" }));
  await screen.findByText("Trial workspace");
  await user.click(screen.getByRole("button", { name: "Delete Local key" }));
  expect(screen.getByRole("dialog")).toHaveTextContent("The API key will stay active with its provider.");
  expect(screen.queryByRole("link", { name: "Open OpenRouter key settings" })).not.toBeInTheDocument();
});

it("keeps profile controls out of Secrets", async () => {
  render(<SettingsPage section="secrets" workspace={api.rows[0]} onChanged={vi.fn()} onRestart={vi.fn()} onLeave={vi.fn()} />);
  await screen.findByLabelText("Secrets used");
  expect(await screen.findByRole("button", { name: "Profile" })).toBeVisible();
  expect(screen.getByRole("region", { name: "Secrets" })).toBeVisible();
  expect(screen.queryByRole("combobox", { name: "Saved connector" })).not.toBeInTheDocument();
  expect(screen.queryByRole("region", { name: "Profile" })).not.toBeInTheDocument();
});

it("hides Profile from collaborators even when opened by a direct URL", async () => {
  render(<SettingsPage section="profile" workspace={{ ...api.rows[0], ownerId: "another-owner" }} onChanged={vi.fn()} onRestart={vi.fn()} onLeave={vi.fn()} />);
  await screen.findByRole("region", { name: "MCP servers" });
  await screen.findByLabelText("Secrets used");
  expect(screen.queryByRole("button", { name: "Profile" })).not.toBeInTheDocument();
  expect(screen.queryByRole("combobox", { name: "Saved connector" })).not.toBeInTheDocument();
  expect(screen.queryByRole("region", { name: "Profile" })).not.toBeInTheDocument();
});

it("creates an OpenAI API-key connector from the provider dropdown", async () => {
  const user = userEvent.setup();
  render(<GeneralSettingsPage section="ai-connectors" />);
  await screen.findByRole("option", { name: "OpenAI API key (experimental)" });
  await user.selectOptions(screen.getByRole("combobox", { name: "Model provider" }), "openai");
  expect(screen.queryByRole("button", { name: "Connect OpenRouter" })).not.toBeInTheDocument();
  expect(screen.getByText(/API usage is billed separately from ChatGPT subscriptions/)).toBeVisible();
  await user.type(screen.getByRole("textbox", { name: "Connector name" }), "OpenAI work");
  await user.type(screen.getByLabelText("API key"), "sk-openai-test");
  await user.click(screen.getByRole("checkbox", { name: /I acknowledge the third-party AI risks/ }));
  await user.click(screen.getByRole("button", { name: "Save connector" }));
  await screen.findByText("AI connector saved. You can now select it in your workspaces.");
  expect(api.connectors).toEqual([{ id: "connector-1", name: "OpenAI work", provider: "openai" }]);
  expect(screen.getByLabelText("API key")).toHaveValue("");
  expect(screen.queryByRole("region", { name: "Usage for OpenAI work" })).not.toBeInTheDocument();
});

it("shows ChatGPT sign-in only for the selected profile connection method", async()=>{
  api.connectors.push({id:"key",name:"My key",provider:"openrouter"});
  render(<SettingsPage section="profile" workspace={api.rows[0]} onChanged={vi.fn()} onRestart={vi.fn()} onLeave={vi.fn()} />);
  await screen.findByRole("combobox",{name:"Saved connector"});
  expect(screen.queryByRole("button",{name:"Connect ChatGPT"})).not.toBeInTheDocument();
  const user=userEvent.setup();
  await user.click(screen.getByRole("radio",{name:/Use ChatGPT plan/}));
  expect(screen.getByRole("button",{name:"Connect ChatGPT"})).toBeVisible();
  await user.click(screen.getByRole("radio",{name:/Saved API connector/}));
  expect(screen.queryByRole("button",{name:"Connect ChatGPT"})).not.toBeInTheDocument();
});
