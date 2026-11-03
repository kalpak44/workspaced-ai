import { useCallback, useEffect, useState } from "react";
import { useModelProviders } from "../../hooks/useModelProviders";
import { navigate, useRoute } from "../../hooks/useRoute";
import { useTheme } from "../../hooks/useTheme";
import { DEFAULT_NAME, useWorkspaces } from "../../hooks/useWorkspaces";
import { EmptyState, ErrorBanner, PageHeader } from "../molecules";
import { CreateWorkspaceDialog, DeleteWorkspaceDialog, Sidebar, TopBar } from "../organisms";
import { AppLayout } from "../templates";
import { SessionPage } from "./SessionPage";
import { SettingsPage } from "./SettingsPage";
import { TerminalPage } from "./TerminalPage";
import { WorkspacePage } from "./WorkspacePage";

const APP = "workspaced-ai";

/**
 * The frame every page shares — the bar, the sidebar of workspaces, the create dialog — and
 * the one place that turns the URL into the page beside them.
 */
export function WorkspacesPage() {
  const [theme, setTheme] = useTheme();
  const route = useRoute();
  const {
    workspaces,
    loading,
    deleting,
    error,
    dismissError,
    create,
    suggestName,
    rename,
    remove,
    patch,
  } = useWorkspaces();
  const providers = useModelProviders();
  // The name the create dialog opens with; null while it is closed.
  const [newName, setNewName] = useState<string | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [pageTitle, setPageTitle] = useState<string | null>(null);
  const openNew = () => setNewName(suggestName());

  const workspaceId = "workspaceId" in route ? route.workspaceId : null;
  const workspace = workspaces.find((w) => w.id === workspaceId) ?? null;
  const deleteTarget = workspaces.find((w) => w.id === deleteId) ?? null;

  // Home is the first workspace, and a workspace that is gone — deleted here or in another
  // tab, or a stale link — falls back to home. Both replace the entry, so Back skips them.
  useEffect(() => {
    if (loading) {
      return;
    }
    const first = workspaces.at(0);
    if (route.name === "home" && first) {
      navigate({ name: "workspace", workspaceId: first.id }, { replace: true });
    } else if (workspaceId !== null && !workspace) {
      navigate({ name: "home" }, { replace: true });
    }
  }, [loading, route.name, workspaces, workspaceId, workspace]);

  const onSessionTitle = useCallback((title: string) => setPageTitle(title), []);

  let page;
  let tabTitle: string;
  if (loading || (workspaceId !== null && !workspace)) {
    tabTitle = "Workspaces";
    page = (
      <>
        <PageHeader title="Workspaces" />
        <EmptyState busy title="Loading workspaces…" hint="Fetching them from the API" />
      </>
    );
  } else if (!workspace) {
    tabTitle = "Workspaces";
    page = (
      <>
        <PageHeader title="Workspaces" />
        <EmptyState
          title="No workspaces"
          hint="A workspace is a sandbox with its own agent. Create one to get started."
          action={{ label: DEFAULT_NAME, onClick: openNew }}
        />
      </>
    );
  } else if (route.name === "settings") {
    tabTitle = `Settings · ${workspace.name}`;
    const id = workspace.id;
    page = (
      <SettingsPage workspace={workspace} onModelChanged={(model) => patch(id, { model })} />
    );
  } else if (route.name === "terminal") {
    tabTitle = `Terminal · ${workspace.name}`;
    page = <TerminalPage workspace={workspace} deleting={deleting.has(workspace.id)} />;
  } else if (route.name === "session") {
    tabTitle = `${pageTitle ?? "Session"} · ${workspace.name}`;
    page = (
      <SessionPage
        key={route.sessionId}
        workspace={workspace}
        sessionId={route.sessionId}
        deleting={deleting.has(workspace.id)}
        onTitle={onSessionTitle}
      />
    );
  } else {
    tabTitle = workspace.name;
    page = <WorkspacePage workspace={workspace} deleting={deleting.has(workspace.id)} />;
  }

  useEffect(() => {
    document.title = `${tabTitle} — ${APP}`;
  }, [tabTitle]);

  return (
    <AppLayout
      header={<TopBar theme={theme} onThemeChange={setTheme} />}
      sidebar={
        <Sidebar
          workspaces={workspaces}
          selectedId={workspace?.id ?? null}
          deleting={deleting}
          onSelect={(id) => navigate({ name: "workspace", workspaceId: id })}
          onNew={openNew}
          onRename={rename}
          onSettings={(id) => navigate({ name: "settings", workspaceId: id })}
          onTerminal={(id) => navigate({ name: "terminal", workspaceId: id })}
          onDelete={setDeleteId}
        />
      }
      notice={error && <ErrorBanner message={error} onDismiss={dismissError} />}
    >
      {page}
      {newName !== null && (
        <CreateWorkspaceDialog
          initialName={newName}
          providers={providers.providers}
          providersStatus={providers.status}
          onCreate={(request) =>
            create(request, (created) =>
              navigate({ name: "workspace", workspaceId: created.id }),
            )
          }
          onClose={() => setNewName(null)}
        />
      )}
      {deleteTarget && (
        <DeleteWorkspaceDialog
          name={deleteTarget.name}
          onClose={() => setDeleteId(null)}
          onConfirm={() => {
            remove(deleteTarget.id);
            setDeleteId(null);
          }}
        />
      )}
    </AppLayout>
  );
}
