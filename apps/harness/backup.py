#!/usr/bin/env python3
"""workspace-backup — packages the workspace's home for the API, and puts one back.

  workspace-backup run       archive the home and upload it; the API starts this
  workspace-backup restore   replace the home with an archive; start-harness runs this

`run` reads its job from ~/.cache/workspaced/backup/job.json ({"id", "uploadUrl"}) and
reports in status.json beside it, which the API reads to show progress:
{"id", "phase": "archiving|uploading|done|failed", "done", "total", "size", "error"}.

`restore` reads WORKSPACED_RESTORE_URL and WORKSPACED_RESTORE_ID, and does nothing when
~/.config/workspaced/restored-from already names that id, so a pod that restarts later
does not restore again.
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import sqlite3
import sys
import tarfile
import time
import urllib.parse
import urllib.request
from pathlib import Path

HOME = Path(os.environ.get("HOME", "/home/workspace"))
WORK = HOME / ".cache" / "workspaced" / "backup"
RESTORE_WORK = HOME / ".cache" / "workspaced" / "restore"
MARKER = HOME / ".config" / "workspaced" / "restored-from"
# Neither belongs to the workspace: one is this script's scratch space, the other says which
# restore produced the home, and a backup restored elsewhere must not carry it along.
EXCLUDED = {WORK, RESTORE_WORK, MARKER}
CHUNK = 1024 * 1024
SQLITE_HEADER = b"SQLite format 3\x00"
REPORT_EVERY = 0.5


class Status:
    """Written whole and renamed into place, so the API never reads half a file."""

    def __init__(self, job_id: str):
        self.job_id = job_id
        self.last = 0.0

    def write(self, phase: str, done: int = 0, total: int = 0, force: bool = False, **extra):
        now = time.monotonic()
        if not force and now - self.last < REPORT_EVERY:
            return
        self.last = now
        tmp = WORK / "status.json.tmp"
        tmp.write_text(
            json.dumps({"id": self.job_id, "phase": phase, "done": done, "total": total, **extra})
        )
        tmp.replace(WORK / "status.json")


def excluded(path: Path) -> bool:
    return any(path == e or e in path.parents for e in EXCLUDED)


def is_sqlite(path: Path) -> bool:
    try:
        with path.open("rb") as f:
            return f.read(len(SQLITE_HEADER)) == SQLITE_HEADER
    except OSError:
        return False


def entries():
    """Every path under the home, parents before children, without following symlinks."""
    for root, dirs, files in os.walk(HOME):
        base = Path(root)
        dirs.sort()
        kept = []
        for name in dirs:
            path = base / name
            if excluded(path):
                continue
            yield path
            # A link to a folder is archived as the link; what it points at is not walked.
            if not path.is_symlink():
                kept.append(name)
        dirs[:] = kept
        for name in sorted(files):
            path = base / name
            if not excluded(path):
                yield path


class Exactly:
    """Reads exactly `size` bytes: a file that grows is cut, one that shrinks is padded,
    since tarfile has already written the size into the header."""

    def __init__(self, f, size: int, progress):
        self.f, self.left, self.progress = f, size, progress

    def read(self, n: int = -1) -> bytes:
        n = self.left if n < 0 else min(n, self.left)
        data = self.f.read(n) if n else b""
        data += b"\0" * (n - len(data))
        self.left -= n
        self.progress(n)
        return data


def run() -> int:
    job = json.loads((WORK / "job.json").read_text())
    status = Status(job["id"])
    archive = WORK / "archive.tar.gz"
    snapshot = WORK / "snapshot.db"
    try:
        paths = list(entries())
        total = sum(p.lstat().st_size for p in paths if p.is_file() and not p.is_symlink())
        done = 0
        status.write("archiving", 0, total, force=True)

        def advance(n: int):
            nonlocal done
            done += n
            status.write("archiving", done, total)

        # A live SQLite database copied byte by byte can be torn mid-transaction; its own
        # backup API gives a consistent copy, which makes the -wal and -shm files redundant.
        databases = {p for p in paths if p.is_file() and not p.is_symlink() and is_sqlite(p)}
        sidecars = {Path(f"{db}{suffix}") for db in databases for suffix in ("-wal", "-shm", "-journal")}
        with tarfile.open(archive, "w:gz", compresslevel=6) as tar:
            for path in paths:
                name = str(path.relative_to(HOME))
                if path in sidecars:
                    advance(path.lstat().st_size)
                    continue
                try:
                    if path in databases:
                        source = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
                        target = sqlite3.connect(snapshot)
                        with source, target:
                            source.backup(target)
                        source.close()
                        target.close()
                        info = tar.gettarinfo(str(path), arcname=name)
                        info.size = snapshot.stat().st_size
                        with snapshot.open("rb") as f:
                            tar.addfile(info, Exactly(f, info.size, lambda _: None))
                        snapshot.unlink()
                        advance(path.lstat().st_size)
                    elif path.is_file() and not path.is_symlink():
                        info = tar.gettarinfo(str(path), arcname=name)
                        with path.open("rb") as f:
                            tar.addfile(info, Exactly(f, info.size, advance))
                    elif path.is_dir() or path.is_symlink():
                        tar.add(str(path), arcname=name, recursive=False)
                    # Sockets and pipes belong to processes that do not outlive a restart.
                except (FileNotFoundError, PermissionError, sqlite3.Error) as e:
                    # Gone since the walk, or not the workspace's to read: the rest still counts.
                    print(f"workspace-backup: skipped {name}: {e}", file=sys.stderr)

        size = archive.stat().st_size
        upload(job["uploadUrl"], archive, size, lambda sent: status.write("uploading", sent, size))
        status.write("done", size, size, force=True, size=size)
        return 0
    except Exception as e:  # noqa: BLE001 — every failure has to reach the API as a status
        status.write("failed", force=True, error=str(e)[:400] or type(e).__name__)
        return 1
    finally:
        archive.unlink(missing_ok=True)
        snapshot.unlink(missing_ok=True)


def upload(url: str, archive: Path, size: int, progress):
    """One PUT with a known length: a presigned URL accepts no chunked body."""
    target = urllib.parse.urlsplit(url)
    connection_type = http.client.HTTPSConnection if target.scheme == "https" else http.client.HTTPConnection
    connection = connection_type(target.netloc, timeout=120)
    path = target.path + ("?" + target.query if target.query else "")
    connection.putrequest("PUT", path, skip_accept_encoding=True)
    connection.putheader("Content-Length", str(size))
    connection.endheaders()
    sent = 0
    with archive.open("rb") as f:
        while chunk := f.read(CHUNK):
            connection.send(chunk)
            sent += len(chunk)
            progress(sent)
    response = connection.getresponse()
    body = response.read(500).decode(errors="replace")
    connection.close()
    if response.status // 100 != 2:
        raise RuntimeError(f"the storage answered {response.status}: {body}")


def wipe(root: Path, keep: Path):
    """Removes everything under root except keep and the folders leading to it."""
    for entry in root.iterdir():
        if entry == keep:
            continue
        if keep.is_relative_to(entry) and entry.is_dir() and not entry.is_symlink():
            wipe(entry, keep)
        elif entry.is_dir() and not entry.is_symlink():
            shutil.rmtree(entry)
        else:
            entry.unlink()


class Progress:
    """One line per second at most on stderr, which the API reads back from the pod's log: the
    restore runs before OpenCode, so there is nothing else to ask."""

    def __init__(self):
        self.last = 0.0

    def report(self, phase: str, done: int, total: int, force: bool = False):
        now = time.monotonic()
        if not force and now - self.last < 1:
            return
        self.last = now
        line = json.dumps({"phase": phase, "done": done, "total": total})
        print(f"workspace-backup progress {line}", file=sys.stderr, flush=True)


def restore() -> int:
    url = os.environ.get("WORKSPACED_RESTORE_URL", "")
    restore_id = os.environ.get("WORKSPACED_RESTORE_ID", "")
    if not url or not restore_id:
        return 0
    if MARKER.is_file() and MARKER.read_text().strip() == restore_id:
        return 0
    RESTORE_WORK.mkdir(parents=True, exist_ok=True)
    archive = RESTORE_WORK / "archive.tar.gz"
    progress = Progress()
    print(f"workspace-backup: restoring {restore_id}", file=sys.stderr, flush=True)
    # Downloaded and read through once before the home is touched, so a broken link or a
    # damaged archive leaves the current files where they are.
    with urllib.request.urlopen(url, timeout=120) as response, archive.open("wb") as f:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        progress.report("downloading", 0, total, force=True)
        while chunk := response.read(CHUNK):
            f.write(chunk)
            done += len(chunk)
            progress.report("downloading", done, total)
    size = archive.stat().st_size
    with tarfile.open(archive, "r:gz") as tar:
        # Reading every header decompresses the whole archive, which is the check; the list it
        # leaves behind is what extraction then counts against.
        raw = tar.fileobj.fileobj
        for _ in tar:
            progress.report("checking", raw.tell(), size)
        members = tar.getmembers()
        progress.report("extracting", 0, len(members), force=True)
        wipe(HOME, RESTORE_WORK)

        def counted():
            for index, member in enumerate(members, 1):
                yield member
                progress.report("extracting", index, len(members))

        tar.extractall(HOME, members=counted(), filter="tar")
    shutil.rmtree(RESTORE_WORK)
    MARKER.parent.mkdir(parents=True, exist_ok=True)
    MARKER.write_text(restore_id + "\n")
    progress.report("done", 1, 1, force=True)
    print(f"workspace-backup: restored {restore_id}", file=sys.stderr, flush=True)
    return 0


def main() -> int:
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "run":
        return run()
    if command == "restore":
        return restore()
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
