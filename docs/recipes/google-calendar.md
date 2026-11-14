---
title: Google Calendar for your agent (read-only)
summary: Let the agent read your Google Calendar through its private iCal address, so it can answer what's on and whether you're free, with no write access.
category: automation
tags: [calendar, google, ical, mcp, read-only]
visibility: public
---

# Google Calendar for your agent (read-only)

Build a small read-only MCP server that reads the user's Google Calendar from its **secret iCal
address**, register it with OpenCode, and leave instructions, so the agent can answer "what's on
tomorrow?" or "am I free Friday?". Do the steps in order.

## 1. Get the secret address

Ask the user to do this in a desktop browser:

1. Open **calendar.google.com**, then click **⚙ → Settings**.
2. Under **Settings for my calendars**, click their calendar, then **Integrate calendar**.
3. At **Secret address in iCal format**, click the **copy** button. Not the "Public address"
   above it. It looks like
   `https://calendar.google.com/calendar/ical/<email>/private-<code>/basic.ics`.
4. In this workspace, open **Settings → Secrets** and add **`GOOGLE_CALENDAR_ICAL_URL`** with
   that address.

The address works like a password: never let them paste it into the chat. If they do, ask them
to press **Reset** there and use the new address.

## 2. Build the server

Write `~/mcp/calendar/server.py` as a single-file `uv` script, following the pseudocode below. It
is a **stdio MCP server**: OpenCode starts it as a local process and talks to it over standard
input and output. There is no HTTP server, no port and no service to run, so nothing is
reachable from outside the workspace.

- **Dependencies** (pin them in the script header): `mcp==2.3.0`, `icalendar==7.3.0`,
  `recurring-ical-events==3.8.2`, `httpx==0.28.1`, and Python 3.11 or newer.
- In `mcp` 2.x, `FastMCP` is **renamed** to `MCPServer` (`from mcp.server.mcpserver import
  MCPServer`). Raise `ToolError` (`mcp.server.mcpserver.exceptions`) for an error the agent
  should read.

```text
# /// script  header: requires-python >=3.11, dependencies as above

URL      = env GOOGLE_CALENDAR_ICAL_URL         # a credential: never log, return or echo it
TIMEZONE = env USER_TIMEZONE, else the calendar's X-WR-TIMEZONE, else UTC
silence the "httpx" and "httpcore" loggers      # they log every request with its full URL
log only to stderr                              # stdout carries the MCP protocol

calendar():                                     # cached 5 min, one fetch at a time (a lock)
    if URL missing   -> ToolError "GOOGLE_CALENDAR_ICAL_URL is not set: add it under Secrets and restart"
    if not https     -> ToolError
    GET URL, timeout 30 s, follow redirects, refuse bodies over 20 MB
        401/403/404  -> ToolError "address refused: it was reset or copied wrong"
        other error  -> ToolError naming the HTTP status or error type, never the URL
                        (re-raise "from None", or the URL leaks in the traceback)
    parse with icalendar.Calendar.from_ical      # not iCal -> ToolError "check it ends in .ics"

occurrences(start, end):
    recurring_ical_events.of(calendar).between(start, end)   # repeats, skipped dates, moved ones
    drop STATUS:CANCELLED, sort by start
    per event: title, start, end, allDay, location, status, description (cut to 1000 chars),
               recurring = its UID has RRULE, RDATE or RECURRENCE-ID among the definitions
               (not on the occurrence: the expansion stamps RECURRENCE-ID on every one)
    times -> ISO in TIMEZONE; a time with no zone ("floating") keeps its wall-clock time
    all-day -> dates, and end = the last day (iCal's end is the day after)

tool calendar_info()                -> {name, timezone, now, today}       # read-only annotation
tool calendar_events(start, end?, query?, limit=100)
    start/end YYYY-MM-DD, inclusive, end defaults to start, range at most 366 days
    query matches title, location or description, case-insensitive
    -> {timezone, total, events[:limit], truncated}
tool calendar_upcoming(days=7 (max 60), limit=20)
    from now, including events in progress -> {timezone, now, events, truncated}

server.run()                                    # stdio
```

Also write `~/mcp/calendar/check.py`, a `uv` script with `mcp==2.3.0`:

```text
start server.py with mcp.Client(StdioServerParameters(
    command="uv", args=["run", "--quiet", "--script", server.py],
    env=all of os.environ))                     # the client passes almost nothing by default
list the tools, call calendar_info, calendar_upcoming(14 days), calendar_events(today)
print the name, timezone, today and the next events, never the URL; print "OK"
on failure: print the first real error (the client wraps it in exception groups), exit 1
```

Run it once without the address. This also downloads the dependencies, so OpenCode doesn't wait
for them:

```sh
cd ~/mcp/calendar && env -u GOOGLE_CALENDAR_ICAL_URL uv run --quiet --script check.py
```

It must list `calendar_info, calendar_events, calendar_upcoming`, then say
`GOOGLE_CALENDAR_ICAL_URL is not set`. Fix the code until it does.

## 3. Apply it

Add the server to `~/.config/opencode/opencode.jsonc`, under `mcp.servers`. Create the file if
needed, and keep anything else already in it. Never edit `opencode.json`.

```jsonc
{
  "mcp": {
    "servers": {
      "calendar": {
        "type": "local",
        "command": ["uv", "run", "--quiet", "--script", "/home/workspace/mcp/calendar/server.py"],
        "environment": {
          "GOOGLE_CALENDAR_ICAL_URL": "{env:GOOGLE_CALENDAR_ICAL_URL}",
          "USER_TIMEZONE": "{env:USER_TIMEZONE}"
        }
      }
    }
  }
}
```

Ask the user to **restart** the workspace and come back to this session.

## 4. Check it

```sh
case "$GOOGLE_CALENDAR_ICAL_URL" in
  "") echo "MISSING: add the secret and restart" ;;
  */public/basic.ics) echo "WRONG: public address, copy the secret one" ;;
  https://*) echo "secret is set" ;;
  *) echo "WRONG: not an https address" ;;
esac
uv run --quiet --script ~/mcp/calendar/check.py
```

The check must end with `OK`, showing the calendar's name and the next events. Ask the user
whether they look right. If the address is refused, they copy it again (step 1) and restart.

## 5. Leave instructions

Write `~/.config/workspaced/instructions/calendar.md`:

```markdown
# Calendar (read-only)

You can read the user's Google Calendar with `calendar_info`, `calendar_events` and
`calendar_upcoming`. You cannot add, change or delete events.

- Use it for any question about their schedule: what's on, when something is, are they free,
  what's next. Never answer from memory, and always read the calendar again.
- The workspace clock is UTC. For "today", "tomorrow" or "this week", call `calendar_info` first
  and work from its `today`.
- `calendar_events` takes `start` and `end` as `YYYY-MM-DD`, both inclusive, plus an optional
  `query` (title, location, description). Search about 90 days to find something specific.
- Answer briefly, one line per event, with the time in the user's timezone and the title. Never
  invent events.
- When asked to add or change an event, say you can only read the calendar.
- Recent changes can take a while, even hours, to appear. If the address is refused, the user
  copies the secret address again into `GOOGLE_CALENDAR_ICAL_URL` and restarts.
```

## 6. Test

Ask the user to open a **new** session and ask "What's on my calendar this week?", then compare
the answer with Google Calendar. When it matches, tell them it is set up, read-only, and that
**Reset** in Google Calendar cuts off access at any time.
