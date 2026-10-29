"""The lab's Processes page: every program the lab's Python runs now with a way to stop it, and the monitor turned on or off."""

import argparse
from datetime import datetime
import os
from pathlib import Path

import streamlit as st

import batch
import dashboard
import monitor
import processes
import saved

COLUMNS = (3, 1, 2, 1, 1)


@st.fragment(run_every=3)
def programs(folder):
    """Looked at again every few seconds, so a program that ends leaves the list."""
    rows = processes.running()
    if rows:
        for column, name in zip(st.columns(COLUMNS), ("Program", "Process", "Since", "Memory", "")):
            column.caption(name)
    else:
        st.info("No lab program runs now.")
    for row in rows:
        what, number, since, memory, action = st.columns(COLUMNS, vertical_alignment="center")
        what.write(row["what"] + (", this page" if row["pid"] == os.getpid() else ""))
        number.write(str(row["pid"]))
        since.write(f"{datetime.fromtimestamp(row['started']):%Y-%m-%d %H:%M}")
        memory.write("" if row["memory"] is None else f"{row['memory'] / 2**20:,.0f} MB")
        if action.button("Stop", key=f"stop-{row['pid']}"):
            processes.stop(row["pid"])
            st.rerun()
    st.caption("Stopping the page you are on closes it. " + ("Choose Open Lab in ByteQuay to start it again." if os.environ.get("BYTEQUAY_LAB_PARENT_PID") else
                                                           "Start it again in the lab's folder with `uv run dashboard.py`"))
    st.subheader("Resource monitoring")
    try:
        if monitor.session_pid() is not None:
            st.write("The monitor is on for this Lab session. It stops when you close Lab or quit ByteQuay.")
            if st.button("Stop the monitor"):
                monitor.stop_session()
                st.rerun()
        else:
            st.write("The monitor is off, so the Resources page is not live.")
            if st.button("Start the monitor"):
                monitor.start_session(folder)
                st.rerun()
    except ValueError as problem:
        st.error(str(problem))
    st.caption("Monitoring stays off when you reopen Lab. Start it again here whenever you need live measurements.")
    st.caption(f"Nightly rounds are {'on' if processes.loaded(batch.AGENT) else 'off'}. Each experiment sets its own in the dashboard's Reviews tab.")


dashboard.start_page()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
st.title("Processes")
st.caption("Every program the lab runs now: its pages, the monitor, and the experiments with their review runs.")
programs(arguments.parse_known_args()[0].folder)
