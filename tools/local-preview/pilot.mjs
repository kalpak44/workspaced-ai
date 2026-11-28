#!/usr/bin/env node
import { createServer } from "node:http";
import { spawn, spawnSync } from "node:child_process";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { LocalPreview, withState } from "./client.mjs";

/** The listener starts before authorization and closes on every outcome. Never logs callback URLs. */
export async function signIn(client, host, account, openBrowser) {
  let resolve, reject;
  const callback = new Promise((yes, no) => { resolve = yes; reject = no; });
  const server = createServer((request, response) => {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    const expected = `127.0.0.1:${server.address().port}`;
    if (request.method !== "GET" || request.headers.host !== expected ||
        !request.url.startsWith("/auth/callback?")) { response.writeHead(404).end(); return; }
    response.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" }).end("Return to the local terminal for the sign-in result.");
    resolve(`http://${expected}${request.url}`);
  });
  await new Promise((yes, no) => { server.once("error", no); server.listen(0, "127.0.0.1", yes); });
  const timer = setTimeout(() => reject(new Error("Local sign-in timed out")), 300000);
  try {
    const redirect = `http://127.0.0.1:${server.address().port}/auth/callback`;
    const attempt = await client.begin(host, redirect, account);
    await openBrowser(attempt.url);
    return await client.finish(await callback);
  } finally {
    clearTimeout(timer);
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    client.pending.clear();
  }
}

async function main() {
  const [command, id, model] = process.argv.slice(2);
  if (command === "check" && !id) {
    const result = spawnSync(process.execPath, ["--test", fileURLToPath(new URL("./client.test.mjs", import.meta.url))], { stdio: "inherit" });
    process.exitCode = result.status ?? 1;
    return;
  }
  if (!["sign-in", "run", "sign-out"].includes(command) ||
      (id && !/^[0-9a-f-]{36}$/.test(id)) || (command !== "sign-in" && !id) ||
      (command === "run" && !model) || (command !== "run" && model) || process.argv.length > 5) {
    throw new Error("Usage: pilot.mjs check | sign-in [ACCOUNT_ID] | run ACCOUNT_ID MODEL | sign-out ACCOUNT_ID");
  }
  const directory = path.join(os.homedir(), ".local", "state", "workspaced-local-preview");
  await withState(directory, async (store, host) => {
    const client = new LocalPreview();
    let account = id ? await store.read(`${id}.json`) : null;
    if (id && !account) throw new Error("Unknown local account registration");
    if (command === "sign-in") {
      account = await signIn(client, host, account, async url => {
        const child = spawn(process.platform === "darwin" ? "open" : "xdg-open", [url], { stdio: "ignore" });
        await new Promise((resolve, reject) => { child.once("error", () => reject(new Error("Could not open the system browser"))); child.once("exit", code => code === 0 ? resolve() : reject(new Error("Could not open the system browser"))); });
      });
      await store.write(`${account.id}.json`, account);
      console.log(`Local account registration: ${account.id}. Hosted Workspaced remains disconnected.`);
    } else if (command === "sign-out") {
      let revoked = false;
      try { revoked = await client.revoke(account); } catch { /* Clear local tokens even when provider revocation is unavailable. */ }
      await store.write(`${account.id}.json`, { id: account.id, clientId: account.clientId, subject: account.subject });
      console.log(revoked ? "Local tokens cleared; renewable session revoked." : "Local tokens cleared. Remote revocation was not confirmed; disconnect in ChatGPT Settings.");
    } else {
      if (!account.accessToken) throw new Error("Sign in to this local registration first");
      if (account.expiresAt <= Date.now() + 60000) {
        account = await client.refresh(account);
        await store.write(`${account.id}.json`, account);
      }
      console.log(JSON.stringify(await client.infer(account, model)));
    }
  });
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => { console.error("Local preview failed. Check network, account eligibility, file permissions and registration; reconnect if necessary. No hosted connector was enabled."); process.exitCode = 1; });
}
