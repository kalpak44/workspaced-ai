import { FileArchive, History, Upload } from "lucide-react";
import { useRef, useState } from "react";
import { formatBytes } from "../../lib/format";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly name: string;
  /** Restoring needs a workspace that is not already starting. */
  readonly canRestore: boolean;
  readonly onUpload: (file: File, restore: boolean) => void;
  readonly onClose: () => void;
}

// What a backup's Download gives; the workspace restores nothing else.
const ARCHIVE = /\.(tar\.gz|tgz)$/i;

/** Picks an archive and says what restoring it replaces; the upload itself runs on the page. */
export function UploadBackupDialog({ name, canRestore, onUpload, onClose }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [restore, setRestore] = useState(canRestore);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const invalid = file !== null && !ARCHIVE.test(file.name);

  return (
    <Modal title={`Upload a backup to ${name}`} description="A .tar.gz archive, such as one downloaded from a backup." onClose={onClose}>
      <form className="flex min-h-0 flex-col overflow-hidden" onSubmit={(event) => {
        event.preventDefault();
        if (file && !invalid) {
          onUpload(file, restore);
          onClose();
        }
      }}>
        <ModalBody>
          <button type="button" onClick={() => input.current?.click()}
            onDragOver={(event) => { event.preventDefault(); setDragging(true); }}
            onDragLeave={() => setDragging(false)}
            onDrop={(event) => { event.preventDefault(); setDragging(false); setFile(event.dataTransfer.files[0] ?? null); }}
            className={`flex flex-col items-center gap-2 rounded-xl border-2 border-dashed px-4 py-6 text-center text-sm ${
              dragging ? "border-zinc-500 bg-zinc-50 dark:border-zinc-400 dark:bg-zinc-800/50" : "border-zinc-300 hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800/50"
            }`}>
            {file ? <FileArchive aria-hidden className="size-6 text-zinc-500" /> : <Upload aria-hidden className="size-6 text-zinc-400" />}
            {file
              ? <span className="max-w-full break-all font-medium">{file.name} <span className="font-normal text-zinc-500">· {formatBytes(file.size)}</span></span>
              : <span className="font-medium">Choose an archive or drop it here</span>}
            <span className="text-xs text-zinc-500 dark:text-zinc-400">{file ? "Choose another" : ".tar.gz or .tgz"}</span>
          </button>
          <input ref={input} type="file" accept=".tar.gz,.tgz,application/gzip,application/x-gzip" aria-label="Backup archive" className="hidden"
            onChange={(event) => { setFile(event.target.files?.[0] ?? null); event.target.value = ""; }} />
          {invalid && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">Choose a .tar.gz or .tgz archive, like the ones Download gives you.</p>}
          <label className="flex items-start gap-2 text-sm text-zinc-700 dark:text-zinc-300">
            <input type="checkbox" checked={restore} disabled={!canRestore} onChange={(event) => setRestore(event.target.checked)}
              className="mt-0.5 size-4 shrink-0 accent-zinc-900 dark:accent-zinc-100" />
            <span>
              Restore it once uploaded
              <span className="block text-xs text-zinc-500 dark:text-zinc-400">Otherwise it is kept in the list below to restore later.</span>
            </span>
          </label>
          {restore && (
            <div className="flex gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 dark:border-amber-950 dark:bg-amber-950/30">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-amber-100 text-amber-600 dark:bg-amber-950 dark:text-amber-400">
                <History aria-hidden className="size-4.5" />
              </span>
              <p className="pt-0.5 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
                Every file in the workspace is replaced by the archive's, and the workspace restarts.
                Its settings and secrets stay as they are.
              </p>
            </div>
          )}
          <p className="text-xs text-zinc-500 dark:text-zinc-400">Keep this page open until the upload finishes; leaving it cancels the upload.</p>
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" onClick={onClose} />
          <Button label={restore ? "Upload and restore" : "Upload"} type="submit" icon={Upload} disabled={!file || invalid} />
        </ModalFooter>
      </form>
    </Modal>
  );
}
