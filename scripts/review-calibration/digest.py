#!/usr/bin/env python3
"""The lab's daily or weekly report, made from the saved tables: what changed against the days before and why, where the
turn time went, and what moved after a change the reader noted. Start it with `uv run digest.py`."""

from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta
from pathlib import Path

import activity
import charts
import explain
import ranges
import saved

SAVE = "uv run activity.py --save"
DAYS = {"day": 1, "week": 7}
BEFORE = 7  # days before a noted change that the days since it are compared with
NOTED = 28  # days a noted change stays in the reports
TOP = 8  # rows of "where the time went"; what lies below them is added up
START, TOOLS = "at the start of a turn", "after ByteQuay tools"
WHOLE, HOURS, MINUTES, SECONDS, SHARE, DOLLARS = "{:,.0f}".format, "{:.1f} h".format, "{:.1f} min".format, "{:.0f} s".format, "{:.0f}%".format, "${:,.2f}".format
# The numbers of "what changed", in the order they are listed, each with how it is written.
LISTED = {
    "Agent time": HOURS, "Turns": WHOLE, "Tasks": WHOLE, "Typical turn": MINUTES, "Turns with a log": WHOLE, **dict.fromkeys(charts.STATES, SHARE),
    "Long single commands": WHOLE, "Time in long commands": MINUTES, "Questions to you": WHOLE, "Questions that ran out": WHOLE, "Rate limit notices": WHOLE, "Retries": WHOLE,
    "Tokens in": charts.short, "From the cache": SHARE, "Tokens out": charts.short, "Recorded cost": DOLLARS,
    "Review runs": WHOLE, "Runs asked again": WHOLE, "Findings": WHOLE, "Resolved": WHOLE, "Withdrawn": WHOLE, "Still open": WHOLE, "Held up": SHARE,
}
# More numbers a noted change is judged by. Like the typical turn and the three shares, none of them grows just because more work was done.
PER_UNIT = {
    "Model time after a ByteQuay tool call": SECONDS, "Model time at the start of a turn": SECONDS, "Long command minutes in an agent hour": MINUTES,
    "Questions to you in an agent hour": "{:.1f}".format, "Typical tokens in a turn": charts.short, "Share of review runs asked again": SHARE,
}
WRITTEN = LISTED | PER_UNIT
RATES = ("Typical turn", *charts.STATES, *PER_UNIT)


def facts(shown, reviewed):
    """Every number the report can list, for the turns and the review runs of some days. None is "not known" and never zero.
    How much was done counts every turn. How the time was spent is known only for the turns that still have a log."""
    turns = shown["turns"]
    logged = charts.only(shown, turns["logged"])
    timed, after, runs, waits = logged["turns"], logged["after"], logged["runs"], logged["waits"]
    seconds = float(timed["seconds"].sum())
    states, noticed = charts.state_seconds(logged["parts"]), logged["notices"].groupby("what")["times"].sum()
    tokens, cost = charts.tokens(turns), turns["cost"].dropna()
    read = None if tokens.empty else float(tokens["fresh input"].sum() + tokens["cached input"].sum())

    def each(before):
        rows = after[after["before"] == before]
        return float(rows["seconds"].sum() / rows["times"].sum()) if rows["times"].sum() else None

    def an_hour(amount):
        return amount / (seconds / 3600) if seconds else None

    made = {
        "Agent time": float(turns["seconds"].sum()) / 3600, "Turns": len(turns), "Tasks": int(turns["task"].nunique()),
        "Typical turn": float(turns["seconds"].median()) / 60 if len(turns) else None, "Turns with a log": len(timed),
        **{state: charts.share(float(states[state]), seconds) if seconds else None for state in charts.STATES},
        "Long single commands": len(runs), "Time in long commands": float(runs["seconds"].sum()) / 60,
        "Questions to you": len(waits), "Questions that ran out": int((waits["outcome"] == "expired").sum()),
        "Rate limit notices": int(sum(times for what, times in noticed.items() if what.startswith("rate limit"))), "Retries": int(noticed.get("retry", 0)),
        "Tokens in": read, "From the cache": charts.share(float(tokens["cached input"].sum()), read) if read else None,
        "Tokens out": None if tokens.empty else float(tokens["output"].sum()), "Recorded cost": None if cost.empty else float(cost.sum()),
        "Model time after a ByteQuay tool call": each(TOOLS), "Model time at the start of a turn": each(START),
        "Long command minutes in an agent hour": an_hour(float(runs["seconds"].sum()) / 60), "Questions to you in an agent hour": an_hour(len(waits)),
        "Typical tokens in a turn": None if tokens.empty else float((tokens["fresh input"] + tokens["cached input"]).median()),
    }
    if reviewed is not None:
        ran, found = reviewed["runs"], reviewed["findings"]
        again = int(ran["repaired"].sum())
        made |= {"Review runs": len(ran), "Runs asked again": again, "Share of review runs asked again": charts.share(again, len(ran)) if len(ran) else None, "Findings": len(found),
                 **{name: int((found["state"] == state).sum()) for name, state in (("Resolved", "resolved"), ("Withdrawn", "withdrawn"), ("Still open", "open"))},
                 "Held up": charts.held_up(found)}
    return made


