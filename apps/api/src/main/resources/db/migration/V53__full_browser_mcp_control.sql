-- Installed recipes expose the complete private browser control tool set.
UPDATE recipe
SET content = $recipe$# A browser you and the agent share

Set up a browser with a general human handoff procedure. The agent normally works privately
through Playwright. Whenever it needs help, or the user asks to take control, it temporarily
starts noVNC, opens one dedicated port, and supplies a one-time token link.
The user performs the needed step. The agent verifies the result, closes the port, stops VNC,
revokes the temporary token and viewer session, and continues in the same browser.

Use this for any step requiring a person: logging in, solving a CAPTCHA, choosing an account,
reviewing a page, correcting a form, uploading a file, or making a decision. Use website UI
rather than website APIs or exported cookies. Answer in the user's language.

Applying this recipe authorizes temporary desktop access and the corresponding firewall
open/close operations. Use plain HTTP on a dedicated high port, default **6088**, never 80
or 443. Do not set up TLS, certificates, renewal services, domains or permanent desktop
credentials. Explain when giving access that the desktop connection is unencrypted and that
its access token is temporary. Website HTTPS does not encrypt the VNC connection itself.

## 1. Prepare a private browser

Use `~/project/shared-browser`, Compose project `workspaced-browser`. Inspect existing files
and services first. Reuse the existing browser and profile; never erase a profile or stop an
unrelated service. Save the Dockerfile, Compose file, startup scripts, Playwright wrapper and
handoff helpers here. Pin official base images by digest and record package/image versions.
Use the official Playwright Python Ubuntu image with a matching Playwright Python package;
the browser image does not include that package. Install Xvfb and a small window manager.

Run Chromium as an unprivileged user with its sandbox enabled and adequate private shared
memory, using Playwright's documented Docker seccomp profile. Do not use privileged mode,
host networking, host IPC or `--no-sandbox`. Supervise Xvfb, the window manager and Chromium;
wait for the display to be ready and use Compose health checks, `init: true` and
`restart: unless-stopped` for the private browser service.

Launch visible Chromium on that display with a named-volume profile at
`/home/pwuser/browser-profile`, `--remote-debugging-address=127.0.0.1` and
`--remote-debugging-port=9222`. Open `about:blank` on the first launch. Keep one owner of the
profile, not a second Playwright browser. Publish **no browser-service ports**, including
CDP, X11 and raw VNC. Disable X11 TCP listening and protect the display with Xauthority.

Provide a wrapper that streams scripts into
`docker compose exec -T --user pwuser browser python -` inside the running browser service.
Playwright attaches to `http://127.0.0.1:9222` there and uses its existing default context:

```python
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
    context = browser.contexts[0]
    page = context.pages[0] if context.pages else context.new_page()
    # Locate the requested tab by site URL before acting when multiple tabs exist.
    # Perform the authorized browser interaction here.
    # Disconnect the client without closing the shared browser.
```

Do not call `browser.close()`, close the default context, export storage state, print URLs
containing tokens, or create an isolated context for a task that needs the user's session.
Verify that disconnecting the client leaves Chromium and its tabs alive.

## 2. Prepare temporary desktop access

Build a separate, on-demand `access` service containing x11vnc, noVNC, websockify and nginx.
Share only the required X11 Unix socket and Xauthority cookie with the browser service using
named Docker volumes and appropriate UID permissions. Harness paths may not exist on the
Docker daemon's host; populate volumes through helper containers and stdin. Do not mount the
Docker socket or copy the browser profile into the access service.

Within `access`, x11vnc listens only on loopback and attaches to the existing display with
reconnect support. Websockify and the noVNC static server also listen on loopback. Nginx
publishes one container HTTP listener through the dedicated host port. Protect **all** noVNC
assets and WebSocket handshakes with the temporary viewer session described below. Pass WebSocket upgrade
headers and use a long read timeout. Strip viewer authentication material before forwarding internally.
Do not publish raw VNC, websockify, X11, CDP or another unauthenticated route.

The access service is stopped and removed outside active handoffs; it must never restart
by itself without an active handoff. Browser supervision must not depend on VNC or the
access service being alive. Validate Compose with `docker compose config --quiet`.

