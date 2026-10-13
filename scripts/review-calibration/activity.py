#!/usr/bin/env python3
"""Show where the wall-clock time of ByteQuay agent turns goes, read from the task event logs."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex

ENDED = ("COMPLETED", "FAILED")
TURN_ENDED = ("TURN_COMPLETED", "INTERRUPTED", "TASK_FAILED")
WRITING_GAP = 3  # seconds; pieces of visible model text this close together are one run of writing
SILENT = "model silent"

YOU, BUILDS, SHELL, READING, BYTEQUAY, SUBAGENTS, COMPACTION, OTHER_TOOLS, WRITING = (
    "waiting for you", "tests and builds", "other shell commands", "reading and searching files", "ByteQuay tools", "subagents", "context compaction",
    "other tools", "model writing")
# When several things run at once, each second is counted under the first of these that is running.
ORDER = [YOU, BUILDS, SHELL, READING, BYTEQUAY, SUBAGENTS, COMPACTION, OTHER_TOOLS, WRITING]

BUILD_PROGRAMS = {"maven", "gradle", "npm", "make", "cargo", "go", "javac", "pytest"}
SAME_PROGRAM = {"mvn": "maven", "mvnw": "maven", "gradlew": "gradle", "npx": "npm", "pnpm": "npm", "yarn": "npm", "vitest": "npm", "tsc": "npm", "eslint": "npm",
                "python3": "python", "uv": "python", "sleep": "wait loop", "bash": "shell script", "zsh": "shell script", "sh": "shell script"}
# Words that come before the program of a simple command, and words that start a simple command without running a program.
BEFORE_PROGRAM = {"time", "timeout", "sudo", "env", "nohup", "exec", "command", "do", "then", "else", "elif", "if", "while", "until", "!"}
NO_PROGRAM = {"cd", "export", "set", "unset", "source", ".", "for", "case", "select", "function", "done", "fi", "esac", "}", ")", "return", "exit", "local", "wait"}
# Programs that only set the stage; a command line is named after one of these only when it runs nothing else.
SETUP_PROGRAMS = {"echo", "printf", "true", "test", "read", "mkdir", "pwd", "date"}
QUOTED = re.compile(r"'[^']*'|\"(?:\\.|[^\"\\])*\"|\$\([^()]*\)|`[^`]*`", re.DOTALL)
BREAK = re.compile(r"&&|\|\||[;|\n]")
SETTING = re.compile(r"[A-Za-z_]\w*=")
PLAIN_NAME = re.compile(r"[A-Za-z][\w.+-]{0,19}")
READ_TOOLS = {"Read", "Grep", "Glob", "LS", "NotebookRead", "code_query", "codegraph_explore"}
SUBAGENT_TOOLS = {"Task", "Agent", "TaskOutput", "TaskStop", "SubagentHandback", "wait"}
BYTEQUAY_TOOL = re.compile(r"review_|repository_|report_|bytequay_|work_item|read_pull_request|read_issue|propose_ci|create_draft_pull_request|\w+_local_review")
# Every second of a turn is in exactly one of these three states.
STATES = (("The model is working", (SILENT, WRITING)),
          ("Waiting for a tool to finish", (BUILDS, SHELL, READING, BYTEQUAY, SUBAGENTS, COMPACTION, OTHER_TOOLS)),
          ("Waiting for you", (YOU,)))
RUNTIME = Path.home() / "Library/Application Support/ByteQuay/agent-runtime"


@dataclass(frozen=True)
class Item:
    """One thing that ran in a turn: a command, a tool call, an approval wait, or a run of visible model text."""

    kind: str
    turn: str
    start: float
    end: float
    detail: dict


@dataclass(frozen=True)
class Stretch:
    """A piece of a turn during which the same things ran. Its seconds are counted once, under `group`."""

    turn: str
    start: float
    end: float
    group: str
    running: tuple
    counts: tuple  # how many items of each running group, in the same order


def seconds(text):
    return datetime.fromisoformat(text).timestamp()


def read(path):
    """The events of one task log in file order, and how many lines were cut off or in the old format."""
    events, skipped = [], 0
    with path.open() as lines:
        for line in lines:
            try:
                event = json.loads(line)
            except ValueError:
                event = None
            if isinstance(event, dict) and isinstance(event.get("payload"), dict) and "occurredAt" in event:
                events.append(event)
            else:
                skipped += 1
    return events, skipped


def items(events):
    """Turn windows, finished items, and the items that never reported an end, for one task log."""
    windows, last, running, finished, writing = {}, {}, {}, [], {}
    for event in events:
        when, turn, kind, payload = seconds(event["occurredAt"]), event.get("turnId"), event["type"], event["payload"]
        last[turn] = max(when, last.get(turn, when))
        if kind == "lifecycle":
            if payload["phase"] == "TURN_STARTED":
                windows[turn] = (when, None)
            elif payload["phase"] in TURN_ENDED and turn in windows and windows[turn][1] is None:
                windows[turn] = (windows[turn][0], when)
        elif kind in ("command", "tool"):
            # Two tools with one name can run at once; the provider's item id tells them apart when the log has one.
            key = (kind, (event.get("source") or {}).get("providerItemId") or payload.get("name") or " ".join(map(str, payload.get("argv") or [])))
            if payload["state"] == "STARTED":
                running[key] = Item(kind, turn, when, when, payload)
            elif payload["state"] in ENDED:
                started = running.pop(key, None)
                if started:
                    finished.append(replace(started, end=when))
                elif payload.get("durationMs"):
                    finished.append(Item(kind, turn, when - payload["durationMs"] / 1000, when, payload))
        elif kind == "approval_requested":
            running[("approval", payload["approvalId"])] = Item("approval", turn, when, when, payload)
        elif kind == "approval_resolved":
            started = running.pop(("approval", payload["approvalId"]), None)
            if started:
                finished.append(replace(started, end=when))
        elif kind == "assistant_message" and payload["phase"] == "PARTIAL":
            current = writing.get(turn)
            if current and when - current.end <= WRITING_GAP:
                writing[turn] = replace(current, end=when)
            else:
                if current:
                    finished.append(current)
                writing[turn] = Item("text", turn, when, when, {})
    finished.extend(writing.values())
    # A turn with no end mark, such as one still running, ends at its last event.
    windows = {turn: (start, last[turn] if end is None else end) for turn, (start, end) in windows.items()}
    return windows, finished, list(running.values())


def stretches(windows, finished, group_of, order):
    """Cut each turn at every start and end. A piece is counted under the first group in `order` that was running,
    or under the silent model when nothing was."""
    by_turn = defaultdict(list)
    for item in finished:
        by_turn[item.turn].append(item)
    made = []
    for turn, (begin, end) in windows.items():
        changes = defaultdict(Counter)
        for item in by_turn[turn]:
            start, stop = max(item.start, begin), min(item.end, end)  # only the part inside the turn counts
            if stop > start:
                group = group_of(item)
                changes[start][group] += 1
                changes[stop][group] -= 1
        active, edges = Counter(), sorted({begin, end, *changes})
        for left, right in zip(edges, edges[1:]):
            active.update(changes.get(left, {}))
            running = sorted((group for group, count in active.items() if count > 0), key=order.index)
            made.append(Stretch(turn, left, right, running[0] if running else SILENT, tuple(running), tuple(active[group] for group in running)))
    return made


def wraps(parts):
    return len(parts) == 3 and parts[0].rsplit("/", 1)[-1] in ("zsh", "bash", "sh") and parts[1] in ("-lc", "-c")


def command_text(detail):
    """The command line of a command event, without a shell that only wraps it."""
    parts = [str(part) for part in detail.get("argv") or []]
    while True:
        if len(parts) == 1:
            # One string can be a whole quoted line such as: /bin/zsh -lc "./mvnw test "'-Dtest=Foo'
            try:
                split = shlex.split(parts[0])
            except ValueError:  # an unclosed quote; leave the line as it is
                split = []
            parts = split if wraps(split) else parts
        if not wraps(parts):
            return " ".join(parts)
        parts = [parts[2]]


def programs(text):
    """The programs a command line starts, in order and by plain name. Arguments are never returned."""
    text = text.split("<<", 1)[0]  # what follows a here-document mark is input to the command, not more commands
    # Quotes and $(...) are covered so that a ; or | inside them does not split the line, and so that each stays one word.
    covered = QUOTED.sub(lambda quoted: "x" * len(quoted.group()), text)
    found, start = [], 0
    for cut in [*BREAK.finditer(covered), None]:
        end = cut.start() if cut else len(text)
        words = [text[start + word.start():start + word.end()].lstrip("({") for word in re.finditer(r"\S+", covered[start:end])]
        start = cut.end() if cut else end
        after_prefix = False
        # Skip variable settings, words such as time or sudo, and the options and numbers that belong to those words.
        while words and (not words[0] or SETTING.match(words[0]) or words[0] in BEFORE_PROGRAM or (after_prefix and words[0][0] in "-0123456789")):
            after_prefix = after_prefix or words[0] in BEFORE_PROGRAM
            words.pop(0)
        if not words or words[0] in NO_PROGRAM:
            continue
        # Only the first word is a program: java in src/main/java/Foo.java is part of an argument.
        name = words[0].strip("\"'").rsplit("/", 1)[-1]
        name = "shell script" if name.endswith(".sh") else SAME_PROGRAM.get(name, name)
        if name in SAME_PROGRAM.values() or PLAIN_NAME.fullmatch(name):
            found.append(name)
    return found


def program(text):
    """The one program a command line's time is counted under: a build or test tool if it runs one, then a wait loop,
    then the first program that does real work."""
    found = programs(text)
    for wanted in (lambda name: name in BUILD_PROGRAMS, lambda name: name == "wait loop", lambda name: name not in SETUP_PROGRAMS):
        for name in found:
            if wanted(name):
                return name
    return found[0] if found else "other"


def group_of(item):
    """The group an item's time is counted under."""
    if item.kind == "approval":
        return YOU
    if item.kind == "text":
        return WRITING
    if item.kind == "command":
        if program(command_text(item.detail)) in BUILD_PROGRAMS:
            return BUILDS
        kinds = {action.get("kind") for action in item.detail.get("actions") or []}
        return READING if kinds and kinds <= {"READ", "SEARCH", "LIST_FILES"} else SHELL
    name = item.detail["name"]
    if name.startswith("hook/"):  # a permission hook, not the tool it guards
        return OTHER_TOOLS
    plain = name.rsplit("__", 1)[-1].rsplit("/", 1)[-1]  # mcp__bytequay__review_finish and bytequay/review_finish are both review_finish
    if plain == "AskUserQuestion":
        return YOU
    if plain in READ_TOOLS:
        return READING
    if plain in SUBAGENT_TOOLS:
        return SUBAGENTS
    if name == "Context compaction":
        return COMPACTION
    if plain == "Bash":
        return SHELL
    if "bytequay" in name or BYTEQUAY_TOOL.match(plain):
        return BYTEQUAY
    return OTHER_TOOLS


