#!/usr/bin/env python3
"""The lab's dashboard: the saved results as one page, shown to this machine only. Start it with `uv run dashboard.py`."""

from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import streamlit as st
from streamlit.runtime.scriptrunner import get_script_run_ctx

import activity
import approvals
import batch
import charts
import digest
import saved
import suggest

HOST, PORT = "127.0.0.1", 8517
SAVE = "uv run activity.py --save"
SHOW = {"width": "stretch", "config": {"displayModeBar": False}}
TILE = 170
LOOKS = {"7 days": 7, "14 days": suggest.DAYS, "28 days": 28, "every day": None}  # how far back the suggestions look
MARKDOWN = re.compile(r"([\\`*_\[\]<>#|~$])")


def draw(name, figure):
    """Draw a chart under a name of its own. Two charts can hold the same numbers, and the page must still tell them apart."""
    st.plotly_chart(figure, key=name, **SHOW)


def when(moment):
    """A saved time in the reader's own time zone."""
    return datetime.fromisoformat(moment).astimezone().strftime("%Y-%m-%d %H:%M")


def chosen(made, table):
    """The turns the reader asks for in the side bar, and the choice itself for what is not a turn."""
    days = made["turns"]["day"]
    with st.sidebar:
        picked = st.date_input("Days", (days.min(), days.max()), min_value=days.min(), max_value=days.max())
        # While the reader is still picking the second day there is only one, and it stands for both ends.
        first, last = (picked[0], picked[-1]) if picked else (days.min(), days.max())
        shown = charts.within(made, first, last)
        names = [row["model and effort"] for row in charts.settings_table(made["turns"])]
        settings = st.multiselect("Model and effort", names, placeholder="All") if names else []
        if settings:
            shown = charts.only(shown, shown["turns"]["setting"].isin(settings))
        names = sorted(set(made["turns"]["repository"].dropna()))
        repositories = st.multiselect("Repository", names, placeholder="All") if names else []
        if repositories:
            shown = charts.only(shown, shown["turns"]["repository"].isin(repositories))
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
    return shown, (first, last, settings, repositories)


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
        draw("states_donut", charts.states_donut(parts, mode))
    with right:
        st.subheader("How the turns ended")
        draw("endings_donut", charts.endings_donut(turns, mode))
    st.subheader("Turn time per day")
    draw("days_figure", charts.days_figure(turns, parts, mode))
    for note in activity.notes(turns["parallel"].sum(), int(turns["mostAtOnce"].max()), int(turns["unfinished"].sum()),
                               table["leftOut"]["skippedLines"], table["leftOut"]["unreadLogs"]):
        st.caption(note)


def time(shown, mode):
    turns, parts, programs, after = shown["turns"], shown["parts"], shown["programs"], shown["after"]
    colors, waiting = charts.COLORS[mode], charts.STATES[1]
    st.subheader("Every part of the time")
    draw("sunburst", charts.sunburst(parts, mode))
    waited = charts.part_rows(parts, waiting)
    if waited:
        st.subheader(f"{waiting}: what was running")
        draw("waited", charts.bars_figure(waited, turns["seconds"].sum(), colors["tool"]))
    ranked = charts.program_rows(programs)
    if ranked:
        st.subheader("Shell time by program, summed over runs")
        draw("programs", charts.bars_figure(ranked, programs["seconds"].sum(), colors["tool"], "runs"))
        every = charts.program_rows(programs, top=len(programs))
        with st.expander(f"All {len(every):,} programs"):
            st.dataframe([{"program": name, "minutes": round(length / 60, 1), "runs": runs} for name, length, runs in every], hide_index=True)
    reacted = charts.model_rows(after)
    if reacted:
        st.subheader("Model time by what just finished")
        draw("reacted", charts.bars_figure(reacted, after["seconds"].sum(), colors["model"], "times"))
    st.subheader("The tasks that took the most time")
    draw("tasks_figure", charts.tasks_figure(turns, parts, mode))
    if turns["repository"].notna().any():
        st.subheader("Turn time by repository")
        draw("repositories_figure", charts.repositories_figure(turns, parts, mode))


