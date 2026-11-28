import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { ChatGptConnect } from "../src/components/organisms/ChatGptConnect";
import { cancelChatGpt, connectChatGpt, pollChatGpt, selectAiConnector } from "../src/api/aiConnectorsApi";

vi.mock("../src/api/aiConnectorsApi", () => ({
  cancelChatGpt: vi.fn(), connectChatGpt: vi.fn(), pollChatGpt: vi.fn(), selectAiConnector: vi.fn(),
}));
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(connectChatGpt).mockResolvedValue({ id: "attempt", url: "https://auth.openai.com/codex/device", instructions: "Enter code: ABCD-EFGH", expiresAt: new Date(Date.now() + 600000).toISOString(), pollSeconds: 0.01 });
  vi.mocked(pollChatGpt).mockResolvedValue({ status: "pending", connectorId: null });
  vi.mocked(cancelChatGpt).mockResolvedValue();
  vi.mocked(selectAiConnector).mockResolvedValue({ id: "profile", name: "ChatGPT", provider: "openai", authType: "chatgpt", workspaceId: "workspace" });
});
it("requires acknowledgement, shows the provider code, stages the profile and requests a restart", async () => {
  const user=userEvent.setup();
  const connected=vi.fn().mockResolvedValue(undefined);
  render(<ChatGptConnect workspaceId="workspace" disabled={false} onConnected={connected} />);
  expect(screen.getByRole("button", { name: "Connect ChatGPT" })).toBeDisabled();
  await user.click(screen.getByRole("checkbox"));
  await user.click(screen.getByRole("button", { name: "Connect ChatGPT" }));
  expect(connectChatGpt).toHaveBeenCalledWith("workspace","ChatGPT",true);
  expect(await screen.findByText("ABCD-EFGH")).toBeVisible();
  expect(screen.getByRole("link", { name: "Continue to ChatGPT" })).toHaveAttribute("href","https://auth.openai.com/codex/device");
  expect(selectAiConnector).not.toHaveBeenCalled();
  vi.mocked(pollChatGpt).mockResolvedValue({ status: "complete", connectorId: "profile" });
  expect(await screen.findByText("ChatGPT connected. Restart the workspace to activate this profile.")).toBeVisible();
  expect(selectAiConnector).toHaveBeenCalledExactlyOnceWith("workspace","profile");
  expect(connected).toHaveBeenCalledExactlyOnceWith("profile");
  expect(screen.queryByText("ABCD-EFGH")).not.toBeInTheDocument();
  expect(screen.getByRole("button",{name:"Connect ChatGPT"})).toBeEnabled();
});
it("cancels a pending sign-in without changing the workspace profile", async () => {
  const user=userEvent.setup();
  render(<ChatGptConnect workspaceId="workspace" disabled={false} onConnected={vi.fn()} />);
  await user.click(screen.getByRole("checkbox"));
  await user.click(screen.getByRole("button",{name:"Connect ChatGPT"}));
  await user.click(await screen.findByRole("button",{name:"Cancel ChatGPT sign-in"}));
  await waitFor(() => expect(screen.queryByText("ABCD-EFGH")).not.toBeInTheDocument());
  expect(cancelChatGpt).toHaveBeenCalledWith("workspace","attempt");
  expect(selectAiConnector).not.toHaveBeenCalled();
});
it("preserves a successful login when staging fails", async () => {
  vi.mocked(pollChatGpt).mockResolvedValue({status:"complete",connectorId:"profile"});
  vi.mocked(selectAiConnector).mockRejectedValue(new Error("restart in progress"));
  const user=userEvent.setup();
  render(<ChatGptConnect workspaceId="workspace" disabled={false} onConnected={vi.fn()} />);
  await user.click(screen.getByRole("checkbox"));
  await user.click(screen.getByRole("button",{name:"Connect ChatGPT"}));
  expect(await screen.findByRole("alert")).toHaveTextContent("ChatGPT was saved. Select it in Saved connector");
  expect(screen.getByRole("button",{name:"Connect ChatGPT"})).toBeEnabled();
});
