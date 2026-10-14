"""Why the numbers of the lab report moved. A total such as agent time or cost is what every model and effort gave, added
up, so the report names the ones that moved it most with their turns, and says when the turns ran with other models and
efforts than in the days before. Then what changed in ByteQuay itself on those days: the setups that first ran, and the
commits that reached the agent code."""

from __future__ import annotations

from datetime import datetime, time, timedelta
from pathlib import Path
import subprocess

import pandas

import activity
import charts

ROOT = Path(__file__).resolve().parents[2]  # ByteQuay's repository, which the lab lives in
# Where a commit to the agent code landed, by the folders and files of ByteQuay that make up each part.
AREAS = {
    "instructions and context": ("backend/agent-cli/src/main/java/com/bytequay/agent/cli/ProviderInstructions.java",
                                 "backend/agent-spi/src/main/java/com/bytequay/agent/spi/AgentCommandPolicy.java", "backend/app/src/main/java/com/bytequay/app/agent/",
                                 "backend/app/src/main/java/com/bytequay/app/context/", "backend/app/src/main/java/com/bytequay/app/memory/"),
    "tools and approvals": ("backend/agent-cli/src/main/java/com/bytequay/agent/cli/mcp/",),
    "the Codex lane": ("backend/agent-cli/src/main/java/com/bytequay/agent/cli/codex/",),
    "the Claude lane": ("backend/agent-cli/src/main/java/com/bytequay/agent/cli/claude/",),
    "turns and tasks": ("backend/agent-core/src/main/",),
    "reviews": ("backend/app/src/main/java/com/bytequay/app/review/",),
}

UNKNOWN = "no model known"  # the turns the database does not know
TOTALS = ("Agent time", "Turns", "Long single commands", "Time in long commands", "Questions to you", "Rate limit notices", "Tokens in", "Tokens out", "Recorded cost")
BIG = 25  # percent a total must move by before the report says why
FEW = 10  # a count that stays below this on both sides moves by chance, so it gets no reason
COUNTS = ("Long single commands", "Questions to you", "Rate limit notices")
SHIFT = 10  # points of the turns a model and effort must gain or lose before it is named
NAMED = 2  # models and efforts named for a total; one that gave less than a tenth of the move is left out
# The totals that some turns leave out, with the column that says whether a turn has it and how the report names it.
KNOWN = {"Tokens in": ("inputTokens", "tokens", "them"), "Tokens out": ("inputTokens", "tokens", "them"), "Recorded cost": ("cost", "a cost", "one")}


def settings(shown):
    """The turns of each model and effort as frames, the turns the database does not know under UNKNOWN."""
    named = shown["turns"]["setting"].fillna(UNKNOWN)
    return {setting: charts.only(shown, named == setting) for setting in sorted(set(named))}


def signed(written, amount):
    return ("-" if amount < 0 else "+") + written(abs(amount))


def shifted(before, now):
    """The models and efforts whose share of the turns moved by SHIFT points or more, the most first, as a sentence; None
    when none did. `before` and `now` map a model and effort to its turns."""
    def shares(counts):
        whole = sum(counts.values())
        return {setting: charts.share(amount, whole) for setting, amount in counts.items()}

    was, is_ = shares(before), shares(now)
    gaps = sorted(((is_.get(setting, 0.0) - was.get(setting, 0.0), setting) for setting in set(was) | set(is_)), key=lambda pair: (-abs(pair[0]), pair[1]))
    named = [f"{setting} {is_.get(setting, 0.0):.0f}% of the turns ({was.get(setting, 0.0):.0f}% before)" for gap, setting in gaps[:3] if abs(gap) >= SHIFT]
    return "The turns ran with other models and efforts than in the days before: " + ", ".join(named) + "." if named else None


def total(name, before_by, now_by, before, now, written):
    """A total that moved by BIG percent or more, with the models and efforts that moved it most; None for one that moved
    less or is not known on a side. `before_by` and `now_by` map a model and effort to its own numbers."""
    was, is_ = before.get(name), now.get(name)
    if was is None or is_ is None or not was or abs(is_ - was) < was * BIG / 100 or (name in COUNTS and max(was, is_) < FEW):
        return None

    def amount(by, setting, what=name):
        return (by[setting].get(what) or 0) if setting in by else 0

    gave = {setting: amount(now_by, setting) - amount(before_by, setting) for setting in set(before_by) | set(now_by)}
    top = [setting for setting in sorted(gave, key=lambda one: (-abs(gave[one]), one)) if abs(gave[setting]) >= abs(is_ - was) / 10][:NAMED]
    named = [f"{setting} {signed(written, gave[setting])}" + ("" if name == "Turns" else f" ({amount(before_by, setting, 'Turns'):,} → {amount(now_by, setting, 'Turns'):,} turns)")
             for setting in top]
    return f"{name} {written(was)} → {written(is_)}: " + ", ".join(named) + "."