def slow_spots(shown, mode):
    turns, runs, waits, notices = shown["turns"], shown["runs"], shown["waits"], shown["notices"]
    st.subheader("Long single commands")
    if runs.empty:
        st.caption(f"No single command ran for {activity.LONG_RUN} seconds or more.")
    else:
        st.caption(f"{activity.count(len(runs), 'single command')} ran for {activity.LONG_RUN} seconds or more: {runs['seconds'].sum() / 60:,.1f} minutes in all.")
        draw("long_runs", charts.bars_figure(charts.long_rows(runs), runs["seconds"].sum(), charts.COLORS[mode]["tool"], "runs"))
        st.dataframe(charts.longest_runs(runs, turns), hide_index=True)
    st.subheader("Waiting for you")
    if waits.empty:
        st.caption("The agent never had to wait for your answer.")
    else:
        expired = int((waits["outcome"] == "expired").sum())
        st.caption(f"The agent asked {activity.count(len(waits), 'time')} and waited {waits['seconds'].sum() / 60:,.1f} minutes in all. A typical answer took "
                   f"{waits['seconds'].median():,.0f} seconds." + (f" {activity.count(expired, 'wait')} ran out with no answer, which ends the turn." if expired else ""))
        left, right = st.columns(2)
        with left:
            draw("waits_donut", charts.waits_donut(waits, mode))
        with right:
            draw("waits_figure", charts.waits_figure(waits, mode))
    st.subheader("Rate limits and retries")
    if notices.empty:
        st.caption("No rate limit notice and no retry.")
    else:
        counted = notices.groupby("what")["times"].sum()
        st.caption("The logs say how often these came, not how long they held a turn up. " + ", ".join(f"{what}: {int(times):,}" for what, times in counted.items()) + ".")
        draw("notices_figure", charts.notices_figure(notices, turns, mode))


def tools(shown, table, mode):
    turns = shown["turns"]
    try:
        made = charts.calls_of(charts.call_frames(table), turns)
    except (KeyError, TypeError, ValueError):
        # A table from before the lab counted calls, or from before it read why they failed, is never drawn as one without them.
        st.info(f"This table was saved before the lab counted what this tab shows. Save it again with `{SAVE}`")
        return
    calls, reasons = made["calls"], made["reasons"]
    if calls.empty:
        st.info("No turn of this choice called a command or a tool.")
        return
    commands, tool_calls = calls[calls["kind"] == "command"], calls[calls["kind"] == "tool"]
    rate = lambda frame: f"{charts.share(frame['failed'].sum(), frame['runs'].sum()):.1f}%" if len(frame) else "none ran"
    of = lambda frame: f"{int(frame['failed'].sum()):,} of {int(frame['runs'].sum()):,} failed."
    with st.container(horizontal=True):
        st.metric("Calls", f"{int(calls['runs'].sum()):,}", border=True, width=TILE, help="Commands and tool calls that finished.")
        st.metric("Commands failed", rate(commands), border=True, width=TILE, help=of(commands))
        st.metric("Tool calls failed", rate(tool_calls), border=True, width=TILE, help=of(tool_calls))
        st.metric("Found nothing", f"{int(calls['noMatch'].sum()):,}", border=True, width=TILE, help="Searches with rg or grep that matched nothing. They are not counted as failed.")
        st.metric("Time in calls", f"{calls['seconds'].sum() / 3600:.1f} h", border=True, width=TILE, help="Added up over the calls, so two calls that ran at once both count in full.")
    st.caption("A command is named after its program and a tool by its name. A test run that fails counts as failed, also when the test was meant to fail. "
               "Permission hooks are not calls and are left out.")
    st.subheader("Worth a tool?")
    if charts.candidates(calls).empty:
        st.caption(f"No program or tool was called {charts.MIN_RUNS} times or more.")
    else:
        st.caption(f"One bubble for every program and tool with {charts.MIN_RUNS} calls or more. Further right: called more often. Higher: failed more often. "
                   "Bigger: took more time. A big bubble high on the right is where a tool of its own, or a rule, would pay first.")
        draw("candidates_figure", charts.candidates_figure(calls, mode))
    st.subheader("Every command and tool")
    st.dataframe(charts.calls_table(calls), hide_index=True, column_config={"name": st.column_config.TextColumn(width="medium")})  # room for a long tool name
    st.subheader("Why commands failed")
    if reasons.empty:
        st.caption("No command failed.")
    else:
        rows = charts.reason_rows(reasons)
        st.caption("Read from the words a failed command printed: the first reason that fits. \"No output kept\" means the log holds nothing to read, "
                   "often because the output went into another program or a file. \"Other\" is a failure whose words name none of these reasons.")
        draw("reasons_figure", charts.reasons_figure(rows, mode))
        st.dataframe([{"reason": label, "failed commands": times, "programs it hit most": programs} for label, times, programs in rows], hide_index=True)
    st.subheader("What commands hand back")
    printed = charts.printed_rows(calls)
    if not sum(amount for _, amount, _ in printed):
        st.caption("The log kept no output of a command.")
    else:
        st.caption(f"Commands printed {charts.short(sum(amount for _, amount, _ in printed))} characters, which the model then reads. Characters are a rough stand-in for tokens. "
                   "What a tool call hands back is not in the logs.")
        draw("printed_figure", charts.printed_figure(printed, mode))
    merging(turns, made["hops"], made["repeats"], mode)
    st.subheader("By provider")
    st.caption("Different tasks ran under each provider, so this shows what happened, not which one is better.")
    st.dataframe(charts.providers_table(calls, turns), hide_index=True)
    if len(commands):
        st.caption(f"How often the most used commands failed under each provider. A cell with fewer than {charts.MIN_RUNS} runs says how few and has no color.")
        draw("providers_figure", charts.providers_figure(calls, turns, mode))


