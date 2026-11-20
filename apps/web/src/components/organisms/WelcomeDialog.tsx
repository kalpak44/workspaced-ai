import { Plus, Sparkles } from "lucide-react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  /** Whether the account can still have its free demo, which the tour runs in. */
  readonly demoAvailable: boolean;
  readonly onStartTour: () => void;
  readonly onNewWorkspace: () => void;
  readonly onClose: () => void;
}

/** Shown once, on a new account's first sign-in; the same offer as its welcome notification. */
export function WelcomeDialog({ demoAvailable, onStartTour, onNewWorkspace, onClose }: Props) {
  return (
    <Modal title="Welcome to workspaced" description="Your own computer in the cloud, with an AI agent that builds for you." onClose={onClose}>
      <ModalBody>
        <div className="flex flex-col gap-3 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
          <p>
            A workspace is a small computer of your own in the cloud, with an AI agent living on it.
            You say what you want in plain words, and it installs, builds and publishes it for you.
          </p>
          {demoAvailable ? (
            <p>
              The best way to see it is the <strong>guided tour</strong>: we set up a free demo
              workspace and have its agent build a blog while you watch. We guide each step; you connect your own OpenRouter account and choose your WordPress credentials. The
              VM is free for 4 hours and then deleted. OpenRouter charges your account separately for AI usage.
            </p>
          ) : (
            <p>Create a workspace whenever you are ready, then start a session to give its agent a task.</p>
          )}
        </div>
      </ModalBody>
      <ModalFooter>
        <Button label="Explore on my own" variant="secondary" onClick={onClose} />
        {demoAvailable
          ? <Button label="Start the tour" icon={Sparkles} onClick={onStartTour} autoFocus />
          : <Button label="New workspace" icon={Plus} onClick={onNewWorkspace} autoFocus />}
      </ModalFooter>
    </Modal>
  );
}
