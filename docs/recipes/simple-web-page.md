---
title: Simple web page
summary: Create a welcoming page, share it with the world, and make it your own through chat.
category: websites
tags: [demo, website, first-page]
visibility: public
recipe: 00000000-0000-4000-8000-00000000b00d
---

# Simple web page

Build one reproducible page called **Demo page from workspaced**. Use the exact starting
files below: Python's standard library serves the HTML and SQLite stores its heading and
message. No packages, credentials, domain, scheduled tasks or shared instructions are needed.
Use `~/project/demo-page` and container name `workspaced-demo-page`.

## 1. Prepare the page, then ask to make it PUBLIC

Inspect the folder and port 80 first. On a fresh workspace, create the files below. On retries,
reuse the existing database and preserve any changes the user has made. Do not overwrite
another site or stop an unrelated container. If port 80 is in use, explain the conflict and
ask before replacing anything.

**Always pause and ask the user before making this page PUBLIC**, even when applying this
recipe or when port 80 is already open. Say: “Shall we make this page PUBLIC so people can
open it from the internet? Reply here to let me publish it.” Explain that HTTP is unencrypted
and anyone with the address can visit. Do not open ports or start the public server until the
user has confirmed in chat. End your turn and wait. This is the same checkpoint every time.
Do not direct the user to Settings or ask them to open ports manually during the normal flow.

### index.html

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Demo page from workspaced</title>
  <style>
    :root { color-scheme: light dark; font-family: Inter, ui-sans-serif, system-ui, sans-serif; color: #18181b; background: #fafafa; }
    * { box-sizing: border-box; }
    body { margin: 0; line-height: 1.6; }
    header { border-bottom: 1px solid #e4e4e7; padding: 16px 24px; font-weight: 600; }
    header span, .muted { color: #71717a; }
    main { max-width: 760px; margin: 64px auto; padding: 0 24px; }
    .badge { display: inline-block; color: #047857; background: #ecfdf5; border: 1px solid #a7f3d0; border-radius: 6px; padding: 4px 10px; font-size: 14px; }
    h1 { font-size: clamp(32px, 6vw, 48px); line-height: 1.15; letter-spacing: -.04em; margin: 24px 0 16px; }
    .card { background: #fff; border: 1px solid #e4e4e7; border-radius: 12px; padding: 24px; margin-top: 32px; box-shadow: 0 1px 2px #00000008; }
    h2 { margin: 0 0 8px; font-size: 18px; }
    p { margin: 0 0 12px; }
    footer { margin-top: 32px; font-size: 14px; }
    @media (prefers-color-scheme: dark) {
      :root { color: #f4f4f5; background: #09090b; }
      header, .card { border-color: #27272a; }
      .card { background: #18181b; }
      header span, .muted { color: #a1a1aa; }
      .badge { background: #022c22; color: #6ee7b7; border-color: #065f46; }
    }
  </style>
</head>
<body>
  <header>workspaced<span>-ai</span></header>
  <main>
    <span class="badge">Your first page is live</span>
    <h1>{{heading}}</h1>
    <p class="muted">{{message}}</p>
    <section class="card">
      <h2>A little space for your next big idea.</h2>
      <p>Welcome to my corner of the internet. A place to share ideas, tell a story, or start something new.</p>
      <p class="muted">This is just the beginning. Come back soon to see what grows here.</p>
    </section>
    <footer class="muted">Made with a little curiosity and workspaced.</footer>
  </main>
</body>
</html>
```

### app.py

```python
import html
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DB = ROOT / "data" / "page.sqlite3"
DB.parent.mkdir(parents=True, exist_ok=True)
with sqlite3.connect(DB) as db:
    db.execute("CREATE TABLE IF NOT EXISTS page (id INTEGER PRIMARY KEY CHECK (id = 1), heading TEXT NOT NULL, message TEXT NOT NULL)")
    db.execute("INSERT OR IGNORE INTO page VALUES (1, ?, ?)", (
        "Demo page from workspaced",
        "Hello, internet. I built this page with my assistant and a Workspaced recipe.",
    ))

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/", "/index.html"):
            self.send_error(404)
            return
        with sqlite3.connect(DB) as db:
            heading, message = db.execute("SELECT heading, message FROM page WHERE id = 1").fetchone()
        body = (ROOT / "index.html").read_text(encoding="utf-8")
        body = body.replace("{{heading}}", html.escape(heading)).replace("{{message}}", html.escape(message))
        payload = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
```

## 2. After the user confirms, publish over HTTP

The user’s confirmation in chat authorizes public access. Run `firewall list`, then
`firewall open 80` if TCP port 80 is not already open to Anyone. Verify the rule with
`firewall list`. Handle this yourself; do not send the user through a settings walkthrough.
If the firewall tool reports that the user has disabled agent access, explain the blocker
and wait for their decision. Never bypass that permission or claim the page is public.
Use the real `$WORKSPACE_IP` or the address printed by `firewall list`; never invent an IP.

Use a Docker container: a server running directly inside the assistant container is not public.
Harness paths are not necessarily Docker-host paths, so do not bind-mount `~/project/demo-page`.
Create a named volume, copy the files into it with `docker cp`, and run the server from there:

```sh
docker volume create workspaced-demo-page
docker create --name workspaced-demo-page --restart unless-stopped \
  -p 80:8080 -v workspaced-demo-page:/app -w /app python:3-alpine python app.py
docker cp "$HOME/project/demo-page/app.py" workspaced-demo-page:/app/app.py
docker cp "$HOME/project/demo-page/index.html" workspaced-demo-page:/app/index.html
docker start workspaced-demo-page
```

On a retry, inspect and reuse the named container instead of creating another one. Keep SQLite
inside `/app/data` on the named volume; never delete the volume to update the page. For later
edits, update the source and copy changed files into the container. Restart after Python changes.
Edit the SQLite row for heading/message changes; the HTML placeholders display that row.

Check the container logs and fetch `http://$WORKSPACE_IP/` with `curl --fail --max-time 15`.
Confirm the expected heading and message appear. Check `/app.py`, `/data/page.sqlite3` and
`/../app.py` return 404 so source files and the database are never served. If you cannot check
from an independent internet host, say what you checked and ask the user to open the link
in their browser; do not claim an external check you did not perform.

## 3. Give the user the address and invite a change

Only report success after the HTTP check passes. End with a clickable `http://<actual-public-IP>/`
link and: “Open this link in your browser to see your page. Anyone on the internet can open
it; HTTP is unencrypted. What would you like to change? Ask me for any change to this page,
then refresh the same link.” For a trial, remind them the page and its data disappear with the
workspace when the trial expires. Do not send email or configure tasks, shared instructions,
a domain or HTTPS as part of this recipe.
