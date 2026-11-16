#!/bin/sh
# The user's own domains that already reach this workspace, one a line. The app keeps the
# list current; only the user adds a domain, in the workspace's settings.
set -eu

file="$HOME/.config/workspaced/domains"
if [ "$#" -ne 0 ]; then
  echo "usage: domains" >&2
  exit 2
fi
if [ -s "$file" ]; then
  cat "$file"
else
  echo "no domains reach this workspace yet. The user can add one in the workspace's settings," \
    "under Domains."
fi
