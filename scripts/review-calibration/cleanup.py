#!/usr/bin/env python3
"""Clean up what nothing in ByteQuay uses any more: what features the app no longer has left behind, and what tasks the
database no longer has left in the runtime and among the worktrees. These are the owners the monitor's disk count calls
freeable, found by the same rules. With no option it lists what would go; `--apply` deletes it. A worktree goes through
git, which refuses one with changes not committed or files it does not track, so those stay; a removed task's event log
stays, as the lab reads it; what changed in the last day stays. The Resources page starts `--apply` when you confirm."""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

import batch
import monitor
import saved

LOG = "events.jsonl"  # a task's event log: small, and the lab's history of the task
QUIET = 86400  # seconds; what changed more recently than this stays, in case something is still making it
STATE = "cleanup.json"
# Why git keeps a worktree, from what it says; anything else is "git refused to remove it". Its own words name paths.
REFUSALS = {"modified or untracked": "it holds changes not committed", "locked": "it is locked"}
# What makes a repository's own folder hold work found nowhere else, asked of git in this order.
UNSAVED = ((("status", "--porcelain"), "it holds changes not committed"), (("log", "--branches", "--not", "--remotes", "--oneline", "-1"), "it has commits on no remote"),
           (("stash", "list"), "it has a stash"))



@dataclass(frozen=True)
class Item:
    owner: str  # monitor.REMOVED or monitor.RETIRED
    path: Path


def place(folder):
    """The clean-up's own folder in the lab's: its lock, its log and how the last one went."""
    return {"folder": folder / "cleanup"}


def newest(path):
    """When a file or a folder last changed, its direct entries included."""
    moments = [path.lstat().st_mtime]
    if path.is_dir() and not path.is_symlink():
        moments += [entry.lstat().st_mtime for entry in path.iterdir()]
    return max(moments)


def plan(data, known, now):
    """What would go, and what nothing uses but stays because it changed in the last day. Each is the first file or folder
    on its path whose owner is freeable by the monitor's rules; `known` is what monitor.holders read."""
    going, recent, waiting = [], [], [(data, ())]
    while waiting:
        folder, names = waiting.pop()
        try:
            entries = sorted(folder.iterdir())
        except OSError:
            continue  # it went away, or it is not a folder
        for entry in entries:
            found = (*names, entry.name)
            who = monitor.owner(found, known)
            if who is None and entry.is_dir() and not entry.is_symlink():
                waiting.append((entry, found))
            elif who in monitor.FREEABLE and entry.name != LOG:
                (recent if newest(entry) > now - QUIET else going).append(Item(who, entry))
    return sorted(going, key=lambda item: item.path), sorted(recent, key=lambda item: item.path)


def repositories_in(path):
    """The git worktrees and repositories at or under a path. The walk does not go into them."""
    found = []
    for top, folders, files in os.walk(path) if path.is_dir() and not path.is_symlink() else ():
        if ".git" in files or ".git" in folders:
            found.append(Path(top))
            folders[:] = []
    return found


def unsaved(repository):
    """Why a repository's own folder holds work found nowhere else, or None when all of it is on a remote: changes not
    committed, commits on no remote branch, a stash, or a worktree made from it that is still there."""
    git = lambda *arguments: subprocess.run(["git", "-C", str(repository), *arguments], capture_output=True, text=True)
    git("worktree", "prune")  # forget the worktrees whose folders are gone already
    for arguments, why in UNSAVED:
        done = git(*arguments)
        if done.returncode:
            return "git cannot read it"
        if done.stdout.strip():
            return why
    listed = git("worktree", "list", "--porcelain")
    if listed.returncode:
        return "git cannot read it"
    return "a worktree made from it is still there" if sum(line.startswith("worktree ") for line in listed.stdout.splitlines()) > 1 else None


def let_go(tree):
    """Let a git worktree or repository go: None when it may be deleted, or is gone already, else why it stays. A worktree is
    removed through git, which refuses one with changes not committed or files it does not track; its branch stays in the
    repository, so no commit is lost. A repository's own folder is kept while it holds work found nowhere else."""
    if (tree / ".git").is_dir():
        return unsaved(tree)
    common = subprocess.run(["git", "-C", str(tree), "rev-parse", "--path-format=absolute", "--git-common-dir"], capture_output=True, text=True)
    if common.returncode:
        return "git cannot read it"
    done = subprocess.run(["git", "--git-dir", common.stdout.strip(), "worktree", "remove", str(tree)], capture_output=True, text=True)
    if done.returncode == 0:
        return None
    return next((why for words, why in REFUSALS.items() if words in done.stderr), "git refused to remove it")


def writable(function, name, problem):
    """A folder that may not be written to keeps its files: let it be written to, and try once more."""
    if not isinstance(problem, PermissionError):
        raise problem
    os.chmod(os.path.dirname(name), 0o700)
    function(name)


