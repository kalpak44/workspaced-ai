import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

/** An environment variable the workspace starts with, kept in its .env file. */
export interface Variable {
  readonly name: string;
  readonly value: string;
}

// The API's rules, checked here too so the form can say what is wrong before saving.
export const VARIABLE_NAME = /^[A-Za-z_]\w*$/;
export const MAX_VARIABLE_VALUE = 4096;
export const MAX_VARIABLES = 200;

function variablesUrl(workspaceId: string): string {
  return `${workspaceUrl(workspaceId)}/variables`;
}

export async function listVariables(workspaceId: string): Promise<Variable[]> {
  return (await send(variablesUrl(workspaceId))).json();
}

/** Replaces them all, in this order. */
export async function saveVariables(workspaceId: string, variables: readonly Variable[]): Promise<Variable[]> {
  const body = JSON.stringify(variables);
  return (await send(variablesUrl(workspaceId), { method: "PUT", body })).json();
}
