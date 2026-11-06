#!/bin/sh
# opencode writes into its config directory (a .gitignore, its lockfile), so the defaults
# baked into the image are copied into the home, the one writable place, on start.
set -eu
mkdir -p "$HOME/.config/opencode" "$HOME/.local/bin" "$HOME/project"
cp -R /usr/share/harness/opencode/. "$HOME/.config/opencode/"
# The user's own instructions, every Markdown file OpenCode finds here. The first start seeds the
# image's defaults; from then on the folder is the user's, edited in the app and by the agent,
# and nothing here replaces it.
if [ ! -d "$HOME/.config/workspaced/instructions" ]; then
  mkdir -p "$HOME/.config/workspaced/instructions"
  cp /usr/share/harness/instructions/defaults/*.md "$HOME/.config/workspaced/instructions/"
fi
# V1's directory-management plugin must never be loaded by V2. Existing project files stay put.
if [ -f "$HOME/.config/opencode/plugin/session-directories.js" ]; then
  mv "$HOME/.config/opencode/plugin/session-directories.js" "$HOME/.config/opencode/session-directories.v1.disabled"
fi
exec opencode serve --hostname 0.0.0.0 --port 4096
