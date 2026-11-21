---
title: A browser you and the agent share
summary: Sign into websites yourself through a protected VNC desktop, then let Playwright use the same browser. Take over whenever a login or CAPTCHA needs your help.
category: automation
tags: [browser,playwright,vnc,automation]
visibility: private
---

# A browser you and the agent share

Set up a generic browser the user and the agent can take turns controlling. The user signs
into websites in a visible Chromium window through noVNC; Playwright attaches to that exact
browser and uses its existing tabs and login session. Use the website UI, including for later
tasks such as finding LinkedIn job listings. Do not substitute a website API, an unofficial
endpoint, or exported cookies for browser interaction. Answer in the user's language.

Follow these phases in order. Pause when user input is required. Applying this recipe does
not authorize opening firewall ports, entering website credentials, or creating scheduled
tasks. The user opens ports themselves. Reuse an existing installation and profile on retries;
never erase a profile, replace working credentials or interrupt an active browser casually.

## 1. Ask for desktop credentials

First explain the flow: choose credentials for the remote desktop, open its ports when asked,
then use its browser to sign into any website. These desktop credentials are separate from
website logins. Ask the user to save **VNC_USERNAME** and **VNC_PASSWORD** in this workspace's
**Settings → Secrets**, then restart the workspace and return here. Use a unique password of
at least 16 characters and a simple username containing only letters, digits, `_` or `-`.
Never ask for credentials in chat. If the secrets already exist, check their presence and
format without printing values, and proceed without asking the user to re-enter them.

Stop this turn if either value is missing or invalid. Do not start installation until both
are available. Never print secrets, expand them into tool-call text, enable shell tracing,
dump container environments or render resolved Compose configuration. Use standard input
when transferring credentials; store only a bcrypt password hash in the protected proxy
credentials volume. Do not put the plaintext password in Compose, build arguments, images,
process arguments, URLs, instruction files, logs or downloads.

## 2. Prepare the desktop and Playwright privately

Use `~/project/shared-browser`, Compose project `workspaced-browser`. Inspect existing services
and occupied ports before choosing bindings. Keep another app's proxy, certificates and ports
intact. Default to TCP 80 for certificate validation and TCP 8443 for the HTTPS desktop;
if occupied, ask the user about an alternative or an explicit change to their existing proxy.
Do not stop or reconfigure unrelated services automatically.

Implement and save a Dockerfile, Compose file, supervised startup script, nginx configuration,
credential-volume helper, and a small Playwright command wrapper in this directory. Resolve
official base images to digests and record versions in `images.lock`. Use the official
Playwright Python Ubuntu image and install the matching Playwright Python package version;
the image contains browsers but does not contain the Python package. Add Xvfb, a lightweight
window manager, x11vnc, noVNC and websockify. Run Chromium as an unprivileged user with its
sandbox enabled, using Playwright's documented Docker seccomp profile and adequate private
shared memory. Do not use privileged mode, host networking, host IPC or `--no-sandbox`.

The desktop service must:

- Supervise Xvfb, the window manager, Chromium, x11vnc and websockify. Wait for the X display
  before launching GUI processes; fail and restart the service if a required process dies.
  Use Compose `init: true`, health checks and `restart: unless-stopped`.
- Start Chromium visibly on that display with `--user-data-dir=/home/pwuser/browser-profile`,
  `--remote-debugging-address=127.0.0.1` and `--remote-debugging-port=9222`. Open `about:blank`
  on first launch. Keep a single browser process/profile owner, never a second competing
  Playwright browser. Store the profile in a named Docker volume owned by the browser user.
- Run x11vnc against the same display, listening on loopback only, with reconnect support.
  A passwordless internal VNC listener is permitted only on that container's loopback: the
  external authentication boundary is the HTTPS proxy, which checks the full desktop password
  without legacy VNC's eight-character truncation. Do not publish raw VNC TCP 5900.
