// Readiness rather than liveness: it includes the database, so "ready" means the API can
// actually serve a session.
export const READINESS_URL = "/api/v1/actuator/health/readiness";

export type ApiStatus = "checking" | "ready" | "unavailable" | "unreachable";

export async function fetchApiStatus(fetchFn: typeof fetch = fetch): Promise<ApiStatus> {
  try {
    const response = await fetchFn(READINESS_URL);
    return response.ok ? "ready" : "unavailable";
  } catch {
    return "unreachable";
  }
}
