import { type ReactNode, useState } from "react";
import { useTheme } from "../../hooks/useTheme";
import { DEFAULT_NAME, type Workspace, useWorkspaces } from "../../hooks/useWorkspaces";
import { EmptyState, ErrorBanner } from "../molecules";
import { CreateWorkspaceDialog, type Section, Sidebar, TopBar } from "../organisms";
import { AppLayout } from "../templates";

const SECTIONS: Record<Exclude<Section, "workspaces">, { title: string; hint: string }> = {
  knowledge: { title: "No knowledge yet", hint: "Documents your agents can draw on will appear here" },
  files: { title: "No files yet", hint: "Files you upload will appear here" },
};

interface View {
  readonly title: string;
  readonly body: ReactNode;
}

/** The only place that decides what the main area shows. */
function viewFor(
  section: Section,
  loading: boolean,
  selected: Workspace | null,
  deleting: ReadonlySet<string>,
  openNew: () => void,
): View {
  const newWorkspace = { label: DEFAULT_NAME, onClick: openNew };
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
      body: <EmptyState title="No workspaces" hint="Create one to get started" action={newWorkspace} />,
    };
  }
  // Ahead of the status: the pod is being removed on purpose, so "unavailable" would mislead.
  if (deleting.has(selected.id)) {
    return {
      title: selected.name,
      body: <EmptyState busy title="Deleting workspace" hint="Stopping its environment…" />,
    };
  }
  switch (selected.status) {
    case "INITIALIZING":
      return {
        title: selected.name,
        body: <EmptyState busy title="Initializing workspace" hint="Starting its environment…" />,
      };
    case "FAILED":
      return {
        title: selected.name,
        body: (
          <EmptyState
            title="Workspace unavailable"
            hint="Its environment stopped or could not start. Delete it and create a new one."
          />
        ),
      };
    default:
      return {
        title: selected.name,
        body: <EmptyState title="Empty workspace" hint="Start automating here" action={newWorkspace} />,
      };
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
  const [section, setSection] = useState<Section>("workspaces");
  // The name the create dialog opens with; null while it is closed.
  const [newName, setNewName] = useState<string | null>(null);
  const openNew = (base?: string) => setNewName(suggestName(base));
  const view = viewFor(section, loading, selected, deleting, () => openNew());

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
          onSelect={select}
          onNew={openNew}
          onRename={rename}
          onDelete={remove}
        />
      }
      title={view.title}
      notice={error && <ErrorBanner message={error} onDismiss={dismissError} />}
    >
      {view.body}
      {newName !== null && (
        <CreateWorkspaceDialog
          initialName={newName}
          onCreate={create}
          onClose={() => setNewName(null)}
        />
      )}
    </AppLayout>
  );
}
