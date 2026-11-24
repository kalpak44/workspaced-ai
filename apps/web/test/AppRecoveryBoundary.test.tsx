import { lazy, Suspense } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { AppRecoveryBoundary } from "../src/components/templates/AppRecoveryBoundary";

it("offers a working reload when a page module from an older deployment is gone", async () => {
  vi.spyOn(console, "error").mockImplementation(() => undefined);
  const reload = vi.fn();
  vi.stubGlobal("location", { reload });
  const OldPage = lazy(() => Promise.reject(new TypeError("Failed to fetch dynamically imported module")));
  render(<AppRecoveryBoundary><Suspense fallback="Loading page"><OldPage /></Suspense></AppRecoveryBoundary>);
  expect(await screen.findByRole("alert")).toHaveTextContent("Reload to continue");
  await userEvent.setup().click(screen.getByRole("button", { name: "Reload app" }));
  expect(reload).toHaveBeenCalledOnce();
});
