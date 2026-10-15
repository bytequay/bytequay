#!/usr/bin/env python3
"""Watch what ByteQuay takes from this Mac: for each part of the app its memory, CPU, threads, open files and processes,
the agent turns and tasks it has open, and the disk its data fills and who holds it. `--sample` keeps the numbers in the
lab's folder, and the Resources page of the dashboard shows them live and over time. Run with no option, it prints how the
parts stand now."""

from __future__ import annotations

import argparse
from array import array
from collections import Counter, defaultdict
import contextlib
from datetime import datetime
import fcntl
import os
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import threading
import time
from urllib.parse import quote

import psutil

import batch
import saved

APP_DATA = Path.home() / "Library/Application Support/ByteQuay"
DATABASE = APP_DATA / "bytequay-1.0.db"
STORE = "monitor.db"
AGENT = "com.bytequay.lab.monitor"
EVERY = 5  # seconds between two samples
DISK_EVERY = 6 * 3600  # a disk count walks every file of the app's data, so it runs only a few times a day
RAW = 7 * 86400  # seconds every sample is kept; older ones are folded into one row an hour
HOURLY = 90 * 86400  # seconds an hourly row is kept
PARTS = ("app", "window", "gpu", "helpers", "backend", "agents", "commands", "monitor")
AMOUNTS = ("processes", "memory", "cpu", "threads", "files")
AGENTS = ("codex", "claude")
# What TurnState.active() and QueuedAgentInput.State.live() hold in the backend; a test reads them from its source.
ACTIVE_TURNS = ("ACCEPTED", "RUNNING")
LIVE_INPUTS = ("QUEUED", "PAUSED", "STARTING")
# Who holds the app's data on disk, in the order the page lists them. SHARED is a block that copy-on-write copies of
# different owners share, so it goes only when all of them go. NOT_KNOWN is task data while the database cannot be read.
OPEN, IDLE, FINISHED, REMOVED, RETIRED, COPIES, REPOSITORIES, APP, SHARED, NOT_KNOWN = OWNERS = (
    "open tasks", "open tasks idle a week", "finished tasks", "removed tasks", "retired features", "old database copies", "repositories",
    "the app itself", "shared", "tasks not known")
FREEABLE = (REMOVED, RETIRED)  # nothing in the app uses these any more
IDLE_AFTER = 7 * 86400  # seconds an open task goes unchanged before it counts as idle
# What features the app no longer has left behind: the code graph's index in the runtime, the old development flow's
# worktrees, and the repository clones of the layout before workspaces, which nothing reads since the workspaces came.
CODE_INDEX = {"codegraph", "codegraph-home", "codegraph.head", "codegraph-baselines"}
RETIRED_FOLDERS = {"new-flow-worktrees", "repos"}
COPIES_NAMED = ("database-backup", "backup-before", "bytequay.db")  # copies made before past changes, and the database's old name
# Besides tasks, the operations whose rows name a worktree folder; such a worktree is the app's own while the row is there.
# (A CI fix's worktree column holds the id of its workspace.) A test checks that no other table names a worktree.
WORKTREES = ("branch_rebase", "cherry_pick_execution")
BIG = 64 * 1024  # from this size up a file is mapped onto the disk, so a block that copies share counts once
LOG2PHYS_EXT = 65  # F_LOG2PHYS_EXT: where on the disk a stretch of a file lies
SCHEMA = """
CREATE TABLE IF NOT EXISTS parts (at REAL, seconds REAL, part TEXT, processes REAL, memory REAL, memory_peak REAL, cpu REAL, cpu_peak REAL, threads REAL, files REAL);
CREATE INDEX IF NOT EXISTS parts_at ON parts (at);
CREATE TABLE IF NOT EXISTS machine (at REAL, seconds REAL, memory_used REAL, memory_total REAL, cpu REAL, turns REAL, tasks REAL, queued REAL);
CREATE INDEX IF NOT EXISTS machine_at ON machine (at);
CREATE TABLE IF NOT EXISTS space (at REAL, owner TEXT, place TEXT, bytes REAL, files REAL);
"""


def is_app(program, arguments):
    """The app itself: the packaged ByteQuay, or Electron started from a ByteQuay checkout. A browser test's Electron runs a
    script of its own, never "."."""
    return program.endswith("/ByteQuay.app/Contents/MacOS/ByteQuay") or (
        "/bytequay/" in program.lower() and program.endswith("/Electron.app/Contents/MacOS/Electron") and arguments[1:] == ["."])


