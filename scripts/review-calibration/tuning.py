#!/usr/bin/env python3
"""How much a person had to step in to get the agent's work done, and how its turns ended. Read from ByteQuay's task
logs and, read-only, from its database: names, counts and times, never a message, a command or a path. `--save` keeps
the table for the dashboard's Tuning page."""

from __future__ import annotations

import monitor
import dashboard
import argparse
from collections import Counter, defaultdict
from contextlib import closing
from datetime import datetime, timezone
import math
from pathlib import Path
import sqlite3
from urllib.parse import quote

import pandas
import plotly.graph_objects as go

import activity
import charts
import saved

DELIVERIES = {"TURN": "messages", "QUEUED": "queued", "STEER": "steers"}  # how a message reached the agent: it started a turn, waited in the queue, or went into a running turn
ANSWERED = ("ALLOWED", "DENIED")  # approvals a person answered; one that EXPIRED or was CANCELLED went unanswered
HICCUPS = {"RATE_LIMITED": "rateLimits", "RETRYING": "retries", "INTERRUPTED": "interruptions"}
# Every way a person stepped in after asking, in the order the page lists them.
STEPS = {"followUps": "another message", "queued": "a message queued while it worked", "steers": "a message into a running turn",
         "answered": "an approval answered", "cancels": "a turn stopped"}
SIZES = (("1", 1), ("2", 2), ("3 to 4", 4), ("5 to 8", 8), ("9 or more", float("inf")))  # messages a task needed
# ByteQuay's own tools are loaded only when the agent asks for them; these calls look them up instead of doing work.
LOOKUPS = ("bytequay_list_tools", "bytequay_get_tool", "ToolSearch")
TOP_TOOLS = 8  # the tools in the chart of which call follows which
ENDINGS = ("COMPLETED", "CANCELLED", "INTERRUPTED", "FAILED")


def turns_of(task, events):
    """One row per turn of a task log: the messages that came in by how they came, the approvals by whether a person
    answered them, and the hiccups. A message names its turn, also when it is logged just before the turn starts."""
    windows, finished, _ = activity.items(events)
    rows = {turn: {"task": task, "turn": turn, "start": start, "seconds": round(end - start, 3), **dict.fromkeys(DELIVERIES.values(), 0),
                   "answered": 0, "unanswered": 0, **dict.fromkeys(HICCUPS.values(), 0)} for turn, (start, end) in windows.items()}
    for event in events:
        row, payload = rows.get(event.get("turnId")), event["payload"]
        if row is not None and event["type"] == "user_message" and payload.get("deliveryKind") in DELIVERIES:
            row[DELIVERIES[payload["deliveryKind"]]] += 1
        elif row is not None and event["type"] == "lifecycle" and payload.get("phase") in HICCUPS:
            row[HICCUPS[payload["phase"]]] += 1
    for item in finished:
        if item.kind == "approval" and item.turn in rows:
            rows[item.turn]["answered" if item.detail.get("outcome") in ANSWERED else "unanswered"] += 1
    calls = defaultdict(list)
    for item in sorted(finished, key=lambda item: item.start):
        if item.turn in rows and activity.call_name(item):
            calls[item.turn].append((activity.call_name(item), item.end - item.start))
    for turn, row in rows.items():
        row |= path(calls[turn])
    return list(rows.values())


def path(calls):
    """What one turn's calls, in the order they started, say about its path: the calls of each tool, which call followed
    which, the longest run of one call in a row, and the lookups of ByteQuay's own tools with the seconds they took."""
    names = [name for name, _ in calls]
    longest, run = None, 0
    for at, name in enumerate(names):
        run = run + 1 if at and names[at - 1] == name else 1
        if longest is None or run > longest[1]:
            longest = [name, run]
    return {"calls": len(names), "tools": dict(Counter(names)), "paths": [[before, after, count] for (before, after), count in Counter(zip(names, names[1:])).items()],
            "streak": longest, "lookups": sum(name in LOOKUPS for name in names), "lookupSeconds": round(sum(length for name, length in calls if name in LOOKUPS), 3)}


