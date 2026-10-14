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
import sqlite3

import records
import saved

ENDED = ("COMPLETED", "FAILED")
TURN_ENDED = ("TURN_COMPLETED", "INTERRUPTED", "TASK_FAILED")
WRITING_GAP = 3  # seconds; pieces of visible model text this close together are one run of writing
LONG_RUN = 30  # seconds; a single command this long is kept by itself for the list of long runs
HOW_IT_ENDED = ("state", "exitCode")  # what the end of a command or tool call adds to its start
# A search ends with exit code 1 when it finds nothing, which is an answer and not a failure.
# ponytail: a command line is named after one program, so a search behind another program's pipe is not seen here.
SEARCHES = {"rg", "grep"}
MOST_FAILED = 3  # names listed under commands and under tools in the table of calls
RATE_LIMIT = re.compile(r"rate limit: (\w+)")
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
                    # The start names the call, and only the end says how it went.
                    finished.append(replace(started, end=when, detail={**started.detail, **{name: payload.get(name) for name in HOW_IT_ENDED}}))
                elif payload.get("durationMs"):
                    finished.append(Item(kind, turn, when - payload["durationMs"] / 1000, when, payload))
        elif kind == "approval_requested":
            running[("approval", payload["approvalId"])] = Item("approval", turn, when, when, payload)
        elif kind == "approval_resolved":
            started = running.pop(("approval", payload["approvalId"]), None)
            if started:
                finished.append(replace(started, end=when, detail={**started.detail, "outcome": payload.get("outcome")}))
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


def tool_name(name):
    """A tool's name without its server: mcp__bytequay__review_finish and bytequay/review_finish are both review_finish."""
    return name.rsplit("__", 1)[-1].rsplit("/", 1)[-1]


def call_name(item):
    """The name a command or tool call is counted under, or None for an item that is not a call the agent chose."""
    if item.kind == "command":
        return program(command_text(item.detail))
    if item.kind == "tool" and not item.detail["name"].startswith("hook/"):  # a permission hook guards a call and is not one
        return tool_name(item.detail["name"])
    return None


def ending(item, name):
    """How a command or tool call ended, as (failed, found nothing). A search that found nothing is not a failure."""
    failed = item.detail.get("state") == "FAILED"
    nothing = failed and item.kind == "command" and name in SEARCHES and item.detail.get("exitCode") == 1
    return failed and not nothing, nothing


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
    plain = tool_name(name)
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
class Turn:
    """What one turn adds up to. Times are in seconds."""

    task: str
    turn: str
    start: float
    end: float
    ended: str = ""  # the mark that closed the turn; empty when the log has none, as for a turn that is still running
    groups: Counter = field(default_factory=Counter)
    after: Counter = field(default_factory=Counter)
    after_times: Counter = field(default_factory=Counter)
    program_seconds: Counter = field(default_factory=Counter)
    program_runs: Counter = field(default_factory=Counter)
    parallel: float = 0.0
    most_at_once: int = 0
    unfinished: int = 0
    stretches: list = field(default_factory=list)
    long_runs: list = field(default_factory=list)  # (program, seconds) for every single command of LONG_RUN seconds or more
    approvals: list = field(default_factory=list)  # (kind, outcome, seconds the turn waited for the user)
    notices: Counter = field(default_factory=Counter)  # rate limit notices by their status, and retries
    # For each (command or tool, name): its runs, how many failed, how many searches found nothing, and its seconds.
    calls: defaultdict = field(default_factory=lambda: defaultdict(Counter))


@dataclass
class Totals:
    """What a set of task logs adds up to: the sum of its turns, and what was left out. Times are in seconds."""

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
    calls: defaultdict = field(default_factory=lambda: defaultdict(Counter))
    parallel: float = 0.0
    most_at_once: int = 0
    unfinished: int = 0
    skipped: int = 0
    unread: int = 0
    each: list = field(default_factory=list)  # one Turn per turn, in the order the logs were read


def endings(events):
    """The mark that closed each turn, by the rule `items` closes its window with: the first end mark after the turn started."""
    closed = {}
    for event in events:
        if event["type"] == "lifecycle":
            turn, phase = event.get("turnId"), event["payload"]["phase"]
            if phase == "TURN_STARTED":
                closed[turn] = ""
            elif phase in TURN_ENDED and closed.get(turn) == "":
                closed[turn] = phase
    return closed


