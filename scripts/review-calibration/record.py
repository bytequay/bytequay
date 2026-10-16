"""What became of the lab's suggestions. In the dashboard you mark a suggestion as being worked on, or as not wanted with a
reason; "I made this change" notes it in `changes.json` as before. A change that was made is checked by the suggestion's
own number: the seven days before the change against the days since, comparing like with like, with the suggestion as it
stood in the days before it. The risks the lab's agent finds on the Resources page share the record: a risk that was made
is checked by whether the looks since still find it. The marks are kept in `record.json` beside the saved tables, the
newest one for each suggestion."""

from __future__ import annotations

from collections import Counter
from datetime import date, datetime, timedelta

import charts
import explain
import ranges
import saved
import suggest

FILE = "record.json"
MARKS = ("doing", "dropped")


def marks(folder):
    """The mark of each suggestion, by its title, as (state, day, reason). A mark this version does not know fails, so a
    file changed by hand is never read wrong."""
    made = saved.read(folder, FILE)
    found = {}
    for one in [] if made is None else made["suggestions"]:
        if one["state"] not in MARKS:
            raise ValueError(f"a suggestion is marked {one['state']!r}")
        found[str(one["title"])] = (one["state"], date.fromisoformat(one["day"]), str(one["reason"]))
    return found


def mark(folder, title, state, day, reason=""):
    """Mark a suggestion as being worked on ("doing") or not wanted ("dropped"), or take its mark away with None."""
    kept = {name: made for name, made in marks(folder).items() if name != title}
    if state is not None:
        kept[title] = (state, day, " ".join(reason.split()))
    saved.write(folder, FILE, {"version": 1, "suggestions": [{"title": name, "state": state, "day": day.isoformat(), "reason": reason}
                                                             for name, (state, day, reason) in sorted(kept.items())]})


def standing(title, marked, noted):
    """Where a suggestion stands, as (state, day, reason): not wanted wins, then made (a change noted under its title, the
    latest day), then being worked on, and otherwise open."""
    state, day, reason = marked.get(title, (None, None, ""))
    made = [one["day"] for one in noted if one["what"] == title]
    if state == "dropped":
        return "dropped", day, reason
    if made:
        return "done", max(made), ""
    return ("doing", day, "") if state == "doing" else ("open", None, "")


def by_setting(window):
    """The window cut by model and effort, each part with its turns. A task's approval asks go with the model and effort most
    of its turns ran with, and a review run with its own."""
    turns = window.made["turns"]
    named = turns["setting"].fillna(explain.UNKNOWN)
    main = {task: Counter(group).most_common(1)[0][0] for task, group in named.groupby(turns["task"])}
    runs = None if window.reviews is None else window.reviews["runs"]["setting"].fillna(explain.UNKNOWN)
    made = {}
    for setting in sorted(set(named)):
        part = charts.only(window.made, named == setting)
        reviews = None if runs is None else {"runs": window.reviews["runs"][runs == setting],
                                              "findings": window.reviews["findings"][window.reviews["findings"]["run"].isin(runs[runs == setting].index)]}
        made[setting] = (len(part["turns"]), suggest.Window(part, None if window.calls is None else charts.calls_of(window.calls, part["turns"]), reviews,
                                                            tuple(ask for ask in window.asks if main.get(ask.task) == setting), window.days))
    return made


def then(title, made, calls, reviews, asks, day, zone=None):
    """The suggestion of this title as it stood in the days before `day`, or None when those days no longer give it."""
    data = suggest.window(made, calls, reviews, asks, day - timedelta(days=suggest.DAYS), day - timedelta(days=1), zone)
    return next((one for one in suggest.suggestions(data) if one.title == title), None)


def check(suggestion, made, calls, reviews, asks, day, last, zone=None):
    """The suggestion's number in the seven days before `day` and in the days since, as (before, since, turns since, where
    the change could lie). The change compares like with like and is None when no model and effort has three tasks or more
    on both sides."""
    before = suggest.window(made, calls, reviews, asks, day - timedelta(days=suggest.BEFORE), day - timedelta(days=1), zone)
    since = suggest.window(made, calls, reviews, asks, day, last, zone)
    pair = ranges.alike(*({setting: (turns, suggestion.measure(part)) for setting, (turns, part) in by_setting(window).items()} for window in (before, since)))
    return suggest.value(suggestion.measure(before)), suggest.value(suggestion.measure(since)), len(since.made["turns"]), None if pair is None else ranges.change(*pair)


def verdict(moved):
    """What a change did to a suggestion's number. Every number a suggestion watches is better lower."""
    if moved is None:
        return "Too few tasks of one model and effort on both sides to tell yet."
    if moved[1] < 0:
        return f"It helped: down by {-moved[1]:,.1f} to {-moved[0]:,.1f}, within the same models and efforts."
    if moved[0] > 0:
        return f"It made it worse: up by {moved[0]:,.1f} to {moved[1]:,.1f}, within the same models and efforts."
    return f"No clear change yet: somewhere from {moved[0]:+,.1f} to {moved[1]:+,.1f}, within the same models and efforts."


def gone(title, day, looks, zone=None):
    """What a fix did to a risk the agent found: whether the newest look after the day of the change still finds it."""
    later = [look for look in looks if datetime.fromisoformat(look["ended"]).astimezone(zone).date() > day]
    if not later:
        return "no look since yet"
    return "still found" if any(one["title"] == title for one in later[-1]["suggestions"]) else "gone"


def table(marked, noted, listed, made, calls, reviews, asks, last, zone=None, looks=()):
    """One row for every suggestion that was marked or made: where it stands, the minutes a week it could save when it was
    made and now, and for one that was made, its number in the days before and since and what the change did. A risk from
    the agent's looks has no minutes; once made, it is checked by the looks since. A noted change that is not a
    suggestion's title is left out."""
    now = {one.title: one.saves for one in listed}
    risks = {one["title"]: one for look in looks for one in look["suggestions"]}  # the newest look's words for each
    rows = []
    for title in sorted(set(marked) | {one["what"] for one in noted}):
        state, day, reason = standing(title, marked, noted)
        risk = title in risks and title not in now
        row = {"suggestion": title, "from": "risk look" if risk else "lab rules",
               "stands": {"dropped": f"not wanted: {reason}" if reason else "not wanted", "done": "made", "doing": "being worked on"}[state],
               "since": day, "could save then": None, "could save now": now.get(title), "number to watch": None, "before": None, "after": None, "what it did": None}
        if risk:
            row |= {"number to watch": risks[title]["watch"], "what it did": gone(title, day, looks, zone) if state == "done" else None}
        elif state == "done":
            found = then(title, made, calls, reviews, asks, day, zone)
            if found is None and title not in now:
                continue  # a change noted in words of its own, or one the saved tables no longer show
            if found is not None:
                before, since, _, moved = check(found, made, calls, reviews, asks, day, last, zone)
                row |= {"could save then": found.saves, "number to watch": found.watch, "before": before, "after": since,
                        "what it did": "too few tasks" if moved is None else "helped" if moved[1] < 0 else "worse" if moved[0] > 0 else "no clear change yet"}
        rows.append(row)
    return rows
