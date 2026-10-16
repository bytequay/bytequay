"""The lab's Memory page: whether the memories ByteQuay gives a turn are used, from the table `uv run memory.py --save`
keeps."""

import argparse
from pathlib import Path
import sqlite3
import statistics

import streamlit as st

import activity
import dashboard
import memory
import saved

SAVE = "uv run memory.py --save"
DECIDED = 20  # decided findings on each side before their shares say anything

st.set_page_config(page_title="ByteQuay Lab", layout="wide")
# Started any other way, Streamlit would serve the page to the whole network.
if st.get_option("server.address") != dashboard.HOST:
    st.error("This page is only shown to this machine. Start it with `uv run dashboard.py`")
    st.stop()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
arguments.add_argument("--database", type=Path, default=activity.RUNTIME.parent / "bytequay-1.0.db")
CHOSEN = arguments.parse_known_args()[0]
FOLDER = CHOSEN.folder
st.title("Memory")
st.caption("Whether the memories ByteQuay gives a turn are used. A turn of a repository with memories is given them at the start, and a review finding names "
           "the memories it relied on. A memory given many times and never cited costs every turn and helps none: rewrite it or remove it on the Memory page of ByteQuay.")
try:
    MADE = saved.read(FOLDER, memory.FILE)
except (OSError, ValueError):
    st.warning(f"The saved memory table could not be read. Save it again with `{SAVE}`")
    st.stop()
if MADE is None:
    st.info(f"Nothing is saved yet. Save it with `{SAVE}`; the monitor saves it every hour while it runs.")
    st.stop()
st.caption(f"Saved {dashboard.when(MADE['savedAt'])}.")
try:
    TITLES = memory.titles(CHOSEN.database)
except (OSError, sqlite3.Error):
    TITLES = {}
    st.caption("ByteQuay's database could not be read, so memories are shown by their ids.")
MEMORIES, TURNS = MADE["memories"], MADE["turns"]
NEVER = [one for one in MEMORIES if one["given"] and not one["cited"]]
with st.container(horizontal=True):
    st.metric("Memories", f"{len(MEMORIES):,}", border=True, width=dashboard.TILE)
    st.metric("Never cited", f"{len(NEVER):,}", border=True, width=dashboard.TILE, help="Given to turns, and never named by a review finding.")
    st.metric("Turns given memory", f"{len(TURNS):,}", border=True, width=dashboard.TILE)
    st.metric("Memory tokens a turn", "none" if not TURNS else f"{statistics.median(one['characters'] for one in TURNS) / 4:,.0f}", border=True, width=dashboard.TILE,
              help="About a token for every four characters of the memories a turn is given, sent again at every model call of the turn.")
st.subheader("Each memory")
st.caption("How many turns each memory was given to and how many review findings cite it, the ones never cited first.")
st.dataframe(sorted(({"memory": TITLES.get(one["id"]) or one["id"], "kind": one["kind"].lower(), "given": one["given"], "cited": one["cited"],
                      "cited per 100 given": 100 * one["cited"] / one["given"] if one["given"] else None} for one in MEMORIES),
                    key=lambda row: (row["cited"] > 0, -row["given"])),
             hide_index=True, column_config={"cited per 100 given": st.column_config.NumberColumn(format="%.1f")})
st.subheader("Findings that cite a memory")
HELD = memory.held(MADE["findings"])
st.dataframe([{"findings that": name, **row} for name, row in HELD.items()], hide_index=True, column_config={"held up": st.column_config.NumberColumn(format="%.0f%%")})
FEW = [name for name, row in HELD.items() if row["resolved"] + row["withdrawn"] < DECIDED]
st.caption("Held up is the share of the decided findings that were resolved rather than withdrawn. "
           + (f"Fewer than {DECIDED} findings that {' or '.join(FEW)} are decided, so the shares cannot tell yet whether memory helps." if FEW else
              "Findings of one review are alike, so a gap of a few points can still be chance."))