def notices(events):
    """For each turn, how often the provider said it was near or over a rate limit, by the status it gave, and how often a call was tried again."""
    counted = defaultdict(Counter)
    for event in events:
        if event["type"] == "lifecycle":
            phase, message = event["payload"]["phase"], str(event["payload"].get("message", ""))
            if phase == "RATE_LIMITED":
                status = RATE_LIMIT.search(message)
                counted[event.get("turnId")]["rate limit: " + (status.group(1).replace("_", " ") if status else "other")] += 1
            elif phase == "RETRYING":
                counted[event.get("turnId")]["retry"] += 1
    return counted


def measure(logs, since=None):
    """Add up the given task logs, one turn at a time. Turns that started before `since` are left out."""
    total, tools = Totals(), set(STATES[1][1])
    for log in logs:
        events, skipped = read(log)
        windows, finished, unfinished = items(events)
        windows = {turn: window for turn, window in windows.items() if since is None or window[0] >= since}
        if not windows:
            total.unread += since is None  # a log with no turn mark at all, as the old format has
            continue
        total.tasks += 1
        total.skipped += skipped
        closed, noticed = endings(events), notices(events)
        turns = {turn: Turn(log.parent.name, turn, begin, end, closed.get(turn, ""), notices=noticed[turn]) for turn, (begin, end) in windows.items()}
        for item in unfinished:
            if item.turn in turns:
                turns[item.turn].unfinished += 1
        one = before = None
        for made in stretches(windows, finished, group_of, ORDER):
            if turns[made.turn] is not one:
                one, before, thinking = turns[made.turn], "at the start of a turn", False
            length = made.end - made.start
            one.groups[made.group] += length
            one.stretches.append(made)
            if made.group in STATES[0][1]:
                one.after[before] += length  # model time belongs to whatever finished just before it
                one.after_times[before] += not thinking  # silent and writing pieces in a row are one stretch of model time
                thinking = True
            else:
                before, thinking = "after you answered" if made.group == YOU else "after " + made.group, False
            at_once = sum(number for group, number in zip(made.running, made.counts) if group in tools)
            if at_once >= 2:
                one.parallel += length
                one.most_at_once = max(one.most_at_once, at_once)
        for item in finished:
            if item.turn not in turns:
                continue
            one = turns[item.turn]
            inside = max(0.0, min(item.end, one.end) - max(item.start, one.start))
            name = call_name(item)
            if name is not None:
                failed, nothing = ending(item, name)
                # An empty counter takes its first values as they are, so a yes or no is made a number here.
                one.calls[(item.kind, name)].update(runs=1, failed=int(failed), noMatch=int(nothing), seconds=inside)
            if item.kind == "command":
                one.program_seconds[name] += inside
                one.program_runs[name] += 1
                if inside >= LONG_RUN:
                    one.long_runs.append((name, inside))
            elif item.kind == "approval":
                one.approvals.append((str(item.detail.get("approvalKind") or "OTHER").lower(), str(item.detail.get("outcome") or "unknown").lower(), inside))
        total.each.extend(turns.values())
    for one in total.each:
        total.turns += 1
        total.seconds += one.end - one.start
        total.first, total.last = min(total.first, one.start), max(total.last, one.end)
        for whole, part in ((total.groups, one.groups), (total.after, one.after), (total.after_times, one.after_times),
                            (total.program_seconds, one.program_seconds), (total.program_runs, one.program_runs)):
            whole.update(part)
        for call, made in one.calls.items():
            total.calls[call].update(made)
        total.parallel += one.parallel
        total.most_at_once = max(total.most_at_once, one.most_at_once)
        total.unfinished += one.unfinished
    return total


SHOWN = 3  # seconds; anything shorter would print as 0.0 minutes


def line(label, length, whole, indent=""):
    return f"{indent}{label:<{46 - len(indent)}}{length / 60:>9.1f}{100 * length / whole:>8.1f}%"


def count(number, one, many=None):
    return f"{number} {one if number == 1 else many or one + 's'}"


def day(moment):
    return datetime.fromtimestamp(moment, timezone.utc).strftime("%Y-%m-%d")


