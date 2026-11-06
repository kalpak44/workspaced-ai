import { UserPlus } from "lucide-react";
import { type SubmitEvent, useEffect, useRef, useState } from "react";
import type { CreateResult, NewUser } from "../../hooks/useUsers";
import { Button, TextField } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

// The API rejects anything longer, so the field never lets the user type past it.
const MAX_NAME = 100;

interface Props {
  /** Resolves "created" once the user exists; on anything else the dialog stays and keeps its input. */
  readonly onCreate: (user: NewUser) => Promise<CreateResult>;
  readonly onClose: () => void;
}

/** Names, a username to sign in with, and a first password the new user can change later. */
export function CreateUserDialog({ onCreate, onClose }: Props) {
  const [draft, setDraft] = useState<NewUser>({ username: "", password: "", firstName: "", lastName: "" });
  const [usernameError, setUsernameError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const firstInput = useRef<HTMLInputElement>(null);

  useEffect(() => firstInput.current?.focus(), []);

  // Closing mid-request would hide whether the user was made, so it waits.
  const close = () => {
    if (!pending) {
      onClose();
    }
  };

  const edit = (field: keyof NewUser, value: string) => {
    setDraft((current) => ({ ...current, [field]: value }));
    if (field === "username") setUsernameError(null);
    if (field === "password") setPasswordError(null);
  };

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    if (draft.password.length < 8) return setPasswordError("Use at least 8 characters.");
    if (draft.password.length > 72) return setPasswordError("Use at most 72 characters.");
    setPending(true);
    try {
      const user = { ...draft, username: draft.username.trim(), firstName: draft.firstName.trim(), lastName: draft.lastName.trim() };
      const result = await onCreate(user).catch((): CreateResult => "failed");
      if (result === "created") {
        onClose();
      } else if (result === "conflict") {
        setUsernameError(`${user.username} is already taken.`);
      } else {
        setUsernameError("Could not create the user.");
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <Modal title="New user" description="They sign in with this username and password." onClose={close}>
      <form onSubmit={submit} className="flex min-h-0 flex-col overflow-hidden">
        <ModalBody>
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField ref={firstInput} id="new-user-first-name" label="First name" autoComplete="off" maxLength={MAX_NAME} disabled={pending} value={draft.firstName} onChange={(e) => edit("firstName", e.currentTarget.value)} />
            <TextField id="new-user-last-name" label="Last name" autoComplete="off" maxLength={MAX_NAME} disabled={pending} value={draft.lastName} onChange={(e) => edit("lastName", e.currentTarget.value)} />
          </div>
          <TextField id="new-user-username" label="Username" autoComplete="off" spellCheck={false} maxLength={MAX_NAME} disabled={pending} error={usernameError} value={draft.username} onChange={(e) => edit("username", e.currentTarget.value)} />
          <TextField id="new-user-password" label="Password" type="password" autoComplete="new-password" hint="8 to 72 characters." disabled={pending} error={passwordError} value={draft.password} onChange={(e) => edit("password", e.currentTarget.value)} />
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" disabled={pending} onClick={close} />
          <Button label="Create user" type="submit" icon={UserPlus} busy={pending} disabled={!draft.username.trim() || !draft.password} />
        </ModalFooter>
      </form>
    </Modal>
  );
}
