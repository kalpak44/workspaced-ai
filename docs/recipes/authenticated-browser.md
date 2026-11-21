---
title: A browser you and the agent share
summary: Let the agent work in a browser and hand control to you whenever it needs help. It opens temporary VNC access with random credentials, verifies your step, then removes access and continues.
category: automation
tags: [browser,playwright,vnc,automation]
visibility: private
---

# A browser you and the agent share

Set up a browser with a general human handoff procedure. The agent normally works privately
through Playwright. Whenever it needs help, or the user asks to take control, it temporarily
starts noVNC, opens one dedicated port, and supplies a browser link and random credentials.
The user performs the needed step. The agent verifies the result, closes the port, stops VNC,
removes the temporary credentials, and continues in the same browser.

Use this for any step requiring a person: logging in, solving a CAPTCHA, choosing an account,
reviewing a page, correcting a form, uploading a file, or making a decision. Use website UI
rather than website APIs or exported cookies. Answer in the user's language.

Applying this recipe authorizes temporary desktop access and the corresponding firewall
open/close operations. Use plain HTTP on a dedicated high port, default **6088**, never 80
or 443. Do not set up TLS, certificates, renewal services, domains or permanent desktop
credentials. Explain when giving access that the desktop connection is unencrypted and that
its credentials are temporary. Website HTTPS does not encrypt the VNC connection itself.

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
assets and WebSocket handshakes with nginx Basic authentication. Pass WebSocket upgrade
headers and use a long read timeout. Strip Authorization before forwarding internally.
Do not publish raw VNC, websockify, X11, CDP or another unauthenticated route.

The access service is stopped and removed outside active handoffs; it must never restart
by itself without an active handoff. Browser supervision must not depend on VNC or the
access service being alive. Validate Compose with `docker compose config --quiet`.

Implement `handoff open`, `handoff status` and `handoff close` helpers. Persist only a
non-secret record with a unique handoff ID, reason, target tab, expected result, chosen port,
source restriction and expiry. Serialize handoffs and prevent an old cleanup from removing
a newer handoff. Keep credential hashes in a handoff-specific temporary volume, readable only
by the access service and its setup helper. Keep plaintext credentials only in memory for
delivery to the user, not in project files, environment dumps, logs or shared instructions.

## 3. Open access and ask for help

When human assistance is needed, identify the concrete outcome and bring its tab to the
visible display. Open Chromium yourself if necessary. If the user has not specified a page
or step, ask what they want to do before opening access. Pause agent mouse/keyboard actions
and retries so the user can work without interference.

Choose an unused dedicated high TCP port, default 6088. Verify that neither its binding nor
its firewall rule belongs to another service. Never take over a pre-existing rule or use
80/443. Get the real public IP from `$WORKSPACE_IP` or `firewall list`; never invent it.

Generate a **new** temporary username and a cryptographically random password of at least
24 URL-safe characters for every handoff, using Python `secrets` or an equivalent secure
source. Generate a bcrypt hash for nginx using stdin, not a password argument. Do not use
legacy VNC authentication as the external boundary, which would truncate the password.
Reuse neither earlier handoff credentials nor website credentials. The user does not need
to enter VNC secrets in Settings or restart the workspace.

Before opening access, register a 30-minute expiry and a foreground cleanup watchdog through
the workspace service runner. Start `access` with the temporary hash, verify its health,
then call `firewall open 6088` using the actual chosen port. Restrict it to the user's public
IP if known, using the source argument; never guess that address. Check `firewall list` and
external HTTP reachability, a 401 without credentials, refusal of incorrect credentials,
and a successful authenticated noVNC/WebSocket connection. Do not log Authorization headers
or put credentials in URLs or command-line arguments. If setup fails, close access and clean
up before reporting the failure.

If the platform refuses agent firewall changes, explain **Settings → Firewall** and ask the
user to enable agent changes or perform the specific port change themselves. Pause until
it is verified. Applying this recipe is sufficient authorization to open and close its port;
do not ask again when the platform permits it.

Give the user a message in this form with real values, not placeholders:

- Why help is needed, which page is open, and the step to perform.
- **Open:** `http://<public-IP>:<port>/vnc.html?autoconnect=true&resize=remote&path=websockify`
- **Username:** the generated temporary username.
- **Password:** the generated temporary password.
- Enter those credentials when the browser prompts. The desktop connection is unencrypted;
  access expires in 30 minutes. Tell me when the step is finished, or ask for more time.