def database(path):
    """For every turn what started it, whether someone asked it to stop, how it ended and the setup it ran with; for every
    task its state, its kind and its repository. Read without writing. A turn's terminal reason is free text and is not
    read. A database from before turns named their setup has no such column, and every setup is then not known."""
    with closing(sqlite3.connect(f"file:{quote(str(path))}?mode=ro", uri=True, timeout=3)) as connection:
        setup = "setup_fingerprint" if "setup_fingerprint" in {row[1] for row in connection.execute("PRAGMA table_info(agent_turn)")} else "NULL"
        turns = {turn: {"origin": origin, "cancelAsked": bool(asked), "ended": state, "setup": named} for turn, origin, asked, state, named in connection.execute(
            f"SELECT id, development_origin_kind, cancellation_requested_at IS NOT NULL, state, {setup} FROM agent_turn")}
        tasks = {task: {"state": state, "interactive": bool(interactive), "repository": f"{owner}/{name}" if owner and name else None}
                 for task, state, interactive, owner, name in connection.execute("SELECT id, state, interactive, repository_owner, repository_name FROM agent_task")}
    return turns, tasks


def table(runtime, database_path, now):
    """Every logged turn with what the database adds to it, and every task that has a log. A task whose row is gone from the
    database keeps its turns, with no state."""
    rows, skipped = [], 0
    for log in sorted(runtime.glob("*/events.jsonl")):
        events, cut = activity.read(log)
        skipped += cut
        rows += turns_of(log.parent.name, events)
    try:
        turns, tasks = database(database_path)
        problem = None
    except sqlite3.Error as error:
        turns, tasks, problem = {}, {}, f"the database could not be read: {error}"
    unknown = {"origin": None, "cancelAsked": None, "ended": None, "setup": None}
    rows = [row | turns.get(row["turn"], unknown) for row in sorted(rows, key=lambda row: row["start"])]
    logged = dict.fromkeys(row["task"] for row in rows)
    return {"version": 1, "savedAt": saved.stamp(now.timestamp()), "skippedLines": skipped, "database": {"problem": problem},
            "turns": rows, "tasks": [{"task": task, **tasks.get(task, {"state": None, "interactive": None, "repository": None})} for task in logged]}


def frames(made, since=0.0, zone=None):
    """The saved table from a moment on, as turns and tasks. A task's first message is its ask; every later message is
    another message. A task counts by its turns since that moment."""
    turns = pandas.DataFrame(made["turns"])
    turns = turns[turns["start"] >= since].copy() if len(turns) else turns
    if turns.empty:
        return turns, pandas.DataFrame()
    first = turns.groupby("task")["start"].transform("min") == turns["start"]
    turns["followUps"] = (turns["messages"] - first.astype(int)).clip(lower=0)
    turns["cancels"] = turns["cancelAsked"].eq(True).astype(int)  # not known counts as not asked
    local = pandas.to_datetime(turns["start"], unit="s", utc=True).dt.tz_convert(zone or datetime.now().astimezone().tzinfo).dt.tz_localize(None)
    turns["week"] = local.dt.to_period("W").dt.start_time  # weeks start on Monday, in this Mac's time zone
    tasks = turns.groupby("task", as_index=False).agg(turns=("turn", "count"), messages=("messages", "sum"), **{name: (name, "sum") for name in STEPS}, unanswered=("unanswered", "sum"))
    known = pandas.DataFrame(made["tasks"], columns=["task", "state", "interactive", "repository"])
    tasks = tasks.merge(known, on="task", how="left")
    tasks["touches"] = tasks["messages"] + tasks[[name for name in STEPS if name != "followUps"]].sum(axis=1)
    return turns, tasks


def steps_rows(tasks):
    """How often a person stepped in after asking, by the way they did, and per task."""
    return [(label, int(tasks[name].sum()), tasks[name].sum() / len(tasks)) for name, label in STEPS.items()]


