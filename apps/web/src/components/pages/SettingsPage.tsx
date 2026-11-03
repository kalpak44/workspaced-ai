import { useMcpServers } from "../../hooks/useMcpServers";
import { useModelSettings } from "../../hooks/useModelSettings";
import { navigate } from "../../hooks/useRoute";
import { useSecrets } from "../../hooks/useSecrets";
import type { Workspace } from "../../hooks/useWorkspaces";
import { PageHeader } from "../molecules";
import { McpPanel, ModelPanel, SecretsPanel } from "../organisms";

interface Props {
  readonly workspace: Workspace;
  /** Hears a change the API accepted, so the rest of the app sees it without a reload. */
  readonly onModelChanged: (model: string | null) => void;
}

/**
 * Model, MCP servers and secrets, in that order: the first two shape every reply, and secrets
 * are what an MCP header refers to, so they sit right below it.
 */
export function SettingsPage({ workspace, onModelChanged }: Props) {
  const running = workspace.status === "READY";
  const models = useModelSettings(running ? workspace.id : null, workspace.model, onModelChanged);
  const mcp = useMcpServers(workspace.id);
  const secrets = useSecrets(workspace.id);
  return (
    <>
      <PageHeader
        title="Settings"
        crumbs={[
          {
            label: workspace.name,
            onClick: () => navigate({ name: "workspace", workspaceId: workspace.id }),
          },
        ]}
      />
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-6 py-8">
        <ModelPanel
          catalogue={models.catalogue}
          current={workspace.model}
          available={running}
          loading={models.loading}
          saving={models.saving}
          error={models.error}
          onDismissError={models.dismissError}
          onChoose={models.choose}
        />
        <McpPanel
          servers={mcp.servers}
          loading={mcp.loading}
          removing={mcp.removing}
          error={mcp.error}
          onDismissError={mcp.dismissError}
          onAdd={mcp.add}
          onRemove={mcp.remove}
        />
        <SecretsPanel
          secrets={secrets.secrets}
          loading={secrets.loading}
          removing={secrets.removing}
          error={secrets.error}
          onDismissError={secrets.dismissError}
          onAdd={secrets.add}
          onRemove={secrets.remove}
        />
      </div>
    </>
  );
}