def merging(turns, hops, repeats, mode):
    st.subheader("Would merging or caching pay?")
    whole, (_, together, _), (_, hopped, hop_seconds), _ = turns["seconds"].sum(), *charts.gap_rows(hops)
    after = int(hops["times"].sum())
    with st.container(horizontal=True):
        st.metric("Merging, at most", f"{charts.share(hop_seconds, whole):.1f}%", border=True, width=TILE,
                  help=f"Of all turn time: {hop_seconds / 60:,.1f} minutes in {hopped:,} short hops between one result and the next call.")
        st.metric("Caching, at most", f"{charts.share(repeats['unchangedSeconds'].sum(), whole):.1f}%", border=True, width=TILE,
                  help=f"Of all turn time: {repeats['unchangedSeconds'].sum() / 60:,.1f} minutes in {int(repeats['unchangedRuns'].sum()):,} runs of a line already run, with nothing written in between.")
        st.metric("Already together", f"{charts.share(together, after):.0f}%" if after else "no calls", border=True, width=TILE,
                  help="Of the calls that had something before them, the share the model asked for in the same answer as the one before.")
    st.caption(f"A call counts from what the model last saw end or start. Within {activity.ONE_ANSWER:g} seconds it was asked for in the same answer; within {activity.SHORT_HOP:g} "
               "seconds it is a short hop from one result to the next call; anything longer is a long step, where the model was thinking or writing. Merging calls into one could save "
               "at most the short hops, and only where the second call did not need the first one's result. A cache could save at most the runs of a line already run with nothing "
               "written in between, and only where the line gives the same answer twice.")
    if after:
        draw("gaps_figure", charts.gaps_figure(hops, whole, mode))
    pairs = charts.hop_pairs_table(hops)
    if pairs:
        st.caption("The short hops with the most time, by what the model last saw and the call it then made.")
        st.dataframe(pairs, hide_index=True)
    if repeats.empty:
        st.caption("No command line ran twice in a task.")
    else:
        st.caption("Runs of a command line already run in the same folder of the same task, by program.")
        draw("repeats_figure", charts.repeats_figure(charts.repeat_rows(repeats), mode))


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
    draw("heat_figure", charts.heat_figure(turns, mode))
    st.subheader("How long a turn took")
    st.caption("A box holds the middle half of the turns. The line in it is the typical turn, and each dot is a turn far from the rest.")
    draw("lengths_figure", charts.lengths_figure(turns, mode))
    if charts.settings(turns, logged=True):
        st.subheader("How each split its time")
        st.caption("Only turns that also have a log.")
        draw("split_figure", charts.split_figure(turns, shown["parts"], mode))
    st.subheader("How the turns ended")
    draw("outcomes_figure", charts.outcomes_figure(turns, mode))


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
    st.caption(f"Tokens are known for {len(kinds):,} of {len(turns):,} turns. Each amount is the turn's own.")
    left, right = st.columns(2)
    with left:
        st.subheader("Tokens by kind")
        draw("tokens_donut", charts.tokens_donut(turns, mode))
    with right:
        st.subheader("Tokens by model and effort")
        draw("tokens_settings_figure", charts.tokens_settings_figure(turns, mode))
    st.subheader("Tokens per day")
    draw("tokens_days_figure", charts.tokens_days_figure(turns, mode))
    st.subheader("The tasks that used the most tokens")
    draw("tokens_tasks_figure", charts.tokens_tasks_figure(turns, mode))
    st.subheader("Cost in dollars")
    st.caption("Recorded cost is the Claude command line's own figure at API prices. On a subscription it is not what you paid.")
    st.dataframe(charts.cost_table(costed), hide_index=True)
    if (costed["how"] != "unknown").any():
        draw("dollars_days_figure", charts.dollars_days_figure(costed, mode))
    missing = charts.unpriced(costed)
    if missing:
        st.caption(f"No cost is known for {', '.join(missing)}. To estimate it, save this as `prices.json` in `{folder}` with your own prices, in dollars for a million tokens:")
        wanted = {model: dict(zip(charts.PRICES, amounts)) for model, amounts in prices.items()} | {model: dict.fromkeys(charts.PRICES, 0) for model in missing}
        st.code(json.dumps({"version": 1, "dollarsPerMillionTokens": wanted}, indent=2), language="json")


