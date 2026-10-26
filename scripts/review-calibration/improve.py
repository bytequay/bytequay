#!/usr/bin/env python3
"""One improvement of the review rules, from start to end. From what a setting's runs did on the tuning cases, the lab's
agent writes a version of how the reviewer works and one of what an expert of the repository knows; both run on those cases;
the agent merges them from what their runs did, never from a score; and the merged version runs on the unseen cases, where the
judge scores it beside the rules as they stand. Of the unseen cases nothing is shown but each setting's totals, their ranges
and the change between them. A person decides what becomes of a version."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

import agent
import batch
import judge
import ranges
import report
import saved
import writer

SCRIPT = Path(__file__).resolve()
STATE = "improvement.json"
STAGES = ("base", "work", "expert", "tried", "merge", "checked", "compared")


def place(folder):
    return folder / "improvements"


def numbered(folder):
    """Every improvement kept, the latest last."""
    return sorted((int(path.name), path) for path in place(folder).glob("*") if path.name.isdigit() and (path / STATE).is_file())


def improvements(folder):
    """Every improvement kept, the latest first."""
    return [saved.read(path, STATE) for _, path in reversed(numbered(folder))]


def start(folder, arguments, tuning):
    """Make or carry on an improvement apart from the page, as `uv run improve.py ARGUMENTS` would, and wait until it holds
    the tuning experiment or has ended. What it says goes to improve.log in the improvements folder. It takes the DeepSeek key
    from where it starts, or from the Keychain: nobody is there to be asked for one."""
    own = place(folder)
    own.mkdir(parents=True, exist_ok=True)
    with (own / "improve.log").open("a") as log:
        process = subprocess.Popen([sys.executable, str(SCRIPT), *arguments, "--folder", str(folder)], stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    plan = batch.load(folder, tuning)
    for _ in range(100):
        if batch.runner(plan) is not None or process.poll() is not None:
            break
        time.sleep(0.05)
    return process


def add_setting(experiment, name, chosen):
    """Name a setting in an experiment's plan. One already there must be the same, so a plan never changes what a setting ran."""
    plan = json.loads((experiment / "plan.json").read_text())
    if plan["settings"].get(name, chosen) != chosen:
        raise ValueError(f"{experiment.name} already has a setting {name} that runs something else")
    plan["settings"][name] = chosen
    saved.write_text(experiment, "plan.json", json.dumps(plan, indent=2) + "\n")


def checked(folder, tuning, unseen, setting):
    """The two plans, refused before anything runs when they cannot make an improvement."""
    plans = batch.load(folder, tuning), batch.load(folder, unseen)
    if plans[0]["unseen"] or not plans[1]["unseen"]:
        raise ValueError(f"{tuning} must hold tuning cases and {unseen} unseen ones, marked with \"unseen\": true")
    chosen = [next((one for one in plan["settings"] if one["name"] == setting), None) for plan in plans]
    if None in chosen:
        raise ValueError(f"{setting} must be a setting of both {tuning} and {unseen}")
    if len({(one["provider"], one["model"], one["effort"], one["policy"].read_bytes()) for one in chosen}) > 1:
        raise ValueError(f"{setting} must run the same rules, model and effort in {tuning} and {unseen}")
    return plans, chosen[0]


def totals(rows, setting):
    """What a setting's judged runs found, as a Ratio by case: the comments of one pull request are alike, so a case is drawn whole."""
    by_case = {}
    for made in rows:
        if made["setting"] == setting and made["judged"]:
            part, whole = by_case.get(made["case"], (0, 0))
            by_case[made["case"]] = (part + made["FOUND"] + made["PARTLY"], whole + made["comments"])
    return by_case


def compared(rows, today, merged):
    """Each setting's totals and range, and the change between them on the cases both were judged on. Never a case on its own."""
    sides = totals(rows, today), totals(rows, merged)
    cases = sorted(set(sides[0]) & set(sides[1]))
    measured = [ranges.Ratio(tuple(side[case][0] for case in cases), tuple(side[case][1] for case in cases), 100) for side in sides]
    moved = ranges.paired_change(*measured)
    summed = [{"setting": name, "comments": sum(whole for _, whole in side.values()), "foundOrPartly": sum(part for part, _ in side.values()),
               "share": measure.value(), "range": ranges.range_of(measure)} for name, side, measure in zip((today, merged), sides, measured)]
    verdict = "not clear" if not ranges.clear(moved) else "better" if moved[0] > 0 else "worse"
    return {"sides": summed, "cases": len(cases), "change": moved, "verdict": verdict}


def said(result):
    lines = [f"{side['setting']}: {side['foundOrPartly']} of {side['comments']} comments found or partly"
             + ("" if side["share"] is None else f", {side['share']:.0f}%") + (f" (range {ranges.said(side['range'], 0)})" if side["range"] else "")
             for side in result["sides"]]
    change = "no range" if result["change"] is None else f"{ranges.said(result['change'], 0)} points"
    return lines + [f"Change on the {result['cases']} unseen cases both were judged on: {change}, so {result['verdict']}."]


class Unfinished(Exception):
    """A writing that handed nothing in: the improvement ends, and can be carried on."""


