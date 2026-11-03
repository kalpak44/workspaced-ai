import { Plus } from "lucide-react";
import { type SubmitEvent, useEffect, useRef, useState } from "react";
import { Button, TextField } from "../atoms";
import { Modal } from "../molecules";

// The API rejects anything longer, so the field never lets the user type past it.
const MAX_NAME = 100;

interface Props {
  readonly initialName: string;
  /** Resolves true once created; the dialog stays open on false so nothing typed is lost. */
  readonly onCreate: (name: string) => Promise<boolean>;
  readonly onClose: () => void;
}

export function CreateWorkspaceDialog({ initialName, onCreate, onClose }: Props) {
  const [name, setName] = useState(initialName);
  const [pending, setPending] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  // The suggested name is selected, so typing replaces it and Enter accepts it.
  useEffect(() => {
    input.current?.focus();
    input.current?.select();
  }, []);

  // Closing mid-request would hide whether the workspace was made, so it waits.
  const close = () => {
    if (!pending) {
      onClose();
    }
  };

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    setPending(true);
    try {
      const created = await onCreate(name).catch(() => false);
      if (created) {
        onClose();
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <Modal title="New workspace" onClose={close}>
      <form onSubmit={submit} className="flex flex-col gap-5">
        <TextField
          ref={input}
          id="workspace-name"
          label="Name"
          value={name}
          maxLength={MAX_NAME}
          disabled={pending}
          onChange={(e) => setName(e.currentTarget.value)}
        />
        <div className="flex justify-end gap-2">
          <Button label="Cancel" variant="secondary" onClick={onClose} disabled={pending} />
          <Button
            label="Create"
            type="submit"
            icon={Plus}
            busy={pending}
            disabled={!name.trim()}
          />
        </div>
      </form>
    </Modal>
  );
}
