"""What the Resources page draws from the monitor's samples: the frames for a stretch of time, and the charts and tables
made from them. The page itself is pages/Resources.py."""

from __future__ import annotations

import contextlib
from datetime import datetime
import sqlite3

import pandas
import plotly.graph_objects as go

import charts
import cleanup
import monitor

MB, GB = 2**20, 2**30
# Three groups wear the three hues of the lab's checked palette; the table below the charts keeps every part apart.
GROUPS = {"Electron app": ("app", "window", "gpu", "helpers"), "Java backend": ("backend",), "Agents": ("agents", "commands")}
COLOR = dict(zip(GROUPS, ("model", "tool", "you")))
NAMES = {"app": "app, main process", "window": "app windows", "gpu": "app GPU", "helpers": "app helpers", "backend": "Java backend",
         "agents": "agent command lines", "commands": "commands they run", "monitor": "this monitor"}
# How far back each choice reaches, and the bucket its charts average over, so every chart has a few hundred points at most.
SPANS = {"Last hour": (3600, 30), "Last day": (86400, 300), "7 days": (7 * 86400, 3600), "30 days": (30 * 86400, 3 * 3600), "90 days": (90 * 86400, 86400)}
LIVE = 900  # seconds the live view reaches back
STALE = 20  # seconds without a sample before the monitor counts as stopped
AMOUNTS = ["processes", "memory", "cpu", "threads", "files"]
# What each owner of the app's data is, and what can be done about it.
NOTES = {
    monitor.OPEN: "Worktrees and run data of open tasks changed in the last week.",
    monitor.IDLE: "Open tasks nobody changed for a week or more. Closing the ones you are done with frees this.",
    monitor.FINISHED: "Kept after a task finished.",
    monitor.REMOVED: "Left behind by tasks the database no longer has. Nothing uses it, but a worktree may hold work that was never committed.",
    monitor.RETIRED: "Left by features the app no longer has: the code graph's index and the old development flow's worktrees. Nothing reads it.",
    monitor.COPIES: "Copies of the database made before past changes, and the database under its old name. The app does not read them.",
    monitor.REPOSITORIES: "The clones that worktrees are made from.",
    monitor.APP: "Its database, caches, browser data, tools and pictures.",
    monitor.SHARED: "Blocks that copies held by different owners share. They are freed only when every copy goes.",
    monitor.NOT_KNOWN: "Task data counted while the database could not be read.",
}


def read(folder, since):
    """The monitor's samples since a moment, and every disk count, as frames. None when the monitor has kept nothing yet."""
    path = folder / monitor.STORE
    if not path.exists():
        return None
    with contextlib.closing(sqlite3.connect(path, timeout=5)) as connection:
        made = {table: pandas.read_sql_query(f"SELECT * FROM {table} WHERE at >= ? ORDER BY at", connection, params=(since,)) for table in ("parts", "machine")}
        return made | {"space": pandas.read_sql_query("SELECT * FROM space ORDER BY at", connection)}


def moments(at):
    """Sample times as this Mac's own clock shows them."""
    return pandas.to_datetime(at, unit="s", utc=True).dt.tz_convert(datetime.now().astimezone().tzinfo).dt.tz_localize(None)


def grouped(parts):
    """One row per moment and group, its parts added up. The monitor's own row is left out: it is not the app."""
    group_of = {part: group for group, members in GROUPS.items() for part in members}
    rows = parts[parts["part"].isin(group_of)].assign(group=lambda frame: frame["part"].map(group_of))
    return rows.groupby(["at", "group"], as_index=False)[AMOUNTS].sum()


