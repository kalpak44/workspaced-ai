#!/usr/bin/env python3
"""task — the workspace's scheduled tasks, ~/.config/workspaced/tasks/<name>.md.

  task add <name> --in 5m --prompt "…"                     once, after 5m / 2h / 1d
  task add <name> --at "20:41 $USER_TIMEZONE" --prompt "…" once, the next 20:41 on that clock
  task add <name> --at "2026-12-01 09:00 UTC" --prompt "…" once, at that date and time
  task add <name> --cron "0 9 * * 1-5" --timezone "$USER_TIMEZONE" --prompt "…"
  task add <name> --every 30m --prompt "…"                 1m, 5m, 30m, 2h; 1d is 09:00
  task list | show <name> | rm <name>

The workspace clock is UTC, and a cron without --timezone is UTC. --at must name its clock,
so a time the user said is never read as UTC by mistake. Every answer shows the time in UTC
and in USER_TIMEZONE, and a one-off whose time has passed is refused. --prompt - reads the
prompt from stdin.

A task's prompt runs later in a new session with nobody watching, so it must stand on its
own: what to do, where, and where the result goes.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

TASKS = Path(os.environ.get("HOME", "/home/workspace")) / ".config" / "workspaced" / "tasks"
UTC = ZoneInfo("UTC")
INTERVAL = re.compile(r"^(\d+)\s*([mhd])$", re.I)
# The same names the app accepts for a task file.
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$")


def zone(name: str) -> ZoneInfo:
    try:
        return UTC if name.upper() == "UTC" else ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        raise SystemExit(f"task: unknown time zone {name!r}; use a name such as Europe/Sofia") from None


def user_zone() -> ZoneInfo | None:
    name = os.environ.get("USER_TIMEZONE", "").strip()
    return zone(name) if name else None


def show_time(when: datetime, now: datetime) -> str:
    """The time in UTC and on the user's clock, and how far off it is."""
    minutes = round((when - now).total_seconds() / 60)
    if minutes < 90:
        gap = f"in {minutes} min"
    elif minutes < 48 * 60:
        gap = f"in {minutes // 60} h {minutes % 60:02d} min"
    else:
        gap = f"in {minutes // 1440} days"
    text = f"{when.astimezone(UTC):%a %d %b %H:%M} UTC"
    local = user_zone()
    if local is not None:
        text += f" = {when.astimezone(local):%a %d %b %H:%M} {local.key}"
    return f"{text} ({gap})"


def field_matches(spec: str, value: int, low: int) -> bool:
    for part in spec.split(","):
        span, _, step = part.partition("/")
        if span == "*":
            first, last = low, 10_000
        elif "-" in span:
            first, last = (int(x) for x in span.split("-"))
        else:
            first = last = int(span)
            if step:
                last = 10_000
        if first <= value <= last and (value - first) % int(step or 1) == 0:
            return True
    return False


def next_run(cron: str, tz: ZoneInfo, after: datetime) -> datetime | None:
    """The first minute after `after` that the cron matches on its clock, within a year."""
    minute, hour, dom, month, dow = cron.split()
    t = after.astimezone(tz).replace(second=0, microsecond=0) + timedelta(minutes=1)
    end = t + timedelta(days=366)
    while t < end:
        if not (
            field_matches(month, t.month, 1)
            and field_matches(dom, t.day, 1)
            and field_matches(dow, t.isoweekday() % 7, 0)
        ):
            t = (t + timedelta(days=1)).replace(hour=0, minute=0)
            continue
        if field_matches(hour, t.hour, 0) and field_matches(minute, t.minute, 0):
            return t
        t += timedelta(minutes=1)
    return None


def check_cron(expr: str) -> str:
    fields = expr.split()
    if len(fields) != 5:
        raise SystemExit(f"task: a cron has 5 fields, got {len(fields)}: {expr!r}")
    try:
        next_run(" ".join(fields), UTC, datetime.now(UTC))
    except ValueError:
        raise SystemExit(f"task: can't read cron {expr!r}; use numbers, *, ranges, lists and /steps") from None
    return " ".join(fields)


def every_to_cron(expr: str) -> str:
    match = INTERVAL.match(expr.strip())
    if not match:
        raise SystemExit(f"task: can't read interval {expr!r}; use 1m, 5m, 30m, 2h, 1d")
    n, unit = int(match.group(1)), match.group(2).lower()
    if n < 1:
        raise SystemExit("task: an interval is at least 1")
    if unit == "m":
        return "* * * * *" if n == 1 else (f"*/{n} * * * *" if n < 60 else "0 * * * *")
    if unit == "h":
        return "0 * * * *" if n == 1 else f"0 */{n} * * *"
    return "0 9 * * *"


def after_interval(expr: str, now: datetime) -> datetime:
    match = INTERVAL.match(expr.strip())
    if not match:
        raise SystemExit(f"task: can't read --in {expr!r}; use 5m, 2h, 1d")
    unit = {"m": "minutes", "h": "hours", "d": "days"}[match.group(2).lower()]
    when = now + timedelta(**{unit: int(match.group(1))})
    # A run fires on the minute; round up so "in 5m" is never early.
    return when.replace(second=0, microsecond=0) + timedelta(minutes=1) if when.second else when


