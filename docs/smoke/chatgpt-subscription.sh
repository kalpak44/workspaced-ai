#!/bin/sh
# Local-only smoke test. Never run this with hosted or shared workspace credentials.
set -eu
umask 077

usage() {
  echo "Usage: $0 check | run openai/MODEL" >&2
  exit 2
}

case "${1:-}" in
  check) [ "$#" -eq 1 ] || usage ;;
  run)
    [ "$#" -eq 2 ] || usage
    case "$2" in openai/?*) ;; *) usage ;; esac
    [ -t 0 ] && [ -t 1 ] || { echo "Run requires an interactive terminal for ChatGPT sign-in." >&2; exit 2; }
    ;;
  *) usage ;;
esac

bin=$(command -v "${OPENCODE_BIN:-opencode}") || { echo "Install OpenCode first." >&2; exit 1; }
case "$bin" in /*) ;; *) bin="$PWD/$bin" ;; esac
command -v jq >/dev/null || { echo "Install jq first." >&2; exit 1; }

scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$scratch/home/.config/opencode" "$scratch/project"
cat > "$scratch/home/.config/opencode/opencode.json" <<'JSON'
{
  "$schema": "https://opencode.ai/config.json",
  "policies": [
    { "action": "provider.use", "resource": "*", "effect": "deny" },
    { "action": "provider.use", "resource": "openai", "effect": "allow" }
  ],
  "permissions": [
    { "action": "*", "resource": "*", "effect": "deny" }
  ],
  "share": "disabled",
  "update": "disable"
}
JSON

cd "$scratch/project"
oc() {
  env -i PATH="$PATH" TERM="${TERM:-dumb}" LANG="${LANG:-C}" \
    HOME="$scratch/home" XDG_CONFIG_HOME="$scratch/home/.config" \
    XDG_DATA_HOME="$scratch/home/.local/share" XDG_CACHE_HOME="$scratch/home/.cache" \
    XDG_STATE_HOME="$scratch/home/.local/state" XDG_RUNTIME_DIR="$scratch/runtime" \
    "$bin" --standalone "$@"
}
mkdir -p "$scratch/runtime"

if [ "$1" = check ]; then
  oc debug config > "$scratch/config.json"
  jq -e 'any(.[]; .type == "document" and
    (.info.policies | any(.action == "provider.use" and .resource == "*" and .effect == "deny")) and
    (.info.policies | any(.action == "provider.use" and .resource == "openai" and .effect == "allow")) and
    (.info.permissions | any(.action == "*" and .resource == "*" and .effect == "deny")))' \
    "$scratch/config.json" >/dev/null
  oc stats --all --json > "$scratch/stats.json"
  jq -e 'select(.sessions == 0) | {sessions, tokens}' "$scratch/stats.json"
  exit
fi

# Use only the documented subscription-sharing method, not an API key or legacy login.
oc auth login openai --method chatgpt-token-sharing
oc run --model "$2" --title "Workspaced subscription smoke test" \
  "Reply with exactly WORKSPACED_OK. Do not use any tools." > "$scratch/reply"
cat "$scratch/reply"
grep -q '^WORKSPACED_OK[[:space:]]*$' "$scratch/reply" || {
  echo "Expected smoke-test reply was not received." >&2
  exit 1
}
oc stats --all --models --json > "$scratch/stats.json"
jq -e '
  select(.sessions > 0 and .tokens.input > 0 and .tokens.output > 0)
  | {sessions, tokens, models: [.models[] | {model, tokens}]}' "$scratch/stats.json"
