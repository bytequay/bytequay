#!/usr/bin/env python3
"""Run what is still missing of an experiment: every case of its plan under every setting, as often as the plan says.
An experiment is a folder that keeps its plan and all of its runs, so it can be stopped, started again and added to."""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import contextlib
from datetime import datetime, timezone
import fcntl
import getpass
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

import report
import saved

RUN, JUDGE = Path(__file__).with_name("run.py"), Path(__file__).with_name("judge.py")
PROVIDERS = ("codex", "claude")
ENDED = ("completed", "failed", "timed_out", "incomplete_output")
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")  # a setting and a case become folder names


def load(folder, name):
    """The plan of an experiment with every path made whole. Fails with ValueError, saying what is wrong, before anything runs."""
    experiment = folder / "experiments" / name

    def need(right, problem):
        if not right:
            raise ValueError(f"{experiment / 'plan.json'}: {problem}")

    try:
        plan = json.loads((experiment / "plan.json").read_text())
    except FileNotFoundError:
        raise ValueError(f"{experiment / 'plan.json'} is not there") from None
    except ValueError:
        need(False, "it is not JSON")
    need(isinstance(plan, dict) and plan.get("version") == 1, "it is not a plan this version reads")
    repeats, timeout, clones, programs = plan.get("repeats", 1), plan.get("timeout", 900), plan.get("clones", {}), plan.get("executables", {})
    need(type(repeats) is int and repeats >= 1, "repeats must be a whole number of 1 or more")
    need(type(timeout) is int and timeout >= 1, "timeout must be a whole number of seconds")
    need(isinstance(clones, dict) and isinstance(programs, dict) and set(programs) <= set(PROVIDERS) and all(isinstance(path, str) for path in programs.values()),
         "clones and executables must each map a name to a path")
    named, settings = plan.get("cases"), plan.get("settings")
    need(isinstance(named, list) and named and isinstance(settings, dict) and settings, "it needs cases and settings")
    cases = []
    for case in named:
        need(isinstance(case, str) and NAME.fullmatch(case), f"{case!r} cannot name a case")
        kept = folder / "cases" / case
        try:
            frozen = json.loads((kept / "case.json").read_text())
            repo, base, head = frozen["repo"], frozen["base"], frozen["head"]
        except (OSError, ValueError, KeyError, TypeError):
            need(False, f"{kept / 'case.json'} is not a case")
        need(all(isinstance(value, str) for value in (repo, base, head)), f"{kept / 'case.json'} is not a case")
        need((kept / "context.md").is_file(), f"{kept / 'context.md'} is not there")
        need(isinstance(clones.get(repo), str) and Path(clones[repo]).is_dir(), f"clones names no folder for {repo}")
        cases.append({"name": case, "folder": kept, "base": base, "head": head, "clone": Path(clones[repo])})
    made = []
    for setting, chosen in settings.items():
        need(NAME.fullmatch(setting) and isinstance(chosen, dict), f"{setting!r} cannot name a setting")
        need(chosen.get("provider") in PROVIDERS, f"{setting}: provider must be one of {', '.join(PROVIDERS)}")
        need(all(isinstance(chosen.get(key), str) and chosen[key] for key in ("model", "effort")), f"{setting}: model and effort must be named")
        policy = chosen.get("policy", plan.get("policy"))
        need(isinstance(policy, str) and (experiment / policy).is_file(), f"{setting}: its rules file {policy!r} is not there")
        made.append({"name": setting, "provider": chosen["provider"], "model": chosen["model"], "effort": chosen["effort"], "policy": experiment / policy})
    return {"folder": experiment, "repeats": repeats, "timeout": timeout, "executables": programs, "cases": cases, "settings": made}


def runs(plan):
    """Every run the plan asks for as (setting, case, folder), a case at a time, so a stopped experiment has compared whole cases."""
    for case in plan["cases"]:
        for repeat in range(1, plan["repeats"] + 1):
            for setting in plan["settings"]:
                yield setting, case, plan["folder"] / setting["name"] / f"{case['name']}_rep{repeat}"


