import { send } from "./http";

const REFERRALS_URL = "/api/v1/account/referrals";

/** One side of the program. */
export interface ReferralTerms {
  /** Of each paid top-up before VAT. */
  readonly percent: number;
  /** The most for one friend. */
  readonly capCents: number;
  /** How long after the friend's sign-up it runs. */
  readonly days: number;
}

/** Where an invited friend is; friends have no name here, since names stay in Auth0. */
export type ReferralStatus = "SIGNED_UP" | "EARNING" | "FINISHED";

export interface ReferredFriend {
  readonly signedUpAt: string;
  /** Null while they have not paid. */
  readonly firstPaidAt: string | null;
  readonly status: ReferralStatus;
  /** Credited to the user for this friend. */
  readonly earnedCents: number;
  /** The friend's top-ups earn until then. */
  readonly earningUntil: string;
}

export interface Referrals {
  readonly enabled: boolean;
  /** False for an admin, who takes part on neither side. */
  readonly eligible: boolean;
  readonly code: string | null;
  readonly link: string | null;
  readonly referee: ReferralTerms;
  readonly referrer: ReferralTerms;
  readonly invited: number;
  readonly paying: number;
  readonly earnedCents: number;
  /** Newest first. */
  readonly friends: readonly ReferredFriend[];
}

/** Makes the user's code the first time it is asked for. */
export async function getReferrals(): Promise<Referrals> {
  return (await send(REFERRALS_URL)).json();
}
