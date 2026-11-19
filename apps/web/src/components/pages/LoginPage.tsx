import { LogIn, UserPlus } from "lucide-react";
import { useEffect, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { useTheme } from "../../hooks/useTheme";
import { Button, Logo } from "../atoms";

// The public site, which hosts the documents a user agrees to by signing in.
const LEGAL_SITE = "https://workspaced.online";

// What the API's `/login?error=<code>` redirect means; any other code reads as a plain failure.
const UNVERIFIED = "Your email address is not verified yet. Open the link in the email we sent you, then sign in again.";
const FAILED = "Sign-in failed. Please try again.";

/** Shown instead of the app until someone signs in. */
export function LoginPage({ unreachable = false }: { readonly unreachable?: boolean }) {
  useTheme();
  const { signIn, signUp } = useAccount();
  // Which button is on its way to Auth0, so only that one spins.
  const [leaving, setLeaving] = useState<"sign-in" | "sign-up" | null>(null);
  const [error] = useState(() => {
    if (unreachable) return "The API could not be reached. Try again in a moment.";
    const code = new URLSearchParams(globalThis.location.search).get("error");
    if (code === null) return "";
    return code === "unverified" ? UNVERIFIED : FAILED;
  });

  // Dropped once read, so a reload does not show an old failure again.
  useEffect(() => {
    if (new URLSearchParams(globalThis.location.search).has("error")) {
      globalThis.history.replaceState(globalThis.history.state, "", "/");
    }
  }, []);

  // Back from Auth0 can restore this page from the browser's cache with the button still busy.
  useEffect(() => {
    const restored = (event: PageTransitionEvent) => event.persisted && setLeaving(null);
    globalThis.addEventListener("pageshow", restored);
    return () => globalThis.removeEventListener("pageshow", restored);
  }, []);

  function leave(to: "sign-in" | "sign-up") {
    setLeaving(to);
    if (to === "sign-up") signUp();
    else signIn();
  }

  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-zinc-50 px-4 py-10 text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100">
      <span aria-hidden className="pointer-events-none absolute -top-40 left-1/2 size-[36rem] -translate-x-1/2 rounded-full bg-linear-to-br from-sky-300/30 via-fuchsia-300/20 to-orange-200/20 blur-3xl dark:from-sky-500/10 dark:via-fuchsia-500/10 dark:to-orange-400/5" />
      <form
        aria-label="Sign in"
        className="card relative flex w-full max-w-sm animate-pop-in flex-col gap-5 p-6 shadow-xl sm:p-8"
        onSubmit={(event) => { event.preventDefault(); leave("sign-in"); }}
      >
        <div className="flex items-center gap-2">
          <Logo />
          <span className="font-semibold tracking-tight">workspaced<span className="text-zinc-400">-ai</span></span>
        </div>
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Sign in</h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">Welcome back. Pick up where your agents left off.</p>
        </div>
        {error && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-900/70 dark:bg-rose-950/40 dark:text-rose-300">{error}</p>}
        <div className="flex flex-col gap-2 [&>button]:w-full">
          <Button label="Sign in" type="submit" icon={LogIn} busy={leaving === "sign-in"} disabled={leaving === "sign-up"} />
          <Button label="Create account" variant="secondary" icon={UserPlus} busy={leaving === "sign-up"} disabled={leaving === "sign-in"} onClick={() => leave("sign-up")} />
        </div>
        <p className="text-center text-xs text-zinc-500 dark:text-zinc-400">
          A new account gets a verification email. Open its link before you sign in for the first time.
        </p>
        {/* A new tab, so reading the terms never leaves the sign-in page. */}
        <p className="text-center text-xs text-zinc-500 dark:text-zinc-400">
          By signing in you agree to the{" "}
          <a href={`${LEGAL_SITE}/terms`} target="_blank" rel="noopener noreferrer" className="underline underline-offset-2 hover:text-zinc-900 dark:hover:text-zinc-100">Terms of service</a>
          {" "}and{" "}
          <a href={`${LEGAL_SITE}/privacy`} target="_blank" rel="noopener noreferrer" className="underline underline-offset-2 hover:text-zinc-900 dark:hover:text-zinc-100">Privacy policy</a>.
        </p>
      </form>
    </main>
  );
}
