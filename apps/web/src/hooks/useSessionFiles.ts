import { createContext, useCallback, useContext, useRef, useState } from "react";
import { linkSessionFile, uploadSessionFile } from "../api/sessionFilesApi";
import { ApiError } from "../api/http";

/** One file picked in the composer, from the pick until it is in the session's folder. */
export interface Attachment {
  readonly id: string;
  readonly name: string;
  /** "queued" waits in the browser for a session to exist, as on the start screen. */
  readonly status: "queued" | "uploading" | "ready" | "failed";
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

/**
 * The file a session link names. Markdown needs a space written as %20 in a link, so the raw
 * href is never the file's name; one that is not valid percent-encoding is taken as it is.
 */
export function sessionFilePath(href: string): string {
  try {
    return decodeURIComponent(href);
  } catch {
    return href;
  }
}

/** Names the uploaded files under the message, so the agent knows to read them. */
export function attachmentNote(text: string, paths: readonly string[]): string {
  if (paths.length === 0) return text;
  return `${text ? `${text}\n\n` : ""}Attached files:\n${paths.map((p) => `- ${p}`).join("\n")}`;
}

/**
 * Files picked before there is a session to put them in. They are uploaded only once the
 * session exists, on send.
 */
export function useFileQueue() {
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const files = useRef(new Map<string, File>());

  const update = (id: string, changes: Partial<Attachment>) =>
    setAttachments((current) => current.map((a) => (a.id === id ? { ...a, ...changes } : a)));

  const attach = (picked: readonly File[]) => {
    for (const file of picked) {
      const id = `${file.name}-${crypto.randomUUID()}`;
      const tooLarge = file.size > MAX_BYTES;
      if (!tooLarge) files.current.set(id, file);
      setAttachments((current) => [...current, { id, name: file.name, status: tooLarge ? "failed" : "queued", path: null, error: tooLarge ? "Larger than 100 MB" : null }]);
    }
  };

  const detach = (id: string) => {
    files.current.delete(id);
    setAttachments((current) => current.filter((a) => a.id !== id));
  };

  /** Every queued file into the session; null when any of them failed, which stay to retry. */
  const uploadTo = async (workspaceId: string, sessionId: string): Promise<string[] | null> => {
    const queued = attachments.filter((a) => a.status === "queued" || (a.status === "failed" && files.current.has(a.id)));
    const results = await Promise.all(queued.map(async (a) => {
      update(a.id, { status: "uploading", error: null });
      try {
        const uploaded = await uploadSessionFile(workspaceId, sessionId, files.current.get(a.id)!);
        update(a.id, { status: "ready", path: uploaded.path });
        return uploaded.path;
      } catch {
        update(a.id, { status: "failed", error: "Upload failed" });
        return null;
      }
    }));
    return results.every((path) => path !== null) ? (results as string[]) : null;
  };

  const clear = () => {
    files.current.clear();
    setAttachments([]);
  };

  return { attachments, attach, detach, uploadTo, clear };
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

  const withAttachments = (text: string): string =>
    attachmentNote(text, attachments.filter((a) => a.status === "ready" && a.path).map((a) => a.path!));

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
