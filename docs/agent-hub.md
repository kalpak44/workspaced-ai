# Agent hub

The one service workspace VMs talk to. Each VM's supervisor enrolls an Ed25519 public key here once, then signs every
request after it; the agent hub checks the signature and passes what the VM reports on to the VM manager. Like the
other services, the whole app is served under the `/api/v1` context path on port 8080, health at
`/api/v1/actuator/health` (with `/liveness` and `/readiness` probes). It has no Swagger UI. Every error body is an
RFC 9457 problem detail (`application/problem+json`).

Only `/api/v1/agent/*` is routed from the internet, at `https://agent.workspaced.online`. The `/api/v1/internal/*`
routes are for the VM manager inside the cluster, and the app answers `404` to any of them that carries
`X-Forwarded-For`, `X-Forwarded-Host` or `X-Real-Ip`: Traefik adds those to everything it forwards, and an in-cluster
caller never sends them, so a route widened by mistake still cannot reach them.

## Routes

From the VMs:

| Method | Path                       | Body                                            | Answers                                   |
|--------|----------------------------|-------------------------------------------------|-------------------------------------------|
| `POST` | `/api/v1/agent/enroll`     | `{"vmId": "…", "token": "…", "publicKey": "…"}` | `204`, `400`, `401`, or `409`             |
| `POST` | `/api/v1/agent/ready`      | none, signed                                    | `204`, `401`, `404`, or `502`             |
| `POST` | `/api/v1/agent/heartbeat`  | none, signed                                    | `200` and `{"status"}`, `401`, `404`, or `502` |
| `POST` | `/api/v1/agent/harness`    | none, signed                                    | `200` and `{"image", "environment"}`, `401`, `404`, `502`, or `503` |

From the VM manager:

| Method   | Path                              | Body               | Answers                                                    |
|----------|-----------------------------------|--------------------|------------------------------------------------------------|
| `POST`   | `/api/v1/internal/enrollments`    | `{"vmId": "…"}`    | `201` and `{"vmId", "token", "expiresAt"}`, or `409`        |
| `DELETE` | `/api/v1/internal/agents/{vmId}`  |                    | `204`, whether or not there was anything to forget         |

`ready`, `heartbeat` and `harness` record when the VM was last seen and call the VM manager's `POST /vms/{id}/ready`,
`/heartbeat` or `GET /vms/{id}/harness`. A heartbeat answers the VM's status there (`READY`, `RESTARTING`,
`PROVISIONING`), which is how the supervisor learns to recreate its harness. `harness` passes the VM manager's body
through unread and unlogged, since it holds the VM's secret values, with `Cache-Control: no-store`. The VM manager
answering `404` (the VM was deleted) answers `404`, `503` (no harness image or no Vault) answers `503`; anything else
it fails with, or not answering within 10 s, answers `502`.

## Enrollment

The VM manager asks for a token before it creates a server and puts it in that server's user data. A token is
base64url without padding of 32 random bytes, usable for 30 minutes, and only its SHA-256 is stored, so a read of
the table cannot enroll anyone. Issuing again for the same VM replaces the earlier token; a VM already enrolled gets
`409`.

Enrolling sends the token with the raw 32-byte public key, base64 (standard, padded). A key that is not one answers
`400`; a VM already enrolled `409`; a token that is unknown, expired, already used or another VM's `401`, with one
message for all of them. The token is compared in constant time, and deleting it and storing the key commit in one
transaction, with the token row locked, so two enrolls racing on one token cannot both succeed. Deleting a VM revokes
its agent and any token still pending for it.

## Signed requests

Every request after enrollment carries `X-Agent-Vm` (the VM id), `X-Agent-Timestamp` (unix seconds) and
`X-Agent-Signature`, base64 (standard) of the Ed25519 signature of:

```
<METHOD>\n<PATH>\n<TIMESTAMP>\n<lowercase hex SHA-256 of the raw body, of the empty string when there is none>
```

`<PATH>` is the path as sent, `/api/v1` included and the query string excluded, e.g. `/api/v1/agent/heartbeat`. A
missing or malformed header, a VM with no enrolled key, a signature that does not verify, or a timestamp more than
60 s from the agent hub's clock either way answers `401`; the reason is logged, not answered. Verification uses the
JDK's own Ed25519, with no extra library.

## Database

It shares the API's PostgreSQL database and credentials, and owns no migrations: its two tables, `enrollment_token`
and `agent`, are created by the API's `V15__agent_hub.sql`. **Deploy the API first**, or the agent hub starts against
a database without them and every route fails. The tests create the same tables from `src/test/resources/schema.sql`,
which must stay identical to that migration.

## Configuration

| Variable                       | Default                                          | What                                        |
|--------------------------------|--------------------------------------------------|---------------------------------------------|
| `WORKSPACED_DB_HOST`           | `localhost`                                      | the API's database, same names the API uses |
| `WORKSPACED_DB_PORT`           | `5432`                                           |                                             |
| `WORKSPACED_DB_NAME`           | `workspaced`                                     |                                             |
| `WORKSPACED_DB_USERNAME`       | `workspaced`                                     |                                             |
| `WORKSPACED_DB_PASSWORD`       | none                                             | required, so no fallback reaches production |
| `WORKSPACED_VM_MANAGER_URL`    | `http://workspaced-ai-vm-manager:8080/api/v1`    | where reports are passed on                 |
| `WORKSPACED_ENROLLMENT_TTL`    | `30m`                                            | how long a token stays usable               |
| `WORKSPACED_SIGNATURE_SKEW`    | `60s`                                            | how far a timestamp may be from this clock  |

## Code layout

- `agent` — the domain: `EnrollmentService` (issue, enroll, revoke), `AgentService` (check a signed request, pass the
  report on), `AgentStore` and its `JdbcAgentStore` over `JdbcClient`, and `Ed25519Keys`. Services take a `Clock` and
  throw domain exceptions; none of them knows HTTP.
- `agent.web` — the controllers, `ApiExceptionHandler` turning the domain exceptions into problem details, and
  `InternalRoutesFilter`.
- `vmmanager` — `VmManagerClient`, the VM manager as this app uses it, and `HttpVmManagerClient` over an injected
  `HttpClient`.

Services are unit-tested against stand-ins (an in-memory store, a hand-written `VmManagerClient`, a hand-moved clock); the
HTTP client against a local fake VM manager; the routes end to end on a random port against a Testcontainers
PostgreSQL, with real Ed25519 keys generated by the JDK.

## Running locally

It needs the API's database with the API's migrations applied, and listens on 8080 like the API and the VM manager,
so give it another port to run beside them.

```sh
cd apps/agent-hub
SERVER_PORT=8082 WORKSPACED_DB_PASSWORD=… WORKSPACED_VM_MANAGER_URL=http://localhost:8081/api/v1 \
  mvn spring-boot:run
mvn fmt:format                                            # fixes what the fmt:check gate rejects
mvn verify                                                # tests (Docker needed) plus the 80% line floor
mvn -DskipTests spring-boot:build-image                   # buildpacks image, no Dockerfile
```
