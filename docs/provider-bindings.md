# Provider bindings and explicit restart

One connector/provider is active per workspace. Profile offers saved API connectors and
workspace-bound ChatGPT plan sign-in as separate connection methods. ChatGPT controls appear
only when that method is selected. OpenAI API keys remain experimental and can be disabled
with `WORKSPACED_AI_OPENAI_API_KEY_ENABLED=false`.

General settings → AI connectors manages saved API keys. Workspace Settings → Profile
selects a connector, or connects a ChatGPT account. Existing workspace changes require a
verified restart. New workspace creation offers OpenRouter or Use ChatGPT plan; direct
OpenAI API-key creation remains gated. ChatGPT workspace creation is described below.

## Selection and status

`PUT /api/v1/workspaces/{id}/ai-connector` checks ownership, provider support, decryption and
key acceptance, then stages a pending choice. It does not rotate a VM key or change the active
connector/model provider. Same-provider key/connector changes follow the same restart boundary.
The existing GET returns the active connector. Owner-only
`GET /api/v1/workspaces/{id}/ai-connector/status` returns secret-free `active`, `pending`,
supported saved `choices`, `phase`, `error` and `restartRequired`.

The existing settings screen shows last-confirmed active and pending choices, restarting,
rollback, and blocked states. It uses Restart required/Restart workspace and never activates
a choice from a selection/restart response. Only server-confirmed readiness changes active state.
Collaborators cannot select a connector. Staged choices may supersede earlier staged choices;
changes during apply, rollback or blocked recovery return 409 instead of losing an update.

Disconnecting any active/pending connector returns 409: explicitly select and activate another
connector first. There is no automatic Trial or billing fallback. Removing an unused connector
does not revoke its external key. OpenAI credit metrics remain unknown; no subscription allowance
or OpenRouter metrics are invented for it.

## Durable protocol

Migration V80 preserves existing active fields and initializes legacy rows to phase `NONE`,
revision zero. Pending connector, sealed target/previous credential snapshots, revision,
deadline and error persist separately. A configuration revision counter preserves a newer
ordinary secret/variable edit's Restart required flag when an earlier provider restart finishes.

```text
NONE -> STAGED -> APPLYING -> NONE (matching target readiness)
                    |
                    +-> ROLLING_BACK -> STAGED (matching previous-binding readiness)
                    |                      target retained for explicit retry
                    +-> BLOCKED (no authorized restoration or rollback timeout)
```

Prompt/edit/form dispatch and provider restart/reconciliation share a distributed database lease,
without holding a database connection during harness I/O. This prevents an already-accepted request
from crossing the restart boundary and using a replacement key before its binding is confirmed.
The routing lease is ten minutes to cover bounded multi-request edits; a process crash can defer
retry until that lease expires. A timed-out lock returns 409, without changing the binding.

The API commits restart intent before remote I/O. VM Manager internal
`PUT /vms/{id}/provider-binding` atomically stores the desired revision, provider and credential
in one Vault CAS record. Its GET receipt exposes only `revision`/`readyRevision`. Same-content
retries are idempotent; older revisions or different content at the same revision are refused.
The legacy raw key-rotation route is refused after a versioned binding exists.

The harness environment and top-level revision come from one binding snapshot. Only the selected
system key/profile are supplied; the other provider's key/routing settings are removed. Both
keys and `WORKSPACED_AI_PROVIDER` are reserved against ordinary secret/system-variable routes.
The [.harness profile contract](../apps/harness/README.md) remains the source of managed policy.

The supervisor includes the revision in container identity, checks that the requested container
is running, and verifies authenticated OpenCode managed provider policies plus main/title model
providers. An older image that ignores the selector cannot acknowledge the switch. Signed ready
reports carry the revision through agent hub to `/vms/{id}/ready-binding`. Legacy reports,
heartbeats and old revisions cannot activate pending bindings. Pending Vault state continues
to request restart if VM Manager loses transient VM status during process restart.

The API reconciles durable intent against that exact receipt, including after API restart,
and revalidates the target before activation. While merely staged, model/credit reads use the
active binding. Apply, rollback and blocked states reject new prompts, edits, form replies and
model changes rather than route to an unconfirmed profile. Last-confirmed active configuration
does not guarantee the process is currently up.

