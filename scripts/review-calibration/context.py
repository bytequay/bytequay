#!/usr/bin/env python3
"""How the context of an agent turn grows from one model call to the next, and what fills it, from ByteQuay's task logs.

ByteQuay logs the tokens a Codex turn has spent after every model call, as a running total. The step from one total to
the next is what that call was sent, the whole context, and what it wrote. Codex logs a call's tokens after the commands
and tools the call started, whose results come into the next call. A call is sent what the call before it was sent, what
that call wrote, and the results of what that call ran. So what came in is the step less the two, shared out evenly among
what the call before it ran. A context that shrank was compacted. A call's time is split by a straight fit into a fixed
part and parts for what it is sent and what it writes. Claude reports a turn's tokens once, at its end, so its turns have
no calls here.

`--save` keeps `context.json` for the dashboard's Context page: names, counts and times only, never a command's
arguments, a path or what a tool returned. Model and effort come read-only from ByteQuay's database."""

from __future__ import annotations

import monitor
import argparse
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
import sqlite3
import statistics

import numpy

import activity
import records
import saved

FILE = "context.json"
OWN_TURN, SESSION = RUNNING = ("CUMULATIVE_FOR_INVOCATION", "CUMULATIVE")  # totals that grow call by call
FINISHED = ("COMPLETED", "FAILED")
FITTED = 30  # timed calls of a model before its time is split


def name_of(kind, payload):
    """What an item in a log is counted under: the program a command ran, a tool's name, or an edit."""
    if kind == "command":
        return activity.program(activity.command_text(payload))
    return activity.tool_name(str(payload.get("name") or "tool")) if kind == "tool" else "edit"


def calls(events, known=None):
    """The model calls of one task log in order, each as {turn, start, call, sent, wrote, added, shrank, between, seconds}:
    `call` counts from 1 in each turn; `added` is None for a turn's first call and for one whose context shrank; `between`
    names what the call before it ran, whose results came into this call; `seconds` is the time from when all of those
    results were back, or the turn began, to the call's first command or tool, or to its end when it ran none. Codex logs
    a call's tokens after the commands and tools the call started, so what started before a call's tokens were logged is
    what that call ran. A subagent's own calls and items are left out: they fill its context.

    `known` maps a turn to the input and output tokens ByteQuay's database holds for the whole turn. Some turns logged the
    session's running total under the turn's own name (a Codex count since fixed), and the database's total says where the
    turn's running total started. Without it a turn's own total starts at zero, and a session's goes on from the turn
    before; when that is not in the log, the turn's first call is left out."""
    known, turns, session, began, ran, started, ended = known or {}, {}, {}, {}, defaultdict(list), {}, {}
    for event in events:
        kind, payload, source = event["type"], event["payload"], event.get("source") or {}
        if source.get("parentProviderThreadId"):
            continue
        when, turn = activity.seconds(event["occurredAt"]), event.get("turnId")
        began.setdefault(turn, when)
        if kind in ("command", "tool", "file_change"):
            item = (turn, source.get("providerItemId") or event.get("eventId"))
            if item not in started:  # an item counts once, when it is first seen
                started[item] = when
                ran[turn].append((item, name_of(kind, payload)))
            if payload.get("state") in FINISHED:
                ended[item] = when
            continue
        if kind != "usage" or payload.get("semantics") not in RUNNING:
            continue
        thread = source.get("providerThreadId")
        spent = (payload.get("inputTokens") or 0, payload.get("outputTokens") or 0)
        entry = turns.setdefault(turn, {"semantics": payload["semantics"], "before": session.get(thread), "totals": []})
        session[thread] = spent
        if entry["totals"] and spent[0] <= entry["totals"][-1]["spent"][0]:
            continue  # the same total sent again, as after a compaction; what ran goes with the next call
        entry["totals"].append({"spent": spent, "start": event["occurredAt"], "when": when, "ran": ran.pop(turn, [])})
    found = []
    for turn, entry in turns.items():
        totals, base = entry["totals"], start_of(entry, known.get(turn))
        previous = None
        for number, total in enumerate(totals, 1):
            before = base if number == 1 else totals[number - 2]["spent"]
            if before is None:
                continue
            sent, wrote = total["spent"][0] - before[0], total["spent"][1] - before[1]
            if sent <= 0:
                previous = None  # a first call the database cannot account for
                continue
            earlier = totals[number - 2] if number > 1 else None
            asked = began[turn] if earlier is None else max([earlier["when"], *(ended[item] for item, _ in earlier["ran"] if item in ended)])
            acted = min((started[item] for item, _ in total["ran"]), default=total["when"])
            added = None if previous is None or previous["call"] != number - 1 else sent - previous["sent"] - previous["wrote"]
            previous = {"turn": turn, "start": total["start"], "call": number, "sent": sent, "wrote": wrote,
                        "added": None if added is None or added < 0 else added, "shrank": added is not None and added < 0,
                        "between": [] if earlier is None else [name for _, name in earlier["ran"]], "seconds": round(acted - asked, 3) if acted >= asked else None}
            found.append(previous)
    return found


def start_of(entry, whole):
    """Where a turn's running total started, as (input, output) tokens, or None when that is not known."""
    last = entry["totals"][-1]["spent"]
    if whole is not None and whole[0]:
        # A turn logged on its own reaches the database's total; one logged on the session's started that much before it.
        before = (last[0] - whole[0], last[1] - (whole[1] or 0))
        return before if before[0] > 0.01 * whole[0] else (0, 0)
    return (0, 0) if entry["semantics"] == OWN_TURN else entry["before"]