def notes(parallel, most_at_once, unfinished, skipped, unread):
    """What the tables cannot show: tools that overlapped, and what was left out of the count."""
    lines = []
    if parallel:
        lines.append(f"Two or more tools ran at once for {parallel / 60:.1f} minutes; the most at once was {most_at_once}.")
    if unfinished:
        lines.append(f"{count(unfinished, 'command or tool', 'commands or tools')} never reported an end and {'is' if unfinished == 1 else 'are'} not counted.")
    if skipped:
        lines.append(f"{count(skipped, 'log line')} cut off or in an old format {'was' if skipped == 1 else 'were'} skipped.")
    if unread:
        lines.append(f"{count(unread, 'task log')} with no turn in the current format {'was' if unread == 1 else 'were'} left out.")
    return lines


def call_lines(calls):
    """Commands and tool calls with how many failed, and under each the names that failed most often."""
    if not calls:
        return []
    row = lambda label, runs, failed, indent="": f"{indent}{label:<{46 - len(indent)}}{runs:>9}{failed:>9}{100 * failed / runs:>8.1f}%"
    lines = ["", f"{'Calls and how they ended':<46}{'calls':>9}{'failed':>9}{'share':>9}"]
    for kind, label in (("command", "Commands"), ("tool", "Tool calls")):
        named = sorted(((name, made) for (of, name), made in calls.items() if of == kind), key=lambda one: (-one[1]["failed"], one[0]))
        if named:
            lines.append(row(label, sum(made["runs"] for _, made in named), sum(made["failed"] for _, made in named)))
            lines += [row(name, made["runs"], made["failed"], "    ") for name, made in named[:MOST_FAILED] if made["failed"]]
    nothing = sum(made["noMatch"] for made in calls.values())
    return lines + ([f"{count(nothing, 'search', 'searches')} found nothing, which is not a failure."] if nothing else [])


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
    lines += call_lines(total.calls)
    lines += ["", *notes(total.parallel, total.most_at_once, total.unfinished, total.skipped, total.unread)]
    return "\n".join(lines).rstrip()


def timeline(total, least=30):
    """Each turn's stretches in order, neighbours of one group joined. Stretches shorter than `least` seconds are only counted."""
    lines = [f"Timeline: stretches of {least} seconds or more, with the time since the turn started"]
    for number, turn in enumerate(total.each, 1):
        begin, end = turn.start, turn.end
        lines += ["", f"Turn {number}, {(end - begin) / 60:.1f} minutes"]
        joined = []
        for one in turn.stretches:
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


# How a turn ended when only its log can say, in the words the database uses for the same endings.
OUTCOMES = {"TURN_COMPLETED": "completed", "INTERRUPTED": "interrupted", "TASK_FAILED": "failed", "": "no end mark"}
FACTS = ("provider", "model", "effort", "repository", "inputTokens", "cachedInputTokens", "outputTokens", "cost", "toolCalls", "compactions")


