import { Coins, Copy, Share2, UserCheck, UserPlus } from "lucide-react";
import { useState } from "react";
import { type ReferralStatus, type ReferredFriend, type Referrals, useReferrals } from "../../hooks/useReferrals";
import { formatMoney } from "../../lib/format";
import { Button, Spinner } from "../atoms";
import { SettingsSection, Toast } from "../molecules";
import { MetricTile } from "../molecules/MetricTile";

const STATUS: Record<ReferralStatus, string> = {
  SIGNED_UP: "Signed up",
  EARNING: "Paying",
  FINISHED: "Finished",
};

function day(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

/** "10%" without a trailing ".0" for a whole number. */
function percent(value: number): string {
  return `${Number(value.toFixed(2))}%`;
}

/** Of the friends at one stage, what share reached it from sign-up; a dash with no friends. */
function share(part: number, whole: number): string {
  return whole === 0 ? "–" : `${Math.round((part / whole) * 100)}%`;
}

function Funnel({ referrals }: { readonly referrals: Referrals }) {
  const earning = referrals.friends.filter((friend) => friend.status === "EARNING").length;
  const steps = [
    { label: "Signed up", count: referrals.invited },
    { label: "Paid", count: referrals.paying },
    { label: "Still earning you rewards", count: earning },
  ];
  const widest = Math.max(1, referrals.invited);
  return <ol aria-label="Your invitation funnel" className="space-y-2">
    {steps.map((step) => <li key={step.label} className="grid grid-cols-[minmax(0,10rem)_1fr_auto] items-center gap-3 text-sm">
      <span className="truncate">{step.label}</span>
      <span className="h-2 overflow-hidden rounded-full bg-zinc-100 dark:bg-zinc-800">
        <span className="block h-full rounded-full bg-zinc-900 dark:bg-zinc-100" style={{ width: `${(step.count / widest) * 100}%` }} />
      </span>
      <span className="tabular-nums">{step.count} <span className="text-zinc-500">{share(step.count, referrals.invited)}</span></span>
    </li>)}
  </ol>;
}

function FriendRow({ friend, number, cap }: { readonly friend: ReferredFriend; readonly number: number; readonly cap: number }) {
  return <tr className="border-t border-zinc-200 dark:border-zinc-800">
    <th scope="row" className="py-2 pr-4 text-left font-medium">Friend {number}<span className="block text-xs font-normal text-zinc-500">Joined {day(friend.signedUpAt)}</span></th>
    <td className="px-3 py-2">{STATUS[friend.status]}{friend.status === "EARNING" && <span className="block text-xs text-zinc-500">Until {day(friend.earningUntil)}</span>}</td>
    <td className="py-2 pl-3 text-right tabular-nums">{formatMoney(friend.earnedCents)}<span className="block text-xs text-zinc-500">of {formatMoney(cap)}</span></td>
  </tr>;
}

/**
 * The user's invitation link, what it gives both sides, and how far the friends it brought have
 * got. Friends are numbered, not named: names stay in Auth0.
 */
export function ReferralsPanel() {
  const { referrals, error, retry } = useReferrals();
  const [notice, setNotice] = useState("");

  if (error) {
    return <div className="card flex flex-wrap items-center justify-between gap-3 p-4">
      <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
      <Button label="Try again" variant="secondary" onClick={retry} />
    </div>;
  }
  if (!referrals) return <div className="flex justify-center p-8"><Spinner label="Loading your referrals" /></div>;
  if (!referrals.enabled) {
    return <SettingsSection title="Invite friends" description="Referrals are paused for now. Rewards you already got stay in your balance.">{null}</SettingsSection>;
  }
  if (!referrals.eligible || !referrals.link) {
    return <SettingsSection title="Invite friends" description="Admins pay cost price, so they can't invite friends or be invited.">{null}</SettingsSection>;
  }

  const { referee, referrer, link } = referrals;
  const copy = () => { void navigator.clipboard?.writeText(link); setNotice("Link copied."); };
  const canShare = typeof navigator.share === "function";
  const shareLink = () => { void navigator.share?.({ title: "workspaced", text: `Get ${percent(referee.percent)} extra on your first top-ups at workspaced.`, url: link }).catch(() => undefined); };

  return <div className="space-y-6">
    <SettingsSection title={`Give friends ${percent(referee.percent)} extra, earn ${percent(referrer.percent)} of what they pay`}
      description={`Your friends get ${percent(referee.percent)} extra on their top-ups for ${referee.days} days. You get ${percent(referrer.percent)} of what they pay for ${referrer.days} days, up to ${formatMoney(referrer.capCents)} per friend.`}>
      <div className="flex flex-wrap items-center gap-2">
        <input aria-label="Your invitation link" readOnly value={link} onFocus={(event) => event.currentTarget.select()}
          className="min-w-0 flex-1 rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 font-mono text-sm dark:border-zinc-800 dark:bg-zinc-900" />
        <Button label="Copy link" icon={Copy} onClick={copy} />
        {canShare && <Button label="Share" icon={Share2} variant="secondary" onClick={shareLink} />}
      </div>
    </SettingsSection>

    <ul aria-label="Your referrals" className="grid grid-cols-1 gap-3 sm:grid-cols-3">
      <MetricTile label="Friends invited" icon={UserPlus} value={String(referrals.invited)} />
      <MetricTile label="Paying friends" icon={UserCheck} value={String(referrals.paying)} />
      <MetricTile label="Earned so far" icon={Coins} value={formatMoney(referrals.earnedCents)} detail="Added to your balance" />
    </ul>

    <SettingsSection title="How far your friends got" description="Who signed up, who paid, and who still earns you rewards.">
      <Funnel referrals={referrals} />
    </SettingsSection>

    <SettingsSection title="Friends" description="Newest first. Friends are shown as numbers, not names, so their accounts stay private.">
      {referrals.friends.length === 0
        ? <p className="text-sm text-zinc-500 dark:text-zinc-400">Nobody has signed up with your link yet.</p>
        : <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs text-zinc-500 dark:text-zinc-400">
              <th scope="col" className="pb-2 pr-4 font-medium">Friend</th>
              <th scope="col" className="px-3 pb-2 font-medium">Status</th>
              <th scope="col" className="pb-2 pl-3 text-right font-medium">Earned</th>
            </tr></thead>
            <tbody>
              {referrals.friends.map((friend, index) => <FriendRow key={friend.signedUpAt + index} friend={friend} number={referrals.friends.length - index} cap={referrer.capCents} />)}
            </tbody>
          </table>
        </div>}
    </SettingsSection>

    <SettingsSection title="Good to know" description="Rewards go to your balance and pay for your workspaces.">
      <ul className="list-disc space-y-1 pl-5 text-sm text-zinc-600 dark:text-zinc-400">
        <li>The link works only for people who don't have an account yet.</li>
        <li>Only top-ups paid by card count. Rewards, bonuses and the free trial don't.</li>
        <li>Your friend's extra stops at {formatMoney(referee.capCents)}. Yours stops at {formatMoney(referrer.capCents)} per friend.</li>
        <li>Rewards can't be cashed out or moved to another account. If a payment is refunded, its rewards are taken back.</li>
      </ul>
    </SettingsSection>
    <Toast message={notice} />
  </div>;
}
