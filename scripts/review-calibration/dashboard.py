#!/usr/bin/env python3
"""The lab's dashboard: the saved turn table as one page, shown to this machine only. Start it with `uv run dashboard.py`."""

from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path
import sys

import streamlit as st
from streamlit.runtime.scriptrunner import get_script_run_ctx

import activity
import charts
import saved

HOST, PORT = "127.0.0.1", 8517
SAVE = "uv run activity.py --save"
SHOW = {"width": "stretch", "config": {"displayModeBar": False}}
TILE = 170


def when(moment):
    """A saved time in the reader's own time zone."""
    return datetime.fromisoformat(moment).astimezone().strftime("%Y-%m-%d %H:%M")


def chosen(made, table):
    """The turns the reader asks for in the side bar."""
    days = made["turns"]["day"]
    with st.sidebar:
        picked = st.date_input("Days", (days.min(), days.max()), min_value=days.min(), max_value=days.max())
        # While the reader is still picking the second day there is only one, and it stands for both ends.
        first, last = (picked[0], picked[-1]) if picked else (days.min(), days.max())
        shown = charts.within(made, first, last)
        st.caption(f"{len(shown['turns']):,} of {len(made['turns']):,} turns. Days are in your own time zone.")
        st.caption(f"Saved {when(table['savedAt'])}")
        if table["since"]:
            st.caption(f"Only turns that started on or after {table['since']} were saved.")
        if table["task"]:
            st.caption(f"Only the task whose id starts with {table['task']} was saved.")
    return shown


def overview(shown, table, mode):
    turns, parts = shown["turns"], shown["parts"]
    with st.container(horizontal=True):
        st.metric("Turn time", f"{turns['seconds'].sum() / 3600:.1f} h", border=True, width=TILE)
        st.metric("Tasks", f"{turns['task'].nunique():,}", border=True, width=TILE)
        st.metric("Turns", f"{len(turns):,}", border=True, width=TILE)
        st.metric("Typical turn", f"{turns['seconds'].median() / 60:.1f} min", border=True, width=TILE, help="Half of the turns were shorter than this and half were longer.")
        st.metric("Longest turn", f"{turns['seconds'].max() / 60:,.0f} min", border=True, width=TILE)
    left, right = st.columns(2)
    with left:
        st.subheader("Where the time went")
        st.plotly_chart(charts.states_donut(parts, mode), **SHOW)
    with right:
        st.subheader("How the turns ended")
        st.plotly_chart(charts.endings_donut(turns, mode), **SHOW)
    st.subheader("Turn time per day")
    st.plotly_chart(charts.days_figure(turns, parts, mode), **SHOW)
    for note in activity.notes(turns["parallel"].sum(), int(turns["mostAtOnce"].max()), int(turns["unfinished"].sum()),
                               table["leftOut"]["skippedLines"], table["leftOut"]["unreadLogs"]):
        st.caption(note)


def time(shown, mode):
    turns, parts, programs, after = shown["turns"], shown["parts"], shown["programs"], shown["after"]
    colors, waiting = charts.COLORS[mode], charts.STATES[1]
    st.subheader("Every part of the time")
    st.plotly_chart(charts.sunburst(parts, mode), **SHOW)
    waited = charts.part_rows(parts, waiting)
    if waited:
        st.subheader(f"{waiting}: what was running")
        st.plotly_chart(charts.bars_figure(waited, turns["seconds"].sum(), colors["tool"]), **SHOW)
    ranked = charts.program_rows(programs)
    if ranked:
        st.subheader("Shell time by program, summed over runs")
        st.plotly_chart(charts.bars_figure(ranked, programs["seconds"].sum(), colors["tool"], "runs"), **SHOW)
        every = charts.program_rows(programs, top=len(programs))
        with st.expander(f"All {len(every):,} programs"):
            st.dataframe([{"program": name, "minutes": round(length / 60, 1), "runs": runs} for name, length, runs in every], hide_index=True)
    reacted = charts.model_rows(after)
    if reacted:
        st.subheader("Model time by what just finished")
        st.plotly_chart(charts.bars_figure(reacted, after["seconds"].sum(), colors["model"], "times"), **SHOW)
    st.subheader("The tasks that took the most time")
    st.plotly_chart(charts.tasks_figure(turns, parts, mode), **SHOW)


def page(folder):
    st.set_page_config(page_title="ByteQuay Lab", layout="wide")
    # Started any other way, Streamlit would serve the page to the whole network.
    if st.get_option("server.address") != HOST:
        st.error("This page is only shown to this machine. Start it with `uv run dashboard.py`")
        return
    st.title("ByteQuay Lab")
    try:
        table = saved.read(folder, "turns.json")
        made = None if table is None else charts.frames(table)
    except (OSError, KeyError, TypeError, ValueError):
        # A file in another shape is said to be unreadable. It is never drawn as zeros.
        st.warning(f"The saved turn table could not be read. Save it again with `{SAVE}`")
        return
    if made is None:
        st.info(f"Nothing saved yet. Save the turn table with `{SAVE}`")
        return
    if made["turns"].empty:
        st.info("The saved turn table holds no turn.")
        return
    shown = chosen(made, table)
    if shown["turns"].empty:
        st.info("No turn started in these days.")
        return
    mode = "dark" if st.context.theme.type == "dark" else "light"
    first, second = st.tabs(["Overview", "Time"])
    with first:
        overview(shown, table, mode)
    with second:
        time(shown, mode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER, help="Folder with the saved results")
    parser.add_argument("--port", type=int, default=PORT, help="Port on this machine to show the page on")
    args = parser.parse_args()
    if get_script_run_ctx(suppress_warning=True) is None:
        # Started as a plain script: hand over to Streamlit, with the page shown to this machine only.
        from streamlit.web import cli
        sys.argv = ["streamlit", "run", __file__, "--server.address", HOST, "--server.port", str(args.port), "--server.headless", "true",
                    "--browser.gatherUsageStats", "false", "--client.toolbarMode", "minimal", "--", str(args.folder)]
        sys.exit(cli.main())
    page(args.folder)


if __name__ == "__main__":
    main()
