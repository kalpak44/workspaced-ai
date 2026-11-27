import { Crown, LogOut, type LucideIcon, Mail, Trash2, UserPlus, Users } from "lucide-react";
import { type SubmitEvent, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { type Collaborator, type CollaboratorStatus, useCollaborators } from "../../hooks/useCollaborators";
import type { Workspace } from "../../hooks/useWorkspaces";
import { timeAgo } from "../../lib/time";
import { Button, IconButton, Spinner, TextField } from "../atoms";
import { ConfirmDeleteDialog, ErrorBanner, SettingsSection } from "../molecules";

interface Props {
  readonly workspace: Workspace;
  /** The signed-in collaborator leaves, which takes the workspace off their list. */
  readonly onLeave: () => void;
}

const STATUS: Record<CollaboratorStatus, { readonly label: string; readonly tone: string; readonly dot: string }> = {
  OWNER: { label: "Owner", tone: "bg-zinc-100 text-zinc-700 ring-zinc-500/15 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-white/10", dot: "bg-zinc-500" },
  APPLIED: { label: "Applied", tone: "bg-emerald-50 text-emerald-700 ring-emerald-600/15 dark:bg-emerald-950/40 dark:text-emerald-400 dark:ring-emerald-400/20", dot: "bg-emerald-500" },
  PENDING: { label: "Pending", tone: "bg-amber-50 text-amber-700 ring-amber-600/15 dark:bg-amber-950/40 dark:text-amber-400 dark:ring-amber-400/20", dot: "animate-pulse bg-amber-500" },
};

const LEFT = new Intl.RelativeTimeFormat("en", { numeric: "always" });

/**
 * "expires in 18 hours", or minutes in its last hour. Never "tomorrow": an invitation lasts a day,
 * so a day is too coarse to say how long is left.
 */
function expiresIn(iso: string, now: number = Date.now()): string {
  const minutes = Math.max(1, Math.round((Date.parse(iso) - now) / 60_000));
  const left = minutes < 60 ? LEFT.format(minutes, "minute") : LEFT.format(Math.round(minutes / 60), "hour");
  return `expires ${left}`;
}

const ICONS: Record<Collaborator["status"], LucideIcon> = { OWNER: Crown, PENDING: Mail, APPLIED: Users };

function detail(collaborator: Collaborator): string {
  const by = collaborator.invitedBy ? ` by ${collaborator.invitedBy}` : "";
  if (collaborator.status === "OWNER") return "Created the workspace and pays for it";
  if (collaborator.status === "PENDING") {
    const sent = collaborator.since ? " " + timeAgo(collaborator.since) : "";
    const expiry = collaborator.expiresAt ? " · " + expiresIn(collaborator.expiresAt) : "";
    return `Sent${by}${sent}${expiry}`;
  }
  const joined = collaborator.since ? " · joined " + timeAgo(collaborator.since) : "";
  return `Invited${by}${joined}`;
}

/**
 * Who is on a workspace, with an invite-by-email form. Everyone may invite; only the owner removes
 * people or withdraws invitations, and a collaborator may leave.
 */
export function CollaboratorsPanel({ workspace, onLeave }: Props) {
  const { account } = useAccount();
  const { collaborators, loading, error, dismissError, removing, invite, remove } = useCollaborators(workspace.id);
  const [email, setEmail] = useState("");
  const [emailError, setEmailError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [confirming, setConfirming] = useState<Collaborator | null>(null);
  const owner = !!account && workspace.ownerId === account.id;

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    setPending(true);
    setSentTo(null);
    try {
      const result = await invite(email);
      if (result.ok) {
        setSentTo(email.trim());
        setEmail("");
      } else {
        setEmailError(result.message);
      }
    } finally {
      setPending(false);
    }
  };

  const action = (collaborator: Collaborator) => {
    const id = collaborator.invitationId ?? collaborator.userId ?? "";
    if (removing.has(id)) return <Spinner label="Removing" className="size-4 text-zinc-400" />;
    if (collaborator.status === "APPLIED" && collaborator.userId === account?.id) {
      return <Button label="Leave" variant="secondary" icon={LogOut} onClick={onLeave} />;
    }
    if (!owner || collaborator.status === "OWNER") return null;
    const label = collaborator.status === "PENDING" ? "Revoke invitation" : `Remove ${collaborator.email ?? "collaborator"}`;
    return <IconButton label={label} title={label} icon={Trash2} onClick={() => setConfirming(collaborator)} />;
  };

  return (
    <SettingsSection
      title="Collaborators"
      description="Invite up to 5 people by email to work in this workspace with you; an open invitation holds a place until it is accepted. They can do everything but delete it, and it keeps running on the owner's AI connector. Everyone shares the workspace's computer, so invite only people you trust with its files, secrets and chats: the app lists only the sessions each person started, but anyone can read the rest from a terminal. Only the owner can remove people."
    >
      {error && <ErrorBanner message={error} onDismiss={dismissError} />}
      <form onSubmit={submit} className="flex flex-col gap-3 rounded-md bg-zinc-50 p-4 sm:flex-row sm:items-start dark:bg-zinc-900/60">
        <div className="min-w-0 flex-1">
          <TextField
            id="collaborator-email"
            surface="solid"
            label="Email address"
            type="email"
            placeholder="ana@example.com"
            autoComplete="off"
            spellCheck={false}
            maxLength={254}
            value={email}
            disabled={pending}
            error={emailError}
            hint={sentTo ? `Invitation sent to ${sentTo}. Its link works once, for 24 hours.` : "They get a link that works once, for 24 hours."}
            onChange={(event) => {
              setEmail(event.currentTarget.value);
              setEmailError(null);
            }}
          />
        </div>
        <div className="sm:mt-6">
          <Button label="Invite" type="submit" icon={UserPlus} busy={pending} disabled={!email.trim()} />
        </div>
      </form>
      {loading ? (
        <Spinner label="Loading collaborators" className="size-5 text-zinc-400" />
      ) : (
        <ul aria-label="Collaborators" className="divide-y divide-zinc-200 overflow-hidden rounded-lg border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
          {collaborators.map((collaborator) => {
            const status = STATUS[collaborator.status];
            const Icon = ICONS[collaborator.status];
            const name = collaborator.email ?? (collaborator.status === "PENDING" ? "Invitation by email" : "Unknown user");
            return (
              <li key={collaborator.invitationId ?? collaborator.userId} className="flex min-w-0 items-center gap-3 px-3 py-2.5 transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-800/40">
                <span aria-hidden className="flex size-8 shrink-0 items-center justify-center rounded-full bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400">
                  <Icon className="size-4" />
                </span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="flex min-w-0 items-center gap-2">
                    <span className="truncate text-sm font-medium">{name}</span>
                    {collaborator.userId === account?.id && <span className="shrink-0 text-xs text-zinc-500 dark:text-zinc-400">(you)</span>}
                  </span>
                  <span className="truncate text-xs text-zinc-500 dark:text-zinc-400">{detail(collaborator)}</span>
                </span>
                <span className={`inline-flex shrink-0 items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${status.tone}`}>
                  <span aria-hidden className={`size-1.5 rounded-full ${status.dot}`} />{status.label}
                </span>
                <span className="flex shrink-0 justify-end">{action(collaborator)}</span>
              </li>
            );
          })}
        </ul>
      )}
      {confirming && (
        <ConfirmDeleteDialog
          title={confirming.status === "PENDING" ? "Revoke invitation" : "Remove collaborator"}
          description={workspace.name}
          confirmLabel={confirming.status === "PENDING" ? "Revoke" : "Remove"}
          busy={removing.has(confirming.invitationId ?? confirming.userId ?? "")}
          onClose={() => setConfirming(null)}
          onConfirm={() => { void remove(confirming).then(() => setConfirming(null)); }}
        >
          {confirming.status === "PENDING"
            ? "Its link stops working at once. You can invite the same address again."
            : `${confirming.email ?? "They"} will lose access to ${workspace.name} at once, and their open terminals close. The chat sessions they started stay in the workspace. Anything they copied while they had access, such as secrets, stays with them, so change those secrets if you need to.`}
        </ConfirmDeleteDialog>
      )}
    </SettingsSection>
  );
}
