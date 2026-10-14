#!/usr/bin/env python3
"""Print what each setting's review runs found, from the files the runs and the judge left behind."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path

import saved

VERDICTS = ("FOUND", "PARTLY", "MISSED")
REVIEWER = ("inputTokens", "cachedInputTokens", "outputTokens")
JUDGE = ("prompt_tokens", "completion_tokens")
COUNTS = ("comments", *VERDICTS, "errors", "leads", "extra")


def read(path):
    return json.loads(path.read_text()) if path.exists() else None


def row(run):
    """One run directory under <root>/<setting>/: what it cost and, when judged, what it found. A setting is the rules
    file, the model and the effort together; run.py records all three and what the run used."""
    runtime = read(run / "runtime.json")
    spent = runtime.get("usage") or {}  # a run that never ended recorded none
    made = {"setting": run.parent.name, "run": run.name, "status": runtime["status"], "judged": False,
            "rules": " ".join(filter(None, (runtime.get("policySha256", "")[:8], runtime.get("model"), runtime.get("effort")))),
            "minutes": runtime.get("elapsedSeconds", 0) / 60, **{count: spent.get(count, 0) for count in REVIEWER}, "cost": spent.get("cost")}
    judgement = read(run / "judgement.json")
    if runtime["status"] == "completed" and judgement:
        given = [verdict["verdict"] for verdict in judgement["verdicts"]]
        matched = {lead for verdict in judgement["verdicts"] for lead in verdict["leadIds"]}
        made.update(judged=True, case=judgement["case"], comments=len(given) + len(judgement["errors"]), errors=len(judgement["errors"]),
                    verdicts={str(verdict["commentId"]): verdict["verdict"].lower() for verdict in judgement["verdicts"]},
                    leads=judgement["leads"], extra=judgement["leads"] - len(matched), **{verdict: given.count(verdict) for verdict in VERDICTS},
                    **{count: judgement["usage"].get(count, 0) for count in JUDGE})
    return made


def collect(root):
    return [row(runtime.parent) for runtime in sorted(root.glob("*/*/runtime.json"))]


def dollars(rows):
    """The recorded cost of these runs added up, or None when none of them records one: Codex names no cost."""
    known = [scored["cost"] for scored in rows if scored["cost"] is not None]
    return sum(known) if known else None


def cells(name, case, rows):
    total = {count: sum(scored[count] for scored in rows) for count in (*COUNTS, *REVIEWER)}
    cost = dollars(rows)
    return (f"| {name} | {case} | " + " | ".join(str(total[count]) for count in COUNTS)
            + f" | {total['inputTokens']:,} ({total['cachedInputTokens']:,}) / {total['outputTokens']:,} | {sum(scored['minutes'] for scored in rows):.1f} | "
            + ("-" if cost is None else f"${cost:,.2f}") + " |")


def render(rows):
    lines = []
    for setting in sorted({made["setting"] for made in rows}):
        mine = [made for made in rows if made["setting"] == setting]
        scored = [made for made in mine if made["judged"]]
        rules = sorted({made["rules"] for made in mine})
        lines += [f"## {setting} ({rules[0]})" if len(rules) == 1 else f"## {setting}", ""]
        if len(rules) > 1:
            lines += [f"Mixed rules files, models or efforts in this setting, so its runs are not one comparison: {', '.join(rules)}", ""]
        if scored:
            lines += ["| run | case | comments | found | partly | missed | judge errors | leads | extra leads | reviewer tokens in (cached) / out | minutes | recorded cost |",
                      "|---|---|---|---|---|---|---|---|---|---|---|---|"]
            lines += [cells(made["run"], made["case"], [made]) for made in scored]
            lines += [cells("all scored runs", f"{len(scored)} of {len(mine)} runs", scored), "",
                      "Judge tokens: " + " / ".join(f"{sum(made[count] for made in scored):,}" for count in JUDGE) + " (in / out)", ""]
        unscored = [f"{made['run']} ({made['status'] if made['status'] != 'completed' else 'completed, not judged'})" for made in mine if not made["judged"]]
        if unscored:
            lines += ["Not scored, and not counted as zero: " + ", ".join(unscored), ""]
    return "\n".join(lines)


def summary(rows, when):
    """What the tables show, as plain data to save: names, counts and times, and for every comment its id and its verdict.
    The verdict of each comment is what lets two settings be compared comment by comment."""
    settings = []
    for setting in sorted({made["setting"] for made in rows}):
        mine = [made for made in rows if made["setting"] == setting]
        settings.append({
            "name": setting, "rules": sorted({made["rules"] for made in mine}),
            "runs": [{"run": made["run"], "case": made["case"], "comments": made["comments"], "found": made["FOUND"], "partly": made["PARTLY"], "missed": made["MISSED"],
                      "errors": made["errors"], "leads": made["leads"], "extra": made["extra"], "verdicts": made["verdicts"],
                      **{count: made[count] for count in REVIEWER}, "cost": made["cost"],
                      "seconds": round(made["minutes"] * 60, 3), "judgeInputTokens": made["prompt_tokens"], "judgeOutputTokens": made["completion_tokens"]}
                     for made in mine if made["judged"]],
            "notScored": [{"run": made["run"], "status": made["status"] if made["status"] != "completed" else "not judged"} for made in mine if not made["judged"]]})
    return {"version": 1, "savedAt": when.strftime("%Y-%m-%dT%H:%M:%SZ"), "settings": settings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="An experiment: a directory holding <setting>/<run>/ folders written by run.py and judge.py")
    parser.add_argument("--save", action="store_true", help="Also save the numbers as scores.json in that directory, where the dashboard looks for them")
    args = parser.parse_args()
    rows = collect(args.root)
    print(render(rows))
    if args.save and rows:  # with no runs there is nothing worth replacing an earlier result with
        print("Saved to", saved.write(args.root, "scores.json", summary(rows, datetime.now(timezone.utc))))


if __name__ == "__main__":
    main()
