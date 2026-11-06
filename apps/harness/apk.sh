#!/bin/sh
# Alpine's apk for a user without root: packages go into their own root in the persistent home,
# ~/.local/alpine, so they survive restarts. The image's own packages are left as they are.
# PATH and the musl loader's library path both reach into that root, after the image's own.
# Install scripts are skipped: apk runs them in a namespace, which needs privileges a workspace
# does not have. Most packages have none; one that relies on them (PostgreSQL's client links
# psql into /usr/bin from one) is installed but may leave a command under usr/libexec.
set -eu
root="$HOME/.local/alpine"
mkdir -p "$root" "$HOME/.cache/apk"

# The first word that is not an option names what apk is asked to do.
applet=""
for arg in "$@"; do
  case "$arg" in
    -*) ;;
    *) applet="$arg"; break ;;
  esac
done

# Only commands that change packages take --no-scripts. The first add creates the package
# database in user mode, which every later command then keeps to.
changes=""
case "$applet" in
  add | del | fix | upgrade) changes="--no-scripts" ;;
esac
if [ "$applet" = add ] && [ ! -d "$root/lib/apk/db" ]; then
  changes="$changes --initdb --usermode"
fi

# shellcheck disable=SC2086 # $changes is a list of options, split on purpose
exec /sbin/apk --root "$root" \
  --keys-dir /etc/apk/keys --repositories-file /etc/apk/repositories \
  --cache-dir "$HOME/.cache/apk" $changes "$@"
