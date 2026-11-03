import { type ReactNode, useState } from "react";
import { useTheme } from "../../hooks/useTheme";
import { DEFAULT_NAME, type Workspace, useWorkspaces } from "../../hooks/useWorkspaces";
import { EmptyState, ErrorBanner } from "../molecules";
import { type Section, Sidebar, TopBar } from "../organisms";
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
  create: () => void,
): View {
  const newWorkspace = { label: DEFAULT_NAME, onClick: create };
  if (section !== "workspaces") {
    return {
      title: section === "knowledge" ? "Knowledge" : "My Files",
      body: <EmptyState {...SECTIONS[section]} />,
    };
  }
  if (loading) {
    return {
      title: "Workspaces",
      body: <EmptyState title="Loading workspaces…" hint="Fetching them from the API" />,
    };
  }
  if (!selected) {
    return {
      title: "Workspaces",
      body: <EmptyState title="No workspaces" hint="Create one to get started" action={newWorkspace} />,
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
  const { workspaces, selected, loading, error, dismissError, select, create, rename, remove } =
    useWorkspaces();
  const [section, setSection] = useState<Section>("workspaces");
  const view = viewFor(section, loading, selected, () => create());

  return (
    <AppLayout
      header={<TopBar theme={theme} onThemeChange={setTheme} />}
      sidebar={
        <Sidebar
          workspaces={workspaces}
          selectedId={selected?.id ?? null}
          section={section}
          onSection={setSection}
          onSelect={select}
          onCreate={create}
          onRename={rename}
          onDelete={remove}
        />
      }
      title={view.title}
      notice={error && <ErrorBanner message={error} onDismiss={dismissError} />}
    >
      {view.body}
    </AppLayout>
  );
}
