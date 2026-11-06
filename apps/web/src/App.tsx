import { Spinner } from "./components/atoms";
import { LoginPage, WorkspacesPage } from "./components/pages";
import { useAccount } from "./hooks/useAccount";

export function App() {
  const { state } = useAccount();
  if (state.status === "checking") {
    return (
      <div role="status" aria-label="Loading" className="flex min-h-dvh items-center justify-center">
        <Spinner />
      </div>
    );
  }
  if (state.status === "signed-out") {
    return <LoginPage unreachable={state.unreachable} />;
  }
  return <WorkspacesPage />;
}