Implement `handoff open`, `handoff status` and `handoff close` helpers. Persist only a
non-secret record with a unique handoff ID, reason, target tab, expected result, chosen port,
source restriction and expiry. Serialize handoffs and prevent an old cleanup from removing
a newer handoff. Keep only hashes of active tokens/session identifiers in protected
handoff-specific state.
Deliver the raw one-time token only in the user's private help link, never project files,
environment dumps, logs or shared instructions. Do not create an OS user, application account,
permanent password or Settings secret for a handoff.

## 3. Open access and ask for help

When human assistance is needed, identify the concrete outcome and bring its tab to the
visible display. Open Chromium yourself if necessary. If the user has not specified a page
or step, ask what they want to do before opening access. Pause agent mouse/keyboard actions
and retries so the user can work without interference.

Choose an unused dedicated high TCP port, default 6088. Verify that neither its binding nor
its firewall rule belongs to another service. Never take over a pre-existing rule or use
80/443. Get the real public IP from `$WORKSPACE_IP` or `firewall list`; never invent it.

Generate a fresh cryptographically random token with at least 256 bits of entropy for each
handoff (for example Python `secrets.token_urlsafe(32)`). Build a minimal authentication
bootstrap and session validator into the access service:

- The public `/handoff` bootstrap page receives the token in the URL **fragment**, never a
  query string or path. It loads only local resources, uses `Referrer-Policy: no-referrer`
  and `Cache-Control: no-store`, reads the fragment, immediately clears it using
  `history.replaceState`, then submits the token to `/auth/exchange` in a same-origin POST.
- Validate a stored hash with constant-time comparison; enforce expiry and consume the token
  atomically on the first successful exchange. Rate-limit failed attempts and return generic
  errors. A token from a different, consumed, expired or cancelled handoff must fail.
- Return a new random opaque viewer-session cookie with `HttpOnly`, `SameSite=Strict`, a
  short Max-Age bounded by the handoff expiry, and the required path scope. Plain HTTP cannot
  use a Secure cookie: retain the explicitly requested HTTP behavior without claiming token
  authentication encrypts it. The token exchange and viewer session are still unencrypted.
- Authorize every noVNC/static request and WebSocket handshake against this session, including
  same-origin checks for exchange and WebSocket requests. Only the bootstrap and exchange
  endpoint are public. On success redirect to the local noVNC viewer without any token.
  Disable body, cookie, token and full-URL logging. Do not include third-party page resources.
- Invalidate both the one-time token and the viewer session during cleanup. A refresh can use
  the active cookie; replaying the bootstrap link cannot issue another viewer session. If the
  user loses that session, revoke the old grant and issue a replacement link without extending
  the existing handoff expiry unless they request more time.

Before opening access, register a 30-minute expiry and a foreground cleanup watchdog through
the workspace service runner. Start `access` with the temporary grant state, verify its health,
then call `firewall open 6088` using the actual chosen port. Restrict it to the user's public
IP if known, using the source argument; never guess that address. Check `firewall list` and
external HTTP reachability, denial without a valid session, rejection of invalid/replayed
tokens, and a successful
exchange followed by an authenticated noVNC/WebSocket connection. Use a separate test grant
for checks, revoke it afterwards, then issue the user's unused grant. Never consume their
one-time token in a link checker or put tokens in process arguments. If setup fails, close access and clean
up before reporting the failure.

If the platform refuses agent firewall changes, explain **Settings → Firewall** and ask the
user to enable agent changes or perform the specific port change themselves. Pause until
it is verified. Applying this recipe is sufficient authorization to open and close its port;
do not ask again when the platform permits it.

Give the user a message in this form with real values, not placeholders:

- Why help is needed, which page is open, and the step to perform.
- **Open:** `http://<public-IP>:<port>/handoff#token=<one-time-token>`
- Open this private one-time link to enter the already-open browser. No VNC account or password
  is needed. The desktop connection is unencrypted; access expires in 30 minutes. Tell me
  when the step is finished, or ask for more time.

Show the generated link only in the private handoff message as requested. Never show website
passwords, cookies or one-time codes. End the turn and wait. A link click, viewer connection,
disconnection or elapsed time is not proof the step is complete. Extend only on request.

While the user controls the browser, do not observe keystrokes, capture login screenshots,
read secret inputs/cookies or issue competing browser actions. Website credentials and codes
go directly into the site inside VNC, never chat. For a CAPTCHA or another human check, ask
for help and let the user complete it; do not bypass it or use solving services.

