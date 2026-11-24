import { useCallback, useState } from "react";
import { ApiError } from "../api/http";
import { keepWorkspace, type Workspace } from "../api/workspacesApi";
import { readStored, writeStored } from "../lib/storage";

/** Kept: no longer a demo. Short: the balance lacks shortCents. Refused: why it cannot be kept. */
export type KeepResult =
  | { readonly kind: "kept"; readonly workspace: Workspace }
  | { readonly kind: "short"; readonly shortCents: number }
  | { readonly kind: "refused"; readonly reason: string };

const PENDING = "workspaced.keepAfterTopUp";
// A top-up abandoned for longer than this was not made to keep the demo any more.
const PENDING_FOR_MS = 60 * 60 * 1000;

/** Keeps the demo as a paid workspace, saying what stands in the way when it cannot. */
export async function keep(id: string): Promise<KeepResult> {
  try {
    return { kind: "kept", workspace: await keepWorkspace(id) };
  } catch (error) {
    if (error instanceof ApiError && error.status === 402 && typeof error.problem.shortCents === "number") {
      return { kind: "short", shortCents: error.problem.shortCents };
    }
    if (error instanceof ApiError && error.status === 409 && error.detail) {
      return { kind: "refused", reason: `${error.detail.charAt(0).toUpperCase()}${error.detail.slice(1)}.` };
    }
    return { kind: "refused", reason: "It could not be kept just now. Please try again." };
  }
}

/** Remembers, across the trip to Stripe, that this payment is to keep the demo. */
export function keepAfterTopUp(id: string): void {
  writeStored(PENDING, JSON.stringify({ id, at: Date.now() }));
}

/** The demo a top-up just paid for was meant to keep, once; null when there is none. */
export function takeKeepAfterTopUp(): string | null {
  const stored = readStored(PENDING);
  writeStored(PENDING, "");
  if (!stored) return null;
  try {
    const { id, at } = JSON.parse(stored) as { id?: unknown; at?: unknown };
    return typeof id === "string" && typeof at === "number" && Date.now() - at < PENDING_FOR_MS ? id : null;
  } catch {
    return null;
  }
}

/** The dialog's state around {@link keep}. */
export function useKeepWorkspace(id: string) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<KeepResult | null>(null);
  const run = useCallback(async () => {
    setBusy(true);
    try {
      const outcome = await keep(id);
      setResult(outcome);
      return outcome;
    } finally {
      setBusy(false);
    }
  }, [id]);
  return { busy, result, keep: run };
}