def sizes_rows(tasks):
    """How many tasks needed how many messages, from one up."""
    rows, low = [], 0
    for label, high in SIZES:
        rows.append((label, int(((tasks["messages"] > low) & (tasks["messages"] <= high)).sum())))
        low = high
    return rows


def count_bars(rows, mode, unit, height_per_row=34):
    """One bar per row, its count written at the tip with the share of the whole, in the color of a person."""
    whole = sum(amount for _, amount, *_ in rows) or 1
    figure = go.Figure(go.Bar(orientation="h", y=[label for label, *_ in rows], x=[amount for _, amount, *_ in rows], cliponaxis=False, textposition="outside",
                              text=[f"{amount:,} {unit} · {100 * amount / whole:.0f}%" for _, amount, *_ in rows], marker={"color": charts.COLORS[mode]["you"]},
                              hovertemplate="%{y}: %{x:,}<extra></extra>"))
    figure.update_xaxes(range=[0, max([amount for _, amount, *_ in rows] + [1]) * 1.7])
    figure.update_yaxes(type="category")  # names such as "1" and "2" stay names, not numbers on a scale
    return charts.bar_list(figure, height_per_row * len(rows) + 12)


def endings_figure(turns, mode):
    """The turns of each week by how they ended, stacked. A turn whose task is gone from the database is "not known"."""
    ended = turns.assign(ended=turns["ended"].fillna("not known"))
    weeks = sorted(ended["week"].unique())
    figure = go.Figure()
    for ending in (*ENDINGS, "not known"):
        counts = ended[ended["ended"] == ending].groupby("week").size().reindex(weeks, fill_value=0)
        # An ending that is not known is hatched, so it does not read as a result of its own.
        marker = {"color": charts.COLORS[mode]["usual"], "pattern": {"shape": "/", "fgcolor": charts.COLORS[mode]["other"]}} if ending == "not known" else \
            {"color": charts.COLORS[mode][charts.ENDINGS[ending.lower()]]}
        if counts.any():
            figure.add_bar(x=weeks, y=counts, name=ending.lower(), marker=marker | {"line": {"color": charts.COLORS[mode]["surface"], "width": 2}},
                           hovertemplate=f"%{{y:,}} {ending.lower()}<extra></extra>")
    figure.update_xaxes(showgrid=False, tickformat="%b %d")
    figure.update_yaxes(gridcolor=charts.COLORS[mode]["grid"])
    return charts.tidy(figure, 260, legend=True)


def busiest(tasks, top=10):
    """The tasks that needed the most messages, with how a person stepped in to each."""
    shown = tasks.sort_values(["messages", "touches"], ascending=False).head(top)
    return pandas.DataFrame({"task": shown["task"].str[:8], "repository": shown["repository"].fillna("not known"), "state": shown["state"].fillna("not known").str.lower(),
                             "turns": shown["turns"], "messages": shown["messages"], "queued": shown["queued"], "steered": shown["steers"],
                             "approvals": shown["answered"], "stopped": shown["cancels"]})


def tools_table(turns):
    """For every tool and program: the turns that used it, its calls, how many a turn that used it typically made and at
    most, and the share of its calls that came straight after a call to itself."""
    used = pandas.DataFrame([(turn, name, count) for turn, tools in zip(turns["turn"], turns["tools"]) for name, count in tools.items()], columns=["turn", "tool", "calls"])
    again = Counter()
    for paths in turns["paths"]:
        again.update({before: count for before, after, count in paths if before == after})
    made = used.groupby("tool", as_index=False).agg(turns=("turn", "nunique"), calls=("calls", "sum"), typical=("calls", "median"), most=("calls", "max"))
    made["after itself"] = made["tool"].map(again).fillna(0) / made["calls"]
    return made.sort_values(["calls", "tool"], ascending=[False, True]).rename(columns={"typical": "per turn", "most": "most in a turn"})


