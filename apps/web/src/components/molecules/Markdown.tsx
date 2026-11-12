import { Download } from "lucide-react";
import type { ReactNode } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { isSessionFileLink, useFileLink } from "../../hooks/useSessionFiles";

/**
 * A link to a file in the session's folder becomes a download, since the browser cannot open a
 * path inside the workspace; any other link opens in a new tab.
 */
function Link({ href, children }: { readonly href?: string; readonly children?: ReactNode }) {
  const open = useFileLink();
  if (open && isSessionFileLink(href)) {
    return (
      <button type="button" onClick={() => open(href)} title={`Download ${href}`}
        className="inline-flex items-center gap-1 rounded-md border border-zinc-300 px-1.5 py-0.5 align-baseline text-[0.95em] hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800">
        <Download aria-hidden className="size-3.5" />
        {children}
      </button>
    );
  }
  return (
    <a href={href} target="_blank" rel="noreferrer" className="underline underline-offset-2">
      {children}
    </a>
  );
}

// Raw HTML in the source is never rendered: react-markdown escapes it unless told otherwise,
// and model output is not trusted markup.
// A file under a session's folder, as an agent prints one when it forgets to link it.
const SESSION_FILE = /^\/home\/workspace\/sessions\/[^\s/]+\/\S*[^/\s]\.[A-Za-z0-9]{1,10}$/;

/** A path the user could not otherwise reach gets a download beside it. */
function Code({ children }: { readonly children?: ReactNode }) {
  const open = useFileLink();
  const path = typeof children === "string" ? children.trim() : null;
  const code = (
    <code className="rounded bg-zinc-100 px-1 py-0.5 font-mono text-[0.85em] dark:bg-zinc-800">
      {children}
    </code>
  );
  if (!open || !path || !SESSION_FILE.test(path)) {
    return code;
  }
  const name = path.slice(path.lastIndexOf("/") + 1);
  return (
    <span className="inline-flex max-w-full items-center gap-1 align-baseline">
      {code}
      <button type="button" aria-label={`Download ${name}`} title={`Download ${name}`} onClick={() => open(path)}
        className="shrink-0 rounded p-0.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100">
        <Download aria-hidden className="size-3.5" />
      </button>
    </span>
  );
}

const COMPONENTS: Components = {
  p: ({ children }) => <p className="my-3 first:mt-0 last:mb-0">{children}</p>,
  a: ({ href, children }) => <Link href={href}>{children}</Link>,
  ul: ({ children }) => <ul className="my-3 list-disc space-y-1 pl-6">{children}</ul>,
  ol: ({ children }) => <ol className="my-3 list-decimal space-y-1 pl-6">{children}</ol>,
  h1: ({ children }) => <h3 className="mt-5 mb-2 text-lg font-semibold">{children}</h3>,
  h2: ({ children }) => <h3 className="mt-5 mb-2 text-base font-semibold">{children}</h3>,
  h3: ({ children }) => <h4 className="mt-4 mb-2 font-semibold">{children}</h4>,
  blockquote: ({ children }) => (
    <blockquote className="my-3 border-l-2 border-zinc-300 pl-3 text-zinc-600 dark:border-zinc-700 dark:text-zinc-400">
      {children}
    </blockquote>
  ),
  // Inline code gets a chip; inside a block the pre styles it, so the chip is undone there.
  code: ({ children }) => <Code>{children}</Code>,
  pre: ({ children }) => (
    <pre className="my-3 overflow-x-auto rounded-lg bg-zinc-100 p-3 font-mono text-xs leading-relaxed dark:bg-zinc-900 [&_code]:bg-transparent [&_code]:p-0 [&_code]:text-xs">
      {children}
    </pre>
  ),
  table: ({ children }) => (
    <div className="my-3 overflow-x-auto">
      <table className="w-full border-collapse text-left text-xs">{children}</table>
    </div>
  ),
  th: ({ children }) => (
    <th className="border-b border-zinc-300 px-2 py-1 font-semibold dark:border-zinc-700">
      {children}
    </th>
  ),
  td: ({ children }) => (
    <td className="border-b border-zinc-200 px-2 py-1 dark:border-zinc-800">{children}</td>
  ),
};

/** The agent writes markdown; this renders it with the page's own type scale. */
export function Markdown({ text }: { readonly text: string }) {
  return (
    <div className="min-w-0 leading-relaxed [overflow-wrap:anywhere]">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={COMPONENTS}>
        {text}
      </ReactMarkdown>
    </div>
  );
}
