---
title: Ciela legal research for your agent
summary: Build, install and test an MCP server for Ciela, the Bulgarian legal reference system, so the agent searches legislation and case law there and answers with quotes and source links.
category: data
tags: [ciela, mcp, legal, bulgaria, research]
visibility: public
---

# Ciela legal research for your agent

Connect this workspace's agent to **Ciela** (`web7.ciela.net`), a Bulgarian **справочно-правна
система** (справочен и правен софтуер) with legislation, case law and interpretive decisions.
Every file is given in full below. **Write each one exactly as shown**: do not rewrite, shorten
or "improve" the code, because it has been run and checked as it is. Do every step in order, and
answer in the user's language.

When you are done, the user asks a question in any session ("what does the law say about…",
"провери в Сиела…") and the agent searches Ciela, reads the document and answers with the quoted
text and a link to the source.

The user needs a Ciela account with web access. Tell them at the start, before any work, and stop
if they have none.

## What you are building

A small MCP server in `~/mcp/ciela/`. OpenCode starts it itself over stdio, so there is no port,
no service and nothing reachable from outside the workspace. It has two tools:

- **`ciela_search`**: full-text search over all of Ciela, best match first. It returns each
  result's title, date, relevance score, a `contentHref` for reading it, and a `citationUrl`, the
  link to the document on Ciela's site.
- **`ciela_get_document`**: the plain text of one result, one page at a time, with its title and
  `citationUrl`.

It talks to Ciela the way the Ciela web app does. This is not a published API, so if Ciela
changes its site, these are the places to look:

| Step | Call |
|------|------|
| Sign in | `POST /proxy/api/token` with `{"username","password"}`, which answers `{"token": "<JWT>"}`. The token is cached until a minute before its `exp`. |
| Start a search | `POST /proxy/api/CSearch?limit=…&offset=0&orderBy=score desc` with `{"searchPhrase","onlyInDbs": null}`, which answers `searchContent.first.href` and `searchContent.searchGuid`. |
| Search results | `GET` that `href`, which answers `value[]` (each with `id`, `dbId`, `title`, `dateCommon`, `score`, `href`) and `size`. |
| Document | `GET` a result's `href`. The text is every `render` HTML fragment in the JSON. |
| Source link | `/{dbId}/chighlight/{id}/gethighlightnavigation/{searchGuid}/2147483647/0` |

Every call carries the token as `Authorization: Bearer`, times out after 30 seconds, and is
refused unless it goes to Ciela's own address: a `contentHref` comes from a tool call, and an
unchecked one could send the user's token anywhere. A `401` means Ciela ended the session
(another sign-in elsewhere), so the server signs in again once. The password and token never
appear in a log, a tool answer or an error.

## 1. Check the basics

```sh
node --version   # must be v20 or newer
grep -n '"ciela"' ~/.config/opencode/opencode.jsonc 2>/dev/null || echo "no ciela server yet"
```

If a `ciela` server is already configured, ask the user whether to replace it before going on.

## 2. Write the server

Create the folder with `mkdir -p ~/mcp/ciela`, then write these four files into it.

### `~/mcp/ciela/package.json`

```json
{
  "name": "ciela-mcp",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "description": "MCP server for the Ciela Bulgarian legal database",
  "main": "server.js",
  "engines": {
    "node": ">=20"
  },
  "scripts": {
    "start": "node server.js",
    "check": "node check.js"
  },
  "dependencies": {
    "@modelcontextprotocol/sdk": "^1.32.2",
    "zod": "^4.6.7"
  }
}
```

### `~/mcp/ciela/ciela.js`: the Ciela client

