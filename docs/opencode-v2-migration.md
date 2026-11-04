# OpenCode V2 rollout

The harness is pinned to OpenCode 2.2.5 with its SHA-512 package integrity checked during the image build.
The API speaks V2 exclusively. Build and roll out the API and harness together; upgrading only one side is unsupported.
Session creation checks `/api/info` and resolves the project with `/api/location` before creating it.

New workspaces receive a 10Gi PVC by default. The cluster must provide a default storage class, or set
`WORKSPACED_WORKSPACE_STORAGE_CLASS`; `WORKSPACED_WORKSPACE_STORAGE_SIZE` controls the requested size.
Grant the API service account get/list/create/delete on persistentvolumeclaims. Remove pods/exec permission.

Existing Deployments are not mutated automatically. Before replacing a V1 pod, copy its entire
`/home/workspace` directory to a backup, including hidden OpenCode data and configuration. Its old emptyDir
will disappear when the pod is replaced. Provision a PVC of the workspace resource name, restore the backup with
UID/GID 1000 ownership, and change the Deployment's home volume from emptyDir to that PVC while updating its image.
Keep the Recreate strategy. Keep the existing authentication Secret: V2 accepts OPENCODE_SERVER_PASSWORD.
The startup script disables the obsolete session-directory plugin and installs native V2 defaults.

Keep the backup until existing sessions have been verified in V2. Workspaced never deletes database rows merely
because the new harness cannot find their conversations; they appear unavailable. Existing sessions retain their
stored location, while newly created sessions share `/home/workspace/project`.

Verification performed locally:

```sh
docker build --platform linux/amd64 -t workspaced-harness:v2-check apps/harness
# Start a disposable harness on a loopback port, then run the Java transport smoke test:
cd apps/api
mvn -Dtest=OpenCodeLiveTest -Dopencode.live.url=http://localhost:14096 \
  -Dopencode.live.password=integration-check test
```

The live smoke test covers version/location discovery, session creation/list/history/deletion, and real Bash PTY
input/output/resize/cleanup without an LLM request. Provider-backed replies require valid workspace credentials.
