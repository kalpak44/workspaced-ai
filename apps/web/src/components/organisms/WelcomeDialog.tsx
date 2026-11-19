import { Wallet } from "lucide-react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly onAddFunds: () => void;
  readonly onClose: () => void;
}

/** Shown once, on a new account's first sign-in; the same steps as its welcome notification. */
export function WelcomeDialog({ onAddFunds, onClose }: Props) {
  return (
    <Modal title="Welcome to workspaced" description="Here is how to get your first agent working." onClose={onClose}>
      <ModalBody>
        <p className="text-sm leading-6 text-zinc-700 dark:text-zinc-300">
          A workspace is a sandbox with its own AI agent. Add funds to your balance first, then
          create a workspace from the sidebar and start a session to give the agent a task.
        </p>
      </ModalBody>
      <ModalFooter>
        <Button label="Later" variant="secondary" onClick={onClose} />
        <Button label="Add funds" icon={Wallet} onClick={onAddFunds} autoFocus />
      </ModalFooter>
    </Modal>
  );
}
