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
  await user.click(screen.getByRole("button", { name: "Save connector" }));
  await screen.findByText("Work");
  expect(api.connectors).toEqual([{ id: "connector-1", name: "Work", provider: "openrouter" }]);
  expect(screen.getByLabelText("API key")).toHaveValue("");
  expect(screen.queryByText("sk-private")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Delete Work" }));
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
