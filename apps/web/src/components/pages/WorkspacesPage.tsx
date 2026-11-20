import { lazy, Suspense, useCallback, useEffect, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { navigate, useRoute } from "../../hooks/useRoute";
import { useTheme } from "../../hooks/useTheme";
import { useWorkspaces } from "../../hooks/useWorkspaces";
import { reportTourAction, setTourProgress, TOUR_STEP, useTourProgress } from "../../lib/tour";
import { DemoBanner, EmptyState, ErrorBanner, PageHeader } from "../molecules";
import { WorkspacesOverview } from "./WorkspacesOverview";
import { CommandPalette, CreateWorkspaceDialog, DeleteWorkspaceDialog, DemoTour, RestartWorkspaceDialog, TopBar, WorkspaceHeader, type WorkspaceView } from "../organisms";
import { AppLayout } from "../templates";
import { SessionPage } from "./SessionPage";
import { SettingsPage } from "./SettingsPage";
import { TerminalPage } from "./TerminalPage";
import { WorkspacePage } from "./WorkspacePage";

const APP = "workspaced-ai";
const StorePage = lazy(() => import("./StorePage").then((page) => ({ default: page.StorePage })));
const RecipePage = lazy(() => import("./RecipePage").then((page) => ({ default: page.RecipePage })));
const BillingPage = lazy(() => import("./BillingPage").then((page) => ({ default: page.BillingPage })));
const NotificationsPage = lazy(() => import("./NotificationsPage").then((page) => ({ default: page.NotificationsPage })));
const NotificationPage = lazy(() => import("./NotificationPage").then((page) => ({ default: page.NotificationPage })));
const MonitoringPage = lazy(() => import("./MonitoringPage").then((page) => ({ default: page.MonitoringPage })));
const GeneralSettingsPage = lazy(() => import("./GeneralSettingsPage").then((page) => ({ default: page.GeneralSettingsPage })));

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
    draft,
    launch,
    discardDraft,
    suggestName,
    rename,
    restart,
    remove,
    patch,
  } = useWorkspaces();
  // The name the create dialog opens with; null while it is closed.
  const [newName, setNewName] = useState<string | null>(null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [restartId, setRestartId] = useState<string | null>(null);
  const [pageTitle, setPageTitle] = useState<string | null>(null);
  const [paletteOpen, setPaletteOpen] = useState(false);
  // The API sends a new account's first sign-in to `/?welcome`, and its welcome mail links to
  // `/?tour`; both are read before any redirect drops them.
  const [tourAsked, setTourAsked] = useState(() => ["welcome", "tour"].some((parameter) => new URLSearchParams(globalThis.location.search).has(parameter)));
  const openNew = () => {
    setNewName(tour ? "" : suggestName());
    reportTourAction({ type: "workspace-form-opened" });
  };
  const openRestart = (id: string) => { setRestartId(id); reportTourAction({ type: "restart-dialog-opened", workspaceId: id }); };
  const { account } = useAccount();
  const tour = useTourProgress();
  const demo = workspaces.find((w) => w.demo) ?? null;
  // The account says whether its demo is used; a demo listed here is the same answer, sooner.
  const demoAvailable = account?.demoAvailable === true && !demo;
  const startTour = () => {
    setTourProgress({ step: 0 });
  };

  useEffect(() => {
    if (tour?.step === TOUR_STEP.NAME && newName === null) setNewName("");
  }, [tour?.step, newName]);

  const workspaceId = "workspaceId" in route ? route.workspaceId : null;
  const workspace = workspaces.find((w) => w.id === workspaceId) ?? null;
  const deleteTarget = workspaces.find((w) => w.id === deleteId) ?? null;
  const restartTarget = workspaces.find((w) => w.id === restartId) ?? null;

  // A workspace that is gone — deleted here or in another tab, or a stale link — falls back to
  // the overview, replacing the entry so Back skips it.
  useEffect(() => {
    if (!loading && workspaceId !== null && !workspace) {
      navigate({ name: "home" }, { replace: true });
    }
  }, [loading, workspaceId, workspace]);

  // Dropped once read, so a reload does not welcome the user again.
  useEffect(() => {
    const params = new URLSearchParams(globalThis.location.search);
    if (params.has("welcome") || params.has("tour")) {
      globalThis.history.replaceState(globalThis.history.state, "", globalThis.location.pathname);
    }
  }, []);

  useEffect(() => {
    if (tourAsked && !loading && account) {
      setTourAsked(false);
      if (demoAvailable && !tour) setTourProgress({ step: 0 });
    }
  }, [tourAsked, loading, account, demoAvailable, tour]);

  const onSessionTitle = useCallback((title: string) => setPageTitle(title), []);

  let page;
  let tabTitle: string;
  if (route.name === "general-settings") {
    tabTitle = "General settings";
    page = <Suspense fallback={<EmptyState busy title="Loading settings…" hint="Preparing your preferences" />}><GeneralSettingsPage section={route.section} /></Suspense>;
  } else if (route.name === "billing") {
    tabTitle = "Billing";
    page = <Suspense fallback={<EmptyState busy title="Loading billing…" hint="Fetching your balance and invoices" />}><BillingPage /></Suspense>;
  } else if (route.name === "notifications") {
    tabTitle = "Notifications";
    page = <Suspense fallback={<EmptyState busy title="Loading notifications…" hint="Fetching the latest" />}><NotificationsPage /></Suspense>;
  } else if (route.name === "notification") {
    tabTitle = "Notification";
    page = <Suspense fallback={<EmptyState busy title="Loading notification…" hint="Fetching it" />}><NotificationPage key={route.notificationId} notificationId={route.notificationId} /></Suspense>;
  } else if (route.name === "store") {
    tabTitle = "Recipe store";
    page = <Suspense fallback={<EmptyState busy title="Loading the store…" hint="Fetching recipes" />}><StorePage /></Suspense>;
  } else if (route.name === "recipe") {
    tabTitle = `${pageTitle ?? "Recipe"} · Recipe store`;
    page = (
      <Suspense fallback={<EmptyState busy title="Loading recipe…" hint="Fetching it from the store" />}>
        <RecipePage key={route.recipeId} recipeId={route.recipeId} workspaces={workspaces} onTitle={onSessionTitle} />
      </Suspense>
    );
  } else if (route.name === "home" || (!loading && !workspace)) {
    tabTitle = "Workspaces";
    page = (
      <WorkspacesOverview workspaces={workspaces} loading={loading} deleting={deleting} onNew={openNew}
        onRename={rename} onRestart={openRestart} onDelete={setDeleteId}
        onStartTour={demoAvailable && !tour ? startTour : undefined} />
    );
  } else if (!workspace) {
    tabTitle = "Workspaces";
    page = (
      <>
        <PageHeader title="Workspaces" />
        <EmptyState busy title="Loading workspaces…" hint="Fetching them from the API" />
      </>
    );
  } else if (route.name === "settings") {
    tabTitle = `Settings · ${workspace.name}`;
    page = (
      <SettingsPage
        workspace={workspace}
        section={route.section}
        onChanged={(changes) => patch(workspace.id, changes)}
        onRestart={() => openRestart(workspace.id)}
      />
    );
  } else if (route.name === "monitoring") {
    tabTitle = `Monitoring · ${workspace.name}`;
    page = (
      <Suspense fallback={<EmptyState busy title="Loading monitoring…" hint="Fetching the workspace's metrics" />}>
        <MonitoringPage key={workspace.id} workspace={workspace} deleting={deleting.has(workspace.id)} />
      </Suspense>
    );
  } else if (route.name === "terminal") {
    tabTitle = `Terminal · ${workspace.name}`;
    page = <TerminalPage key={workspace.id} workspace={workspace} terminalId={route.terminalId} deleting={deleting.has(workspace.id)} />;
  } else if (route.name === "session") {
    tabTitle = `${pageTitle ?? "Session"} · ${workspace.name}`;
    page = (
      <SessionPage
        key={route.sessionId}
        workspace={workspace}
        sessionId={route.sessionId}
        deleting={deleting.has(workspace.id)}
        onModelChanged={(model) => patch(workspace.id, { model })}
        onTitle={onSessionTitle}
      />
    );
  } else {
    tabTitle = workspace.name;
    page = <WorkspacePage workspace={workspace} deleting={deleting.has(workspace.id)} onModelChanged={(model) => patch(workspace.id, { model })} />;
  }

  useEffect(() => {
    document.title = `${tabTitle} — ${APP}`;
  }, [tabTitle]);

  // A workspace's own views share one header with its tabs; a session is a chat, kept full height.
  const VIEWS: Partial<Record<typeof route.name, WorkspaceView>> = { workspace: "sessions", terminal: "terminal", monitoring: "monitoring", settings: "settings" };
  const view = VIEWS[route.name];
  if (workspace && view && !loading) {
    page = (
      <>
        <WorkspaceHeader workspace={workspace} workspaces={workspaces} current={view} deleting={deleting.has(workspace.id)}
          onRestart={() => openRestart(workspace.id)} onRename={(name) => rename(workspace.id, name)}
          onDelete={() => setDeleteId(workspace.id)} onNew={openNew} />
        {page}
      </>
    );
  }

  return (
    <AppLayout guided={!!tour}
      header={<TopBar theme={theme} onThemeChange={setTheme} storeOpen={route.name === "store" || route.name === "recipe"} onSearch={() => setPaletteOpen(true)}
        workspacesOpen={route.name === "home"} onNewWorkspace={openNew} />}
      notice={(demo || error) && (
        <>
          {demo && <DemoBanner name={demo.name} expiresAt={demo.expiresAt ?? null} />}
          {error && <ErrorBanner message={error} onDismiss={dismissError} />}
        </>
      )}
    >
      {page}
      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} workspaces={workspaces} current={workspace}
        theme={theme} onThemeChange={setTheme} onNewWorkspace={openNew} />
      {newName !== null && (
        <CreateWorkspaceDialog
          initialName={newName}
          onNameChosen={tour ? (name) => { setNewName(null); reportTourAction({ type: "workspace-named", name }); } : undefined}
          onCreate={(request) =>
            create(request, (created) =>
              navigate({ name: "workspace", workspaceId: created.id }),
            )
          }
          onDraft={draft}
          onLaunch={(id, key) =>
            launch(id, key, (created) => navigate({ name: "workspace", workspaceId: created.id }))
          }
          onDiscard={discardDraft}
          onClose={() => { setNewName(null); reportTourAction({ type: "workspace-form-closed" }); }}
        />
      )}
      <DemoTour workspaces={workspaces} loading={loading} onDraft={draft} onLaunch={(id, key) => launch(id, key)}
        onExit={() => { setNewName(null); setTourProgress(null); }} />
      {restartTarget && (
        <RestartWorkspaceDialog
          name={restartTarget.name}
          onClose={() => { setRestartId(null); reportTourAction({ type: "restart-cancelled" }); }}
          onConfirm={() => {
            restart(restartTarget.id);
            reportTourAction({ type: "restart-confirmed", workspaceId: restartTarget.id });
            setRestartId(null);
          }}
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