def clear(path, kept):
    """Delete a file or a folder, except the worktrees in `kept` and the folders that lead to them."""
    if path in kept or not (path.exists() or path.is_symlink()):
        return
    if any(path in tree.parents for tree in kept):
        for entry in path.iterdir():
            clear(entry, kept)
    elif path.is_dir() and not path.is_symlink():
        shutil.rmtree(path, onexc=writable)
    else:
        path.unlink()


def remove(path):
    """Delete a file or a folder, its git worktrees through git first and then its repositories, each only once all its
    work is found elsewhere. Returns {worktree or repository: why it stays}."""
    kept = {}
    for tree in sorted(repositories_in(path), key=lambda tree: (tree / ".git").is_dir()):  # a repository goes after the worktrees made from it
        why = let_go(tree)
        if why:
            kept[tree] = why
    clear(path, kept)
    return kept


def apply(folder, data=monitor.APP_DATA, database=monitor.DATABASE, clock=time.time):
    """Delete what the plan names now, from the database as it reads now, and keep how it goes in the clean-up's folder
    for the Resources page. Then count the disk again, so the page shows what is left."""
    own = place(folder)["folder"]
    going, _ = plan(data, monitor.holders(database, clock()), clock())
    made = {"version": 1, "state": "running", "started": saved.stamp(clock()), "ended": None, "items": len(going), "done": 0,
            "removed": dict.fromkeys(monitor.FREEABLE, 0), "kept": [], "freeBefore": shutil.disk_usage(data).free, "freeAfter": None, "problem": None}
    saved.write(own, STATE, made)
    try:
        for item in going:
            try:
                kept = remove(item.path)
            except OSError as failed:
                kept = {item.path: f"it could not be deleted: {failed.strerror or type(failed).__name__}"}
            made["done"] += 1
            made["removed"][item.owner] += not kept
            made["kept"] += [{"what": tree.relative_to(data).as_posix(), "owner": item.owner, "why": why} for tree, why in kept.items()]
            saved.write(own, STATE, made)
        made["freeAfter"], made["state"] = shutil.disk_usage(data).free, "counting"
        saved.write(own, STATE, made)
        monitor.count_disk(folder, clock(), data, database)
        made["state"] = "done"
    except (KeyboardInterrupt, SystemExit):
        made["state"] = "stopped"
        raise
    except Exception as failed:
        made["state"], made["problem"] = "failed", f"{type(failed).__name__}: {failed}"[:300]
        raise
    finally:
        made["ended"] = saved.stamp(clock())
        saved.write(own, STATE, made)
    return made


def start(folder):
    """Clean up apart from the page, as `uv run cleanup.py --apply` would, and wait until it has claimed the clean-up or
    ended. What it prints goes to cleanup.log in its folder."""
    own = place(folder)
    own["folder"].mkdir(parents=True, exist_ok=True)
    with (own["folder"] / "cleanup.log").open("a") as log:
        process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--apply", "--folder", str(folder)],
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    for _ in range(100):
        if batch.runner(own) is not None or process.poll() is not None:
            break
        time.sleep(0.05)
    return process


def tally(data, items):
    """How many places of each owner would go from each top-level folder of the app's data."""
    return Counter((item.owner, item.path.relative_to(data).parts[0]) for item in items)


def listing(data, going, recent):
    """What `cleanup.py` with no option prints."""
    if not going:
        return ["Nothing to clean up." + (f" {len(recent)} places nothing uses changed in the last day and stay." if recent else "")]
    return [f"Would delete {len(going)} places nothing in ByteQuay uses:",
            *(f"  {owner} in {top}: {amount}" for (owner, top), amount in sorted(tally(data, going).items())),
            "Worktrees go through git, which keeps any with changes not committed; a repository stays while it has work found nowhere else. "
            "Event logs of removed tasks stay.",
            *([f"{len(recent)} more changed in the last day and stay."] if recent else []), "Run again with --apply to delete them."]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, default=saved.FOLDER, help="The lab's folder, where how a clean-up went is kept")
    parser.add_argument("--data", type=Path, default=monitor.APP_DATA, help="ByteQuay's data folder")
    parser.add_argument("--database", type=Path, help="ByteQuay's database, read and never written; by default the one in the data folder")
    parser.add_argument("--apply", action="store_true", help="Delete what is listed; without it nothing is deleted")
    args = parser.parse_args()
    database = args.database or args.data / monitor.DATABASE.name
    if not args.apply:
        print("\n".join(listing(args.data, *plan(args.data, monitor.holders(database, time.time()), time.time()))))
        return
    signal.signal(signal.SIGTERM, batch.stopped)
    own = place(args.folder)
    own["folder"].mkdir(parents=True, exist_ok=True)
    try:
        held = batch.claim(own)
    except ValueError as problem:
        parser.exit(1, f"{problem}\n")
    try:
        made = apply(args.folder, args.data, database)
    finally:
        (own["folder"] / "running.pid").unlink(missing_ok=True)  # before the lock goes, so nobody takes the lock of a name that is going
        held.close()
    print(f"Deleted {sum(made['removed'].values())} of {made['items']} places; {len(made['kept'])} worktrees or folders stay.")


if __name__ == "__main__":
    main()