## 4. Verify, close the port, remove VNC and continue

After the user says they are done, attach through private CDP and check the UI needed to
verify the agreed outcome. Examples include the requested account being selected, a corrected
form, a completed challenge or the desired page becoming available. Inspect no secret inputs.
If verification fails or is inconclusive, explain what remains and hand control back within
the current expiry. If it expired, create a new handoff with a new token. Do not resume
the dependent task until the result is verified.

Once verified, use `firewall close <actual-port>`, confirm its rule is absent, then stop and
remove the `access` container and its handoff-specific token/session state. This must terminate
existing VNC/WebSocket connections; a firewall change alone may leave an established viewer
connected. Revoke all token/session grants and remove handoff state, and verify that the public
port is unreachable and no VNC/bridge/proxy process from the handoff remains running.
Never run `docker compose down -v`: preserve the private browser, its X display, profile,
tabs and login sessions. A token link already delivered in chat remains in that history but must no longer grant
access after cleanup.

Tell the user access is closed, then continue their authorized task through Playwright in
the same browser. Completing a handoff does not authorize unrelated messages, submissions,
account changes or scheduled tasks.

Run the same cleanup on cancellation, timeout, setup failure and workspace startup after an
interrupted handoff. The watchdog must work without another agent turn, fail closed on
malformed state, and prevent access from restarting outside an active unexpired handoff.
If firewall closure fails, still stop/remove `access` and revoke its grants immediately,
report the remaining rule and request the specific manual correction. Do not claim verified
port closure until it is checked. Ordinary cleanup must never stop Chromium.

Test a full open → human handoff → verified outcome → close cycle and an expiry cleanup.
Confirm viewer disconnection, absent firewall rule, revoked token/session grants and removed access
container, and the same browser tab/session still available to Playwright.

## 5. Expose full browser control to OpenCode through MCP

Build a real local MCP server in `~/mcp/shared-browser/` that gives OpenCode in the harness
full control of this same visible browser. Routine browser work must be possible entirely
through MCP calls, without asking the agent to generate Python or use a terminal. Keep the
private wrapper as an internal implementation and diagnostic fallback. This is full browser
control for the user's authorized tasks; the human handoff is available whenever needed.

The following is an implementation
specification for the agent applying this recipe, not a claim that tools already exist.
Create `server.py`, the browser-worker module, a dependency lockfile, an executable stdio
launcher and `check.py`. Use the official Python MCP SDK, choose a supported version from
its documentation at install time, and pin it in a persistent home-directory virtualenv.
Keep stdout exclusively for MCP protocol messages; diagnostics go to stderr without secrets.
Use structured input/output schemas, bounded result sizes, operation timeouts and explicit
errors. Never expose an HTTP MCP server or CDP port publicly.

The local server runs in the workspace harness. It calls fixed-argument subprocesses for
the existing handoff helpers and private browser wrapper; the wrapper executes Playwright
inside the browser container. Verify Docker CLI/Compose access through the workspace's
existing `DOCKER_HOST`; do not try to start a nested Docker daemon. If that bridge is
unavailable, report the prerequisite and stop rather than expose a debug port as a workaround.
Write a browser worker that receives validated JSON over stdin and emits JSON results, not
arbitrary Python generated from tool inputs. Use argument arrays, no shell interpolation.
Keep tab IDs stable across requests and invalidate stale element references after navigation.
All MCP instances share the same handoff state and interprocess lock. Keep a persistent
browser worker attached to Chromium so dialogs, downloads, popups, frame references and
keyboard/mouse state survive individual tool calls. Use a private Unix-domain control socket
inside the browser container for the worker; access it through the fixed-argument wrapper,
not a published network port. Reconnect safely after worker restarts, invalidating references
that can no longer be resolved. Do not close Chromium when an MCP client disconnects.

Implement these tools with explicit schemas, examples and descriptions. Do not ship just
`navigate`, `snapshot` and a narrow click helper and call that full control. The listed tool
names are the required public interface of this recipe's server; implement them with the
installed Playwright APIs rather than assuming an upstream package has identical schemas.
Microsoft's Playwright MCP is a useful implementation reference. If adapting it, keep all
calls behind the shared handoff lock and attach it to this existing browser through the
private bridge; never register a second, unrestricted browser server that bypasses the lock.

