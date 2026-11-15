import { Camera, Globe, Search, UserRound, Users } from "lucide-react";
import { useRef, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { useDomains } from "../../hooks/useDomains";
import { navigate } from "../../hooks/useRoute";
import { type UserSort, useUsers } from "../../hooks/useUsers";
import { Avatar, Button, TextField } from "../atoms";
import { EmptyState, PageHeader, SectionNav, SettingsSection, Toast } from "../molecules";
import { DomainDnsPanel, UsersPanel } from "../organisms";
import { ContextLayout } from "../templates/ContextLayout";

const NOTES = {
  profile: "Your profile is saved to your account.",
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
  const saved = { firstName: account?.firstName ?? "", lastName: account?.lastName ?? "", avatar: account?.avatar ?? "" };
  const [profile, setProfile] = useState(saved);
  const [savingProfile, setSavingProfile] = useState(false);
  const [passwords, setPasswords] = useState({ current: "", next: "", confirm: "" });
  const [passwordError, setPasswordError] = useState("");
  const [savingPassword, setSavingPassword] = useState(false);
  const [changePassword, setChangePassword] = useState(false);
  const [notice, setNotice] = useState("");
  const [avatarError, setAvatarError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
  // Only an admin may manage users; the API refuses anyone else, so the tab is not offered.
  const isAdmin = account?.role === "ADMIN";
  const profileChanged = profile.firstName !== saved.firstName || profile.lastName !== saved.lastName || profile.avatar !== saved.avatar;

  async function submitProfile() {
    const trimmed = { ...profile, firstName: profile.firstName.trim(), lastName: profile.lastName.trim() };
    setSavingProfile(true);
    try {
      await saveProfile({ ...trimmed, avatar: trimmed.avatar || null });
      setProfile(trimmed);
      setNotice("Profile updated.");
    } catch {
      setNotice("Could not save the profile. Please try again.");
    } finally {
      setSavingProfile(false);
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
    reader.onload = () => {
      setProfile((previous) => ({ ...previous, avatar: String(reader.result) }));
      setAvatarError("");
      setNotice("");
    };
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
              <SettingsSection title="Profile" description="Your photo and name, all in one place.">
                <form className="space-y-6" onSubmit={(event) => { event.preventDefault(); void submitProfile(); }}>
                  <div className="flex flex-wrap items-center gap-4">
                    <Avatar src={profile.avatar || undefined} large />
                    <div className="space-y-2">
                      <div className="flex flex-wrap gap-2">
                        <Button label="Change avatar" icon={Camera} variant="secondary" onClick={() => fileInput.current?.click()} />
                        {profile.avatar && <Button label="Remove" variant="secondary" onClick={() => { setProfile((previous) => ({ ...previous, avatar: "" })); setNotice(""); }} />}
                      </div>
                      <p className="text-xs text-zinc-500 dark:text-zinc-400">JPG, PNG, WebP or GIF. Up to 2 MB.</p>
                      <input ref={fileInput} type="file" accept="image/jpeg,image/png,image/webp,image/gif" aria-label="Upload avatar" className="hidden" onChange={(event) => { void changeAvatar(event.target.files?.[0]); event.target.value = ""; }} />
                    </div>
                  </div>
                  {avatarError && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{avatarError}</p>}
                  <div className="grid gap-4 md:grid-cols-2">
                    <TextField id="first-name" label="First name" autoComplete="given-name" maxLength={100} value={profile.firstName} onChange={(event) => { setProfile({ ...profile, firstName: event.target.value }); setNotice(""); }} />
                    <TextField id="last-name" label="Last name" autoComplete="family-name" maxLength={100} value={profile.lastName} onChange={(event) => { setProfile({ ...profile, lastName: event.target.value }); setNotice(""); }} />
                  </div>
                  <div className="flex justify-end border-t border-zinc-200 pt-4 dark:border-zinc-800">
                    <Button label="Update profile" type="submit" disabled={!profileChanged} busy={savingProfile} />
                  </div>
                </form>
              </SettingsSection>
              <SettingsSection title="Password" description="Manage the password you use to sign in.">
                <div className="flex items-center justify-between gap-4">
                  <span id="change-password-label" className="text-sm font-medium">Change password</span>
                  <button type="button" role="switch" aria-checked={changePassword} aria-labelledby="change-password-label" onClick={() => setChangePassword(!changePassword)} className={`inline-flex h-6 w-10 shrink-0 items-center rounded-full p-0.5 transition-colors ${changePassword ? "bg-zinc-900 dark:bg-white" : "bg-zinc-200 dark:bg-zinc-700"}`}>
                    <span className={`size-5 rounded-full shadow-sm transition-transform ${changePassword ? "translate-x-4 bg-white dark:bg-zinc-900" : "bg-white dark:bg-zinc-300"}`} />
                  </button>
                </div>
                {changePassword && <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void submitPassword(); }}>
                  <TextField id="current-password" label="Current password" type="password" autoComplete="current-password" required value={passwords.current} onChange={(event) => setPasswords({ ...passwords, current: event.target.value })} />
                  <TextField id="new-password" label="New password" type="password" autoComplete="new-password" required hint="8 to 72 characters." value={passwords.next} onChange={(event) => setPasswords({ ...passwords, next: event.target.value })} />
                  <TextField id="confirm-password" label="Confirm new password" type="password" autoComplete="new-password" required value={passwords.confirm} onChange={(event) => setPasswords({ ...passwords, confirm: event.target.value })} />
                  {passwordError && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{passwordError}</p>}
                  <div className="flex justify-end border-t border-zinc-200 pt-4 dark:border-zinc-800">
                    <Button label="Change password" type="submit" busy={savingPassword} disabled={!passwords.current || !passwords.next || !passwords.confirm} />
                  </div>
                </form>}
              </SettingsSection>
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
