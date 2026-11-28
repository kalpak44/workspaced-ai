# Supervisor updates

VM startup and a requested workspace restart fetch `/agent/harness` through the existing
signed agent protocol. Its optional `supervisor` object is independent of the harness image:

```json
{
  "supervisor": {
    "version": "<full commit SHA>",
    "artifacts": {
      "amd64": {"url": "https://github.com/kalpak44/workspaced-ai/releases/download/supervisor-<SHA>/supervisor-linux-amd64", "sha256": "<approved 64 hex checksum>"},
      "arm64": {"url": "https://github.com/kalpak44/workspaced-ai/releases/download/supervisor-<SHA>/supervisor-linux-arm64", "sha256": "<approved 64 hex checksum>"}
    }
  }
}
```

Absent, null, or an empty version pauses new update attempts. Older supervisors ignore the
field; the hub forwards the harness response without parsing its secrets. Configuration is
fetched on startup/restart and harness recovery, not continuously during a healthy workspace.
An unchanged installed version does not download anything. A lower pinned version is an
explicit rollback, so version ordering is intentionally not inferred.

## Release and trust

The supervisor publish job tests the code and publishes `supervisor-<full commit SHA>` GitHub
releases containing static Linux amd64/arm64 binaries, `bootstrap.sh`, `SHA256SUMS`, and
`release.json`. It refuses to overwrite an existing release; reruns verify existing contents.
Release creation requires repository write permission. No VM resolves `latest`.

An operator approves the release and copies its checksums from the trusted build into VM
Manager configuration. This is the authenticity anchor: checksums arrive through the
VM's authenticated HTTPS control-plane route, not from the binary download server. The VM
requires HTTPS for that route, the artifact URL, and every redirect. A compromised mirror
cannot authorize its own binary by changing an adjacent checksum file. This uses trusted
control-plane digest pins rather than a separate release signing key. Treat changes to those
pins and access to the publishing workflow as release authority.

The independent VM Manager settings (and GitOps deployment environment entries) are:

| Setting | Behavior |
| --- | --- |
| `WORKSPACED_SUPERVISOR_VERSION` | Approved full release SHA; empty disables selection |
| `WORKSPACED_SUPERVISOR_AMD64_SHA256` | Trusted amd64 binary SHA256 |
| `WORKSPACED_SUPERVISOR_ARM64_SHA256` | Trusted arm64 binary SHA256 |
| `WORKSPACED_SUPERVISOR_BASE_URL` | Defaults to this repository's GitHub release download path |
| `WORKSPACED_SUPERVISOR_PAUSED` | Defaults to `true`; null metadata prevents new attempts |
| `WORKSPACED_SUPERVISOR_VM_IDS` | Comma-separated canary VM UUIDs; empty selects the fleet |
| `WORKSPACED_HARNESS_IMAGE` | Continues to select the harness independently |

Pause does not cancel a transaction already staged on a VM. Check its journal before assuming
an in-flight update stopped. A failed version is blocked locally by checksum; publishing a new
pin or explicitly clearing the journal's `failed` field through the trusted host channel allows
a retry. Do this only after diagnosing the failed release.

## Installation and rollback

The stable `/usr/local/lib/workspaced/supervisor-launcher --launch` runs as the systemd service.
It starts native supervisors from `/var/lib/workspaced/supervisor/supervisor-<checksum>`.
The supervisor downloads at most 64 MiB to a temporary file, verifies the approved SHA256,
and stages a root-only request. Download failures leave the existing supervisor and harness
running and are retried at the next startup/restart or harness recovery.

The launcher verifies the staged binary again, durably records current/previous versions and
a pending transaction in `journal.json`, stops the old supervisor gracefully, and starts the
candidate. It commits only after that process has brought up its harness and successfully
reported signed readiness to the hub. A new private readiness marker prevents accepting a
previous process's readiness. Workspace restarts also recreate the harness after replacing
the supervisor. The old supervisor binary remains available.

A candidate that cannot execute, exits before readiness, or fails to report ready within ten
minutes rolls back. A launcher crash or reboot with a pending transaction also rolls back.
The same failed checksum is suppressed to avoid a restart loop. Network or harness failures
can therefore conservatively roll back a good candidate. After acceptance, ordinary process
crashes are handled by systemd; this does not promise indefinite post-release health rollback.
The stable launcher itself requires a separate controlled host installation to upgrade.

No update removes `/home/workspace`, Docker data, `/etc/workspaced/agent.env`, the enrollment
marker, or `agent.key`. Root authority stays at the host; the harness does not get the launcher
or its state directory.