Show the temporary desktop credentials in this handoff message as requested. Never show
website passwords, cookies or one-time codes. The URL contains no credentials. End the turn
and wait for the user. Opening the link, a viewer connection/disconnection or elapsed time
is not confirmation that the step is complete. Extend expiry only on their request.

While the user controls the browser, do not observe keystrokes, capture login screenshots,
read secret inputs/cookies or issue competing browser actions. Website credentials and codes
go directly into the site inside VNC, never chat. For a CAPTCHA or another human check, ask
for help and let the user complete it; do not bypass it or use solving services.

## 4. Verify, close the port, remove VNC and continue

After the user says they are done, attach through private CDP and check the UI needed to
verify the agreed outcome. Examples include the requested account being selected, a corrected
form, a completed challenge or the desired page becoming available. Inspect no secret inputs.
If verification fails or is inconclusive, explain what remains and hand control back within
the current expiry. If it expired, create a new handoff with new credentials. Do not resume
the dependent task until the result is verified.

Once verified, use `firewall close <actual-port>`, confirm its rule is absent, then stop and
remove the `access` container and its handoff-specific credential volume. This must terminate
existing VNC/WebSocket connections; a firewall change alone may leave an established viewer
connected. Remove active credential material and handoff state, and verify that the public
port is unreachable and no VNC/bridge/proxy process from the handoff remains running.
Never run `docker compose down -v`: preserve the private browser, its X display, profile,
tabs and login sessions. Credentials already delivered in chat remain in that history, but
must no longer authenticate anywhere.

Tell the user access is closed, then continue their authorized task through Playwright in
the same browser. Completing a handoff does not authorize unrelated messages, submissions,
account changes or scheduled tasks.

Run the same cleanup on cancellation, timeout, setup failure and workspace startup after an
interrupted handoff. The watchdog must work without another agent turn, fail closed on
malformed state, and prevent access from restarting outside an active unexpired handoff.
If firewall closure fails, still stop/remove `access` and its credentials immediately,
report the remaining rule and request the specific manual correction. Do not claim verified
port closure until it is checked. Ordinary cleanup must never stop Chromium.

Test a full open → human handoff → verified outcome → close cycle and an expiry cleanup.
Confirm viewer disconnection, absent firewall rule, removed temporary credentials/access
container, and the same browser tab/session still available to Playwright.

## 5. Make this usable in later sessions

Create a persistent shared instruction at
`~/.config/workspaced/instructions/shared-browser.md` so it appears in **Settings → Instructions**
and is available to later agent sessions and scheduled tasks. This is required setup work,
not an optional suggestion. Write it after verifying the installation; use actual paths,
service names and tested commands rather than placeholders. Include these sections:

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
  run the handoff-open command, and provide the temporary HTTP link, username, password and
  expiry. Then pause agent interaction and wait for the user's confirmation. This permission
  applies to later sessions too, including login, CAPTCHA, approvals, visual checks and form
  corrections; it is not limited to a particular site or type of obstacle.
- **Resume and clean up:** after the user confirms, verify the needed result through the UI.
  If complete, run the tested handoff-close command, verify port closure and removal of VNC
  and temporary credentials, and continue in the same browser. Include the actual helper
  commands, dedicated port ownership, watchdog/expiry, cancellation and failure cleanup.
  Never leave VNC open just because a session or task ended.
- **Known sign-ins and scope:** record site domains and account scope only when the user
  tells you what is signed in. Check the site's current login state before relying on it.
  Include no credentials, cookies, session tokens, private page contents or expired handoff
  links/passwords in this instruction.

Read back the saved instruction and check that its wrapper and helper commands work, that
it describes the installed browser accurately, and that it will let a fresh session use the
browser or ask for manual help without needing the setup chat. Finish by pointing the user
to **Settings → Instructions → shared-browser**.

Later sessions and tasks reuse this browser and follow the same handoff procedure whenever
they need help. A scheduled run reports the reason, temporary link/credentials and expiry
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
will provide a temporary link and credentials whenever it needs their help, verify their
step, then remove desktop access and continue.

References: https://playwright.dev/python/docs/docker,
https://playwright.dev/python/docs/api/class-browsertype#browser-type-connect-over-cdp,
https://github.com/novnc/noVNC/wiki/Proxying-with-nginx,
https://nginx.org/en/docs/http/ngx_http_auth_basic_module.html,
https://nginx.org/en/docs/http/websocket.html
