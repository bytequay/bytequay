#!/usr/bin/env python3
"""The lab's dashboard: the saved turn table as one page, shown to this machine only. Start it with `uv run dashboard.py`."""

from __future__ import annotations

import argparse
from datetime import datetime
import json
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
        names = [row["model and effort"] for row in charts.settings_table(made["turns"])]
        wanted = st.multiselect("Model and effort", names, placeholder="All") if names else []
        if wanted:
            shown = charts.only(shown, shown["turns"]["setting"].isin(wanted))
        turns = shown["turns"]
        st.caption(f"{len(turns):,} of {len(made['turns']):,} turns. Days are in your own time zone.")
        # Two sources stand behind a turn, and not every turn is in both.
        st.caption(f"With a log, so the time split is known: {int(turns['logged'].sum()):,}. In ByteQuay's records, so the model is known: "
                   f"{int(turns['setting'].notna().sum()):,}. In both: {int((turns['logged'] & turns['setting'].notna()).sum()):,}.")
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


def models(shown, table, mode):
    turns = shown["turns"]
    if "problem" in table["database"]:
        st.info(f"Model, effort and tokens were not saved: {table['database']['problem']}. Save again with `{SAVE}`")
        return
    rows = charts.settings_table(turns)
    if not rows:
        st.info("ByteQuay's records hold no turn for this choice.")
        return
    st.caption(f"Different tasks ran on each model and effort, so this shows what happened, not which one is better. "
               f"One with fewer than {charts.MIN_TURNS} turns is in the table only. Typical means the middle turn: half are below it, half above.")
    st.dataframe(rows, hide_index=True, column_config={name: st.column_config.NumberColumn(format="localized") for name in ("typical tokens in", "typical tokens out")})
    if not charts.settings(turns):
        return
    st.subheader("Typical turn, by model and effort")
    st.plotly_chart(charts.heat_figure(turns, mode), **SHOW)
    st.subheader("How long a turn took")
    st.caption("A box holds the middle half of the turns. The line in it is the typical turn, and each dot is a turn far from the rest.")
    st.plotly_chart(charts.lengths_figure(turns, mode), **SHOW)
    if charts.settings(turns, logged=True):
        st.subheader("How each split its time")
        st.caption("Only turns that also have a log.")
        st.plotly_chart(charts.split_figure(turns, shown["parts"], mode), **SHOW)
    st.subheader("How the turns ended")
    st.plotly_chart(charts.outcomes_figure(turns, mode), **SHOW)


def cost(shown, table, folder, mode):
    turns = shown["turns"]
    if "problem" in table["database"]:
        st.info(f"Tokens and cost were not saved: {table['database']['problem']}. Save again with `{SAVE}`")
        return
    kinds = charts.tokens(turns)
    if kinds.empty:
        st.info("No turn of this choice has a token count.")
        return
    try:
        prices = charts.price_table(saved.read(folder, "prices.json"))
    except (OSError, AttributeError, KeyError, TypeError, ValueError):
        # A price table in another shape estimates nothing. It is never read as far as it goes.
        st.warning("The price table `prices.json` could not be read, so no cost is estimated.")
        prices = {}
    costed = charts.dollars(turns, prices)
    summed, recorded, estimated = kinds.sum(), costed[costed["how"] == "recorded"]["dollars"].sum(), costed[costed["how"] == "estimated"]["dollars"].sum()
    with st.container(horizontal=True):
        st.metric("Tokens in", charts.short(summed["fresh input"] + summed["cached input"]), border=True, width=TILE)
        st.metric("From the cache", f"{charts.share(summed['cached input'], summed['fresh input'] + summed['cached input']):.1f}%", border=True, width=TILE)
        st.metric("Tokens out", charts.short(summed["output"]), border=True, width=TILE)
        st.metric("Recorded cost", f"${recorded:,.2f}", border=True, width=TILE, help="What the Claude command line says its turns would cost at API prices.")
        if prices:
            st.metric("Estimated cost", f"${estimated:,.2f}", border=True, width=TILE, help="Tokens times your own prices, for turns with no recorded cost.")
    st.caption(f"Tokens are known for {len(kinds):,} of {len(turns):,} turns. Each amount is the turn's own: ByteQuay stores running totals for Codex tokens and "
               "Claude cost, and the lab takes what they grew by.")
    left, right = st.columns(2)
    with left:
        st.subheader("Tokens by kind")
        st.plotly_chart(charts.tokens_donut(turns, mode), **SHOW)
    with right:
        st.subheader("Tokens by model and effort")
        st.plotly_chart(charts.tokens_settings_figure(turns, mode), **SHOW)
    st.subheader("Tokens per day")
    st.plotly_chart(charts.tokens_days_figure(turns, mode), **SHOW)
    st.subheader("The tasks that used the most tokens")
    st.plotly_chart(charts.tokens_tasks_figure(turns, mode), **SHOW)
    st.subheader("Cost in dollars")
    st.caption("Recorded cost is the Claude command line's own figure at API prices. On a subscription it is not what you paid.")
    st.dataframe(charts.cost_table(costed), hide_index=True)
    if (costed["how"] != "unknown").any():
        st.plotly_chart(charts.dollars_days_figure(costed, mode), **SHOW)
    missing = charts.unpriced(costed)
    if missing:
        st.caption(f"No cost is known for {', '.join(missing)}. To estimate it, save this as `prices.json` in `{folder}` with your own prices, in dollars for a million tokens:")
        wanted = {model: dict(zip(charts.PRICES, amounts)) for model, amounts in prices.items()} | {model: dict.fromkeys(charts.PRICES, 0) for model in missing}
        st.code(json.dumps({"version": 1, "dollarsPerMillionTokens": wanted}, indent=2), language="json")


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
        st.info("No turn matches this choice.")
        return
    mode = "dark" if st.context.theme.type == "dark" else "light"
    # The first two tabs are about time, which only a log can split. The other two are about what ByteQuay recorded.
    logged = charts.only(shown, shown["turns"]["logged"])
    first, second, third, fourth = st.tabs(["Overview", "Time", "Models", "Cost"])
    with first:
        overview(logged, table, mode) if len(logged["turns"]) else st.info("No turn of this choice has a log.")
    with second:
        time(logged, mode) if len(logged["turns"]) else st.info("No turn of this choice has a log.")
    with third:
        models(shown, table, mode)
    with fourth:
        cost(shown, table, folder, mode)


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
