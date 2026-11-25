import { useEffect, useState } from "react";
import { getReferrals, type Referrals } from "../api/referralsApi";

export type { Referrals, ReferralStatus, ReferralTerms, ReferredFriend } from "../api/referralsApi";

/** The signed-in user's invitation link and the friends it brought. */
export function useReferrals() {
  const [referrals, setReferrals] = useState<Referrals | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let current = true;
    setError(null);
    getReferrals()
      .then((value) => { if (current) setReferrals(value); })
      .catch(() => { if (current) setError("Could not load your referrals."); });
    return () => { current = false; };
  }, [attempt]);

  return { referrals, error, retry: () => setAttempt((n) => n + 1) };
}
