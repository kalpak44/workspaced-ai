#!/bin/sh
# opencode writes into its config directory (a .gitignore, its lockfile), so the defaults
# baked into the image are copied into the home, the one writable place, on start.
set -eu
mkdir -p "$HOME/.config/opencode" "$HOME/sessions"
cp -R /usr/share/harness/opencode/. "$HOME/.config/opencode/"
exec opencode serve --hostname 0.0.0.0 --port 4096