def state(run):
    """How a run stands: one of ENDED, "missing", or "interrupted" for a folder whose run never ended."""
    if not run.exists():
        return "missing"
    try:
        status = json.loads((run / "runtime.json").read_text())["status"]
    except (OSError, ValueError, KeyError, TypeError):
        return "interrupted"
    return status if status in ENDED else "interrupted"


def runner(plan):
    """The process that is running this experiment now, or None. While it runs it holds a lock on a file that names it. The
    system lets go of the lock however the process ends, so a file left behind is not believed, also when its number has
    been given to another process since."""
    named = plan["folder"] / "running.pid"
    try:
        with named.open() as held:
            fcntl.flock(held, fcntl.LOCK_SH | fcntl.LOCK_NB)
            return None  # nobody holds the lock
    except BlockingIOError:
        pass
    except OSError:
        return None
    try:
        return int(named.read_text())
    except (OSError, ValueError):
        return None  # it has the lock and has not named itself yet


def claim(plan):
    """Name this process as the one running the experiment, and hold the lock on that name for as long as the returned file
    stays open. Two at once would each take the other's runs for interrupted ones and start them over."""
    named = (plan["folder"] / "running.pid").open("a+")
    try:
        fcntl.flock(named, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        named.close()
        other = runner(plan)
        raise ValueError(f"{plan['folder'].name} is already running" + ("" if other is None else f", as process {other}")) from None
    named.truncate(0)
    named.write(str(os.getpid()))
    named.flush()
    return named


def arm(plan, setting, case, run):
    return [sys.executable, str(RUN), "--repo", str(case["clone"]), "--base", case["base"], "--head", case["head"], "--policy", str(setting["policy"]),
            "--context", str(case["folder"] / "context.md"), "--output", str(run), "--provider", setting["provider"], "--model", setting["model"],
            "--reasoning", setting["effort"], "--timeout", str(plan["timeout"]),
            *(option for provider, path in plan["executables"].items() for option in ("--" + provider, path))]


def tell(line):
    print(line, flush=True)  # an experiment runs for hours, often into a file: each line is wanted when it happens


def execute(plan, parallel, say=tell):
    """Run every run that has not ended, `parallel` at a time, and return how each of them ended."""
    waiting = [(setting, case, run) for setting, case, run in runs(plan) if state(run) not in ENDED]
    live = []

    def one(setting, case, run):
        if run.exists():
            shutil.rmtree(run)  # an interrupted run: run.py only writes into a new folder
        run.parent.mkdir(parents=True, exist_ok=True)
        process = subprocess.Popen(arm(plan, setting, case, run), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        live.append(process)
        errors = process.communicate()[1]
        live.remove(process)
        # Two whole source trees and the command line's own working state: a few hundred megabytes for each run. The manifest keeps the trees' hashes.
        for part in ("input/base", "input/head", "state", "logs"):
            shutil.rmtree(run / part, ignore_errors=True)
        ended = state(run)
        say(f"{setting['name']} {run.name}: {ended}" + (f" ({errors.strip().splitlines()[-1]})" if ended == "interrupted" and errors.strip() else ""))
        return ended

    pool = ThreadPoolExecutor(parallel)
    try:
        return list(pool.map(lambda job: one(*job), waiting))
    except BaseException:
        # Stopped: no new run starts, and the ones that run are told to stop. Each of them takes its command line down with it.
        pool.shutdown(wait=False, cancel_futures=True)
        for process in list(live):
            process.terminate()
        raise
    finally:
        pool.shutdown(wait=True)


def judge(plan, say=tell):
    """Have the judge score every completed run that has no verdicts yet. The key comes from the environment, or is asked for without being shown or kept."""
    waiting = [(case, run) for _, case, run in runs(plan) if state(run) == "completed" and not (run / "judgement.json").exists()]
    environment = dict(os.environ)
    if waiting and not environment.get("DEEPSEEK_API_KEY"):
        if not sys.stdin.isatty():
            say(f"{len(waiting)} runs are not judged: DEEPSEEK_API_KEY is not set.")
            return
        environment["DEEPSEEK_API_KEY"] = getpass.getpass("DeepSeek API key (not shown, not saved): ")
    for case, run in waiting:
        done = subprocess.run([sys.executable, str(JUDGE), "run", "--case", str(case["folder"] / "case.json"), "--run", str(run)], capture_output=True, text=True, env=environment)
        judged = (run / "judgement.json").exists()
        say(f"{run.parent.name} {run.name}: " + ("judged" if judged else "not judged (" + (done.stderr.strip().splitlines() or ["the judge gave no reason"])[-1] + ")"))
    rows = report.collect(plan["folder"])
    if rows:  # the scores the dashboard compares, kept with the experiment
        say(f"Saved to {saved.write(plan['folder'], 'scores.json', report.summary(rows, datetime.now(timezone.utc)))}")


def standing(plan):
    """Every run of the plan as (setting, run folder, how it stands, whether it is judged). While the experiment runs, a run that has not ended is one that is running."""
    running = runner(plan) is not None
    return [(setting["name"], run, "running" if running and state(run) == "interrupted" else state(run), (run / "judgement.json").exists()) for setting, _, run in runs(plan)]


def listing(plan):
    """One line for every run of the plan, and how many stand in each state."""
    made = standing(plan)
    counted = Counter(stands for _, _, stands, _ in made)
    return [*(f"{setting} {run.name}: {stands}" + (", judged" if judged else "") for setting, run, stands, judged in made),
            ", ".join(f"{amount} {stands}" for stands, amount in sorted(counted.items()))]


def start(plan):
    """Run the experiment apart from this process, as `uv run batch.py NAME --judge` would, and wait until it has claimed the
    experiment or ended. What it says goes to batch.log beside the plan. It judges only when DEEPSEEK_API_KEY is set where it
    starts: nobody is there to be asked for the key."""
    with (plan["folder"] / "batch.log").open("a") as log:
        process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), plan["folder"].name, "--folder", str(plan["folder"].parents[1]), "--judge"],
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    for _ in range(100):
        if runner(plan) is not None or process.poll() is not None:
            break
        time.sleep(0.05)
    return process


