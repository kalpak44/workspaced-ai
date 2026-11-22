import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { DemoBanner } from "../src/components/molecules/DemoBanner";
import { installFakeApi } from "./fakeApi";

afterEach(() => vi.useRealTimers());

it.each([
  [1, 100, "bg-emerald-500"], [0.51, 51, "bg-emerald-500"],
  [0.5, 50, "bg-orange-500"], [0.21, 21, "bg-orange-500"],
  [0.2, 20, "bg-red-500"], [0, 0, "bg-red-500"],
])("shows a $%s trial budget at %s percent", async (remaining, percentage, color) => {
  const api = installFakeApi();
  api.connectors.push({ id: "trial-key", name: "Trial", provider: "openrouter", trial: true });
  api.state.trialRemaining = remaining as number;
  render(<DemoBanner expiresAt={new Date(Date.now() + 3600_000).toISOString()} name="Demo" />);
  const bar = await screen.findByRole("progressbar", { name: "Trial AI budget remaining" });
  expect(bar).toHaveAttribute("aria-valuenow", String(percentage));
  expect(bar.firstChild).toHaveClass(color as string);
  expect(screen.getByRole("status", { name: "Demo workspace" })).toHaveTextContent("It is deleted in");
  expect(bar).toHaveAttribute("aria-valuetext", `$${Number(remaining).toFixed(2)} of $1.00 remaining`);
});

it("does not invent a full budget when OpenRouter does not report usage", async () => {
  const api = installFakeApi();
  api.connectors.push({ id: "trial-key", name: "Trial", provider: "openrouter", trial: true });
  api.state.trialRemaining = null;
  render(<DemoBanner expiresAt={null} name="Demo" />);
  await screen.findByText("Unavailable");
  expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
});

it("refreshes the actual remaining budget every thirty seconds", async () => {
  const api = installFakeApi();
  api.connectors.push({ id: "trial-key", name: "Trial", provider: "openrouter", trial: true });
  render(<DemoBanner expiresAt={null} name="Demo" />);
  await screen.findByRole("progressbar");
  await waitFor(() => expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "100"));
  vi.useFakeTimers();
  // Remount while fake timers are active so the interval uses the same clock.
  const { unmount } = render(<DemoBanner expiresAt={null} name="Second demo" />);
  await act(async () => { await vi.advanceTimersByTimeAsync(1); });
  api.state.trialRemaining = 0.1;
  await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
  expect(screen.getAllByRole("progressbar").at(-1)).toHaveAttribute("aria-valuenow", "10");
  unmount();
});
