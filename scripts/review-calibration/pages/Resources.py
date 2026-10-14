"""The lab's Resources page: what ByteQuay takes from this Mac, live and over time, from the samples monitor.py keeps."""

import argparse
from datetime import datetime
from pathlib import Path
import time

import streamlit as st

import dashboard
import resources
import saved

GUIDE = "Start the monitor with `uv run monitor.py --start`"
NUMBERS = {"memory MB": "%,.0f", "typical MB": "%,.0f", "peak MB": "%,.0f", "CPU cores": "%.2f", "typical CPU": "%.2f", "peak CPU": "%.2f", "threads": "%,.0f", "open files": "%,.0f"}


def tiles(made):
    with st.container(horizontal=True):
        st.metric("App memory", f"{made['memory'] / resources.GB:,.2f} GB", border=True, width=dashboard.TILE,
                  help="What the app's processes hold in memory. Its helpers share a little of it, so the sum runs slightly high.")
        st.metric("CPU cores", f"{made['cpu']:,.2f}", border=True, width=dashboard.TILE, help="1 means one core kept busy.")
        st.metric("Threads", f"{made['threads']:,}", border=True, width=dashboard.TILE)
        st.metric("Processes", f"{made['processes']:,}", border=True, width=dashboard.TILE)
        st.metric("Turns running", "not known" if made["turns"] is None else f"{made['turns']:,}", border=True, width=dashboard.TILE)
        st.metric("Open tasks", "not known" if made["tasks"] is None else f"{made['tasks']:,}", border=True, width=dashboard.TILE,
                  help=None if made["queued"] is None else f"{made['queued']:,} inputs wait in their queues.")
    st.caption(f"This Mac: {made['used'] / resources.GB:,.1f} of {made['total'] / resources.GB:,.0f} GB of memory in use.")


@st.fragment(run_every=2)
def live(folder, mode):
    """The newest samples, looked at again every two seconds."""
    end = time.time()
    span = (end - resources.LIVE, end)
    made = resources.read(folder, span[0])
    if made is None:
        st.info(f"Nothing is sampled yet. {GUIDE}")
        return
    parts, machine = made["parts"], made["machine"]
    if parts.empty or end - parts["at"].max() > resources.STALE:
        st.warning(f"The monitor is not running, so this is not live. {GUIDE}")
    if parts.empty:
        return
    tiles(resources.now(parts, machine))
    groups = resources.grouped(parts)
    left, right = st.columns(2)
    with left:
        st.caption("Memory over the last 15 minutes")
        dashboard.draw("live_memory", resources.lines_figure(groups, "memory", resources.GB, mode, " GB", span, stacked=True))
    with right:
        st.caption("CPU over the last 15 minutes")
        dashboard.draw("live_cpu", resources.lines_figure(groups, "cpu", 1, mode, " cores", span))


def over_time(folder, mode):
    st.subheader("Over time")
    chosen = st.segmented_control("Show", list(resources.SPANS), default="Last day", key="span") or "Last day"
    reach, bucket = resources.SPANS[chosen]
    end = time.time()
    span = (end - reach, end)
    made = resources.read(folder, span[0])
    if made is None or made["parts"].empty:
        st.info("Nothing was sampled in this stretch.")
        return made
    parts, machine = made["parts"], made["machine"]
    groups, turns = resources.bucketed(resources.grouped(parts), bucket, ["group"]), resources.bucketed(machine, bucket)
    st.caption(f"Memory, averaged over every {bucket // 60:,} minutes" if bucket < 3600 else f"Memory, averaged over every {bucket // 3600:,} hours")
    dashboard.draw("memory_over_time", resources.lines_figure(groups, "memory", resources.GB, mode, " GB", span, stacked=True, height=280))
    st.caption("Turns running on the same clock, so a jump above can be laid against the work")
    dashboard.draw("turns_over_time", resources.turns_figure(turns, mode, span))
    left, right = st.columns(2)
    with left:
        st.caption("CPU")
        dashboard.draw("cpu_over_time", resources.lines_figure(groups, "cpu", 1, mode, " cores", span))
    with right:
        st.caption("Threads")
        dashboard.draw("threads_over_time", resources.lines_figure(groups, "threads", 1, mode, "", span))
    st.subheader("Every part")
    st.caption("Now, typical and peak over this stretch. Typical is the middle of the samples a part ran in.")
    st.dataframe(resources.parts_table(parts), hide_index=True, column_config={name: st.column_config.NumberColumn(format=shape) for name, shape in NUMBERS.items()})
    st.subheader("Memory while no turn ran")
    idle = resources.idle_days(parts, machine)
    if idle is None:
        st.info("No sample of this stretch was taken while no turn ran.")
    else:
        st.caption("The typical memory of each group in MB on each day, from the samples when no turn ran. A column that rises day after day "
                   "holds on to memory with no work to do; a restart of the app starts it low again.")
        st.dataframe(idle, hide_index=True, column_config={group: st.column_config.NumberColumn(format="%,.0f") for group in resources.GROUPS})
    mine = parts[parts["part"] == "monitor"]
    if len(mine):
        st.caption(f"The monitor itself typically holds {mine['memory'].median() / resources.MB:,.0f} MB and keeps {100 * mine['cpu'].mean():.1f}% of one core busy.")
    return made


def disk(made, mode):
    st.subheader("Disk")
    rows = None if made is None else resources.disk_rows(made["disk"])
    if rows is None:
        st.info("The app's data is counted a few times a day. The first count starts with the monitor and takes a few minutes.")
        return
    st.caption(f"What each part of the app's data takes on disk, counted {datetime.fromtimestamp(made['disk']['at'].max()):%Y-%m-%d %H:%M}. "
               "Grew is the change since the first count kept.")
    dashboard.draw("disk", resources.disk_figure(rows, mode))
    st.dataframe(rows, hide_index=True, column_config={"GB": st.column_config.NumberColumn(format="%,.1f"), "grew GB": st.column_config.NumberColumn(format="%+,.1f")})


st.set_page_config(page_title="ByteQuay Lab", layout="wide")
# Started any other way, Streamlit would serve the page to the whole network.
if st.get_option("server.address") != dashboard.HOST:
    st.error("This page is only shown to this machine. Start it with `uv run dashboard.py`")
    st.stop()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
FOLDER = arguments.parse_known_args()[0].folder
MODE = "dark" if st.context.theme.type == "dark" else "light"
st.title("Resources")
st.caption("What ByteQuay takes from this Mac: its Electron app, its Java backend, and the agents it runs with the commands they start.")
live(FOLDER, MODE)
disk(over_time(FOLDER, MODE), MODE)
