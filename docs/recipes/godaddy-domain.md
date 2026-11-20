---
title: GoDaddy domains for your workspace
summary: Install GoDaddy's official CLI, connect it using a scoped secret, and point an existing domain at this workspace with verified DNS and HTTPS.
category: devops
tags: [godaddy, domains, dns, cli, https]
visibility: public
---

# GoDaddy domains for your workspace

Give OpenCode GoDaddy's official `gddy` CLI so the user can ask it to inspect DNS and point a
name they already own at this workspace. This is an optional Store recipe, not a tutorial step.
Do each stage in order and stop at the first check that fails. Never claim the domain is
connected until public DNS proves it.

This recipe configures DNS for an existing domain. It does not buy or transfer a domain, change
nameservers, or purchase hosting. Ask for the registered zone (for example `example.com`) and
the exact name to connect (`app.example.com`, or the apex `example.com`). Prefer a new subdomain
when the user has no preference, so an existing site or email service is not disrupted. Inspect
what is already running in the workspace; do not expose OpenCode, its terminal or private files.
For an expiring demo, explain that DNS outlives the workspace and must be removed or repointed
before deletion; attaching a domain does not extend the trial or turn it into production hosting.

## 1. Get the right token and store it as a secret

Explain to the user that the token lets this workspace manage DNS in their GoDaddy account.
Walk them through these steps in their own browser:

1. Open [GoDaddy Personal Access Tokens](https://developer.godaddy.com/personal-access-token)
   and sign in to the account that owns the domain.
2. Select **Generate Token**, name it for this workspace, and choose an appropriate expiry.
3. Expand **Domains & DNS** and choose only `domains.domain:read` and `domains.dns:update`.
   Do not select the whole bundle: buying, deleting, transferring domains and changing
   nameservers are not needed. If the labels change, consult the linked authentication guide
   and explain the matching read and DNS-edit permissions rather than guessing.
4. Create the token, copy the one-time value and keep it in a password manager.
5. In this workspace's **Settings → Secrets**, add **`GDDY_PAT`** with that value. It is the
   current CLI's environment variable, not a GoDaddy account password or a legacy API key/secret.
6. Press **Restart** and return to this session once the workspace is ready.

Never ask for a token in chat. Never write it to `.env`, a CLI credential file, a command
literal, a Docker build argument or an instruction. If it has been pasted into chat or otherwise
exposed, ask the user to revoke it and create a replacement. Replacing a workspace secret
requires removing the old value, adding the new value under the same name, and restarting.
The token can be revoked or replaced from the same GoDaddy token page at any time.

Wait for the user to return. Then check presence without showing the value:

```sh
test -n "$GDDY_PAT" && echo 'GDDY_PAT is set' || echo 'GDDY_PAT is missing; add it in Secrets and restart'
```

Stop if it is missing. This recipe uses production DNS (`--env prod`), not GoDaddy's OTE sandbox.

## 2. Install a CLI that works in this workspace

Use the [official installation guide](https://developer.godaddy.com/en/docs/api-users/cli/set-up)
and [official releases](https://github.com/godaddy/cli/releases). Install under `~/.local/bin`
so the CLI survives restarts. Download the release's official installer, inspect it, then run
it; it verifies the binary's checksum. Never run an installer from an unrelated package or site.
Check `gddy --version`, `gddy --help`, `gddy dns --help`, `gddy dns set --help` and
`gddy guide auth` before using the command syntax below.

The harness is Alpine/musl, whereas GoDaddy's published Linux binaries may require glibc. If a
native binary cannot run, do not modify the harness OS or repeatedly install incompatible
binaries. Build a small Debian-based Docker image with the official checksum-verified CLI,
CA certificates and an unprivileged runtime user. Install packages and run the installer inside
that image, not with `apt` in the harness. Keep the Dockerfile in `~/project/godaddy-cli` and put
this launcher in `~/.local/bin/gddy` (use the image tag you actually built):

```sh
#!/usr/bin/env bash
set -euo pipefail
exec docker run --rm --interactive --env GDDY_PAT --env GDDY_NO_SESSION_ID=1 workspaced-gddy "$@"
```

Make it executable and verify its version and help. `--env GDDY_PAT` passes the existing secret
by name; do not interpolate its value into the launcher. Never bake credentials into the image
or mount a Docker socket or the user's whole home into this container. CLI config may be
throwaway: pass `--env prod` on each call, and authenticate from `GDDY_PAT`. Do not run
`gddy auth login`, `gddy pat add` or verbose HTTP debug logging in this headless workspace.

## 3. Check credentials, ownership and authoritative DNS

Use read-only operations first:

```sh
gddy --env prod --json domain list
gddy --env prod --json domain get example.com
gddy --env prod --json dns list example.com
```

Replace `example.com` with the zone the user chose. Show only the relevant domain and DNS
results, not a dump of account contact details. Successful reads prove authentication and
visibility, not permission to write. Check the token's DNS-update scope and preview the intended
mutation; do not make a test write to an unrelated record just to check permissions.

Handle failures without printing the token or full HTTP headers:

- `401`: token is missing, invalid, expired or revoked. Ask the user to replace it and restart.
- `403`: inspect the provider's error code; explain whether the token lacks DNS permissions or
  the account is not eligible for the operation. Do not recommend buying a plan speculatively.
- `404` or no matching domain: check the zone and which GoDaddy account owns it.
- Rate limiting or an upstream outage: wait as instructed by the provider and retry only the
  intended operation, without creating duplicate records.

Resolve the zone's public NS records (install `bind-tools` with the workspace's `apk` wrapper
if `dig` is absent) and compare them with GoDaddy's DNS configuration. A domain registered at
GoDaddy may delegate DNS to Cloudflare or another provider. If it does, stop and explain that
GoDaddy DNS changes will not control it; do not move its nameservers or overwrite its zone.

## 4. Point the selected name at this workspace

Read the actual public IPv4 from `$WORKSPACE_IP` or `firewall list`. Never guess it or use a
private address or a Docker container address. `$WORKSPACE_HOSTNAME` is the platform-provided
CNAME target for custom subdomains; it is not a hostname on which to publish the website itself.

Inspect existing A, AAAA and CNAME records for the exact name. Describe the current and proposed
records to the user. If an existing target or conflicting record would be replaced or deleted,
show the affected records and wait for approval before doing that. Save the non-secret original
DNS records locally for rollback. Preserve all unrelated records, especially MX, SPF, DKIM,
DMARC, TXT verification records and nameservers. A CNAME cannot coexist with other data at the
same name: prefer a different subdomain over deleting unrelated data.

For a subdomain, prefer one CNAME to the workspace hostname. For example, using the actual zone,
relative label (which may contain dots), and platform hostname:

```sh
gddy --env prod --json dns set example.com --type CNAME --name app --data "$WORKSPACE_HOSTNAME" --ttl 600 --dry-run
```

If that hostname is not available, use an A record to the verified public IPv4. At the apex,
use an A record (`--name @`) to the workspace IPv4 rather than an ordinary apex CNAME:

```sh
gddy --env prod --json dns set example.com --type A --name @ --data "$WORKSPACE_IP" --ttl 600 --dry-run
```

Once the specific change is authorized, run the same command without `--dry-run` only if the
record differs. `dns set` replaces the entire type/name record set, not just one address;
`dns add` appends and is not a safe retry of an existing record. Do not replace the whole zone.
Do not add `www` or a wildcard unless the user requested it. Never publish an AAAA record unless
the workspace's public IPv6 and service reachability are verified. If an old AAAA points elsewhere,
explain the conflict and get approval to correct that specific record.

Read the records back with `gddy dns list`, then resolve the selected name against its authoritative
nameserver and a public recursive resolver. The A result must contain this workspace's public IPv4;
any published AAAA must reach this workspace too. Caching can delay propagation: report pending
verification honestly, with the observed result, and retry without repeatedly writing DNS.

## 5. Register the name with the platform and check HTTPS

GoDaddy DNS and the platform's Domains list are different. Ask the user to add the exact name
once in **workspace Settings → Domains** and use its manual-record view for verification.
If it is already listed, reuse it. DNS has already been configured by the CLI; do not ask the user
to approve a second Domain Connect change. Wait for the platform to verify it; `domains` then
lists the name for OpenCode and later sessions. Never edit the platform-managed domains file or
invent a platform API token to register the name on the user's behalf.

If the user has a public website in this workspace, configure its existing reverse proxy for the
selected host, obtain a trusted Let's Encrypt certificate for that name and ensure renewal is
configured. Preserve existing IP certificates and other sites; a new domain must not break them.
For the WordPress recipe, reuse its proxy, ACME challenge and certificate volumes and renewal
service; add a separate certificate for the custom name, not for `$WORKSPACE_HOSTNAME`. Set the
WordPress HTTPS home/site URLs to the custom domain only after the certificate and DNS checks
pass, using WP-CLI and safe URL replacement. Do not disable TLS checks or fall back to HTTP.

Check `https://<selected-name>` using normal certificate verification, the expected page content,
and an HTTP-to-HTTPS redirect. Do not publish administrative tools or unrelated services. If there
is no website yet, report that DNS is connected but no website has been deployed; do not invent
an application or claim a passing HTTPS check.

## 6. Leave reusable instructions for OpenCode

Write `~/.config/workspaced/instructions/godaddy-domains.md`, preserving unrelated instructions.
Include the installed CLI path/version or wrapper image, the actual zone and relative label,
self-targeting hostname/IP, verified DNS state, website URL and renewal setup. Refer to the secret
only as `GDDY_PAT` and explain replacement, restart and revocation; never include its value.

Include tested examples to list records, inspect one name, preview and apply a request to point
it at this workspace, verify DNS and HTTPS, and restore the exact previously saved record set.
For later sessions, read the workspace's current address again rather than relying on a stale IP.
State that a user's request to point a fresh name here authorizes that record, while replacing
an existing site's target needs an explanation and specific approval. Unrelated records and
nameservers are left alone. Record the platform's one-time Settings → Domains registration step
for each new name; it is not performed by `gddy`.

Finish by reporting what was verified, where the instructions are, and examples the user can ask
OpenCode: "Show the DNS for my domain", "Point app.example.com at this workspace", or
"Check the domain's HTTPS certificate". Do not create a tutorial step, buy a domain, change
registrant details, or enable an automatic DNS updater.

References:

- [GoDaddy CLI setup](https://developer.godaddy.com/en/docs/api-users/cli/set-up)
- [Token creation](https://developer.godaddy.com/en/docs/api-users/auth/how-to)
- [Token scopes](https://developer.godaddy.com/en/docs/api-users/auth)
- [DNS CLI reference](https://developer.godaddy.com/en/docs/api-users/cli/reference)
