"""The saved turn table as pandas frames and Plotly figures for the dashboard. Nothing here reads a file or draws a page."""

from __future__ import annotations

from datetime import datetime

import pandas
import plotly.graph_objects as go

import activity

TOP_PROGRAMS = 5  # programs named in the chart; the rest are added up, as in the terminal table
TOP_TASKS = 10
# One step of each hue for each mode, checked against that mode's page surface: neighbours stay apart for color-blind
# readers and every mark clears the surface. Text never wears these colors.
COLORS = {
    "light": {"model": "#2a78d6", "tool": "#eb6834", "you": "#1baf7a", "other": "#8b929b", "surface": "#ffffff", "grid": "#e6e9ed",
              "usual": "#d5d9df", "warning": "#fab219", "critical": "#d03b3b"},
    "dark": {"model": "#3987e5", "tool": "#d95926", "you": "#199e70", "other": "#8b929b", "surface": "#0e1117", "grid": "#2a2f3a",
             "usual": "#3d4350", "warning": "#fab219", "critical": "#d03b3b"},
}
STATES = [state for state, _ in activity.STATES]
# A state keeps its color whatever else is saved.
KINDS = dict(zip(STATES, ("model", "tool", "you")))
STATE_OF = {part: state for state, parts in activity.STATES for part in parts}
# A completed turn is the usual case and stays quiet, so the eye goes to the turns that ended another way.
ENDINGS = {"TURN_COMPLETED": ("completed", "usual"), "INTERRUPTED": ("interrupted", "warning"), "TASK_FAILED": ("failed", "critical"), "": ("no end mark", "other")}


def frames(table, zone=None):
    """The saved turn table as four frames that share a turn number: one row per turn, and for each turn one row per
    part of its time, per program it ran, and per thing the model reacted to. Days are in the reader's own time zone."""
    rows = table["turns"]
    start = pandas.to_datetime([row["start"] for row in rows], utc=True).tz_convert(zone or datetime.now().astimezone().tzinfo)
    turns = pandas.DataFrame({"task": [row["task"] for row in rows], "start": start, "day": start.date, "seconds": [float(row["seconds"]) for row in rows],
                              "ended": [row["ended"] for row in rows], "parallel": [float(row["parallel"]) for row in rows],
                              "mostAtOnce": [int(row["mostAtOnce"]) for row in rows], "unfinished": [int(row["unfinished"]) for row in rows]})
    # A part this version does not know fails here, so a table saved by another version is refused and never drawn short.
    parts = pandas.DataFrame([(number, STATE_OF[part], part, float(length)) for number, row in enumerate(rows) for part, length in row["groups"].items()],
                             columns=["turn", "state", "part", "seconds"])
    programs = pandas.DataFrame([(number, name, float(made["seconds"]), int(made["runs"])) for number, row in enumerate(rows) for name, made in row["programs"].items()],
                                columns=["turn", "program", "seconds", "runs"])
    after = pandas.DataFrame([(number, before, float(made["seconds"]), int(made["times"])) for number, row in enumerate(rows) for before, made in row["after"].items()],
                             columns=["turn", "before", "seconds", "times"])
    return {"turns": turns, "parts": parts, "programs": programs, "after": after}


def within(made, first, last):
    """Only the turns that started from `first` to `last`, both days included, in every frame."""
    turns = made["turns"][(made["turns"]["day"] >= first) & (made["turns"]["day"] <= last)]
    return {"turns": turns, **{name: frame[frame["turn"].isin(turns.index)] for name, frame in made.items() if name != "turns"}}


def share(part, whole):
    """A part of a whole in percent. A part of nothing is 0."""
    return 100 * part / whole if whole else 0.0


def ranked(frame, name, amounts):
    """The sum of each amount for every name, the most seconds first; a tie goes by name, so the order never changes between two runs."""
    return frame.groupby(name, as_index=False)[amounts].sum().sort_values(["seconds", name], ascending=[False, True])


