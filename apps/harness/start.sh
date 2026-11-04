#!/bin/sh
# opencode writes into its config directory (a .gitignore, its lockfile), so the defaults
# baked into the image are copied into the home, the one writable place, on start.
set -eu
mkdir -p "$HOME/.config/opencode" "$HOME/project"
cp -R /usr/share/harness/opencode/. "$HOME/.config/opencode/"
# V1's directory-management plugin must never be loaded by V2. Existing project files stay put.
if [ -f "$HOME/.config/opencode/plugin/session-directories.js" ]; then
  mv "$HOME/.config/opencode/plugin/session-directories.js" "$HOME/.config/opencode/session-directories.v1.disabled"
fi
exec opencode serve --hostname 0.0.0.0 --port 4096