def table(runtime, database, now):
    """Every model call of every task log, with the model and effort of its turn, ready to save."""
    try:
        recorded, problem = records.read(database), None
    except OSError:
        recorded, problem = {}, "the database is not there"
    except sqlite3.Error as error:
        recorded, problem = {}, f"the database has another shape ({error})"
    made = []
    for log in sorted(runtime.glob("*/events.jsonl")):
        task = log.parent.name
        mine = {turn: one for (owner, turn), one in recorded.items() if owner == task}
        totals = {turn: (one["inputTokens"], one["outputTokens"]) for turn, one in mine.items() if one.get("inputTokens")}
        for call in calls(activity.read(log)[0], totals):
            made.append({"task": task, **call, "model": (mine.get(call["turn"]) or {}).get("model"), "effort": (mine.get(call["turn"]) or {}).get("effort")})
    return {"version": 1, "savedAt": saved.stamp(now), "database": {"problem": problem}, "calls": made}


def adders(made):
    """What fills the context: for each name, the calls it ran before, the tokens it added (each call's addition shared
    evenly among what ran before it), its share of all that was added, the input tokens it caused (what is added before a
    call is sent again until the next observed compaction), and the typical tokens it added when it ran alone."""
    until = {}
    shared, caused, counted, alone = defaultdict(float), defaultdict(float), defaultdict(int), defaultdict(list)
    for call in sorted(made, key=lambda call: call["call"], reverse=True):
        turn = (call["task"], call["turn"])
        until.setdefault(turn, call["call"] + 1)
        if call["shrank"]:
            until[turn] = call["call"]  # How much of an earlier result survived compaction is not known.
        if call["added"] is None or not call["between"]:
            continue
        sent = until[turn] - call["call"]
        for name in call["between"]:
            shared[name] += call["added"] / len(call["between"])
            caused[name] += sent * call["added"] / len(call["between"])
            counted[name] += 1
        if len(call["between"]) == 1:
            alone[call["between"][0]].append(call["added"])
    whole = sum(shared.values()) or 1
    return sorted(({"name": name, "calls": counted[name], "tokens": round(tokens), "share": 100 * tokens / whole, "caused": round(caused[name]),
                    "alone": statistics.median(alone[name]) if alone[name] else None} for name, tokens in shared.items()), key=lambda row: -row["caused"])


def timing(made):
    """What a model's time goes with, for each model with enough timed calls: its calls, their typical seconds, and the
    seconds of a call split by a straight fit into a fixed part, a part for every 100,000 tokens sent and one for every
    1,000 written (the reasoning counted), with how much of the spread the fit explains and each part's share."""
    rows = []
    by_model = {}
    for call in made:
        if call.get("seconds") is not None and call["model"]:
            by_model.setdefault(call["model"], []).append(call)
    for model, own in sorted(by_model.items(), key=lambda pair: -len(pair[1])):
        if len(own) < FITTED:
            continue
        shape = numpy.array([[1.0, call["sent"] / 1e5, call["wrote"] / 1e3] for call in own])
        seconds = numpy.array([call["seconds"] for call in own])
        fit = numpy.linalg.lstsq(shape, seconds, rcond=None)[0]
        guessed = shape @ fit
        spread = ((seconds - seconds.mean()) ** 2).sum()
        parts = (shape * fit).sum(axis=0) / guessed.sum() * 100 if guessed.sum() else numpy.zeros(3)
        rows.append({"model": model, "calls": len(own), "typical": float(numpy.median(seconds)), "fixed": float(fit[0]), "per 100k sent": float(fit[1]),
                     "per 1k written": float(fit[2]), "explained": float(1 - ((seconds - guessed) ** 2).sum() / spread) if spread else None,
                     "fixed share": float(parts[0]), "context share": float(parts[1]), "writing share": float(parts[2])})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runtime", type=Path, default=activity.RUNTIME, help="Folder with one sub-folder per task, each holding an events.jsonl")
    parser.add_argument("--database", type=Path, help="ByteQuay's database, read without writing (default: beside the runtime folder)")
    parser.add_argument("--save", type=Path, nargs="?", const=saved.FOLDER, metavar="FOLDER", help="Also save the calls as context.json for the dashboard")
    args = parser.parse_args()
    made = table(args.runtime, args.database or args.runtime.parent / monitor.DATABASE.name, datetime.now(timezone.utc).timestamp())
    found = made["calls"]
    sent = [call["sent"] for call in found]
    print(f"{len(found):,} model calls in {len({(call['task'], call['turn']) for call in found}):,} turns; "
          + (f"a call is typically sent {statistics.median(sent):,.0f} tokens, at most {max(sent):,}." if sent else "none logs its tokens call by call."))
    for row in adders(found)[:10]:
        print(f"  {row['name']:24} {row['share']:5.1f}% of what was added, {row['tokens']:>11,} tokens over {row['calls']:,} calls, causing {row['caused']:>13,} "
              "input tokens" + ("" if row["alone"] is None else f"; alone typically {row['alone']:,.0f}"))
    for row in timing(found):
        print(f"  {row['model']}: a call takes typically {row['typical']:,.1f} s; {row['writing share']:.0f}% of it goes with what it writes "
              f"({row['per 1k written']:,.1f} s per 1,000 tokens), {row['context share']:.0f}% with what it is sent")
    if args.save and found:  # with no call there is nothing worth replacing an earlier table with
        print("Saved to", saved.write(args.save, FILE, made))


if __name__ == "__main__":
    main()
