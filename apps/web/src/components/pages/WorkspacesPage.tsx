import { ArrowLeft, Settings } from "lucide-react";
import { type ReactNode, useState } from "react";
import { useModelProviders } from "../../hooks/useModelProviders";
import { useSecrets } from "../../hooks/useSecrets";
import { useTheme } from "../../hooks/useTheme";
import { DEFAULT_NAME, type Workspace, useWorkspaces } from "../../hooks/useWorkspaces";
import { IconButton } from "../atoms";
import { EmptyState, ErrorBanner } from "../molecules";
import {
  ChatPanel,
  CreateWorkspaceDialog,
  SecretsPanel,
  type Section,
  Sidebar,
  TopBar,
} from "../organisms";
import { AppLayout } from "../templates";

const SECTIONS: Record<Exclude<Section, "workspaces">, { title: string; hint: string }> = {
  knowledge: { title: "No knowledge yet", hint: "Documents your agents can draw on will appear here" },
  files: { title: "No files yet", hint: "Files you upload will appear here" },
};

interface View {
  readonly title: string;
  readonly body: ReactNode;
  readonly actions?: ReactNode;
}

interface Handlers {
  readonly openNew: () => void;
  readonly openSettings: () => void;
  readonly closeSettings: () => void;
  readonly settings: ReactNode;
}

/** The only place that decides what the main area shows. */
function viewFor(
  section: Section,
  loading: boolean,
  selected: Workspace | null,
  deleting: ReadonlySet<string>,
  settingsOpen: boolean,
  { openNew, openSettings, closeSettings, settings }: Handlers,
): View {
  if (section !== "workspaces") {
    return {
      title: section === "knowledge" ? "Knowledge" : "My Files",
      body: <EmptyState {...SECTIONS[section]} />,
    };
  }
  if (loading) {
    return {
      title: "Workspaces",
      body: <EmptyState busy title="Loading workspaces…" hint="Fetching them from the API" />,
    };
  }
  if (!selected) {
    return {
      title: "Workspaces",
      body: (
        <EmptyState
          title="No workspaces"
          hint="Create one to get started"
          action={{ label: DEFAULT_NAME, onClick: openNew }}
        />
      ),
    };
  }
  // Ahead of the status: the pod is being removed on purpose, so "unavailable" would mislead.
  if (deleting.has(selected.id)) {
    return {
      title: selected.name,
      body: <EmptyState busy title="Deleting workspace" hint="Stopping its environment…" />,
    };
  }
  // Secrets live apart from the pod, so they can be changed whatever state it is in.
  if (settingsOpen) {
    return {
      title: `${selected.name} · Settings`,
      actions: <IconButton label="Back to chat" icon={ArrowLeft} onClick={closeSettings} />,
      body: settings,
    };
  }
  const settingsButton = (
    <IconButton label="Workspace settings" icon={Settings} onClick={openSettings} />
  );
  switch (selected.status) {
    case "INITIALIZING":
      return {
        title: selected.name,
        actions: settingsButton,
        body: <EmptyState busy title="Initializing workspace" hint="Starting its environment…" />,
      };
    case "FAILED":
      return {
        title: selected.name,
        actions: settingsButton,
        body: (
          <EmptyState
            title="Workspace unavailable"
            hint="Its environment stopped or could not start. Delete it and create a new one."
          />
        ),
      };
    default:
      return { title: selected.name, actions: settingsButton, body: <ChatPanel /> };
  }
}

export function WorkspacesPage() {
  const [theme, setTheme] = useTheme();
  const {
    workspaces,
    selected,
    loading,
    deleting,
    error,
    dismissError,
    select,
    create,
    suggestName,
    rename,
    remove,
  } = useWorkspaces();
  const providers = useModelProviders();
  const [section, setSection] = useState<Section>("workspaces");
  // The name the create dialog opens with; null while it is closed.
  const [newName, setNewName] = useState<string | null>(null);
  // Which workspace's settings are open. Keyed by id, so a deleted one cannot leave them open.
  const [settingsFor, setSettingsFor] = useState<string | null>(null);
  const settingsOpen = section === "workspaces" && !!selected && settingsFor === selected.id;
  const secrets = useSecrets(settingsOpen ? selected.id : null);
  const openNew = (base?: string) => setNewName(suggestName(base));

  const settings = (
    <SecretsPanel
      secrets={secrets.secrets}
      loading={secrets.loading}
      removing={secrets.removing}
      error={secrets.error}
      onDismissError={secrets.dismissError}
      onAdd={secrets.add}
      onRemove={secrets.remove}
    />
  );
  const view = viewFor(section, loading, selected, deleting, settingsOpen, {
    openNew: () => openNew(),
    openSettings: () => setSettingsFor(selected?.id ?? null),
    closeSettings: () => setSettingsFor(null),
    settings,
  });

  return (
    <AppLayout
      header={<TopBar theme={theme} onThemeChange={setTheme} />}
      sidebar={
        <Sidebar
          workspaces={workspaces}
          selectedId={selected?.id ?? null}
          deleting={deleting}
          section={section}
          onSection={setSection}
          onSelect={(id) => {
            select(id);
            setSettingsFor(null);
          }}
          onNew={openNew}
          onRename={rename}
          onDelete={remove}
        />
      }
      title={view.title}
      actions={view.actions}
      notice={error && <ErrorBanner message={error} onDismiss={dismissError} />}
    >
      {view.body}
      {newName !== null && (
        <CreateWorkspaceDialog
          initialName={newName}
          providers={providers.providers}
          providersStatus={providers.status}
          onCreate={create}
          onClose={() => setNewName(null)}
        />
      )}
    </AppLayout>
  );
}
