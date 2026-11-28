# OpenCode V2 rollout

The harness is pinned to OpenCode 2.2.5 with its SHA-512 package integrity checked during the image build.
The API speaks V2 exclusively. Build and roll out the API and harness together; upgrading only one side is unsupported.
Session creation checks `/api/info` and resolves the project with `/api/location` before creating it.

Workspace Profile settings also support ChatGPT device sign-in. Start the workspace, connect ChatGPT,
approve its device code, then restart to activate the saved profile. Each ChatGPT connector belongs to
one workspace; API-key connectors remain reusable. This avoids cloning rotating refresh tokens into
multiple OpenCode databases. OpenCode owns token refresh after the initial credential import.

The API encrypts pending device state and the initial OAuth credential. Browser responses contain
only the approval code, login status and connector metadata. The VM manager receives a non-secret
`managed-chatgpt` marker and starts the `openai-chatgpt` harness profile without either API-key
environment variable. After runtime readiness, the API selects the native OpenCode credential before
activating the binding. Existing native credentials are preserved on later activations so refreshed
tokens are never overwritten with the initial snapshot. Removing an unused ChatGPT connector also
removes its native credential; its workspace must be running.

Deploy the API, VM manager and harness changes together. Device sign-in uses the pinned OpenCode
2.2.5 `chatgpt-headless` protocol from
[`openai.ts`](https://github.com/anomalyco/opencode/blob/v2.2.5/packages/core/src/plugin/provider/openai.ts).
Re-check that protocol and the native credential API when upgrading OpenCode. Local tests cover
mocked device approval and native credential switching with synthetic credentials; real account
approval and a provider-backed reply still require a signed-in browser check.

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