def real_reviews(folder, choice, together, mode):
    st.subheader("Reviews ByteQuay ran")
    try:
        table = saved.read(folder, "reviews.json")
        made = None if table is None else charts.reviews_chosen(charts.review_frames(table), *choice)
        apart = None if table is None or table["savedAt"] == together else when(table["savedAt"])
    except (OSError, KeyError, TypeError, ValueError):
        st.warning(f"The saved reviews could not be read. Save them again with `{SAVE}`")
        return
    if made is None:
        st.info(f"Nothing saved yet. Save the reviews with `{SAVE}`")
        return
    if apart:
        # One save writes the turns and the reviews with the same time. Another time means a later save could not read the reviews.
        st.warning(f"These reviews were saved {apart}, at another time than the turns. Save both again with `{SAVE}`")
    runs, findings = made["runs"], made["findings"]
    if runs.empty:
        st.info("No review run matches this choice.")
        return
    kept = charts.held_up(findings)
    with st.container(horizontal=True):
        st.metric("Reviews", f"{runs['review'].nunique():,}", border=True, width=TILE)
        st.metric("Runs", f"{len(runs):,}", border=True, width=TILE)
        st.metric("Findings", f"{len(findings):,}", border=True, width=TILE)
        st.metric("Held up", "none decided" if kept is None else f"{kept:.0f}%", border=True, width=TILE,
                  help="Of the findings that are decided, the share that was resolved and not withdrawn.")
        st.metric("Still open", f"{int((findings['state'] == 'open').sum()):,}", border=True, width=TILE)
    st.caption("Resolved: you or a development agent marked the finding done. Withdrawn: the reviewer took it back. Open: not decided yet. "
               "Held up is the resolved share of the first two.")
    left, right = st.columns(2)
    with left:
        if len(findings):
            draw("findings_donut", charts.findings_donut(findings, mode))
    with right:
        draw("run_endings_donut", charts.run_endings_donut(runs, mode))
    st.caption("How many findings a run raised.")
    draw("findings_per_run", charts.findings_per_run_figure(runs, findings, mode))
    if len(findings):
        st.caption("What became of the findings of each severity.")
        draw("severity", charts.severity_figure(findings, mode))
    st.caption("Different pull requests were reviewed under each model and effort, so this shows what happened, not which one reviews better. "
               "Asked again counts the runs whose report was not complete, so ByteQuay asked the reviewer once more.")
    st.dataframe(charts.review_settings_table(runs, findings), hide_index=True, column_config={"typical tokens in": st.column_config.NumberColumn(format="localized")})


