# API

The whole app is served under the `/api/v1` context path, so the host root is free for the frontend: Swagger UI is at
`/api/v1/swagger-ui`, the OpenAPI 3.0 document at `/api/v1/v3/api-docs`, health at `/api/v1/actuator/health`.

## Workspaces

A workspace has an `id` (UUID, assigned by the server) and a `name` (1–100 characters, surrounding whitespace
trimmed). Workspaces are listed in the order they were created.

| Method   | Path                      | Body               | Answers                                         |
|----------|---------------------------|--------------------|-------------------------------------------------|
| `GET`    | `/api/v1/workspaces`      |                    | `200` and every workspace                       |
| `POST`   | `/api/v1/workspaces`      | `{"name": "Docs"}` | `201`, the workspace, and its URL in `Location` |
| `PUT`    | `/api/v1/workspaces/{id}` | `{"name": "Docs"}` | `200` and the renamed workspace, or `404`       |
| `DELETE` | `/api/v1/workspaces/{id}` |                    | `204`, or `404`                                 |

A blank or overlong name, or an `id` that is not a UUID, answers `400`. Every error body is an RFC 9457 problem detail (`application/problem+json`).

```sh
curl -X POST localhost:8080/api/v1/workspaces -H 'Content-Type: application/json' -d '{"name":"Docs"}'
curl localhost:8080/api/v1/workspaces
curl -X PUT localhost:8080/api/v1/workspaces/$ID -H 'Content-Type: application/json' -d '{"name":"Notes"}'
curl -X DELETE localhost:8080/api/v1/workspaces/$ID
```

The schema is created by Flyway from `apps/api/src/main/resources/db/migration` when the API starts.

## Running locally

The API needs PostgreSQL; `WORKSPACED_DB_HOST`, `_PORT`, `_NAME` and `_USERNAME` default to `localhost`, `5432`,
`workspaced` and `workspaced`, and `WORKSPACED_DB_PASSWORD` has no default. The tests start their own database in
Docker, so `verify` needs a running Docker daemon.

```sh
docker run -d --name workspaced-db -p 5432:5432 \
  -e POSTGRES_DB=workspaced -e POSTGRES_USER=workspaced -e POSTGRES_PASSWORD=workspaced postgres:18-alpine
cd apps/api
WORKSPACED_DB_PASSWORD=workspaced mvn spring-boot:run    # http://localhost:8080/api/v1/swagger-ui
mvn fmt:format                                            # fixes what the fmt:check gate rejects
mvn verify                                                # tests plus the 80% line-coverage floor
mvn -DskipTests spring-boot:build-image                   # buildpacks image, no Dockerfile

cd apps/web
npm run dev                                               # proxies /api to localhost:8080
npm test                                                  # vitest plus the 80% line-coverage floor
```
