import { useState } from "react";
import { EmptyState } from "./components/EmptyState";
import { type Section, Sidebar } from "./components/Sidebar";
import { TopBar } from "./components/TopBar";
import { useTheme } from "./useTheme";
import { DEFAULT_NAME, useWorkspaces } from "./useWorkspaces";

const SECTIONS: Record<Exclude<Section, "workspaces">, { title: string; hint: string }> = {
  knowledge: { title: "No knowledge yet", hint: "Documents your agents can draw on will appear here" },
  files: { title: "No files yet", hint: "Files you upload will appear here" },
};

export function App() {
  const [theme, setTheme] = useTheme();
  const { workspaces, selected, select, create, rename, remove } = useWorkspaces();
  const [section, setSection] = useState<Section>("workspaces");

  const newWorkspace = { label: DEFAULT_NAME, onClick: () => create() };

  let title: string;
  let body;
  if (section !== "workspaces") {
    title = section === "knowledge" ? "Knowledge" : "My Files";
    body = <EmptyState {...SECTIONS[section]} />;
  } else if (selected) {
    title = selected.name;
    body = <EmptyState title="Empty workspace" hint="Start automating here" action={newWorkspace} />;
  } else {
    title = "Workspaces";
    body = <EmptyState title="No workspaces" hint="Create one to get started" action={newWorkspace} />;
  }

  return (
    <div className="flex h-screen flex-col">
      <TopBar theme={theme} onThemeChange={setTheme} />
      <div className="flex min-h-0 flex-1">
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
        <main className="flex flex-1 flex-col">
          <h1 className="px-4 pt-4 pb-2 font-medium">{title}</h1>
          {body}
        </main>
      </div>
    </div>
  );
}
