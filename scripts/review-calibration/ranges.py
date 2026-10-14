"""How far a lab number could move by chance: a 95% range made by drawing the tasks again, with replacement, many times.
Whole tasks are drawn and not single turns, because the turns of one task share its code, its prompt and its mistakes,
so they are not separate tries. A range from too few tasks says nothing and is not given."""

from __future__ import annotations

from dataclasses import dataclass
import math

import numpy

DRAWS = 2000
SEED = 7  # the same table always gives the same range
LEVEL = 95  # percent of the draws a range holds
MIN_TASKS = 3  # with fewer tasks there is no range
CHUNK = 200  # draws of a median made at once, so a long list of turns never needs much memory at a time


@dataclass(frozen=True)
class Ratio:
    """A number made of two sums, such as failed runs over runs: for each task its part and its whole."""

    parts: tuple
    wholes: tuple
    scale: float = 1.0  # 100 for a share in percent

    def value(self):
        whole = sum(self.wholes)
        return sum(self.parts) / whole * self.scale if whole else None

    def draws(self, seed):
        if sum(whole > 0 for whole in self.wholes) < MIN_TASKS:
            return None
        parts, wholes = numpy.asarray(self.parts, dtype=float), numpy.asarray(self.wholes, dtype=float)
        picks = numpy.random.default_rng(seed).integers(0, len(parts), size=(DRAWS, len(parts)))
        top, bottom = parts[picks].sum(axis=1), wholes[picks].sum(axis=1)
        return numpy.divide(top, bottom, out=numpy.full(DRAWS, numpy.nan), where=bottom > 0) * self.scale


@dataclass(frozen=True)
class Median:
    """The middle of values that come by task, such as the length of every turn: for each task the tuple of its values."""

    groups: tuple

    def value(self):
        values = [one for group in self.groups for one in group]
        return float(numpy.median(values)) if values else None

    def draws(self, seed):
        if len(self.groups) < MIN_TASKS:
            return None
        padded = numpy.full((len(self.groups), max(len(group) for group in self.groups)), numpy.nan)
        for place, group in enumerate(self.groups):
            padded[place, :len(group)] = group
        generator, made = numpy.random.default_rng(seed), []
        for start in range(0, DRAWS, CHUNK):
            picks = generator.integers(0, len(self.groups), size=(min(CHUNK, DRAWS - start), len(self.groups)))
            made.append(numpy.nanmedian(padded[picks].reshape(len(picks), -1), axis=1))
        return numpy.concatenate(made)


def ratio(parts, wholes, scale=1.0):
    """A Ratio from two pandas Series indexed by task. A task missing from one of them counts zero there."""
    tasks = sorted(set(parts.index) | set(wholes.index))
    return Ratio(tuple(float(parts.get(task, 0.0)) for task in tasks), tuple(float(wholes.get(task, 0.0)) for task in tasks), scale)


def median(values, tasks):
    """A Median from values and the task of each. A value that is not known is left out."""
    groups = {}
    for value, task in zip(values, tasks):
        if value is not None and not math.isnan(float(value)):
            groups.setdefault(task, []).append(float(value))
    return Median(tuple(tuple(groups[task]) for task in sorted(groups)))


def span(draws):
    """The middle LEVEL percent of some draws, as (low, high); None when there are none."""
    if draws is None:
        return None
    draws = draws[~numpy.isnan(draws)]
    if not len(draws):
        return None
    low, high = numpy.percentile(draws, [(100 - LEVEL) / 2, (100 + LEVEL) / 2])
    return float(low), float(high)


def range_of(measure):
    """Where the number could lie, or None when its tasks are too few."""
    return None if measure is None else span(measure.draws(SEED))


def drawn_apart(before, since):
    """The draws of both sides, the tasks of each drawn on their own: they are other tasks, so they are never paired. None when either has too few."""
    if before is None or since is None:
        return None
    first, second = before.draws(SEED), since.draws(SEED + 1)
    return None if first is None or second is None else (first, second)


def change(before, since):
    """Where the change from `before` to `since` could lie, in the unit of the number; None when either side has too few tasks."""
    drawn = drawn_apart(before, since)
    return None if drawn is None else span(drawn[1] - drawn[0])


def relative(before, since):
    """Where the change could lie in percent of `before`. Drawn as a ratio of the two sides, so it never falls below -100%."""
    drawn = drawn_apart(before, since)
    if drawn is None:
        return None
    first, second = drawn
    return span(numpy.divide(second, first, out=numpy.full(len(first), numpy.nan), where=first != 0) * 100 - 100)


def clear(moved):
    """Whether a change is clear: its whole range lies on one side of zero."""
    return moved is not None and (moved[0] > 0 or moved[1] < 0)


def said(found, digits=1):
    """A range as words, such as "4.1 to 6.9"; None when there is none."""
    return None if found is None else f"{found[0]:,.{digits}f} to {found[1]:,.{digits}f}"


def change_in_words(moved, digits=1):
    """What a change amounts to, as a reader would say it."""
    if moved is None:
        return "Too few tasks on one side to tell whether it changed."
    if moved[1] < 0:
        return f"It went down, clearly: by {-moved[1]:,.{digits}f} to {-moved[0]:,.{digits}f}."
    if moved[0] > 0:
        return f"It went up, clearly: by {moved[0]:,.{digits}f} to {moved[1]:,.{digits}f}."
    return f"Not clear yet: the change lies somewhere from {moved[0]:+,.{digits}f} to {moved[1]:+,.{digits}f}."
