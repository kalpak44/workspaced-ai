import { useCallback, useEffect, useRef, useState } from "react";
import {
  getGeneralInstructions,
  getWorkspaceInstructions,
  type SavedInstructions,
  setGeneralInstructions,
  setWorkspaceInstructions,
} from "../api/instructionsApi";
import { readStored } from "../lib/storage";

export type { SavedInstructions } from "../api/instructionsApi";

// Where instructions were kept before they moved to the account; read once, as a draft.
const LEGACY_KEY = "workspaced:general-settings";

/** What this browser held before instructions moved to the account, or "" if nothing. */
function legacyDraft(): string {
  try {
    const parsed = JSON.parse(readStored(LEGACY_KEY) ?? "{}");
    return typeof parsed?.instructions === "string" ? parsed.instructions : "";
  } catch {
    return "";
  }
}

function forgetLegacy() {
  try {
    globalThis.localStorage.removeItem(LEGACY_KEY);
  } catch {
    // Nothing stored, or storage unavailable: nothing to forget.
  }
}

/**
 * The signed-in user's general instructions, given to the agent in all their workspaces. `draft`
 * is what the editor starts from: the saved text or, while the account has none, what this browser
 * held before instructions were saved to the account.
 */
export function useGeneralInstructions() {
  const [saved, setSaved] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getGeneralInstructions().then(
      (instructions) => {
        if (!active) return;
        setSaved(instructions);
        setDraft(instructions || legacyDraft());
      },
      () => active && setError("Could not load your instructions."),
    );
    return () => {
      active = false;
    };
  }, []);

  /** False when the API refused or could not be reached; the draft is kept either way. */
  async function save(instructions: string): Promise<boolean> {
    try {
      setSaved(await setGeneralInstructions(instructions));
      forgetLegacy();
      return true;
    } catch {
      return false;
    }
  }

  return { saved, draft, setDraft, loading: saved === null && error === null, error, save };
}

/** A workspace's own instructions and its harness's guide, re-read when the workspace changes. */
export function useWorkspaceInstructions(workspaceId: string) {
  const [saved, setSaved] = useState<string | null>(null);
  const [guide, setGuide] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(() => {
    const current = ++generation.current;
    setSaved(null);
    setGuide(null);
    setError(null);
    getWorkspaceInstructions(workspaceId).then(
      (loaded) => {
        if (current !== generation.current) return;
        setSaved(loaded.instructions);
        setGuide(loaded.guide);
      },
      () => current === generation.current && setError("Could not load the workspace's instructions."),
    );
  }, [workspaceId]);

  useEffect(() => {
    load();
    return () => {
      generation.current++;
    };
  }, [load]);

  /** Null when the API refused or could not be reached. */
  async function save(instructions: string): Promise<SavedInstructions | null> {
    try {
      const result = await setWorkspaceInstructions(workspaceId, instructions);
      setSaved(result.instructions);
      return result;
    } catch {
      return null;
    }
  }

  return { saved, guide, loading: saved === null && error === null, error, retry: load, save };
}
