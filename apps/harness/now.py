#!/usr/bin/env python3
"""now — the time on the workspace clock (UTC) and on the user's (USER_TIMEZONE).

The container runs in UTC, so `date` alone is the wrong clock to tell a user. This prints
both, with the offset, so nobody converts in their head.
"""

import os
import sys
from datetime import datetime, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

utc = datetime.now(timezone.utc)
line = f"UTC {utc:%a %d %b %Y %H:%M}"
name = os.environ.get("USER_TIMEZONE", "").strip()
if not name:
    print(f"{line} | the user's time zone is unknown: USER_TIMEZONE is not set")
    sys.exit(0)
try:
    local = utc.astimezone(ZoneInfo(name))
except (ZoneInfoNotFoundError, ValueError):
    print(f"{line} | USER_TIMEZONE={name!r} is not a time zone name such as Europe/Sofia")
    sys.exit(1)
minutes = int(local.utcoffset().total_seconds()) // 60
hours, rest = divmod(abs(minutes), 60)
offset = f"UTC{'-' if minutes < 0 else '+'}{hours}" + (f":{rest:02d}" if rest else "")
print(f"{line} | {name} {local:%a %d %b %Y %H:%M} {local:%Z} ({offset})")
