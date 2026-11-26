# VM manager

The service that creates, watches and deletes the Hetzner VMs workspaces run on. Like the API, the whole app is served
under the `/api/v1` context path on port 8080: Swagger UI is at `/api/v1/swagger-ui`, the OpenAPI 3.0 document at
`/api/v1/v3/api-docs`, health at `/api/v1/actuator/health`.

It is internal: the cluster runs it behind a ClusterIP Service, `workspaced-ai-vm-manager:8080`, with no IngressRoute,
and only the workspace API calls it. Every error body is an RFC 9457 problem detail (`application/problem+json`).

## VMs

A VM is one Hetzner Cloud server running one workspace. It has an `id` (UUID, assigned by the server), the
`workspaceId` it runs and the `userId` who owns that workspace, its `serverType`, the Hetzner `serverId`, a `status`
(`PROVISIONING`, `READY`, `RESTARTING`, `STOPPED`), its public `ipv4`, and `createdAt` / `lastSeenAt`.

| Method   | Path                          | Body                                                     | Answers                                         |
|----------|-------------------------------|----------------------------------------------------------|-------------------------------------------------|
| `GET`    | `/api/v1/vms`                 |                                                          | `200` and every VM; `?workspaceId=` for one     |
| `GET`    | `/api/v1/vms/events`          |                                                          | `200` and a server-sent event stream            |
| `GET`    | `/api/v1/vms/{id}`            |                                                          | `200` and the VM, or `404`                      |
| `POST`   | `/api/v1/vms`                 | `{"workspaceId": "…", "userId": "…", "serverType": "cx23", "openRouterApiKey": "…", "environment": {…}}` | `201`, the VM, and its URL in `Location`; `200` and the workspace's VM if it has one |
| `DELETE` | `/api/v1/vms/{id}`            |                                                          | `204`, or `404`                                 |
| `POST`   | `/api/v1/vms/{id}/restart`    |                                                          | `202` and the VM, or `404`                      |
| `POST`   | `/api/v1/vms/{id}/stop`       |                                                          | `202` and the VM, `404`, or `502`               |
| `POST`   | `/api/v1/vms/{id}/start`      |                                                          | `202` and the VM, `404`, or `502`               |
| `POST`   | `/api/v1/vms/{id}/ready`      |                                                          | `200` and the VM, or `404`                      |
| `POST`   | `/api/v1/vms/{id}/heartbeat`  | optional `{"memory": {"totalBytes": …, "availableBytes": …}}` | `200` and the VM, `400`, or `404`               |
| `GET`    | `/api/v1/vms/{id}/harness`    |                                                          | `200` and the harness, `404`, or `503`          |
| `GET`    | `/api/v1/vms/{id}/metrics`    |                                                          | `200` and the metrics, `400`, `404`, or `502`   |

Creating a VM stores `openRouterApiKey` in Vault as its system secret and `environment` (optional) as its harness
environment, then creates its server at Hetzner
(`ubuntu-24.04` in the configured location) and answers once Hetzner has accepted it; it is `PROVISIONING` until it
reports ready. `serverType` is optional and defaults to the first one offered. A missing or malformed `workspaceId` or
`userId`, a missing key, an `environment` name that is not a variable name, or a `serverType` that is not offered,
answers `400`; Hetzner or Vault refusing answers `502`
and keeps nothing. Deleting a VM deletes its server, disk included, then its secrets; a server already gone counts as
deleted, and a VM whose server or secrets could not be deleted is kept so the delete can be retried.

### Firewall

A VM filters inbound traffic itself when its snapshot is labelled `firewall=host`. Its image loads an nftables table
that drops everything before the network comes up; the supervisor writes the rules each heartbeat carries into
`/var/lib/workspaced/firewall.d/rules.nft`, and a path unit reloads the table, all of it or none. The rules are kept in
Vault at `secret/workspaced-ai/vms/<vm id>/firewall`. Such VMs sit behind a shared Cloud Firewall, labelled
`role=shared`, that lets every inbound packet through and closes outbound SMTP; a new one is made once each holds 100
servers, so Hetzner's 50 firewalls per account no longer cap the VMs. A null `firewall` in the heartbeat, which the VM
manager answers when Vault cannot be read, leaves the VM's rules as they are; an empty list closes it.