@dataclass
class Totals:
    """What a set of task logs adds up to. Times are in seconds."""

    tasks: int = 0
    turns: int = 0
    seconds: float = 0.0
    first: float = float("inf")
    last: float = 0.0
    groups: Counter = field(default_factory=Counter)
    after: Counter = field(default_factory=Counter)
    after_times: Counter = field(default_factory=Counter)
    program_seconds: Counter = field(default_factory=Counter)
    program_runs: Counter = field(default_factory=Counter)
    parallel: float = 0.0
    most_at_once: int = 0
    unfinished: int = 0
    skipped: int = 0
    unread: int = 0
    timeline: list = field(default_factory=list)


def measure(logs, since=None):
    """Add up the given task logs. Turns that started before `since` are left out."""
    total, tools = Totals(), set(STATES[1][1])
    for log in logs:
        events, skipped = read(log)
        windows, finished, unfinished = items(events)
        windows = {turn: window for turn, window in windows.items() if since is None or window[0] >= since}
        if not windows:
            total.unread += since is None  # a log with no turn mark at all, as the old format has
            continue
        total.tasks += 1
        total.turns += len(windows)
        total.skipped += skipped
        total.seconds += sum(end - start for start, end in windows.values())
        total.first, total.last = min(total.first, *(start for start, _ in windows.values())), max(total.last, *(end for _, end in windows.values()))
        total.unfinished += sum(item.turn in windows for item in unfinished)
        turn = before = None
        for made in stretches(windows, finished, group_of, ORDER):
            if made.turn != turn:
                turn, before, thinking = made.turn, "at the start of a turn", False
                total.timeline.append((windows[turn], []))
            length = made.end - made.start
            total.groups[made.group] += length
            total.timeline[-1][1].append(made)
            if made.group in STATES[0][1]:
                total.after[before] += length  # model time belongs to whatever finished just before it
                total.after_times[before] += not thinking  # silent and writing pieces in a row are one stretch of model time
                thinking = True
            else:
                before, thinking = "after you answered" if made.group == YOU else "after " + made.group, False
            at_once = sum(number for group, number in zip(made.running, made.counts) if group in tools)
            if at_once >= 2:
                total.parallel += length
                total.most_at_once = max(total.most_at_once, at_once)
        for item in finished:
            if item.kind == "command" and item.turn in windows:
                begin, end = windows[item.turn]
                name = program(command_text(item.detail))
                total.program_seconds[name] += max(0.0, min(item.end, end) - max(item.start, begin))
                total.program_runs[name] += 1
    return total


