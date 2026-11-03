import "./style.css";

const dot = document.querySelector<HTMLSpanElement>("#api-dot")!;
const label = document.querySelector<HTMLSpanElement>("#api-status")!;

// Readiness rather than liveness: it includes the database, so "ready" means the API can
// actually serve a session.
async function checkApi(): Promise<void> {
  try {
    const response = await fetch("/api/v1/actuator/health/readiness");
    const ready = response.ok;
    label.textContent = ready ? "ready" : `unavailable (${response.status})`;
    dot.className = `size-2.5 rounded-full ${ready ? "bg-emerald-400" : "bg-amber-400"}`;
  } catch {
    label.textContent = "unreachable";
    dot.className = "size-2.5 rounded-full bg-rose-500";
  }
}

void checkApi();
