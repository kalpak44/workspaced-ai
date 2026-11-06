import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

/** A Markdown file in the workspace that its agent is given in every session. */
export interface InstructionFile {
  /** The file's name, without ".md". */
  readonly name: string;
  readonly content: string;
}

// The API's rule, checked here too so a name never becomes a path: up to 63 letters, digits,
// - and _, starting with a letter or digit.
export const INSTRUCTION_NAME = /^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$/;

function fileUrl(workspaceId: string, name: string): string {
  if (!INSTRUCTION_NAME.test(name)) {
    throw new Error("Invalid instructions name");
  }
  return `${workspaceUrl(workspaceId)}/instructions/${name}`;
}

export async function listInstructions(workspaceId: string): Promise<InstructionFile[]> {
  return (await send(`${workspaceUrl(workspaceId)}/instructions`)).json();
}

export async function saveInstructions(workspaceId: string, name: string, content: string): Promise<InstructionFile> {
  const body = JSON.stringify({ content });
  return (await send(fileUrl(workspaceId, name), { method: "PUT", body })).json();
}

export async function deleteInstructions(workspaceId: string, name: string): Promise<void> {
  await send(fileUrl(workspaceId, name), { method: "DELETE" });
}