def coverage(name, shown_before, shown):
    """For a total that some turns leave out, how many turns on each side have it and which providers never record it, as a
    sentence; empty when every turn has it."""
    column, what, it = KNOWN[name]
    (had, of), (has, out_of) = ((int(made["turns"][column].notna().sum()), len(made["turns"])) for made in (shown_before, shown))
    if had == of and has == out_of:
        return ""
    both = pandas.concat([shown_before["turns"], shown["turns"]])
    silent = sorted(provider for provider, group in both.groupby("provider") if group[column].isna().all())
    return (f" {what[0].upper()}{what[1:]} {'are' if what == 'tokens' else 'is'} recorded for {had:,} of {of:,} turns before and {has:,} of {out_of:,} these days"
            + (f"; no {' or '.join(silent)} turn records {it}" if silent else "") + ".")


def why(shown_before, shown, before, now, facts, written):
    """The lines of "why it moved". `facts` makes the numbers of some turns as the report lists them, and `written` maps
    each number's name to how it is written."""
    if min(len(shown_before["turns"]), len(shown["turns"])) < charts.MIN_TURNS:
        return [f"Fewer than {charts.MIN_TURNS} turns on a side, too few to say why the totals moved."]
    before_by, now_by = ({setting: facts(part, None) for setting, part in settings(made).items()} for made in (shown_before, shown))
    lines = []
    moved = shifted({setting: made["Turns"] for setting, made in before_by.items()}, {setting: made["Turns"] for setting, made in now_by.items()})
    if moved:
        lines += [moved, ""]
    for name in TOTALS:
        line = total(name, before_by, now_by, before, now, written[name])
        if line:
            lines.append("- " + line + (coverage(name, shown_before, shown) if name in KNOWN else ""))
    return lines if lines and lines[-1].startswith("- ") else [*lines, f"No total moved by {BIG}% or more."]


def commits(first, last, zone=None, root=ROOT):
    """The commits that reached ByteQuay's agent code from the start of `first` to the end of `last`, in the reader's own
    time zone, as (time, subject, paths), the latest first. None when git cannot say."""
    zone = zone or datetime.now().astimezone().tzinfo
    start, end = (datetime.combine(day, time.min, zone) for day in (first, last + timedelta(days=1)))
    try:
        found = subprocess.run(["git", "-C", str(root), "log", f"--since={start.isoformat()}", f"--until={end.isoformat()}", "--format=%x00%ct%x09%s", "--name-only", "--",
                                *(path for paths in AREAS.values() for path in paths)], capture_output=True, text=True, check=True, timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    made = []
    for chunk in found.split("\x00")[1:]:
        head, *names = chunk.strip("\n").split("\n")
        when, subject = head.split("\t", 1)
        if start.timestamp() <= int(when) < end.timestamp():
            made.append((int(when), subject, tuple(name for name in names if name)))
    return made


def area(path):
    """The part of the agent code a path belongs to, or None."""
    return next((name for name, prefixes in AREAS.items() if any(path.startswith(prefix) for prefix in prefixes)), None)


def changed(turns, first, last, found):
    """The lines of "what changed in ByteQuay": the setups that first ran from `first` to `last`, each with the half of its
    name no earlier setup had, and the commits that reached the agent code, by where they landed. `turns` is every saved
    turn and `found` is what `commits` gave, or None when the commits were not read."""
    named, lines = turns[turns["setup"].notna()], []
    if named.empty:
        lines.append("- No saved turn names its setup yet.")
    else:
        new, seen = [], (set(), set())
        for setup, start in named.groupby("setup")["start"].min().sort_values().items():
            halves = setup.split("-")
            if first <= start.date() <= last:
                fresh = [part for part, half, earlier in zip(("instructions", "tools"), halves, seen) if half not in earlier]
                what = "the first named" if not seen[0] else f"new {' and '.join(fresh)}" if fresh else "known halves"
                ran = int(((named["setup"] == setup) & (named["day"] >= first) & (named["day"] <= last)).sum())
                new.append(f"`{setup}` ({what}, {activity.count(ran, 'turn')})")
            for half, earlier in zip(halves, seen):
                earlier.add(half)
        lines.append("- Setups that first ran these days: " + ", ".join(new) + "." if new else "- No new setup ran these days.")
    if found is None:
        lines.append("- The commits to the agent code were not read.")
    elif not found:
        lines.append("- No commit reached the agent code these days.")
    else:
        landed = {}
        for _, subject, paths in found:
            for name in sorted({area(path) for path in paths} - {None}):
                landed.setdefault(name, []).append(subject)
        lines.append(f"- {activity.count(len(found), 'commit')} reached the agent code these days:")
        lines += [f"  - {name}: {len(subjects)}, the latest \"{subjects[0]}\"" for name, subjects in sorted(landed.items(), key=lambda pair: (-len(pair[1]), pair[0]))]
    return lines
