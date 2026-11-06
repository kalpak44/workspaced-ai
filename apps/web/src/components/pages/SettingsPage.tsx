import { ArrowLeft, Clock, FileText, KeyRound, Plug } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { useMcpServers } from "../../hooks/useMcpServers";
import { navigate } from "../../hooks/useRoute";
import { useSecrets } from "../../hooks/useSecrets";
import type { Workspace } from "../../hooks/useWorkspaces";
import { McpPanel, SecretsPanel } from "../organisms";
import { SessionTtlPanel } from "../organisms/SessionTtlPanel";
import { Spinner } from "../atoms";
import { ContextLayout } from "../templates/ContextLayout";

interface Props {
  readonly workspace: Workspace;
  /** A secret was added or removed; the running workspace sees it only after a restart. */
  readonly onSecretsChanged: () => void;
  readonly onRestart: () => void;
}

const SECTIONS = [
  { id: "mcp", label: "MCP servers", Icon: Plug },
  { id: "secrets", label: "Secrets", Icon: KeyRound },
  { id: "sessions", label: "Sessions", Icon: Clock },
  { id: "instructions", label: "Instructions", Icon: FileText },
] as const;

// The instructions editor is large, so it is only loaded once its section is opened.
const InstructionsPanel = lazy(() =>
  import("../organisms/InstructionsPanel").then((panel) => ({ default: panel.InstructionsPanel })),
);

/** Workspace integrations and secrets, using the same layout as general settings. */
export function SettingsPage({ workspace, onSecretsChanged, onRestart }: Props) {
  const [section, setSection] = useState<(typeof SECTIONS)[number]["id"]>("mcp");
  const mcp = useMcpServers(workspace.id);
  const secrets = useSecrets(workspace.id, onSecretsChanged);
  const back = () => navigate({ name: "workspace", workspaceId: workspace.id });

  return (
    <ContextLayout
      sidebar={
        <>
          <div className="flex h-14 shrink-0 items-center border-b border-zinc-200 px-4 dark:border-zinc-800">
            <button
              type="button"
              aria-label="Back to workspace"
              onClick={back}
              className="flex min-w-0 items-center gap-2 rounded-md text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
            >
              <ArrowLeft aria-hidden className="size-4 shrink-0" />
              <span className="truncate">{workspace.name}</span>
            </button>
          </div>
          <nav aria-label="Workspace settings sections" className="flex flex-wrap gap-1 p-3 sm:flex-col">
            {SECTIONS.map(({ id, label, Icon }) => (
              <button
                key={id}
                type="button"
                aria-current={section === id ? "page" : undefined}
                onClick={() => setSection(id)}
                className={`flex items-center gap-2 rounded-md px-3 py-2 text-left text-sm font-medium ${
                  section === id
                    ? "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100"
                    : "text-zinc-500 hover:bg-zinc-50 dark:text-zinc-400 dark:hover:bg-zinc-900"
                }`}
              >
                <Icon aria-hidden className="size-4" />
                {label}
              </button>
            ))}
          </nav>
        </>
      }
    >
      <div className="w-full px-4 py-6 sm:px-6 sm:py-8">
        {section === "instructions" && (
          <Suspense fallback={<Spinner label="Loading instructions" className="size-5 text-zinc-400" />}>
            <InstructionsPanel key={workspace.id} workspaceId={workspace.id} />
          </Suspense>
        )}
        <div hidden={section !== "sessions"}>
          <SessionTtlPanel key={workspace.id} workspaceId={workspace.id} />
        </div>
        <div hidden={section !== "mcp"}>
          <McpPanel
            servers={mcp.servers}
            loading={mcp.loading}
            removing={mcp.removing}
            error={mcp.error}
            onDismissError={mcp.dismissError}
            onAdd={mcp.add}
            onRemove={mcp.remove}
          />
        </div>
        <div hidden={section !== "secrets"}>
          <SecretsPanel
            secrets={secrets.secrets}
            loading={secrets.loading}
            removing={secrets.removing}
            error={secrets.error}
            onDismissError={secrets.dismissError}
            onAdd={secrets.add}
            onRemove={secrets.remove}
            restartRequired={workspace.restartRequired}
            restartDisabled={workspace.status === "INITIALIZING"}
            onRestart={onRestart}
          />
        </div>
      </div>
    </ContextLayout>
  );
}
