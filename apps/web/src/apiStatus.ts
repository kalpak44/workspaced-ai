// Readiness rather than liveness: it includes the database, so "ready" means the API can
// actually serve a session.
export const READINESS_URL = "/api/v1/actuator/health/readiness";

const DOT = "size-2.5 rounded-full";

export async function checkApi(
  dot: HTMLElement,
  label: HTMLElement,
  fetchFn: typeof fetch = fetch,
): Promise<void> {
  try {
    const response = await fetchFn(READINESS_URL);
    const ready = response.ok;
    label.textContent = ready ? "ready" : `unavailable (${response.status})`;
    dot.className = `${DOT} ${ready ? "bg-emerald-400" : "bg-amber-400"}`;
  } catch {
    label.textContent = "unreachable";
    dot.className = `${DOT} bg-rose-500`;
  }
}