A VM from an older snapshot has a Cloud Firewall of its own instead, `vm-<vm id>`, labelled with its VM and workspace
ids, made with no rules and named in the server's create request. Either way nothing reaches a VM until a rule lets
it in, not even while it boots. SSH is masked in the image, so the only ports a rule can open are ones the workspace
publishes.

| Method | Path                        | Body                                                                | Answers                              |
|--------|-----------------------------|---------------------------------------------------------------------|--------------------------------------|
| `GET`  | `/api/v1/vms/{id}/firewall` |                                                                     | `200` and the rules, or `404`        |
| `PUT`  | `/api/v1/vms/{id}/firewall` | `{"rules": [{"protocol": "tcp", "port": "8080", "sourceIps": []}]}` | `200`, `400`, or `404`               |

A `PUT` replaces every rule, as Hetzner does; an empty list closes the VM again. A rule is `tcp` or `udp` with a port
or range (`8000-8100`), or `icmp` with none; no `sourceIps` lets in anyone over IPv4 and IPv6, and a bare address
means that one address. A source with host bits set (`10.0.0.1/8`) is refused rather than rounded, and a VM holds at
most 50 rules of at most 20 sources each.

Hetzner refuses to delete a firewall while it still applies to a server, which lasts a while after the server is
deleted, so the VM's delete leaves it and a sweep every ten minutes deletes each labelled firewall that applies to no
server and whose VM is gone. A shared firewall has no VM id and is never swept.

### Enrollment

Before it asks Hetzner for the server, the VM manager asks the [agent hub](agent-hub.md) for a one-time enrollment
token and hands the VM a cloud-config writing `/etc/workspaced/agent.env` (root-only): the VM id, the agent hub's
public address (`WORKSPACED_AGENT_HUB_PUBLIC_URL`) and the token. The supervisor enrolls with it on first boot and
reports ready and heartbeats through the agent hub, which relays them to `/vms/{id}/ready` and `/heartbeat` here; a
heartbeat answers the VM, so its supervisor learns it is `RESTARTING` and recreates the harness.

Stop shuts the server down through Hetzner's ACPI shutdown, keeping its disk, secrets and agent, and marks the VM
`STOPPED`; Hetzner still bills a server that is off. Start powers it back on and marks it `PROVISIONING` until its
supervisor reports ready again. Both do nothing to a VM already in that state, and a stopped VM ignores a restart or a
late ready. The workspace API stops a workspace's VM when the owner's balance runs out and starts it after a top-up.
A restarted VM manager reads a server that is off back as `STOPPED`.

### Metrics

`/vms/{id}/metrics?range=` (`live`, `1h`, `24h`, `7d`, `30d`) answers Hetzner's CPU, disk and network series for the
server, CPU as a percentage of all its cores, and the memory reading the VM's last heartbeat carried. Hetzner has no
memory series, so memory is a current value only, kept in memory and answered as `null` once two heartbeats are
missed. Hetzner allows 3600 requests an hour per project, so its answer is reused per VM and range for a while (30 s
for `live`, longer for wider ranges) rather than fetched per viewer.
Deleting a VM revokes its agent. Without `WORKSPACED_AGENT_HUB_URL` a VM boots with no agent identity, which is only
useful locally.

With `WORKSPACED_HETZNER_IMAGE_SELECTOR` (`image=workspace-vm` in the cluster), every VM is created from the newest
snapshot carrying that label, the image with Docker and the supervisor installed; no such snapshot fails the create
rather than falling back to a plain image.

### Labels

Every server is labelled `managed-by=workspaced-ai-vm-manager`, `vm-id`, `workspace-id` and `user-id`, so any server can
be traced to its workspace and its owner from the Hetzner console (`label_selector=workspace-id=<uuid>`), and the VM
manager can rebuild its inventory from them.

### Restarts

Hetzner is the record of which VMs exist. At startup the VM manager reads every labelled server back into its
inventory, so a restart forgets only statuses and heartbeats, which the VMs report again, never a server that is still
billing. Until that read has succeeded, creating a VM answers `503`, because a retried create could otherwise make a
second server for a workspace that has one; a failed read is retried every minute.

## VM secrets

