import { send } from "./http";

const FUNNEL_URL = "/api/v1/admin/funnel";

/** Accounts at each step from sign-up to paying. */
export interface FunnelCounts {
  readonly signedUp: number;
  readonly demoStarted: number;
  readonly paid: number;
  /** Made 30 days ago or more, so they can count as still paying. */
  readonly retentionEligible: number;
  /** Charged for a workspace 30 days or more after signing up. */
  readonly retained: number;
}

export interface FunnelSource {
  /** utm_source of the sign-up link; null for none. */
  readonly source: string | null;
  readonly campaign: string | null;
  readonly counts: FunnelCounts;
}

/** One guided-tour step, as the web app numbers them. */
export interface FunnelTourStep {
  readonly step: number;
  readonly reached: number;
  /** Tours still under way at this step. */
  readonly open: number;
  /** Skipped here and not resumed. */
  readonly paused: number;
  /** Ended here; at the last step that is finishing. */
  readonly ended: number;
}

/** What became of the accounts made between two dates. Only an admin may read it. */
export interface Funnel {
  readonly from: string;
  readonly to: string;
  readonly total: FunnelCounts;
  readonly sources: readonly FunnelSource[];
  readonly tour: readonly FunnelTourStep[];
}

/** Dates are UTC days, both included. */
export async function getFunnel(from: string, to: string): Promise<Funnel> {
  return (await send(`${FUNNEL_URL}?${new URLSearchParams({ from, to })}`)).json();
}
