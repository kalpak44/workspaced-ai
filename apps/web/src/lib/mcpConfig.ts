import { MCP_NAME, type NewMcpServer } from "../hooks/useMcpServers";

export type ParsedMcpConfig =
  | { readonly servers: readonly NewMcpServer[]; readonly error?: undefined }
  | { readonly error: string; readonly servers?: undefined };

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const strings = (value: unknown): value is Record<string, string> =>
  isObject(value) && Object.values(value).every((v) => typeof v === "string");

/**
 * Reads the `mcpServers` JSON other MCP clients use, or just the object inside it. A server
 * with a `url` is remote and one with a `command` is started in the workspace; `type` is
 * accepted but not needed, since OpenCode finds the transport itself.
 */
export function parseMcpConfig(text: string): ParsedMcpConfig {
  let config: unknown;
  try {
    config = JSON.parse(text);
  } catch {
    return { error: "This is not valid JSON" };
  }
  const entries = isObject(config) && isObject(config.mcpServers) ? config.mcpServers : config;
  if (!isObject(entries) || Object.keys(entries).length === 0) {
    return { error: 'Expected an object of servers, as in { "mcpServers": { "name": { … } } }' };
  }
  const servers: NewMcpServer[] = [];
  for (const [name, server] of Object.entries(entries)) {
    if (!MCP_NAME.test(name)) {
      return { error: `${name}: use lowercase letters, digits, - and _ for the name` };
    }
    if (!isObject(server)) {
      return { error: `${name}: expected an object with a url or a command` };
    }
    const { url, headers, command, args, env } = server;
    if (typeof url === "string" && command === undefined) {
      if (headers !== undefined && !strings(headers)) {
        return { error: `${name}: headers must map names to text` };
      }
      servers.push({ name, url, headers: headers ?? {} });
    } else if (typeof command === "string" && url === undefined) {
      if (args !== undefined && !(Array.isArray(args) && args.every((a) => typeof a === "string"))) {
        return { error: `${name}: args must be a list of text` };
      }
      if (env !== undefined && !strings(env)) {
        return { error: `${name}: env must map names to text` };
      }
      servers.push({ name, command, ...(args && { args }), ...(env && { env }) });
    } else {
      return { error: `${name}: give either a url or a command` };
    }
  }
  return { servers };
}