```js
// Client for Ciela's web API: the same endpoints the Ciela web app uses. Not a public API, so a
// change on Ciela's side may need a change here.

const ORIGIN = new URL(process.env.CIELA_BASE_URL || 'https://web7.ciela.net').origin;
const TIMEOUT_MS = 30_000;
// Sign in again this long before the token expires, so no request carries a token that dies
// on the way.
const REFRESH_MARGIN_S = 60;
const DOCUMENT_CACHE_SIZE = 20;
const DOCUMENT_CACHE_MS = 10 * 60_000;

/** A failure worth showing the agent as it is; anything else is a bug and is logged. */
export class CielaError extends Error {}

let token = null;
let expiresAt = 0;
let signingIn = null;
const documents = new Map();

function credentials() {
  const username = process.env.CIELA_USERNAME?.trim();
  const password = process.env.CIELA_PASSWORD;
  if (!username || !password) {
    throw new CielaError(
      'CIELA_USERNAME and CIELA_PASSWORD are not set: add them under Secrets and restart the workspace'
    );
  }
  return { username, password };
}

function jwtExpiry(jwt) {
  try {
    const payload = JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString('utf8'));
    return Number(payload.exp) || 0;
  } catch {
    return 0;
  }
}

async function signIn() {
  const response = await fetch(`${ORIGIN}/proxy/api/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(credentials()),
    signal: AbortSignal.timeout(TIMEOUT_MS)
  });
  // The body of a refused sign-in is never passed on: it may echo what was sent.
  if ([400, 401, 403].includes(response.status)) {
    throw new CielaError('Ciela refused the username or password: correct the secrets and restart');
  }
  if (!response.ok) {
    throw new CielaError(`Ciela sign-in failed: HTTP ${response.status}`);
  }
  const body = await response.json();
  if (typeof body?.token !== 'string') {
    throw new CielaError('Ciela sign-in answered without a token');
  }
  token = body.token;
  expiresAt = jwtExpiry(token);
}

/** Calls made at the same time share one sign-in. */
function refresh() {
  signingIn ??= signIn().finally(() => {
    signingIn = null;
  });
  return signingIn;
}

async function currentToken() {
  if (!token || expiresAt - Date.now() / 1000 < REFRESH_MARGIN_S) {
    await refresh();
  }
  return token;
}

// A contentHref arrives from a tool call, so it is checked: an unchecked one would send the
// user's token to whatever address it named.
function onCiela(url) {
  const target = new URL(url, ORIGIN);
  if (target.origin !== ORIGIN) {
    throw new CielaError(`refusing a link outside ${ORIGIN}`);
  }
  return target;
}

async function request(url, init = {}) {
  const target = onCiela(url);
  const send = async () =>
    fetch(target, {
      ...init,
      headers: { Accept: 'application/json', ...init.headers, Authorization: `Bearer ${await currentToken()}` },
      signal: AbortSignal.timeout(TIMEOUT_MS)
    });
  let response = await send();
  // Ciela ends a session when the same account signs in elsewhere: sign in again, once.
  if (response.status === 401) {
    token = null;
    response = await send();
  }
  return response;
}

async function json(response, step) {
  if (!response.ok) {
    throw new CielaError(`Ciela ${step} failed: HTTP ${response.status}`);
  }
  return response.json();
}

function citation(dbId, docId, searchGuid) {
  return searchGuid
    ? `${ORIGIN}/${dbId}/chighlight/${docId}/gethighlightnavigation/${searchGuid}/2147483647/0`
    : null;
}