Each VM keeps its secrets in Vault, one KV v2 entry per VM at `secret/workspaced-ai/vms/<vm id>` holding one key per
secret. `OPENROUTER_API_KEY` is the system secret: set when the VM is created, and never added, replaced or removed
through these routes. A user may add up to 50 more. The harness environment the API hands over at creation
(`OPENROUTER_BASE_URL`, `OPENCODE_PASSWORD`, …) lives in a second entry, `<vm id>/system`, which these routes
never list, count or change; deleting the VM deletes both.

| Method   | Path                              | Body                                       | Answers                              |
|----------|-----------------------------------|--------------------------------------------|--------------------------------------|
| `GET`    | `/api/v1/vms/{id}/secrets`        |                                            | `200` and the names, or `404`        |
| `POST`   | `/api/v1/vms/{id}/secrets`        | `{"name": "GITHUB_TOKEN", "value": "…"}`   | `201`, `400`, `404`, or `409`        |
| `DELETE` | `/api/v1/vms/{id}/secrets/{name}` |                                            | `204`, `404`, or `409`               |

Names are listed system secret first, then by name, and values never come back. `409` means the VM already has that
secret, it is the system secret, its name is in the harness environment or starts with `OPENCODE_` (it would be
shadowed), or the VM already holds 50; adding never replaces. A change is a check-and-set write
of the whole entry, so two concurrent adds cannot both pass the limit, and the entry keeps one version, so a removed
value is not left in its history. Without `WORKSPACED_VAULT_URL` these routes and creating a VM answer `503`.

`PUT /api/v1/vms/{id}/environment/{name}` with `{"value": "…"}` sets one variable of the harness environment, and
`DELETE` on the same path removes it; both answer `204`. The API uses it for the workspace's `USER_TIMEZONE`. The value
wins over a secret of the same name, and the harness sees it from its next start. The key and `OPENCODE_` names are
refused with `409`, since they are set only when the VM is created.

### Harness

`GET /api/v1/vms/{id}/harness` answers `{"image": "…", "environment": {…}}`: the image from `WORKSPACED_HARNESS_IMAGE`
and the VM's secrets, its key and its harness environment merged, the harness environment winning a clash. It is the
only route that returns values, and only the agent hub calls it, for a VM that signed its request. A VM with no harness
environment, one created before it existed, is given an `OPENCODE_PASSWORD` on its first call, written with
check-and-set so two concurrent calls agree on one. No image configured answers `503`.

### Vault access

The VM manager holds no Vault credential. It signs in through Vault's Kubernetes auth as the role
`workspaced-ai-vm-manager`, bound to its own ServiceAccount, with a short-lived token projected into the pod, and
renews the sign-in before its one-hour lease runs out. The role's policy reaches only `secret/data/workspaced-ai/vms/*`
and its metadata, so it can read neither its own Hetzner token nor any other secret. Locally,
`WORKSPACED_VAULT_TOKEN` takes a token directly.

## VM options

