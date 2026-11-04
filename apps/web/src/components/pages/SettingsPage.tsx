import { useMcpServers } from "../../hooks/useMcpServers";
import { navigate } from "../../hooks/useRoute";
import { useSecrets } from "../../hooks/useSecrets";
import type { Workspace } from "../../hooks/useWorkspaces";
import { PageHeader } from "../molecules";
import { McpPanel, SecretsPanel } from "../organisms";

interface Props {
  readonly workspace: Workspace;

}

/** Workspace integrations and environment secrets. */
export function SettingsPage({ workspace }: Props) {
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