## Bootstrap existing VMs

Existing Docker supervisors cannot acquire the launcher through a harness restart. Use a
trusted root host channel, such as an already established management channel, to copy the
reviewed `install/bootstrap.sh` and approved native binary onto the VM. Keep a recoverable disk
snapshot and verify a workspace file before and after the operation. SSH is masked on these
images: a signed harness tunnel is not a root host channel. Where no management channel
exists, arrange Hetzner console/rescue access and a controlled host maintenance session first.
Do not recreate or rebuild an existing VM to bootstrap it.

Run on the VM host, using the checksum independently approved from the release build:

```sh
sudo sh bootstrap.sh ./supervisor-linux-amd64 <version> <approved-sha256>
```

Use the arm64 binary on an arm64 host. The installer checks its checksum and reported version,
retains the original unit as `original.service`, stops the old supervisor unit, installs the
watchdog unit, and starts it with the existing identity and disks. It refuses a second bootstrap.
If initial bootstrap fails, use the same host channel to stop the new unit, restore
`/var/lib/workspaced/supervisor/original.service` to
`/etc/systemd/system/workspaced-supervisor.service`, run `systemctl daemon-reload`, and start
that unit. Confirm the original pinned image is still cached before doing this. New supervisor
updates after a successful bootstrap use automatic rollback.

For fresh VMs, Packer now extracts the binary and bootstrap installer from an explicitly
pinned `WORKSPACED_SUPERVISOR_IMAGE=ghcr.io/kalpak44/workspaced-ai-supervisor@sha256:<digest>`
image. The installer runs with `--image`, which enables the service without starting or
enrolling the build server. The image must be built from the new Dockerfile carrying
`/bootstrap.sh`; older images intentionally fail this build. Build through
`just --justfile packer/Justfile build workspace-vm` in homelab-infra.

## Fleet reporting and rollout

Supervisor heartbeats include optional `supervisor` status even when memory is unavailable:
`installed`, `desired`, `state`, `time`, and a bounded failure reason. Agent-hub forwards it to
VM Manager. Central `supervisor_status` logs include the VM UUID; per-VM systemd JSON logs and
the local journal provide recovery evidence. Arbitrary error bodies, URLs, tokens and secret
values are excluded from central status logs. Reports are held in memory and rebuilt after a
control-plane restart; log storage supplies history.

VM Manager emits warning events `supervisor_update_failed`, `supervisor_version_behind`, and
`supervisor_restart_stalled`. The latter checks a workspace that remains RESTARTING for
15 minutes. Behind-version alerts start after a 15-minute control-plane warmup, include legacy
VMs with unknown versions, apply only to selected unpaused VMs, and exclude stopped VMs.
Wire these warning events to the deployment's log alert receiver; notification delivery is an
operations configuration step, not implemented by sending messages from the VM. Pausing
suppresses behind-version alerts, while failures and stalled restarts remain observable.

1. Publish a release, review the build and checksum pins, and leave rollout paused.
2. Build a disposable VM from the new Packer image. Check enrollment, initial readiness,
   workspace file persistence, update, restart, and automatic rollback.
3. Bootstrap the affected existing VM through the trusted host channel. Record its UUID and
   add only that UUID to `WORKSPACED_SUPERVISOR_VM_IDS`. Set the approved version/checksums and
   unpause, then start/restart that workspace.
4. Confirm `supervisor_status` reaches the desired installed version and `current`, the
   workspace reports READY, its harness answers, and its files remain intact. Inspect
   `journalctl -u workspaced-supervisor.service` and `journal.json` on the host if needed.
5. Expand the UUID list to a small group. After observing that group, clear the list for the
   remaining bootstrapped VMs. They update at their next startup/restart.
6. On failures, pause to prevent further attempts. To roll back accepted VMs, pin the prior
   release and its two checksums, unpause for the affected IDs and restart them. A broken
   supervisor that cannot receive configuration requires the independent host channel.

## Verification

Local tests exercise checksum failure, TLS downloads, rejected and truncated downloads,
architecture selection, pause/current pins, restart intent, failed-release suppression,
journal recovery, successful process replacement, early process exit and health timeout
rollback. Existing supervisor, VM Manager and agent-hub suites check protocol compatibility.
Linux systemd bootstrap, disk snapshot recovery, fresh Hetzner creation, actual release
publication and staged fleet rollout still require the disposable-VM operations check above.