SHOWN = 3  # seconds; anything shorter would print as 0.0 minutes


def line(label, length, whole, indent=""):
    return f"{indent}{label:<{46 - len(indent)}}{length / 60:>9.1f}{100 * length / whole:>8.1f}%"


def count(number, one, many=None):
    return f"{number} {one if number == 1 else many or one + 's'}"


def day(moment):
    return datetime.fromtimestamp(moment, timezone.utc).strftime("%Y-%m-%d")


def render(total, top=5):
    """The tables. Only group names, program names, counts and times are printed, never a command's arguments or any text."""
    if not total.turns:
        return "No turns found."
    lines = [f"Agent time: {count(total.tasks, 'task')}, {count(total.turns, 'turn')}, {total.seconds / 3600:.1f} hours of turn time, {day(total.first)} to {day(total.last)}", "",
             f"{'What is happening':<46}{'minutes':>9}{'share':>9}"]
    for state, groups in STATES:
        lines.append(line(state, sum(total.groups[group] for group in groups), total.seconds))
        if len(groups) > 1:
            lines += [line(group, total.groups[group], total.seconds, "    ") for group in sorted(groups, key=lambda group: -total.groups[group]) if total.groups[group] >= SHOWN]
    if total.program_runs:
        ranked = total.program_seconds.most_common()
        lines += ["", f"{'Shell time by program, summed over runs':<46}{'minutes':>9}{'runs':>9}"]
        lines += [f"    {name:<42}{length / 60:>9.1f}{total.program_runs[name]:>9}" for name, length in ranked[:top]]
        if ranked[top:]:
            lines.append(f"    {'every other program':<42}{sum(length for _, length in ranked[top:]) / 60:>9.1f}{sum(total.program_runs[name] for name, _ in ranked[top:]):>9}")
    model = sum(total.after.values())
    if model:
        lines += ["", f"{'Model time by what just finished':<46}{'minutes':>9}{'share':>9}{'times':>9}"]
        lines += [line(before, length, model, "    ") + f"{total.after_times[before]:>9}" for before, length in total.after.most_common() if length >= SHOWN]
    lines.append("")
    if total.parallel:
        lines.append(f"Two or more tools ran at once for {total.parallel / 60:.1f} minutes; the most at once was {total.most_at_once}.")
    if total.unfinished:
        lines.append(f"{count(total.unfinished, 'command or tool', 'commands or tools')} never reported an end and {'is' if total.unfinished == 1 else 'are'} not counted.")
    if total.skipped:
        lines.append(f"{count(total.skipped, 'log line')} cut off or in an old format {'was' if total.skipped == 1 else 'were'} skipped.")
    if total.unread:
        lines.append(f"{count(total.unread, 'task log')} with no turn in the current format {'was' if total.unread == 1 else 'were'} left out.")
    return "\n".join(lines).rstrip()


