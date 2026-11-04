import { Markdown } from "@tiptap/markdown";
import Image from "@tiptap/extension-image";
import { TaskItem, TaskList } from "@tiptap/extension-list";
import { TableKit } from "@tiptap/extension-table";
import { EditorContent, useEditor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import { Bold, Code, Heading2, Italic, List, ListOrdered, Quote, Redo2, Undo2 } from "lucide-react";
import { useEffect, useState } from "react";

interface Props {
  readonly value: string;
  readonly onChange: (value: string) => void;
}

/** One Markdown document, edited as formatted text or source. All styling belongs to the app. */
export function InstructionsEditor({ value, onChange }: Props) {
  const [mode, setMode] = useState<"editor" | "raw">("editor");
  const editor = useEditor({
    extensions: [StarterKit, Markdown, Image, TableKit, TaskList, TaskItem.configure({ nested: true })],
    content: value,
    contentType: "markdown",
    shouldRerenderOnTransaction: true,
    editorProps: {
      attributes: {
        class: "instructions-editor min-h-80 px-4 py-4 text-sm leading-relaxed outline-none",
        role: "textbox",
        "aria-label": "AI instructions editor",
        "aria-multiline": "true",
      },
    },
    onUpdate: ({ editor: current }) => onChange(current.getMarkdown()),
  });

  useEffect(() => {
    if (editor && editor.getMarkdown() !== value) {
      editor.commands.setContent(value, { contentType: "markdown", emitUpdate: false });
    }
  }, [editor, value]);

  const tools = editor ? [
    { label: "Bold", Icon: Bold, active: editor.isActive("bold"), run: () => editor.chain().focus().toggleBold().run() },
    { label: "Italic", Icon: Italic, active: editor.isActive("italic"), run: () => editor.chain().focus().toggleItalic().run() },
    { label: "Heading", Icon: Heading2, active: editor.isActive("heading", { level: 2 }), run: () => editor.chain().focus().toggleHeading({ level: 2 }).run() },
    { label: "Bullet list", Icon: List, active: editor.isActive("bulletList"), run: () => editor.chain().focus().toggleBulletList().run() },
    { label: "Numbered list", Icon: ListOrdered, active: editor.isActive("orderedList"), run: () => editor.chain().focus().toggleOrderedList().run() },
    { label: "Quote", Icon: Quote, active: editor.isActive("blockquote"), run: () => editor.chain().focus().toggleBlockquote().run() },
    { label: "Code block", Icon: Code, active: editor.isActive("codeBlock"), run: () => editor.chain().focus().toggleCodeBlock().run() },
    { label: "Undo", Icon: Undo2, disabled: !editor.can().undo(), run: () => editor.chain().focus().undo().run() },
    { label: "Redo", Icon: Redo2, disabled: !editor.can().redo(), run: () => editor.chain().focus().redo().run() },
  ] : [];

  return (
    <div className="overflow-hidden rounded-lg border border-zinc-300 focus-within:border-zinc-500 focus-within:ring-2 focus-within:ring-zinc-900/10 dark:border-zinc-700 dark:focus-within:border-zinc-400 dark:focus-within:ring-white/10">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200 bg-zinc-50 px-3 py-2 dark:border-zinc-800 dark:bg-zinc-900">
        <span className="font-mono text-xs text-zinc-500 dark:text-zinc-400">AGENTS.md</span>
        <div role="group" aria-label="Instructions editing mode" className="flex rounded-full border border-zinc-200 p-0.5 dark:border-zinc-700">
          {([{ id: "editor", label: "Editor" }, { id: "raw", label: "Raw Markdown" }] as const).map(({ id, label }) => (
            <button key={id} type="button" aria-pressed={mode === id} onClick={() => setMode(id)}
              className={`rounded-full px-3 py-1 text-xs font-medium ${mode === id ? "bg-zinc-900 text-white dark:bg-white dark:text-zinc-900" : "text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"}`}>
              {label}
            </button>
          ))}
        </div>
      </div>
      {mode === "editor" ? <>
        <div role="toolbar" aria-label="Text formatting" className="flex flex-wrap gap-0.5 border-b border-zinc-200 px-2 py-1.5 dark:border-zinc-800">
          {tools.map(({ label, Icon, active, disabled, run }) => (
            <button key={label} type="button" title={label} aria-label={label} aria-pressed={active} disabled={disabled} onMouseDown={(event) => event.preventDefault()} onClick={run}
              className={`rounded p-2 disabled:opacity-30 ${active ? "bg-zinc-200 text-zinc-900 dark:bg-zinc-700 dark:text-white" : "text-zinc-500 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-800"}`}>
              <Icon aria-hidden className="size-3.5" />
            </button>
          ))}
        </div>
        <EditorContent editor={editor} />
      </> : <textarea aria-label="AI instructions Markdown" value={value} onChange={(event) => onChange(event.target.value)} placeholder={"# General instructions\n\nDescribe how you want your AI to work…"} spellCheck={false} className="block min-h-80 w-full resize-y bg-transparent px-4 py-4 font-mono text-sm leading-relaxed outline-none placeholder:text-zinc-400" />}
      <div className="border-t border-zinc-200 px-3 py-2 text-xs text-zinc-500 dark:border-zinc-800 dark:text-zinc-400">{mode === "editor" ? "Format with the toolbar or use Markdown shortcuts as you type." : "Edit Markdown source. Switch to Editor to see and edit formatted text."}</div>
    </div>
  );
}