| Tool | Required behavior |
| --- | --- |
| `browser_status()` | Browser health, tab IDs, sanitized site URLs, pending dialogs/downloads and active handoff status/expiry. No tokens or cookies. |
| `browser_tabs(action, tab_id?, url?)` | List, create, select or close a tab in the existing default context. Track popups as new tab IDs. Closing a tab must not close Chromium or its default context. |
| `browser_navigate(tab_id, url)` | HTTP(S) navigation in the selected existing tab; return final sanitized location and a bounded snapshot. |
| `browser_history(tab_id, action)` | Back, forward or reload, with bounded completion waits and updated page state. |
| `browser_snapshot(tab_id, frame_id?, target?)` | Visible text/accessibility snapshot with element references, optionally scoped to an element or frame. Redact secret inputs. |
| `browser_frames(tab_id)` | List the frame tree with stable frame IDs, including nested/cross-origin frames supported by Playwright. Let interaction tools target a frame explicitly. |
| `browser_click(tab_id, target, frame_id?, button?, click_count?, modifiers?)` | Single/double/right click through a snapshot reference or unambiguous locator, including modifier keys. |
| `browser_fill_form(tab_id, fields, frame_id?)` | Fill text, checkbox/radio and select fields using typed field descriptors. Distinguish replace from typing; preserve handoff for website credentials and human checks. |
| `browser_keyboard(tab_id, action, key?, text?)` | Type text and press shortcuts, or key-down/key-up where needed. Release held keys on cancellation/error. |
| `browser_mouse(tab_id, action, x?, y?, button?, delta_x?, delta_y?)` | Move, down/up, click and wheel with screenshot-relative viewport coordinates. Validate coordinate bounds and release held buttons on cancellation/error. |
| `browser_hover(tab_id, target, frame_id?)` | Hover a located element and report the resulting page state. |
| `browser_drag(tab_id, source, destination, frame_id?)` | Drag/drop using locators or explicit viewport coordinates. Support ordinary UI drag interactions without requiring VNC. |
| `browser_screenshot(tab_id, frame_id?, target?, full_page?)` | Return an actual MCP image result plus its dimensions, or a controlled artifact reference if too large. Support viewport, full-page and element capture. Do not capture secrets or the user's manual-input phase. |
| `browser_resize(tab_id, width, height)` | Resize the real headed browser/viewport coherently with the shared desktop; return the actual resulting dimensions. |
| `browser_wait(tab_id, condition, timeout_ms)` | Bounded waits for a declared UI/URL condition; return timeout details rather than hang or claim success. |
| `browser_dialog(tab_id, action, prompt_text?)` | Inspect, accept or dismiss alert/confirm/prompt/beforeunload dialogs. Capture dialog events without blocking a tool call indefinitely. |
| `browser_upload(tab_id, files, target?, frame_id?)` | Set file inputs or satisfy a pending file chooser using user-authorized files staged into a dedicated transfer volume. Support multiple files. |
| `browser_downloads(action, tab_id?, download_id?)` | List, wait for, save or cancel actual browser downloads. Return size, status and a workspace artifact path; never silently discard downloads when a client disconnects. |
| `browser_evaluate(tab_id, function, argument?, target?, frame_id?)` | Evaluate page/element JavaScript for DOM inspection and UI interaction when structured tools are insufficient. Bound time/output and return structured values. This is page JavaScript, not host Python or shell execution. |
| `browser_console(tab_id, level?, limit?)` | Bounded console/page-error diagnostics with secret redaction. |
| `browser_network(tab_id, limit?)` | Bounded metadata about requests made by the UI: sanitized URL, method, status, type and timing. Do not export authorization headers, cookies or secret bodies or turn this into direct website API access. |
| `browser_request_user_help(tab_id, reason, instructions, expected_result)` | Pause automation, call handoff-open, and return `waiting_for_user`, a handoff ID, private one-time link, expiry and the message the agent must actually deliver. Do not wait inside the tool call or pretend it contacted the user itself. |
| `browser_verify_user_step(handoff_id)` | After real user confirmation, check the stored expected result. On a pass, clean up and verify port closure, VNC removal and grant revocation before returning `completed`. Otherwise return `needs_more_help`, `inconclusive`, `expired` or `cleanup_failed` with evidence. |
| `browser_cancel_user_help(handoff_id)` | Revoke grants, close its port, remove VNC, verify cleanup and release the lock while preserving Chromium. |

