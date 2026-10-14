#!/usr/bin/env python3
"""How much a person had to step in to get the agent's work done, and how its turns ended. Read from ByteQuay's task
logs and, read-only, from its database: names, counts and times, never a message, a command or a path. `--save` keeps
the table for the dashboard's Tuning page."""

from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
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
    return list(rows.values())


def database(path):
    """For every turn what started it, whether someone asked it to stop, and how it ended; for every task its state, its
    kind and its repository. Read without writing. A turn's terminal reason is free text and is not read."""
    with closing(sqlite3.connect(f"file:{quote(str(path))}?mode=ro", uri=True, timeout=3)) as connection:
        turns = {turn: {"origin": origin, "cancelAsked": bool(asked), "ended": state} for turn, origin, asked, state in connection.execute(
            "SELECT id, development_origin_kind, cancellation_requested_at IS NOT NULL, state FROM agent_turn")}
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
    unknown = {"origin": None, "cancelAsked": None, "ended": None}
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=activity.RUNTIME, help="Folder with one sub-folder per task, each holding an events.jsonl")
    parser.add_argument("--database", type=Path, help="ByteQuay's database, read without writing (default: beside the runtime folder)")
    parser.add_argument("--save", type=Path, nargs="?", const=saved.FOLDER, metavar="FOLDER", help="Also save the table as tuning.json for the dashboard")
    args = parser.parse_args()
    made = table(args.runtime, args.database or args.runtime.parent / "bytequay-1.0.db", datetime.now(timezone.utc))
    turns, tasks = made["turns"], made["tasks"]
    touched = lambda row: row["messages"] + row["queued"] + row["steers"] + row["answered"] + bool(row["cancelAsked"])
    print(f"{len(turns):,} turns of {len(tasks):,} tasks; {sum(map(touched, turns)):,} times a person stepped in" + (f"; {made['database']['problem']}" if made["database"]["problem"] else ""))
    if args.save and turns:  # with no turn there is nothing worth replacing an earlier table with
        print("Saved to", saved.write(args.save, "tuning.json", made))


if __name__ == "__main__":
    main()