def midnight(day_text):
    """The start of a UTC day given as YYYY-MM-DD, in seconds."""
    return datetime.strptime(day_text, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp()


def turn_table(total, when, since=None, task=None, recorded=None, problem=None):
    """One row per turn for the dashboard, which adds them up any way it likes. Names, counts and times, and nothing else.

    `recorded` is what the database holds for each (task id, turn id). A turn found in both gets its model, effort,
    tokens and outcome from there. A turn only the database knows gets a row without a time split. `problem` says why
    the database was not read."""
    rows, known = [], recorded or {}
    for one in total.each:
        facts = known.get((one.task, one.turn))
        rows.append({"task": one.task, "start": saved.stamp(one.start), "seconds": round(one.end - one.start, 3), "logged": True,
                     "outcome": facts and facts["outcome"] or OUTCOMES[one.ended], **{name: facts[name] if facts else None for name in FACTS},
                     "groups": {group: round(length, 3) for group, length in one.groups.items()},
                     "programs": {name: {"seconds": round(length, 3), "runs": one.program_runs[name]} for name, length in one.program_seconds.items()},
                     "after": {before: {"seconds": round(length, 3), "times": one.after_times[before]} for before, length in one.after.items()},
                     "parallel": round(one.parallel, 3), "mostAtOnce": one.most_at_once, "unfinished": one.unfinished,
                     "longRuns": [{"program": name, "seconds": round(length, 3)} for name, length in sorted(one.long_runs, key=lambda run: (-run[1], run[0]))],
                     "approvals": [{"kind": kind, "outcome": outcome, "seconds": round(length, 3)} for kind, outcome, length in one.approvals],
                     "notices": dict(sorted(one.notices.items())),
                     "calls": [{"kind": kind, "name": name, "runs": made["runs"], "failed": made["failed"], "noMatch": made["noMatch"], "seconds": round(made["seconds"], 3)}
                               for (kind, name), made in sorted(one.calls.items())]})
    logged = {(one.task, one.turn) for one in total.each}
    for (owner, turn), facts in sorted(known.items(), key=lambda made: (made[1]["start"], made[0])):
        # The same filters as for the logs, so a saved table never mixes two selections.
        if (owner, turn) not in logged and (since is None or facts["start"] >= midnight(since)) and (task is None or owner.startswith(task)):
            rows.append({"task": owner, "start": saved.stamp(facts["start"]), "seconds": round(facts["seconds"], 3), "logged": False, "outcome": facts["outcome"] or OUTCOMES[""],
                         **{name: facts[name] for name in FACTS}, "groups": {}, "programs": {}, "after": {}, "parallel": 0.0, "mostAtOnce": 0, "unfinished": 0,
                         "longRuns": [], "approvals": [], "notices": {}, "calls": []})
    database = {"problem": problem or "the database was not read"} if recorded is None else {"turns": len(known), "matched": len(logged & set(known))}
    return {"version": 1, "savedAt": when.strftime("%Y-%m-%dT%H:%M:%SZ"), "since": since, "task": task, "database": database,
            "leftOut": {"skippedLines": total.skipped, "unreadLogs": total.unread}, "turns": rows}


def review_table(reviewed, when, since=None, task=None):
    """The review runs and their findings as `records.reviews` gave them, with when and under which choices they were saved."""
    return {"version": 1, "savedAt": when.strftime("%Y-%m-%dT%H:%M:%SZ"), "since": since, "task": task, **reviewed}


def from_database(read):
    """What `read` gives, or the reason why the database gave nothing."""
    try:
        return read(), None
    except OSError:
        return None, "the database is not there"
    except sqlite3.Error as error:
        return None, f"the database has another shape ({error})"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=RUNTIME, help="Folder with one sub-folder per task, each holding an events.jsonl")
    parser.add_argument("--task", help="Only the task whose id starts with this, with a timeline of its long stretches")
    parser.add_argument("--since", help="Only turns that started on or after this day, YYYY-MM-DD in UTC")
    parser.add_argument("--save", type=Path, nargs="?", const=saved.FOLDER, metavar="FOLDER", help="Also save the numbers for the dashboard")
    parser.add_argument("--database", type=Path, help="ByteQuay's database, read only when saving and never written; by default the one beside the runtime folder")
    args = parser.parse_args()
    if args.task and not re.fullmatch(r"[0-9a-fA-F-]+", args.task):
        parser.error("--task takes the start of a task id: letters a to f, digits and dashes")
    try:
        since = midnight(args.since) if args.since else None
    except ValueError:
        parser.error("--since takes a day as YYYY-MM-DD")
    logs = sorted(args.runtime.glob((args.task or "") + "*/events.jsonl"))
    if not logs:
        parser.error(f"No task log matches under {args.runtime}")
    total = measure(logs, since)
    print(render(total) + ("\n\n" + timeline(total) if args.task and total.turns else ""))
    if args.save and total.turns:  # with no turns there is nothing worth replacing an earlier result with
        # The tables above come from the logs alone. Only the saved tables also carry what the database knows.
        database, now = args.database or args.runtime.parent / "bytequay-1.0.db", datetime.now(timezone.utc)
        recorded, problem = from_database(lambda: records.read(database))
        if problem:
            print(f"\nModel, effort, tokens and cost are left out: {problem}.")
        print("\nSaved to", saved.write(args.save, "turns.json", turn_table(total, now, args.since, args.task, recorded, problem)))
        reviewed, problem = from_database(lambda: records.reviews(database, recorded or {}, since, args.task))
        if problem:
            print(f"Reviews are not saved: {problem}.")
        else:
            print("Saved to", saved.write(args.save, "reviews.json", review_table(reviewed, now, args.since, args.task)))


if __name__ == "__main__":
    main()