`GET /api/v1/vm-options` lists the sizes a VM can be created as, cheapest first, with their cores, memory, disk,
whole monthly cost and whether Hetzner has capacity for them in the configured location right now. The recommended low-cost
sizes are configuration (`WORKSPACED_VM_SERVER_TYPES`, `cx23,cx33,cx43` by default, the first the default); any other x86 size
with at least the default's memory, up to `WORKSPACED_VM_MAX_MONTHLY_GROSS` (60 by default, 0 for none) is offered beside them, so there is something to
choose while they are sold out. Specs, prices and capacity are never restated: they come from Hetzner and are cached for
a minute. A size Hetzner deprecates or does not sell in the location drops out; one it has sold out stays, with
`available: false`, and a create naming it answers `409`, as does a create Hetzner refuses with `412` because it sold
out in between. The cost is the server plus the public IPv4 address every VM gets, which Hetzner bills apart (from
`/pricing`), with `costs` breaking it down; firewalls and IPv6 are free, and only traffic past the included 20 TB is left
out, having no fixed price. The cap applies to that whole cost. The sizes themselves are chosen in
[hetzner-vm-runtime.md](hetzner-vm-runtime.md#vm-sizes).

Without a Hetzner token it answers `503`; when Hetzner fails or answers unexpectedly, `502`.

## Hetzner access

VMs live in their own Hetzner Cloud project, so its API token can reach nothing else. The token is a Read & Write one,
kept in Vault at `secret/workspaced-vm-manager-secrets` under `hcloud-token`, and reaches the VM manager as
`WORKSPACED_HETZNER_TOKEN` through its own ExternalSecret, so the API pod never holds it. VMs are created in `nbg1`
(Nuremberg, Germany) unless `WORKSPACED_HETZNER_LOCATION` says otherwise. A size sold out there is offered, and
created, in the first of `WORKSPACED_HETZNER_FALLBACK_LOCATIONS` (`fsn1,hel1` by default, the other EU locations) that has it, so the API's plans keep
their server rather than going unavailable; each option names its location. VMs are created from `WORKSPACED_HETZNER_IMAGE`
(`ubuntu-24.04`). `WORKSPACED_HETZNER_SSH_KEYS` names project SSH keys to put on every VM. Left empty, as it is,
every VM gets `workspaced-ai-no-login`, a key the VM manager uploads once and whose private half was never kept: a
server created with no key gets a root password Hetzner emails to the account owner. Do not configure a key whose
private half is stored anywhere; SSH is off on the image, so no key is ever needed to reach a VM.

## Compute providers

Every VM is a Hetzner server, but the contract already names the cloud: a create takes an optional `provider`
(`hetzner` when omitted, `400` for one not offered), and every VM and VM option answers its `provider`. The ids live in
`ComputeProvider` here and in the API, and must match, since they are what the two services exchange.

Adding a cloud (e.g. AWS EC2) means:

- an entry in both `ComputeProvider` enums;
- its own create, stop, start, delete, firewall, metrics and inventory code, chosen by provider in `VmController`
  where today `VmService` calls `HetznerClient` directly;
- its options in `/vm-options`, each carrying its `provider`.

What must stay true:

- **A workspace's provider is fixed at creation.** The API stores it in `workspace.compute_provider` and never updates
  it; a VM does not move between clouds.
- **Users never see providers.** The API builds `/vm-sizes` field by field, so `provider` is dropped there, and the
  workspace the web app reads does not carry it.
- **Admins decide what can be created and at what price, in the API.** Not built yet: an admin-managed list of allowed
  provider and size pairs with their prices, which `/vm-sizes` filters and prices before anything reaches the web app.

## Code layout

- `hetzner` — `HetznerClient`, the Hetzner Cloud API as this app uses it, and `HttpHetznerClient`, its HTTP
  implementation over an injected `HttpClient`.
- `vault` — `VaultSecretStore`, the `SecretStore` the domain keeps secrets in, over Vault's HTTP API.
- `vm` — the domain: `Vm` and `VmOption`, `VmRepository` (in memory, rebuilt from Hetzner by `HetznerVmInventory`),
  and `VmService` / `VmOptionService` / `VmSecretService`.
  Services take a `Clock`, throw domain exceptions, and publish each change as a `VmEvent`; none of them knows HTTP.
- `vm.web` — controllers that only map between DTOs (`vm.web.dto`) and the services, `ApiExceptionHandler` turning the
  domain exceptions into problem details, and `VmEventStream`, which sends each `VmEvent` to every open stream through
  its own `SseOutbox` so a client that stops reading holds up no one else.

Services are unit-tested against stand-ins (an in-memory repository, a lambda `HetznerClient`, a hand-moved clock);
the HTTP client against a local fake Hetzner; the routes end to end on a random port.

## Running locally

It needs no database. Without a token it still starts, and only what needs Hetzner answers `503`. It listens on 8080
like the API, so run one of them on another port (`SERVER_PORT=8081`) to have both up.

```sh
cd apps/vm-manager
WORKSPACED_HETZNER_TOKEN=$(vault kv get -field=hcloud-token secret/workspaced-vm-manager-secrets) \
WORKSPACED_VAULT_URL=$VAULT_ADDR WORKSPACED_VAULT_TOKEN=$(cat ~/.vault-token) \
  mvn spring-boot:run                                     # http://localhost:8080/api/v1/swagger-ui
mvn fmt:format                                            # fixes what the fmt:check gate rejects
mvn verify                                                # tests plus the 80% line-coverage floor
mvn -DskipTests spring-boot:build-image                   # buildpacks image, no Dockerfile
```

The cluster's copy has no IngressRoute, so reach it through a port-forward:

```sh
kubectl --context k3s -n workspaced-ai port-forward svc/workspaced-ai-vm-manager 8080
open http://localhost:8080/api/v1/swagger-ui
```