@st.fragment(run_every=5)
def experiment_runs(plan):
    """How the runs of an experiment stand, with a button to run and judge what is missing or to stop the runs. It looks again
    every few seconds, so a run that ends shows without a reload."""
    lines, made = batch.listing(plan), batch.standing(plan)
    unjudged = sum(stands == "completed" and not judged for _, _, stands, judged in made)
    waiting = unjudged or any(stands not in batch.ENDED for _, _, stands, _ in made)
    st.caption(f"Runs: {lines[-1]}" + (f", {unjudged} of them not judged yet." if unjudged else "."))
    if batch.runner(plan) is not None:
        st.button("Stop", key="stop_runs", on_click=batch.stop, args=(plan,))
    elif waiting or not (plan["folder"] / "scores.json").exists():  # with nothing left to run or judge, a start only saves the scores
        st.button("Run and judge what is missing", key="start_runs", on_click=batch.start, args=(plan,))
        if waiting and not os.environ.get("DEEPSEEK_API_KEY"):
            st.caption("Judging needs DEEPSEEK_API_KEY set where this page was started. Without it, judge in a terminal with "
                       f"`uv run batch.py {plan['folder'].name} --judge`")
    with st.expander("Every run"):
        st.text("\n".join(lines[:-1]))
        log = plan["folder"] / "batch.log"
        if log.exists():
            st.caption("The end of batch.log, where a run started here writes what it says:")
            st.code("\n".join(log.read_text().splitlines()[-5:]), language=None)


def scored_reviews(folder, mode):
    st.subheader("Scored against a human reviewer")
    # Every experiment keeps its plan, its runs and its scores. The one worked on last comes first.
    kept = sorted({path.parent for pattern in ("*/plan.json", "*/scores.json") for path in (folder / "experiments").glob(pattern)},
                  key=lambda experiment: (-experiment.stat().st_mtime, experiment.name))
    if not kept:
        st.info("Nothing scored yet. Run and score an experiment with `uv run batch.py NAME --judge`")
        return
    name = st.selectbox("Experiment", [experiment.name for experiment in kept], key="experiment")
    plan = None
    if (folder / "experiments" / name / "plan.json").exists():
        try:
            plan = batch.load(folder, name)
        except ValueError as problem:
            st.caption(f"It cannot run: {problem}")
    if plan is not None:
        experiment_runs(plan)
    if not (folder / "experiments" / name / "scores.json").exists():
        st.info(f"{name} is not scored yet." + ("" if plan else f" Score it with `uv run batch.py {name} --judge`"))
        return
    try:
        scores = saved.read(folder / "experiments" / name, "scores.json")
        settings, runs, notes = charts.score_settings_table(scores), charts.score_runs_table(scores), charts.score_notes(scores)
        figure = charts.scores_figure(scores, mode) if any(total["comments"] for _, _, total in charts.score_totals(scores)) else None
        scored = [setting["name"] for setting in scores["settings"] if setting["runs"]]
        pairs = {(first, second): charts.compared(scores, first, second) for first in scored for second in scored}
    except (OSError, AttributeError, KeyError, TypeError, ValueError):
        st.warning(f"The saved scores of {name} could not be read. Save them again with `uv run report.py EXPERIMENT --save`")
        return
    if not settings:
        st.info(f"The saved scores of {name} hold no setting.")
        return
    st.caption(f"What each setting found of the comments a human reviewer left on the same pull requests. Saved {when(scores['savedAt'])}")
    if figure is not None:
        draw("scores", figure)
    for note in notes:
        st.caption(note)
    numbers = {name: st.column_config.NumberColumn(format="localized") for name in ("tokens in", "cached", "tokens out", "judge tokens in", "judge tokens out")}
    dollars = {"recorded cost": st.column_config.NumberColumn(format="dollar")}
    st.dataframe(settings, hide_index=True, column_config=numbers | dollars)
    if len(scored) >= 2:
        st.subheader("Two settings, comment by comment")
        left, right = st.columns(2)
        first = left.selectbox("Setting", scored, key="compare_first")
        second = right.selectbox("Against", scored, index=1, key="compare_second")
        rows, counts = pairs[first, second]
        st.caption(charts.compared_in_words(first, second, counts))
        st.dataframe(rows, hide_index=True, column_config={"first": st.column_config.TextColumn(first), "second": st.column_config.TextColumn(second)})
    with st.expander(f"All {len(runs):,} scored runs"):
        st.dataframe(runs, hide_index=True, column_config=numbers | dollars)