def timeline(total, least=30):
    """Each turn's stretches in order, neighbours of one group joined. Stretches shorter than `least` seconds are only counted."""
    lines = [f"Timeline: stretches of {least} seconds or more, with the time since the turn started"]
    for number, ((begin, end), made) in enumerate(total.timeline, 1):
        lines += ["", f"Turn {number}, {(end - begin) / 60:.1f} minutes"]
        joined = []
        for one in made:
            if joined and joined[-1][2] == one.group:
                joined[-1][1] = one.end
            else:
                joined.append([one.start, one.end, one.group])
        short = [0, 0.0]
        for start, stop, group in [*joined, (end, end + least, None)]:  # the last entry only flushes the short ones before it
            if stop - start >= least:
                if short[0]:
                    lines.append(f"{'':>11}({count(short[0], 'shorter stretch', 'shorter stretches')}, {short[1] / 60:.1f} minutes)")
                    short = [0, 0.0]
                if group:
                    lines.append(f"{int(start - begin) // 60:>6}:{int(start - begin) % 60:02}{(stop - start) / 60:>7.1f} min  {group}")
            else:
                short = [short[0] + 1, short[1] + stop - start]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=RUNTIME, help="Folder with one sub-folder per task, each holding an events.jsonl")
    parser.add_argument("--task", help="Only the task whose id starts with this, with a timeline of its long stretches")
    parser.add_argument("--since", help="Only turns that started on or after this day, YYYY-MM-DD in UTC")
    args = parser.parse_args()
    if args.task and not re.fullmatch(r"[0-9a-fA-F-]+", args.task):
        parser.error("--task takes the start of a task id: letters a to f, digits and dashes")
    try:
        since = datetime.strptime(args.since, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp() if args.since else None
    except ValueError:
        parser.error("--since takes a day as YYYY-MM-DD")
    logs = sorted(args.runtime.glob((args.task or "") + "*/events.jsonl"))
    if not logs:
        parser.error(f"No task log matches under {args.runtime}")
    total = measure(logs, since)
    print(render(total) + ("\n\n" + timeline(total) if args.task and total.turns else ""))


if __name__ == "__main__":
    main()