- Serve noVNC and its WebSocket bridge on private container port 6080. Only nginx may reach
  that port over the project network; publish no desktop-service ports. Never publish CDP
  9222, a Playwright server, a Docker socket, or an unauthenticated WebSocket bridge.
- Provide a wrapper that streams Python scripts into `docker compose exec -T --user pwuser
  desktop python -` inside the running desktop container. Playwright runs there and connects
  to `http://127.0.0.1:9222`; it does not need an exposed debugging port or an extra browser.

Only nginx publishes host ports. Protect **every desktop path**, including noVNC assets and
`/websockify`, with `auth_basic` and the same bcrypt credential file. Use
`htpasswd -i -B` through standard input in a helper container, validate the username first,
and pass no secrets into the desktop container. Make the hash file readable only by the
proxy and its setup helper. Configure WebSocket upgrade headers, HTTP/1.1 and a long read
timeout. Strip the proxy Authorization header before forwarding to the desktop. Disable
request logging of authorization headers and query strings; never put credentials in a URL.

Use named Docker volumes for profile, proxy configuration, hashed credentials, ACME challenges
and certificates: a harness file path is not necessarily a path on the Docker daemon's host.
Populate volumes using helper containers and stdin, and send build context through Docker's
build command. Keep browser profiles out of source control, chat, artifacts and outbox.
Validate Compose with `docker compose config --quiet`. Build and prepare the files now, but
do not launch Chromium or expose a desktop before the port checkpoint below.

## 3. Ask the user to open ports, then stop

Get the workspace's real public IPv4 from `$WORKSPACE_IP` or `firewall list`; never invent it
or use the platform's hostname for a certificate. Ask the user to open **Settings → Firewall**
and add **TCP 80 from Anyone** for certificate validation and **TCP 8443** for their desktop,
preferably restricted to their own public IP. Substitute the actual chosen HTTPS port if it
changed. Explain that the next step will start the desktop with Chromium already open.

Do not call `firewall open`, even if otherwise permitted. End this turn and wait for the user.
After they confirm, check `firewall list` and pause again if the necessary rules are missing.

## 4. Enable trusted HTTPS and open the browser

After the ports are open, start nginx initially with only an HTTP ACME challenge location on
port 80. Every other HTTP request returns 404; no desktop, password prompt or WebSocket is
available over plaintext HTTP. Verify a temporary challenge file from the public IP and
remove it. Obtain a trusted Let's Encrypt IP certificate using Certbot 5.4 or newer, the
webroot authenticator, `--ip-address "$WORKSPACE_IP"`, `--preferred-profile shortlived`,
`--cert-name shared-browser-ip`, `--non-interactive`, `--agree-tos` and
`--register-unsafely-without-email`. Reuse a valid existing certificate on retries.

Keep Certbot's complete configuration in the certificates volume so symlinks resolve; nginx
mounts it read-only. Configure nginx to serve TLS internally on 443, published as host 8443,
using this certificate. Port 80 keeps the challenge location and redirects other traffic to
the actual HTTPS desktop address and port. Apply authentication to the entire TLS server,
including WebSocket handshakes. Preserve any existing working configuration if validation
fails. Never fall back to an HTTP desktop or ask the user to bypass certificate warnings.

Now start the desktop service, wait for its health check, test nginx and reload it. Confirm
that Chromium is visibly running in Xvfb and that Playwright can attach to its existing tab.
Verify normal TLS validation, an unauthenticated **401** for both the desktop and WebSocket
paths, refusal with incorrect credentials, and a successful authenticated WebSocket upgrade.
Supply real credentials to checks through protected stdin/configuration, never command-line
literals or URLs. Check that raw VNC, 6080 and CDP have no published Docker host bindings.
Do not claim the desktop works until these checks pass.

Add `~/.config/workspaced/services/shared-browser-tls-renew.sh` using the platform's service
runner. Its foreground `exec bash` loop checks Certbot renewal every 12 hours and tests/reloads
nginx after success. Test with `certbot renew --dry-run`. Do not create an AI scheduled task
for renewal. Certificate renewal needs port 80 to remain available.

