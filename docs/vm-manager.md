# VM manager

The service that creates, watches and deletes the Hetzner VMs workspaces run on. Like the API, the whole app is served
under the `/api/v1` context path on port 8080: Swagger UI is at `/api/v1/swagger-ui`, the OpenAPI 3.0 document at
`/api/v1/v3/api-docs`, health at `/api/v1/actuator/health`.

It is internal: the cluster runs it behind a ClusterIP Service, `workspaced-ai-vm-manager:8080`, with no IngressRoute,
and only the workspace API calls it. Every error body is an RFC 9457 problem detail (`application/problem+json`).

## VMs

A VM has an `id` (UUID, assigned by the server), the `ownerRef` it belongs to (a workspace id in practice), its
`serverType`, a `status` (`PROVISIONING`, `READY`, `RESTARTING`), its public `ipv4` once it has one, and `createdAt` /
`lastSeenAt`. VMs are still kept in memory, and none is created at Hetzner yet.

| Method   | Path                          | Body                                         | Answers                                         |
|----------|-------------------------------|----------------------------------------------|-------------------------------------------------|
| `GET`    | `/api/v1/vms`                 |                                              | `200` and every VM; `?ownerRef=` for one owner's |
| `GET`    | `/api/v1/vms/events`          |                                              | `200` and a server-sent event stream            |
| `GET`    | `/api/v1/vms/{id}`            |                                              | `200` and the VM, or `404`                      |
| `POST`   | `/api/v1/vms`                 | `{"ownerRef": "…", "serverType": "cx23"}`    | `201`, the VM, and its URL in `Location`; `200` and the owner's VM if it has one |
| `DELETE` | `/api/v1/vms/{id}`            |                                              | `204`, or `404`                                 |
| `POST`   | `/api/v1/vms/{id}/restart`    |                                              | `202` and the VM, or `404`                      |
| `POST`   | `/api/v1/vms/{id}/ready`      |                                              | `200` and the VM, or `404`                      |
| `POST`   | `/api/v1/vms/{id}/heartbeat`  |                                              | `204`, or `404`                                 |

`serverType` is optional and defaults to the first one offered. A blank `ownerRef`, or a `serverType` that is not
offered, answers `400`.

## VM options

`GET /api/v1/vm-options` lists the sizes a VM can be created as, default first, with their cores, memory, disk and
monthly price as Hetzner currently sells them in the configured location. Which sizes exist is configuration
(`WORKSPACED_VM_SERVER_TYPES`, `cx23,cx33` by default); specs and prices are never restated, they come from Hetzner and
are cached for ten minutes. A size Hetzner deprecates or stops selling in the location drops out of the list. Adding
CX43 is `WORKSPACED_VM_SERVER_TYPES=cx23,cx33,cx43`. The sizes themselves are chosen in
[hetzner-vm-runtime.md](hetzner-vm-runtime.md#vm-sizes).

Without a Hetzner token it answers `503`; when Hetzner fails or answers unexpectedly, `502`.

## Hetzner access

VMs live in their own Hetzner Cloud project, so its API token can reach nothing else. The token is a Read & Write one,
kept in Vault at `secret/workspaced-vm-manager-secrets` under `hcloud-token`, and reaches the VM manager as
`WORKSPACED_HETZNER_TOKEN` through its own ExternalSecret, so the API pod never holds it. VMs are created in `nbg1`
unless `WORKSPACED_HETZNER_LOCATION` says otherwise.

## Running locally

It needs no database. Without a token it still starts, and only what needs Hetzner answers `503`. It listens on 8080
like the API, so run one of them on another port (`SERVER_PORT=8081`) to have both up.

```sh
cd apps/vm-manager
WORKSPACED_HETZNER_TOKEN=$(vault kv get -field=hcloud-token secret/workspaced-vm-manager-secrets) \
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
