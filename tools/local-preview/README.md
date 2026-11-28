# Local ChatGPT plan preview pilot

This standalone Node 24+ pilot is for a trusted, single-user local computer. It is
not imported by Workspaced's API, frontend, supervisor or harness image. The hosted
product has **no ChatGPT subscription sign-in**. The experimental hosted OpenAI
binding is API-key transport only, disabled by default.

The [official open-source/local preview](https://developers.openai.com/siwc/token-sharing-open-source)
allows dynamic, user-specific registration without a hosted client ID. It does not
establish eligibility for a paid or remotely hosted Workspaced service. That path
requires [OpenAI commercial partner acceptance and a registered client](https://developers.openai.com/siwc/request-client-id).
No form has been submitted and no partner approval is assumed.

## Offline acceptance check

```sh
node tools/local-preview/pilot.mjs check
```

This is the pilot's real CLI entry point. All OpenAI traffic is replaced by an
in-process mock; fixtures are synthetic, including the generated RSA signing key.
It exercises dynamic/returning registration, fresh PKCE/state/nonce, signed OIDC
validation, wrong account/client/audience/issuer/authorized-party/nonce, expiry,
missing grant, denial/replay/duplicate callback handling, token refresh, revocation,
private atomic storage, stable host identity across restarts, operation locking,
account-specific models, and completed Responses/token receipts. A real loopback
listener receives the mock callback and is verified closed afterward. Nothing
contacts OpenAI or signs into a real account during this check.

The fixture check is **not a live authentication or eligibility test**. The real
sign-in and inference commands below have not been run with a real account.

## Optional local pilot, run manually by the account owner

```sh
node tools/local-preview/pilot.mjs sign-in
# The terminal reports a local ACCOUNT_ID after validated sign-in.
node tools/local-preview/pilot.mjs sign-in ACCOUNT_ID  # same registration/host
node tools/local-preview/pilot.mjs run ACCOUNT_ID MODEL
node tools/local-preview/pilot.mjs sign-out ACCOUNT_ID
```

Sign-in opens the system browser and listens only on `127.0.0.1` at an ephemeral
port, with a five-minute timeout and one-shot callback. The app identifies itself
as **Workspaced Local Preview**, never as another tool. It uses only discovery,
authorization, token, JWKS, revocation and public API endpoints documented by OpenAI.
The selected model must be in the account's current visible catalog; there is no
fallback. The single inference requests a fixed smoke reply and returns only
completed model/input/output measurements, not remaining quota. OAuth refresh is
serialized and the rotated token is stored before another call.

State lives outside the repository at
`~/.local/state/workspaced-local-preview/`: directory `0700`, files `0600`, atomic
writes, no-follow reads and a lock across each operation. Each account has its own
UUID-named record containing its issued client ID, verified subject, credentials
and expiry; `host.json` contains one stable opaque host ID. These are **local
secrets**: do not copy them into workspace files, environment variables, a shared
VM, logs, fixtures or commits. Local file protection does not isolate credentials
from other processes running as the same OS user. The CLI does not load a project
`.env` or accept a token from the shell.

Sign-out attempts renewable-session revocation, clears access/refresh/ID tokens,
and retains the registration/host for later authorization. If remote revocation
cannot be confirmed, it tells the user to disconnect in ChatGPT Settings. A crash
may leave `operation.lock`; remove it only after verifying that no pilot process
is still running. It is intentionally not broken automatically during token rotation.

## Pinned harness relationship and remaining hosted acceptance

OpenCode **2.2.5**, the repository pin, has its own documented
`openai` / `chatgpt-token-sharing` local auth method. The existing
[`docs/smoke/chatgpt-subscription.sh`](../../docs/smoke/chatgpt-subscription.sh)
checks isolated config without login; its opt-in live mode exercises OpenCode's own
local registration, not a Workspaced-hosted connector. This pilot instead owns a
separate Workspaced local registration. It does not reuse OpenCode's client ID,
credential store or OAuth tokens and does not forward them into the harness.
The harness profile and public Responses transport are tested separately with
[`test-completed-usage.py`](../../apps/harness/test-completed-usage.py), using an
inert API-key placeholder and a synthetic loopback provider inside a container
with external networking disabled. That test proves pinned message IDs, selected
model and completed token receipts; it does not prove OAuth authentication.

Real hosted acceptance still needs OpenAI's explicit approval for the commercial
hosting arrangement; a Workspaced-owned client ID, exact callback registrations,
confirmed token authentication method and any assigned server-side credential;
and a trusted per-account credential broker/storage boundary outside workspace
shells. Only then should an authorized test account complete new and returning
sign-in, consent, account/workspace switching, refresh rotation/restart, disconnect
and external revocation, failed/expired consent and quota recovery, model listing
and one completed inference. Verify that credentials never enter workspace
files/environments or responses, and that revocation fails closed. Do not expose a
hosted sign-in button before these acceptance checks. No remaining ChatGPT quota
API or hosted eligibility is claimed.

References: [registration](https://developers.openai.com/siwc/token-sharing-open-source/sign-in),
[account/session lifecycle](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions),
[public inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference),
[preview limits](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).