def plain(text):
    """Text as it is, with nothing in it read as Markdown: a rule such as Bash(./mvnw -o:*) holds a star."""
    return MARKDOWN.sub(r"\\\1", text)


@st.cache_data(ttl=300, show_spinner=False)
def read_logs(runtime, since=None, until=None):
    """The asks, Codex's own reviews and command lines of every task log, kept for a few minutes because reading them all takes about a
    second. The command lines stay in this process to match rules against and are never shown."""
    asks, automatic, lines, unanswered = [], [], [], 0
    for log in sorted(Path(runtime).glob("*/events.jsonl")):
        made, reviewed, run, left = approvals.read_log(log, since, until)
        asks, automatic, lines, unanswered = asks + made, automatic + reviewed, lines + run, unanswered + left
    return asks, automatic, lines, unanswered


def refresh(folder, runtime):
    """A button that reads the task logs and ByteQuay's database again and saves the tables, as SAVE does in a terminal."""
    with st.sidebar:
        if not st.button("Read the logs again", key="refresh", help=f"Saves the tables again from the task logs and ByteQuay's records, as `{SAVE}` does"):
            return
        with st.spinner("Reading the logs"):
            try:
                done = subprocess.run([sys.executable, str(Path(__file__).with_name("activity.py")), "--save", str(folder), "--runtime", str(runtime)],
                                      capture_output=True, text=True, timeout=600)
                problem = (done.stderr.strip().splitlines() or ["it gave no reason"])[-1] if done.returncode else None
            except subprocess.TimeoutExpired:
                problem = "it took more than ten minutes"
        read_logs.clear()
        if problem:
            st.error(f"The logs could not be read again: {problem}")


def suggestions(made, table, folder, runtime):
    """What the lab suggests changing in ByteQuay, each with a button to note the change once it is made, and after that the number to watch."""
    st.caption("What to change in ByteQuay, the most time each could save first. They come from every model and repository in the days picked here; the side bar does "
               f"not apply. Once you have changed ByteQuay, press \"I made this change\": the suggestion then shows its number in the {suggest.BEFORE} days before and in the days since.")
    look = st.radio("Look at the last", list(LOOKS), index=1, horizontal=True, key="look")
    days = made["turns"]["day"]
    last = days.max()
    first = days.min() if LOOKS[look] is None else max(days.min(), last - timedelta(days=LOOKS[look] - 1))
    try:
        calls = charts.call_frames(table)
    except (KeyError, TypeError, ValueError):
        calls = None
        st.caption(f"The turn table was saved before the lab counted calls, so command failures and tool hops are left out. Save it again with `{SAVE}`")
    try:
        found = saved.read(folder, "reviews.json")
        reviews = None if found is None else charts.review_frames(found)
    except (OSError, KeyError, TypeError, ValueError):
        reviews = None
        st.caption("The saved reviews could not be read, so review runs are left out here.")
    try:
        noted = digest.changes(folder)
    except (OSError, KeyError, TypeError, ValueError):
        noted = []
        st.caption("The noted changes in `changes.json` could not be read.")
    asks = read_logs(str(runtime))[0]
    data = suggest.window(made, calls, reviews, asks, first, last)
    listed = suggest.suggestions(data)
    if not listed:
        st.caption("Nothing in these days passes the limits for a suggestion.")
    for place, one in enumerate(listed, 1):
        with st.container(border=True):
            st.markdown(f"##### {place}. {plain(one.title)}")
            st.caption(f"Could save up to {one.saves:,.1f} minutes a week. Watch: {one.watch}, now {suggest.number(one.measure(data))}.")
            st.markdown(plain(f"Seen: {one.seen}"))
            st.markdown(plain(f"Do: {one.advice}"))
            st.markdown("Where: " + "; ".join(f"`{path}` ({plain(what)})" if path else plain(what) for path, what in one.where))
            mine = [change for change in noted if change["what"] == one.title]
            if mine:
                day = mine[-1]["day"]
                before, since, turns = suggest.checked(one, made, calls, reviews, asks, day, last)
                st.caption(f"You noted this change on {suggest.said(day)}. {one.watch}: {suggest.number(before)} in the {suggest.BEFORE} days before, "
                           f"{suggest.number(since)} since, over {activity.count(turns, 'turn')}.")
            st.button("I changed it again" if mine else "I made this change", key=f"noted_{place}", on_click=digest.note, args=(folder, one.title, date.today()))


