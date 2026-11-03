# API

The endpoints are placeholders: a scripted agent stands behind them, so clients can be built against the real protocol
before a real agent exists. Swagger UI is at `/swagger-ui`, the OpenAPI 3.0 document at `/v3/api-docs`.

| Requirement                         | Protocol           | Endpoint                                                  |
|-------------------------------------|--------------------|-----------------------------------------------------------|
| Watch agent output                  | SSE                | `GET /api/sessions/{id}/events`                           |
| Stream token-by-token               | SSE                | `token` events on the same stream                         |
| See shell commands and output       | SSE                | `command`, `output`, `exit` events                        |
| Answer agent questions              | SSE + POST actions | `question` event, then `POST .../questions/{qid}/answer`  |
| Approve/reject commands             | SSE + POST actions | `approval` event, then `POST .../commands/{cid}/decision` |
| Type directly into a running terminal | WebSocket + PTY  | `WS /api/sessions/{id}/terminal`                          |
| Full interactive terminal like SSH  | WebSocket + PTY    | same socket                                               |

## Sessions

```sh
curl -X POST localhost:8080/api/sessions               # 201 {"id": "..."}
curl -N localhost:8080/api/sessions/$ID/events         # opening the stream starts the run
curl -X POST localhost:8080/api/sessions/$ID/questions/$QID/answer \
     -H 'Content-Type: application/json' -d '{"answer":"main"}'                  # 202
curl -X POST localhost:8080/api/sessions/$ID/commands/$CID/decision \
     -H 'Content-Type: application/json' -d '{"decision":"approve"}'             # 202, or "reject"
```

One client may watch a session; a second `GET .../events` answers `409`. Answering a prompt nobody is waiting on
answers `404`. An unanswered prompt ends the run after `workspaced.demo.prompt-timeout` (10 minutes by default).

## Event types

Every event carries a JSON object in `data`.

| Event      | Data                                    | Meaning                                       |
|------------|-----------------------------------------|-----------------------------------------------|
| `status`   | `state`: `running` \| `completed`       | Run lifecycle; the stream closes after `completed` |
| `token`    | `text`                                  | One streamed fragment of agent text           |
| `message`  | `text`                                  | The full text the preceding tokens made up    |
| `command`  | `id`, `command`                         | A shell command started                       |
| `output`   | `commandId`, `stream`, `text`           | One line of its output                        |
| `exit`     | `commandId`, `code`                     | It finished                                   |
| `question` | `id`, `text`, `options`                 | The agent is blocked on an answer             |
| `answer`   | `questionId`, `answer`                  | The answer it received                        |
| `approval` | `id`, `command`                         | The agent is blocked on approving a command   |
| `decision` | `commandId`, `decision`                 | The decision it received                      |

## Terminal

The socket behaves like a PTY on the wire: send raw keystrokes as text frames, render what comes back verbatim
(xterm.js or similar). The server does the echo, so the client must not. Nothing is executed yet; Enter answers each
line with a fixed reply.

## Running locally

```sh
cd apps/api
./mvnw spring-boot:run                        # http://localhost:8080
./mvnw fmt:format                             # fixes what the fmt:check gate rejects
./mvnw verify                                 # tests plus the 80% line-coverage floor
./mvnw -DskipTests package && docker build .  # the image packages the built jar
```
