import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

/** A workspace's own instructions, and the read-only guide its harness gives the agent first. */
export interface WorkspaceInstructions {
  readonly instructions: string;
  /** Null while the workspace is not running, since the guide comes from its harness. */
  readonly guide: string | null;
}

export interface SavedInstructions {
  readonly instructions: string;
  /** Whether the running workspace has them already; otherwise it gets them on its next start. */
  readonly applied: boolean;
}

const GENERAL_URL = "/api/v1/account/instructions";

export async function getGeneralInstructions(): Promise<string> {
  return (await (await send(GENERAL_URL)).json()).instructions;
}

export async function setGeneralInstructions(instructions: string): Promise<string> {
  const body = JSON.stringify({ instructions });
  return (await (await send(GENERAL_URL, { method: "PUT", body })).json()).instructions;
}

export async function getWorkspaceInstructions(workspaceId: string): Promise<WorkspaceInstructions> {
  return (await send(`${workspaceUrl(workspaceId)}/instructions`)).json();
}

export async function setWorkspaceInstructions(workspaceId: string, instructions: string): Promise<SavedInstructions> {
  const body = JSON.stringify({ instructions });
  return (await send(`${workspaceUrl(workspaceId)}/instructions`, { method: "PUT", body })).json();
}