def stop(plan):
    """Stop the experiment as Ctrl+C would, and wait for it to end, half a minute at most."""
    process = runner(plan)
    if process is not None:
        with contextlib.suppress(ProcessLookupError):  # it ended on its own just now
            os.kill(process, signal.SIGTERM)
    for _ in range(300):
        if runner(plan) is None:
            return
        time.sleep(0.1)


def stopped(*_):
    """A stop from outside ends the runs as Ctrl+C does."""
    signal.signal(signal.SIGTERM, signal.SIG_IGN)  # a second stop would cut short what the first one cleans up
    sys.exit(143)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", help="The experiment: a folder under experiments/ that holds a plan.json")
    parser.add_argument("--folder", type=Path, default=saved.FOLDER, help="The lab's folder, which holds experiments/ and cases/")
    parser.add_argument("--list", action="store_true", help="Only say how every run of the plan stands; run nothing")
    parser.add_argument("--parallel", type=int, default=1, help="Runs at the same time")
    parser.add_argument("--judge", action="store_true", help="Afterwards, have the judge score the completed runs that have no verdicts yet")
    args = parser.parse_args()
    if args.parallel < 1:
        parser.error("--parallel must be 1 or more")
    try:
        plan = load(args.folder, args.name)
    except ValueError as problem:
        parser.exit(2, f"{problem}\n")
    if args.list:
        print("\n".join(listing(plan)))
        return
    signal.signal(signal.SIGTERM, stopped)
    try:
        held = claim(plan)
    except ValueError as problem:
        parser.exit(2, f"{problem}\n")
    try:
        execute(plan, args.parallel)
        if args.judge:
            judge(plan)
    except KeyboardInterrupt:
        parser.exit(130, "Stopped. Start it again to run what is missing.\n")
    finally:
        (plan["folder"] / "running.pid").unlink(missing_ok=True)  # before the lock goes, so nobody takes the lock of a name that is going
        held.close()
    print(listing(plan)[-1])


if __name__ == "__main__":
    main()
