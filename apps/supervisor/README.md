# supervisor

Runs on every workspace VM. It enrolls the VM with agent-hub once, using the single-use token
from cloud-init, then fetches the harness image and environment (`/agent/harness`), runs it as
the `workspaced-harness` container through the system Docker socket, reports `ready` once
`127.0.0.1:4096` answers, and sends a signed `heartbeat` every 30 s. A heartbeat answering
`RESTARTING` recreates the harness with fresh values; `PROVISIONING` reports `ready` again. A
container whose image and environment hash matches its `io.workspaced.config` label is kept, and
one that exited is started again. Requests are signed with an Ed25519 key that never leaves the
VM; the environment's values are never logged.

Files:

- `/etc/workspaced/agent.env` (`SUPERVISOR_CONFIG`) — `WORKSPACED_VM_ID`, `WORKSPACED_AGENT_URL`,
  `WORKSPACED_ENROLLMENT_TOKEN`. The token line is removed once enrollment succeeds.
- `/var/lib/workspaced` (`SUPERVISOR_STATE_DIR`) — `agent.key` (Ed25519 seed, 0600) and the
  `enrolled` marker that makes a restart skip enrollment.
- `/var/run/docker.sock` (`SUPERVISOR_DOCKER_SOCKET`) — the system Docker daemon that runs the
  harness. The harness itself only gets the rootless socket `/run/workspace-docker/docker.sock`.

On a VM, systemd starts it as:

    docker run --network host -v /etc/workspaced:/etc/workspaced \
      -v /var/lib/workspaced:/var/lib/workspaced -v /var/run/docker.sock:/var/run/docker.sock \
      --cap-drop ALL --read-only \
      --security-opt no-new-privileges <image>

Test with `go test ./...`; build with `docker build --build-arg VERSION=<tag> .`.
