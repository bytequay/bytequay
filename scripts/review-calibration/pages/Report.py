"""The lab's report on a page: what got worse, what changed against the days before and why, what changed in ByteQuay,
where the turn time went, and what moved after the changes you noted. `uv run digest.py` writes the same report to a file."""

import argparse
from pathlib import Path

import streamlit as st

import dashboard
import digest
import explain
import saved

SAVE = "uv run activity.py --save"
KINDS = {"Week": "week", "Day": "day"}

st.set_page_config(page_title="ByteQuay Lab", layout="wide")
# Started any other way, Streamlit would serve the page to the whole network.
if st.get_option("server.address") != dashboard.HOST:
    st.error("This page is only shown to this machine. Start it with `uv run dashboard.py`")
    st.stop()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
FOLDER = arguments.parse_known_args()[0].folder
st.title("Report")
st.caption("The report `uv run digest.py` writes, for the days picked here: what got worse, what changed against the days before and why, what changed "
           "in ByteQuay, where the turn time went, and what moved after the changes you noted.")
UNREADABLE = f"The saved turn table could not be read. Save it again with `{SAVE}`"
try:
    TABLE = saved.read(FOLDER, "turns.json")
except (OSError, ValueError):
    st.warning(UNREADABLE)
    st.stop()
if TABLE is None:
    st.info(f"Nothing saved yet. Save the turn table with `{SAVE}`")
    st.stop()
try:
    REVIEWS = saved.read(FOLDER, "reviews.json")
except (OSError, ValueError):
    REVIEWS = {}  # in another shape: the report names it and leaves the reviews out
try:
    NOTED = digest.changes(FOLDER)
except (OSError, KeyError, TypeError, ValueError):
    NOTED = []
    st.caption("The noted changes in `changes.json` could not be read, so they are left out.")
with st.container(horizontal=True):
    KIND = KINDS[st.segmented_control("Against the one before", list(KINDS), default="Week", key="kind") or "Week"]
    ENDING = st.date_input("Last day", value=None, key="ending", help="Left empty, the last day that had ended when the tables were saved.")
try:
    TEXT = digest.report(TABLE, REVIEWS, NOTED, KIND, ENDING, commits=explain.commits)[0]
except (KeyError, TypeError, ValueError):
    st.warning(UNREADABLE)
    st.stop()
TITLE, BODY = TEXT.split("\n", 1)
st.subheader(TITLE.removeprefix("# "))
# A dollar sign would start a formula on the page.
st.markdown(BODY.replace("$", "\\$"))