def state_seconds(parts):
    """Seconds in each of the three states, always all three and always in the same order."""
    return parts.groupby("state")["seconds"].sum().reindex(STATES, fill_value=0.0)


def part_rows(parts, state):
    """The parts of one state as (name, seconds, None), longest first, without those that would print as 0.0 minutes."""
    return [(row.part, row.seconds, None) for row in ranked(parts[parts["state"] == state], "part", ["seconds"]).itertuples() if row.seconds >= activity.SHOWN]


def program_rows(programs, top=TOP_PROGRAMS):
    """Shell time as (program, seconds, runs): the longest few by name, then every other program added up."""
    every = ranked(programs, "program", ["seconds", "runs"])
    rows = [(row.program, row.seconds, int(row.runs)) for row in every.head(top).itertuples()]
    if len(every) > top:
        rows.append(("every other program", float(every["seconds"].iloc[top:].sum()), int(every["runs"].iloc[top:].sum())))
    return rows


def model_rows(after):
    """Model time as (what just finished, seconds, times), longest first."""
    return [(row.before, row.seconds, int(row.times)) for row in ranked(after, "before", ["seconds", "times"]).itertuples() if row.seconds >= activity.SHOWN]


def tidy(figure, height, legend=False):
    figure.update_layout(height=height, margin={"l": 0, "r": 0, "t": 4, "b": 0}, showlegend=legend, barmode="stack", bargap=0.38, barcornerradius=4,
                         legend={"orientation": "h", "x": 0, "y": 1.02, "yanchor": "bottom", "traceorder": "normal"}, uniformtext={"minsize": 11, "mode": "hide"})
    return figure


def bar_list(figure, height, legend=False):
    """A list of bars with names on the left and no axis: the numbers are written at the bars."""
    figure.update_xaxes(visible=False, fixedrange=True)
    figure.update_yaxes(fixedrange=True, ticksuffix="   ", showgrid=False, autorange="reversed")
    return tidy(figure, height, legend)