def asked(runtime, first, last):
    """The approval cards of the days in the side bar, the rules their commands need, and a test of rules the reader picks or writes."""
    since, until = (datetime.combine(day, datetime.min.time()).astimezone().timestamp() for day in (first, last + timedelta(days=1)))
    asks, automatic, lines, _ = read_logs(str(runtime), since, until)
    st.caption("The approval cards ByteQuay showed you, read from the task logs for the days in the side bar. Model and repository do not apply here.")
    if not asks:
        st.caption("No approval was asked in these days.")
        return
    commands, ran_out = [ask for ask in asks if ask.what == "command"], [ask for ask in asks if ask.outcome == "expired"]
    with st.container(horizontal=True):
        st.metric("Asks", f"{len(asks):,}", border=True, width=TILE)
        st.metric("Minutes waited", f"{sum(ask.seconds for ask in asks) / 60:,.1f}", border=True, width=TILE)
        st.metric("Ran out", f"{len(ran_out):,}", border=True, width=TILE, help="Asks nobody answered in the time an ask may wait.")
        st.metric("Ended a turn", f"{sum(ask.stopped for ask in ran_out):,}", border=True, width=TILE, help="A Codex command that runs out stops its turn; Claude carries on.")
        st.metric("Codex reviewer", f"{len(automatic):,}", border=True, width=TILE, help="Requests Codex's own reviewer answered by itself, so no card was shown.")
    st.subheader("What was asked")
    st.dataframe([{"provider": provider, "kind": what, "asks": number, "ran out": ran, "minutes": round(seconds / 60, 1)}
                  for provider, what, number, ran, seconds in approvals.asked(asks)], hide_index=True)
    if not commands:
        return
    st.subheader("Rules the asked commands need")
    st.caption("A rule covers a command that starts with its words, in the shape ByteQuay already writes for Claude. Alone: the asks a rule answers by itself, because "
               "their line holds no other command. Minutes: what those asks waited. A Codex ask is its model asking to run a command outside its sandbox.")
    rows = approvals.rule_rows(commands)
    st.dataframe([{"rule": rule, "asks that need it": need, "alone": alone, "minutes": round(seconds / 60, 1), "codex": codex, "claude": claude}
                  for rule, need, alone, seconds, codex, claude in rows], hide_index=True, column_config={"rule": st.column_config.TextColumn(width="medium")})
    hidden, unread = approvals.uncovered(commands)
    if hidden:
        st.caption(f"No rule can cover {activity.count(len(hidden), 'ask')}: a line with $(...), backticks or <(...), or one that starts by setting a variable.")
    if unread:
        st.caption(f"The command of {activity.count(len(unread), 'ask')} could not be read.")
    st.subheader("Test rules")
    picked = st.multiselect("Rules from the table", [row[0] for row in rows], key="rules", placeholder="Pick rules to test")
    typed = st.text_area("Rules of your own, one a line", key="own_rules", placeholder="Bash(./mvnw -o:*)")
    rules, wrong = [], []
    for text in [*picked, *typed.splitlines()]:
        try:
            rules += [approvals.parse_rule(text)] if text.strip() else []
        except ValueError:
            wrong.append(text.strip())
    if wrong:
        st.warning("Not a rule, so left out: " + ", ".join(wrong) + ". A rule looks like Bash(./mvnw -o:*) or Bash(git status).")
    if not rules:
        st.caption("Pick or write a rule to see what it would have answered.")
        return
    made = approvals.effect(commands, lines, rules)
    with st.container(horizontal=True):
        st.metric("Asks answered", f"{made['answered']} of {made['asks']}", border=True, width=TILE, help="Command asks every command of whose line matches a rule.")
        st.metric("Minutes saved", f"{made['saved'] / 60:,.1f}", border=True, width=TILE, help=f"Of {made['waited'] / 60:,.1f} minutes waited for command asks.")
        st.metric("Turns kept going", f"{made['kept']} of {made['stopped']}", border=True, width=TILE, help="Turns an ask ended that these rules would have answered.")
        st.metric("Commands covered", f"{sum(times for _, times in made['covered']):,}", border=True, width=TILE,
                  help=f"Of the {made['runs']:,} commands run in these days, asked for or not: how wide the rules are.")
    if made["denied"]:
        st.warning(f"You denied {activity.count(made['denied'], 'ask')} that these rules would have allowed.")
    st.caption("Risks in what they answer: " + (", ".join(f"{label} {times}" for label, times in made["risks"]) or "none found")
               + ". A risk is a word in the line worth a closer look, not a judgment that a line is safe.")
    if made["covered"]:
        st.caption("Commands they cover, by program: " + ", ".join(f"{name} {times:,}" for name, times in made["covered"]) + ".")


