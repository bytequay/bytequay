"""The lab's Context page: how the context of an agent turn grows from one model call to the next and what fills it, from
the table `uv run context.py --save` keeps."""

import argparse
from pathlib import Path

import pandas
import plotly.graph_objects as go
import streamlit as st

import charts
import context
import dashboard
import saved

SPANS = {"Last 7 days": 7, "Last 30 days": 30, "All": None}
SAVE = "uv run context.py --save"
CALLS = 30  # calls of a turn the growth chart follows
SHOWN = 12  # names in the chart of what fills the context


def growth_figure(calls, mode):
    """The typical context sent at each call of a turn, for each model with the most calls."""
    figure = go.Figure()
    known = calls.dropna(subset=["model"])
    for color, model in zip(("model", "tool", "you"), known["model"].value_counts().index[:3]):
        typical = known[(known["model"] == model) & (known["call"] <= CALLS)].groupby("call")["sent"].median() / 1000
        figure.add_scatter(x=typical.index, y=typical.values, name=model, mode="lines+markers", marker={"size": 6},
                           line={"width": 2, "color": charts.COLORS[mode][color]}, hovertemplate=f"call %{{x}}: %{{y:,.0f}}k tokens<extra>{model}</extra>")
    figure.update_xaxes(showgrid=False, title="call of the turn", dtick=5)
    figure.update_yaxes(gridcolor=charts.COLORS[mode]["grid"], ticksuffix="k", rangemode="tozero")
    return charts.tidy(figure, 260, legend=True).update_layout(hovermode="x unified")


def fills_figure(rows, mode):
    """The names that cause the most input tokens, the most first."""
    shown = rows[:SHOWN]
    figure = go.Figure(go.Bar(y=[row["name"] for row in shown], x=[row["caused"] / 1e6 for row in shown], orientation="h",
                              marker={"color": charts.COLORS[mode]["tool"]}, texttemplate="%{x:,.1f} M", textposition="outside",
                              customdata=[[row["share"], row["calls"]] for row in shown],
                              hovertemplate="%{y}: %{x:,.1f} M input tokens; %{customdata[0]:.1f}% of what was added, over %{customdata[1]:,} calls<extra></extra>"))
    # Room beyond the longest bar for its number.
    return charts.bar_list(figure, 28 * len(shown) + 20).update_xaxes(range=[0, 1.2 * shown[0]["caused"] / 1e6])


dashboard.start_page()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
FOLDER = arguments.parse_known_args()[0].folder
MODE = "dark" if st.context.theme.type == "dark" else "light"
st.title("Context")
st.caption("How the context of an agent turn grows from one model call to the next, and what fills it. Every call is sent the whole context again, so "
           "what a command or tool returns is paid for at every later call of the turn. Only Codex logs its tokens call by call; Claude's turns are not here.")
try:
    MADE = saved.read(FOLDER, context.FILE)
except (OSError, ValueError):
    st.warning(f"The saved context table could not be read. Save it again with `{SAVE}`")
    st.stop()
if MADE is None:
    st.info(f"Nothing is saved yet. Save it with `{SAVE}`; the monitor saves it every hour while it runs.")
    st.stop()
REACH = SPANS[st.segmented_control("Show", list(SPANS), default="Last 30 days", key="span") or "All"]
CALLS_MADE = pandas.DataFrame(MADE["calls"], columns=["task", "turn", "start", "call", "sent", "wrote", "added", "shrank", "between", "seconds", "model", "effort"])
if REACH is not None:
    CALLS_MADE = CALLS_MADE[pandas.to_datetime(CALLS_MADE["start"], utc=True, format="ISO8601") >= pandas.Timestamp(MADE["savedAt"]) - pandas.Timedelta(days=REACH)]
st.caption(f"Saved {dashboard.when(MADE['savedAt'])}." + (f" Model and effort are left out: {MADE['database']['problem']}." if MADE["database"]["problem"] else ""))
if CALLS_MADE.empty:
    st.info("No model call was logged call by call in these days.")
    st.stop()
ADDED = CALLS_MADE["added"].dropna()
with st.container(horizontal=True):
    st.metric("Model calls", f"{len(CALLS_MADE):,}", border=True, width=dashboard.TILE, help=f"In {CALLS_MADE.groupby(['task', 'turn']).ngroups:,} turns.")
    st.metric("Typical context", f"{CALLS_MADE['sent'].median() / 1000:,.0f}k", border=True, width=dashboard.TILE, help="Tokens one call is sent, in the middle of all calls.")
    st.metric("Largest context", f"{CALLS_MADE['sent'].max() / 1000:,.0f}k", border=True, width=dashboard.TILE)
    st.metric("Added per call", "not known" if ADDED.empty else f"{ADDED.median():,.0f}", border=True, width=dashboard.TILE,
              help="Tokens that came into the context between two calls of a turn, not counting what the model wrote, in the middle of all calls.")
    st.metric("Compacted", f"{int(CALLS_MADE['shrank'].sum()):,}", border=True, width=dashboard.TILE, help="Calls sent less than the call before them: the context was compacted.")
st.subheader("Context by call of a turn")
st.caption(f"The typical tokens sent at each of the first {CALLS} calls of a turn, for the models with the most calls.")
dashboard.draw("context_growth", growth_figure(CALLS_MADE, MODE))
st.subheader("What fills the context")
ROWS = context.adders([MADE["calls"][index] for index in CALLS_MADE.index])  # the saved rows: a frame would turn a missing number into NaN
if not ROWS:
    st.info("No call in these days says what came into its context.")
    st.stop()
st.caption("The input tokens each command program or tool caused: what it returned, sent again at every later call of its turn. What came in between two calls is "
           "shared evenly among what ran there; alone is what it added when it was the only thing that ran. Trimming or capping the output of the biggest saves the most.")
dashboard.draw("context_fills", fills_figure(ROWS, MODE))
st.dataframe([{"what": row["name"], "calls": row["calls"], "added": row["tokens"], "share of added": row["share"], "input tokens caused": row["caused"],
               "alone, typically": row["alone"]} for row in ROWS], hide_index=True,
             column_config={"added": st.column_config.NumberColumn(format="localized"), "share of added": st.column_config.NumberColumn(format="%.1f%%"),
                            "input tokens caused": st.column_config.NumberColumn(format="localized"), "alone, typically": st.column_config.NumberColumn(format="%,.0f")})
st.subheader("What a call's time goes with")
TIMED = context.timing([MADE["calls"][index] for index in CALLS_MADE.index])
if not TIMED:
    st.info(f"No model has {context.FITTED} timed calls in these days.")
else:
    st.caption("A call's time runs from when all it was given was back, or the turn began, to its first command or tool, or to its end. A straight fit splits it "
               "into a fixed part, a part for what the call is sent and a part for what it writes, its reasoning counted. Explained is the share of the spread the fit "
               "accounts for; the shares are of all the time of that model's calls.")
    st.dataframe(TIMED, hide_index=True, column_config={
        "typical": st.column_config.NumberColumn("typical seconds", format="%.1f"), "fixed": st.column_config.NumberColumn("fixed seconds", format="%.1f"),
        "per 100k sent": st.column_config.NumberColumn("seconds per 100k sent", format="%.2f"),
        "per 1k written": st.column_config.NumberColumn("seconds per 1k written", format="%.1f"), "explained": st.column_config.NumberColumn(format="percent"),
        **{name: st.column_config.NumberColumn(format="%.0f%%") for name in ("fixed share", "context share", "writing share")}})
