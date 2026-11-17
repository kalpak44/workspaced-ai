---
title: Your Telegram account for the agent
summary: Let the agent read, search and send messages, files and voice notes as you, from your own Telegram account, always asking before it sends anything.
category: automation
tags: [telegram, messaging, voice, cli]
visibility: public
---

# Your Telegram account for the agent

Give the agent a `tg` command that uses the user's **own Telegram account**, not a bot, so it can
answer "what's unread?", "what did Maria say about Friday?" or "tell Ivan I'm running late" and
send it from the user's name. Do the steps in order, and do not start one before the one above
it has passed. Write to the user in their language.

It talks to Telegram the way the desktop apps do (MTProto, through the
[Telethon](https://docs.telethon.dev) library), signed in as a linked device of the user's account.

What it can do when you are done:

- **Read**: list chats, show what is unread, read a chat, search messages. Voice notes, video notes
  and audio arrive transcribed. Reading never marks anything as read.
- **Write**: text, files and photos, voice notes spoken from text, forwards, and marking a chat
  read. The agent **always shows the user the target chat and the exact text and waits for their
  OK** before it sends, unless they say otherwise for a specific chat.

Before you start, tell the user plainly, and go on only once they agree:

- Whoever holds the session file this sets up can read and send everything their account can.
  It lives only in this workspace, in `~/.config/telegram/`, and they can end it at any time in
  Telegram under **Settings → Devices**.
- Telegram's terms forbid spam and bulk messaging through the API, and it bans accounts that do
  it. This recipe is for the user's normal, personal use only.

## 1. Get the API credentials

Telegram gives every developer an app id and hash for signing in through the API. Ask the user
to do this in a desktop browser:

1. Open **https://my.telegram.org/auth** and sign in with their phone number in international
   format (`+359…`). The confirmation code arrives **inside the Telegram app**, in a chat from
   "Telegram", not by SMS.
2. Click **API development tools** (https://my.telegram.org/apps).
3. Fill in the form: App title `workspaced`, Short name `workspaced` (5 to 32 letters and digits),
   Platform **Desktop**, the other fields empty. Click **Create application**.
4. The page shows **App api_id** (a number) and **App api_hash** (32 characters). Tell the user
   to **keep both private and not paste them here**.

If the form answers just "ERROR", that is a known quirk of the site: they try again with VPN and
ad blockers off, or from another browser or network. The page never changes once the app exists,
so they can come back to it later for the values.

## 2. Add them as secrets

Ask the user to open this workspace's **Settings → Secrets** and add two secrets:

- **`TELEGRAM_API_ID`**: the api_id.
- **`TELEGRAM_API_HASH`**: the api_hash.

Then they press **Restart**, since a secret reaches the environment only after a restart, and
come back to this session once the workspace is running again. When they are back, check both
without printing them:

```sh
test -n "$TELEGRAM_API_ID" && echo id set || echo id missing
test -n "$TELEGRAM_API_HASH" && echo hash set || echo hash missing
case "$TELEGRAM_API_ID" in *[!0-9]*) echo "the id must be digits only" ;; esac
test ${#TELEGRAM_API_HASH} -eq 32 || echo "the hash should be 32 characters"
test -n "$OPENROUTER_API_KEY" && echo openrouter set
```

If anything is missing or malformed, say which, and ask the user to fix the secret and restart
again. `OPENROUTER_API_KEY` is normally already there; voice notes use it.

## 3. Install

Everything goes in the home, so it survives restarts:

```sh
mkdir -p ~/telegram-cli ~/.local/bin
cd ~/telegram-cli
uv venv .venv
uv pip install --python .venv telethon==1.45.0 qrcode==8.2
apk add ffmpeg
```

Then write the two files below, exactly as given, and the `tg` command:

```sh
cat > ~/.local/bin/tg <<'SH'
#!/bin/sh
exec "$HOME/telegram-cli/.venv/bin/python" "$HOME/telegram-cli/tg.py" "$@"
SH
chmod +x ~/.local/bin/tg
tg --help
```

### `~/telegram-cli/tg.py`

```python
#!/usr/bin/env python3
"""tg - the user's own Telegram account from the command line.

  tg login [--phone +359...]       sign in (QR code by default)
  tg me                            the signed-in account
  tg chats [filter] [-n 30]        recent chats, unread counts, last message
  tg unread [-n 10] [-a]           what is unread, chat by chat
  tg read <chat> [-n 20]           recent messages, oldest first
  tg read <chat> --before <id>     page further back
  tg search <text> [--in <chat>]   search messages
  tg send <chat> <text...>         send text ("-" reads stdin)
  tg voice <chat> <text...> --preview   record a voice note, send it to Saved Messages only
  tg voice <chat> <text...>        send that same recording to the chat
  tg send-file <chat> <path...>    send photos or files [--caption ...] [--as-file]
  tg forward <chat> <ids...> --to <chat>
  tg mark-read <chat>

-j gives JSON. <chat> is a name fragment, @username, numeric id, t.me link, or `me` for
Saved Messages.
Voice notes, video notes and audio are transcribed in read and unread (--no-transcribe skips).
"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import json
import os
import sys
from pathlib import Path

from telethon import TelegramClient
from telethon.errors import SessionPasswordNeededError
from telethon.sessions import SQLiteSession
from telethon.tl.types import DocumentAttributeAudio

CONFIG_DIR = Path.home() / ".config" / "telegram"
SESSION_PATH = CONFIG_DIR / "telegram.session"
DEVICE = {"device_model": "workspaced", "system_version": "1.0", "app_version": "1.0"}


# --- session ---------------------------------------------------------------------------


def creds() -> tuple[int, str]:
    api_id = os.environ.get("TELEGRAM_API_ID", "").strip()
    api_hash = os.environ.get("TELEGRAM_API_HASH", "").strip()
    if not api_id or not api_hash:
        raise RuntimeError(
            "TELEGRAM_API_ID / TELEGRAM_API_HASH are not set. Add them as workspace secrets "
            "and restart the workspace."
        )
    return int(api_id), api_hash


def new_client() -> TelegramClient:
    api_id, api_hash = creds()
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_DIR.chmod(0o700)
    return TelegramClient(SQLiteSession(str(SESSION_PATH)), api_id, api_hash, **DEVICE)


async def client() -> TelegramClient:
    if not SESSION_PATH.exists():
        raise RuntimeError("not signed in yet; the user runs `tg login` in the workspace terminal")
    c = new_client()
    await c.connect()
    if not await c.is_user_authorized():
        raise RuntimeError("the session is no longer authorized; the user runs `tg login` again")
    return c


# --- formatting ------------------------------------------------------------------------


def out(obj) -> None:
    print(json.dumps(obj, ensure_ascii=False, indent=2) if isinstance(obj, (dict, list)) else obj)


def label(entity) -> str:
    name = " ".join(
        filter(None, [getattr(entity, "first_name", None), getattr(entity, "last_name", None)])
    )
    return getattr(entity, "title", None) or name or str(getattr(entity, "id", "?"))


async def resolve(c: TelegramClient, chat: str):
    """A chat by @username, numeric id, t.me link, `me` or a unique name fragment."""
    raw = (chat or "").strip()
    if not raw:
        raise ValueError("chat must not be empty")
    # Saved Messages, which a name search would never find.
    if raw.lower() == "me":
        return await c.get_me()
    if "t.me/" in raw:
        return await c.get_entity("@" + raw.split("t.me/")[-1].split("/")[0].strip("@"))
    if raw.startswith("@"):
        return await c.get_entity(raw)
    if raw.lstrip("-").isdigit():
        return await c.get_entity(int(raw))
    needle = raw.lower()
    hits = [d.entity async for d in c.iter_dialogs() if needle in (d.name or "").lower()]
    if len(hits) == 1:
        return hits[0]
    if not hits:
        raise ValueError(f"no chat matched {raw!r} (try: tg chats {raw})")
    listing = ", ".join(f"{label(h)} ({h.id})" for h in hits[:10])
    raise ValueError(f"{raw!r} matches several chats: {listing}; use the id or @username")


async def sender_names(c: TelegramClient, messages) -> dict[int, str]:
    names: dict[int, str] = {}
    for m in messages:
        if m.sender_id is None or m.sender_id in names:
            continue
        try:
            names[m.sender_id] = label(await c.get_entity(m.sender_id))
        except Exception:
            names[m.sender_id] = str(m.sender_id)
    return names


def media_label(m) -> str:
    """What a message carries besides text, in words: [voice 0:12], [file report.pdf]."""
    if not m.media:
        return ""
    f = m.file
    seconds = int(getattr(f, "duration", 0) or 0) if f else 0
    dur = f" {seconds // 60}:{seconds % 60:02d}" if seconds else ""
    name = f" {f.name}" if f and f.name else ""
    if m.voice:
        return f"[voice{dur}]"
    if m.video_note:
        return f"[video note{dur}]"
    if m.audio:
        return f"[audio{dur}{name}]"
    if m.photo:
        return "[photo]"
    if m.gif:
        return "[gif]"
    if m.video:
        return f"[video{dur}]"
    if m.sticker:
        return "[sticker]"
    if m.geo:
        return "[location]"
    if m.contact:
        return "[contact]"
    if m.poll:
        return "[poll]"
    if m.document:
        return f"[file{name}]"
    return f"[{type(m.media).__name__}]"


def message_text(m, spoken: dict[int, str]) -> str:
    media = media_label(m)
    if m.id in spoken:
        media = f"{media} «{spoken[m.id]}»"
    return f"{media} {(m.message or '').strip()}".strip()


def message_line(m, names, spoken, limit: int = 1500) -> str:
    who = "me" if m.out else names.get(m.sender_id, str(m.sender_id or "system"))
    text = message_text(m, spoken)
    if len(text) > limit:
        text = text[:limit] + f"… (+{len(text) - limit} chars)"
    reply = f" (reply_to {m.reply_to_msg_id})" if m.reply_to_msg_id else ""
    stamp = m.date.strftime("%Y-%m-%d %H:%M UTC") if m.date else "?"
    return f"#{m.id} [{stamp}] {who}{reply}: {text}"


def message_dict(m, names, spoken) -> dict:
    return {
        "id": m.id,
        "date": m.date.isoformat() if m.date else None,
        "sender": "me" if m.out else names.get(m.sender_id, str(m.sender_id)),
        "sender_id": m.sender_id,
        "out": bool(m.out),
        "reply_to": m.reply_to_msg_id,
        "text": m.message or "",
        "media": media_label(m) or None,
        "transcript": spoken.get(m.id),
    }


async def transcripts(c, messages, enabled: bool) -> dict[int, str]:
    """Spoken messages as text, each transcribed once and then cached."""
    if not enabled:
        return {}
    import voice

    result: dict[int, str] = {}
    for m in messages:
        if not (m.voice or m.video_note or m.audio):
            continue
        key = f"{m.chat_id}_{m.id}"
        cached = voice.cached_transcript(key)
        if cached is not None:
            result[m.id] = cached
        elif (getattr(m.file, "duration", 0) or 0) > 600:
            result[m.id] = "(longer than 10 minutes, not transcribed)"
        else:
            try:
                data = await c.download_media(m, bytes)
                result[m.id] = voice.transcript(key, data, "ogg" if m.voice else None)
            except Exception as exc:
                result[m.id] = f"(transcription failed: {exc})"
    return result


# --- commands --------------------------------------------------------------------------


async def cmd_login(args) -> int:
    import qrcode

    creds()
    c = new_client()
    await c.connect()
    try:
        if not await c.is_user_authorized():
            if args.phone:
                sent = await c.send_code_request(args.phone)
                code = input("the login code Telegram just sent you: ").strip()
                try:
                    await c.sign_in(args.phone, code, phone_code_hash=sent.phone_code_hash)
                except SessionPasswordNeededError:
                    await c.sign_in(password=getpass.getpass("two-step verification password: "))
            else:
                while True:
                    qr = await c.qr_login()
                    print("\nOn your phone: Telegram > Settings > Devices > Link Desktop Device,")
                    print("then scan this code:\n")
                    code = qrcode.QRCode(border=1)
                    code.add_data(qr.url)
                    code.print_ascii(invert=True)
                    try:
                        await qr.wait(timeout=40)
                        break
                    except asyncio.TimeoutError:
                        print("the code expired, here is a new one")
                    except SessionPasswordNeededError:
                        await c.sign_in(password=getpass.getpass("two-step verification password: "))
                        break
        me = await c.get_me()
        print(f"signed in as {label(me)} (@{me.username or '-'}, id {me.id})")
        return 0
    finally:
        await c.disconnect()


async def cmd_me(args) -> int:
    c = await client()
    me = await c.get_me()
    if args.json:
        out({"id": me.id, "name": label(me), "username": me.username})
    else:
        out(f"{label(me)} (@{me.username or '-'}, id {me.id})")
    return 0


async def cmd_chats(args) -> int:
    c = await client()
    needle = " ".join(args.filter).lower()
    rows = []
    async for d in c.iter_dialogs():
        if needle and needle not in (d.name or "").lower():
            continue
        rows.append({
            "id": d.id,
            "name": d.name or "",
            "kind": "group" if d.is_group else ("channel" if d.is_channel else "user"),
            "username": getattr(d.entity, "username", None),
            "unread": d.unread_count,
            "last": (d.message.message or "") if d.message else "",
            "date": d.date.isoformat() if d.date else None,
        })
        if len(rows) >= args.number:
            break
    if args.json:
        out(rows)
        return 0
    for r in rows:
        parts = [str(r["id"]), r["kind"], r["name"]]
        if r["username"]:
            parts.append("@" + r["username"])
        if r["unread"]:
            parts.append(f"unread {r['unread']}")
        if r["last"]:
            parts.append("last: " + r["last"].replace("\n", " ")[:60])
        print(" | ".join(parts))
    if not rows:
        print("no chats found")
    return 0


async def cmd_read(args) -> int:
    c = await client()
    entity = await resolve(c, args.chat)
    msgs = [m for m in await c.get_messages(entity, limit=args.number, offset_id=args.before) if m]
    msgs.reverse()
    names = await sender_names(c, msgs)
    spoken = await transcripts(c, msgs, not args.no_transcribe)
    if args.json:
        out({
            "chat": {"id": entity.id, "name": label(entity)},
            "messages": [message_dict(m, names, spoken) for m in msgs],
        })
        return 0
    print(f"{label(entity)} (id {entity.id})")
    for m in msgs:
        print(message_line(m, names, spoken))
    if not msgs:
        print("(no messages)")
    return 0


async def cmd_unread(args) -> int:
    from datetime import datetime, timezone

    c = await client()
    now = datetime.now(timezone.utc)
    chats = []
    async for d in c.iter_dialogs(limit=args.scan):
        if not d.unread_count:
            continue
        mute = getattr(d.dialog.notify_settings, "mute_until", None)
        muted = bool(mute and mute > now)
        broadcast = d.is_channel and not d.is_group
        if not args.all and (muted or broadcast or d.archived):
            continue
        msgs = [m for m in await c.get_messages(d.entity, limit=min(d.unread_count, args.number)) if m]
        msgs.reverse()
        names = await sender_names(c, msgs)
        spoken = await transcripts(c, msgs, not args.no_transcribe)
        chats.append((d, muted, msgs, names, spoken))
    if args.json:
        out([
            {"id": d.id, "name": d.name, "unread": d.unread_count, "muted": muted,
             "messages": [message_dict(m, names, spoken) for m in msgs]}
            for d, muted, msgs, names, spoken in chats
        ])
        return 0
    if not chats:
        skipped = "" if args.all else " (muted chats, channels and the archive skipped; -a includes them)"
        print("nothing unread" + skipped)
    for d, muted, msgs, names, spoken in chats:
        shown = f", last {len(msgs)} shown" if len(msgs) < d.unread_count else ""
        print(f"== {d.name} (id {d.id}) | unread {d.unread_count}{shown}{' | muted' if muted else ''}")
        for m in msgs:
            print(message_line(m, names, spoken, limit=600))
    return 0


async def cmd_search(args) -> int:
    c = await client()
    entity = await resolve(c, args.in_chat) if args.in_chat else None
    hits = [m for m in await c.get_messages(entity, limit=args.number, search=args.query) if m]
    names = await sender_names(c, hits)
    rows = []
    for m in hits:
        row = message_dict(m, names, {})
        row["chat"] = label(entity) if entity else label(await m.get_chat())
        row["chat_id"] = m.chat_id
        rows.append(row)
    if args.json:
        out(rows)
        return 0
    for row, m in zip(rows, hits):
        print(f"[{row['chat']}] {message_line(m, names, {})}")
    if not rows:
        print("no messages found")
    return 0


def read_text(words: list[str]) -> str:
    return (sys.stdin.read() if words == ["-"] else " ".join(words)).strip()


async def cmd_send(args) -> int:
    text = read_text(args.text)
    if not text:
        print("tg: refusing to send an empty message", file=sys.stderr)
        return 2
    c = await client()
    entity = await resolve(c, args.chat)
    msg = await c.send_message(entity, text, reply_to=args.reply_to or None)
    out({"chat": label(entity), "chat_id": entity.id, "id": msg.id} if args.json
        else f"sent to {label(entity)} (message id {msg.id})")
    return 0


async def cmd_voice(args) -> int:
    import voice

    text = read_text(args.text)
    if not text:
        print("tg: refusing to record an empty message", file=sys.stderr)
        return 2
    c = await client()
    entity = await resolve(c, args.chat)
    ogg, seconds = voice.record(text, args.voice or voice.DEFAULT_VOICE)
    attributes = [DocumentAttributeAudio(duration=max(1, round(seconds)), voice=True)]
    if args.preview:
        # Saved Messages: only the user sees it, and the cache makes the real send this file.
        await c.send_file("me", str(ogg), voice_note=True, attributes=attributes,
                          caption=f"Preview, voice note for {label(entity)}:\n{text}"[:1000])
        out(f"preview for {label(entity)} (id {entity.id}) is in your Saved Messages; "
            "the same command without --preview sends this exact recording")
        return 0
    msg = await c.send_file(entity, str(ogg), voice_note=True, attributes=attributes,
                            reply_to=args.reply_to or None)
    out(f"voice note sent to {label(entity)} (message id {msg.id}, {round(seconds)}s)")
    return 0


async def cmd_send_file(args) -> int:
    paths = [Path(p).expanduser() for p in args.paths]
    missing = [str(p) for p in paths if not p.is_file()]
    if missing:
        print(f"tg: no such file: {', '.join(missing)}", file=sys.stderr)
        return 2
    c = await client()
    entity = await resolve(c, args.chat)
    sent = await c.send_file(entity, [str(p) for p in paths] if len(paths) > 1 else str(paths[0]),
                             caption=args.caption or None, force_document=args.as_file,
                             reply_to=args.reply_to or None)
    ids = [m.id for m in sent] if isinstance(sent, list) else [sent.id]
    out(f"sent {len(paths)} file(s) to {label(entity)} (message ids {', '.join(map(str, ids))})")
    return 0


async def cmd_forward(args) -> int:
    c = await client()
    source, target = await resolve(c, args.chat), await resolve(c, args.to)
    sent = await c.forward_messages(target, args.ids, source)
    sent = sent if isinstance(sent, list) else [sent]
    out(f"forwarded {len([m for m in sent if m])} message(s) from {label(source)} to {label(target)}")
    return 0


async def cmd_mark_read(args) -> int:
    c = await client()
    entity = await resolve(c, args.chat)
    await c.send_read_acknowledge(entity)
    out(f"marked {label(entity)} as read")
    return 0


def parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-j", "--json", action="store_true", help="machine-readable output")
    p = argparse.ArgumentParser(prog="tg", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("login", help="sign in, by QR code unless --phone is given")
    s.add_argument("--phone", help="sign in with a code sent to this number instead")
    s.set_defaults(func=cmd_login)

    s = sub.add_parser("me", parents=[common])
    s.set_defaults(func=cmd_me)

    s = sub.add_parser("chats", parents=[common])
    s.add_argument("filter", nargs="*", default=[])
    s.add_argument("-n", "--number", type=int, default=30)
    s.set_defaults(func=cmd_chats)

    s = sub.add_parser("unread", parents=[common])
    s.add_argument("-n", "--number", type=int, default=10, help="messages per chat")
    s.add_argument("-a", "--all", action="store_true", help="include muted chats, channels, archive")
    s.add_argument("--scan", type=int, default=100, help="how many recent chats to look at")
    s.add_argument("--no-transcribe", action="store_true")
    s.set_defaults(func=cmd_unread)

    s = sub.add_parser("read", parents=[common])
    s.add_argument("chat")
    s.add_argument("-n", "--number", type=int, default=20)
    s.add_argument("--before", type=int, default=0, help="only messages older than this id")
    s.add_argument("--no-transcribe", action="store_true")
    s.set_defaults(func=cmd_read)

    s = sub.add_parser("search", parents=[common])
    s.add_argument("query")
    s.add_argument("--in", dest="in_chat", default="")
    s.add_argument("-n", "--number", type=int, default=20)
    s.set_defaults(func=cmd_search)

    s = sub.add_parser("send", parents=[common])
    s.add_argument("chat")
    s.add_argument("text", nargs="+", help="the text; '-' reads stdin")
    s.add_argument("--reply-to", type=int, default=0)
    s.set_defaults(func=cmd_send)

    s = sub.add_parser("voice")
    s.add_argument("chat")
    s.add_argument("text", nargs="+", help="what to say; '-' reads stdin")
    s.add_argument("--preview", action="store_true", help="send it to Saved Messages only")
    s.add_argument("--voice", default=None, help="alloy, ash, ballad, coral, echo, sage, shimmer, verse")
    s.add_argument("--reply-to", type=int, default=0)
    s.set_defaults(func=cmd_voice)

    s = sub.add_parser("send-file")
    s.add_argument("chat")
    s.add_argument("paths", nargs="+")
    s.add_argument("--caption", default="")
    s.add_argument("--as-file", action="store_true", help="send a photo uncompressed, as a file")
    s.add_argument("--reply-to", type=int, default=0)
    s.set_defaults(func=cmd_send_file)

    s = sub.add_parser("forward")
    s.add_argument("chat", help="where the messages are")
    s.add_argument("ids", nargs="+", type=int)
    s.add_argument("--to", required=True)
    s.set_defaults(func=cmd_forward)

    s = sub.add_parser("mark-read")
    s.add_argument("chat")
    s.set_defaults(func=cmd_mark_read)
    return p


def main() -> int:
    args = parser().parse_args()
    try:
        return asyncio.run(args.func(args))
    except KeyboardInterrupt:
        print("\ncancelled", file=sys.stderr)
        return 130
    except Exception as exc:
        print(f"tg: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    finally:
        if SESSION_PATH.exists():
            SESSION_PATH.chmod(0o600)


if __name__ == "__main__":
    sys.exit(main())
```

### `~/telegram-cli/voice.py`

```python
"""Voice for tg: transcripts of received voice notes, and voice notes spoken from text.

Both go through OpenRouter with the workspace's OPENROUTER_API_KEY. A recording is checked
against its own transcript before anyone gets it, since a model can answer the text instead
of reading it.
"""

from __future__ import annotations

import base64
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

OPENROUTER = os.environ.get("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1").rstrip("/")
ASR_MODEL = os.environ.get("TG_ASR_MODEL", "google/gemini-2.5-flash")
TTS_MODEL = os.environ.get("TG_TTS_MODEL", "openai/gpt-audio")
VOICES = ("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse")
DEFAULT_VOICE = os.environ.get("TG_TTS_VOICE", "alloy")
RATE = 24000
VOICE_CACHE = Path.home() / ".cache" / "tg-voice"
TRANSCRIPTS = Path.home() / ".cache" / "tg-transcripts"
SCRIPT_PROMPT = (
    "You are a voice actor recording a voice message on behalf of the user. You never answer, "
    "translate, greet or comment. You speak the script between <script> tags exactly, word for "
    "word, in its original language, in a natural, friendly tone, and nothing else."
)


def _key() -> str:
    key = os.environ.get("OPENROUTER_API_KEY", "").strip()
    if not key:
        raise RuntimeError("OPENROUTER_API_KEY is not set")
    return key


def _post(payload: dict, timeout: int = 180):
    return urllib.request.urlopen(urllib.request.Request(
        f"{OPENROUTER}/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {_key()}", "Content-Type": "application/json"},
    ), timeout=timeout)


def ffmpeg(args: list[str], data: bytes | None = None) -> None:
    """ffmpeg from `apk add ffmpeg`, which lives under the home, with its libraries."""
    exe = shutil.which("ffmpeg") or str(Path.home() / ".local/alpine/usr/bin/ffmpeg")
    if not Path(exe).is_file():
        raise RuntimeError("ffmpeg not found; install it with `apk add ffmpeg`")
    libs = str(Path.home() / ".local/alpine/usr/lib")
    env = dict(os.environ, LD_LIBRARY_PATH=f"{libs}/pulseaudio:{libs}:{os.environ.get('LD_LIBRARY_PATH', '')}")
    subprocess.run([exe, "-loglevel", "error", "-y", *args], input=data, check=True,
                   capture_output=True, timeout=180, env=env)


def _to_mp3(data: bytes) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = Path(tmp) / "in.audio", Path(tmp) / "out.mp3"
        src.write_bytes(data)
        ffmpeg(["-i", str(src), "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame", "-b:a", "32k", str(dst)])
        return dst.read_bytes()


def _transcribe(data: bytes, fmt: str) -> str:
    payload = {"model": ASR_MODEL, "messages": [{"role": "user", "content": [
        {"type": "text", "text": (
            "Transcribe this verbatim in the language it is spoken in. Keep punctuation. Do not "
            "translate, summarize or comment. If there is no intelligible speech, output nothing.")},
        {"type": "input_audio", "input_audio": {"data": base64.b64encode(data).decode(), "format": fmt}},
    ]}]}
    try:
        with _post(payload) as resp:
            body = json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"transcription HTTP {exc.code}: {exc.read().decode(errors='replace')[:200]}") from None
    return (body["choices"][0]["message"].get("content") or "").strip()


def cached_transcript(key: str) -> str | None:
    path = TRANSCRIPTS / f"{key}.txt"
    return path.read_text() if path.exists() else None


def transcript(key: str, data: bytes, fmt: str | None) -> str:
    """A received voice note as text. A format the model refuses is converted to mp3 first."""
    try:
        text = _transcribe(_to_mp3(data), "mp3") if fmt is None else _transcribe(data, fmt)
    except RuntimeError as exc:
        if "HTTP 4" not in str(exc) or fmt is None:
            raise
        text = _transcribe(_to_mp3(data), "mp3")
    TRANSCRIPTS.mkdir(parents=True, exist_ok=True)
    text = text or "(no intelligible speech)"
    (TRANSCRIPTS / f"{key}.txt").write_text(text)
    return text


def _speak(text: str, voice: str) -> tuple[bytes, str]:
    """24 kHz mono PCM, and what the model says it spoke. Audio output needs streaming."""
    payload = {
        "model": TTS_MODEL,
        "modalities": ["text", "audio"],
        "audio": {"voice": voice, "format": "pcm16"},
        "stream": True,
        "messages": [
            {"role": "system", "content": SCRIPT_PROMPT},
            {"role": "user", "content": f"<script>{text}</script>"},
        ],
    }
    pcm, said = bytearray(), []
    try:
        with _post(payload) as resp:
            for raw in resp:
                line = raw.decode(errors="replace").strip()
                if not line.startswith("data:") or line == "data: [DONE]":
                    continue
                choices = json.loads(line[5:]).get("choices") or [{}]
                audio = (choices[0].get("delta") or {}).get("audio") or {}
                if audio.get("data"):
                    pcm += base64.b64decode(audio["data"])
                if audio.get("transcript"):
                    said.append(audio["transcript"])
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"speech HTTP {exc.code}: {exc.read().decode(errors='replace')[:200]}") from None
    return bytes(pcm), "".join(said)


def record(text: str, voice: str = DEFAULT_VOICE) -> tuple[Path, float]:
    """A voice note of exactly `text`, cached by text and voice, so a preview is what gets sent."""
    if voice not in VOICES:
        raise ValueError(f"unknown voice {voice!r}; pick one of: {', '.join(VOICES)}")
    VOICE_CACHE.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256(f"{TTS_MODEL}\n{voice}\n{text}".encode()).hexdigest()[:16]
    ogg, meta = VOICE_CACHE / f"{digest}.ogg", VOICE_CACHE / f"{digest}.json"
    if ogg.exists() and meta.exists():
        return ogg, json.loads(meta.read_text())["duration"]
    words = lambda s: re.findall(r"\w+", s.lower())  # noqa: E731
    for _ in range(2):
        pcm, said = _speak(text, voice)
        if pcm and difflib.SequenceMatcher(None, words(text), words(said)).ratio() >= 0.9:
            break
    else:
        raise RuntimeError(f"the voice model did not read the text as written (it said {said[:200]!r})")
    ffmpeg(["-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "pipe:0",
            "-c:a", "libopus", "-b:a", "32k", "-application", "voip", str(ogg)], pcm)
    duration = len(pcm) / (RATE * 2)
    meta.write_text(json.dumps({"text": text, "voice": voice, "duration": duration}, ensure_ascii=False))
    return ogg, duration
```

Check both compile and the command answers, before anyone signs in:

```sh
~/telegram-cli/.venv/bin/python -m py_compile ~/telegram-cli/tg.py ~/telegram-cli/voice.py
tg me    # expected: "not signed in yet"
```

## 4. Sign in

Signing in is interactive (a QR code, maybe a password), and your shell has no keyboard, so
**the user does it in the workspace's terminal**, never you. Ask them to:

1. Open the workspace's **Terminal** tab and run `tg login`.
2. On their phone: **Telegram → Settings → Devices → Link Desktop Device**, and scan the QR code
   in the terminal. It renews itself every 40 seconds until it is scanned.
3. If their account has a two-step verification password, the terminal asks for it. It is typed
   there, never in the chat.
4. The terminal prints `signed in as …`. They tell you once it does.

If the QR code does not scan (a small screen, a narrow terminal), they run
`tg login --phone +359…` instead and type the code Telegram sends to their app.

Then check it yourself:

```sh
tg me
ls -l ~/.config/telegram/telegram.session   # must be -rw-------
```

Never run `tg login` yourself, never sign out, and never copy, print or move the session file.

## 5. Verify it end to end

Run each, and fix anything that fails before you go on:

1. `tg chats -n 10`: the user's recent chats, with unread counts.
2. `tg unread -n 3`: what is unread, or "nothing unread". A voice note shows its transcript in «…».
3. `tg read me -n 3`: the user's Saved Messages.
4. `tg send me "Test from the workspace"`: ask the user to confirm it arrived in Saved Messages.
   Sending to themselves needs no OK.
5. `tg voice me "This is a test voice note" --preview`: a voice note arrives in Saved Messages.
   This one is the slow step; it takes 10 to 30 seconds.

## 6. Leave instructions behind

Write `~/.config/workspaced/instructions/telegram.md`, so every later session knows the tool and
its rules. Use this, in the user's language if they write in another one:

```markdown
# Telegram

My own Telegram account is reachable with `tg` (code in `~/telegram-cli/`). Use it for anything
about my Telegram chats, never a bot or another client.

    tg unread [-n 10] [-a]                 what is unread, chat by chat
    tg chats [filter] [-n 30]              chats, unread counts, last message
    tg read <chat> [-n 20]                 recent messages, oldest first; --before <id> pages back
    tg search "<text>" [--in <chat>]       search messages
    tg send <chat> "<text>"                send text; "-" reads stdin
    tg voice <chat> "<text>" --preview     record a voice note, send it to my Saved Messages only
    tg voice <chat> "<text>"               send that exact recording
    tg send-file <chat> <path...> [--caption "…"] [--as-file]
    tg forward <chat> <id...> --to <chat>
    tg mark-read <chat>

`<chat>` is a name fragment, @username, numeric id or t.me link; `me` is my Saved Messages.
`-j` gives JSON. Times are UTC; tell me them on my clock.

## Reading

"What's new", "check my messages", "anything from Maria?" → `tg unread`, or `tg read <chat>`
for one person. Summarize: who wrote, what they want, what needs an answer from me. Skip noise.
Reading never marks anything as read. A «…» after a voice note is its transcript, which can
mishear names, so say "in the voice note she says…" rather than quoting it as exact words.

## Sending: always the same three steps

1. Resolve the chat (`tg chats <name>`). When the name matches several, ask; never guess.
2. Show me the exact chat name and the exact text, and wait for my OK. For a voice note, run
   `tg voice <chat> "<text>" --preview` first, so I can listen to it in Saved Messages.
3. Only after I agree, send it with the identical text, so what I approved is what goes out.
   Then confirm in one line: to whom, what, and the message id.

When I dictate a message ("tell Ivan that…"), write it in my voice, addressed to them, not as a
report about me. A multi-line message goes through stdin:

    tg send <chat> - <<'EOF'
    line one
    line two
    EOF

Mark-read and forward also need my OK first. Sending to `me` does not.

## Never

- Never sign in, sign out, or touch `~/.config/telegram/`. If `tg` says the session is not
  authorized, ask me to run `tg login` in the workspace's terminal.
- Never print, copy or paste `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` or the session file.
- Never message people who have not written to me, and never send the same text to many chats.
- When `tg` fails it prints `tg: <Error>: <message>`: read it and tell me, don't retry blindly.
```

Then tell the user, in two or three sentences, that the agent can now read and send Telegram
messages as them, that it always asks before sending, and how to end it: delete the device
**workspaced** in Telegram under **Settings → Devices**. Suggest a first try: "what's unread on
Telegram?", or a task: "every morning at 9, summarize my unread Telegram messages".
