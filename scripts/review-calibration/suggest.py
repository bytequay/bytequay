#!/usr/bin/env python3
"""Turn what the lab measured into suggestions for ByteQuay: what to change, where, how much time it could save at most,
and the number to watch once it is changed. Made from the saved tables and the approvals in the task logs, for the last
days only, because an older leak may already be fixed. Start it with `uv run suggest.py`."""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Callable

import activity
import approvals
import charts
import saved

SAVE = "uv run activity.py --save"
DAYS = 14  # the days a suggestion looks at by default; older evidence may describe something ByteQuay has changed since
MIN_WEEKLY = 2.0  # minutes a week; a suggestion that could save less is left out
MIN_ASKS = 2  # asks a rule must answer by itself before it is suggested
MIN_FAILED = 3  # failed runs of one program with one reason before they make a suggestion
START_LIMIT = 45  # seconds; a turn whose first model step takes longer than this on average is worth a look
START_FLOOR = 20  # seconds a first step may take: it has to read the task
LONG_WEEKLY = 10.0  # minutes a week in long runs of one program before they make a suggestion
ASKED_AGAIN = 0.15  # the share of review runs asked again above which it is a suggestion
BEFORE = 7  # days before a noted change that the days since it are compared with
START, AFTER_TOOLS = "at the start of a turn", "after " + activity.BYTEQUAY

# The places in ByteQuay a suggestion points to, from the repository root, each with a name to look for in it. A test
# checks that each file is still there and still holds that name, so a move in ByteQuay breaks the test and not the advice.
CODEX_DRIVER = "backend/agent-cli/src/main/java/com/bytequay/agent/cli/codex/CodexCliDriver.java"
PERMISSIONS = "backend/agent-cli/src/main/java/com/bytequay/agent/cli/mcp/PermissionBroker.java"
INSTRUCTIONS = "backend/agent-cli/src/main/java/com/bytequay/agent/cli/ProviderInstructions.java"
MEMORY = "backend/app/src/main/java/com/bytequay/app/memory/MemoryDelivery.java"
CONTEXT = "backend/app/src/main/java/com/bytequay/app/context/ContextBuildService.java"
GUIDELINES = "backend/app/src/main/java/com/bytequay/app/agent/RepositoryGuidelineReader.java"
REVIEW_TOOLS = "backend/app/src/main/java/com/bytequay/app/review/ReviewTools.java"
REVIEW_SERVICE = "backend/app/src/main/java/com/bytequay/app/review/AgentReviewService.java"
RECIPE = (None, "a recipe in the repository's memory, on the Memory page, which every turn of that repository is given")
PROVIDERS = {
    "codex": ("Codex", (CODEX_DRIVER, "registerApproval"),
              "A Codex ask is for a command outside its sandbox, so an allowed line may write anywhere and reach the network. Answer such an ask in "
              "ByteQuay before any card is shown, where a review's remote writes are already refused, and only for a line with none of the risks it names."),
    "claude": ("Claude", (PERMISSIONS, "sandboxSettings"),
               "Add allow rules to the Claude settings ByteQuay writes, beside the deny rules it already writes there."),
}
# What to do about a command that failed for a reason the agent can avoid. A failing test or a compile error is the
# work itself, so it gets no suggestion.
FIXES = {
    "no test matched": "The run named a test that the module or the filter does not have. Have the agent list the tests first, or name the module it runs in.",
    activity.NOTHING_KEPT: "The output went into another program or a file, so a failure left nothing to read and the agent may run it again to see why. "
                           "Have the agent keep the last lines of a failing run.",
    "sandbox or permission": "The sandbox refused it. Allow the path it needs, or tell the agent where it may write.",
    "missing file or path": "The agent named a file, a folder or a program that is not there. Give it the layout, or have it search before it runs.",
    "network": "It needed the network. Run offline when the dependencies are cached, or allow the host.",
    "timeout or killed": "It ran out of time. Run less at once, or raise the limit.",
}
LONG_ADVICE = {
    "build": "Build only the module that changed, run only the tests it touches, and run offline when the dependencies are cached.",
    "wait loop": "The agent slept in a loop, waiting for something to finish. Have it wait for that job itself to end instead of sleeping and looking again.",
    None: "Run less at once, or let it run in the background while the agent goes on.",
}
MAVEN_NO_TEST = ("A Maven run that names one test fails in every other module it builds, because those modules lack the test. Run the test in its own "
                 "module, or add -Dsurefire.failIfNoSpecifiedTests=false when -am builds the others.")