def measures(shown, reviewed):
    """The numbers a noted change is judged by, each made by task so that it has a range: the same numbers `facts` gives, as
    ranges.Ratio or ranges.Median. Review runs are drawn by review, since the runs of one pull request are alike."""
    turns = shown["turns"]
    logged = charts.only(shown, turns["logged"])

    def by_task(frame, amount):
        return amount.groupby(frame["turn"].map(turns["task"])).sum()

    hours = logged["turns"].groupby("task")["seconds"].sum() / 3600
    after, parts = logged["after"], logged["parts"]

    def model(before):
        rows = after[after["before"] == before]
        return ranges.ratio(by_task(rows, rows["seconds"]), by_task(rows, rows["times"]))

    tokens = charts.tokens(turns)
    made = {"Typical turn": ranges.median(turns["seconds"] / 60, turns["task"]),
            **{state: ranges.ratio(by_task(parts, parts["seconds"].where(parts["state"] == state, 0.0)), hours * 3600, 100) for state in charts.STATES},
            "Model time after a ByteQuay tool call": model(TOOLS), "Model time at the start of a turn": model(START),
            "Long command minutes in an agent hour": ranges.ratio(by_task(logged["runs"], logged["runs"]["seconds"] / 60), hours),
            "Questions to you in an agent hour": ranges.ratio(logged["waits"].groupby(logged["waits"]["turn"].map(turns["task"])).size(), hours),
            "Typical tokens in a turn": ranges.median(tokens["fresh input"] + tokens["cached input"], turns.loc[tokens.index, "task"]),
            "Share of review runs asked again": None}
    if reviewed is not None:
        runs = reviewed["runs"]
        made["Share of review runs asked again"] = ranges.ratio(runs.groupby("review")["repaired"].sum(), runs.groupby("review").size(), 100)
    return made


def clear(name, before, since):
    """Whether a noted change moved this number beyond chance, with where the change could lie, in the unit of the Change column:
    points for a share, and percent of the days before for any other number."""
    share = WRITTEN[name] is SHARE
    moved = (ranges.change if share else ranges.relative)(before[name], since[name])
    if moved is None:
        return "too few tasks"
    verdict = "yes" if ranges.clear(moved) else "not yet"
    return f"{verdict}: {moved[0]:+.0f} to {moved[1]:+.0f} points" if share else f"{verdict}: {moved[0]:+.0f}% to {moved[1]:+.0f}%"


def moved(before, now, share):
    """How a number moved from the earlier days to the later ones, as the reader would say it. Nothing where either is not known."""
    if now is None or before is None:
        return ""
    if share:
        points = round(now) - round(before)
        return "same" if points == 0 else f"{points:+d} point{'' if abs(points) == 1 else 's'}"
    if before == 0:
        return "" if now == 0 else "new"
    grew = round(100 * (now - before) / before)
    return "same" if grew == 0 else f"{grew:+d}%"


def compared(names, before, now):
    """One table row for each number: the earlier days, the later days, and how it moved."""
    def written(name, amount):
        return "-" if amount is None else WRITTEN[name](amount)
    return [(name, written(name, before.get(name)), written(name, now.get(name)), moved(before.get(name), now.get(name), WRITTEN[name] is SHARE)) for name in names]


def spent(shown):
    """Where the turn time of these days went, the most first, as (who, what, seconds, times): the model's time by what had
    just finished, each kind of waiting for a tool, and waiting for the reader. Times are known for the model and the reader."""
    logged = charts.only(shown, shown["turns"]["logged"])
    rows = [("Model", before, seconds, times) for before, seconds, times in charts.model_rows(logged["after"])]
    for state, who in zip(charts.STATES[1:], ("Tool", "You")):
        rows += [(who, part, seconds, len(logged["waits"]) if who == "You" else None) for part, seconds, _ in charts.part_rows(logged["parts"], state)]
    return sorted(rows, key=lambda row: (-row[2], row[0], row[1]))


def length(seconds):
    return HOURS(seconds / 3600) if seconds >= 3600 else MINUTES(seconds / 60)


def logged_seconds(shown):
    return float(shown["turns"][shown["turns"]["logged"]]["seconds"].sum())


def said(day):
    return f"{day.day} {day:%b}"


def table(head, rows):
    """A Markdown table: a first column of words, every other one of numbers."""
    return ["| " + " | ".join(head) + " |", "|---|" + "---:|" * (len(head) - 1), *("| " + " | ".join(row) + " |" for row in rows)]


