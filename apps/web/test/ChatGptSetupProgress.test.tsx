import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { ChatGptSetupProgress } from "../src/components/organisms/ChatGptSetupProgress";

it("guides creation through startup, sign-in, and the first chat as server status changes", () => {
  const { rerender } = render(<ChatGptSetupProgress workspaceId="new" status="INITIALIZING" connected={false} />);
  expect(screen.getByRole("status", { name: "Preparing workspace" })).toBeVisible();
  expect(screen.getByText(/This page updates automatically/)).toBeVisible();
  expect(screen.getByText("Prepare workspace").closest("li")).toHaveAttribute("aria-current", "step");
  expect(screen.queryByRole("link", { name: "Open workspace" })).not.toBeInTheDocument();
  rerender(<ChatGptSetupProgress workspaceId="new" status="READY" connected={false} />);
  expect(screen.queryByRole("status", { name: "Preparing workspace" })).not.toBeInTheDocument();
  expect(screen.getByText(/Acknowledge the notice below/)).toBeVisible();
  expect(screen.getByText("Connect ChatGPT").closest("li")).toHaveAttribute("aria-current", "step");
  rerender(<ChatGptSetupProgress workspaceId="new" status="READY" connected={true} />);
  expect(screen.getByRole("link", { name: "Open workspace" })).toHaveAttribute("href", "/workspaces/new");
  expect(screen.getByText(/ready for its first message/)).toBeVisible();
});

it.each(["PAUSED", "FAILED"] as const)("gives recovery instructions instead of a spinner when %s", status => {
  render(<ChatGptSetupProgress workspaceId="new" status={status} connected={false} />);
  expect(screen.queryByRole("status", { name: "Preparing workspace" })).not.toBeInTheDocument();
  expect(screen.getByText(/workspace actions/)).toBeVisible();
});
