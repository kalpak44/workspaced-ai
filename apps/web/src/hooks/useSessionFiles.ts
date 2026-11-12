import { createContext, useCallback, useContext, useState } from "react";
import { linkSessionFile, uploadSessionFile } from "../api/sessionFilesApi";
import { ApiError } from "../api/http";

/** One file picked in the composer, from the pick until it is in the session's folder. */
export interface Attachment {
  readonly id: string;
  readonly name: string;
  readonly status: "uploading" | "ready" | "failed";
  /** Relative to the session's folder, once uploaded. */
  readonly path: string | null;
  readonly error: string | null;
}

/** 100 MB, as the API allows. */
const MAX_BYTES = 100 * 1024 * 1024;

/**
 * Opens a file the agent linked in its reply. Provided by the session page, so a chat message
 * can turn a relative link into a download without knowing which session it is in.
 */
export const FileLinkContext = createContext<((path: string) => void) | null>(null);

export function useFileLink() {
  return useContext(FileLinkContext);
}

/** A link the agent wrote to a file of its own folder, as opposed to a page on the web. */
export function isSessionFileLink(href: string | undefined): href is string {
  if (!href || href.startsWith("#")) return false;
  if (href.startsWith("/home/workspace/")) return true;
  return !href.startsWith("/") && !/^[a-z][a-z0-9+.-]*:/i.test(href);
}

/** Files between the user and one session's agent: what the composer attaches, and links out. */
export function useSessionFiles(workspaceId: string | null, sessionId: string | null) {
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [linkError, setLinkError] = useState<string | null>(null);

  const update = (id: string, changes: Partial<Attachment>) =>
    setAttachments((current) => current.map((a) => (a.id === id ? { ...a, ...changes } : a)));

  const attach = (files: readonly File[]) => {
    if (!workspaceId || !sessionId) return;
    for (const file of files) {
      const id = `${file.name}-${crypto.randomUUID()}`;
      if (file.size > MAX_BYTES) {
        setAttachments((current) => [...current, { id, name: file.name, status: "failed", path: null, error: "Larger than 100 MB" }]);
        continue;
      }
      setAttachments((current) => [...current, { id, name: file.name, status: "uploading", path: null, error: null }]);
      uploadSessionFile(workspaceId, sessionId, file).then(
        (uploaded) => update(id, { status: "ready", path: uploaded.path, name: uploaded.name }),
        (failure: unknown) =>
          update(id, { status: "failed", error: failure instanceof ApiError && failure.status === 413 ? "Larger than 100 MB" : "Upload failed" }),
      );
    }
  };

  const detach = (id: string) => setAttachments((current) => current.filter((a) => a.id !== id));

  /** Names the uploaded files under the message, so the agent knows to read them, and clears them. */
  const withAttachments = (text: string): string => {
    const ready = attachments.filter((a) => a.status === "ready" && a.path);
    if (ready.length === 0) return text;
    const list = ready.map((a) => `- ${a.path}`).join("\n");
    return `${text ? `${text}\n\n` : ""}Attached files:\n${list}`;
  };

  const clear = () => setAttachments([]);

  const open = useCallback((path: string) => {
    if (!workspaceId || !sessionId) return;
    setLinkError(null);
    linkSessionFile(workspaceId, sessionId, path).then(
      (link) => globalThis.open(link.url, "_blank", "noopener"),
      (failure: unknown) =>
        setLinkError(failure instanceof ApiError && failure.status === 404 ? `The file ${path} is not in this session.` : "Could not prepare the download."),
    );
  }, [workspaceId, sessionId]);

  return {
    attachments,
    uploading: attachments.some((a) => a.status === "uploading"),
    attach,
    detach,
    withAttachments,
    clear,
    open,
    linkError,
    dismissLinkError: () => setLinkError(null),
  };
}
