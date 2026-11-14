---
title: Telegram bot for your workspace
summary: Talk to this workspace's agent from Telegram, with voice messages, files, quick-reply buttons, a model switcher and notifications from tasks.
category: automation
tags: [telegram, bot, voice, remote-control]
visibility: public
---

# Telegram bot for your workspace

Set up a private Telegram bot that forwards each message to this workspace's agent and sends the
answer back, so the user can work with the workspace from their phone. Do every step in order and
do not skip a check. Write everything in English unless the user writes in another language.

What it can do when you are done:

- Each Telegram chat is one agent session, kept until `/new`.
- It understands text, voice messages and round video notes (transcribed), photos and files
  (saved to the workspace and handed to the agent), and forwarded messages.
- While the agent works, it shows "typing…" and a one-line status ("🔎 searching", "💻 running a
  command"), then replaces the status with the answer.
- When the agent ends a question with `[[buttons: Yes | No]]`, the line becomes tappable buttons.
- It has a menu with `/new`, `/status`, `/model` (presets, then every model by vendor, paged),
  `/lang` (voice language) and `/help`.
- A `notify "text"` command lets scheduled tasks message the user.
- Only the user's own Telegram account can use it. Everyone else is ignored.

Out of scope: acting as the user's own Telegram account, WhatsApp, Kubernetes and calendars. Do
not add them, even if they would be easy.

## 1. Create the bot with BotFather

Walk the user through this. Wait for them to confirm each step before you go on:

1. In Telegram, open **@BotFather** (the blue check mark matters; impostors exist) and press
   **Start**.
2. Send `/newbot`.
3. Send a display name, for example `My Workspace`.
4. Send a username that ends in `bot`, for example `my_workspace_bot`. If it is taken, try another.
5. BotFather answers with a token like `123456789:AA…`. Tell the user to **keep it private and
   not paste it here**.
6. Optional: `/setuserpic` for an avatar, and `/setdescription` for what the bot shows before
   Start.

## 2. Add the token as a secret

Ask the user to open this workspace's **Settings → Secrets** and add a secret named
**`TELEGRAM_BOT_TOKEN`** with the token as its value. Then they press **Restart**, since a secret
reaches the environment only after a restart, and come back to this session once the workspace
is running again.

When they are back, check it without printing it:

```sh
test -n "$TELEGRAM_BOT_TOKEN" && echo set || echo missing
curl -fsS "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getMe" | jq -r '.result.username'
```

If the variable is missing, or `getMe` fails, say which, and ask the user to fix the secret and
restart again. Never echo the token. Also check `test -n "$OPENROUTER_API_KEY"`: voice
transcription uses it. It is normally already there.

## 3. Find out who may use the bot

Ask the user to open the bot (`https://t.me/<username>` from `getMe`), press **Start**, and send
it any message. Then read their numeric user id **once**, before anything else polls the bot:

```sh
curl -fsS "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getUpdates" \
  | jq '[.result[].message.from | {id, username, first_name}] | unique'
```

Show the names you found, and ask which one is them if there is more than one. Save the id as a
variable, not a secret (it is not private): add `TELEGRAM_ALLOWED_IDS=<id>` and
`NOTIFY_CHAT_ID=<id>` to `~/.config/workspaced/.env`. Several ids are separated by commas. A
variable applies after a restart, so until then the service sources that file itself (see step
6).

## 4. Check what the bot will talk to

The bot talks to the workspace's own OpenCode server over HTTP, the same API the web app uses.
Never use the `opencode` CLI, which starts a second server for every call.

- URL: `http://127.0.0.1:4096`. Every route is under `/api`, with basic auth: the user is
  `opencode`, and the password is `$OPENCODE_SERVER_PASSWORD`, or `$OPENCODE_PASSWORD` when that
  one is empty.
- Check: `curl -fsS -u "opencode:${OPENCODE_SERVER_PASSWORD:-$OPENCODE_PASSWORD}" http://127.0.0.1:4096/api/info`
  should report a `2.x` version. If it does not, stop and tell the user.
- Routes that read the project take `location[directory]=<dir>` as a query parameter. Use
  `~/project`.
- Before you rely on a route, look up its body and answer in the OpenAPI document
  (`curl -fsS https://dev.opencode.ai/v2/openapi.json | jq '.paths["/api/session/{sessionID}/prompt"]'`)
  rather than guessing. The bridge needs these:
  - `POST /api/session`, with `location.directory`, to create a session.
  - `GET /api/session/{id}` to check a session.
  - `POST /api/session/{id}/agent` to set its agent.
  - `POST /api/session/{id}/model` with `{model: {providerID, id}}` to set its model.
  - `POST /api/session/{id}/prompt` with `{text}` to send a message.
  - `GET /api/session/{id}/message?limit=N`, which returns the newest messages first.
  - `POST /api/session/{id}/interrupt` to stop a run.
  - `DELETE /api/session/{id}` to delete a session.
  - `GET /api/event?types=…` for the event stream.
  - `GET /api/model` for the model list.
  - `POST /api/location/reload` to reload instructions and agents.

## 5. Write the bot

Python 3, **standard library only** (`urllib`, `json`, `threading`), so nothing can break on an
upgrade. Put it in `~/telegram-bot/` (the home survives restarts):

- `bridge.py`: the bot.
- `~/.local/bin/notify`: the notification command, executable.
- `~/.config/opencode/agents/telegram.md`: the agent the bot's sessions use.

Keep its state (the update offset, a session per chat, the model per chat, the voice language,
the pending buttons) in `~/.local/state/telegram-bot/state.json`. Write it atomically: a temp
file, then rename.

### Telegram side

- Long-poll `getUpdates` with `timeout=50` and `allowed_updates=["message","edited_message","callback_query"]`.
  Save the offset after each update, so a restart never replays one.
- Retry `429` and `5xx` responses and network errors with growing pauses. Any other error is
  final, and you log its first 300 characters.
- Drop every update whose `from.id` is not in `TELEGRAM_ALLOWED_IDS`. Answer a stranger's button
  press with an empty `answerCallbackQuery`, and never reply to them.
- On start, call `setMyCommands` with `new`, `status`, `model`, `lang` and `help`, and log the
  bot's username.
- Split answers into chunks of 4000 characters, and put buttons only on the last chunk.

### A message comes in

1. **Voice** (`voice`, `video_note`, and `audio` that is not mp3, ogg, wav or flac): download it
   with `getFile`. Transcribe it through OpenRouter's chat completions
   (`$OPENROUTER_BASE_URL`, default `https://openrouter.ai/api/v1`, key `$OPENROUTER_API_KEY`)
   with an audio-capable model, by default `google/gemini-2.5-flash` and overridable with
   `BOT_ASR_MODEL`. Send the audio as an `input_audio` part, base64, in format `ogg`, with the
   instruction: "Transcribe verbatim in the spoken language, keep punctuation, do not translate
   or summarize, output nothing if there is no speech". The `/lang` setting replaces "the spoken
   language" with Russian, Bulgarian, English and so on.
   - If the model refuses the format, or the file is a video note, convert it to 16 kHz mono mp3
     with ffmpeg first. Install ffmpeg with `apk add ffmpeg` and find it on `PATH` or under
     `~/.local/alpine/usr/bin`.
   - Reply with the transcript in italics, using `parse_mode` `HTML` and escaped text, so the user
     sees what was heard. Then treat the transcript as their message.
   - An empty transcript gets "I couldn't hear any speech", and nothing is sent to the agent.
2. **Photos and files** up to 20 MB: save them to `~/telegram-bot/inbox/<date>-<name>`. Use the
   largest photo size. Pass the agent the caption plus `[attached file: <path>]`.
3. **Forwarded messages**: prefix the text with `[forwarded from <name>]`, taken from
   `forward_origin`.
4. **Commands and buttons** (below) are handled by the bridge itself and never reach the agent.
5. Anything else goes to the agent:
   - Send `sendChatAction typing` every 4 seconds until the answer is in.
   - Post a status message, "⏳ thinking…".
   - Make sure the chat's session exists, with agent `telegram` and the chosen model, and create
     it if it does not.
   - Note the newest message id, then `POST …/prompt`.
   - Wait for the answer. Listen to `/api/event` for the session's events, and on each event, or
     every 1.5 seconds, read `…/message?limit=50`. The run has ended once the newest entry is
     `idle` and there is a `user` message newer than the id you noted. The answer is the text of
     the newest `assistant` message after it.
   - While waiting, edit the status line from tool events: `bash` becomes "💻 running a command",
     `webfetch` or `websearch` "🔎 searching the web", file edits "✏️ editing files". Edit it at
     most once every 3 seconds.
   - After 15 minutes, `interrupt` the run and say so.
6. **Deliver** the answer by editing the status message, and send extra messages for further
   chunks. If the agent failed, add a ⚠️ line with the reason instead of staying silent.

### Quick-reply buttons

If the answer contains a line `[[buttons: A | B | C]]`:

- Remove the line, and make up to 4 inline buttons of at most 40 characters each, two per row.
- Each button's `callback_data` is `opt:<chat>:<message>:<index>`. Keep the label in state, and
  only the newest 200 of them.
- Add buttons only when the remaining text contains a `?`.
- A press answers the callback with "→ label", removes the keyboard, and sends the label to the
  agent as the user's next message. A label that is no longer in state gets "This button has
  expired".

### Commands

- `/new`: delete the chat's session in OpenCode, forget it, and answer "Fresh start".
- `/status`: show the session id, the model (and whether it is the default), the working folder,
  the voice language and the bot's uptime.
- `/model`: show an inline keyboard of presets, picked from `GET /api/model` by matching
  substrings: a cheap fast model, a mid model and a strong model, each showing its price per
  million tokens in and out. Under them, "📚 All models (N)", then vendors (largest first), then
  that vendor's models, 8 per page with ◀️ 1/3 ▶️. Mark the current model with ✅, and show "↩️
  Default" when an override is set. Edit the same message as the user moves around, never post a
  new one. Store the choice per chat, and apply it before the next prompt.
- `/lang`: Auto, Russian, Bulgarian, English (one per row, ✅ on the current one), stored per
  chat.
- `/help` and `/start`: one short paragraph on what it accepts, plus the command list.
- Keep `callback_data` under 64 bytes. Use indexes, never model ids.

### Instructions that change

OpenCode reads instructions and agents when the location loads. Before each prompt, compare the
modification times of `~/.config/workspaced/instructions/*.md`, `~/.config/opencode/agents/*.md`
and `~/.config/opencode/opencode.json*` with the previous check, and `POST /api/location/reload`
when they changed. That way an instruction the user edits applies from their next Telegram
message.

### `notify`

`notify "text"` sends a message to `$NOTIFY_CHAT_ID` through the same bot. `notify -` sends what
it reads on standard input, and `notify --check` prints the bot's username. It exits non-zero
with a short reason on failure, and chunks at 4000 characters. Scheduled tasks use it, since
nobody is watching them.

### The `telegram` agent

Write `~/.config/opencode/agents/telegram.md` with front matter
`description: Assistant for chatting through Telegram` and `mode: primary`, and allow
`webfetch` and `websearch`. The body tells the agent:

- **Its role:** it is chatting with the user on their phone, and does what they ask in this
  workspace. It answers in the language of their latest message, voice transcripts included.
- **Facts:** facts about files, the workspace or the system come from a command it ran in this
  turn. It never invents output, and when something fails it says so plainly.
- **Format:** plain text only, with no Markdown headings, bold, tables or code blocks, since
  Telegram shows them as raw symbols. Short "-" lists are fine. It is short by default, and never
  mentions commands, paths or tools unless asked how something works.
- **Buttons:** when a message ends with a real question whose answer is one of 2–4 short, obvious
  options, it adds the last line `[[buttons: A | B]]`. Never for open questions, free text
  (dates, names, numbers) or small talk.
- **Recurring requests:** "remind me", "every morning", "tell me when…" become a `task`. The
  task's prompt must stand alone, since it runs in a fresh session with no memory of the chat. It
  says what to check and how to report, ending with `notify "…"`, and gives the language to
  report in.
- **Destructive actions:** it asks before deleting, overwriting or anything that costs money.

## 6. Run it as a service

Write `~/.config/workspaced/services/telegram-bot.sh`. This starts the bot within a few seconds,
keeps it running, and starts it again with every workspace start:

```sh
set -a; [ -f "$HOME/.config/workspaced/.env" ] && . "$HOME/.config/workspaced/.env"; set +a
export PATH="$HOME/.local/bin:$HOME/.local/alpine/usr/bin:$PATH"
cd "$HOME/project"
exec python3 "$HOME/telegram-bot/bridge.py"
```

Read `~/.local/state/workspaced/services/telegram-bot.log` until it shows the bot's username
with no traceback. Never run a second copy by hand while the service runs: two pollers on one
token make Telegram answer `409 Conflict`.

## 7. Verify it end to end

Ask the user to do each of these, and check the log after each one:

1. Send "hi". An answer arrives, and "typing…" shows while the agent works.
2. Send a short voice message. The transcript appears, then the answer.
3. Send a photo with a caption. The agent mentions the saved file.
4. `/model`: switch to another model, then `/status` shows it.
5. Ask something with two choices ("Should I create a notes folder?"). Buttons appear, and
   pressing one continues.
6. `/new`, then ask "what did I say before?". The bot has no memory of it.
7. Run `notify "Test from the workspace"` yourself. It arrives.

Fix anything that fails before you go on.

## 8. Leave instructions behind

Add `~/.config/workspaced/instructions/telegram-bot.md`, a few lines covering:

- The bot's username and where its code, state, inbox and log are.
- That `TELEGRAM_BOT_TOKEN` is a secret and `TELEGRAM_ALLOWED_IDS` and `NOTIFY_CHAT_ID` are
  variables.
- That tasks report with `notify "…"`.
- How to restart the bot: `touch` the service file.
- How to turn it off: delete the service file.
- What to check when it stops answering: the log, then `getMe`, then a `409` from a second
  poller.

Then tell the user, in two or three sentences, that the bot is live and how to reach it, and
that it works only while the workspace is running. Suggest a first try: "remind me in 10 minutes
to stretch".
