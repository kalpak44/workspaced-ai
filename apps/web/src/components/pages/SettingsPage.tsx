import { ArrowLeft, CalendarClock, Clock, FileText, Globe, KeyRound, Plug, Variable } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { useMcpServers } from "../../hooks/useMcpServers";
import { goBack } from "../../hooks/useRoute";
import { useSecrets } from "../../hooks/useSecrets";
import type { Workspace } from "../../hooks/useWorkspaces";
import { McpPanel, SecretsPanel } from "../organisms";
import { SessionTtlPanel } from "../organisms/SessionTtlPanel";
import { VariablesPanel } from "../organisms/VariablesPanel";
import { WebServerPanel } from "../organisms/WebServerPanel";
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
  { id: "variables", label: "Variables", Icon: Variable },
  { id: "sessions", label: "Sessions", Icon: Clock },
  { id: "web-server", label: "Web server", Icon: Globe },
  { id: "instructions", label: "Instructions", Icon: FileText },
  { id: "tasks", label: "Tasks", Icon: CalendarClock },
] as const;

// The instructions editor is large, so the panels using it are only loaded once opened.
const InstructionsPanel = lazy(() =>
  import("../organisms/InstructionsPanel").then((panel) => ({ default: panel.InstructionsPanel })),
);
const TasksPanel = lazy(() => import("../organisms/TasksPanel").then((panel) => ({ default: panel.TasksPanel })));

/** Workspace integrations and secrets, using the same layout as general settings. */
export function SettingsPage({ workspace, onSecretsChanged, onRestart }: Props) {
  const [section, setSection] = useState<(typeof SECTIONS)[number]["id"]>("mcp");
  const mcp = useMcpServers(workspace.id);
  const secrets = useSecrets(workspace.id, onSecretsChanged);
  const back = () => goBack({ name: "workspace", workspaceId: workspace.id });

  return (
    <ContextLayout
      sidebar={
        <>
          <div className="flex h-14 shrink-0 items-center border-b border-zinc-200 px-4 dark:border-zinc-800">
            <button
              type="button"
              onClick={back}
              className="flex min-w-0 items-center gap-2 rounded-md text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
            >
              <ArrowLeft aria-hidden className="size-4 shrink-0" />
              Back
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
        {section === "variables" && (
          <VariablesPanel key={workspace.id} workspaceId={workspace.id}
            restartDisabled={workspace.status === "INITIALIZING"} onRestart={onRestart} />
        )}
        {section === "tasks" && (
          <Suspense fallback={<Spinner label="Loading tasks" className="size-5 text-zinc-400" />}>
            <TasksPanel key={workspace.id} workspaceId={workspace.id} />
          </Suspense>
        )}
        {section === "web-server" && <WebServerPanel key={workspace.id} workspaceId={workspace.id} />}
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