def is_backend(arguments):
    return any(word.endswith("bytequay-backend.jar") for word in arguments)


def kind(program, arguments):
    """The part of a process that ByteQuay started."""
    flag = next((word for word in arguments if word.startswith("--type=")), None)
    if flag or "crashpad" in Path(program).name:
        return {"--type=renderer": "window", "--type=gpu-process": "gpu"}.get(flag, "helpers")
    if is_backend(arguments):
        return "backend"
    return "agents" if {Path(word).name for word in [program, *arguments[:2]]} & set(AGENTS) else "commands"


def assign(parents, programs):
    """{pid: part} for every process of ByteQuay: the app and its helpers, the backend, and all that they started.
    `parents` maps every pid to its parent's, `programs` every pid to its program and arguments."""
    children = defaultdict(list)
    for pid, parent in parents.items():
        children[parent].append(pid)
    made = {pid: "app" for pid, (program, arguments) in programs.items() if is_app(program, arguments)}
    made |= {pid: "backend" for pid, (_, arguments) in programs.items() if is_backend(arguments)}
    waiting = list(made)
    while waiting:
        for child in children[waiting.pop()]:
            if child not in made:
                made[child] = kind(*programs.get(child, ("", [])))
                waiting.append(child)
    return made


def program(pid):
    """A process's program and arguments, or nothing when they cannot be read: it ended, or belongs to another user. They
    only tell the parts apart and are never kept."""
    with contextlib.suppress(psutil.Error):
        process = psutil.Process(pid)
        return process.exe(), process.cmdline()
    return "", []


def records(path):
    """How many agent turns ByteQuay has running, its open tasks and the inputs waiting in their queues, read without
    writing. None for each when the database cannot be read."""
    try:
        with contextlib.closing(sqlite3.connect(f"file:{quote(str(path))}?mode=ro", uri=True, timeout=1)) as connection:
            return dict(zip(("turns", "tasks", "queued"), connection.execute(
                f"SELECT (SELECT COUNT(*) FROM agent_turn WHERE state IN {ACTIVE_TURNS}), (SELECT COUNT(*) FROM agent_task WHERE state = 'OPEN'), "
                f"(SELECT COUNT(*) FROM agent_queued_input WHERE state IN {LIVE_INPUTS})").fetchone()))
    except sqlite3.Error:
        return dict.fromkeys(("turns", "tasks", "queued"))


class Watch:
    """What the sampler remembers from one sample to the next: the program of every process it has seen, read again only
    when it changes, and the processes of ByteQuay, whose CPU is counted between two samples."""

    def __init__(self, database=DATABASE):
        self.database, self.programs, self.counted = database, {}, {}

    def parts(self):
        seen = {process.pid: (process.info["ppid"], process.info["exe"] or "") for process in psutil.process_iter(["ppid", "exe"])}
        for gone in self.programs.keys() - seen.keys():
            del self.programs[gone]
        for pid, (_, executable) in seen.items():
            # A process can turn into another program and keep its pid: dev.sh's copy of itself runs the build and only then
            # becomes the backend. The executable comes with the parent at no extra cost, so a change is noticed at once.
            if pid not in self.programs or self.programs[pid][0] != executable:
                self.programs[pid] = program(pid)
        parents = {pid: parent for pid, (parent, _) in seen.items()}
        # Its own cost is counted apart from the app's, with the disk count it runs as a process of its own.
        return assign(parents, self.programs) | {pid: "monitor" for pid, parent in parents.items() if parent == os.getpid()} | {os.getpid(): "monitor"}

    def sample(self):
        """The numbers of every part, of the machine, and of ByteQuay's records at one moment. CPU is in cores: 1.0 is one
        core kept busy since the sample before."""
        made, totals = self.parts(), {part: dict.fromkeys(AMOUNTS, 0.0) for part in PARTS}
        for pid, part in made.items():
            try:
                process = self.counted.get(pid) or self.counted.setdefault(pid, psutil.Process(pid))
                with process.oneshot():
                    numbers = (1, process.memory_info().rss, process.cpu_percent(None) / 100, process.num_threads(), process.num_fds())
            except psutil.Error:
                continue  # it ended between the two looks
            for amount, number in zip(AMOUNTS, numbers):
                totals[part][amount] += number
        for gone in self.counted.keys() - made.keys():
            del self.counted[gone]
        memory = psutil.virtual_memory()
        machine = {"memory_used": memory.total - memory.available, "memory_total": memory.total, "cpu": psutil.cpu_percent(None) / 100 * psutil.cpu_count()}
        return totals, machine | records(self.database)


