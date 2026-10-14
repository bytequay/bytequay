"""The lab's Tuning page: how much a person had to step in to get the agent's work done, and how its turns ended, from
the table `uv run tuning.py --save` keeps."""

import argparse
from pathlib import Path
import time

import streamlit as st

import dashboard
import saved
import tuning

SPANS = {"Last 7 days": 7 * 86400, "Last 30 days": 30 * 86400, "All": None}
SAVE = "uv run tuning.py --save"
HICCUPS = {"rateLimits": "rate limits", "retries": "retries", "interruptions": "interruptions"}


def people(tasks, mode):
    st.subheader("A person in the loop")
    one = int((tasks["touches"] == 1).sum())
    with st.container(horizontal=True):
        st.metric("Tasks", f"{len(tasks):,}", border=True, width=dashboard.TILE)
        st.metric("One-ask tasks", f"{100 * one / len(tasks):.0f}%", border=True, width=dashboard.TILE,
                  help=f"{one:,} of {len(tasks):,} tasks needed one message and nothing else from a person.")
        st.metric("Messages per task", f"{tasks['messages'].median():,.0f}", border=True, width=dashboard.TILE, help="The middle task: half needed fewer, half more.")
        st.metric("Approvals", f"{int(tasks['answered'].sum()):,}", border=True, width=dashboard.TILE,
                  help=f"Answered by a person. {int(tasks['unanswered'].sum()):,} more ran out or were cancelled before anyone answered.")
        st.metric("Turns stopped", f"{int(tasks['cancels'].sum()):,}", border=True, width=dashboard.TILE)
    left, right = st.columns(2)
    with left:
        st.caption("How a person stepped in after asking")
        dashboard.draw("steps", tuning.count_bars(tuning.steps_rows(tasks), mode, "times"))
    with right:
        st.caption("Messages each task needed")
        dashboard.draw("sizes", tuning.count_bars(tuning.sizes_rows(tasks), mode, "tasks"))
    st.caption("Another message can be a correction or new work, and the log keeps no reason. The tasks that needed the most:")
    st.dataframe(tuning.busiest(tasks), hide_index=True)


def endings(turns, mode):
    st.subheader("How turns ended")
    known = int(turns["ended"].notna().sum())
    with st.container(horizontal=True):
        st.metric("Turns", f"{len(turns):,}", border=True, width=dashboard.TILE)
        st.metric("Completed", f"{100 * (turns['ended'] == 'COMPLETED').sum() / known:.0f}%" if known else "not known", border=True, width=dashboard.TILE,
                  help=f"Of the {known:,} turns whose ending ByteQuay's database still holds.")
    st.caption("For every 100 turns: " + ", ".join(f"{100 * turns[name].sum() / len(turns):,.0f} {label}" for name, label in HICCUPS.items())
               + ". Rate limits and retries over time are on the Slow spots tab.")
    st.caption("Turns by week and how they ended")
    dashboard.draw("endings", tuning.endings_figure(turns, mode))


st.set_page_config(page_title="ByteQuay Lab", layout="wide")
# Started any other way, Streamlit would serve the page to the whole network.
if st.get_option("server.address") != dashboard.HOST:
    st.error("This page is only shown to this machine. Start it with `uv run dashboard.py`")
    st.stop()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
FOLDER = arguments.parse_known_args()[0].folder
MODE = "dark" if st.context.theme.type == "dark" else "light"
st.title("Tuning")
st.caption("How much a person had to step in to get the agent's work done, and how its turns ended. The counts come from the task logs; "
           "a task's state and a turn's ending from ByteQuay's database.")
UNREADABLE = f"The saved tuning table could not be read. Save it again with `{SAVE}`"
try:
    MADE = saved.read(FOLDER, "tuning.json")
except (OSError, ValueError):
    st.warning(UNREADABLE)
    st.stop()
if MADE is None:
    st.info(f"Nothing saved yet. Save the tuning table with `{SAVE}`")
    st.stop()
REACH = SPANS[st.segmented_control("Show", list(SPANS), default="All", key="span") or "All"]
try:
    TURNS, TASKS = tuning.frames(MADE, 0.0 if REACH is None else time.time() - REACH)
    SAVED = dashboard.when(MADE["savedAt"])
except (KeyError, TypeError, ValueError):
    st.warning(UNREADABLE)
    st.stop()
PROBLEM = (MADE.get("database") or {}).get("problem")
st.caption(f"Saved {SAVED}." + (f" Without the database: {PROBLEM}." if PROBLEM else ""))
if TURNS.empty:
    st.info("No turn started in this stretch.")
    st.stop()
people(TASKS, MODE)
endings(TURNS, MODE)
