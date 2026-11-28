#!/bin/sh
# Run through a trusted root host channel, never inside the harness.
# Inputs: local release binary, immutable version, trusted SHA256, optional --image.
set -eu
[ "$(id -u)" = 0 ] || { echo 'Run as root' >&2; exit 1; }
[ "$#" -ge 3 ] || { echo 'Usage: bootstrap.sh BINARY VERSION SHA256 [--image]' >&2; exit 1; }
binary=$1 version=$2 checksum=$3
case "$version" in ''|*[!a-zA-Z0-9._-]*) exit 1;; esac
[ "${#checksum}" = 64 ] || exit 1
case "$checksum" in *[!a-f0-9]*) exit 1;; esac
printf '%s  %s\n' "$checksum" "$binary" | sha256sum -c -
[ "$("$binary" --version)" = "$version" ] || { echo 'Version mismatch' >&2; exit 1; }
dir=/var/lib/workspaced/supervisor
[ ! -e "$dir/journal.json" ] || { echo 'Already bootstrapped; use desired-version configuration' >&2; exit 1; }
install -d -m 0700 "$dir" /etc/workspaced /var/lib/workspaced/firewall.d /usr/local/lib/workspaced
install -m 0700 "$binary" "$dir/supervisor-$checksum"
install -m 0700 "$binary" /usr/local/lib/workspaced/supervisor-launcher
umask 077
printf '{"current":{"version":"%s","sha256":"%s"},"pending":false}\n' "$version" "$checksum" > "$dir/journal.json"
# Save the original unit for manual bootstrap recovery. Workspace, enrollment and Docker
# data are never removed. The old container is stopped only by its original systemd unit.
if [ -f /etc/systemd/system/workspaced-supervisor.service ]; then
 cp -p /etc/systemd/system/workspaced-supervisor.service "$dir/original.service"
fi
if [ "${4:-}" != --image ]; then systemctl stop workspaced-supervisor.service; fi
cat > /etc/systemd/system/workspaced-supervisor.service <<'UNIT'
[Unit]
Description=Workspaced supervisor with rollback watchdog
Requires=docker.service
Wants=network-online.target user@1000.service
After=docker.service user@1000.service cloud-init.service network-online.target
ConditionPathExists=/etc/workspaced/agent.env
[Service]
ExecStartPre=/bin/sh -c 'until [ -S /run/workspace-docker/docker.sock ]; do sleep 1; done'
ExecStart=/usr/local/lib/workspaced/supervisor-launcher --launch
Restart=always
RestartSec=10
TimeoutStartSec=300
TimeoutStopSec=30
KillMode=mixed
UMask=0077
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable workspaced-supervisor.service
if [ "${4:-}" != --image ]; then systemctl start workspaced-supervisor.service; fi