def improve(folder, made, ask, model, parallel=2, say=batch.tell, clock=time.time):
    """Carry the improvement `made` on from the first stage that has not ended, saving it after each. A writing that hands
    nothing in ends it as failed; carried on, that writing starts over under a new label."""
    own = place(folder) / str(made["number"])
    (tuning, unseen), chosen = checked(folder, made["tuning"], made["unseen"], made["setting"])
    made.update(state="running", problem=None)
    saved.write(own, STATE, made)

    def run(name, judged=False):
        plan = batch.load(folder, name)
        held = batch.claim(plan) if plan["unseen"] else None  # the tuning experiment is held for the whole improvement
        try:
            batch.execute(plan, parallel, say)
            if judged:
                batch.judge(plan, say)
        finally:
            if held:
                (plan["folder"] / "running.pid").unlink(missing_ok=True)
                held.close()

    def written(kind, versions=()):
        done = writer.write(folder, made["tuning"], made["setting"], kind, ask, model, clock, versions)
        if done["state"] != "done":
            raise Unfinished(f"the {kind} writing did not finish: {done['problem']}")
        say(f"{kind}: kept {done['rules']}")
        return Path(done["rules"]).stem

    def tried(label):
        name = f"{made['setting']}-{label}"
        return name, {"provider": chosen["provider"], "model": chosen["model"], "effort": chosen["effort"], "policy": f"rules/{label}.md"}

    try:
        for stage in STAGES:
            if stage in made["done"]:
                continue
            say(f"Improvement {made['number']}: {stage}")
            if stage == "base":
                run(made["tuning"])
                run(made["unseen"], judged=True)
            elif stage in ("work", "expert"):
                made["labels"][stage] = written(stage)
            elif stage == "tried":
                for kind in ("work", "expert"):
                    add_setting(tuning["folder"], *tried(made["labels"][kind]))
                run(made["tuning"])
            elif stage == "merge":
                made["labels"]["merge"] = written("merge", [tried(made["labels"][kind])[0] for kind in ("work", "expert")])
            elif stage == "checked":
                label = made["labels"]["merge"]
                copy = unseen["folder"] / "rules" / f"{label}.md"
                copy.parent.mkdir(exist_ok=True)
                shutil.copyfile(tuning["folder"] / "rules" / f"{label}.md", copy)
                add_setting(unseen["folder"], *tried(label))
                run(made["unseen"], judged=True)
            else:
                made["compared"] = compared(report.collect(unseen["folder"]), made["setting"], tried(made["labels"]["merge"])[0])
                for line in said(made["compared"]):
                    say(line)
            made["done"].append(stage)
            saved.write(own, STATE, made)
        made["state"] = "done"
    except Unfinished as unfinished:
        made["state"], made["problem"] = "failed", str(unfinished)
    except (KeyboardInterrupt, SystemExit):
        made["state"] = "stopped"
        raise
    except Exception as failed:
        made["state"], made["problem"] = "failed", f"{type(failed).__name__}: {failed}"[:300]
        raise
    finally:
        made["ended"] = saved.stamp(clock())
        saved.write(own, STATE, made)
    return made


def begun(folder, tuning, unseen, setting, clock=time.time):
    """A new improvement, numbered after the last one."""
    checked(folder, tuning, unseen, setting)
    number = (numbered(folder) or [(0, None)])[-1][0] + 1
    made = {"version": 1, "number": number, "tuning": tuning, "unseen": unseen, "setting": setting, "state": "running", "started": saved.stamp(clock()),
            "ended": None, "done": [], "labels": {"work": None, "expert": None, "merge": None}, "compared": None, "problem": None}
    saved.write(place(folder) / str(number), STATE, made)
    return made


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tuning", help="The experiment of tuning cases the writer learns from")
    parser.add_argument("--unseen", help="The experiment of unseen cases the merged version is checked on")
    parser.add_argument("--setting", help="The setting, in both experiments, that runs the rules as they stand")
    parser.add_argument("--continue", dest="again", action="store_true", help="Carry on with the last improvement that did not end")
    parser.add_argument("--parallel", type=int, default=2, help="Runs at the same time")
    parser.add_argument("--model", default=judge.MODEL, help="The DeepSeek model that writes")
    parser.add_argument("--folder", type=Path, default=saved.FOLDER, help="The lab's folder, with both experiments")
    args = parser.parse_args()
    if args.again == any((args.tuning, args.unseen, args.setting)) or not (args.again or all((args.tuning, args.unseen, args.setting))):
        parser.error("name --tuning, --unseen and --setting for a new improvement, or --continue alone")
    if args.parallel < 1:
        parser.error("--parallel must be 1 or more")
    key = os.environ.get("DEEPSEEK_API_KEY") or batch.stored_key()
    if not key:
        parser.exit(1, "The writer and the judge need a DeepSeek key. Store it once with `uv run batch.py --store-key`\n")
    os.environ["DEEPSEEK_API_KEY"] = key  # where the writer's client and the judge take it from
    signal.signal(signal.SIGTERM, batch.stopped)
    try:
        if args.again:
            made = next((one for one in improvements(args.folder) if one["state"] != "done"), None)
            if made is None:
                parser.exit(1, "No improvement is left to carry on.\n")
        else:
            made = begun(args.folder, args.tuning, args.unseen, args.setting)
        held = batch.claim(batch.load(args.folder, made["tuning"]))
    except ValueError as problem:
        parser.exit(2, f"{problem}\n")
    try:
        made = improve(args.folder, made, agent.asker(judge.make_client(), args.model), args.model, args.parallel)
    except KeyboardInterrupt:
        parser.exit(130, "Stopped. Carry it on with `uv run improve.py --continue`.\n")
    except Exception as failed:  # the improvement's record says so too
        parser.exit(1, f"The improvement failed: {failed}\n")
    finally:
        (args.folder / "experiments" / made["tuning"] / "running.pid").unlink(missing_ok=True)
        held.close()
    if made["state"] != "done":
        parser.exit(1, f"The improvement did not finish: {made['problem']}. Carry it on with `uv run improve.py --continue`.\n")


if __name__ == "__main__":
    main()