def store(folder):
    folder.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(folder / STORE, timeout=10)
    connection.execute("PRAGMA journal_mode=WAL")  # the page reads while the sampler writes
    connection.executescript(SCHEMA)
    return connection


def keep(connection, now, totals, machine):
    connection.executemany("INSERT INTO parts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                           [(now, EVERY, part, made["processes"], made["memory"], made["memory"], made["cpu"], made["cpu"], made["threads"], made["files"]) for part, made in totals.items()])
    connection.execute("INSERT INTO machine VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                       (now, EVERY, *(machine[name] for name in ("memory_used", "memory_total", "cpu", "turns", "tasks", "queued"))))
    connection.commit()


def fold(connection, now):
    """Fold the samples older than a week into one row an hour, with the hour's average and peak, and forget rows older
    than 90 days. Whole hours only, so an hour is never folded twice."""
    edge = (now - RAW) // 3600 * 3600
    connection.execute("INSERT INTO parts SELECT CAST(at / 3600 AS INTEGER) * 3600, 3600, part, AVG(processes), AVG(memory), MAX(memory_peak), AVG(cpu), MAX(cpu_peak), "
                       "AVG(threads), AVG(files) FROM parts WHERE seconds < 3600 AND at < ? GROUP BY CAST(at / 3600 AS INTEGER), part", (edge,))
    connection.execute("INSERT INTO machine SELECT CAST(at / 3600 AS INTEGER) * 3600, 3600, AVG(memory_used), AVG(memory_total), AVG(cpu), AVG(turns), AVG(tasks), AVG(queued) "
                       "FROM machine WHERE seconds < 3600 AND at < ? GROUP BY CAST(at / 3600 AS INTEGER)", (edge,))
    for table in ("parts", "machine"):
        connection.execute(f"DELETE FROM {table} WHERE seconds < 3600 AND at < ?", (edge,))
    for table in ("parts", "machine", "space"):
        connection.execute(f"DELETE FROM {table} WHERE at < ?", (now - HOURLY,))
    connection.commit()


def holders(path, now):
    """From ByteQuay's database, read without writing: the owner of every task, by its id, and of every worktree a row names,
    by the worktree folder's name. None when the database cannot be read."""
    try:
        with contextlib.closing(sqlite3.connect(f"file:{quote(str(path))}?mode=ro", uri=True, timeout=3)) as connection:
            tasks, worktrees = {}, {}
            for task, state, changed, worktree in connection.execute("SELECT id, state, updated_at, worktree FROM agent_task"):
                idle = changed and now - datetime.fromisoformat(changed).timestamp() >= IDLE_AFTER
                tasks[task] = FINISHED if state != "OPEN" else IDLE if idle else OPEN
                if worktree:
                    worktrees[Path(worktree).name] = tasks[task]
            for table in WORKTREES:
                worktrees |= {Path(worktree).name: APP for (worktree,) in connection.execute(f"SELECT worktree FROM {table} WHERE worktree IS NOT NULL")}
            return tasks, worktrees
    except (sqlite3.Error, ValueError, TypeError):  # TypeError: a time stored in another shape than the text it is today
        return None


def owner(names, known):
    """Who holds what lies at a path of the app's data, from the path's first names; None while they do not tell yet.
    `known` is what `holders` read."""
    tasks, worktrees = known or ({}, {})
    of = lambda name, owners: owners.get(name, REMOVED) if known else NOT_KNOWN
    place, depth = names[0], len(names)
    if place == "agent-runtime":
        if depth < 2 or (depth < 3 and names[1] == "ide"):
            return None
        if names[1] in CODE_INDEX:
            return RETIRED
        if names[1] == "ide":
            return of(names[2], tasks)
        if len(names[1]) == 36:  # a task's own folder, named by its id
            return None if depth < 3 else RETIRED if names[2] in CODE_INDEX else of(names[1], tasks)
        return APP
    if place == "workspaces":
        if depth < 3 or (depth < 4 and names[2] == "worktrees"):
            return None
        return REPOSITORIES if names[2] == "repository" else of(names[3], worktrees) if names[2] == "worktrees" else APP
    if place in RETIRED_FOLDERS:
        return RETIRED
    # ponytail: a copy is known by its name; the app has no record of the copies made before past changes
    return COPIES if place.startswith(COPIES_NAMED) else APP


def stretches(path, size):
    """Where each stretch of a file lies on the disk, as (start, length) with neighbours joined, read from the file system
    without reading the file. Fails with OSError when the file system cannot tell."""
    found, offset, descriptor = [], 0, os.open(path, os.O_RDONLY)
    try:
        while offset < size:
            _, length, start = struct.unpack("=Iqq", fcntl.fcntl(descriptor, LOG2PHYS_EXT, struct.pack("=Iqq", 0, size - offset, offset)))
            if length <= 0:
                break
            if found and sum(found[-1]) == start:
                found[-1] = (found[-1][0], found[-1][1] + length)
            else:
                found.append((start, length))
            offset += length
    finally:
        os.close(descriptor)
    return found


def shares(starts, lengths, marks, labels):
    """Bytes of each label from the stretches files of that label take on the disk: a byte one label holds is its own, a
    byte two or more hold is SHARED, in their place when they share one. One pass over the stretches sorted by start."""
    import numpy  # only a disk count needs it, and it runs in a process of its own
    begin = numpy.frombuffer(starts, dtype=numpy.int64)
    edges = numpy.concatenate([begin, begin + numpy.frombuffer(lengths, dtype=numpy.int64)])
    order = numpy.argsort(edges, kind="stable")
    edges = edges[order]
    steps = numpy.concatenate([numpy.ones(len(begin), numpy.int32), numpy.full(len(begin), -1, numpy.int32)])[order]
    marked = numpy.tile(numpy.frombuffer(marks, dtype=numpy.uint16), 2)[order]
    # ponytail: one bit per label in an int64, so up to 63 labels (the app's data has about 40), and a few arrays as long as
    # all the stretches: about 350 MB for 2.4 million for a minute. Join each label's own stretches first if that grows.
    holding = numpy.zeros(len(edges), dtype=numpy.int64)
    for mark in range(len(labels)):
        holding |= (numpy.cumsum(numpy.where(marked == mark, steps, 0)) > 0).astype(numpy.int64) << mark
    held, named = Counter(), {mark: label for label, mark in labels.items()}
    found, where = numpy.unique(holding[:-1], return_inverse=True)  # each stretch between two edges, by who holds it
    for mask, size in zip(found.tolist(), numpy.bincount(where, weights=numpy.diff(edges)).tolist()):
        if not size:
            continue  # where one stretch ends just as another begins, both hold that edge and no byte
        owning = [named[mark] for mark in range(len(labels)) if mask >> mark & 1]
        if len(owning) == 1:
            held[owning[0]] += size
        elif owning:
            places = {place for _, place in owning}
            held[(SHARED, places.pop() if len(places) == 1 else "several places")] += size
    return held


def space(data, known):
    """What the app's data takes on disk, as (owner, place, bytes, files): the place is its top-level folder. A file of
    BIG or more is mapped onto the disk, so a block that copy-on-write copies share is counted once."""
    held, files, seen = Counter(), Counter(), set()
    labels, starts, lengths, marks = {}, array("q"), array("q"), array("H")
    waiting = [(data, (), None)]
    while waiting:
        folder, names, label = waiting.pop()
        try:
            entries = list(os.scandir(folder))
        except OSError:
            continue  # it went away while it was counted
        for entry in entries:
            found = (*names, entry.name)
            mine = label or ((who, found[0]) if (who := owner(found, known)) else None)
            try:
                if entry.is_dir(follow_symlinks=False):
                    waiting.append((entry.path, found, mine))
                    continue
                if not entry.is_file(follow_symlinks=False):
                    continue
                facts = entry.stat(follow_symlinks=False)
            except OSError:
                continue
            if facts.st_nlink > 1:  # a file with more than one name is counted once
                if (facts.st_dev, facts.st_ino) in seen:
                    continue
                seen.add((facts.st_dev, facts.st_ino))
            mine = mine or (APP, found[0])
            files[mine] += 1
            try:
                pieces = stretches(entry.path, facts.st_size) if facts.st_size >= BIG else []
            except OSError:
                pieces = []
            if not pieces:  # a small file, or one the file system cannot map: its blocks, taken as its own
                held[mine] += facts.st_blocks * 512
                continue
            mark = labels.setdefault(mine, len(labels))
            for start, length in pieces:
                starts.append(start)
                lengths.append(length)
                marks.append(mark)
    if labels:
        held.update(shares(starts, lengths, marks, labels))
    return [(who, place, float(held[(who, place)]), files[(who, place)]) for who, place in sorted(held.keys() | files.keys())]


def count_disk(folder, now, data=APP_DATA, database=DATABASE):
    rows = space(data, holders(database, now))
    with contextlib.closing(store(folder)) as connection:
        connection.executemany("INSERT INTO space VALUES (?, ?, ?, ?, ?)", [(now, *row) for row in rows])
        connection.commit()


def run(folder, watch=None, rounds=None):
    """Sample every few seconds until stopped, or for `rounds` samples. Old samples are folded every hour, and the disk is
    counted a few times a day apart from the samples, as a count takes minutes."""
    connection, watch, folded, counted, done = store(folder), watch or Watch(), 0.0, 0.0, 0
    watch.sample()  # not kept: CPU is counted between two looks, so the first look of every process reads as idle
    time.sleep(EVERY)
    while rounds is None or done < rounds:
        now = time.time()
        keep(connection, now, *watch.sample())
        if now - folded >= 3600:
            fold(connection, now)
            folded = now
        if now - counted >= DISK_EVERY:
            # A count walks millions of files and holds a map of the disk for a while, so it runs as a process of its own at the
            # lowest priority, and the sampler stays small.
            command = ["nice", "-n", "20", sys.executable, str(Path(__file__).resolve()), "--count-disk", "--folder", str(folder)]
            threading.Thread(target=subprocess.run, args=(command,), daemon=True).start()
            counted = now
        done += 1
        if rounds is None or done < rounds:
            time.sleep(max(0.0, EVERY - (time.time() - now)))
    connection.close()


def snapshot(watch):
    """How every part stands now, as lines to print. CPU needs two looks, a second apart."""
    watch.sample()
    time.sleep(1)
    totals, machine = watch.sample()
    lines = [f"{'part':10} {'processes':>9} {'memory MB':>10} {'CPU cores':>9} {'threads':>8} {'open files':>10}"]
    lines += [f"{part:10} {made['processes']:9.0f} {made['memory'] / 2**20:10,.0f} {made['cpu']:9.2f} {made['threads']:8.0f} {made['files']:10.0f}" for part, made in totals.items()]
    known = lambda name: "?" if machine[name] is None else f"{machine[name]:.0f}"
    return [*lines, "", f"Turns running {known('turns')}, open tasks {known('tasks')}, inputs queued {known('queued')}. "
                        f"This Mac: {machine['memory_used'] / 2**30:.1f} of {machine['memory_total'] / 2**30:.0f} GB used, {machine['cpu']:.1f} cores busy."]


def agent(folder):
    """The LaunchAgent that keeps the sampler running: at every login and whenever it stops."""
    return {"ProgramArguments": [sys.executable, str(Path(__file__).resolve()), "--sample", "--folder", str(folder)],
            "RunAtLoad": True, "KeepAlive": True, "ProcessType": "Background",
            "StandardOutPath": str(folder / "monitor.log"), "StandardErrorPath": str(folder / "monitor.log")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, default=saved.FOLDER, help="The lab's folder, where the numbers are kept")
    chosen = parser.add_mutually_exclusive_group()
    chosen.add_argument("--sample", action="store_true", help="Sample every five seconds until stopped (what the LaunchAgent runs)")
    chosen.add_argument("--start", action="store_true", help="Keep the sampler running from now on: at every login and whenever it stops")
    chosen.add_argument("--stop", action="store_true", help="Stop the sampler and remove its LaunchAgent")
    chosen.add_argument("--count-disk", action="store_true", help="Count once who holds the app's data on disk (what the sampler starts a few times a day)")
    args = parser.parse_args()
    if args.sample:
        run(args.folder)
    elif args.count_disk:
        count_disk(args.folder, time.time())
    elif args.start or args.stop:
        try:
            batch.keep_agent(AGENT, args.start and agent(args.folder))
        except ValueError as problem:
            parser.exit(1, f"{problem}\n")
        print(f"The monitor samples every {EVERY} seconds into {args.folder / STORE}, from now on." if args.start else "The monitor is stopped.")
    else:
        print("\n".join(snapshot(Watch())))


if __name__ == "__main__":
    main()