Give the user the verified clickable URL
`https://<actual-public-IP>:8443/vnc.html?autoconnect=true&resize=remote&path=websockify`.
Tell them to enter the VNC username and password they saved when their browser asks, then
use the already-open Chromium window. Ask which site they want to open. If they already
named one, navigate the visible browser there with Playwright before handing over.
The recipe must open the remote browser itself, not just provide instructions to launch it.

## 5. Let the user sign in, then share control

Tell the user to type website passwords, one-time codes and recovery details directly into
the website inside the remote desktop. Do not request website secrets in Settings or chat,
watch their keystrokes, capture login screenshots, or read password inputs or cookies.
Ask them to return here and say when they are signed in and which site/account to use.
End this turn and wait. Do not infer a successful login from their opening the desktop link.

Once they confirm, attach to the running browser and verify a non-sensitive signed-in UI
indicator. Use its existing default context and tabs, for example:

```python
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9222")
    context = browser.contexts[0]
    page = context.pages[0] if context.pages else context.new_page()
    # Locate the requested tab by URL before acting when several tabs exist.
    print(page.url)
    # Perform the user's requested UI interaction here.
    # Leave the shared browser running when the Playwright client disconnects.
```

Do not call `browser.close()`, close the default context, export storage state or launch an
isolated context. Verify that ending this client leaves the desktop browser alive. Select
the correct tab by site URL on later runs instead of assuming the first tab is correct.

When a CAPTCHA, MFA prompt, device approval, expired login or other human check appears,
**ask the user for help**. Explain briefly which website needs attention, give the desktop
link, and pause browser actions and retries. Let them take control in VNC. Do not bypass
CAPTCHAs, use solving services, defeat site checks or automate credential entry. Resume only
after they explicitly say they have finished, and re-check the current page first. Use this
same handoff whenever the agent is stuck or needs a human decision. Avoid competing mouse
or keyboard actions while the user is controlling the desktop.

## 6. Make later sessions and tasks usable

Write `~/.config/workspaced/instructions/shared-browser.md` with the actual desktop URL,
Compose directory/project, wrapper path, attach example, profile volume and service names,
start/stop/health commands, renewal procedure and secret **names only**. Record site domains
the user says they signed into and any scope they authorize; never store passwords, cookies,
session tokens or private page contents. Explain how to find the right tab and request a
VNC handoff when a CAPTCHA or login blocks progress.

Later tasks must reuse this browser and first check its health and the website's session.
A scheduled run encountering a human check must stop, report that it needs the user's help
through the task's existing result mechanism, and leave the tab available. Do not create a
new notification integration. Do not keep retrying or mark the task complete while blocked.
For a job-listing request, browse the visible jobs UI and return matching links and summaries;
submitting applications, sending messages or changing account settings needs user direction.

Tell the user that website sessions can expire or be revoked at any time. Profiles survive
ordinary service restarts in this workspace, but session-only cookies may not. Workspace
deletion or trial expiry removes the browser and profile; there is no promise of permanent
login. Closing the VNC viewer does not close Chromium. Closing a firewall rule prevents
remote viewing but does not sign out websites or stop the agent. To revoke the agent's
access, stop the desktop service or sign out of the website. Provide the tested stop command;
never delete the profile or volume without explicit permission.

Finish with the working desktop link and a brief explanation that the user can now request
browser tasks and will be asked for help when needed. Create a scheduled task only when
the user specifies its scope and schedule.

References: https://playwright.dev/python/docs/docker,
https://playwright.dev/python/docs/api/class-browsertype#browser-type-connect-over-cdp,
https://github.com/novnc/noVNC/wiki/Proxying-with-nginx,
https://nginx.org/en/docs/http/ngx_http_auth_basic_module.html,
https://nginx.org/en/docs/http/websocket.html