def time_went(shown, shown_before):
    """The lines of "where the time went": the largest parts of the turn time, each with its share now and before."""
    whole, whole_before = logged_seconds(shown), logged_seconds(shown_before)
    rows, earlier = spent(shown), {(who, what): seconds for who, what, seconds, _ in spent(shown_before)}
    if not rows:
        return ["No turn of these days has a log."]
    drawn = [(f"{who}, {what}", length(seconds), SHARE(charts.share(seconds, whole)), SHARE(charts.share(earlier[who, what], whole_before)) if (who, what) in earlier else "-",
              "" if times is None else WHOLE(times), SECONDS(seconds / times) if times else "") for who, what, seconds, times in rows[:TOP]]
    if len(rows) > TOP:
        rest = sum(seconds for _, _, seconds, _ in rows[TOP:])
        drawn.append(("Everything else", length(rest), SHARE(charts.share(rest, whole)), "", "", ""))
    lines = table(("", "Time", "Share", "Share before", "Times", "Each"), drawn)
    # program_rows adds up what lies below the top; only the named programs are wanted here.
    programs = charts.program_rows(charts.only(shown, shown["turns"]["logged"])["programs"], top=3)[:3]
    if programs:
        lines += ["", "Shell time by program: " + "; ".join(f"{name} {seconds / 60:.0f} min in {runs:,} runs" for name, seconds, runs in programs) + "."]
    return lines


def models(turns, turns_before):
    """The lines of "models and effort": the settings with the most turns in these days."""
    rows, earlier = charts.settings_table(turns), {row["model and effort"]: row["turns"] for row in charts.settings_table(turns_before)}
    if not rows:
        return ["ByteQuay's records hold no turn for these days."]
    return [*table(("", "Turns", "Turns before", "Typical turn", "Completed", "Typical tokens in"),
                   [(row["model and effort"], WHOLE(row["turns"]), WHOLE(earlier.get(row["model and effort"], 0)), MINUTES(row["typical minutes"]), SHARE(row["completed %"]),
                     "-" if row["typical tokens in"] is None else charts.short(row["typical tokens in"])) for row in rows[:5]]),
            "", "This counts every turn ByteQuay recorded, with or without a log."]


def report(turn_table, review_table, noted, kind, ending=None, zone=None):
    """The report as Markdown, and the last day it covers. Fails with KeyError, TypeError or ValueError when the turn table is
    in another shape. Reviews that are missing or in another shape are named as such and left out."""
    made = charts.frames(turn_table, zone)
    saved_at = datetime.fromisoformat(turn_table["savedAt"]).astimezone(zone)
    # By default the last day that had ended when the tables were saved: a day still running would be compared with whole ones.
    last = ending or saved_at.date() - timedelta(days=1)
    first, earlier = last - timedelta(days=DAYS[kind] - 1), last - timedelta(days=DAYS[kind])
    notes, reviews = [], None
    if last >= saved_at.date():
        notes.append(f"The tables were saved before {said(last)} had ended, so the last day is not whole. Save again with `{SAVE}`")
    if turn_table["since"]:
        notes.append(f"Only turns that started on or after {turn_table['since']} were saved.")
    if turn_table["task"]:
        notes.append(f"Only the task whose id starts with {turn_table['task']} was saved.")
    if "problem" in turn_table["database"]:
        notes.append(f"Model, effort, tokens and cost were not saved: {turn_table['database']['problem']}.")
    if review_table is None:
        notes.append(f"No reviews are saved yet. Save them with `{SAVE}`")
    else:
        try:
            reviews = charts.review_frames(review_table, zone)
            if review_table["savedAt"] != turn_table["savedAt"]:
                notes.append(f"The reviews were saved at another time than the turns. Save both again with `{SAVE}`")
        except (KeyError, TypeError, ValueError):
            notes.append(f"The saved reviews could not be read and are left out. Save them again with `{SAVE}`")

    def stretch(start, end):
        shown = charts.within(made, start, end)
        chosen = None if reviews is None else charts.reviews_chosen(reviews, start, end)
        return shown, facts(shown, chosen), chosen

    (shown, now, _), (shown_before, before, _) = stretch(first, last), stretch(earlier - timedelta(days=DAYS[kind] - 1), earlier)
    # ponytail: the days before are measured again from today's tables, so a log lost since then is missing from them.
    # Keep each report's numbers and read them back if this note shows up often.
    for when, known in (("In these days", now), ("Before", before)):
        if known["Turns with a log"] < known["Turns"]:
            notes.append(f"{when}, {known['Turns with a log']:,} of {known['Turns']:,} turns have a log. How the time was spent, long commands, questions and notices "
                         "are known for those turns only.")
    lines = [f"# ByteQuay lab report: {said(last) if first == last else f'{said(first)} to {said(last)}'} {last.year}", "",
             f"Against the {'day' if kind == 'day' else f'{DAYS[kind]} days'} before. From the tables saved {saved_at:%Y-%m-%d %H:%M}. Days are in your own time zone.",
             *(line for note in notes for line in ("", note)),
             "", "## What changed", "", *table(("", "Before", "These days", "Change"), compared(LISTED, before, now)),
             "", "## Why it moved", "", *explain.why(shown_before, shown, before, now, facts, WRITTEN),
             "", "## Where the time went", "", *time_went(shown, shown_before),
             "", "## Models and effort", "", *models(shown["turns"], shown_before["turns"]),
             "", "## Changes you noted", ""]
    recent = [one for one in noted if last - timedelta(days=NOTED) < one["day"] <= last]
    for one in recent:
        (was_shown, was, was_reviews), (since_shown, since, since_reviews) = stretch(one["day"] - timedelta(days=BEFORE), one["day"] - timedelta(days=1)), stretch(one["day"], last)
        head = ("", f"{BEFORE} days before ({activity.count(was['Turns'], 'turn')})",
                f"{activity.count((last - one['day']).days + 1, 'day')} since ({activity.count(since['Turns'], 'turn')})", "Change", "Clear?")
        measured = measures(was_shown, was_reviews), measures(since_shown, since_reviews)
        lines += [f"### {said(one['day'])}: {one['what']}", "", *table(head, [(*row, clear(row[0], *measured)) for row in compared(RATES, was, since)]), "",
                  f"Clear? says whether the change is beyond what other tasks could have given: whole tasks drawn again, {ranges.LEVEL}% of the draws. "
                  "With fewer than three tasks on a side it cannot tell.", ""]
    if not recent:
        lines.append(f"None in the last {NOTED} days. Note one with `uv run digest.py --note \"what you changed\"`")
    return "\n".join(lines).rstrip("\n") + "\n", last


