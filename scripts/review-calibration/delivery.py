"""What development tasks delivered for the agent time, tokens and dollars they took, from the turn table that
`activity.py --save` keeps. A result is the agent's own report at the end of a development: the change is ready to
deliver, or it is not. A turn that answered a question or researched asked for no change, so its result does not count.
Every task with a result that counts is here, under the model and effort most of its turns ran with."""

from __future__ import annotations

import pandas
import plotly.graph_objects as go

import activity
import charts
import ranges

UNKNOWN = "not known"  # the setting of a task whose turns the database no longer holds
ALL = "all settings"
TASKS = ["task", "setting", "turns", "minutes", "tokens", "dollars", "results", "ready"]


def asked(turns):
    """Whether each turn's result counts: it reported a change ready, or not ready for any reason but that none was asked."""
    named = turns["notReady"] if "notReady" in turns else pandas.Series(None, index=turns.index, dtype=object)
    return turns["result"].notna() & (named != "no change asked")


def tasks(table, since=0.0):
    """One row per task that reported a development result in the turns started since a moment: its setting, turns, agent
    minutes, tokens, dollars and results, and how many of those said ready. Tokens and dollars are None for a task with a
    turn that does not record them, so a sum never passes for the whole."""
    turns = pandas.DataFrame(table["turns"])
    if len(turns):
        turns = turns[pandas.to_datetime(turns["start"], utc=True) >= pandas.Timestamp(since, unit="s", tz="UTC")]
    rows = []
    for task, own in turns[turns["task"].isin(turns.loc[asked(turns), "task"])].groupby("task", sort=False) if len(turns) else ():
        named = own[own["model"].notna()]
        setting = named.groupby(["model", named["effort"].fillna("no effort")]).size().idxmax() if len(named) else None
        rows.append({"task": task, "setting": f"{setting[0]} · {setting[1]}" if setting else UNKNOWN, "turns": len(own), "minutes": own["seconds"].sum() / 60,
                     "tokens": (own["inputTokens"] + own["outputTokens"]).sum() if own[["inputTokens", "outputTokens"]].notna().all().all() else None,
                     "dollars": own["cost"].sum() if own["cost"].notna().all() else None,
                     "results": int(asked(own).sum()), "ready": int((own["result"] == "ready").sum())})
    return pandas.DataFrame(rows, columns=TASKS)


def not_ready(table, since=0.0):
    """Why the results of the turns started since a moment were not ready, the most common first. A table saved before
    the reasons were named has none."""
    turns = pandas.DataFrame(table["turns"])
    if not len(turns) or "notReady" not in turns:
        return pandas.DataFrame(columns=["why", "results"])
    turns = turns[(pandas.to_datetime(turns["start"], utc=True) >= pandas.Timestamp(since, unit="s", tz="UTC")) & (turns["result"] == "not ready")]
    counted = turns["notReady"].fillna("not known").value_counts()
    return pandas.DataFrame({"why": counted.index, "results": counted.values})


def per_ready(rows, amount):
    """An amount per ready result, from the tasks that recorded it, drawn by task."""
    known = rows[rows[amount].notna()]
    return ranges.Ratio(tuple(known[amount].astype(float)), tuple(known["ready"].astype(float)))


def by_setting(rows):
    """For each setting, the one with the most tasks first, and for all of them together: tasks, results, the share that
    said ready, and the agent minutes, tokens and dollars per ready result. A share or minutes per ready come with the range
    they could lie in, drawn by task; with fewer than three tasks behind them there is none."""
    made = []
    groups = sorted(rows.groupby("setting"), key=lambda group: (-len(group[1]), group[0]))
    for setting, own in [*groups, *([(ALL, rows)] if len(groups) > 1 else [])]:
        share, minutes = ranges.Ratio(tuple(own["ready"].astype(float)), tuple(own["results"].astype(float)), 100), per_ready(own, "minutes")
        made.append({"setting": setting, "tasks": len(own), "results": int(own["results"].sum()), "ready": int(own["ready"].sum()),
                     "share": share.value(), "share range": ranges.range_of(share), "minutes": minutes.value(), "minutes range": ranges.range_of(minutes),
                     "tokens": per_ready(own, "tokens").value() if own["tokens"].notna().any() else None,
                     "dollars": per_ready(own, "dollars").value() if own["dollars"].notna().any() else None})
    return pandas.DataFrame(made)


def shown(table):
    """The table by setting as a reader sees it: numbers with their ranges in words."""
    within = lambda found, digits: ranges.said(found, digits) or "no range yet"
    return pandas.DataFrame([{"model and effort": row["setting"], "tasks": row["tasks"], "ready": f"{row['ready']} of {row['results']}",
                              "ready %": row["share"], "ready % range": within(row["share range"], 0), "minutes per ready": row["minutes"],
                              "minutes range": within(row["minutes range"], 0), "M tokens per ready": None if row["tokens"] is None else row["tokens"] / 1e6,
                              "dollars per ready": row["dollars"]} for row in table.to_dict("records")])


def never_ready(rows, top=10):
    """The tasks that reported results but never a ready one, the most agent time first: where development time went
    without a change to deliver."""
    stuck = rows[rows["ready"] == 0].sort_values("minutes", ascending=False).head(top)
    return pandas.DataFrame({"task": stuck["task"].str[:8], "model and effort": stuck["setting"], "results": stuck["results"], "turns": stuck["turns"],
                             "minutes": stuck["minutes"].round(1)})


def minutes_figure(table, mode, height=None):
    """Agent minutes per ready result for each setting as a bar, with the range it could lie in where there is one. A
    setting with no ready result has no bar."""
    drawn = table[(table["setting"] != ALL) & table["minutes"].notna()]
    names = [f"{name}  ({activity.count(amount, 'task')})" for name, amount in zip(drawn["setting"], drawn["tasks"])]
    figure = go.Figure(go.Bar(orientation="h", y=names, x=drawn["minutes"], marker={"color": charts.COLORS[mode]["model"]},
                              hovertemplate="%{y}: %{x:,.0f} minutes per ready result<extra></extra>"))
    # The ranges are a layer of their own, so a setting with none shows no whisker at all rather than one of no length.
    ranged = [(name, value, found) for name, value, found in zip(names, drawn["minutes"], drawn["minutes range"]) if found is not None]
    figure.add_scatter(y=[name for name, _, _ in ranged], x=[value for _, value, _ in ranged], mode="markers", marker={"color": "rgba(0,0,0,0)"}, showlegend=False,
                       error_x={"type": "data", "symmetric": False, "array": [found[1] - value for _, value, found in ranged],
                                "arrayminus": [value - found[0] for _, value, found in ranged], "color": charts.COLORS[mode]["ink"], "thickness": 1.5, "width": 4},
                       hovertemplate=[f"{name}: {found[0]:,.0f} to {found[1]:,.0f} minutes<extra></extra>" for name, _, found in ranged])
    figure.update_xaxes(visible=True, gridcolor=charts.COLORS[mode]["grid"], ticksuffix=" min", rangemode="tozero")
    return charts.tidy(figure.update_yaxes(autorange="reversed", ticksuffix="   ", showgrid=False), height or 40 * len(drawn) + 60)
