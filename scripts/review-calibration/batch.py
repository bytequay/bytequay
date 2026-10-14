#!/usr/bin/env python3
"""Run what is still missing of an experiment: every case of its plan under every setting, as often as the plan says.
An experiment is a folder that keeps its plan and all of its runs, so it can be stopped, started again and added to.
A schedule can add a round of runs every day or week by itself."""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import contextlib
from datetime import datetime, timedelta, timezone
import fcntl
import getpass
import json
import os
from pathlib import Path
import plistlib
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
ROUND = re.compile(r".+_rep(\d+)")
KEYCHAIN = "ByteQuay Lab DeepSeek"  # the Keychain item that keeps the judge's key
AGENT = "com.bytequay.lab"  # the one LaunchAgent the lab keeps
EVERY = {"day": timedelta(days=1), "week": timedelta(weeks=1)}
EARLY = timedelta(hours=12)  # the nightly look comes at about the same hour each night, so a round is due a little before its time is full


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


def rounds(plan):
    """How many rounds the experiment has: the plan's repeats, or more once the schedule has added rounds."""
    return max([plan["repeats"], *(int(found[1]) for setting in plan["settings"] for run in (plan["folder"] / setting["name"]).glob("*_rep*")
                                   if (found := ROUND.fullmatch(run.name)))])


def runs(plan, total=None):
    """Every run of the experiment's rounds, or of `total` rounds, as (setting, case, folder), a case at a time, so a stopped
    experiment has compared whole cases."""
    last = total or rounds(plan)
    for case in plan["cases"]:
        for repeat in range(1, last + 1):
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


def execute(plan, parallel, say=tell, total=None):
    """Run every run that has not ended, `parallel` at a time, and return how each of them ended. With `total`, the experiment
    gets that many rounds."""
    waiting = [(setting, case, run) for setting, case, run in runs(plan, total) if state(run) not in ENDED]
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


def stored_key():
    """The judge's key from the Keychain, or None. It goes to the judge alone and is never shown."""
    found = subprocess.run(["security", "find-generic-password", "-s", KEYCHAIN, "-w"], capture_output=True, text=True)
    return found.stdout.strip() if found.returncode == 0 and found.stdout.strip() else None


def has_key():
    """Whether the judge has a key without asking anyone. The Keychain is only asked whether it holds one; the key is not read."""
    return bool(os.environ.get("DEEPSEEK_API_KEY")) or subprocess.run(["security", "find-generic-password", "-s", KEYCHAIN], capture_output=True).returncode == 0


def store_key():
    """Keep the judge's key in the Keychain. The Mac's own tool asks for it, so it never passes through this program."""
    # The Mac's prompt says "password data", which reads like the Mac's own password.
    print("Paste your DeepSeek API key (not your Mac password) when asked for password data, then once more. Nothing shows as you type.", flush=True)
    try:
        return subprocess.run(["security", "add-generic-password", "-U", "-a", getpass.getuser(), "-s", KEYCHAIN, "-w"]).returncode
    except KeyboardInterrupt:
        return 130  # stopped with Ctrl+C: nothing is stored


def judge(plan, say=tell):
    """Have the judge score every completed run that has no verdicts yet. The key comes from the environment or the Keychain, or
    is asked for without being shown or kept."""
    waiting = [(case, run) for _, case, run in runs(plan) if state(run) == "completed" and not (run / "judgement.json").exists()]
    environment = dict(os.environ)
    if waiting and not environment.get("DEEPSEEK_API_KEY"):
        key = stored_key() or (getpass.getpass("DeepSeek API key (not shown, not saved): ") if sys.stdin.isatty() else None)
        if not key:
            say(f"{len(waiting)} runs are not judged: there is no key. Store it once with `uv run batch.py --store-key`.")
            return
        environment["DEEPSEEK_API_KEY"] = key
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
    experiment or ended. What it says goes to batch.log beside the plan. It judges only with a key in the Keychain or in
    DEEPSEEK_API_KEY where it starts: nobody is there to be asked for one."""
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


def schedule(folder):
    """For every scheduled experiment: how often a new round starts and when the last one did."""
    return (saved.read(folder, "schedule.json") or {}).get("experiments", {})


def next_round(chosen):
    """When the next round of a scheduled experiment is due."""
    return datetime.fromisoformat(chosen["last"]) + EVERY[chosen["every"]]


def launchctl(*arguments):
    return subprocess.run(["launchctl", *arguments], capture_output=True, text=True)


def keep_agent(label, settings):
    """Make one of the lab's LaunchAgents match what is wanted: loaded with these settings, or gone when there are none."""
    agent = Path.home() / "Library" / "LaunchAgents" / f"{label}.plist"
    launchctl("bootout", f"gui/{os.getuid()}/{label}")  # the copy loaded before, if any
    if not settings:
        agent.unlink(missing_ok=True)
        return
    agent.parent.mkdir(parents=True, exist_ok=True)
    agent.write_bytes(plistlib.dumps({"Label": label, **settings}))
    loaded = launchctl("bootstrap", f"gui/{os.getuid()}", str(agent))
    if loaded.returncode:
        raise ValueError(f"launchctl could not load {agent}: {loaded.stderr.strip()}")