def parse_at(text: str) -> datetime:
    """`HH:MM <zone>` (the next such time) or `YYYY-MM-DD HH:MM <zone>`; the zone is required."""
    stamp, _, name = text.strip().rpartition(" ")
    if not stamp or not ("/" in name or name.upper() == "UTC"):
        raise SystemExit(
            f"task: --at {text!r} must name its clock, such as '20:41 Europe/Sofia' or '18:41 UTC', "
            "so a time the user said is never read as UTC"
        )
    tz = zone(name)
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M"):
        try:
            return datetime.strptime(stamp, fmt).replace(tzinfo=tz)
        except ValueError:
            pass
    try:
        clock = datetime.strptime(stamp, "%H:%M")
    except ValueError:
        raise SystemExit(f"task: can't read --at {text!r}; use 'HH:MM <zone>' or 'YYYY-MM-DD HH:MM <zone>'") from None
    now = datetime.now(tz)
    when = now.replace(hour=clock.hour, minute=clock.minute, second=0, microsecond=0)
    return when if when > now else when + timedelta(days=1)


def read_task(path: Path) -> dict:
    raw = path.read_text()
    meta: dict[str, str] = {}
    body = raw
    if raw.startswith("---"):
        _, front, body = raw.split("---", 2)
        for line in front.strip().splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                meta[key.strip()] = value.strip()
    return {
        "name": path.stem,
        "cron": meta.get("cron", ""),
        "timezone": meta.get("timezone", "") or "UTC",
        "prompt": body.strip(),
    }


def path_for(name: str) -> Path:
    if not NAME.match(name):
        raise SystemExit(f"task: {name!r} is not a task name: letters, digits, - and _, up to 63")
    return TASKS / f"{name}.md"


def cmd_add(args) -> int:
    path = path_for(args.name)
    now = datetime.now(UTC)
    if sum(x is not None for x in (args.in_, args.at, args.cron, args.every)) != 1:
        raise SystemExit("task: give exactly one of --in, --at, --cron, --every")
    once = None
    if args.in_ is not None:
        # Kept on the user's clock, so the file reads as they said it.
        once = after_interval(args.in_, now).astimezone(user_zone() or UTC)
    elif args.at is not None:
        once = parse_at(args.at)
    if once is not None:
        if once <= now:
            raise SystemExit(f"task: that time has already passed; it is {show_time(now, now).split(' (')[0]} now")
        cron, tz = f"{once.minute} {once.hour} {once.day} {once.month} *", once.tzinfo
    else:
        cron = check_cron(args.cron) if args.cron is not None else every_to_cron(args.every)
        tz = zone(args.timezone)
    prompt = sys.stdin.read() if args.prompt in (None, "-") else args.prompt
    prompt = prompt.strip()
    if not prompt:
        raise SystemExit('task: a task needs a prompt (--prompt "…" or --prompt - for stdin)')
    first = next_run(cron, tz, now)
    if first is None:
        raise SystemExit(f"task: cron '{cron}' never fires within a year")
    fields = cron.split()
    if once is None and fields[2] != "*" and fields[3] != "*" and first - now > timedelta(days=300):
        raise SystemExit(
            f"task: cron '{cron}' on {tz.key} has passed this year and would next run {first:%d %b %Y}; "
            "for a one-off use --in or --at"
        )
    if once is not None:
        prompt += f"\n\nThis is a one-off: when it is done, delete {path} so it does not run again next year."
    TASKS.mkdir(parents=True, exist_ok=True)
    path.write_text(f"---\ncron: {cron}\ntimezone: {tz.key}\n---\n{prompt}\n")
    print(f"{'once' if once else 'first run'} {show_time(first, now)}: {path.stem}, cron '{cron}' on {tz.key}")
    return 0


def cmd_list(args) -> int:
    now = datetime.now(UTC)
    items = [read_task(p) for p in sorted(TASKS.glob("*.md"))] if TASKS.is_dir() else []
    for item in items:
        try:
            nxt = next_run(item["cron"], zone(item["timezone"]), now)
            item["next"] = show_time(nxt, now) if nxt else "never"
        except (SystemExit, ValueError):
            item["next"] = "unreadable schedule"
    if args.json:
        print(json.dumps(items, ensure_ascii=False, indent=2))
    elif not items:
        print("no scheduled tasks")
    for item in items if not args.json else []:
        first = item["prompt"].splitlines()[0][:80] if item["prompt"] else ""
        print(f"{item['name']} | next {item['next']} | cron '{item['cron']}' on {item['timezone']} | {first}")
    return 0


def cmd_show(args) -> int:
    path = path_for(args.name)
    if not path.exists():
        raise SystemExit(f"task: no task named {args.name!r} (see: task list)")
    item = read_task(path)
    print(f"name: {item['name']}\ncron: {item['cron']}\ntimezone: {item['timezone']}\n\n{item['prompt']}")
    return 0


def cmd_rm(args) -> int:
    path = path_for(args.name)
    if not path.exists():
        raise SystemExit(f"task: no task named {args.name!r} (see: task list)")
    path.unlink()
    print(f"removed {path.stem}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="task", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="cmd", required=True)
    add = sub.add_parser("add", help="create or replace a task")
    add.add_argument("name")
    add.add_argument("--in", dest="in_", help="once, after 5m, 2h, 1d")
    add.add_argument("--at", help="once, at 'HH:MM <zone>' or 'YYYY-MM-DD HH:MM <zone>'")
    add.add_argument("--cron", help="5-field cron")
    add.add_argument("--every", help="recurring: 1m, 5m, 30m, 2h, 1d")
    add.add_argument("--timezone", default="UTC", help="the clock --cron and --every run on")
    add.add_argument("--prompt", help="what to do; - reads stdin")
    add.set_defaults(func=cmd_add)
    listing = sub.add_parser("list", help="the tasks and when each runs next")
    listing.add_argument("-j", "--json", action="store_true")
    listing.set_defaults(func=cmd_list)
    show = sub.add_parser("show", help="one task in full")
    show.add_argument("name")
    show.set_defaults(func=cmd_show)
    rm = sub.add_parser("rm", help="delete a task")
    rm.add_argument("name")
    rm.set_defaults(func=cmd_rm)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
