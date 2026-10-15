"""The lab's Delivery page: what development tasks delivered for the agent time, tokens and dollars they took, from the
turn table `uv run activity.py --save` keeps."""

import argparse
from pathlib import Path
import time

import pandas
import streamlit as st

import dashboard
import delivery
import saved

SPANS = {"Last 7 days": 7 * 86400, "Last 30 days": 30 * 86400, "All": None}
SAVE = "uv run activity.py --save"
NUMBERS = {"ready %": "%.0f%%", "minutes per ready": "%,.0f", "M tokens per ready": "%,.1f", "dollars per ready": "$%,.2f"}


def tiles(tasks, whole):
    stuck = tasks[tasks["ready"] == 0]
    with st.container(horizontal=True):
        st.metric("Development tasks", f"{len(tasks):,}", border=True, width=dashboard.TILE, help="Tasks whose turns reported at least one development result.")
        st.metric("Ready", f"{whole['ready']:,} of {whole['results']:,}", border=True, width=dashboard.TILE,
                  help=f"{whole['share']:.0f}% of the results said the change was ready to deliver.")
        st.metric("Minutes per ready", "none ready" if pandas.isna(whole["minutes"]) else f"{whole['minutes']:,.0f}", border=True, width=dashboard.TILE,
                  help="Agent minutes of all these tasks, those never ready too, for each result that said ready.")
        st.metric("Never ready", f"{len(stuck):,}", border=True, width=dashboard.TILE,
                  help=f"Tasks with results and none of them ready: {stuck['minutes'].sum() / 60:,.1f} agent hours in all.")


st.set_page_config(page_title="ByteQuay Lab", layout="wide")
# Started any other way, Streamlit would serve the page to the whole network.
if st.get_option("server.address") != dashboard.HOST:
    st.error("This page is only shown to this machine. Start it with `uv run dashboard.py`")
    st.stop()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
FOLDER = arguments.parse_known_args()[0].folder
MODE = "dark" if st.context.theme.type == "dark" else "light"
st.title("Delivery")
st.caption("What development tasks delivered for the agent time, tokens and dollars they took. A result is the agent's own report when a development "
           "ends: the change is ready to deliver, or it is not. A task counts under the model and effort most of its turns ran with.")
UNREADABLE = f"The saved turn table could not be read. Save it again with `{SAVE}`"
try:
    MADE = saved.read(FOLDER, "turns.json")
except (OSError, ValueError):
    st.warning(UNREADABLE)
    st.stop()
if MADE is None:
    st.info(f"Nothing saved yet. Save the turn table with `{SAVE}`")
    st.stop()
if MADE["turns"] and "result" not in MADE["turns"][0]:
    st.info(f"This table was saved before development results were kept. Save it again with `{SAVE}`")
    st.stop()
REACH = SPANS[st.segmented_control("Show", list(SPANS), default="All", key="span") or "All"]
try:
    TASKS = delivery.tasks(MADE, 0.0 if REACH is None else time.time() - REACH)
except (KeyError, TypeError, ValueError):
    st.warning(UNREADABLE)
    st.stop()
st.caption(f"Saved {dashboard.when(MADE['savedAt'])}.")
if TASKS.empty:
    st.info("No development task reported a result in this stretch.")
    st.stop()
TABLE = delivery.by_setting(TASKS)
tiles(TASKS, TABLE.iloc[-1])
st.subheader("Agent minutes per ready change")
st.caption("For each model and effort: the agent minutes of its development tasks, those never ready too, for each result that said ready, with the "
           "range it could lie in. A range needs three tasks with a ready result; the tasks differ, so this shows what happened, not a fair race.")
dashboard.draw("minutes_per_ready", delivery.minutes_figure(TABLE, MODE))
st.dataframe(delivery.shown(TABLE), hide_index=True, column_config={name: st.column_config.NumberColumn(format=shape) for name, shape in NUMBERS.items()})
st.caption("Tokens count every input token, cached ones too, and the output. Dollars come only from turns that record a cost.")
st.subheader("Never ready")
STUCK = delivery.never_ready(TASKS)
if STUCK.empty:
    st.info("Every development task reported a ready result at least once.")
else:
    st.caption("Tasks that reported results but none ready, the most agent time first: where development time went without a change to deliver.")
    st.dataframe(STUCK, hide_index=True, column_config={"minutes": st.column_config.NumberColumn(format="%,.1f")})
