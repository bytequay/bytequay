#!/usr/bin/env python3
"""Watch what ByteQuay takes from this Mac: for each part of the app its memory, CPU, threads, open files and processes,
the agent turns and tasks it has open, and the disk its data fills. `--sample` keeps the numbers in the lab's folder, and
the Resources page of the dashboard shows them live and over time. Run with no option, it prints how the parts stand now."""

from __future__ import annotations

import argparse
from collections import defaultdict
import contextlib
import os
from pathlib import Path
import sqlite3
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
SCHEMA = """
CREATE TABLE IF NOT EXISTS parts (at REAL, seconds REAL, part TEXT, processes REAL, memory REAL, memory_peak REAL, cpu REAL, cpu_peak REAL, threads REAL, files REAL);
CREATE INDEX IF NOT EXISTS parts_at ON parts (at);
CREATE TABLE IF NOT EXISTS machine (at REAL, seconds REAL, memory_used REAL, memory_total REAL, cpu REAL, turns REAL, tasks REAL, queued REAL);
CREATE INDEX IF NOT EXISTS machine_at ON machine (at);
CREATE TABLE IF NOT EXISTS disk (at REAL, place TEXT, bytes REAL);
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
    """What the sampler remembers from one sample to the next: the program of every process it has seen, read once, and
    the processes of ByteQuay, whose CPU is counted between two samples."""

    def __init__(self, database=DATABASE):
        self.database, self.programs, self.counted = database, {}, {}

    def parts(self):
        parents = {process.pid: process.info["ppid"] for process in psutil.process_iter(["ppid"])}
        for gone in self.programs.keys() - parents.keys():
            del self.programs[gone]
        for pid in parents.keys() - self.programs.keys():
            self.programs[pid] = program(pid)
        return assign(parents, self.programs) | {os.getpid(): "monitor"}  # its own cost is counted, apart from the app's

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
    for table in ("parts", "machine", "disk"):
        connection.execute(f"DELETE FROM {table} WHERE at < ?", (now - HOURLY,))
    connection.commit()


def disk(data):
    """Bytes on disk of every top-level part of the app's data, counted by `du` at the lowest priority."""
    made = {}
    for place in sorted(data.iterdir()) if data.is_dir() else []:
        counted = subprocess.run(["nice", "-n", "20", "du", "-sk", str(place)], capture_output=True, text=True)
        with contextlib.suppress(ValueError, IndexError):  # a file that went away while it was counted
            made[place.name] = int(counted.stdout.split()[0]) * 1024
    return made


def count_disk(folder, now, data=APP_DATA):
    sizes = disk(data)
    with contextlib.closing(store(folder)) as connection:
        connection.executemany("INSERT INTO disk VALUES (?, ?, ?)", [(now, place, size) for place, size in sizes.items()])
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
            threading.Thread(target=count_disk, args=(folder, now), daemon=True).start()
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, default=saved.FOLDER, help="The lab's folder, where the numbers are kept")
    chosen = parser.add_mutually_exclusive_group()
    chosen.add_argument("--sample", action="store_true", help="Sample every five seconds until stopped (what the LaunchAgent runs)")
    chosen.add_argument("--start", action="store_true", help="Keep the sampler running from now on: at every login and whenever it stops")
    chosen.add_argument("--stop", action="store_true", help="Stop the sampler and remove its LaunchAgent")
    args = parser.parse_args()
    if args.sample:
        run(args.folder)
    elif args.start or args.stop:
        settings = args.start and {"ProgramArguments": [sys.executable, str(Path(__file__).resolve()), "--sample", "--folder", str(args.folder)],
                                   "RunAtLoad": True, "KeepAlive": True, "ProcessType": "Background",
                                   "StandardOutPath": str(args.folder / "monitor.log"), "StandardErrorPath": str(args.folder / "monitor.log")}
        try:
            batch.keep_agent(AGENT, settings)
        except ValueError as problem:
            parser.exit(1, f"{problem}\n")
        print(f"The monitor samples every {EVERY} seconds into {args.folder / STORE}, from now on." if args.start else "The monitor is stopped.")
    else:
        print("\n".join(snapshot(Watch())))


if __name__ == "__main__":
    main()
