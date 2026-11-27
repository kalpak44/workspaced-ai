import { LogIn, MailCheck, RotateCw, UserPlus } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { type ResendResult, type SignedOutReason, useAccount } from "../../hooks/useAccount";
import { useTheme } from "../../hooks/useTheme";
import { hasPendingInvitation } from "../../lib/invitation";
import { readStored, writeStored } from "../../lib/storage";
import { Button, Logo, Spinner } from "../atoms";

// Where the API sends someone who signed up but has not verified their address yet.
const VERIFY_PATH = "/verify-email";
// When the app last sent the browser to Auth0. Signed out again within moments of it means a
// redirect now would loop, or would trap the Back button on the way out of Auth0.
const REDIRECTED_AT = "workspaced:sign-in-redirect";
const LOOP_WINDOW_MS = 10_000;

type View = "redirecting" | "sign-in" | "invited" | "failed" | "verify" | SignedOutReason;

function initialView(reason: SignedOutReason | undefined): View {
  if (reason === "signing-out") return reason;
  const { pathname, search } = globalThis.location;
  if (pathname === VERIFY_PATH) return "verify";
  if (new URLSearchParams(search).has("error")) return "failed";
  if (reason) return reason;
  // Someone invited may have no account yet, so they choose rather than land on sign-in.
  if (hasPendingInvitation()) return "invited";
  return Date.now() - Number(readStored(REDIRECTED_AT)) < LOOP_WINDOW_MS ? "sign-in" : "redirecting";
}

const ALERTS: Partial<Record<View, string>> = {
  failed: "Sign-in didn't work. Try again.",
  unreachable: "The API could not be reached. Try again in a moment.",
  "sign-out-failed": "Sign-out didn't finish. Try again in a moment.",
};

const RESENT: Record<ResendResult | "error", { readonly text: string; readonly ok: boolean }> = {
  sent: { text: "Sent again.", ok: true },
  wait: { text: "Wait a minute before asking again.", ok: false },
  expired: { text: "Sign in again to get a new email.", ok: false },
  error: { text: "Could not send the email. Try again in a moment.", ok: false },
};

/**
 * Shown while no one is signed in. A visitor goes straight on to Auth0; a card appears only to
 * explain why they came back without a session.
 */
export function LoginPage({ reason }: { readonly reason?: SignedOutReason }) {
  useTheme();
  const { signIn, signUp, resendVerificationEmail } = useAccount();
  // Decided once: the URL that chose it is cleared below, and the card stays until the user acts.
  const [view, setView] = useState(() => initialView(reason));
  // Which button is on its way to Auth0, so only that one spins.
  const [leaving, setLeaving] = useState<"sign-in" | "sign-up" | null>(null);
  const [resending, setResending] = useState(false);
  const [resent, setResent] = useState<ResendResult | "error" | null>(null);

  function leave(to: "sign-in" | "sign-up") {
    setLeaving(to);
    writeStored(REDIRECTED_AT, String(Date.now()));
    if (to === "sign-up") signUp();
    else signIn();
  }

  useEffect(() => {
    if (view === "redirecting") leave("sign-in");
    // Dropped once read, so a reload does not show an old failure again.
    if (view === "verify" || view === "failed") globalThis.history.replaceState(globalThis.history.state, "", "/");
  }, []);

  // Back from Auth0 can restore this page from the browser's cache, mid-redirect or with a
  // button still busy; a card the user can act on replaces either.
  useEffect(() => {
    const restored = (event: PageTransitionEvent) => {
      if (!event.persisted) return;
      setLeaving(null);
      setView((current) => (current === "redirecting" ? "sign-in" : current));
    };
    globalThis.addEventListener("pageshow", restored);
    return () => globalThis.removeEventListener("pageshow", restored);
  }, []);

  async function resend() {
    setResending(true);
    setResent(null);
    try {
      setResent(await resendVerificationEmail());
    } catch {
      setResent("error");
    } finally {
      setResending(false);
    }
  }

  if (view === "redirecting" || view === "signing-out") {
    const label = view === "redirecting" ? "Taking you to sign in" : "Signing out";
    return (
      <div role="status" aria-label={label} className="flex min-h-dvh items-center justify-center bg-zinc-50 dark:bg-zinc-950">
        <Spinner />
      </div>
    );
  }

  const signInButton = <Button label="Sign in" icon={LogIn} busy={leaving === "sign-in"} disabled={leaving === "sign-up"} onClick={() => leave("sign-in")} />;

  if (view === "verify") {
    const outcome = resent && RESENT[resent];
    return (
      <Card title="Check your email" intro="We sent a verification link to your email address. Open it, then sign in.">
        {outcome && (outcome.ok
          ? <p role="status" className="flex items-center gap-2 text-sm text-emerald-700 dark:text-emerald-400"><MailCheck aria-hidden className="size-4" />{outcome.text}</p>
          : <Alert>{outcome.text}</Alert>)}
        <div className="flex flex-col gap-2 [&>button]:w-full">
          {signInButton}
          {/* The API can no longer tell who signed up, so only a new sign-in sends another. */}
          {resent !== "expired" && <Button label="Resend email" icon={RotateCw} variant="secondary" busy={resending} disabled={leaving !== null} onClick={() => void resend()} />}
          <Button label="Use a different account" icon={UserPlus} variant="secondary" busy={leaving === "sign-up"} disabled={leaving === "sign-in"} onClick={() => leave("sign-up")} />
        </div>
      </Card>
    );
  }

  if (view === "invited") {
    return (
      <Card title="You're invited" intro="Sign in, or create an account, with the email address the invitation was sent to. You'll join the workspace as soon as you're in.">
        <div className="flex flex-col gap-2 [&>button]:w-full">
          {signInButton}
          <Button label="Create account" variant="secondary" icon={UserPlus} busy={leaving === "sign-up"} disabled={leaving === "sign-in"} onClick={() => leave("sign-up")} />
        </div>
      </Card>
    );
  }

  const alert = ALERTS[view];
  return (
    <Card title={view === "sign-out-failed" ? "Sign out" : "Sign in"} intro={alert ? undefined : "Welcome back. Pick up where your agents left off."}>
      {alert && <Alert>{alert}</Alert>}
      <div className="flex flex-col gap-2 [&>button]:w-full">
        {signInButton}
        {view === "sign-in" && <Button label="Create account" variant="secondary" icon={UserPlus} busy={leaving === "sign-up"} disabled={leaving === "sign-in"} onClick={() => leave("sign-up")} />}
      </div>
    </Card>
  );
}

function Card({ title, intro, children }: { readonly title: string; readonly intro?: string; readonly children: ReactNode }) {
  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-zinc-50 px-4 py-10 text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100">
      <span aria-hidden className="pointer-events-none absolute -top-40 left-1/2 size-[36rem] -translate-x-1/2 rounded-full bg-linear-to-br from-sky-300/30 via-fuchsia-300/20 to-orange-200/20 blur-3xl dark:from-sky-500/10 dark:via-fuchsia-500/10 dark:to-orange-400/5" />
      <section aria-label={title} className="card relative flex w-full max-w-sm animate-pop-in flex-col gap-5 p-6 shadow-xl sm:p-8">
        <div className="flex items-center gap-2">
          <Logo />
          <span className="font-semibold tracking-tight">workspaced<span className="text-zinc-400">-ai</span></span>
        </div>
        <div>
          <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
          {intro && <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{intro}</p>}
        </div>
        {children}
      </section>
    </main>
  );
}

function Alert({ children }: { readonly children: ReactNode }) {
  return <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-900/70 dark:bg-rose-950/40 dark:text-rose-300">{children}</p>;
}
