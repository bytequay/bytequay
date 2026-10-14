"""The saved turn table as pandas frames and Plotly figures for the dashboard. Nothing here reads a file or draws a page."""

from __future__ import annotations

from datetime import datetime
import math

import pandas
import plotly.graph_objects as go

import activity

TOP_PROGRAMS = 5  # programs named in the chart; the rest are added up, as in the terminal table
TOP_TASKS = 10
MIN_TURNS = 5  # a model and effort with fewer turns is listed in the table and left out of the charts
TOP_SETTINGS = 8
TOP_RUNS = 15
# How a wait for the user ended. An answer is the usual case and stays quiet; a wait that ran out ended the turn.
ANSWERS = {"allowed": "usual", "denied": "serious", "cancelled": "warning", "expired": "critical"}
WAITS = (("under 30 s", 30), ("30 s to 1 min", 60), ("1 to 2 min", 120), ("2 to 5 min", 300), ("5 min or more", float("inf")))
NOTICES = {"rate limit: allowed warning": "warning", "rate limit: rejected": "critical", "retry": "other"}
# What became of a review's finding. The user or a development agent marks one resolved, the reviewer itself withdraws one, and an open one is not decided
# yet. So a resolved finding is read as one that held up, and a withdrawn one as one that did not.
FINDINGS = {"resolved": "model", "withdrawn": "serious", "open": "usual"}
RUN_ENDINGS = {"accepted": "usual", "incomplete": "serious", "stopped": "warning", "failed": "critical"}
SEVERITIES = ("high", "medium", "low")
# What a scored run made of a human reviewer's comment, best first. A judge error is its own part and is never drawn as missed.
VERDICTS = {"found": "model", "partly": "lighter", "missed": "usual", "errors": "warning"}
SCORE_AMOUNTS = ("comments", *VERDICTS, "leads", "extra", "inputTokens", "cachedInputTokens", "outputTokens", "seconds", "judgeInputTokens", "judgeOutputTokens")
# The three kinds of tokens a turn is billed for, and the color each wears in the token charts. Fresh input is what was
# not read from the cache. Cached input is most of the tokens and the cheapest, so it stays quiet and the eye goes to the other two.
TOKEN_KINDS = {"fresh input": "model", "cached input": "usual", "output": "tool"}
PRICES = ("input", "cachedInput", "output")  # dollars for a million tokens of each kind, in the order of TOKEN_KINDS
EFFORTS = ("low", "medium", "high", "extra", "max")
MIN_RUNS = 5  # a program or tool with fewer calls is in the table and out of the charts, where one failure would read as a rate
TOP_NAMES = 6  # the names written at their bubbles
TOP_PROGRAMS_COMPARED = 8  # the programs compared between providers
BUBBLE = 44  # pixels across for the program or tool that took the most time
SMALLEST = 5  # pixels from the middle to the edge of the smallest bubble
LIFTS = (14, 32, 50)  # pixels from the edge of a bubble up to its name; along the axis the names take these heights in turn
UNSAID = (activity.OTHER_REASON, activity.NOTHING_KEPT)  # a failure whose reason the words it printed do not give; these stay quiet in the chart
# Calls the model asked for together stay quiet; the short hops a merge could save stand out; a long step is the model's own time.
GAP_COLORS = dict(zip(activity.GAPS, ("usual", "tool", "model")))
TOP_PAIRS = 10  # the pairs of groups listed with the most time in short hops
# The two kinds of call, the name each goes by and the color it wears. A command wears the color of waiting for a tool, as shell time does.
CALLS = {"command": ("command", "tool"), "tool": ("tool call", "model")}
# One step of each hue for each mode, checked against that mode's page surface: neighbours stay apart for color-blind
# readers and every mark clears the surface. Text never wears these colors.
COLORS = {
    "light": {"model": "#2a78d6", "tool": "#eb6834", "you": "#1baf7a", "other": "#8b929b", "surface": "#ffffff", "grid": "#e6e9ed",
              "usual": "#d5d9df", "warning": "#fab219", "serious": "#ec835a", "critical": "#d03b3b", "scale": ("#cde2fb", "#5598e7"), "ink": "#1f2328", "lighter": "#86b6ef"},
    "dark": {"model": "#3987e5", "tool": "#d95926", "you": "#199e70", "other": "#8b929b", "surface": "#0e1117", "grid": "#2a2f3a",
             "usual": "#3d4350", "warning": "#fab219", "serious": "#ec835a", "critical": "#d03b3b", "scale": ("#0d366b", "#2a78d6"), "ink": "#ffffff", "lighter": "#1c5cab"},
}
STATES = [state for state, _ in activity.STATES]
# A state keeps its color whatever else is saved.
KINDS = dict(zip(STATES, ("model", "tool", "you")))
STATE_OF = {part: state for state, parts in activity.STATES for part in parts}
# A completed turn is the usual case and stays quiet, so the eye goes to the turns that ended another way.
# Any other ending, such as a turn with no end mark, keeps its own name and the neutral color.
ENDINGS = {"completed": "usual", "cancelled": "warning", "interrupted": "serious", "failed": "critical"}


def frames(table, zone=None):
    """The saved turn table as four frames that share a turn number: one row per turn, and for each turn one row per
    part of its time, per program it ran, and per thing the model reacted to. Days are in the reader's own time zone."""
    rows = table["turns"]
    start = pandas.to_datetime([row["start"] for row in rows], utc=True).tz_convert(zone or datetime.now().astimezone().tzinfo)
    turns = pandas.DataFrame({"task": [row["task"] for row in rows], "start": start, "day": start.date, "seconds": [float(row["seconds"]) for row in rows],
                              "logged": [bool(row["logged"]) for row in rows], "outcome": [str(row["outcome"]) for row in rows],
                              "parallel": [float(row["parallel"]) for row in rows], "mostAtOnce": [int(row["mostAtOnce"]) for row in rows],
                              "unfinished": [int(row["unfinished"]) for row in rows], **{name: [row[name] for row in rows] for name in activity.FACTS}})
    # A turn is compared under its model and effort together; one the database does not know has neither.
    turns["setting"] = [None if row["model"] is None else f"{row['model']} · {row['effort'] or 'no effort'}" for row in rows]
    # A part this version does not know fails here, so a table saved by another version is refused and never drawn short.
    parts = pandas.DataFrame([(number, STATE_OF[part], part, float(length)) for number, row in enumerate(rows) for part, length in row["groups"].items()],
                             columns=["turn", "state", "part", "seconds"])
    programs = pandas.DataFrame([(number, name, float(made["seconds"]), int(made["runs"])) for number, row in enumerate(rows) for name, made in row["programs"].items()],
                                columns=["turn", "program", "seconds", "runs"])
    after = pandas.DataFrame([(number, before, float(made["seconds"]), int(made["times"])) for number, row in enumerate(rows) for before, made in row["after"].items()],
                             columns=["turn", "before", "seconds", "times"])
    runs = pandas.DataFrame([(number, run["program"], float(run["seconds"])) for number, row in enumerate(rows) for run in row["longRuns"]], columns=["turn", "program", "seconds"])
    waits = pandas.DataFrame([(number, wait["kind"], wait["outcome"], float(wait["seconds"])) for number, row in enumerate(rows) for wait in row["approvals"]],
                             columns=["turn", "kind", "outcome", "seconds"])
    notices = pandas.DataFrame([(number, what, int(times)) for number, row in enumerate(rows) for what, times in row["notices"].items()], columns=["turn", "what", "times"])
    return {"turns": turns, "parts": parts, "programs": programs, "after": after, "runs": runs, "waits": waits, "notices": notices}


