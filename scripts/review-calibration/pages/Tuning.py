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


def paths(turns, mode):
    st.subheader("Tool paths")
    if "calls" not in turns:
        st.info(f"This table was saved before tool paths were counted. Save it again with {dashboard.SAVE}")
        return
    used, looked = turns[turns["calls"] > 0], turns[turns["lookups"] > 0]
    streak = max((made for made in turns["streak"] if made), key=lambda made: made[1], default=None)
    with st.container(horizontal=True):
        st.metric("Calls", f"{int(turns['calls'].sum()):,}", border=True, width=dashboard.TILE)
        st.metric("Calls per turn", f"{used['calls'].median():,.0f}" if len(used) else "none", border=True, width=dashboard.TILE, help="The middle turn of those that made calls.")
        st.metric("Tool lookups", f"{int(turns['lookups'].sum()):,}", border=True, width=dashboard.TILE,
                  help=f"Calls that only looked up one of ByteQuay's own tools: in {100 * len(looked) / len(turns):.0f}% of turns, {turns['lookupSeconds'].sum() / 60:,.1f} minutes in all.")
        st.metric("Same call in a row", f"{streak[1]:,}×" if streak else "none", border=True, width=dashboard.TILE,
                  help=f"{streak[0]}: the most times one call followed itself within one turn." if streak else None)
    st.caption("Which call follows which, among the most called tools and programs. The diagonal is a call that followed itself.")
    dashboard.draw("follows", tuning.follows_figure(turns, mode))
    st.caption("Every tool and program. Per turn is the middle of the turns that used it; after itself is the share of its calls that came straight after a call to itself.")
    st.dataframe(tuning.tools_table(turns), hide_index=True, column_config={"after itself": st.column_config.NumberColumn(format="percent")})


def setups(turns):
    st.subheader("Setups")
    table = tuning.setups_table(turns)
    if table is None:
        st.info("No turn names its setup yet. ByteQuay names it for every turn it starts from a version that records it; "
                f"save the table again with {dashboard.SAVE} once such turns have run.")
        return
    st.caption("A setup is named after ByteQuay's own instructions for a provider session and the tools it was given, so the turns of one setup "
               "can be set against the turns of the next. Changed says which half no earlier turn had.")
    st.dataframe(table, hide_index=True, column_config={"completed": st.column_config.NumberColumn(format="percent"), "minutes per turn": st.column_config.NumberColumn(format="%.1f"),
                                                        "steered or stopped per 100": st.column_config.NumberColumn(format="%.0f"),
                                                        "rate limits per 100": st.column_config.NumberColumn(format="%.0f")})


dashboard.start_page()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
FOLDER = arguments.parse_known_args()[0].folder
MODE = "dark" if st.context.theme.type == "dark" else "light"
st.title("Tuning")
dashboard.refresh(*dashboard.arguments())
st.caption("How much a person had to step in to get the agent's work done, how its turns ended, and the paths its calls took. The counts come "
           "from the task logs; a task's state and a turn's ending from ByteQuay's database.")
UNREADABLE = f"The saved tuning table could not be read. Save it again with {dashboard.SAVE}"
try:
    MADE = saved.read(FOLDER, "tuning.json")
except (OSError, ValueError):
    st.warning(UNREADABLE)
    st.stop()
if MADE is None:
    st.info(f"Nothing saved yet. Save the tuning table with {dashboard.SAVE}")
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
paths(TURNS, MODE)
setups(TURNS)
