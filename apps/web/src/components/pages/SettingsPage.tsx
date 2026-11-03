import { navigate } from "../../hooks/useRoute";
import { useSecrets } from "../../hooks/useSecrets";
import type { Workspace } from "../../hooks/useWorkspaces";
import { PageHeader } from "../molecules";
import { SecretsPanel } from "../organisms";

/** Secrets live apart from the pod, so they can be changed whatever state it is in. */
export function SettingsPage({ workspace }: { readonly workspace: Workspace }) {
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
      <div className="mx-auto w-full max-w-3xl py-6">
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
