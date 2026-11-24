import { Component, type ReactNode } from "react";
import { Button } from "../atoms";

/** An open tab can request an older page module after a deployment replaces its assets. */
export class AppRecoveryBoundary extends Component<{ readonly children: ReactNode }, { readonly failed: boolean }> {
  override state = { failed: false };

  static getDerivedStateFromError(): { failed: boolean } { return { failed: true }; }

  override render() {
    if (!this.state.failed) return this.props.children;
    return <main role="alert" className="flex min-h-dvh items-center justify-center p-6">
      <section className="card max-w-md space-y-4 p-6">
        <h1 className="text-xl font-semibold">Reload to continue</h1>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">This page could not load. A newer version may be available. Reload the app to continue your saved tour.</p>
        <Button label="Reload app" onClick={() => globalThis.location.reload()} />
      </section>
    </main>;
  }
}