def bucketed(frame, seconds, keys=()):
    """Averages over buckets of time, so a chart of three months has no more points than one of an hour."""
    return frame.assign(at=frame["at"] // seconds * seconds).groupby(["at", *keys], as_index=False).mean(numeric_only=True)


def now(parts, machine):
    """The tiles of the live view from the newest sample: the app's parts added up, and ByteQuay's open work."""
    newest = parts[(parts["at"] == parts["at"].max()) & (parts["part"] != "monitor")]
    last = machine.iloc[-1]
    known = lambda name: None if pandas.isna(last[name]) else int(last[name])
    return {"memory": newest["memory"].sum(), "cpu": newest["cpu"].sum(), "threads": int(newest["threads"].sum()), "processes": int(newest["processes"].sum()),
            "turns": known("turns"), "tasks": known("tasks"), "queued": known("queued"), "used": last["memory_used"], "total": last["memory_total"]}


def lines_figure(frame, value, unit, mode, suffix, span, stacked=False, height=240):
    """One line for each group over a span of time, in the group's own color; stacked, the lines add up to the whole. A
    group with only a few points gets dots too, as a single point draws no line."""
    figure = go.Figure()
    for group in GROUPS:
        rows = frame[frame["group"] == group]
        if len(rows):
            figure.add_scatter(x=moments(rows["at"]), y=rows[value] / unit, name=group, mode="lines" if len(rows) > 2 else "lines+markers", marker={"size": 8},
                               stackgroup="all" if stacked else None,
                               line={"width": 2, "color": charts.COLORS[mode][COLOR[group]]}, hovertemplate=f"%{{y:,.2f}}{suffix}<extra>{group}</extra>")
    figure.update_xaxes(showgrid=False, range=list(moments(pandas.Series(span))))
    figure.update_yaxes(gridcolor=charts.COLORS[mode]["grid"], ticksuffix=suffix, rangemode="tozero")
    return charts.tidy(figure, height, legend=True).update_layout(hovermode="x unified")


def turns_figure(machine, mode, span, height=110):
    """How many turns ran, on the same clock as the charts above it, so a spike there can be set against the work."""
    figure = go.Figure(go.Scatter(x=moments(machine["at"]), y=machine["turns"], mode="lines" if len(machine) > 2 else "lines+markers", marker={"size": 8}, fill="tozeroy",
                                  line={"width": 2, "color": charts.COLORS[mode]["other"]}, hovertemplate="%{y:.1f} turns running<extra></extra>"))
    figure.update_xaxes(showgrid=False, range=list(moments(pandas.Series(span))))
    figure.update_yaxes(gridcolor=charts.COLORS[mode]["grid"], rangemode="tozero")
    return charts.tidy(figure, height)


def parts_table(parts):
    """For every part that ran: its processes, memory, CPU, threads and open files now, and its memory and CPU typically and
    at their peak. Typical is the middle of the samples it ran in; now is empty for a part that is not running now."""
    newest, rows = parts["at"].max(), []
    for part in monitor.PARTS:
        mine = parts[(parts["part"] == part) & (parts["processes"] > 0)]
        if mine.empty:
            continue
        current = mine[mine["at"] == newest]
        at_once = lambda amount, unit=1: current[amount].sum() / unit if len(current) else None
        rows.append({"part": NAMES[part], "processes": int(current["processes"].sum()), "memory MB": at_once("memory", MB), "typical MB": mine["memory"].median() / MB,
                     "peak MB": mine["memory_peak"].max() / MB, "CPU cores": at_once("cpu"), "typical CPU": mine["cpu"].median(), "peak CPU": mine["cpu_peak"].max(),
                     "threads": at_once("threads"), "open files": at_once("files")})
    return pandas.DataFrame(rows)


def idle_days(parts, machine):
    """The typical memory of each group on each day, from the samples when no turn ran: memory that grows with no work to
    do shows as a column that rises day after day. None while there is no such sample."""
    idle = grouped(parts[parts["at"].isin(machine.loc[machine["turns"] == 0, "at"])])
    idle = idle[idle["processes"] > 0]
    if idle.empty:
        return None
    table = idle.assign(day=moments(idle["at"]).dt.date).pivot_table(index="day", columns="group", values="memory", aggfunc="median") / MB
    return table[[group for group in GROUPS if group in table.columns]].reset_index()


def owner_rows(space):
    """The newest count of the app's data by owner, in the monitor's order: its size, its files and what the owner is.
    None before the first count."""
    if space.empty:
        return None
    newest = space[space["at"] == space["at"].max()].groupby("owner")[["bytes", "files"]].sum()
    return pandas.DataFrame([{"owner": owner, "GB": newest.loc[owner, "bytes"] / GB, "files": int(newest.loc[owner, "files"]), "what it is": NOTES[owner]}
                             for owner in monitor.OWNERS if owner in newest.index])


def disk_rows(space, top=8):
    """The newest count of the app's data by place, biggest first, and how much each grew since the first count kept. The
    places past the biggest few are added up."""
    if space.empty:
        return None
    disk = space.groupby(["at", "place"], as_index=False)["bytes"].sum()
    newest, first = disk[disk["at"] == disk["at"].max()], disk[disk["at"] == disk["at"].min()].set_index("place")["bytes"]
    newest = newest.sort_values("bytes", ascending=False)
    rows = [{"place": place, "GB": size / GB, "grew GB": (size - first[place]) / GB if place in first else None} for place, size in zip(newest["place"], newest["bytes"])]
    if len(rows) > top:
        rest = rows[top:]
        rows = [*rows[:top], {"place": f"{len(rest)} more", "GB": sum(row["GB"] for row in rest),
                              "grew GB": None if any(row["grew GB"] is None for row in rest) else sum(row["grew GB"] for row in rest)}]
    return pandas.DataFrame(rows)


def cleanup_rows(data, going, space):
    """What a clean-up would delete, by owner and top-level folder, with what that owner held there at the last disk count."""
    newest = space[space["at"] == space["at"].max()].groupby(["owner", "place"])["bytes"].sum() if len(space) else None
    return pandas.DataFrame([{"owner": owner, "place": top, "folders or files": amount, "GB at the last count": None if newest is None else newest.get((owner, top), 0) / GB}
                             for (owner, top), amount in sorted(cleanup.tally(data, going).items())])


def disk_figure(rows, mode, by="place"):
    """The places or the owners of the app's data as bars, each with its size at the tip. What could be freed wears the
    warning color, so the eye goes there first."""
    colors = [charts.COLORS[mode]["warning" if name in monitor.FREEABLE else "model"] for name in rows[by]]
    figure = go.Figure(go.Bar(orientation="h", y=rows[by], x=rows["GB"], text=[f"{size:,.1f} GB" for size in rows["GB"]], textposition="outside", cliponaxis=False,
                              marker={"color": colors}, hovertemplate="%{y}: %{x:,.1f} GB<extra></extra>"))
    figure.update_xaxes(range=[0, rows["GB"].max() * 1.6])
    return charts.bar_list(figure, 34 * len(rows) + 12)
