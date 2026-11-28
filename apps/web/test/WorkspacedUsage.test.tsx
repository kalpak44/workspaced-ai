import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it } from "vitest";
import { WorkspacedUsage } from "../src/components/organisms/WorkspacedUsage";
import { installFakeApi } from "./fakeApi";
let api: ReturnType<typeof installFakeApi>;
beforeEach(() => { api = installFakeApi(); });
const row = { workspaceId: "w1", workspaceName: "Project one", connectorId: "c1", provider: "openai", model: "gpt-6.1-sol", steps: 2, input: 1250, output: 47, reasoning: 9, cacheRead: 11, cacheWrite: 0 };
it("shows measured workspace/model tokens without a quota and only for the selected connector", async () => {
  api.usage.push(row, { ...row, connectorId: "c2", workspaceName: "Other connector" });
  render(<WorkspacedUsage connectorId="c1" />);
  await screen.findByText("Project one");
  expect(screen.getByText("1,250")).toBeInTheDocument();
  expect(screen.getByText("openai · gpt-6.1-sol")).toBeInTheDocument();
  expect(screen.queryByText("Other connector")).not.toBeInTheDocument();
  expect(screen.getByText(/not a provider balance or remaining ChatGPT allowance/)).toBeInTheDocument();
});
it("handles empty receipts, refresh and errors without invented tokens", async () => {
  render(<WorkspacedUsage connectorId="c1" />);
  await screen.findByText("No attributed completed steps recorded yet.");
  api.usage.push(row);
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Refresh token usage" }));
  await screen.findByText("Project one");
  api.state.fail = 503;
  await user.click(screen.getByRole("button", { name: "Refresh token usage" }));
  await screen.findByRole("alert");
  expect(screen.getByText("Project one")).toBeInTheDocument();
});
it("drops an old connector's figures when selection changes", async () => {
  api.usage.push(row);
  const { rerender } = render(<WorkspacedUsage key="c1" connectorId="c1" />);
  await screen.findByText("Project one");
  rerender(<WorkspacedUsage key="c2" connectorId="c2" />);
  await waitFor(() => expect(screen.queryByText("Project one")).not.toBeInTheDocument());
});