def keep_schedule(folder, kept):
    """Save the schedule, and make the Mac's own scheduler match it: one LaunchAgent while any experiment is scheduled, none when
    no experiment is. Every night at three it starts the rounds that are due; a night the Mac slept through counts when it wakes."""
    saved.write(folder, "schedule.json", {"version": 1, "experiments": kept})
    keep_agent(AGENT, kept and {"ProgramArguments": [sys.executable, str(Path(__file__).resolve()), "--due", "--folder", str(folder)],
                                "StartCalendarInterval": {"Hour": 3, "Minute": 0}, "EnvironmentVariables": {"PATH": os.environ.get("PATH", "")},
                                "StandardOutPath": str(folder / "schedule.log"), "StandardErrorPath": str(folder / "schedule.log")})


def run_due(folder, now, say=tell):
    """Start a new round of every scheduled experiment whose time has come, one after another, each judged and scored."""
    kept = schedule(folder)
    for name, chosen in kept.items():
        if now < next_round(chosen) - EARLY:
            continue
        try:
            plan = load(folder, name)
        except ValueError as problem:
            say(f"{now:%Y-%m-%d %H:%M %Z} {name}: not started: {problem}")
            continue
        if runner(plan) is not None:
            say(f"{now:%Y-%m-%d %H:%M %Z} {name}: not started: it is running")
            continue
        chosen["last"] = now.isoformat(timespec="seconds")
        saved.write(folder, "schedule.json", {"version": 1, "experiments": kept})
        say(f"{now:%Y-%m-%d %H:%M %Z} {name}: a new round starts")
        subprocess.run([sys.executable, str(Path(__file__).resolve()), name, "--folder", str(folder), "--round", "--judge"], stdin=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", nargs="?", help="The experiment: a folder under experiments/ that holds a plan.json")
    parser.add_argument("--folder", type=Path, default=saved.FOLDER, help="The lab's folder, which holds experiments/ and cases/")
    parser.add_argument("--list", action="store_true", help="Only say how every run of the plan stands; run nothing")
    parser.add_argument("--parallel", type=int, default=1, help="Runs at the same time")
    parser.add_argument("--judge", action="store_true", help="Afterwards, have the judge score the completed runs that have no verdicts yet")
    parser.add_argument("--round", action="store_true", help="Add a round: every case under every setting once more")
    parser.add_argument("--every", choices=[*EVERY, "never"], help="Add a judged round by itself every day or week, from now on; never stops it")
    parser.add_argument("--due", action="store_true", help="Start the scheduled rounds whose time has come (what the Mac runs every night)")
    parser.add_argument("--store-key", action="store_true", help="Keep the judge's DeepSeek key in the Keychain; the Mac asks for it")
    args = parser.parse_args()
    if args.store_key:
        sys.exit(store_key())
    if args.due:
        run_due(args.folder, datetime.now(timezone.utc))
        return
    if not args.name:
        parser.error("name the experiment")
    if args.parallel < 1:
        parser.error("--parallel must be 1 or more")
    if args.every == "never":
        kept = schedule(args.folder)
        kept.pop(args.name, None)
        try:
            keep_schedule(args.folder, kept)
        except ValueError as problem:
            parser.exit(1, f"{problem}\n")
        print(f"{args.name} is not scheduled.")
        return
    try:
        plan = load(args.folder, args.name)
    except ValueError as problem:
        parser.exit(2, f"{problem}\n")
    if args.list:
        print("\n".join(listing(plan)))
        return
    if args.every:
        chosen = {"every": args.every, "last": datetime.now(timezone.utc).isoformat(timespec="seconds")}
        try:
            keep_schedule(args.folder, {**schedule(args.folder), args.name: chosen})
        except ValueError as problem:
            parser.exit(1, f"{problem}\n")
        print(f"{args.name} gets a new round every {args.every}, the first on {next_round(chosen).astimezone():%Y-%m-%d}, judged with the key in the Keychain.")
        return
    signal.signal(signal.SIGTERM, stopped)
    try:
        held = claim(plan)
    except ValueError as problem:
        parser.exit(2, f"{problem}\n")
    try:
        execute(plan, args.parallel, total=rounds(plan) + 1 if args.round else None)
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
