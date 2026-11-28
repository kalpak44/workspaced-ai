#!/bin/sh
# One image, one active provider profile. This is runtime configuration, not auth.
# /etc/opencode points at this container's writable /tmp, never at persisted home defaults.
set -eu

profile=${WORKSPACED_AI_PROVIDER-openrouter}
case "$profile" in
  openrouter | openai | openai-chatgpt) ;;
  *) echo "Unsupported WORKSPACED_AI_PROVIDER: $profile (expected openrouter, openai or openai-chatgpt)" >&2; exit 2 ;;
esac

managed=/tmp/workspaced-opencode
mkdir -p "$managed" "$HOME/.config/opencode" "$HOME/.local/bin" "$HOME/project"
# Rebuild from the image every start, including restarts with the same home. OpenCode
# reads this once as system-managed config, above global/project config and policies.
jq -s '.[0] * .[1]' /usr/share/harness/opencode/common.json \
  "/usr/share/harness/opencode/profiles/$profile.json" > "$managed/opencode.json"
# Only these two files are harness-owned. Keep user opencode.jsonc, MCP, instructions,
# plugins and all project files. Remove the old, formerly replaced-on-start defaults
# so instructions and policies are not loaded a second time from the global directory.
rm -f "$managed/opencode.jsonc" "$HOME/.config/opencode/opencode.json"
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
# The workspace's variables: each NAME=value line of ~/.config/workspaced/.env, set here so that
# OpenCode, the terminal and every service start with them. One already set, by a secret or the
# image, keeps its value, and none may set OpenCode's own, which would lock the API out. The value
# is taken as it is, without expansion; one pair of quotes around it is removed. Blank lines and
# lines starting with # are skipped, and an `export ` in front is allowed.
env_file="$HOME/.config/workspaced/.env"
if [ -f "$env_file" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line#"${line%%[![:space:]]*}"}
    line=${line#export }
    case "$line" in '' | '#'*) continue ;; esac
    name=${line%%=*}
    [ "$name" != "$line" ] || continue
    case "$name" in '' | [0-9]* | *[!A-Za-z0-9_]* | OPENCODE_* | WORKSPACED_AI_PROVIDER) continue ;; esac
    eval "[ -z \"\${$name+set}\" ]" || continue
    value=${line#*=}
    case "$value" in
      \"*\") value=${value#\"}; value=${value%\"} ;;
      \'*\') value=${value#\'}; value=${value%\'} ;;
    esac
    export "$name=$value"
  done < "$env_file"
fi
# The user's services: every ~/.config/workspaced/services/<name>.sh is kept running, with this
# environment, until its file is deleted. They start again on every start of the workspace.
start-services &
exec opencode serve --hostname 0.0.0.0 --port 4096
