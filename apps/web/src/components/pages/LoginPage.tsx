import { LogIn } from "lucide-react";
import { useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { useTheme } from "../../hooks/useTheme";
import { Button, Logo, TextField } from "../atoms";

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
    <main className="flex min-h-dvh items-center justify-center bg-white px-4 text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100">
      <form
        aria-label="Sign in"
        className="flex w-full max-w-sm flex-col gap-5 rounded-xl border border-zinc-200 p-6 dark:border-zinc-800"
        onSubmit={(event) => { event.preventDefault(); void submit(); }}
      >
        <div className="flex items-center gap-2">
          <Logo />
          <span className="font-medium">workspaced-ai</span>
        </div>
        <h1 className="text-lg font-semibold">Sign in</h1>
        <TextField id="username" label="Username" autoComplete="username" autoFocus required maxLength={100} value={username} onChange={(event) => setUsername(event.target.value)} />
        <TextField id="password" label="Password" type="password" autoComplete="current-password" required maxLength={200} value={password} onChange={(event) => setPassword(event.target.value)} />
        {error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>}
        <div className="flex justify-end">
          <Button label="Sign in" type="submit" icon={LogIn} busy={busy} disabled={!username.trim() || !password} />
        </div>
      </form>
    </main>
  );
}