def only(made, keep):
    """Only the turns for which `keep` is true, in every frame."""
    turns = made["turns"][keep]
    return {"turns": turns, **{name: frame[frame["turn"].isin(turns.index)] for name, frame in made.items() if name != "turns"}}


def within(made, first, last):
    """Only the turns that started from `first` to `last`, both days included."""
    return only(made, (made["turns"]["day"] >= first) & (made["turns"]["day"] <= last))


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
    """How the turns ended: as the database says where it knows the turn, else by the mark the log holds."""
    counted = turns["outcome"].value_counts()
    names = outcomes(turns)
    colors = {name: COLORS[mode][ENDINGS.get(name, "other")] for name in names}
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


def outcomes(turns):
    """The endings found among these turns: the known ones in a fixed order, then any other by name."""
    found = set(turns["outcome"])
    return [name for name in ENDINGS if name in found] + sorted(found - set(ENDINGS))


def settings(turns, logged=False):
    """Each model and effort with enough turns to draw, the one with the most turns first, and how many turns it has."""
    known = turns[turns["setting"].notna() & (turns["logged"] | (not logged))]
    counted = known.groupby("setting").size()
    return sorted(((name, int(amount)) for name, amount in counted.items() if amount >= MIN_TURNS), key=lambda made: (-made[1], made[0]))


def whole_or_none(amount):
    return None if pandas.isna(amount) else round(float(amount))


def settings_table(turns):
    """One row for every model and effort the database knows, however few turns it has. "Typical" is the middle turn:
    half of the turns are below it and half above, so a few very long turns do not move it."""
    rows = []
    for name, group in turns[turns["setting"].notna()].groupby("setting"):
        rows.append({"model and effort": name, "turns": len(group), "tasks": int(group["task"].nunique()),
                     "hours": round(float(group["seconds"].sum()) / 3600, 1), "typical minutes": round(float(group["seconds"].median()) / 60, 1),
                     "typical tokens in": whole_or_none(group["inputTokens"].median()),
                     "cached %": whole_or_none(share(group["cachedInputTokens"].sum(), group["inputTokens"].sum())) if group["inputTokens"].sum() else None,
                     "typical tokens out": whole_or_none(group["outputTokens"].median()), "typical tool calls": whole_or_none(group["toolCalls"].median()),
                     "completed %": round(share(int((group["outcome"] == "completed").sum()), len(group)))})
    return sorted(rows, key=lambda row: (-row["turns"], row["model and effort"]))


def named(drawn):
    """A bar is named with its number of turns, so nobody reads a share of 6 turns like a share of 200."""
    return [f"{name}  ({activity.count(amount, 'turn')})" for name, amount in drawn]


def heat_figure(turns, mode="light"):
    """The typical turn in minutes for every model and effort. A cell with too few turns says how few and has no color."""
    known = turns[turns["setting"].notna()].assign(effort=lambda frame: frame["effort"].fillna("no effort"))
    efforts = [effort for effort in EFFORTS if effort in set(known["effort"])] + sorted(set(known["effort"]) - set(EFFORTS))
    models = sorted(set(known["model"]), key=lambda model: (-int((known["model"] == model).sum()), model))
    minutes, counted = known.groupby(["model", "effort"])["seconds"].median() / 60, known.groupby(["model", "effort"]).size()
    cell = lambda model, effort: (minutes.get((model, effort)), int(counted.get((model, effort), 0)))
    figure = go.Figure(go.Heatmap(
        x=efforts, y=models, z=[[cell(model, effort)[0] if cell(model, effort)[1] >= MIN_TURNS else None for effort in efforts] for model in models],
        text=[["" if not cell(model, effort)[1] else f"{cell(model, effort)[0]:.1f} min<br>{activity.count(cell(model, effort)[1], 'turn')}" if cell(model, effort)[1] >= MIN_TURNS
               else activity.count(cell(model, effort)[1], "turn") for effort in efforts] for model in models],
        texttemplate="%{text}", textfont={"color": COLORS[mode]["ink"]}, colorscale=[[0, COLORS[mode]["scale"][0]], [1, COLORS[mode]["scale"][1]]], showscale=False, xgap=3, ygap=3,
        hovertemplate="%{y} · %{x}<br>%{text}<extra></extra>", hoverongaps=False))
    figure.update_xaxes(side="top", fixedrange=True, showgrid=False)
    figure.update_yaxes(fixedrange=True, showgrid=False, autorange="reversed", ticksuffix="   ")
    return tidy(figure, 56 * len(models) + 40)