@dataclass(frozen=True)
class Window:
    """What the suggestions are made from: the turns of some days, their calls, the review runs and the approval asks of the same days."""

    made: dict  # charts.frames, only these days
    calls: dict | None  # charts.call_frames of these turns; None for a table saved before calls were counted
    reviews: dict | None  # charts.review_frames of these days; None when no reviews are saved
    asks: tuple  # approvals.Ask of these days
    days: int

    def logged(self):
        return charts.only(self.made, self.made["turns"]["logged"])

    def hours(self):
        """Hours of turn time with a log, which every rate here is measured against."""
        return float(self.logged()["turns"]["seconds"].sum()) / 3600


@dataclass(frozen=True)
class Suggestion:
    """One change to make in ByteQuay. The title stays the same while the numbers move, so a change noted under it finds it again."""

    title: str
    saves: float  # minutes a week it could save at most; the list is ranked by it
    seen: str  # the numbers behind it
    advice: str
    where: tuple  # (path from the repository root or None for a place in the app, what to look for there)
    watch: str  # the number to watch once the change is made
    measure: Callable  # Window -> that number, or None when those days do not tell


def window(made, calls, reviews, asks, first, last, zone=None):
    """The turns, calls, review runs and asks from `first` to `last`, both days included. `calls` and `reviews` are the
    frames of the whole table or None."""
    shown = charts.within(made, first, last)
    kept = tuple(ask for ask in asks if first <= datetime.fromtimestamp(ask.start, zone or datetime.now().astimezone().tzinfo).date() <= last)
    return Window(shown, None if calls is None else charts.calls_of(calls, shown["turns"]), None if reviews is None else charts.reviews_chosen(reviews, first, last),
                  kept, (last - first).days + 1)


def weekly(seconds, days):
    return seconds / 60 / days * 7


def listed(names):
    return names[0] if len(names) == 1 else ", ".join(names[:-1]) + " and " + names[-1]


def said(day):
    return f"{day.day} {day:%b}"


def last_day(asks):
    return said(datetime.fromtimestamp(max(ask.start for ask in asks)).date())


def per_hour(amount, data):
    return amount / data.hours() if data.hours() else None


def allow_rules(data):
    """Rules that would have answered a provider's command asks by themselves, with the risks of what they would let through."""
    made = []
    for provider, (name, where, advice) in PROVIDERS.items():
        own = [ask for ask in data.asks if ask.what == "command" and ask.provider == provider]
        alone = Counter(approvals.suggested(ask.line)[0] for ask in own if len(approvals.suggested(ask.line) or ()) == 1)
        rules = sorted(rule for rule, times in alone.items() if times >= MIN_ASKS)
        if not rules:
            continue
        answered = [ask for ask in own if approvals.covered(ask.line, [approvals.parse_rule(rule) for rule in rules])]
        marked = Counter(label for ask in answered for label in approvals.risks(ask))
        stopped = sum(ask.stopped for ask in answered)
        seen = (f"{len(answered)} of {len(own)} {name} command asks match {activity.count(len(rules), 'rule')}: {', '.join(rules)}. You waited "
                f"{sum(ask.seconds for ask in answered) / 60:,.1f} minutes for them" + (f", and {activity.count(stopped, 'ask')} ran out and ended the turn" if stopped else "")
                + f". The last was on {last_day(answered)}." + (" Risks among them: " + ", ".join(f"{label} {times}" for label, times in marked.most_common()) + "." if marked else ""))
        programs = sorted({activity.program(approvals.parse_rule(rule)[0]) for rule in rules})
        made.append(Suggestion(f"Let {name} run {listed(programs)} without asking", weekly(sum(ask.seconds for ask in answered), data.days), seen, advice, (where,),
                               f"{name} command asks in an agent hour",
                               lambda window, provider=provider: per_hour(sum(ask.what == "command" and ask.provider == provider for ask in window.asks), window)))
    return made


