#!/bin/sh
# Which ports of this workspace's VM the internet may reach. The app carries each request out,
# and only when the user lets the agent change the firewall.
#   firewall list                            the VM's address and every open port
#   firewall open <port>[/tcp|udp] [source]  let anyone, or only that address or network, in
#   firewall open icmp [source]              answer ping
#   firewall close <port>[/tcp|udp]          shut it again
#   firewall close icmp
set -eu

usage() {
  echo "usage: firewall list | open <port>[/tcp|udp] [source] | open icmp [source] | close <port>[/tcp|udp] | close icmp" >&2
  exit 2
}

# A port or range with an optional protocol, or icmp.
target() {
  case "$1" in
    icmp) ;;
    *[!0-9/a-z-]* | '' | -* | */*/*) usage ;;
    */tcp | */udp | *[0-9]) ;;
    *) usage ;;
  esac
}

case "${1:-}" in
  list)
    [ "$#" -eq 1 ] || usage
    request=list
    ;;
  open)
    [ "$#" -ge 2 ] && [ "$#" -le 3 ] || usage
    target "$2"
    if [ "$#" -eq 3 ]; then
      case "$3" in *[!0-9a-fA-F.:/]* | '') usage ;; esac
    fi
    request="$*"
    ;;
  close)
    [ "$#" -eq 2 ] || usage
    target "$2"
    request="$*"
    ;;
  *) usage ;;
esac

dir="$HOME/.config/workspaced/firewall"
mkdir -p "$dir"
rm -f "$dir/result"
printf '%s\n' "$request" > "$dir/request"
# The app looks for requests every few seconds and writes its answer beside this one.
for _ in $(seq 1 60); do
  if [ -f "$dir/result" ]; then
    result=$(cat "$dir/result")
    rm -f "$dir/result"
    echo "$result"
    case "$result" in error*) exit 1 ;; esac
    exit 0
  fi
  sleep 1
done
rm -f "$dir/request"
echo "no answer: the user has not let the agent change the firewall. Ask them to allow it in" \
  "the workspace's settings, under Firewall, or to open the port there themselves." >&2
exit 1