def states_donut(parts, mode="light"):
    """All turn time as one ring cut into the three states, with the hours in the middle."""
    seconds = state_seconds(parts)
    # The outline has the color of the page, so it reads as a gap between two neighbours.
    # A share is written in the key as well, because a thin slice has no room for it.
    figure = go.Figure(go.Pie(labels=[f"{state}  {share(length, seconds.sum()):.1f}%" for state, length in seconds.items()], values=[length / 60 for length in seconds], hole=0.6,
                              sort=False, direction="clockwise", textposition="inside", insidetextorientation="horizontal", texttemplate="%{percent:.1%}", customdata=STATES,
                              marker={"colors": [COLORS[mode][KINDS[state]] for state in STATES], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                              hovertemplate="%{customdata}<br>%{value:,.1f} minutes · %{percent:.1%}<extra></extra>"))
    figure.add_annotation(text=f"{seconds.sum() / 3600:.1f} h", showarrow=False, font={"size": 24})
    return tidy(figure, 300, legend=True)


def endings_donut(turns, mode="light"):
    """How the turns ended, by the mark the log holds."""
    counted = turns["ended"].map(lambda mark: ENDINGS.get(mark, ENDINGS[""])[0]).value_counts()
    names = [name for name, _ in ENDINGS.values() if counted.get(name, 0)]
    colors = {name: COLORS[mode][kind] for name, kind in ENDINGS.values()}
    figure = go.Figure(go.Pie(labels=[f"{name}  {int(counted[name]):,}" for name in names], values=[int(counted[name]) for name in names], hole=0.6, sort=False,
                              direction="clockwise", textposition="inside", insidetextorientation="horizontal", texttemplate="%{value}", customdata=names,
                              marker={"colors": [colors[name] for name in names], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                              hovertemplate="%{customdata}<br>%{value} turns · %{percent:.1%}<extra></extra>"))
    figure.add_annotation(text=f"{len(turns):,} turns", showarrow=False, font={"size": 20})
    return tidy(figure, 300, legend=True)


def sunburst(parts, mode="light"):
    """Every part of the time: the three states in the inner ring, their parts in the outer ring. A part wears its state's color."""
    states = state_seconds(parts)
    ids, labels, parents, values, colors = list(STATES), list(STATES), [""] * len(STATES), [float(length) for length in states], [COLORS[mode][KINDS[state]] for state in STATES]
    for state in STATES:
        for part, length, _ in part_rows(parts, state):
            ids.append(f"{state}/{part}"), labels.append(part), parents.append(state), values.append(float(length)), colors.append(COLORS[mode][KINDS[state]])
    figure = go.Figure(go.Sunburst(ids=ids, labels=labels, parents=parents, values=values, branchvalues="total", sort=False, insidetextorientation="radial",
                                   texttemplate="%{label} %{percentRoot:.1%}",
                                   marker={"colors": colors, "line": {"color": COLORS[mode]["surface"], "width": 2}}, customdata=[length / 60 for length in values],
                                   hovertemplate="%{label}<br>%{customdata:,.1f} minutes · %{percentRoot:.1%} of all turn time<extra></extra>"))
    return tidy(figure, 460)


def days_figure(turns, parts, mode="light"):
    """Hours of turn time for each day, cut into the three states."""
    daily = parts.merge(turns[["day"]], left_on="turn", right_index=True).groupby(["day", "state"])["seconds"].sum().unstack(fill_value=0.0).reindex(columns=STATES, fill_value=0.0)
    figure = go.Figure()
    for state in STATES:
        figure.add_bar(x=list(daily.index), y=list(daily[state] / 3600), name=state, marker={"color": COLORS[mode][KINDS[state]], "line": {"color": COLORS[mode]["surface"], "width": 1}},
                       hovertemplate="%{x|%b %d}<br>" + state + ": %{y:.1f} hours<extra></extra>")
    figure.update_xaxes(fixedrange=True, showgrid=False, tickformat="%b %d")
    figure.update_yaxes(fixedrange=True, title_text="hours", gridcolor=COLORS[mode]["grid"], zeroline=False)
    return tidy(figure, 300, legend=True)


def bars_figure(rows, whole, color, counted=None):
    """One bar per row with its numbers at the tip. Every bar is measured against the same whole, so its length is its share."""
    text = [f"{length / 60:,.1f} min · {share(length, whole):.1f}%" + (f" · {amount:,} {counted}" if counted else "") for _, length, amount in rows]
    figure = go.Figure(go.Bar(orientation="h", y=[name for name, _, _ in rows], x=[length / 60 for _, length, _ in rows], text=text, textposition="outside", cliponaxis=False,
                              marker={"color": color}, hovertemplate="%{y}<br>%{text}<extra></extra>"))
    # The longest bar takes about half the width, which leaves room for the numbers at its tip.
    figure.update_xaxes(range=[0, max(length for _, length, _ in rows) / 60 * 1.9])
    return bar_list(figure, 34 * len(rows) + 12)


def tasks_figure(turns, parts, mode="light", top=TOP_TASKS):
    """The tasks that took the most turn time, each cut into the three states. A task is named by the start of its id."""
    by_task = parts.merge(turns[["task"]], left_on="turn", right_index=True).groupby(["task", "state"])["seconds"].sum().unstack(fill_value=0.0).reindex(columns=STATES, fill_value=0.0)
    longest = by_task.loc[by_task.sum(axis=1).sort_values(ascending=False).head(top).index]
    figure = go.Figure()
    for state in STATES:
        figure.add_bar(orientation="h", y=list(longest.index), x=list(longest[state] / 60), name=state,
                       marker={"color": COLORS[mode][KINDS[state]], "line": {"color": COLORS[mode]["surface"], "width": 1}},
                       hovertemplate="%{y}<br>" + state + ": %{x:,.1f} minutes<extra></extra>")
    figure.update_yaxes(tickvals=list(longest.index), ticktext=[task[:13] for task in longest.index])
    return bar_list(figure, 30 * len(longest) + 40, legend=True)
