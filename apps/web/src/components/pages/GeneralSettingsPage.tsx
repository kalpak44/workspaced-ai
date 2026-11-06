import { ArrowLeft, Camera, Sparkles, UserRound } from "lucide-react";
import { useRef, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { useGeneralSettings } from "../../hooks/useGeneralSettings";
import { navigate } from "../../hooks/useRoute";
import { Avatar, Button, TextField } from "../atoms";
import { PageHeader, SettingsSection } from "../molecules";
import { InstructionsEditor } from "../molecules/InstructionsEditor";
import { ContextLayout } from "../templates/ContextLayout";

export function GeneralSettingsPage() {
  const { settings, update } = useGeneralSettings();
  const [section, setSection] = useState<"profile" | "ai">("profile");
  const { account, saveProfile, changePassword: savePassword } = useAccount();
  const saved = { firstName: account?.firstName ?? "", lastName: account?.lastName ?? "", avatar: account?.avatar ?? "" };
  const [profile, setProfile] = useState(saved);
  const [savingProfile, setSavingProfile] = useState(false);
  const [passwords, setPasswords] = useState({ current: "", next: "", confirm: "" });
  const [passwordError, setPasswordError] = useState("");
  const [savingPassword, setSavingPassword] = useState(false);
  const [instructions, setInstructions] = useState(settings.instructions);
  const [changePassword, setChangePassword] = useState(false);
  const [notice, setNotice] = useState("");
  const [avatarError, setAvatarError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
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
    <ContextLayout sidebar={<>
        <div className="flex h-14 shrink-0 items-center border-b border-zinc-200 px-4 dark:border-zinc-800">
          <button type="button" onClick={() => navigate({ name: "home" })} className="flex items-center gap-2 rounded-md text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white">
            <ArrowLeft aria-hidden className="size-4" />Workspaces
          </button>
        </div>
          <nav aria-label="Settings sections" className="flex gap-1 p-3 sm:flex-col">
            {([{ id: "profile", label: "Profile", Icon: UserRound }, { id: "ai", label: "General AI", Icon: Sparkles }] as const).map(({ id, label, Icon }) => (
              <button key={id} type="button" aria-current={section === id ? "page" : undefined}
                onClick={() => { setSection(id); setNotice(""); }}
                className={`flex items-center gap-2 rounded-md px-3 py-2 text-left text-sm font-medium ${section === id ? "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100" : "text-zinc-500 hover:bg-zinc-50 dark:text-zinc-400 dark:hover:bg-zinc-900"}`}>
                <Icon aria-hidden className="size-4" />{label}
              </button>
            ))}
          </nav>
      </>}>
      <PageHeader title="General settings" />
      <div className="w-full px-4 py-6 sm:px-6 sm:py-8">
        <p className="mb-6 text-xs text-zinc-500 dark:text-zinc-400">Your profile is saved to your account. AI instructions are saved only in this browser and are not applied to agents yet.</p>
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
            <div hidden={section !== "ai"}>
              <SettingsSection title="General AI instructions" description={<>Define your preferences, working style, and rules for your general <code className="font-mono text-xs">AGENTS.md</code>.</>}>
                <InstructionsEditor value={instructions} onChange={(value) => { setInstructions(value); setNotice(""); }} />
                <div className="flex items-center justify-between gap-4 border-t border-zinc-200 pt-4 dark:border-zinc-800">
                  <p className="text-xs text-zinc-500 dark:text-zinc-400">{instructions.length.toLocaleString()} characters</p>
                  <Button label="Save instructions" disabled={instructions === settings.instructions} onClick={() => setNotice(update({ instructions }) ? "Instructions saved in this browser." : "Could not save. Your browser storage may be full or unavailable.")} />
                </div>
              </SettingsSection>
            </div>
            <p role="status" className="mt-4 min-h-5 text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>
          </div>
      </div>
    </ContextLayout>
  );
}
