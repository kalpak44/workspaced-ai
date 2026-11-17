import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CreateWorkspaceDialog } from "../src/components/organisms/CreateWorkspaceDialog";
import { useWorkspaces } from "../src/hooks/useWorkspaces";
import { OPENROUTER_CHANNEL, relayOpenRouterCallback } from "../src/lib/openRouterCallback";
import { installFakeApi, REJECTED_KEY } from "./fakeApi";

const KEYS_URL = "https://openrouter.ai/api/v1/auth/keys";

let api: ReturnType<typeof installFakeApi>;
let popup: { location: { href: string }; close: ReturnType<typeof vi.fn> } | null;
let exchanges: { code: string; code_verifier: string; code_challenge_method: string }[];
let exchangeStatus: number;
let issuedKey: string;
let closed: ReturnType<typeof vi.fn<() => void>>;

beforeEach(() => {
  api = installFakeApi();
  popup = { location: { href: "" }, close: vi.fn() };
  exchanges = [];
  exchangeStatus = 200;
  issuedKey = "sk-or-v1-issued";
  closed = vi.fn<() => void>();
  vi.stubGlobal("open", vi.fn(() => popup));
  // OpenRouter's key exchange, in front of the fake API that answers everything else.
  vi.stubGlobal("fetch", (url: string, init?: RequestInit) => {
    if (url === KEYS_URL) {
      exchanges.push(JSON.parse(String(init?.body)));
      return Promise.resolve(new Response(JSON.stringify({ key: issuedKey }), { status: exchangeStatus }));
    }
    return api.fetchFn(url, init);
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** The dialog working through the real hook, so drafts and launches reach the fake API. */
function Dialog() {
  const { create, draft, launch, discardDraft } = useWorkspaces();
  return (
    <CreateWorkspaceDialog
      initialName="Research"
      onCreate={(workspace) => create(workspace)}
      onDraft={draft}
      onLaunch={(id, key) => launch(id, key)}
      onDiscard={discardDraft}
      onClose={closed}
    />
  );
}

/** The authorization URL the popup was sent to, once the draft exists and PKCE is computed. */
async function authorization(): Promise<URL> {
  await waitFor(() => expect(popup!.location.href).not.toBe(""));
  return new URL(popup!.location.href);
}

/** Plays OpenRouter sending the user back to the callback page. */
function callBack(code: string | null, state: string | null) {
  const channel = new BroadcastChannel(OPENROUTER_CHANNEL);
  channel.postMessage({ code, state });
  channel.close();
}

async function openProviderStep() {
  const user = userEvent.setup();
  render(<Dialog />);
  await user.click(screen.getByRole("button", { name: "Next" }));
  await screen.findByRole("radio", { name: /cx23/ });
  await user.click(screen.getByRole("button", { name: "Next" }));
  await screen.findByLabelText("OpenRouter API key");
  return user;
}

describe("Connect OpenRouter", () => {
  it("labels the key with a draft's id and launches that draft with it", async () => {
    const user = await openProviderStep();
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));
    expect(screen.getByRole("status")).toHaveTextContent("Approve the key in the OpenRouter window.");

    const url = await authorization();
    const [draftId] = [...api.drafts.keys()];
    expect(url.origin + url.pathname).toBe("https://openrouter.ai/auth");
    expect(url.searchParams.get("callback_url")).toBe(`${globalThis.location.origin}/connect/openrouter`);
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("key_label")).toBe(`workspaced: ${draftId}`);
    // A draft is not a workspace yet: nothing is listed until it is launched.
    expect(api.rows.map((w) => w.id)).not.toContain(draftId);

    callBack("the-code", url.searchParams.get("state"));
    await waitFor(() => expect(closed).toHaveBeenCalled());
    expect(api.launched).toEqual([{ id: draftId, apiKey: "sk-or-v1-issued" }]);
    expect(api.rows.map((w) => w.id)).toContain(draftId);

    // The verifier must hash to the challenge OpenRouter was given, or it refuses the code.
    const [exchange] = exchanges;
    expect(exchange).toMatchObject({ code: "the-code", code_challenge_method: "S256" });
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(exchange.code_verifier));
    const challenge = btoa(String.fromCodePoint(...new Uint8Array(digest)))
      .replaceAll("+", "-")
      .replaceAll("/", "_")
      .replace(/=+$/, "");
    expect(url.searchParams.get("code_challenge")).toBe(challenge);
  });

  it("keeps one draft across retries and launches it with a pasted key instead", async () => {
    const user = await openProviderStep();
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));
    await authorization();
    popup!.location.href = "";
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));
    await authorization();
    expect(api.drafts.size).toBe(1);

    await user.type(screen.getByLabelText("OpenRouter API key"), "sk-or-v1-pasted");
    await user.click(screen.getByRole("button", { name: "Create workspace" }));
    await waitFor(() => expect(closed).toHaveBeenCalled());
    expect(api.launched).toEqual([{ id: expect.any(String), apiKey: "sk-or-v1-pasted" }]);
    expect(api.drafts.size).toBe(0);
  });

  it("keeps the draft when the issued key is rejected, so it can be launched again", async () => {
    issuedKey = REJECTED_KEY;
    const user = await openProviderStep();
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));
    callBack("the-code", (await authorization()).searchParams.get("state"));

    expect(await screen.findByText("OpenRouter did not accept this key")).toBeInTheDocument();
    expect(api.drafts.size).toBe(1);
    expect(closed).not.toHaveBeenCalled();
  });

  it("discards the draft when the dialog is left without launching it", async () => {
    const user = await openProviderStep();
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));
    await authorization();
    expect(api.drafts.size).toBe(1);

    await user.click(screen.getByRole("button", { name: "Back" }));
    await waitFor(() => expect(api.drafts.size).toBe(0));
  });

  it("closes the window and says why when no draft can be made", async () => {
    api.state.noFunds = true;
    const user = await openProviderStep();
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));

    await waitFor(() => expect(popup!.close).toHaveBeenCalled());
    expect(popup!.location.href).toBe("");
    expect(await screen.findByText(/does not cover/)).toBeInTheDocument();
  });

  it("ignores a code returned for another attempt", async () => {
    const user = await openProviderStep();
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));
    await authorization();
    callBack("forged", "not-our-state");
    // Give the channel time to deliver before asserting nothing happened.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(exchanges).toEqual([]);
    expect(api.launched).toEqual([]);
  });

  it("says so when the browser blocks the window", async () => {
    popup = null;
    const user = await openProviderStep();
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Allow pop-ups for this site");
    expect(api.drafts.size).toBe(0);
  });

  it("offers a retry when OpenRouter refuses the exchange", async () => {
    exchangeStatus = 403;
    const user = await openProviderStep();
    await user.click(screen.getByRole("button", { name: "Connect OpenRouter" }));
    callBack("expired", (await authorization()).searchParams.get("state"));
    expect(await screen.findByRole("alert")).toHaveTextContent("OpenRouter did not issue a key");
    expect(screen.getByLabelText("OpenRouter API key")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Connect OpenRouter" })).toBeEnabled();
  });
});

describe("relayOpenRouterCallback", () => {
  it("leaves every other page to the app", () => {
    expect(relayOpenRouterCallback({ pathname: "/", search: "?code=x" } as Location)).toBe(false);
  });

  it("hands the code to the window that asked and drops it from the address bar", async () => {
    const received = new Promise((resolve) => {
      const channel = new BroadcastChannel(OPENROUTER_CHANNEL);
      channel.onmessage = ({ data }) => {
        channel.close();
        resolve(data);
      };
    });
    vi.stubGlobal("close", vi.fn());
    globalThis.history.replaceState(null, "", "/connect/openrouter?code=c1&state=s1");

    expect(relayOpenRouterCallback()).toBe(true);
    expect(await received).toEqual({ code: "c1", state: "s1" });
    expect(globalThis.location.search).toBe("");
    expect(document.body).toHaveTextContent("You can close this window.");
    expect(globalThis.close).toHaveBeenCalled();
    globalThis.history.replaceState(null, "", "/");
  });
});
