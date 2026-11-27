import { Funnel, Gift, KeyRound, Plug, Mail, ShieldCheck, UserRound } from "lucide-react";
import { type ReactNode, useState } from "react";
import { type MailPreferences, useAccount } from "../../hooks/useAccount";
import { navigate } from "../../hooks/useRoute";
import { Avatar, Button, TextField } from "../atoms";
import { PageHeader, SectionNav, Toast } from "../molecules";
import { MailPanel } from "../organisms";
import { ContextLayout } from "../templates/ContextLayout";

import { AiCreditWarningSettings } from "../organisms/AiCreditWarningSettings";
import { AiConnectorsPanel } from "../organisms/AiConnectorsPanel";
import { FunnelPanel } from "../organisms/FunnelPanel";
import { ReferralsPanel } from "../organisms/ReferralsPanel";

const NOTES = {
  "ai-connectors": "Named AI connections for all your workspaces.",
  profile: "Your photo, name, sign-in and email.",
  mail: "Mail sent from the platform's own addresses.",
  funnel: "Where sign-ups come from and how far they get.",
  referrals: "Invite friends and earn credit on what they pay.",
};

type Section = "profile" | "ai-connectors" | "referrals" | "mail" | "funnel";

/** Account settings; the open section is in the URL, so a link can open Mail directly. */
export function GeneralSettingsPage({ section: requested }: { readonly section?: string }) {
  const section: Section = (requested === "mail" || requested === "ai-connectors" || requested === "referrals" || requested === "funnel") ? requested : "profile";
  // Replaced rather than pushed, so Back leaves settings instead of stepping through sections.
  const setSection = (id: Section) => navigate({ name: "general-settings", section: id }, { replace: true });
  const { account, saveProfile, saveMailPreferences, requestPasswordReset, enrollMfa } = useAccount();
  const saved = { firstName: account?.firstName ?? "", lastName: account?.lastName ?? "" };
  const [names, setNames] = useState(saved);
  const [savingProfile, setSavingProfile] = useState(false);
  // The address a reset link went to; empty until one has been sent.
  const [resetSentTo, setResetSentTo] = useState("");
  const [resetError, setResetError] = useState("");
  const [sendingReset, setSendingReset] = useState(false);
  const [notice, setNotice] = useState("");
  const [savingMail, setSavingMail] = useState(false);
  // Mailed unless turned off, as for an account the API has not yet answered with a choice.
  const balanceReminders = account?.mail?.balanceReminders ?? true;
  const tips = account?.mail?.tips ?? true;
  // Only an admin may send mail; the API refuses anyone else, so the tab is not offered.
  const isAdmin = account?.role === "ADMIN";
  const namesChanged = names.firstName !== saved.firstName || names.lastName !== saved.lastName;

  async function submitProfile() {
    const trimmed = { firstName: names.firstName.trim(), lastName: names.lastName.trim() };
    setSavingProfile(true);
    try {
      if (namesChanged) {
        await saveProfile(trimmed);
        setNames(trimmed);
      }
      setNotice("Profile updated.");
    } catch {
      setNotice("Could not save your profile. Please try again.");
    } finally {
      setSavingProfile(false);
    }
  }

  async function saveMail(preferences: Partial<MailPreferences>, saved: string) {
    setSavingMail(true);
    try {
      await saveMailPreferences(preferences);
      setNotice(saved);
    } catch {
      setNotice("Could not save your email settings. Please try again.");
    } finally {
      setSavingMail(false);
    }
  }

  async function sendPasswordReset() {
    setResetError("");
    setSendingReset(true);
    try {
      await requestPasswordReset();
      setResetSentTo(account?.email ?? "");
    } catch {
      setResetError("Could not send the email. Please try again.");
    } finally {
      setSendingReset(false);
    }
  }

  return (
    <>
    <PageHeader title="General settings" description={NOTES[section]} />
    <ContextLayout contained sidebar={
      <SectionNav label="Settings sections" current={section} onSelect={(id) => { setSection(id); setNotice(""); }}
        sections={[{ id: "profile", label: "Profile", Icon: UserRound }, { id: "ai-connectors", label: "AI connectors", Icon: Plug }, { id: "referrals", label: "Referrals", Icon: Gift }, ...(isAdmin ? [{ id: "mail", label: "Mail", Icon: Mail }, { id: "funnel", label: "Funnel", Icon: Funnel }] as const : [])]} />
    }>
      <div className="w-full">
          <div className="min-w-0 flex-1">
            <div hidden={section !== "profile"} className="space-y-6">
              <section aria-label="Profile" className="card overflow-hidden">
                <CardHeader title="Profile" description="How you appear to other people on this installation." />
                <div className="divide-y divide-zinc-200 dark:divide-zinc-800">
                  <SettingRow title="Photo" hint="Your Gravatar for this email address, or your initials when there is none.">
                    <span className="flex w-fit rounded-full ring-4 ring-zinc-100 dark:ring-zinc-800">
                      <Avatar src={account?.avatar ?? undefined} large />
                    </span>
                  </SettingRow>
                  <form aria-label="Details" className="divide-y divide-zinc-200 dark:divide-zinc-800" onSubmit={(event) => { event.preventDefault(); void submitProfile(); }}>
                    <SettingRow title="Name" hint="Shown in the account menu and on recipes you share.">
                      <div className="grid gap-4 sm:grid-cols-2">
                        <TextField id="first-name" label="First name" autoComplete="given-name" maxLength={100} value={names.firstName} onChange={(event) => { setNames({ ...names, firstName: event.target.value }); setNotice(""); }} />
                        <TextField id="last-name" label="Last name" autoComplete="family-name" maxLength={100} value={names.lastName} onChange={(event) => { setNames({ ...names, lastName: event.target.value }); setNotice(""); }} />
                      </div>
                    </SettingRow>
                    <div className="flex flex-wrap items-center justify-between gap-3 bg-zinc-50/80 px-5 py-3 sm:px-6 dark:bg-zinc-950/40">
                      <p className="text-xs text-zinc-500 dark:text-zinc-400">
                        {namesChanged ? <span className="flex items-center gap-1.5 font-medium text-amber-700 dark:text-amber-400"><span aria-hidden className="size-1.5 rounded-full bg-amber-500" />Unsaved changes</span> : "Changes save here."}
                      </p>
                      <div className="flex items-center gap-2">
                        {namesChanged && !savingProfile && (
                          <Button label="Cancel" variant="secondary" onClick={() => setNames(saved)} />
                        )}
                        <Button label="Save changes" type="submit" disabled={!namesChanged} busy={savingProfile} />
                      </div>
                    </div>
                  </form>
                </div>
              </section>
              <section aria-label="Password" className="card overflow-hidden">
                <CardHeader title="Password" description="We email you a link to set a new password."
                  action={<Button label={resetSentTo ? "Send again" : "Send reset email"} icon={KeyRound} variant="secondary" busy={sendingReset} onClick={() => void sendPasswordReset()} />} />
                {(resetSentTo || resetError) && <div className="animate-fade-in px-5 py-4 sm:px-6">
                  {resetError
                    ? <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{resetError}</p>
                    : <p role="status" className="text-sm text-zinc-600 dark:text-zinc-300">A reset link is on its way to {resetSentTo}. It opens a page where you choose the new password.</p>}
                </div>}
              </section>
              <section aria-label="Two-factor authentication" className="card overflow-hidden">
                <CardHeader title="Two-factor authentication" description="Takes you through sign-in to add a second factor. After that, every sign-in asks for a code."
                  action={<Button label="Set up" icon={ShieldCheck} variant="secondary" onClick={enrollMfa} />} />
              </section>
              <section aria-label="Email" className="card overflow-hidden">
                <CardHeader title="Email" description={`What we email to ${account?.email ?? "you"} besides your notifications here.`} />
                <SettingRow title="Balance reminders" hint="A week, three days and a day before your balance runs out. They stay in your notifications either way.">
                  <label className="flex items-start gap-2 text-sm text-zinc-700 dark:text-zinc-300">
                    <input type="checkbox" checked={balanceReminders} disabled={savingMail} onChange={(event) => { setNotice(""); const on = event.target.checked; void saveMail({ balanceReminders: on }, on ? "Balance reminders will be emailed." : "Balance reminders will only show here."); }} className="mt-0.5 size-4 accent-zinc-900 dark:accent-zinc-100" />
                    <span>Email me balance reminders<span className="block text-xs text-zinc-500">A paused workspace, one about to be deleted and a failed automatic top-up are always emailed.</span></span>
                  </label>
                </SettingRow>
                <SettingRow title="Tips" hint="Now and then: a nudge to try the free trial, ideas after it, how to invite friends, or a balance you haven't used. They stay in your notifications either way.">
                  <label className="flex items-start gap-2 text-sm text-zinc-700 dark:text-zinc-300">
                    <input type="checkbox" checked={tips} disabled={savingMail} onChange={(event) => { setNotice(""); const on = event.target.checked; void saveMail({ tips: on }, on ? "Tips will be emailed." : "Tips will only show here."); }} className="mt-0.5 size-4 accent-zinc-900 dark:accent-zinc-100" />
                    <span>Email me tips</span>
                  </label>
                </SettingRow>
              </section>
            </div>
            {section === "ai-connectors" && <><AiCreditWarningSettings /><AiConnectorsPanel /></>}
            {section === "referrals" && <ReferralsPanel />}
            {section === "mail" && isAdmin && <MailPanel />}
            {section === "funnel" && isAdmin && <FunnelPanel />}
            <Toast message={notice} />
          </div>
      </div>
    </ContextLayout>
    </>
  );
}

/** A card's title row, with an optional control on its right. */
function CardHeader({ title, description, action }: { readonly title: string; readonly description: string; readonly action?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-zinc-200 px-5 py-4 sm:px-6 dark:border-zinc-800">
      <div className="min-w-0">
        <h2 className="font-semibold tracking-tight">{title}</h2>
        <p className="mt-0.5 text-sm text-zinc-500 dark:text-zinc-400">{description}</p>
      </div>
      {action}
    </div>
  );
}

/** One setting: what it is on the left, the control on the right; stacked on a phone. */
function SettingRow({ title, hint, children }: { readonly title: ReactNode; readonly hint: string; readonly children: ReactNode }) {
  return (
    <div className="grid gap-3 px-5 py-5 sm:px-6 md:grid-cols-[minmax(0,12rem)_minmax(0,1fr)] md:gap-8">
      <div>
        <p className="text-sm font-medium">{title}</p>
        <p className="mt-1 text-xs text-pretty text-zinc-500 dark:text-zinc-400">{hint}</p>
      </div>
      <div className="min-w-0">{children}</div>
    </div>
  );
}