For targets, support fresh snapshot references plus typed role/name, label, text, test-id
and CSS locators with frame scope. Do not silently choose the first match when a target is
ambiguous. Return new state/references after actions, and give the agent actionable stale-ref
errors. Downloads, popup tabs and pending dialogs need stable IDs across calls. Keep binary
results out of JSON text dumps and use proper MCP image/resource results for screenshots/files.

Transfer files between harness and browser with a fixed-argument helper and a dedicated
named volume; their filesystem paths are different. Validate relative paths, resolve symlinks,
reject traversal outside the transfer/artifact roots, avoid overwriting existing files, and
stream bytes through the bridge rather than mounting the entire home or profile. Preserve
original user files. Document the tested staging and artifact retrieval commands.

Page JavaScript must obey the same user-task scope and handoff rules as clicks: do not use
it to read credentials/cookies, automate a human check, bypass the handoff lock, or replace
website UI work with fetch/XHR/API calls. This is an instruction constraint, not a claim that
page JavaScript is incapable of those actions. Navigation, tab changes, evaluation, uploads,
dialogs, downloads, keyboard and mouse all acquire the shared lock. A human handoff releases
held keys/buttons and blocks automation from every client; no tool offers a bypass flag.

`expected_result` must be a bounded declarative schema, not arbitrary executable code: target
origin/tab plus supported UI predicates such as a visible role/text, expected URL path or
selected option. Store it at handoff creation so a later verify call cannot silently replace
the goal with an easier condition. Prefer positive evidence; a disappearing CAPTCHA alone
is not proof that the intended destination is ready. Reject unsupported checks or return
`inconclusive` and ask the user to clarify the observable outcome. Human judgment that cannot
be checked in the UI must be reported as user-confirmed rather than falsely machine-verified.
A client-supplied `success: true` or elapsed time must never count as verification.

The request tool returns a proposed user message; the agent must actually deliver it in
chat and end the turn. MCP tool output is not user confirmation or a notification by itself.
Record that the user said they finished only after their real reply; do not synthesize that
reply. While help is active, reject all mutating browser tools, including from other agent
sessions; only status, cancellation and controlled verification are allowed. Verification
must not race the user's input. Cleanup completion and outcome verification are separate
fields: never return success when either is unresolved. Watchdog expiry follows the same
state machine and invalidates outstanding tool calls/grants.

Register `shared-browser` by merging an entry into
`~/.config/opencode/opencode.jsonc` under `mcp.servers`, preserving other servers and comments.
Use the actual absolute launcher path, for example:

```jsonc
{
  "mcp": {
    "servers": {
      "shared-browser": {
        "type": "local",
        "command": ["/home/workspace/mcp/shared-browser/start.sh"]
      }
    }
  }
}
```

Do not replace the whole existing configuration or edit `opencode.json`, which is rebuilt
on startup. The launcher must work from any directory and use the pinned virtualenv. Verify
OpenCode loads the server and lists the full tool set under **Settings → MCP**. Verify
there is no tool allowlist or permission configuration unintentionally hiding the control
tools; preserve the user's existing permission choices. The MCP connection stays private and
usable while the VNC port is closed. Reconnect the client
or ask the user to restart only if needed by this installation; preserve the browser profile.

Use a real MCP client in `check.py` to initialize the stdio server, list its tool schemas and
call them. Use a local fixture site with nested frames, popup tabs, forms, keyboard shortcuts,
hover/drag targets, dialogs, file upload/download and a page requiring JS-based DOM inspection.
Exercise every tool family through the actual MCP client, including screenshots as image
results, coordinate actions, frame selection, history, waits and bounded diagnostics. Verify
the resulting UI and file bytes rather than merely checking tool-call success. Test wrong
targets, stale references, missing files, traversal, timeouts and worker reconnects. Confirm
OpenCode can complete a multi-step browser task entirely through MCP while VNC stays off.
Test request-help, rejection of actions during handoff, a failed expected
result, a successful UI result followed by cleanup, replay/expired token refusal, cancellation,
timeout and cleanup failure. Simulate user completion on the fixture, then perform one actual
user handoff; do not label a simulation as real user approval. Verify two clients cannot race
control, and that restarting the MCP process preserves the private browser and cleans up
interrupted access. Never claim MCP integration is complete based solely on launching a
process or seeing a tool name. Fix failures before reporting success.