def ran_out(data):
    """Asks that ran out with no answer and so ended their turn."""
    stopped = [ask for ask in data.asks if ask.stopped]
    if not stopped:
        return []
    kinds = Counter(f"{PROVIDERS.get(ask.provider, (ask.provider,))[0]} {ask.what}" for ask in stopped)
    seen = (f"{activity.count(len(stopped), 'ask')} ran out after {max(ask.seconds for ask in stopped) / 60:.0f} minutes with no answer and ended "
            f"{'its' if len(stopped) == 1 else 'their'} turn: " + ", ".join(f"{times} {kind}" for kind, times in kinds.most_common()) + f". The last was on {last_day(stopped)}.")
    advice = ("A Codex command or a Claude question that runs out stops the turn and the work it was doing. A desktop notice when a card appears, "
              "or a longer wait, would keep the turn going.")
    return [Suggestion("Make sure an ask is seen before it runs out", weekly(sum(ask.seconds for ask in stopped), data.days), seen, advice,
                       ((CODEX_DRIVER, "APPROVAL_TIMEOUT"), (PERMISSIONS, "APPROVAL_TIMEOUT")), "asks that ended their turn, of every ask (%)",
                       lambda window: charts.share(sum(ask.stopped for ask in window.asks), len(window.asks)) if window.asks else None)]


def failure_share(name):
    def measure(window):
        if window.calls is None:
            return None
        own = window.calls["calls"][(window.calls["calls"]["kind"] == "command") & (window.calls["calls"]["name"] == name)]
        return charts.share(float(own["failed"].sum()), float(own["runs"].sum())) if own["runs"].sum() else None
    return measure


def failing(data):
    """Programs that failed often for a reason the agent can avoid, with how much more often under one provider than another."""
    if data.calls is None:
        return []
    calls, reasons = data.calls["calls"], data.calls["reasons"]
    commands = calls[calls["kind"] == "command"]
    made = []
    for row in charts.call_sums(commands).itertuples(index=False):
        if row.runs < charts.MIN_RUNS:
            continue
        counted = reasons[reasons["program"] == row.name].groupby("reason")["times"].sum()
        rates = []
        for provider, group in charts.with_provider(commands[commands["name"] == row.name], data.made["turns"]).groupby("provider"):
            if group["runs"].sum() >= charts.MIN_RUNS:
                rates.append((charts.share(float(group["failed"].sum()), float(group["runs"].sum())), provider))
        gap = ""
        if len(rates) >= 2 and max(rates)[0] >= 20 and max(rates)[0] >= 3 * min(rates)[0]:
            (high, worse), (low, better) = max(rates), min(rates)
            gap = f" It failed in {high:.0f}% of runs under {worse} and {low:.0f}% under {better}, so how it is run matters more than the code."
        for reason, times in sorted(counted.items()):
            if reason not in FIXES or times < MIN_FAILED:
                continue
            seen = f"{row.name} ran {row.runs:,} times and failed {row.failed:,} times; {times} of the failures were \"{reason}\".{gap}"
            advice = MAVEN_NO_TEST if (row.name, reason) == ("maven", "no test matched") else FIXES[reason]
            # The failed runs are taken to be as long as the others, so this is their share of the program's time.
            made.append(Suggestion(f"Fix how agents run {row.name}: {reason}", weekly(row.seconds * int(times) / row.runs, data.days), seen, advice,
                                   (RECIPE, (INSTRUCTIONS, "compose")), f"{row.name} runs that fail (%)", failure_share(row.name)))
    return made


def model_after(before):
    def measure(window):
        rows = window.logged()["after"]
        rows = rows[rows["before"] == before]
        return float(rows["seconds"].sum() / rows["times"].sum()) if rows["times"].sum() else None
    return measure