## Failure and safe gates

An uncertain transport result retains intent and retries the same revision. The timeout defaults
to three minutes (`workspaced.ai.provider-restart-timeout=PT3M`), with reconciliation every ten
seconds (`workspaced.ai.provider-reconcile-ms=10000`). Timeout may restore the old snapshot only
if its connector still belongs to the owner, its current key matches that snapshot, and the
provider accepts it. Rollback gets a new revision: late target readiness cannot activate it.
The old binding is not claimed restored until rollback readiness arrives. A confirmed rollback
retains the pending target for an explicit retry.

If restoration cannot be authorized, sending stays blocked. Restart retries authorized recovery;
revoked/unavailable previous bindings remain gated. An unexpectedly newer runtime revision is also blocked for operator reconciliation; retries do not overwrite unknown newer state.
Legacy rows with no saved active connector
have no recoverable key snapshot; failed changes on those rows require operator reconciliation.
External revocation can race any check; provider errors never trigger another billing provider.
Profile selection is not a security sandbox. ChatGPT device authorization and token lifecycle
are managed by the workspace harness; provider binding controls when that connection becomes active.

All services, supervisor and harness must support this contract before using versioned connector selection, including same-provider changes. Legacy creates and ordinary restarts remain compatible; selection with an older image is safely gated by the readiness probe.
API-key validation uses the [public OpenAI models endpoint](https://developers.openai.com/api/reference/resources/models/methods/list); it does not prove access to a
particular model or successful inference. ChatGPT uses its separate sign-in flow and Codex model catalog; API-key validation does not
establish ChatGPT account eligibility or subscription model access.

## Verification

API HTTP tests cover pending vs active, ownership, secret-free status, same-provider changes,
staged supersession, in-flight conflicts, matching/late readiness, uncertain transport retry,
timeout/rollback, revoked fallback, coordinator restart and newer secret changes. VM Manager
HTTP tests cover atomic profile/key output and legacy/stale readiness. Agent-hub and supervisor
checks cover transport, revision identity and actual managed-profile validation. Harness Docker
checks exercise all three pinned profiles with no external network. The opt-in supervisor test `TestManagedProfileHealthWithPinnedHarness` can also validate an
actual disposable harness (`WORKSPACED_TEST_HARNESS_URL`, `WORKSPACED_TEST_PROVIDER`, test
server password `profile-test`); it never requests inference. It was exercised with OpenRouter
→ OpenAI → OpenRouter on one persisted home, using the pinned image with networking disabled.

All VMs/providers in backend
tests are mocks or local fakes; no real VM, sign-in, inference or deployment is required.

Dispatch attribution and restart fences also feed the owner-only
[Workspaced token ledger](ai-usage.md). Unobserved automatic resumption after a
credential change is excluded, not assigned to an old connector. The separate
[local preview pilot](../tools/local-preview/README.md) is fixture tested outside
the hosted product and never forwards OAuth credentials to the workspace.

## Creating a workspace with a ChatGPT plan

`POST /api/v1/workspaces` accepts `useChatGptPlan: true` with `modelProvider: "openai"`
and no API key or connector ID. This is a paid VM, not a free demo. It is provisioned
with the isolated `openai-chatgpt` profile and no model credentials. The durable
`chatgpt_setup` flag blocks model requests until connection setup succeeds.

The creation dialog opens workspace Profile with Use ChatGPT plan selected. Once its
harness is ready, the owner acknowledges the provider notice and signs in. The first
connection is installed into the already isolated profile before clearing the setup gate;
there is no previous provider or credential to migrate and no extra restart is needed.
Existing workspace connector changes continue using the versioned, verified restart path.

The ChatGPT profile discovers visible, API-supported models from the connected account.
Its managed `openai/workspaced-chatgpt` alias resolves chat and titles to that account’s
default independently of the OpenAI API-key profile. Failed discovery does not expose the
static API-key catalog. Unsupported saved selections resolve to the same account default. Switching connectors resets the workspace model preference so
a model selected under another connection does not carry into a new authentication mode.
Device authorizations are cancelled when their connection section is dismissed.
