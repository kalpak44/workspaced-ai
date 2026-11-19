import { Camera, KeyRound, Mail, ShieldCheck, UserRound } from "lucide-react";
import { type ReactNode, useRef, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { navigate } from "../../hooks/useRoute";
import { Avatar, Button, TextField } from "../atoms";
import { PageHeader, SectionNav, Toast } from "../molecules";
import { MailPanel } from "../organisms";
import { ContextLayout } from "../templates/ContextLayout";

const NOTES = {
  profile: "Your photo, name and preferences.",
  mail: "Mail sent from the platform's own addresses.",
};

type Section = "profile" | "mail";

/** Account settings; the open section is in the URL, so a link can open Mail directly. */
export function GeneralSettingsPage({ section: requested }: { readonly section?: string }) {
  const section: Section = requested === "mail" ? requested : "profile";
  // Replaced rather than pushed, so Back leaves settings instead of stepping through sections.
  const setSection = (id: Section) => navigate({ name: "general-settings", section: id }, { replace: true });
  const { account, saveProfile, requestPasswordReset, enrollMfa } = useAccount();
  const saved = { firstName: account?.firstName ?? "", lastName: account?.lastName ?? "" };
  const [names, setNames] = useState(saved);
  const [savingProfile, setSavingProfile] = useState(false);
  // The photo shown while its save is in flight; null shows the account's own.
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null);
  const [savingAvatar, setSavingAvatar] = useState(false);
  const avatar = avatarPreview ?? account?.avatar ?? "";
  // The address a reset link went to; empty until one has been sent.
  const [resetSentTo, setResetSentTo] = useState("");
  const [resetError, setResetError] = useState("");
  const [sendingReset, setSendingReset] = useState(false);
  const [notice, setNotice] = useState("");
  const [avatarError, setAvatarError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
  // Only an admin may send mail; the API refuses anyone else, so the tab is not offered.
  const isAdmin = account?.role === "ADMIN";
  const namesChanged = names.firstName !== saved.firstName || names.lastName !== saved.lastName;

  // The photo saves on its own, sending the name as the account already has it, so an unsaved
  // edit here never rides along with a new photo, or the other way round.
  async function submitProfile() {
    const trimmed = { firstName: names.firstName.trim(), lastName: names.lastName.trim() };
    setSavingProfile(true);
    try {
      if (namesChanged) {
        await saveProfile({ ...trimmed, avatar: account?.avatar ?? null });
        setNames(trimmed);
      }
      setNotice("Profile updated.");
    } catch {
      setNotice("Could not save your profile. Please try again.");
    } finally {
      setSavingProfile(false);
    }
  }

  async function saveAvatar(next: string | null) {
    setSavingAvatar(true);
    setAvatarPreview(next ?? "");
    setAvatarError("");
    setNotice("");
    try {
      await saveProfile({ ...saved, avatar: next });
      setNotice(next ? "Photo updated." : "Photo removed.");
    } catch {
      setAvatarError("Could not save the photo. Please try again.");
    } finally {
      setAvatarPreview(null);
      setSavingAvatar(false);
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

  function changeAvatar(file: File | undefined) {
    if (!file) return;
    if (!["image/jpeg", "image/png", "image/webp", "image/gif"].includes(file.type) || file.size > 2 * 1024 * 1024) {
      setAvatarError("Choose a JPG, PNG, WebP, or GIF image smaller than 2 MB.");
      return;
    }
    const reader = new FileReader();
    reader.onload = () => void saveAvatar(String(reader.result));
    reader.onerror = () => setAvatarError("This image could not be read. Please try another.");
    reader.readAsDataURL(file);
  }

  return (
    <>
    <PageHeader title="General settings" description={NOTES[section]} />
    <ContextLayout contained sidebar={
      <SectionNav label="Settings sections" current={section} onSelect={(id) => { setSection(id); setNotice(""); }}
        sections={[{ id: "profile", label: "Profile", Icon: UserRound }, ...(isAdmin ? [{ id: "mail", label: "Mail", Icon: Mail }] as const : [])]} />
    }>
      <div className="w-full">
          <div className="min-w-0 flex-1">
            <div hidden={section !== "profile"} className="space-y-6">
              <section aria-label="Profile" className="card overflow-hidden">
                <CardHeader title="Profile" description="How you appear to other people on this installation." />
                <div className="divide-y divide-zinc-200 dark:divide-zinc-800">
                  <SettingRow title="Photo" hint="JPG, PNG, WebP or GIF, up to 2 MB. Saved as soon as you pick it.">
                    <div className="flex flex-wrap items-center gap-4">
                      <button type="button" aria-label={avatar ? "Change photo" : "Upload photo"} tabIndex={-1} onClick={() => fileInput.current?.click()}
                        className={`group relative flex shrink-0 rounded-full ring-4 ring-zinc-100 transition-opacity dark:ring-zinc-800 ${savingAvatar ? "opacity-60" : ""}`}>
                        <Avatar src={avatar || undefined} large />
                        <span aria-hidden className="absolute inset-0 flex items-center justify-center rounded-full bg-zinc-950/50 text-white opacity-0 transition-opacity group-hover:opacity-100">
                          <Camera className="size-5" />
                        </span>
                      </button>
                      <div className="flex flex-wrap gap-2">
                        <Button label={avatar ? "Change photo" : "Upload photo"} icon={Camera} variant="secondary" busy={savingAvatar} onClick={() => fileInput.current?.click()} />
                        {avatar && !savingAvatar && <Button label="Remove" variant="secondary" onClick={() => void saveAvatar(null)} />}
                      </div>
                      <input ref={fileInput} type="file" accept="image/jpeg,image/png,image/webp,image/gif" aria-label="Upload avatar" className="hidden" onChange={(event) => { void changeAvatar(event.target.files?.[0]); event.target.value = ""; }} />
                    </div>
                    {avatarError && <p role="alert" className="mt-3 text-sm text-rose-600 dark:text-rose-400">{avatarError}</p>}
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
                        {namesChanged ? <span className="flex items-center gap-1.5 font-medium text-amber-700 dark:text-amber-400"><span aria-hidden className="size-1.5 rounded-full bg-amber-500" />Unsaved changes</span> : "Your photo saves on its own; the rest saves here."}
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
            </div>
            {section === "mail" && isAdmin && <MailPanel />}
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