def turn_start(data):
    """A first model step that takes long, turn after turn."""
    rows = data.logged()["after"]
    rows = rows[rows["before"] == START]
    times, seconds = int(rows["times"].sum()), float(rows["seconds"].sum())
    if times < charts.MIN_TURNS or seconds / times < START_LIMIT:
        return []
    beyond = float((rows["seconds"] - START_FLOOR * rows["times"]).clip(lower=0).sum())
    seen = f"Before its first call a turn spent {seconds / times:.0f} seconds of model time, over {times:,} turns: {seconds / 60:,.0f} minutes."
    advice = ("A turn starts with the task, the repository's guidelines, its memory and the pull request's context. Leave out what the first step does not "
              f"use, or start at a lower effort and raise it for the hard part. Time above {START_FLOOR} seconds a turn is counted as what this could save.")
    return [Suggestion("Shorten what a turn starts with", weekly(beyond, data.days), seen, advice,
                       ((CONTEXT, "ContextBuildService"), (MEMORY, "MemoryDelivery"), (GUIDELINES, "MAX_TOTAL_BYTES")), "model seconds at the start of a turn", model_after(START))]


def after_tools(data):
    """Short hops from one ByteQuay tool result to the next ByteQuay call, which one call returning both would save."""
    if data.calls is None:
        return []
    hops = data.calls["hops"]
    hopped = hops[(hops["from"] == activity.BYTEQUAY) & (hops["to"] == activity.BYTEQUAY) & (hops["gap"] == activity.GAPS[1])]
    if hopped.empty:
        return []
    each = model_after(AFTER_TOOLS)(data)
    seen = (f"The model made {int(hopped['times'].sum()):,} ByteQuay tool calls within {activity.SHORT_HOP} seconds of the last one's result: "
            f"{hopped['seconds'].sum() / 60:,.1f} minutes." + (f" After a ByteQuay tool result it took {each:.0f} seconds on average before going on." if each else ""))
    advice = ("Calls made right after one another often fetch the next piece of the same thing. A call that returns both, or a result that says what to "
              "fetch next, saves the hop. Only the short hops are counted as what this could save.")
    return [Suggestion("Let one ByteQuay tool call return what the next one fetches", weekly(float(hopped["seconds"].sum()), data.days), seen, advice,
                       ((REVIEW_TOOLS, "ReviewTools"), (PERMISSIONS, "CALL_TOOL")), "model seconds after a ByteQuay tool call", model_after(AFTER_TOOLS))]


def long_minutes(name):
    def measure(window):
        runs = window.logged()["runs"]
        return per_hour(float(runs[runs["program"] == name]["seconds"].sum()) / 60, window)
    return measure


def long_runs(data):
    """Programs whose single runs of LONG_RUN seconds or more add up to a lot of time."""
    runs = data.logged()["runs"]
    made = []
    for name, group in runs.groupby("program"):
        beyond = float((group["seconds"] - activity.LONG_RUN).clip(lower=0).sum())
        if weekly(beyond, data.days) < LONG_WEEKLY:
            continue
        seen = (f"{activity.count(len(group), 'single ' + name + ' run')} took {activity.LONG_RUN} seconds or more: {group['seconds'].sum() / 60:,.0f} minutes, "
                f"{group['seconds'].median():.0f} seconds typically and {group['seconds'].max() / 60:.0f} minutes at the longest.")
        advice = LONG_ADVICE.get("build" if name in activity.BUILD_PROGRAMS else name, LONG_ADVICE[None])
        made.append(Suggestion(f"Make long {name} runs shorter", weekly(beyond, data.days), seen, advice + f" Time above {activity.LONG_RUN} seconds a run is counted as what this could save.",
                               (RECIPE, (INSTRUCTIONS, "compose")), f"long {name} minutes in an agent hour", long_minutes(name)))
    return made


def asked_again_share(window):
    runs = None if window.reviews is None else window.reviews["runs"]
    return None if runs is None or runs.empty else charts.share(int(runs["repaired"].sum()), len(runs))


def asked_again(data):
    """Review runs that ended without a complete report, so ByteQuay asked the reviewer once more."""
    if data.reviews is None:
        return []
    runs = data.reviews["runs"]
    repaired, others = runs[runs["repaired"]], runs[~runs["repaired"]]
    if len(repaired) < MIN_FAILED or len(repaired) < ASKED_AGAIN * len(runs) or repaired["seconds"].isna().all() or others["seconds"].isna().all():
        return []
    slower, usual = float(repaired["seconds"].median()), float(others["seconds"].median())
    seen = (f"{len(repaired)} of {len(runs)} review runs ended without a complete report, so ByteQuay asked again. Those runs took {slower / 60:.0f} minutes typically, "
            f"the others {usual / 60:.0f}. Part of the gap is the extra ask, and part may be larger pull requests.")
    advice = ("Find which part of the report was missing most often and ask for it plainly in the review rules, or let the reviewer check its report before "
              "it ends. The extra typical minutes of each run asked again are counted as what this could save.")
    return [Suggestion("Get a complete review report the first time", weekly(max(0.0, slower - usual) * len(repaired), data.days), seen, advice,
                       ((REVIEW_SERVICE, "repairAssignment"), (REVIEW_TOOLS, "ReviewTools")), "review runs asked again (%)", asked_again_share)]