## 6. Make this usable in later sessions

Create a persistent shared instruction at
`~/.config/workspaced/instructions/shared-browser.md` so it appears in **Settings → Instructions**
and is available to later agent sessions and scheduled tasks. This is required setup work,
not an optional suggestion. Write it after verifying the installation; use actual paths,
service names and tested commands rather than placeholders. Include these sections:

- **MCP tools:** server name and install path, all tool names and their tested argument
  examples for tabs, navigation, frame/element selection, forms, keys/mouse, screenshots,
  dialogs, uploads/downloads and DOM evaluation. State that OpenCode can fully operate the
  shared browser through MCP while VNC is stopped; prefer these tools for routine work.
  Explain the request-help → actual
  user reply → verify-step → cleanup sequence, pending/error statuses, and when to cancel.
- **Browser available:** a shared visible Chromium browser is installed and available to the
  agent through Playwright. Its profile and website sessions belong to this workspace.
  Public VNC access is normally stopped; the agent can still use the private browser.
- **How to use it:** the Compose directory/project, browser service name, profile volume,
  executable wrapper path, and tested commands to check health, start the private browser
  if needed, list/select tabs by site URL, attach via local CDP, and perform browser actions.
  Include a complete copyable wrapper example using the existing default context. Disconnect
  without closing Chromium. Use website UI rather than website APIs; reuse signed-in tabs.
- **Ask the user for manual help:** the agent may ask the user to perform any step requiring
  human input or judgment. State the reason and exact step, prepare the correct browser tab,
  call `browser_request_user_help` and deliver its temporary one-time HTTP link and expiry.
  Then pause agent interaction and wait for the user's confirmation. This permission
  applies to later sessions too, including login, CAPTCHA, approvals, visual checks and form
  corrections; it is not limited to a particular site or type of obstacle.
- **Resume and clean up:** after the user confirms, verify the needed result through the UI.
  Call `browser_verify_user_step`; on a pass, verify port closure and removal of VNC
  and token/session grants, and continue in the same browser. Include the actual helper
  commands, dedicated port ownership, watchdog/expiry, cancellation and failure cleanup.
  Never leave VNC open just because a session or task ended.
- **Known sign-ins and scope:** record site domains and account scope only when the user
  tells you what is signed in. Check the site's current login state before relying on it.
  Include no credentials, cookies, session tokens, private page contents or expired handoff
  links/tokens in this instruction.

Read back the saved instruction and check that its wrapper and helper commands work, that
it describes the installed browser accurately, and that it will let a fresh session use the
browser or ask for manual help without needing the setup chat. Finish by pointing the user
to **Settings → Instructions → shared-browser**.

Later sessions and tasks reuse this browser and follow the same handoff procedure whenever
they need help. A scheduled run reports the reason, temporary one-time link and expiry
through its existing private result mechanism, then stops pending human help. If that
mechanism cannot deliver privately, report that help is needed without opening access;
create the handoff when the user returns. The watchdog cleans up unattended handoffs. Do
not create a notification integration or a scheduled task unless requested.

Explain that website sessions may expire or be revoked; profile persistence does not
promise permanent login. Ordinary browser restarts may lose session-only cookies. Workspace
deletion or trial expiry removes the profile. Handoff cleanup keeps the website session alive;
to revoke the agent's access, sign out of the website or stop the private browser service.
Never delete the profile without explicit permission.

Finish setup with public desktop access closed and VNC stopped. Tell the user that the agent
will provide a temporary one-time link whenever it needs their help, verify their
step, then remove desktop access and continue.

References: https://github.com/microsoft/playwright-mcp,
https://github.com/modelcontextprotocol/python-sdk,
https://playwright.dev/python/docs/docker,
https://playwright.dev/python/docs/api/class-browsertype#browser-type-connect-over-cdp,
https://github.com/novnc/noVNC/wiki/Proxying-with-nginx,
https://nginx.org/en/docs/http/ngx_http_auth_basic_module.html,
https://nginx.org/en/docs/http/websocket.html$recipe$
WHERE id = '00000000-0000-4000-8000-00000000b00c';
