#!/bin/bash
# Keeps the workspace's services running. A service is one file,
# ~/.config/workspaced/services/<name>.sh, run with bash from the home. Every few seconds the
# folder is looked at again:
#   - a new file is started;
#   - a changed or touched file is restarted;
#   - a deleted file is stopped;
#   - a service that exits is started again, after a pause if it did not last long.
# What a service prints goes to ~/.local/state/workspaced/services/<name>.log, which is cut back
# to its last part once it grows large. Started by start-harness, so services get the same
# environment, secrets included, and start again on every start of the workspace.
set -u

SERVICES="${WORKSPACED_SERVICES:-$HOME/.config/workspaced/services}"
LOGS="${WORKSPACED_SERVICE_LOGS:-$HOME/.local/state/workspaced/services}"
# What runs now; it is in /tmp, emptied on every restart, as the processes are.
STATE="${WORKSPACED_SERVICE_STATE:-/tmp/workspaced-services}"
INTERVAL="${WORKSPACED_SERVICE_INTERVAL:-3}"
LOG_LIMIT=$((2 * 1024 * 1024))
LOG_KEEP=$((1024 * 1024))

mkdir -p "$SERVICES" "$LOGS" "$STATE"

stamp() { date '+%Y-%m-%d %H:%M:%S'; }

# A file's identity: its content and when it was last changed, so `touch` restarts it too.
signature() { echo "$(stat -c %Y "$1")-$(cksum < "$1")"; }

# Runs one service until told to stop. The service gets a session of its own, so stopping it
# stops whatever it started too.
supervise() {
  local name=$1 file=$2 log="$LOGS/$1.log" child="" started code
  trap 'stopping=1; [ -n "$child" ] && stop_group "$child"; exit 0' TERM INT
  stopping=0
  while [ "$stopping" = 0 ] && [ -f "$file" ]; do
    started=$(date +%s)
    setsid bash "$file" >> "$log" 2>&1 < /dev/null &
    child=$!
    echo "[$(stamp)] workspaced: started $name (pid $child)" >> "$log"
    wait "$child"
    code=$?
    # Whatever it left behind in its session goes with it, so a restart starts clean.
    kill -KILL -- "-$child" 2> /dev/null
    child=""
    echo "[$(stamp)] workspaced: $name exited with code $code" >> "$log"
    # One that keeps failing at once is not started again straight away.
    if [ $(($(date +%s) - started)) -lt 10 ]; then sleep 10; else sleep 1; fi
  done
}

# Asks the service's whole session to end, and makes it after ten seconds.
stop_group() {
  kill -TERM -- "-$1" 2> /dev/null || return 0
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 -- "-$1" 2> /dev/null || return 0
    sleep 1
  done
  kill -KILL -- "-$1" 2> /dev/null
}

start() {
  local name=$1 file=$2
  supervise "$name" "$file" &
  echo $! > "$STATE/$name.pid"
  signature "$file" > "$STATE/$name.sig"
}

stop() {
  local name=$1 pid
  pid=$(cat "$STATE/$name.pid" 2> /dev/null)
  if [ -n "$pid" ]; then
    kill -TERM "$pid" 2> /dev/null
    # The supervisor stops its service before it exits; wait for that, but not forever.
    for _ in $(seq 1 15); do kill -0 "$pid" 2> /dev/null || break; sleep 1; done
  fi
  rm -f "$STATE/$name.pid" "$STATE/$name.sig"
  echo "[$(stamp)] workspaced: stopped $name" >> "$LOGS/$name.log"
}

# Keeps a log's last part once it grows large. The service appends, so it carries on at the end.
trim() {
  local log=$1
  [ -f "$log" ] && [ "$(stat -c %s "$log")" -gt "$LOG_LIMIT" ] || return 0
  tail -c "$LOG_KEEP" "$log" > "$log.tmp" && cat "$log.tmp" > "$log"
  rm -f "$log.tmp"
}

shutdown() {
  for pid in "$STATE"/*.pid; do
    [ -f "$pid" ] && stop "$(basename "$pid" .pid)"
  done
  exit 0
}
trap shutdown TERM INT

while true; do
  for file in "$SERVICES"/*.sh; do
    [ -f "$file" ] || continue
    name=$(basename "$file" .sh)
    # The same names as instructions and tasks: a plain file name, never a path.
    [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$ ]] || continue
    pid=$(cat "$STATE/$name.pid" 2> /dev/null)
    if [ -z "$pid" ] || ! kill -0 "$pid" 2> /dev/null; then
      start "$name" "$file"
    elif [ "$(signature "$file")" != "$(cat "$STATE/$name.sig")" ]; then
      echo "[$(stamp)] workspaced: $name changed, restarting" >> "$LOGS/$name.log"
      stop "$name"
      start "$name" "$file"
    fi
    trim "$LOGS/$name.log"
  done
  for pid in "$STATE"/*.pid; do
    [ -f "$pid" ] || continue
    name=$(basename "$pid" .pid)
    [ -f "$SERVICES/$name.sh" ] || stop "$name"
  done
  sleep "$INTERVAL" &
  wait $!
done