def split_figure(turns, parts, mode="light"):
    """How each model and effort split its turn time between the three states, as shares so that the bars compare."""
    drawn = settings(turns, logged=True)
    seconds = (parts.merge(turns[["setting"]], left_on="turn", right_index=True).groupby(["setting", "state"])["seconds"].sum().unstack(fill_value=0.0)
               .reindex(index=[name for name, _ in drawn], columns=STATES, fill_value=0.0))
    figure = go.Figure()
    for state in STATES:
        shares = [share(seconds.loc[name, state], seconds.loc[name].sum()) for name, _ in drawn]
        figure.add_bar(orientation="h", y=named(drawn), x=shares, name=state, text=[f"{part:.0f}%" for part in shares], textposition="inside", insidetextanchor="middle",
                       marker={"color": COLORS[mode][KINDS[state]], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                       hovertemplate="%{y}<br>" + state + ": %{x:.1f}%<extra></extra>")
    return bar_list(figure, 40 * len(drawn) + 40, legend=True)


def lengths_figure(turns, mode="light"):
    """How long a turn took under each model and effort. The box holds the middle half of the turns and the line in it is the typical turn."""
    drawn = settings(turns)
    figure = go.Figure()
    for label, (name, _) in zip(named(drawn), drawn):
        figure.add_box(x=list(turns[turns["setting"] == name]["seconds"] / 60), name=label, orientation="h", boxpoints="outliers", marker={"color": COLORS[mode]["model"], "size": 4},
                       line={"width": 1.5}, hoverinfo="x")
    # A few turns are a hundred times longer than most, so each step along the axis is ten times the one before.
    figure.update_xaxes(type="log", dtick=1, title_text="minutes", fixedrange=True, gridcolor=COLORS[mode]["grid"], zeroline=False)
    figure.update_yaxes(fixedrange=True, showgrid=False, autorange="reversed", ticksuffix="   ")
    return tidy(figure, 46 * len(drawn) + 60)


def outcomes_figure(turns, mode="light"):
    """How the turns of each model and effort ended, as shares so that the bars compare."""
    drawn = settings(turns)
    known = turns[turns["setting"].notna()]
    counted = known.groupby(["setting", "outcome"]).size().unstack(fill_value=0).reindex(index=[name for name, _ in drawn], fill_value=0)
    figure = go.Figure()
    for outcome in outcomes(known[known["setting"].isin(counted.index)]):
        amounts = [int(counted.loc[name, outcome]) for name, _ in drawn]
        figure.add_bar(orientation="h", y=named(drawn), x=[share(amount, total) for amount, (_, total) in zip(amounts, drawn)], name=outcome, customdata=amounts,
                       text=[f"{amount:,}" if amount else "" for amount in amounts], textposition="inside", insidetextanchor="middle",
                       marker={"color": COLORS[mode][ENDINGS.get(outcome, "other")], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                       hovertemplate="%{y}<br>" + outcome + ": %{customdata} turns · %{x:.1f}%<extra></extra>")
    return bar_list(figure, 40 * len(drawn) + 40, legend=True)


def tokens(turns):
    """The tokens of each turn by kind, for the turns whose tokens are known."""
    known = turns[turns["inputTokens"].notna()]
    cached = known["cachedInputTokens"].fillna(0).astype(float)
    return pandas.DataFrame({"fresh input": known["inputTokens"].astype(float) - cached, "cached input": cached, "output": known["outputTokens"].fillna(0).astype(float)},
                            index=known.index)


def short(amount):
    """A number of tokens in the largest unit that keeps it above one: 7.0 K, 1.2 M, 3.4 B."""
    for least, unit in ((1e9, "B"), (1e6, "M"), (1e3, "K")):
        if amount >= least:
            return f"{amount / least:,.1f} {unit}"
    return f"{amount:,.0f}"


def tokens_donut(turns, mode="light"):
    """All tokens as one ring cut by kind, with their number in the middle."""
    summed = tokens(turns).sum()
    figure = go.Figure(go.Pie(labels=[f"{kind}  {share(summed[kind], summed.sum()):.1f}%" for kind in TOKEN_KINDS], values=[summed[kind] / 1e6 for kind in TOKEN_KINDS], hole=0.6,
                              sort=False, direction="clockwise", textposition="inside", insidetextorientation="horizontal", texttemplate="%{percent:.1%}", customdata=list(TOKEN_KINDS),
                              marker={"colors": [COLORS[mode][kind] for kind in TOKEN_KINDS.values()], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                              hovertemplate="%{customdata}<br>%{value:,.1f} million tokens · %{percent:.1%}<extra></extra>"))
    figure.add_annotation(text=short(summed.sum()), showarrow=False, font={"size": 22})
    return tidy(figure, 300, legend=True)


def token_bars(figure, summed, names, mode, across):
    """One bar for every row of `summed`, cut by kind of token, in millions."""
    for kind, color in TOKEN_KINDS.items():
        amounts = list(summed[kind] / 1e6)
        figure.add_bar(name=kind, marker={"color": COLORS[mode][color], "line": {"color": COLORS[mode]["surface"], "width": 1}}, orientation="h" if across else "v",
                       **({"y": names, "x": amounts} if across else {"x": names, "y": amounts}),
                       hovertemplate=("%{y}<br>" if across else "%{x|%b %d}<br>") + kind + (": %{x:,.1f}" if across else ": %{y:,.1f}") + " million tokens<extra></extra>")
    return figure


def tokens_days_figure(turns, mode="light"):
    """Millions of tokens for each day, cut by kind."""
    daily = tokens(turns).join(turns["day"]).groupby("day").sum()
    figure = token_bars(go.Figure(), daily, list(daily.index), mode, across=False)
    figure.update_xaxes(fixedrange=True, showgrid=False, tickformat="%b %d")
    figure.update_yaxes(fixedrange=True, title_text="million tokens", gridcolor=COLORS[mode]["grid"], zeroline=False)
    return tidy(figure, 300, legend=True)


def tokens_settings_figure(turns, mode="light", top=TOP_SETTINGS):
    """The models and efforts that used the most tokens, cut by kind."""
    summed = tokens(turns).join(turns["setting"]).groupby("setting").sum()
    most = summed.loc[summed.sum(axis=1).sort_values(ascending=False, kind="stable").head(top).index]
    counted = turns[turns["inputTokens"].notna()].groupby("setting").size()
    figure = token_bars(go.Figure(), most, named([(name, int(counted[name])) for name in most.index]), mode, across=True)
    return bar_list(figure, 34 * len(most) + 40, legend=True)


def tokens_tasks_figure(turns, mode="light", top=TOP_TASKS):
    """The tasks that used the most tokens, cut by kind. A task is named by the start of its id."""
    summed = tokens(turns).join(turns["task"]).groupby("task").sum()
    most = summed.loc[summed.sum(axis=1).sort_values(ascending=False, kind="stable").head(top).index]
    figure = token_bars(go.Figure(), most, list(most.index), mode, across=True)
    figure.update_yaxes(tickvals=list(most.index), ticktext=[task[:13] for task in most.index])
    return bar_list(figure, 30 * len(most) + 40, legend=True)


def price_table(made):
    """The reader's own prices as {model: dollars for a million tokens of each kind}. A model whose prices are all zero
    has no price yet. A table in another shape fails, so a wrong price is never used quietly."""
    if made is None:
        return {}
    table = {}
    for model, price in made["dollarsPerMillionTokens"].items():
        amounts = tuple(float(price[name]) for name in PRICES)
        if any(amount < 0 for amount in amounts):
            raise ValueError(f"a price of {model} is below zero")
        if any(amounts):
            table[model] = amounts
    return table


def dollars(turns, prices):
    """What each turn cost and how the lab knows it: `recorded` by the provider's own command line, `estimated` from the
    reader's price table and the turn's tokens, or `unknown`."""
    kinds, cost, how = tokens(turns), [], []
    for number, row in turns.iterrows():
        if pandas.notna(row["cost"]):
            cost.append(float(row["cost"])), how.append("recorded")
        elif row["model"] in prices and number in kinds.index:
            cost.append(sum(kinds.loc[number, kind] * price for kind, price in zip(TOKEN_KINDS, prices[row["model"]])) / 1e6), how.append("estimated")
        else:
            cost.append(float("nan")), how.append("unknown")
    return turns.assign(dollars=cost, how=how)


def unpriced(costed):
    """The models with tokens and no cost: neither recorded nor in the price table."""
    return sorted(set(costed[(costed["how"] == "unknown") & costed["inputTokens"].notna() & costed["model"].notna()]["model"]))


def cost_table(costed):
    """One row for every model and effort: its tokens, and its cost as far as it is known."""
    rows = []
    for name, group in costed[costed["setting"].notna()].groupby("setting"):
        kinds, known = tokens(group).sum(), group[group["how"] != "unknown"]
        rows.append({"model and effort": name, "turns": len(group), "tokens in, M": round(float(kinds["fresh input"] + kinds["cached input"]) / 1e6, 1),
                     "cached %": whole_or_none(share(kinds["cached input"], kinds["fresh input"] + kinds["cached input"])) if kinds["fresh input"] + kinds["cached input"] else None,
                     "tokens out, M": round(float(kinds["output"]) / 1e6, 2),
                     "dollars": round(float(known["dollars"].sum()), 2) if len(known) else None, "turns with a cost": len(known),
                     "how": ", ".join(sorted(set(known["how"]))) or "unknown", "typical turn, $": round(float(known["dollars"].median()), 2) if len(known) else None,
                     # What a finished turn cost, with the turns that did not finish paid for as well.
                     "completed turn, $": round(float(known["dollars"].sum()) / completed, 2) if (completed := int((known["outcome"] == "completed").sum())) else None})
    return sorted(rows, key=lambda row: (-(row["dollars"] or 0), -row["tokens in, M"], row["model and effort"]))


def dollars_days_figure(costed, mode="light"):
    """Dollars for each day: what was recorded, and on top of it what was estimated."""
    daily = costed[costed["how"] != "unknown"].groupby(["day", "how"])["dollars"].sum().unstack(fill_value=0.0).reindex(columns=["recorded", "estimated"], fill_value=0.0)
    figure = go.Figure()
    for how, color in (("recorded", "model"), ("estimated", "lighter")):
        if daily[how].any():
            figure.add_bar(x=list(daily.index), y=list(daily[how]), name=how, marker={"color": COLORS[mode][color], "line": {"color": COLORS[mode]["surface"], "width": 1}},
                           hovertemplate="%{x|%b %d}<br>" + how + ": $%{y:,.2f}<extra></extra>")
    figure.update_xaxes(fixedrange=True, showgrid=False, tickformat="%b %d")
    figure.update_yaxes(fixedrange=True, title_text="dollars", gridcolor=COLORS[mode]["grid"], zeroline=False)
    return tidy(figure, 280, legend=True)


def long_rows(runs, top=TOP_PROGRAMS):
    """Time in long single commands as (program, seconds, runs): the longest few programs by name, then every other one added up."""
    return program_rows(runs.assign(runs=1), top)


def longest_runs(runs, turns, top=TOP_RUNS):
    """The longest single commands, each with its program, its minutes, the start of its task's id and its day."""
    longest = runs.sort_values(["seconds", "program", "turn"], ascending=[False, True, True]).head(top)
    return [{"program": run.program, "minutes": round(run.seconds / 60, 1), "task": turns.loc[run.turn, "task"][:13], "day": turns.loc[run.turn, "day"].isoformat()}
            for run in longest.itertuples()]


def answers(waits):
    """How the waits ended: the known endings in a fixed order, then any other by name."""
    found = set(waits["outcome"])
    return [name for name in ANSWERS if name in found] + sorted(found - set(ANSWERS))


def waits_donut(waits, mode="light"):
    """How the waits for the user ended, with their number in the middle."""
    counted, names = waits["outcome"].value_counts(), answers(waits)
    figure = go.Figure(go.Pie(labels=[f"{name}  {int(counted[name]):,}" for name in names], values=[int(counted[name]) for name in names], hole=0.6, sort=False,
                              direction="clockwise", textposition="inside", insidetextorientation="horizontal", texttemplate="%{value}", customdata=names,
                              marker={"colors": [COLORS[mode][ANSWERS.get(name, "other")] for name in names], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                              hovertemplate="%{customdata}<br>%{value} waits · %{percent:.1%}<extra></extra>"))
    figure.add_annotation(text=f"{len(waits):,} asked", showarrow=False, font={"size": 20})
    return tidy(figure, 300, legend=True)


def waits_figure(waits, mode="light"):
    """How long the user took to answer: the number of waits in each band of time."""
    counted, least = [], 0
    for _, below in WAITS:
        counted.append(int(((waits["seconds"] >= least) & (waits["seconds"] < below)).sum()))
        least = below
    figure = go.Figure(go.Bar(x=[name for name, _ in WAITS], y=counted, text=[f"{amount:,}" if amount else "" for amount in counted], textposition="outside", cliponaxis=False,
                              marker={"color": COLORS[mode]["you"]}, hovertemplate="%{x}<br>%{y} waits<extra></extra>"))
    figure.update_xaxes(fixedrange=True, showgrid=False)
    figure.update_yaxes(fixedrange=True, visible=False, range=[0, max(counted) * 1.25 if counted and max(counted) else 1])
    return tidy(figure, 300)


def notices_figure(notices, turns, mode="light"):
    """Rate limit notices and retries for each day, by what the provider said."""
    daily = notices.merge(turns[["day"]], left_on="turn", right_index=True).groupby(["day", "what"])["times"].sum().unstack(fill_value=0)
    figure = go.Figure()
    for what in [name for name in NOTICES if name in daily.columns] + sorted(set(daily.columns) - set(NOTICES)):
        figure.add_bar(x=list(daily.index), y=[int(amount) for amount in daily[what]], name=what,
                       marker={"color": COLORS[mode][NOTICES.get(what, "serious")], "line": {"color": COLORS[mode]["surface"], "width": 1}},
                       hovertemplate="%{x|%b %d}<br>" + what + ": %{y}<extra></extra>")
    figure.update_xaxes(fixedrange=True, showgrid=False, tickformat="%b %d")
    figure.update_yaxes(fixedrange=True, title_text="times", gridcolor=COLORS[mode]["grid"], zeroline=False)
    return tidy(figure, 280, legend=True)


def repositories_figure(turns, parts, mode="light"):
    """Hours of turn time for each repository, cut into the three states. A turn the database does not know has no repository."""
    named_turns = turns.assign(repository=turns["repository"].fillna("not recorded"))
    seconds = parts.merge(named_turns[["repository"]], left_on="turn", right_index=True).groupby(["repository", "state"])["seconds"].sum().unstack(fill_value=0.0)
    seconds = seconds.reindex(columns=STATES, fill_value=0.0)
    most = seconds.loc[seconds.sum(axis=1).sort_values(ascending=False, kind="stable").index]
    figure = go.Figure()
    for state in STATES:
        figure.add_bar(orientation="h", y=list(most.index), x=list(most[state] / 3600), name=state,
                       marker={"color": COLORS[mode][KINDS[state]], "line": {"color": COLORS[mode]["surface"], "width": 1}},
                       hovertemplate="%{y}<br>" + state + ": %{x:.1f} hours<extra></extra>")
    return bar_list(figure, 34 * len(most) + 40, legend=True)


def call_frames(table):
    """The calls of the saved turns, each with its turn's number in `frames`. A table saved before calls were counted, or one
    with a kind of call this version does not know, fails here and is never drawn as a table without calls."""
    # Only a command says how much it printed and why it failed: the log holds no output of a tool.
    calls = pandas.DataFrame([(number, call["kind"], call["name"], int(call["runs"]), int(call["failed"]), int(call["noMatch"]), float(call["seconds"]),
                               int(call["characters"]) if call["kind"] == "command" else None)
                              for number, row in enumerate(table["turns"]) for call in row["calls"]], columns=["turn", "kind", "name", "runs", "failed", "noMatch", "seconds", "characters"])
    if not calls["kind"].isin(CALLS).all():
        raise ValueError("a call is neither a command nor a tool call")
    reasons = pandas.DataFrame([(number, call["name"], reason, int(times)) for number, row in enumerate(table["turns"]) for call in row["calls"] if call["kind"] == "command"
                                for reason, times in call["reasons"].items()], columns=["turn", "program", "reason", "times"])
    hops = pandas.DataFrame([(number, hop["from"], hop["to"], hop["gap"], int(hop["times"]), float(hop["seconds"])) for number, row in enumerate(table["turns"]) for hop in row["hops"]],
                            columns=["turn", "from", "to", "gap", "times", "seconds"])
    if not hops["gap"].isin(activity.GAPS).all():
        raise ValueError("a kind of gap this version does not know")
    repeats = pandas.DataFrame([(number, made["program"], int(made["runs"]), float(made["seconds"]), int(made["unchangedRuns"]), float(made["unchangedSeconds"]))
                                for number, row in enumerate(table["turns"]) for made in row["repeats"]], columns=["turn", "program", "runs", "seconds", "unchangedRuns", "unchangedSeconds"])
    return {"calls": calls, "reasons": reasons, "hops": hops, "repeats": repeats}


def calls_of(made, turns):
    """Only the calls of these turns, in every frame."""
    return {name: frame[frame["turn"].isin(turns.index)] for name, frame in made.items()}


def call_sums(calls):
    """Every command program and every tool with its calls, its failures, its searches that found nothing and its seconds added up.
    The most calls first; a tie goes by name, so the order never changes between two runs."""
    summed = calls.groupby(["kind", "name"], as_index=False)[["runs", "failed", "noMatch", "seconds"]].sum()
    return summed.sort_values(["runs", "name", "kind"], ascending=[False, True, True], ignore_index=True)


def calls_table(calls):
    """One row for every command program and every tool, the most calls first."""
    return [{"name": row.name, "kind": CALLS[row.kind][0], "calls": int(row.runs), "failed": int(row.failed), "failed %": round(share(row.failed, row.runs), 1),
             "found nothing": int(row.noMatch), "minutes": round(row.seconds / 60, 1), "seconds a call": round(row.seconds / row.runs, 1)} for row in call_sums(calls).itertuples(index=False)]


def candidates(calls):
    """The programs and tools with enough calls for a failure rate to mean something."""
    summed = call_sums(calls)
    return summed[summed["runs"] >= MIN_RUNS]


def candidates_figure(calls, mode="light", top=TOP_NAMES):
    """One bubble for every program and tool with enough calls: across, how often it was called; up, how often it failed;
    its size, the time it took. The few that failed most often are named, and every bubble says what it is under the pointer."""
    drawn = candidates(calls).assign(rate=lambda frame: [share(failed, runs) for failed, runs in zip(frame["failed"], frame["runs"])])
    most = drawn[drawn["failed"] > 0].sort_values(["failed", "name"], ascending=[False, True]).head(top)
    # Plotly draws a bubble of this size with an area of size / sizeref, so the longest is BUBBLE pixels across.
    sizeref = 2 * max(float(drawn["seconds"].max()) if len(drawn) else 0.0, 1.0) / BUBBLE ** 2
    figure = go.Figure()
    for kind, (label, color) in CALLS.items():
        own = drawn[drawn["kind"] == kind]
        # A call that took no time at all still gets the smallest bubble, so it is not lost.
        figure.add_scatter(x=list(own["runs"]), y=list(own["rate"]), mode="markers", name=label, cliponaxis=False,
                           customdata=[(name, int(failed), seconds / 60) for name, failed, seconds in zip(own["name"], own["failed"], own["seconds"])],
                           marker={"size": [max(seconds, 1.0) for seconds in own["seconds"]], "sizemode": "area", "sizeref": sizeref, "sizemin": SMALLEST,
                                   "color": COLORS[mode][color], "opacity": 0.85, "line": {"color": COLORS[mode]["surface"], "width": 2}},
                           hovertemplate="%{customdata[0]}<br>%{x:,} calls · %{customdata[1]:,} failed · %{y:.1f}%<br>%{customdata[2]:,.1f} minutes<extra></extra>")
    # A name stands above its bubble at the end of a thin line. Along the axis the names take three heights in turn,
    # so two neighbours do not write over each other.
    for place, row in enumerate(most.sort_values(["runs", "name"]).itertuples()):
        edge = max(math.sqrt(max(row.seconds, 1.0) / 2 / sizeref), SMALLEST)
        figure.add_annotation(x=math.log10(row.runs), y=row.rate, text=row.name, ax=0, ay=-(edge + LIFTS[place % len(LIFTS)]), standoff=edge, showarrow=True, arrowhead=0, arrowwidth=1,
                              arrowcolor=COLORS[mode]["other"], font={"color": COLORS[mode]["ink"]})
    highest = max(float(drawn["rate"].max()) if len(drawn) else 0.0, 10.0)
    # A few names are called a thousand times more than most, so each step along the axis is ten times the one before.
    figure.update_xaxes(type="log", dtick=1, title_text="calls", fixedrange=True, gridcolor=COLORS[mode]["grid"], zeroline=False)
    # Room below zero and above the highest, so a bubble on either edge is drawn whole.
    figure.update_yaxes(title_text="failed %", fixedrange=True, gridcolor=COLORS[mode]["grid"], zeroline=False, range=[-0.08 * highest, 1.2 * highest])
    return tidy(figure, 440, legend=True).update_layout(hovermode="closest")


def reason_rows(reasons, top=3):
    """Why commands failed as (reason, failed runs, the programs it hit most), the most frequent reason first; a tie goes by name."""
    summed = reasons.groupby("reason")["times"].sum()
    rows = []
    for label in sorted(summed.index, key=lambda label: (-int(summed[label]), label)):
        programs = reasons[reasons["reason"] == label].groupby("program")["times"].sum()
        most = sorted(programs.items(), key=lambda made: (-int(made[1]), made[0]))[:top]
        rows.append((label, int(summed[label]), ", ".join(f"{program} {int(times):,}" for program, times in most)))
    return rows


def reasons_figure(rows, mode="light"):
    """One bar per reason with its failed runs and its share of them. A failure whose reason is not known stays quiet, so the eye goes to the others."""
    whole = sum(times for _, times, _ in rows)
    figure = go.Figure(go.Bar(orientation="h", y=[label for label, _, _ in rows], x=[times for _, times, _ in rows], customdata=[programs for _, _, programs in rows],
                              text=[f"{times:,} · {share(times, whole):.0f}%" for _, times, _ in rows], textposition="outside", cliponaxis=False,
                              marker={"color": [COLORS[mode]["usual" if label in UNSAID else "tool"] for label, _, _ in rows]},
                              hovertemplate="%{y}<br>%{text}<br>%{customdata}<extra></extra>"))
    figure.update_xaxes(range=[0, max(times for _, times, _ in rows) * 1.3])
    return bar_list(figure, 34 * len(rows) + 12)


def printed_rows(calls, top=TOP_PROGRAMS):
    """What commands printed as (program, characters, runs): the few programs that printed the most by name, then every other program added up."""
    every = calls[calls["kind"] == "command"].groupby("name", as_index=False)[["characters", "runs"]].sum().sort_values(["characters", "name"], ascending=[False, True])
    rows = [(row.name, float(row.characters), int(row.runs)) for row in every.head(top).itertuples(index=False)]
    if len(every) > top:
        rows.append(("every other program", float(every["characters"].iloc[top:].sum()), int(every["runs"].iloc[top:].sum())))
    return rows


def printed_figure(rows, mode="light"):
    """One bar per program for the characters its runs printed, with its share of all of them and what one run printed."""
    whole = sum(amount for _, amount, _ in rows)
    figure = go.Figure(go.Bar(orientation="h", y=[name for name, _, _ in rows], x=[amount for _, amount, _ in rows],
                              text=[f"{short(amount)} · {share(amount, whole):.0f}% · {short(amount / runs)} a run" for _, amount, runs in rows], textposition="outside", cliponaxis=False,
                              marker={"color": COLORS[mode]["tool"]}, hovertemplate="%{y}<br>%{text}<extra></extra>"))
    figure.update_xaxes(range=[0, max(amount for _, amount, _ in rows) * 1.9])
    return bar_list(figure, 34 * len(rows) + 12)


def gap_rows(hops):
    """For every kind of gap, always all three and in their order: (kind, the calls that came after one, the seconds of those gaps)."""
    summed = hops.groupby("gap")[["times", "seconds"]].sum().reindex(list(activity.GAPS), fill_value=0)
    return [(kind, int(row.times), float(row.seconds)) for kind, row in summed.iterrows()]


def gaps_figure(hops, whole, mode="light"):
    """One bar per kind of gap for the model's time before calls, with how many calls and the share of all turn time."""
    rows = gap_rows(hops)
    figure = go.Figure(go.Bar(orientation="h", y=[kind for kind, _, _ in rows], x=[seconds / 60 for _, _, seconds in rows], cliponaxis=False, textposition="outside",
                              text=[f"{times:,} calls · {seconds / 60:,.1f} min · {share(seconds, whole):.1f}% of turn time" for _, times, seconds in rows],
                              marker={"color": [COLORS[mode][GAP_COLORS[kind]] for kind, _, _ in rows]}, hovertemplate="%{y}<br>%{text}<extra></extra>"))
    figure.update_xaxes(range=[0, max(max(seconds for _, _, seconds in rows) / 60, 1.0) * 1.9])
    return bar_list(figure, 34 * len(rows) + 12)


def hop_pairs_table(hops, kind=activity.GAPS[1], top=TOP_PAIRS):
    """The pairs of groups with the most time in this kind of gap: what the model last saw, the call it then made, how often and the minutes."""
    summed = hops[hops["gap"] == kind].groupby(["from", "to"], as_index=False)[["times", "seconds"]].sum()
    most = summed.sort_values(["seconds", "from", "to"], ascending=[False, True, True]).head(top)
    return [{"after": before, "the call": called, "times": int(times), "minutes": round(seconds / 60, 1)} for before, called, times, seconds in most.itertuples(index=False, name=None)]


def repeat_rows(repeats, top=TOP_PROGRAMS):
    """Runs of a line already run, by program, the most seconds first: (program, seconds with nothing written in between, the other seconds, runs, runs with nothing written)."""
    summed = repeats.groupby("program", as_index=False)[["runs", "seconds", "unchangedRuns", "unchangedSeconds"]].sum()
    most = summed.sort_values(["seconds", "program"], ascending=[False, True]).head(top)
    return [(row.program, row.unchangedSeconds, row.seconds - row.unchangedSeconds, int(row.runs), int(row.unchangedRuns)) for row in most.itertuples(index=False)]


def repeats_figure(rows, mode="light"):
    """Minutes of repeated runs by program: those a cache could have answered, with nothing written in between, and the rest.
    A program is named with its repeated runs, and a part of a bar long enough to hold them has its minutes written in it."""
    figure = go.Figure()
    names = [f"{row[0]}  ({activity.count(row[3], 'run')})" for row in rows]
    for label, color, at, runs in (("nothing written in between", "tool", 1, lambda row: row[4]), ("something written in between", "usual", 2, lambda row: row[3] - row[4])):
        figure.add_bar(orientation="h", y=names, x=[row[at] / 60 for row in rows], name=label, customdata=[runs(row) for row in rows],
                       text=[f"{row[at] / 60:,.1f} min" if row[at] else "" for row in rows], textposition="inside", insidetextanchor="middle",
                       marker={"color": COLORS[mode][color], "line": {"color": COLORS[mode]["surface"], "width": 1}},
                       hovertemplate="%{y}<br>" + label + ": %{customdata:,} runs · %{x:,.1f} minutes<extra></extra>")
    return bar_list(figure, 34 * len(rows) + 40, legend=True)


def with_provider(calls, turns):
    """Each call with the provider of its turn. A turn the database does not know has no provider."""
    return calls.merge(turns[["provider"]].fillna("not recorded"), left_on="turn", right_index=True)


def providers_table(calls, turns):
    """One row for every provider: its commands and its tool calls, and how many of each failed. The most calls first."""
    rows = []
    for provider, group in with_provider(calls, turns).groupby("provider"):
        row = {"provider": provider, "turns": int(group["turn"].nunique())}
        for kind, (label, _) in CALLS.items():
            own = group[group["kind"] == kind]
            runs, failed = int(own["runs"].sum()), int(own["failed"].sum())
            # A provider that made no call of a kind has no failure rate for it, which is not a rate of zero.
            row |= {f"{label}s": runs, f"{label}s failed": failed, f"{label}s failed %": round(share(failed, runs), 1) if runs else None}
        rows.append(row)
    return sorted(rows, key=lambda row: (-sum(row[f"{label}s"] for label, _ in CALLS.values()), row["provider"]))


def providers_figure(calls, turns, mode="light", top=TOP_PROGRAMS_COMPARED):
    """How often the most used command programs failed under each provider, as a grid. A cell with too few runs says how few and has no color."""
    commands = with_provider(calls[calls["kind"] == "command"], turns)
    programs = list(call_sums(commands)["name"].head(top))
    busiest = commands.groupby("provider")["runs"].sum()
    providers = sorted(busiest.index, key=lambda provider: (-int(busiest[provider]), provider))
    summed = commands.groupby(["name", "provider"])[["runs", "failed"]].sum()
    cell = lambda program, provider: (int(summed["runs"].get((program, provider), 0)), int(summed["failed"].get((program, provider), 0)))
    rates = [[share(cell(program, provider)[1], cell(program, provider)[0]) if cell(program, provider)[0] >= MIN_RUNS else None for provider in providers] for program in programs]
    figure = go.Figure(go.Heatmap(
        x=providers, y=programs, z=rates, zmin=0, zmax=max([rate for row in rates for rate in row if rate is not None] + [1.0]),
        text=[["" if not cell(program, provider)[0] else f"{share(cell(program, provider)[1], cell(program, provider)[0]):.0f}%<br>{cell(program, provider)[1]:,} of {cell(program, provider)[0]:,}"
               if cell(program, provider)[0] >= MIN_RUNS else activity.count(cell(program, provider)[0], "run") for provider in providers] for program in programs],
        texttemplate="%{text}", textfont={"color": COLORS[mode]["ink"]}, colorscale=[[0, COLORS[mode]["scale"][0]], [1, COLORS[mode]["scale"][1]]], showscale=False, xgap=3, ygap=3,
        hovertemplate="%{y} · %{x}<br>%{text}<extra></extra>", hoverongaps=False))
    figure.update_xaxes(side="top", fixedrange=True, showgrid=False)
    figure.update_yaxes(fixedrange=True, showgrid=False, autorange="reversed", ticksuffix="   ")
    return tidy(figure, 50 * len(programs) + 40)


def review_frames(table, zone=None):
    """The saved review runs and findings as two frames. A finding carries the number of its run; a run its day in the reader's own time zone."""
    rows = table["runs"]
    start = pandas.to_datetime([row["start"] for row in rows], utc=True).tz_convert(zone or datetime.now().astimezone().tzinfo)
    runs = pandas.DataFrame({"review": [row["review"] for row in rows], "repository": [row["repository"] for row in rows], "start": start, "day": start.date,
                             "seconds": [row["seconds"] for row in rows], "state": [str(row["state"]) for row in rows], "repaired": [bool(row["repaired"]) for row in rows],
                             "turns": [int(row["turns"]) for row in rows], **{name: [row[name] for row in rows] for name in ("provider", "model", "effort", "inputTokens",
                                                                                                                              "cachedInputTokens", "outputTokens", "cost")}})
    runs["setting"] = [None if row["model"] is None else f"{row['model']} · {row['effort'] or 'no effort'}" for row in rows]
    found = table["findings"]
    findings = pandas.DataFrame({"run": [int(row["run"]) for row in found], "state": [str(row["state"]) for row in found], "severity": [row["severity"] for row in found]})
    if not findings["run"].isin(runs.index).all():
        raise ValueError("a finding names a run that is not saved")
    return {"runs": runs, "findings": findings}


def reviews_chosen(made, first, last, settings=(), repositories=()):
    """Only the runs that started in these days, under these models and efforts and in these repositories, with their findings."""
    runs = made["runs"]
    keep = (runs["day"] >= first) & (runs["day"] <= last)
    if settings:
        keep &= runs["setting"].isin(settings)
    if repositories:
        keep &= runs["repository"].isin(repositories)
    return {"runs": runs[keep], "findings": made["findings"][made["findings"]["run"].isin(runs[keep].index)]}


def in_order(found, known):
    """The names found, the known ones in their fixed order and then any other by name."""
    return [name for name in known if name in found] + sorted(set(found) - set(known))


def counted_donut(counted, known, middle, mode):
    """A ring of counts by name, with each count in the key."""
    names = in_order(counted.index, known)
    figure = go.Figure(go.Pie(labels=[f"{name}  {int(counted[name]):,}" for name in names], values=[int(counted[name]) for name in names], hole=0.6, sort=False,
                              direction="clockwise", textposition="inside", insidetextorientation="horizontal", texttemplate="%{value}", customdata=names,
                              marker={"colors": [COLORS[mode][known.get(name, "other")] for name in names], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                              hovertemplate="%{customdata}<br>%{value} · %{percent:.1%}<extra></extra>"))
    figure.add_annotation(text=middle, showarrow=False, font={"size": 20})
    return tidy(figure, 300, legend=True)


def findings_donut(findings, mode="light"):
    """What became of the findings."""
    return counted_donut(findings["state"].value_counts(), FINDINGS, activity.count(len(findings), "finding"), mode)


def run_endings_donut(runs, mode="light"):
    """How the review runs ended."""
    return counted_donut(runs["state"].value_counts(), RUN_ENDINGS, activity.count(len(runs), "run"), mode)


def held_up(findings):
    """Of the findings that are decided, the share in percent that was resolved and not withdrawn; None while none is decided."""
    resolved, withdrawn = int((findings["state"] == "resolved").sum()), int((findings["state"] == "withdrawn").sum())
    return share(resolved, resolved + withdrawn) if resolved + withdrawn else None


def findings_per_run_figure(runs, findings, mode="light"):
    """How many findings a run raised: the number of runs for each number of findings, with five or more together."""
    raised = findings.groupby("run").size().reindex(runs.index, fill_value=0)
    names = ["0", "1", "2", "3", "4", "5 or more"]
    counted = [int((raised == number).sum()) for number in range(5)] + [int((raised >= 5).sum())]
    figure = go.Figure(go.Bar(x=names, y=counted, text=[f"{amount:,}" if amount else "" for amount in counted], textposition="outside", cliponaxis=False,
                              marker={"color": COLORS[mode]["model"]}, hovertemplate="%{x} findings<br>%{y} runs<extra></extra>"))
    figure.update_xaxes(fixedrange=True, showgrid=False, type="category", title_text="findings in a run")
    figure.update_yaxes(fixedrange=True, visible=False, range=[0, max(counted) * 1.25 if max(counted) else 1])
    return tidy(figure, 300)


def severity_figure(findings, mode="light"):
    """What became of the findings of each severity, as counts."""
    graded = findings.assign(severity=findings["severity"].fillna("not given"))
    counted = graded.groupby(["severity", "state"]).size().unstack(fill_value=0)
    rows = in_order(counted.index, dict.fromkeys((*SEVERITIES, "not given")))
    figure = go.Figure()
    for state in in_order(counted.columns, FINDINGS):
        amounts = [int(counted.loc[row, state]) for row in rows]
        figure.add_bar(orientation="h", y=rows, x=amounts, name=state, text=[f"{amount:,}" if amount else "" for amount in amounts], textposition="inside", insidetextanchor="middle",
                       marker={"color": COLORS[mode][FINDINGS.get(state, "other")], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                       hovertemplate="%{y}<br>" + state + ": %{x}<extra></extra>")
    return bar_list(figure, 40 * len(rows) + 40, legend=True)


def review_settings_table(runs, findings):
    """One row for every model and effort that reviewed: its runs, how many of them ByteQuay had to ask again for a complete report, its findings and what
    became of them. A run whose turns the database lost is "not recorded"."""
    rows = []
    for name, group in runs.assign(setting=runs["setting"].fillna("not recorded")).groupby("setting"):
        own = findings[findings["run"].isin(group.index)]
        kept = held_up(own)
        rows.append({"model and effort": name, "runs": len(group), "asked again": int(group["repaired"].sum()),
                     "typical minutes": None if group["seconds"].isna().all() else round(float(group["seconds"].median()) / 60, 1),
                     "findings": len(own), "findings a run": round(len(own) / len(group), 1), **{state: int((own["state"] == state).sum()) for state in FINDINGS},
                     "held up %": None if kept is None else round(kept), "typical tokens in": whole_or_none(group["inputTokens"].median())})
    return sorted(rows, key=lambda row: (-row["runs"], row["model and effort"]))


def score_totals(scores):
    """For each setting of the scorer: its name, its rules files, and every number added up over its scored runs."""
    return [(setting["name"], setting["rules"], {amount: sum(run[amount] for run in setting["runs"]) for amount in SCORE_AMOUNTS}) for setting in scores["settings"]]


def verdicts(scores):
    """A part of zeros says nothing, so judge errors appear only when there are some."""
    return [verdict for verdict in VERDICTS if verdict != "errors" or any(total["errors"] for _, _, total in score_totals(scores))]


def said(verdict):
    return "judge errors" if verdict == "errors" else verdict


def scores_figure(scores, mode="light"):
    """One bar per setting, cut by what became of the human reviewer's comments. Every bar has the same length, so they compare down the page."""
    scored = [(name, total) for name, _, total in score_totals(scores) if total["comments"]]
    figure = go.Figure()
    for verdict in verdicts(scores):
        amounts = [total[verdict] for _, total in scored]
        figure.add_bar(orientation="h", y=[f"{name}  ({activity.count(total['comments'], 'comment')})" for name, total in scored],
                       x=[share(total[verdict], total["comments"]) for _, total in scored], name=said(verdict), customdata=amounts,
                       text=[f"{amount} {said(verdict)}" if amount else "" for amount in amounts], textposition="inside", insidetextanchor="middle",
                       marker={"color": COLORS[mode][VERDICTS[verdict]], "line": {"color": COLORS[mode]["surface"], "width": 2}},
                       hovertemplate="%{y}<br>%{customdata} " + said(verdict) + "<extra></extra>")
    return bar_list(figure, 44 * len(scored) + 40, legend=True)


def score_cells(scores, total):
    return {"comments": total["comments"], **{said(verdict): total[verdict] for verdict in verdicts(scores)}, "leads": total["leads"], "extra leads": total["extra"],
            "tokens in": total["inputTokens"], "cached": total["cachedInputTokens"], "tokens out": total["outputTokens"], "minutes": round(total["seconds"] / 60, 1)}


def score_settings_table(scores):
    """One row per setting: what all of its scored runs found and what they cost."""
    return [{"setting": name, "rules": ", ".join(rules), "scored runs": f"{len(setting['runs'])} of {len(setting['runs']) + len(setting['notScored'])}",
             **score_cells(scores, total), "judge tokens in": total["judgeInputTokens"], "judge tokens out": total["judgeOutputTokens"]}
            for (name, rules, total), setting in zip(score_totals(scores), scores["settings"])]


def score_runs_table(scores):
    """One row per scored run."""
    return [{"setting": setting["name"], "run": run["run"], "case": run["case"], **score_cells(scores, run)} for setting in scores["settings"] for run in setting["runs"]]


def score_notes(scores):
    """What the score tables cannot show: settings that are not one comparison, and runs that were left out."""
    lines = []
    for setting in scores["settings"]:
        if len(setting["rules"]) > 1:
            lines.append(f"{setting['name']}: mixed rules files or models in this setting, so its runs are not one comparison: {', '.join(setting['rules'])}")
        if setting["notScored"]:
            lines.append(f"{setting['name']}: not scored, and not counted as zero: " + ", ".join(f"{left['run']} ({left['status']})" for left in setting["notScored"]))
    return lines
