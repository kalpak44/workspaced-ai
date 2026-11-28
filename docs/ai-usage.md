# Workspaced token usage

General settings → AI connectors shows Workspaced's own recorded model steps,
grouped by the connector, workspace and actual model. Select a personal connector,
or use **Show Trial token usage**. Existing OpenRouter balance/spend/credit warnings
remain provider-wide measurements. Workspaced token counts are not money, a bill,
or remaining ChatGPT quota.

`GET /api/v1/ai-usage` returns the authenticated owner's ledger only, with
`Cache-Control: no-store`. It cannot query another owner. Collaborators do not
receive their workspace owner's connector ledger. Workspace names are returned
only while owned by that same account; deleted workspaces have a neutral label.
No prompt text, provider keys or OAuth credentials enter the ledger or response.

Before a send or edited prompt, under the shared provider-routing lease, the API
commits an immutable dispatch snapshot: owner, workspace, session, connector,
provider and provider revision. A generated `msg_` ID is sent to the pinned
OpenCode prompt endpoint. Collection walks its ordered message history, matching
that user-input ID before attributing following assistant steps. Unknown user
inputs reset attribution. The model and token counts come from the actual
completed assistant message, never an estimate or the currently selected provider.

For form replies the API records a boundary after the existing history before
resumption; it becomes attributable only after an accepted reply. A failed or
uncertain form reply leaves subsequent usage unallocated instead of assigning it
to the previous credential. Already-started messages keep their original dispatch.

Before a versioned provider restart, collection records observed step IDs,
including incomplete messages, and closes old dispatches to new step IDs. This
preserves attribution when an observed message completes late and excludes
untracked automatic resumption after a same-provider credential change. It never
relabels old work with a newly active connector. An unavailable old runtime or
uncertain transport can therefore leave gaps, rather than speculative counts.

A scheduler collects every 30 seconds (`workspaced.ai.usage-collect-ms`) without
requiring a browser or SSE subscription. Collection also happens before sends,
edits, form replies and session cleanup. Database uniqueness on `(session_id,
message_id)` prevents retries/replays/polling from counting a completed message
twice or rewriting its original attribution. Measurements survive session,
workspace or connector deletion and are removed with their owner's account.
No historical usage is backfilled without a trustworthy dispatch record.

## Coverage and limits

Only completed, attributable parent-session assistant messages with valid
nonnegative integral token counts and a matching provider are counted. Missing
receipts, in-progress responses, untracked local calls, child-agent sessions,
title generation and compaction are excluded. These are **recorded steps**, not a
claim to cover every provider request. Destructive history changes or unavailable
runtimes can lose evidence before collection. A retried underlying provider call
that reuses the same assistant message ID is represented by the final recorded
message, not separately billed attempts.

OpenCode 2.2.5 normalizes the fixture's raw 12 input / 7 output tokens into
9 input, 3 cache-read, 5 output and 2 reasoning tokens. The UI reports those
harness categories separately; it does not add them to a monetary cost or quota.
Only raw API completion counts are reported by the separate local-preview pilot.
No usage-based charging is implemented. Harness data is informational telemetry,
not a tamper-proof financial ledger against a user with workspace shell access.

## Checks

API tests exercise real authenticated HTTP dispatch, owner isolation, durable
collection, completed/missing receipts, late completion, restart fences,
same-provider/other-provider switches, form boundaries and stable deduplication.
The frontend suite covers empty/error/refresh/connector selection and existing
OpenRouter metrics. The real Settings page is checked at 320, 390 and 1280 CSS px.

```sh
python3 apps/harness/test-completed-usage.py
node tools/local-preview/pilot.mjs check
```

The first command uses the actual pinned harness entry point with `--network
none`, a synthetic loopback Responses provider and an inert credential. Neither
command uses a real account or provider. Hosted ChatGPT sign-in stays disabled;
see the [local-preview scope and outstanding acceptance](../tools/local-preview/README.md).