def changes(folder):
    """The changes the reader noted, oldest first, each with its day and what was changed."""
    made = saved.read(folder, "changes.json")
    # Sorted here too: the file may be changed by hand.
    return [] if made is None else sorted(({"day": date.fromisoformat(one["day"]), "what": str(one["what"])} for one in made["changes"]), key=lambda one: (one["day"], one["what"]))


def note(folder, what, day):
    """Add a change to the noted ones, and return them all."""
    kept = sorted([*changes(folder), {"day": day, "what": " ".join(what.split())}], key=lambda one: (one["day"], one["what"]))
    saved.write(folder, "changes.json", {"version": 1, "changes": [{"day": one["day"].isoformat(), "what": one["what"]} for one in kept]})
    return kept


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER, help="Folder with the saved results")
    kind = parser.add_mutually_exclusive_group()
    kind.add_argument("--day", action="store_const", const="day", dest="kind", help="One day against the day before")
    kind.add_argument("--week", action="store_const", const="week", dest="kind", help="Seven days against the seven before; this is the default")
    parser.add_argument("--ending", type=date.fromisoformat, metavar="YYYY-MM-DD", help="Last day of the report; default: the last day that had ended when the tables were saved")
    parser.add_argument("--note", metavar="TEXT", help="Note a change you made, so the reports compare the days before it with the days since")
    parser.add_argument("--on", type=date.fromisoformat, metavar="YYYY-MM-DD", help="The day of the change noted with --note; default: today")
    args = parser.parse_args()
    if args.note is None and args.on:
        parser.error("--on names the day of a change, so it needs --note")
    if args.note is not None and not args.note.strip():
        parser.error("--note needs what you changed")
    try:
        if args.note is not None:
            print("\n".join(f"{one['day']}  {one['what']}" for one in note(args.folder, args.note, args.on or date.today())))
            return
        noted = changes(args.folder)
    except (OSError, KeyError, TypeError, ValueError):
        parser.exit(1, f"The noted changes in {args.folder / 'changes.json'} could not be read.\n")
    try:
        turn_table = saved.read(args.folder, "turns.json")
    except (OSError, ValueError):
        turn_table = {}  # in another shape: said below
    if turn_table is None:
        parser.exit(1, f"Nothing saved yet. Save the turn table with `{SAVE}`\n")
    try:
        review_table = saved.read(args.folder, "reviews.json")
    except (OSError, ValueError):
        review_table = {}  # in another shape: the report names it and leaves the reviews out
    try:
        text, last = report(turn_table, review_table, noted, args.kind or "week", args.ending)
    except (KeyError, TypeError, ValueError):
        parser.exit(1, f"The saved turn table could not be read. Save it again with `{SAVE}`\n")
    print(text)
    print("Saved to", saved.write_text(args.folder / "reports", f"{args.kind or 'week'}-{last}.md", text))


if __name__ == "__main__":
    main()
