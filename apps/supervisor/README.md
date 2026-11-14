# supervisor

Runs on every workspace VM. It enrolls the VM with agent-hub once, using the single-use token
from cloud-init, reports `ready` once per boot, and then sends a signed `heartbeat` every 30 s.
The protocol is described in the agent-hub spec; requests are signed with an Ed25519 key that
never leaves the VM.

Files:

- `/etc/workspaced/agent.env` (`SUPERVISOR_CONFIG`) — `WORKSPACED_VM_ID`, `WORKSPACED_AGENT_URL`,
  `WORKSPACED_ENROLLMENT_TOKEN`. The token line is removed once enrollment succeeds.
- `/var/lib/workspaced` (`SUPERVISOR_STATE_DIR`) — `agent.key` (Ed25519 seed, 0600) and the
  `enrolled` marker that makes a restart skip enrollment.

On a VM, systemd starts it as:

    docker run --network host -v /etc/workspaced:/etc/workspaced \
      -v /var/lib/workspaced:/var/lib/workspaced --cap-drop ALL --read-only \
      --security-opt no-new-privileges <image>

Test with `go test ./...`; build with `docker build --build-arg VERSION=<tag> .`.
