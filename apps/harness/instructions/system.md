# How this workspace works

You run in a container that can be restarted at any time: on a restart, a node move, or when the user applies new secrets. Only the home directory survives a restart. Put everything that must last there.

## The system

- This is **Alpine Linux** with **musl libc**, not Debian, Ubuntu or any glibc distribution. Commands and binaries written for those often fail here.
- There is no root and no `sudo`. Never try `apt`, `apt-get`, `yum`, `dnf` or `brew`: they do not exist.
- Core tools are BusyBox versions, not GNU. Some GNU options are missing, for example `grep -P` (use `rg` for Perl-style regular expressions) and some `sed`, `date` and `find` options. Check `<tool> --help` when an option is refused, or install GNU versions with `apk add coreutils grep sed findutils`.
- Already installed: `bash`, `git`, `curl`, `wget`, `rg` (ripgrep), `node`, `npm`, `npx`, `python3`, `pip`, `uv`, `uvx`, `jq`, `yq`, `make`, `sqlite3`, `ssh`, `nano`, `vi`, `tar`, `gzip`, `zip`, `unzip`, `file`, `tree`, `less`.
- A downloaded binary must be built for musl or be statically linked: look for release assets named `linux-musl`, `x86_64-unknown-linux-musl` or `static`. A glibc build fails with "not found" even though the file exists.

## What survives a restart

- `/home/workspace` (`$HOME`) is a persistent volume. Everything under it is kept.
  - `~/project`: the shared project and the terminal's starting folder.
  - `~/sessions/<id>`: each chat session's own folder. Work for a session belongs here.
  - `~/.local/bin`: your own programs and scripts. It is on `PATH`.
  - `~/.config`, `~/.cache`, `~/.npm`: tool configuration and caches.
- `/tmp` is emptied on every restart. Use it only for scratch files you can lose.
- Everything else (`/usr`, `/etc`, `/opt`, ...) is read-only and comes from the image.

## Installing tools

Every way of installing below puts what it installs in `$HOME`, so it survives restarts. Pick the first that fits.

- **Alpine packages: `apk add <package>`.** It works without root: packages go into `~/.local/alpine`, whose commands are on `PATH` and whose libraries are found automatically. Use `apk search <name>` to find a package and `apk del <package>` to remove one. A compiler and build tools are `apk add build-base`. Package install scripts do not run, so a package that relies on one may leave its command under `~/.local/alpine/usr/libexec/`; link it into `~/.local/bin` if it is not on `PATH`.
- **Node.js: `npm install -g <package>`** installs into `~/.local`. Prefer `npx <package>` for one-off commands.
- **Python tools: `uv tool install <package>`** puts the command in `~/.local/bin`; `uvx <package>` runs one without installing it. Libraries: `pip install <package>` installs into `~/.local` for the user (no `--user` or `--break-system-packages` needed).
- **A standalone binary:** download a musl or static build into `~/.local/bin` and make it executable.
- Do not install into `/tmp` or anywhere else outside `$HOME`: it is either lost on restart or refused.

## MCP servers

- Add MCP servers to `~/.config/opencode/opencode.jsonc`, under `mcp.servers`. That file is kept.
- Never edit `~/.config/opencode/opencode.json`: it is replaced from the image on every start, so anything added there is lost.
- The user can also add and remove MCP servers in the workspace's settings, which write the same `opencode.jsonc`.
- A server started by command (for example `npx -y some-mcp-server`) runs inside this container. Install what it needs as described above, so it still starts after a restart.
- MCP servers started with `npx` or `uvx` work, as do remote ones reached by URL. Servers that need `docker` cannot run here.

## Secrets and environment

- The workspace's secrets are environment variables, set when the container starts. A secret the user adds or removes takes effect only after the workspace restarts.
- Refer to a secret as `{env:NAME}` in MCP configuration instead of copying its value.
- Never write a secret's value into a file, a commit or a chat message.

## The user's instructions

- Besides this guide, you are given every Markdown file in `~/.config/workspaced/instructions/`: the user's own instructions for this workspace, one topic per file. They apply from the next message, in every session.
- They are the user's. The user manages them in the app (the workspace's Instructions page). Add or change a file there only when the user asks you to remember something or to change how you work: keep each file short Markdown on one topic, named for it, like `code-review.md`.
- A change to an existing file, or deleting one, applies from your next message. A new file is given to new sessions only; this one and others already open see it after the workspace restarts. So prefer adding to the file that fits best, and when you do add one, tell the user it applies to new sessions.
- This guide is read-only, in `/usr/share/harness/instructions/`. Never try to change it.