/** Full-text search, best match first. Exact phrases rank highest. */
export async function search(query, limit = 25) {
  const started = await json(
    await request(`/proxy/api/CSearch?limit=${limit}&offset=0&orderBy=${encodeURIComponent('score desc')}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ searchPhrase: query, onlyInDbs: null })
    }),
    'search'
  );
  const content = started?.searchContent ?? {};
  const resultsHref = content.first?.href;
  if (!resultsHref) {
    return { totalCount: content.size ?? 0, results: [] };
  }
  const searchGuid = content.searchGuid ?? /searchcontent\/([0-9a-f-]+)/i.exec(resultsHref)?.[1];
  const page = await json(await request(resultsHref), 'search results');
  return {
    totalCount: page.size ?? 0,
    results: (page.value ?? []).map((entry) => ({
      id: entry.id,
      dbId: entry.dbId,
      title: entry.title,
      date: entry.dateCommon ?? null,
      score: entry.score,
      contentHref: entry.href,
      citationUrl: citation(entry.dbId, entry.id, searchGuid)
    }))
  };
}

const ENTITIES = { nbsp: ' ', amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'" };

function plainText(html) {
  return html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|li|tr|h[1-6])>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&(nbsp|amp|lt|gt|quot|#39);/g, (_, name) => ENTITIES[name])
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

// A document is a tree of sections, each carrying its HTML in a `render` field.
function renders(node, found = []) {
  if (Array.isArray(node)) {
    node.forEach((child) => renders(child, found));
  } else if (node && typeof node === 'object') {
    if (typeof node.render === 'string') found.push(node.render);
    for (const value of Object.values(node)) {
      if (value && typeof value === 'object') renders(value, found);
    }
  }
  return found;
}

/**
 * A document's title, source link and plain text. Kept for a few minutes, so reading a long one
 * page by page fetches it once.
 */
export async function getDocument(contentHref) {
  const key = onCiela(contentHref).href;
  const cached = documents.get(key);
  if (cached && cached.expires > Date.now()) return cached.document;

  const body = await json(await request(key), 'document');
  const ids = /\/api\/(\d+)\/chighlight\/(\d+)\/chighlightdocument\/([0-9a-f-]+)/i.exec(key);
  const document = {
    title: body?.navigation?.item?.titleInStructure ?? body?.navigation?.searchPhrase ?? null,
    citationUrl: ids ? citation(ids[1], ids[2], ids[3]) : null,
    text: renders(body).map(plainText).filter(Boolean).join('\n\n')
  };
  documents.delete(key);
  documents.set(key, { document, expires: Date.now() + DOCUMENT_CACHE_MS });
  if (documents.size > DOCUMENT_CACHE_SIZE) documents.delete(documents.keys().next().value);
  return document;
}
```

### `~/mcp/ciela/server.js`: the MCP server

```js
#!/usr/bin/env node
// Ciela as MCP tools, over stdio: OpenCode starts this process itself. Standard output carries
// the protocol, so every log line goes to standard error.
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { CielaError, getDocument, search } from './ciela.js';

const server = new McpServer({ name: 'ciela', version: '1.0.0' });

function ok(value) {
  return { content: [{ type: 'text', text: JSON.stringify(value, null, 2) }] };
}

function failed(tool, error) {
  let message;
  if (error instanceof CielaError) {
    message = error.message;
  } else if (error?.name === 'TimeoutError') {
    message = 'Ciela did not answer in time; try again';
  } else {
    message = 'unexpected error, see the server log';
    console.error(`${tool}:`, error);
  }
  return { isError: true, content: [{ type: 'text', text: `${tool}: ${message}` }] };
}

server.registerTool(
  'ciela_search',
  {
    title: 'Search Ciela',
    description:
      'Search Ciela (web7.ciela.net), the Bulgarian legal reference system: legislation, court ' +
      'decisions, interpretive cases and other legal documents. Use it whenever the user mentions ' +
      'Ciela in any spelling (Ciela, Siela, Сиела, Циела) or asks what Bulgarian law or case law says. ' +
      'Exact phrase matches rank highest: pass the full title or citation when you know it. Use a ' +
      'higher limit (50-100) for broad research. Pass a result\'s contentHref to ciela_get_document ' +
      'to read it, and always give its citationUrl as the source.',
    inputSchema: {
      query: z.string().trim().min(1).max(500).describe('Search phrase, ideally the full title or citation'),
      limit: z.number().int().min(1).max(100).default(25).describe('How many results, 1-100')
    }
  },
  async ({ query, limit }) => {
    try {
      return ok(await search(query, limit));
    } catch (error) {
      return failed('ciela_search', error);
    }
  }
);

server.registerTool(
  'ciela_get_document',
  {
    title: 'Read a Ciela document',
    description:
      'The plain text of a Ciela document, from the contentHref of a ciela_search result, one page ' +
      'at a time. Returns the title, its citationUrl (always give it as the source when you quote ' +
      'or summarize), totalChars, and nextOffset, which is null on the last page. Read further ' +
      'pages only when the part you need is not in the first.',
    inputSchema: {
      contentHref: z.url().describe('The contentHref of a ciela_search result'),
      offset: z.number().int().min(0).default(0).describe('Where the page starts, in characters'),
      maxChars: z.number().int().min(1000).max(50_000).default(20_000).describe('Page length')
    }
  },
  async ({ contentHref, offset, maxChars }) => {
    try {
      const { title, citationUrl, text } = await getDocument(contentHref);
      const end = offset + maxChars;
      return ok({
        title,
        citationUrl,
        totalChars: text.length,
        offset,
        text: text.slice(offset, end),
        nextOffset: end < text.length ? end : null
      });
    } catch (error) {
      return failed('ciela_get_document', error);
    }
  }
);

await server.connect(new StdioServerTransport());
console.error('ciela MCP server ready');
```

### `~/mcp/ciela/check.js`: the self-test

```js
#!/usr/bin/env node
// Starts server.js the way OpenCode does and runs one search and one read. Prints titles and
// links, never the credentials. Exits non-zero on any failure.
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const query = process.argv[2] ?? 'Закон за задълженията и договорите';

function parse(result) {
  const text = result.content?.[0]?.text ?? '';
  if (result.isError) throw new Error(text);
  return JSON.parse(text);
}

const client = new Client({ name: 'ciela-check', version: '1.0.0' });
// The transport passes only a few safe variables by default; the server needs the credentials.
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [fileURLToPath(new URL('./server.js', import.meta.url))],
  env: { ...process.env },
  stderr: 'inherit'
});

try {
  await client.connect(transport);
  const { tools } = await client.listTools();
  console.log('tools:', tools.map((tool) => tool.name).join(', '));

  const found = parse(await client.callTool({ name: 'ciela_search', arguments: { query, limit: 3 } }));
  console.log(`search "${query}": ${found.totalCount} results`);
  for (const result of found.results) console.log(`- ${result.title} (${result.date ?? 'no date'})`);
  if (found.results.length === 0) throw new Error('no results to read');

  const top = found.results[0];
  const document = parse(
    await client.callTool({ name: 'ciela_get_document', arguments: { contentHref: top.contentHref, maxChars: 1000 } })
  );
  console.log(`read: ${document.title}, ${document.totalChars} characters`);
  console.log(`source: ${document.citationUrl}`);
  console.log(document.text.slice(0, 500));
  console.log('OK');
} catch (error) {
  console.error('FAILED:', error.message);
  process.exitCode = 1;
} finally {
  await client.close();
}
```

Then install and check the files:

```sh
cd ~/mcp/ciela
npm install --no-audit --no-fund
node --check ciela.js && node --check server.js && node --check check.js && echo "syntax ok"
env -u CIELA_USERNAME -u CIELA_PASSWORD node check.js
```

The last command must print `tools: ciela_search, ciela_get_document`, then fail with
`CIELA_USERNAME and CIELA_PASSWORD are not set`. That shows the server starts and answers before
any credentials exist. Anything else means a file was not written exactly: compare it with this
recipe and fix it.

## 3. Register it with OpenCode

MCP servers go in `~/.config/opencode/opencode.jsonc`, under `mcp.servers`. Never put them in
`opencode.json`, which is replaced on every start.

If the file does not exist, create it with exactly this:

```jsonc
{
  "mcp": {
    "servers": {
      "ciela": {
        "type": "local",
        "command": ["node", "/home/workspace/mcp/ciela/server.js"],
        "environment": {
          "CIELA_USERNAME": "{env:CIELA_USERNAME}",
          "CIELA_PASSWORD": "{env:CIELA_PASSWORD}"
        }
      }
    }
  }
}
```

If it exists, add only the `"ciela": { … }` entry above inside `mcp.servers`, creating `mcp` and
`servers` if they are missing. Keep every other server, setting and comment as it is. The
credentials are always `{env:…}` references, never values.

## 4. Add the credentials as secrets

Ask the user to open this workspace's **Settings → Secrets** and add two secrets:

- **`CIELA_USERNAME`**, their Ciela username or email.
- **`CIELA_PASSWORD`**, their Ciela password.

Tell them **never to paste either value into this chat**, even if they offer: credentials typed
here end up in the conversation history. Then they press **Restart**, which brings in both the
secrets and the new MCP server, and come back to this session once the workspace is running
again. Wait for them to say they are back. Do not go on before that.

Only if their organisation reaches Ciela at another address than `https://web7.ciela.net`, they
also add `CIELA_BASE_URL` under **Variables**, and you add `"CIELA_BASE_URL":
"{env:CIELA_BASE_URL}"` to the server's `environment` in `opencode.jsonc`.

## 5. Check it works

When they are back, run three checks in order, and stop at the first that fails.

**The credentials are there.** This prints only whether each one is set, never its value:

```sh
for name in CIELA_USERNAME CIELA_PASSWORD; do
  if [ -n "$(printenv "$name")" ]; then echo "$name: set"; else echo "$name: MISSING"; fi
done
```

If either is missing, the secret was not added, was added under another name (names are case
sensitive), or the workspace was not restarted. Ask the user to fix that and restart, then check
again.

**Ciela accepts them.** `check.js` signs in, searches and reads one document:

```sh
node ~/mcp/ciela/check.js
```

`check.js` ends with `OK` after printing three results, a title, a source link and the start of
a document. What to do when it does not:

- `secrets missing`: the secrets were not added, or the workspace was not restarted.
- `Ciela refused the username or password`: ask the user to correct the secret and restart.
  Never guess at the credentials.
- `HTTP 5xx`, or "did not answer in time": Ciela is having trouble. Try again in a few minutes.
- Anything else: report it as it is.

**OpenCode has loaded the server.** The workspace's own OpenCode server reports each MCP
server's state:

```sh
curl -fsS -u "opencode:${OPENCODE_SERVER_PASSWORD:-$OPENCODE_PASSWORD}" \
  "http://127.0.0.1:4096/api/mcp?location%5Bdirectory%5D=$HOME/project" \
  | jq '.data[] | select(.name == "ciela") | .status'
```

It must print `{"status": "connected"}`. `failed` comes with an `error`: read it, fix the cause
(usually a path in `opencode.jsonc`, or a file not written exactly), and ask the user to restart.
If `ciela` is not listed at all, `opencode.jsonc` is not where step 3 says, or is not valid
JSONC. The user sees the same state under **Settings → MCP**.

## 6. Leave instructions for using Ciela

Write `~/.config/workspaced/instructions/ciela.md` with exactly this content. It is given to the
agent in every session from now on:

````markdown
# Ciela: Bulgarian legal reference system

Ciela (web7.ciela.net) is a **справочно-правна система**: справочен и правен софтуер, a legal
reference database. It holds Bulgarian legislation in force and repealed, court decisions,
interpretive decisions and cases, and other legal documents. You reach it through two tools:
`ciela_search` and `ciela_get_document`.

## When to use it

- Whenever the user mentions Ciela in any spelling: Ciela, Siela, Сиела, сиела, Циела, "в Сиела",
  "провери в Сиела". Recognize the intent from context. Do not require the exact Latin word.
- Whenever the user asks what Bulgarian law says, what a court decided, or for the text of a law,
  code, regulation, decision or interpretive case, even without naming Ciela.
- Never answer about Bulgarian law from memory or training data. Find the text in Ciela first. If
  it is not there, say so.

## How to search

1. **Search** with `ciela_search`. Ciela ranks exact and near-exact phrase matches highest, so:
   - When the user gave a title, number or citation, pass it **whole and exactly**. For example,
     `Закон за задълженията и договорите`, or
     `РАЗПОРЕЖДАНЕ ОТ 15.01.2026 Г. ЗА ОБРАЗУВАНЕ НА ТЪЛК. Д. № 1/2026 Г., ОСГК НА ВКС`.
   - Search in Bulgarian, since the documents are in Bulgarian. Translate the user's question into
     the Bulgarian legal terms first.
   - Prefer the official name of a law or code to an abbreviation. If an abbreviation (ЗЗД, КТ,
     ГПК) finds nothing useful, search again with the full name.
   - Leave `limit` at its default of 25 for a specific document. Use 50–100 for broad research
     (surveying case law on a topic, comparing decisions), so relevant results are not missed.
2. **Pick the right result.** Results come best first, by `score`. Check that the title really
   matches what was asked: the law's name, the date, the case or document number, the court, the
   parties. Do not take the first result on trust.
3. **Read it** with `ciela_get_document`, passing the result's `contentHref` exactly as returned.
   Never build or edit a `contentHref` yourself. The text comes a page at a time: if the part you
   need is not on the first page, call it again with `offset` set to `nextOffset`. Stop once you
   have it, since a whole code is very long.
4. **Refine** when nothing matches. Search again with different keywords, such as the law's
   official name, a narrower phrase or the key legal term, before you give up. Try at least two
   different searches.
5. For a question about one article, find the law, then page through it to that article
   ("Чл. 92."). Quote the article as it stands now.

## How to answer

Every answer about the law follows this structure, in the user's language:

**Answer:** a clear, plain-language explanation.

**Legal basis:**
- [Law / decision name], [article number or section]
- Source: [the `citationUrl` of the document you read]

**Excerpt:** the exact relevant text from `ciela_get_document`, quoted in the original Bulgarian.

## Rules

- Only use what `ciela_search` and `ciela_get_document` returned. Never cite a law, article or
  decision from memory, and never invent a citation link.
- Always include the `citationUrl` of the specific document you used. Nothing from Ciela is
  reported without its source link.
- Answer in the user's language: Bulgarian to Bulgarian, English to English. Legal text is always
  quoted in the original Bulgarian.
- Report what the text says. Do not speculate about interpretation. When the user asks what to do
  in their own case, add one line saying this is legal research, not legal advice.
- If nothing relevant is found, say: "Не открих това в Ciela. Моля, уточнете името на закона или
  формулирайте въпроса по друг начин." (in the user's language).
- If a tool answers with an error, say what failed in plain words. "Ciela refused the username or
  password" means the user should update the `CIELA_USERNAME` or `CIELA_PASSWORD` secret and
  restart the workspace.

## Maintenance

The server is in `~/mcp/ciela/`. Test it with `node ~/mcp/ciela/check.js` (add a search phrase
as an argument to try another). The credentials are the `CIELA_USERNAME` and `CIELA_PASSWORD`
secrets. After a change to the code, restart the workspace.
````

## 7. Test it end to end

The tools and the instructions reach **new** sessions only, so the last test runs in one. Ask the
user to open a new session in this workspace and send:

> Какво казва Законът за задълженията и договорите за неустойката? Провери в Сиела.

Then ask them to come back here and tell you what they got. The test passes when the answer:

- quotes the article's text in Bulgarian (чл. 92 ЗЗД),
- names the law and the article as its legal basis, and
- ends with a `web7.ciela.net` source link that opens the document when clicked.

If the answer came from memory, with no quote and no link, the session did not use the tools:
check that `~/.config/workspaced/instructions/ciela.md` exists and that step 5's last check says
`connected`, then try again in another new session. If the link does not open the document, ask
the user to sign in to Ciela in that browser first: the link opens Ciela's own site, which needs
their login.

## 8. Hand over

Once the test passes, tell the user in a few sentences:

- Ciela is connected and tested.
- They can ask about any Bulgarian law or decision, in any session, or say "провери в Сиела…".
- Every answer quotes the text and links back to the source in Ciela.
- If Ciela ever starts refusing the login, they update the `CIELA_PASSWORD` secret and restart.
