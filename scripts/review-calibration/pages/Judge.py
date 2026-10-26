"""The lab's Judge page: check the review judge by hand. A person labels pairs of one reviewer comment and one run's leads,
seeing what the judge was asked, and the page shows how often the two agree."""

import argparse
from pathlib import Path

import streamlit as st

import batch
import dashboard
import judge
import labels
import saved

dashboard.start_page()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
FOLDER = arguments.parse_known_args()[0].folder
st.title("Judge")
st.caption("How far to trust the review scores. Each pair is one reviewer comment and one run's leads, word for word as the judge was asked, without the "
           "judge's answer or the setting that ran. Answer as the judge should, by its rules; the scores are worth as much as you and the judge agree.")
# Labelling shows the reviewer's comment, which would make an unseen case seen.
KEPT = sorted({path.parents[2].name for path in (FOLDER / "experiments").glob("*/*/*/judge-calls.json") if not batch.unseen(path.parents[2])})
if not KEPT:
    st.info("No experiment has a judged run yet. Run and score one with `uv run batch.py NAME --judge`")
    st.stop()
NAME = st.selectbox("Experiment", KEPT, key="experiment")
EXPERIMENT = FOLDER / "experiments" / NAME
try:
    FOUND, GIVEN = labels.pairs(EXPERIMENT), labels.read(EXPERIMENT)
except (OSError, KeyError, TypeError, ValueError) as problem:
    st.warning(f"The judged runs or the labels of {NAME} could not be read: {problem}")
    st.stop()
DONE = sum(pair.key in GIVEN for pair in FOUND)
if DONE:
    MADE = labels.agreement(FOUND, GIVEN)
    st.metric("You and the judge agree", f"{MADE['agree']:,} of {MADE['compared']:,}", border=True, width=dashboard.TILE,
              help=f"{MADE['rate']:.0%} of the pairs you labelled.")
    st.caption("How the judge answered the pairs you labelled, one row for each of your answers:")
    st.dataframe([{"you": label, **{f"judge {verdict}": count for verdict, count in row.items()}} for label, row in MADE["table"].items()], hide_index=True)
st.caption(f"Labelled {DONE:,} of {len(FOUND):,}. " + ("Enough for a first number." if DONE >= labels.ENOUGH else f"{labels.ENOUGH} give a first number."))
with st.expander("The judge's rules"):
    st.text(judge.SYSTEM)
NEXT = next(((number, pair) for number, pair in enumerate(FOUND, 1) if pair.key not in GIVEN), None)
if NEXT is None:
    st.success("Every pair is labelled.")
else:
    NUMBER, PAIR = NEXT
    st.markdown(f"##### Pair {NUMBER} of {len(FOUND)}")
    st.code(PAIR.shown, language=None, wrap_lines=True)
    with st.container(horizontal=True):
        for VERDICT in judge.VERDICTS:
            st.button(VERDICT.capitalize(), key=f"label_{VERDICT}", on_click=labels.give, args=(EXPERIMENT, PAIR.key, VERDICT))
if GIVEN:
    st.button("Take back the last label", key="label_back", on_click=labels.give, args=(EXPERIMENT, next(reversed(GIVEN)), None))
