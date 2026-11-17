import { LogIn } from "lucide-react";
import { useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { useTheme } from "../../hooks/useTheme";
import { Button, Logo, TextField } from "../atoms";

// The public site, which hosts the documents a user agrees to by signing in.
const LEGAL_SITE = "https://workspaced.online";

/** Shown instead of the app until someone signs in. */
export function LoginPage({ unreachable = false }: { readonly unreachable?: boolean }) {
  useTheme();
  const { signIn } = useAccount();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(unreachable ? "The API could not be reached. Try again in a moment." : "");

  async function submit() {
    setBusy(true);
    setError("");
    try {
      if (!(await signIn(username.trim(), password))) {
        setError("Wrong username or password.");
        setBusy(false);
      }
    } catch {
      setError("Could not sign in. Try again in a moment.");
      setBusy(false);
    }
  }

  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-zinc-50 px-4 py-10 text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100">
      <span aria-hidden className="pointer-events-none absolute -top-40 left-1/2 size-[36rem] -translate-x-1/2 rounded-full bg-linear-to-br from-sky-300/30 via-fuchsia-300/20 to-orange-200/20 blur-3xl dark:from-sky-500/10 dark:via-fuchsia-500/10 dark:to-orange-400/5" />
      <form
        aria-label="Sign in"
        className="card relative flex w-full max-w-sm animate-pop-in flex-col gap-5 p-6 shadow-xl sm:p-8"
        onSubmit={(event) => { event.preventDefault(); void submit(); }}
      >
        <div className="flex items-center gap-2">
          <Logo />
          <span className="font-semibold tracking-tight">workspaced<span className="text-zinc-400">-ai</span></span>
        </div>
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Sign in</h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">Welcome back. Pick up where your agents left off.</p>
        </div>
        <TextField id="username" label="Username" autoComplete="username" autoFocus required maxLength={100} value={username} onChange={(event) => setUsername(event.target.value)} />
        <TextField id="password" label="Password" type="password" autoComplete="current-password" required maxLength={200} value={password} onChange={(event) => setPassword(event.target.value)} />
        {error && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-900/70 dark:bg-rose-950/40 dark:text-rose-300">{error}</p>}
        <div className="flex flex-col [&>button]:w-full">
          <Button label="Sign in" type="submit" icon={LogIn} busy={busy} disabled={!username.trim() || !password} />
        </div>
        {/* A new tab, so following a link never discards the credentials already typed. */}
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
