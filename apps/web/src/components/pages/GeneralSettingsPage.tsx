import { Camera, Clock, Globe, Search, UserRound, Users } from "lucide-react";
import { type ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { BROWSER_TIME_ZONE, TIME_ZONES, useTimeZone, zoneOffset } from "../../hooks/useTimeZone";
import { useAccount } from "../../hooks/useAccount";
import { useDomains } from "../../hooks/useDomains";
import { navigate } from "../../hooks/useRoute";
import { type UserSort, useUsers } from "../../hooks/useUsers";
import { Avatar, Button, Select, TextField } from "../atoms";
import { EmptyState, PageHeader, SectionNav, Toast } from "../molecules";
import { DomainDnsPanel, UsersPanel } from "../organisms";
import { ContextLayout } from "../templates/ContextLayout";

const NOTES = {
  profile: "Your photo, name and preferences.",
  domains: "DNS for the domains you bought. Simple adds subdomains; advanced edits every record.",
  users: "Changes apply to everyone who signs in here.",
};

type Section = "profile" | "domains" | "users";

/** Account settings; the open section is in the URL, so a link can open Domains directly. */
export function GeneralSettingsPage({ section: requested }: { readonly section?: string }) {
  const section: Section = requested === "domains" || requested === "users" ? requested : "profile";
  // Replaced rather than pushed, so Back leaves settings instead of stepping through sections.
  const setSection = (id: Section) => navigate({ name: "general-settings", section: id }, { replace: true });
  const { account, saveProfile, changePassword: savePassword } = useAccount();
  const saved = { firstName: account?.firstName ?? "", lastName: account?.lastName ?? "" };
  const [names, setNames] = useState(saved);
  const [storedZone, setStoredZone] = useTimeZone();
  // "" follows the browser's zone.
  const [zone, setZone] = useState(storedZone ?? "");
  const [savingProfile, setSavingProfile] = useState(false);
  // The photo shown while its save is in flight; null shows the account's own.
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null);
  const [savingAvatar, setSavingAvatar] = useState(false);
  const avatar = avatarPreview ?? account?.avatar ?? "";
  const [passwords, setPasswords] = useState({ current: "", next: "", confirm: "" });
  const [passwordError, setPasswordError] = useState("");
  const [savingPassword, setSavingPassword] = useState(false);
  const [changePassword, setChangePassword] = useState(false);
  const [notice, setNotice] = useState("");
  const [avatarError, setAvatarError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
  // Only an admin may manage users; the API refuses anyone else, so the tab is not offered.
  const isAdmin = account?.role === "ADMIN";
  const namesChanged = names.firstName !== saved.firstName || names.lastName !== saved.lastName;
  const zoneChanged = zone !== (storedZone ?? "");

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
      // Only the browser keeps the zone for now, so it cannot fail once the name is saved.
      if (zoneChanged) setStoredZone(zone || null);
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

  async function submitPassword() {
    if (passwords.next.length < 8) return setPasswordError("Use at least 8 characters.");
    if (passwords.next.length > 72) return setPasswordError("Use at most 72 characters.");
    if (passwords.next !== passwords.confirm) return setPasswordError("The new passwords do not match.");
    setPasswordError("");
    setSavingPassword(true);
    try {
      if (!(await savePassword(passwords.current, passwords.next))) {
        setPasswordError("The current password is wrong.");
        return;
      }
      setPasswords({ current: "", next: "", confirm: "" });
      setChangePassword(false);
      setNotice("Password changed.");
    } catch {
      setPasswordError("Could not change the password. Please try again.");
    } finally {
      setSavingPassword(false);
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
        sections={[{ id: "profile", label: "Profile", Icon: UserRound }, { id: "domains", label: "Domains", Icon: Globe }, ...(isAdmin ? [{ id: "users", label: "Users", Icon: Users }] as const : [])]} />
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
                    <SettingRow title={<label htmlFor="time-zone">Time zone</label>} hint="New scheduled tasks start in this zone. Kept in this browser for now.">
                      <TimeZoneField value={zone} onChange={(next) => { setZone(next); setNotice(""); }} />
                    </SettingRow>
                    <div className="flex flex-wrap items-center justify-between gap-3 bg-zinc-50/80 px-5 py-3 sm:px-6 dark:bg-zinc-950/40">
                      <p className="text-xs text-zinc-500 dark:text-zinc-400">
                        {namesChanged || zoneChanged ? <span className="flex items-center gap-1.5 font-medium text-amber-700 dark:text-amber-400"><span aria-hidden className="size-1.5 rounded-full bg-amber-500" />Unsaved changes</span> : "Your photo saves on its own; the rest saves here."}
                      </p>
                      <div className="flex items-center gap-2">
                        {(namesChanged || zoneChanged) && !savingProfile && (
                          <Button label="Cancel" variant="secondary" onClick={() => { setNames(saved); setZone(storedZone ?? ""); }} />
                        )}
                        <Button label="Save changes" type="submit" disabled={!namesChanged && !zoneChanged} busy={savingProfile} />
                      </div>
                    </div>
                  </form>
                </div>
              </section>
              <section aria-label="Password" className="card overflow-hidden">
                <CardHeader title="Password" description="Change the password you use to sign in."
                  action={<label className="flex items-center gap-3">
                    <span id="change-password-label" className="text-sm font-medium max-sm:sr-only">Change password</span>
                    <button type="button" role="switch" aria-checked={changePassword} aria-labelledby="change-password-label" onClick={() => setChangePassword(!changePassword)} className={`inline-flex h-6 w-10 shrink-0 items-center rounded-full p-0.5 transition-colors ${changePassword ? "bg-zinc-900 dark:bg-white" : "bg-zinc-200 dark:bg-zinc-700"}`}>
                      <span className={`size-5 rounded-full shadow-sm transition-transform ${changePassword ? "translate-x-4 bg-white dark:bg-zinc-900" : "bg-white dark:bg-zinc-300"}`} />
                    </button>
                  </label>} />
                {changePassword && <form className="animate-fade-in" onSubmit={(event) => { event.preventDefault(); void submitPassword(); }}>
                  <div className="grid gap-4 px-5 py-5 sm:px-6 md:grid-cols-2">
                    <div className="md:col-span-2 md:max-w-[calc(50%-0.5rem)]">
                      <TextField id="current-password" label="Current password" type="password" autoComplete="current-password" required value={passwords.current} onChange={(event) => setPasswords({ ...passwords, current: event.target.value })} />
                    </div>
                    <TextField id="new-password" label="New password" type="password" autoComplete="new-password" required hint="8 to 72 characters." value={passwords.next} onChange={(event) => setPasswords({ ...passwords, next: event.target.value })} />
                    <TextField id="confirm-password" label="Confirm new password" type="password" autoComplete="new-password" required value={passwords.confirm} onChange={(event) => setPasswords({ ...passwords, confirm: event.target.value })} />
                    {passwordError && <p role="alert" className="text-sm text-rose-600 md:col-span-2 dark:text-rose-400">{passwordError}</p>}
                  </div>
                  <div className="flex justify-end gap-2 border-t border-zinc-200 bg-zinc-50/80 px-5 py-3 sm:px-6 dark:border-zinc-800 dark:bg-zinc-950/40">
                    <Button label="Change password" type="submit" busy={savingPassword} disabled={!passwords.current || !passwords.next || !passwords.confirm} />
                  </div>
                </form>}
              </section>
            </div>
            {section === "domains" && <DomainsSection />}
            {section === "users" && isAdmin && <UsersSection currentUserId={account?.id} />}
            <Toast message={notice} />
          </div>
      </div>
    </ContextLayout>
    </>
  );
}

/** Mounted only while its tab is open, so the domains load when someone looks at them. */
function DomainsSection() {
  const { owned, status } = useDomains();
  if (status === "loading") return <EmptyState busy title="Loading domains…" hint="Fetching the domains you own" />;
  if (status === "failed") return <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">Your domains could not be loaded.</p>;
  if (owned.length === 0) {
    return <EmptyState icon={Globe} title="No domains yet" hint="Buy one to manage its DNS here." action={{ label: "Find a domain", icon: Globe, onClick: () => navigate({ name: "domains" }) }} />;
  }
  return (
    <div className="space-y-6">
      <div className="flex justify-end">
        <Button label="Find another domain" icon={Search} variant="secondary" onClick={() => navigate({ name: "domains" })} />
      </div>
      {owned.map((domain) => <DomainDnsPanel key={domain.name} domain={domain.name} />)}
    </div>
  );
}

/** Mounted only while its tab is open, so the user list loads when someone looks at it. */
function UsersSection({ currentUserId }: { readonly currentUserId: string | undefined }) {
  const [sort, setSort] = useState<UserSort>("newest");
  const { users, total, hasMore, loading, loadingMore, error, removing, dismissError, loadMore, create, changeRole, remove } = useUsers(sort);
  return (
    <UsersPanel users={users} total={total} currentUserId={currentUserId} sort={sort} onSortChange={setSort}
      loading={loading} loadingMore={loadingMore} hasMore={hasMore} onLoadMore={loadMore} removing={removing}
      error={error} onDismissError={dismissError} onCreate={create} onRemove={remove} onChangeRole={changeRole} />
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

/** The zone picker, with a clock showing the time there so the choice can be checked. */
function TimeZoneField({ value, onChange }: { readonly value: string; readonly onChange: (zone: string) => void }) {
  const shown = value || BROWSER_TIME_ZONE;
  const [now, setNow] = useState(() => new Date());
  // The preview is a clock, so it keeps time while the page is open.
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(timer);
  }, []);
  // Offsets are worked out once per visit; recomputing hundreds on every tick is wasted work.
  const options = useMemo(() => TIME_ZONES.map((name) => ({ name, offset: zoneOffset(name) })), []);
  const preview = new Intl.DateTimeFormat("en-GB", { timeZone: shown, weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }).format(now);

  return (
    <div className="flex flex-col gap-1.5 text-sm">
      <Select id="time-zone" aria-describedby="time-zone-note" value={value} className="w-full" onChange={(event) => onChange(event.target.value)}>
        <option value="">Automatic — {BROWSER_TIME_ZONE} ({zoneOffset(BROWSER_TIME_ZONE)})</option>
        {options.map(({ name, offset }) => <option key={name} value={name}>{name.replaceAll("_", " ")} ({offset})</option>)}
      </Select>
      <p id="time-zone-note" className="flex items-start gap-1.5 text-xs text-zinc-500 dark:text-zinc-400">
        <Clock aria-hidden className="mt-px size-3.5 shrink-0" />
        <span>
          It is now <time aria-label="Current time in the chosen zone" className="font-medium text-zinc-900 tabular-nums dark:text-zinc-100">{preview}</time> there.
        </span>
      </p>
    </div>
  );
}
