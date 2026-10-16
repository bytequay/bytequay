"""Hand labels that check the review judge. A person answers what the judge answered, FOUND, PARTLY or MISSED, for one
reviewer comment and one run's leads, seeing exactly what the judge was asked and neither its answer nor the setting
that ran. The labels are kept in the experiment's `labels.json` in the order they were given, and the scores are worth
as much as the person and the judge agree."""

from __future__ import annotations

from dataclasses import dataclass
import json
import random

import judge
import saved

FILE = "labels.json"
ENOUGH = 30  # labels for a first number on how far to trust the judge
SEED = 7  # the order the pairs come in: the same on every visit, with settings and pull requests mixed


@dataclass(frozen=True)
class Pair:
    key: str  # setting/run/comment id
    shown: str  # what the judge was asked, word for word
    verdict: str  # what the judge answered, shown only once the pair is labelled


def pairs(experiment):
    """Every comment the judge answered in the experiment's runs, in a fixed mixed order. A run without leads asked the
    judge nothing, so its comments are left out."""
    found = []
    for calls in sorted(experiment.glob("*/*/judge-calls.json")):
        run = calls.parent
        asked = {}
        for call in json.loads(calls.read_text())["calls"]:
            asked.setdefault(str(call["commentId"]), call["user"])  # a comment asked again was asked the same
        verdicts = json.loads((run / "judgement.json").read_text())["verdicts"]
        found += [Pair(f"{run.parent.name}/{run.name}/{one['commentId']}", asked[str(one["commentId"])], one["verdict"])
                  for one in verdicts if str(one["commentId"]) in asked]
    random.Random(SEED).shuffle(found)
    return found


def read(experiment):
    """The labels given so far, oldest first, as {pair key: label}. A label that is not a verdict fails, so a file changed
    by hand is never read wrong."""
    made = saved.read(experiment, FILE)
    given = {} if made is None else {str(one["pair"]): one["label"] for one in made["labels"]}
    for key, label in given.items():
        if label not in judge.VERDICTS:
            raise ValueError(f"the label of {key} is {label!r}, not one of {', '.join(judge.VERDICTS)}")
    return given


def give(experiment, key, label):
    """Keep a label as the newest, or take the label of `key` back with None."""
    kept = {name: given for name, given in read(experiment).items() if name != key} | ({} if label is None else {key: label})
    saved.write(experiment, FILE, {"version": 1, "labels": [{"pair": name, "label": given} for name, given in kept.items()]})


def agreement(found, given):
    """The labelled pairs: how many the judge answered as the person did, and for each label how the judge answered."""
    both = [(pair.verdict, given[pair.key]) for pair in found if pair.key in given]
    agree = sum(verdict == label for verdict, label in both)
    return {"compared": len(both), "agree": agree, "rate": agree / len(both) if both else None,
            "table": {label: {verdict: sum(pair == (verdict, label) for pair in both) for verdict in judge.VERDICTS} for label in judge.VERDICTS}}