def timed(tab, logged, *more):
    """Draw a tab that is about time, or say that no turn of this choice has a log to split."""
    if len(logged["turns"]):
        tab(logged, *more)
    else:
        st.info("No turn of this choice has a log.")


def page(folder, runtime):
    st.set_page_config(page_title="ByteQuay Lab", layout="wide")
    # Started any other way, Streamlit would serve the page to the whole network.
    if st.get_option("server.address") != HOST:
        st.error("This page is only shown to this machine. Start it with `uv run dashboard.py`")
        return
    st.title("ByteQuay Lab")
    refresh(folder, runtime)
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
    shown, choice = chosen(made, table)
    if shown["turns"].empty:
        st.info("No turn matches this choice.")
        return
    mode = "dark" if st.context.theme.type == "dark" else "light"
    # The first four tabs are about time and calls, which only a log holds. The others are about what ByteQuay recorded.
    logged = charts.only(shown, shown["turns"]["logged"])
    ideas, first, second, third, called, cards, fourth, fifth, sixth = st.tabs(["Suggestions", "Overview", "Time", "Slow spots", "Tools", "Approvals", "Models", "Cost", "Reviews"])
    with ideas:
        suggestions(made, table, folder, runtime)
    with first:
        timed(overview, logged, table, mode)
    with second:
        timed(time, logged, mode)
    with third:
        timed(slow_spots, logged, mode)
    with called:
        timed(tools, logged, table, mode)
    with cards:
        asked(runtime, *choice[:2])
    with fourth:
        models(shown, table, mode)
    with fifth:
        cost(shown, table, folder, mode)
    with sixth:
        real_reviews(folder, choice, table["savedAt"], mode)
        scored_reviews(folder, mode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER, help="Folder with the saved results")
    parser.add_argument("--port", type=int, default=PORT, help="Port on this machine to show the page on")
    parser.add_argument("--runtime", type=Path, default=activity.RUNTIME, help="Folder with the task logs: read for the approvals, and by the button that saves again")
    args = parser.parse_args()
    if get_script_run_ctx(suppress_warning=True) is None:
        # Started as a plain script: hand over to Streamlit, with the page shown to this machine only.
        from streamlit.web import cli
        sys.argv = ["streamlit", "run", __file__, "--server.address", HOST, "--server.port", str(args.port), "--server.headless", "true",
                    "--browser.gatherUsageStats", "false", "--client.toolbarMode", "minimal", "--", str(args.folder), "--runtime", str(args.runtime)]
        sys.exit(cli.main())
    page(args.folder, args.runtime)


if __name__ == "__main__":
    main()
