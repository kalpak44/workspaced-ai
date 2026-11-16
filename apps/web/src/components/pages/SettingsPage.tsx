import { CalendarClock, Clock, FileText, Globe, KeyRound, Plug, Shield, Variable } from "lucide-react";
import { lazy, Suspense } from "react";
import { useMcpServers } from "../../hooks/useMcpServers";
import { navigate } from "../../hooks/useRoute";
import { useSecrets } from "../../hooks/useSecrets";
import type { Workspace } from "../../hooks/useWorkspaces";
import { McpPanel, SecretsPanel } from "../organisms";
import { DomainsPanel } from "../organisms/DomainsPanel";
import { FirewallPanel } from "../organisms/FirewallPanel";
import { SessionTtlPanel } from "../organisms/SessionTtlPanel";
import { VariablesPanel } from "../organisms/VariablesPanel";
import { Spinner } from "../atoms";
import { SectionNav } from "../molecules";
import { ContextLayout } from "../templates/ContextLayout";

interface Props {
  readonly workspace: Workspace;
  /** The open section's id, from the URL; the first section when unset or unknown. */
  readonly section?: string;
  /** A secret was added or removed; the running workspace sees it only after a restart. */
  /** Hears what a change made to the workspace, such as that it now needs a restart. */
  readonly onChanged: (changes: Partial<Workspace>) => void;
  readonly onRestart: () => void;
}

const SECTIONS = [
  { id: "mcp", label: "MCP servers", Icon: Plug },
  { id: "secrets", label: "Secrets", Icon: KeyRound },
  { id: "variables", label: "Variables", Icon: Variable },
  { id: "sessions", label: "Sessions", Icon: Clock },
  { id: "firewall", label: "Firewall", Icon: Shield },
  { id: "domains", label: "Domains", Icon: Globe },
  { id: "instructions", label: "Instructions", Icon: FileText },
  { id: "tasks", label: "Tasks", Icon: CalendarClock },
] as const;

type SectionId = (typeof SECTIONS)[number]["id"];

// The instructions editor is large, so the panels using it are only loaded once opened.
const InstructionsPanel = lazy(() =>
  import("../organisms/InstructionsPanel").then((panel) => ({ default: panel.InstructionsPanel })),
);
const TasksPanel = lazy(() => import("../organisms/TasksPanel").then((panel) => ({ default: panel.TasksPanel })));

/** Workspace integrations and secrets, using the same layout as general settings. */
export function SettingsPage({ workspace, section: requested, onChanged, onRestart }: Props) {
  const section: SectionId = SECTIONS.find((s) => s.id === requested)?.id ?? "mcp";
  // Replaced rather than pushed, so Back leaves settings instead of stepping through sections.
  const setSection = (id: SectionId) => navigate({ name: "settings", workspaceId: workspace.id, section: id }, { replace: true });
  const mcp = useMcpServers(workspace.id);
  const secrets = useSecrets(workspace.id, () => onChanged({ restartRequired: true }));

  return (
    <ContextLayout
      contained
      sidebar={
        <SectionNav label="Workspace settings sections" sections={SECTIONS} current={section} onSelect={setSection} />
      }
    >
      <div className="w-full">
        {section === "instructions" && (
          <Suspense fallback={<Spinner label="Loading instructions" className="size-5 text-zinc-400" />}>
            <InstructionsPanel key={workspace.id} workspaceId={workspace.id} />
          </Suspense>
        )}
        {section === "variables" && (
          <VariablesPanel key={workspace.id} workspace={workspace}
            restartDisabled={workspace.status === "INITIALIZING"} onRestart={onRestart} onChanged={onChanged} />
        )}
        {section === "tasks" && (
          <Suspense fallback={<Spinner label="Loading tasks" className="size-5 text-zinc-400" />}>
            <TasksPanel key={workspace.id} workspaceId={workspace.id} timeZone={workspace.timeZone} />
          </Suspense>
        )}
        {section === "firewall" && <FirewallPanel key={workspace.id} workspaceId={workspace.id} />}
        {section === "domains" && <DomainsPanel key={workspace.id} workspaceId={workspace.id} />}
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