RULES = (allow_rules, ran_out, failing, turn_start, after_tools, long_runs, asked_again)


def suggestions(data):
    """Every suggestion these days support, the most time it could save first. One that could save less than MIN_WEEKLY is left out."""
    made = [one for rule in RULES for one in rule(data)]
    return sorted((one for one in made if one.saves >= MIN_WEEKLY), key=lambda one: (-one.saves, one.title))


def checked(suggestion, made, calls, reviews, asks, day, last, zone=None):
    """The number to watch in the days before a change was noted and in the days since, as (before, since, turns since)."""
    before = window(made, calls, reviews, asks, day - timedelta(days=BEFORE), day - timedelta(days=1), zone)
    since = window(made, calls, reviews, asks, day, last, zone)
    return suggestion.measure(before), suggestion.measure(since), len(since.made["turns"])


def number(amount):
    return "-" if amount is None else f"{amount:,.1f}"


def where_lines(suggestion):
    return [what if path is None else f"{path} ({what})" for path, what in suggestion.where]


def render(found, data, first, last):
    """The suggestions as text, the most time first."""
    head = f"Suggestions for ByteQuay from {said(first)} to {said(last)} {last.year}: {activity.count(len(data.made['turns']), 'turn')}, {data.hours():.1f} hours with a log"
    if not found:
        return head + "\n\nNothing in these days passes the limits for a suggestion."
    lines = [head]
    for place, one in enumerate(found, 1):
        lines += ["", f"{place}. {one.title}: up to {one.saves:,.1f} minutes a week", f"   Seen: {one.seen}", f"   Do: {one.advice}",
                  *(f"   Where: {where}" for where in where_lines(one)), f"   Watch: {one.watch}, now {number(one.measure(data))}"]
    return "\n".join(lines)


def read_asks(runtime, since=None):
    """Every answered ask in the task logs under `runtime`, or none when there is no log."""
    return [ask for log in sorted(runtime.glob("*/events.jsonl")) for ask in approvals.read_log(log, since)[0]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER, help="Folder with the saved results")
    parser.add_argument("--runtime", type=Path, default=activity.RUNTIME, help="Folder with the task logs, read for the approvals")
    parser.add_argument("--days", type=int, default=DAYS, help=f"How many days up to the last saved one to look at; default {DAYS}")
    args = parser.parse_args()
    if args.days < 1:
        parser.error("--days takes a number of days, 1 or more")
    try:
        table, review_table = saved.read(args.folder, "turns.json"), saved.read(args.folder, "reviews.json")
        if table is None:
            parser.exit(1, f"Nothing saved yet. Save the turn table with `{SAVE}`\n")
        made = charts.frames(table)
        try:
            calls = charts.call_frames(table)
        except (KeyError, TypeError, ValueError):
            calls = None
        reviews = None if review_table is None else charts.review_frames(review_table)
    except (OSError, KeyError, TypeError, ValueError):
        parser.exit(1, f"The saved tables could not be read. Save them again with `{SAVE}`\n")
    if made["turns"].empty:
        parser.exit(1, "The saved turn table holds no turn.\n")
    last = made["turns"]["day"].max()
    first = last - timedelta(days=args.days - 1)
    asks = read_asks(args.runtime, activity.midnight(first.isoformat()) - 86400)
    data = window(made, calls, reviews, asks, first, last)
    print(render(suggestions(data), data, first, last))
    if calls is None:
        print(f"\nThe turn table was saved before the lab counted calls, so command failures and tool hops are left out. Save again with `{SAVE}`")


if __name__ == "__main__":
    main()
