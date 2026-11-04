import { describe, expect, it } from "vitest";
import { parseMcpConfig } from "../src/lib/mcpConfig";

describe("parseMcpConfig", () => {
  it("reads remote and local servers, with or without the mcpServers wrapper", () => {
    const servers = {
      web: { type: "http", url: "https://mcp.test/mcp", headers: { Authorization: "Bearer {env:T}" } },
      local: { command: "npx", args: ["pkg"], env: { KEY: "" } },
      bare: { command: "tool-server" },
    };
    const expected = [
      { name: "web", url: "https://mcp.test/mcp", headers: { Authorization: "Bearer {env:T}" } },
      { name: "local", command: "npx", args: ["pkg"], env: { KEY: "" } },
      { name: "bare", command: "tool-server" },
    ];
    expect(parseMcpConfig(JSON.stringify({ mcpServers: servers }))).toEqual({ servers: expected });
    expect(parseMcpConfig(JSON.stringify(servers))).toEqual({ servers: expected });
  });

  it.each([
    ["nope", "This is not valid JSON"],
    ["[]", "Expected an object of servers"],
    ['{"mcpServers":{}}', "Expected an object of servers"],
    ['{"Bad Name":{"url":"https://x.test"}}', "Bad Name: use lowercase letters"],
    ['{"x":{"url":"https://x.test","command":"npx"}}', "x: give either a url or a command"],
    ['{"x":{}}', "x: give either a url or a command"],
    ['{"x":"npx"}', "x: expected an object"],
    ['{"x":{"command":"npx","args":"pkg"}}', "x: args must be a list of text"],
    ['{"x":{"command":"npx","env":{"K":1}}}', "x: env must map names to text"],
    ['{"x":{"url":"https://x.test","headers":[]}}', "x: headers must map names to text"],
  ])("explains what is wrong with %s", (text, error) => {
    expect(parseMcpConfig(text).error).toContain(error);
  });
});