def follows_figure(turns, mode, top=TOP_TOOLS):
    """Which call follows which among the most called tools and programs: a cell counts how often the row's call came
    straight before the column's, and the diagonal is a call that followed itself. Shades go by the tens, as a few pairs
    run to thousands."""
    counts, totals = Counter(), Counter()
    for paths, tools in zip(turns["paths"], turns["tools"]):
        counts.update({(before, after): count for before, after, count in paths})
        totals.update(tools)
    names = [name for name, _ in totals.most_common(top)]
    cells = [[counts[before, after] for after in names] for before in names]
    low, high = charts.COLORS[mode]["scale"]
    figure = go.Figure(go.Heatmap(z=[[math.log10(count + 1) for count in row] for row in cells], x=names, y=names, text=[[f"{count:,}" if count else "" for count in row] for row in cells],
                                  texttemplate="%{text}", colorscale=[[0, charts.COLORS[mode]["surface"]], [0.001, low], [1, high]], zmin=0, showscale=False, xgap=2, ygap=2,
                                  customdata=cells, hovertemplate="%{y}, then %{x}: %{customdata:,} times<extra></extra>"))
    figure.update_yaxes(autorange="reversed", ticksuffix="  ")
    figure.update_xaxes(side="top", tickangle=-30)
    return charts.tidy(figure, 44 * len(names) + 120)


def setups_table(turns):
    """Every setup the turns ran with, in the order each first ran, with how its turns went. Changed says which half of the
    name no earlier setup had: the instructions, the tools, or both. Sessions of several kinds run side by side, each with
    a setup of its own, so a half is set against every earlier one and not only the setup just before. None while no turn
    names its setup."""
    named = turns[turns["setup"].notna()] if "setup" in turns else turns.iloc[:0]
    if named.empty:
        return None
    rows, seen = [], (set(), set())
    for setup, group in sorted(named.groupby("setup"), key=lambda pair: pair[1]["start"].min()):
        called = group[group["calls"] > 0] if "calls" in group else group.iloc[:0]
        halves = setup.split("-")
        rows.append({"setup": setup, "changed": "" if not seen[0] else " and ".join(part for part, half, earlier in zip(("instructions", "tools"), halves, seen) if half not in earlier),
                     "first ran": dashboard.when(datetime.fromtimestamp(group["start"].min())), "turns": len(group),
                     # A turn names its setup only through its row in the database, which always holds how it ended.
                     "completed": (group["ended"] == "COMPLETED").sum() / len(group), "minutes per turn": group["seconds"].median() / 60,
                     "calls per turn": called["calls"].median() if len(called) else None,
                     "steered or stopped per 100": 100 * (group["steers"] + group["cancels"]).sum() / len(group), "rate limits per 100": 100 * group["rateLimits"].sum() / len(group)})
        for half, earlier in zip(halves, seen):
            earlier.add(half)
    return pandas.DataFrame(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=activity.RUNTIME, help="Folder with one sub-folder per task, each holding an events.jsonl")
    parser.add_argument("--database", type=Path, help="ByteQuay's database, read without writing (default: beside the runtime folder)")
    parser.add_argument("--save", type=Path, nargs="?", const=saved.FOLDER, metavar="FOLDER", help="Also save the table as tuning.json for the dashboard")
    args = parser.parse_args()
    made = table(args.runtime, args.database or args.runtime.parent / monitor.DATABASE.name, datetime.now(timezone.utc))
    turns, tasks = made["turns"], made["tasks"]
    touched = lambda row: row["messages"] + row["queued"] + row["steers"] + row["answered"] + bool(row["cancelAsked"])
    print(f"{len(turns):,} turns of {len(tasks):,} tasks; {sum(map(touched, turns)):,} times a person stepped in" + (f"; {made['database']['problem']}" if made["database"]["problem"] else ""))
    if args.save and turns:  # with no turn there is nothing worth replacing an earlier table with
        print("Saved to", saved.write(args.save, "tuning.json", made))


if __name__ == "__main__":
    main()
