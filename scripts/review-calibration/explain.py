"""Why the totals of the lab report moved. A total such as agent time or cost is what every model and effort gave, added
up, so the report names the ones that moved it most with their turns, and says when the turns ran with other models and
efforts than in the days before."""

from __future__ import annotations

import pandas

import charts

UNKNOWN = "no model known"  # the turns the database does not know
TOTALS = ("Agent time", "Turns", "Long single commands", "Time in long commands", "Questions to you", "Rate limit notices", "Tokens in", "Tokens out", "Recorded cost")
BIG = 25  # percent a total must move by before the report says why
FEW = 10  # a count that stays below this on both sides moves by chance, so it gets no reason
COUNTS = ("Long single commands", "Questions to you", "Rate limit notices")
SHIFT = 10  # points of the turns a model and effort must gain or lose before it is named
NAMED = 2  # models and efforts named for a total; one that gave less than a tenth of the move is left out
# The totals that some turns leave out, with the column that says whether a turn has it and how the report names it.
KNOWN = {"Tokens in": ("inputTokens", "tokens", "them"), "Tokens out": ("inputTokens", "tokens", "them"), "Recorded cost": ("cost", "a cost", "one")}


def settings(shown):
    """The turns of each model and effort as frames, the turns the database does not know under UNKNOWN."""
    named = shown["turns"]["setting"].fillna(UNKNOWN)
    return {setting: charts.only(shown, named == setting) for setting in sorted(set(named))}


def signed(written, amount):
    return ("-" if amount < 0 else "+") + written(abs(amount))


def shifted(before, now):
    """The models and efforts whose share of the turns moved by SHIFT points or more, the most first, as a sentence; None
    when none did. `before` and `now` map a model and effort to its turns."""
    def shares(counts):
        whole = sum(counts.values())
        return {setting: charts.share(amount, whole) for setting, amount in counts.items()}

    was, is_ = shares(before), shares(now)
    gaps = sorted(((is_.get(setting, 0.0) - was.get(setting, 0.0), setting) for setting in set(was) | set(is_)), key=lambda pair: (-abs(pair[0]), pair[1]))
    named = [f"{setting} {is_.get(setting, 0.0):.0f}% of the turns ({was.get(setting, 0.0):.0f}% before)" for gap, setting in gaps[:3] if abs(gap) >= SHIFT]
    return "The turns ran with other models and efforts than in the days before: " + ", ".join(named) + "." if named else None


def total(name, before_by, now_by, before, now, written):
    """A total that moved by BIG percent or more, with the models and efforts that moved it most; None for one that moved
    less or is not known on a side. `before_by` and `now_by` map a model and effort to its own numbers."""
    was, is_ = before.get(name), now.get(name)
    if was is None or is_ is None or not was or abs(is_ - was) < was * BIG / 100 or (name in COUNTS and max(was, is_) < FEW):
        return None

    def amount(by, setting, what=name):
        return (by[setting].get(what) or 0) if setting in by else 0

    gave = {setting: amount(now_by, setting) - amount(before_by, setting) for setting in set(before_by) | set(now_by)}
    top = [setting for setting in sorted(gave, key=lambda one: (-abs(gave[one]), one)) if abs(gave[setting]) >= abs(is_ - was) / 10][:NAMED]
    named = [f"{setting} {signed(written, gave[setting])}" + ("" if name == "Turns" else f" ({amount(before_by, setting, 'Turns'):,} → {amount(now_by, setting, 'Turns'):,} turns)")
             for setting in top]
    return f"{name} {written(was)} → {written(is_)}: " + ", ".join(named) + "."


def coverage(name, shown_before, shown):
    """For a total that some turns leave out, how many turns on each side have it and which providers never record it, as a
    sentence; empty when every turn has it."""
    column, what, it = KNOWN[name]
    (had, of), (has, out_of) = ((int(made["turns"][column].notna().sum()), len(made["turns"])) for made in (shown_before, shown))
    if had == of and has == out_of:
        return ""
    both = pandas.concat([shown_before["turns"], shown["turns"]])
    silent = sorted(provider for provider, group in both.groupby("provider") if group[column].isna().all())
    return (f" {what[0].upper()}{what[1:]} {'are' if what == 'tokens' else 'is'} recorded for {had:,} of {of:,} turns before and {has:,} of {out_of:,} these days"
            + (f"; no {' or '.join(silent)} turn records {it}" if silent else "") + ".")


def why(shown_before, shown, before, now, facts, written):
    """The lines of "why it moved". `facts` makes the numbers of some turns as the report lists them, and `written` maps
    each number's name to how it is written."""
    if min(len(shown_before["turns"]), len(shown["turns"])) < charts.MIN_TURNS:
        return [f"Fewer than {charts.MIN_TURNS} turns on a side, too few to say why the totals moved."]
    before_by, now_by = ({setting: facts(part, None) for setting, part in settings(made).items()} for made in (shown_before, shown))
    lines = []
    moved = shifted({setting: made["Turns"] for setting, made in before_by.items()}, {setting: made["Turns"] for setting, made in now_by.items()})
    if moved:
        lines += [moved, ""]
    for name in TOTALS:
        line = total(name, before_by, now_by, before, now, written[name])
        if line:
            lines.append("- " + line + (coverage(name, shown_before, shown) if name in KNOWN else ""))
    return lines if lines and lines[-1].startswith("- ") else [*lines, f"No total moved by {BIG}% or more."]
