#!/bin/sh
# The workspace's public web server, nginx kept running as an ordinary service.
#   web-server publish <name>  ask the app to make it public at <name>.<domain>, and wait
#   web-server unpublish       ask the app to take it down
#   web-server reload          test the config, then restart nginx with it
# The app answers a request only when the user lets the agent change the web server. It then
# runs, as it does when the user saves the setting:
#   web-server enable <url>    start it, and note the address it answers at
#   web-server disable         stop it; its config and site are kept
set -eu

dir="$HOME/.config/workspaced/web"
service="$HOME/.config/workspaced/services/nginx.sh"
config="$dir/nginx.conf"

case "${1:-}" in
  enable)
    [ -n "${2:-}" ] || { echo "usage: web-server enable <url>" >&2; exit 2; }
    mkdir -p "$dir" "$HOME/web/public" "$(dirname "$service")"
    # From the first start on, the config and the site are the user's, never replaced.
    [ -f "$config" ] || cp /usr/share/harness/web/nginx.conf "$config"
    [ -n "$(ls -A "$HOME/web/public")" ] || cp /usr/share/harness/web/index.html "$HOME/web/public/"
    printf '%s\n' "$2" > "$dir/url"
    # Written only when it differs: any change to the file restarts nginx.
    cmp -s /usr/share/harness/web/nginx.sh "$service" 2> /dev/null \
      || cp /usr/share/harness/web/nginx.sh "$service"
    ;;
  disable)
    rm -f "$service" "$dir/url"
    ;;
  publish | unpublish)
    if [ "$1" = publish ]; then
      name=${2:-}
      case "$name" in
        '' | -* | *- | *[!a-z0-9-]*)
          echo "usage: web-server publish <name>, in lowercase letters, digits and inner hyphens" >&2
          exit 2
          ;;
      esac
      [ "${#name}" -le 59 ] || { echo "the name can have at most 59 characters" >&2; exit 2; }
    else
      name=off
    fi
    mkdir -p "$dir"
    rm -f "$dir/result"
    printf '%s\n' "$name" > "$dir/request"
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
    echo "no answer: the user has not let the agent change the web server. Ask them to allow it in" \
      "the workspace's settings, under Web server, or to turn it on there themselves." >&2
    exit 1
    ;;
  reload)
    [ -f "$service" ] || { echo "the web server is off; turn it on in the workspace's settings" >&2; exit 1; }
    mkdir -p /tmp/nginx
    # The access log is opened as /dev/stdout, which a caller's terminal may not allow.
    nginx -t -q -e stderr -c "$config" > /dev/null
    # The service runner restarts a service whose file was touched.
    touch "$service"
    echo "nginx restarts within a few seconds; its log is ~/.local/state/workspaced/services/nginx.log"
    ;;
  *)
    echo "usage: web-server publish <name> | unpublish | reload" >&2
    exit 2
    ;;
esac
