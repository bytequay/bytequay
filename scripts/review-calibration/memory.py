#!/usr/bin/env python3
"""Does repository memory help? ByteQuay gives a turn the memories of its repository, and counts for each memory the
turns it was given to and the review findings that cite it. This reads those records read-only from ByteQuay's database:
how often each memory was given and cited, what the memories cost a turn, and how the review findings that cite a memory
ended against those that cite none.

`--save` keeps `memory.json` for the dashboard's Memory page: ids, kinds, counts, sizes and times, never a memory's title
or text. The page reads the titles from the database only to show them."""

from __future__ import annotations

import monitor
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3

import activity
import records
import saved

FILE = "memory.json"
GIVEN = "memories_json IS NOT NULL AND memories_json NOT IN ('', '[]')"


def ids(text):
    """The ids of the memories a turn was given or a finding cites, and how many characters the given lines hold."""
    entries = json.loads(text) if text else []
    return [str(entry["id"]) for entry in entries], sum(len(str(entry.get("line") or "")) for entry in entries)


def table(database, now):
    """The memories, the turns given some, and every review finding with the memories it cites, ready to save."""
    memories, turns, findings = records.rows(
        database,
        "SELECT id, kind, delivered, cited, created_at FROM repository_memory ORDER BY id",
        f"SELECT task_id, id, memories_json, input_tokens, model FROM agent_turn WHERE {GIVEN}",
        "SELECT id, state, memories_json FROM agent_review_finding")
    return {"version": 1, "savedAt": saved.stamp(now),
            "memories": [{"id": memory, "kind": kind, "given": given or 0, "cited": cited or 0, "created": created} for memory, kind, given, cited, created in memories],
            "turns": [{"task": task, "turn": turn, **dict(zip(("given", "characters"), ids(text))), "inputTokens": spent, "model": model}
                      for task, turn, text, spent, model in turns],
            "findings": [{"finding": finding, "state": (state or "").lower(), "cites": ids(text)[0]} for finding, state, text in findings]}


def titles(database):
    """Each memory's title, read only to show it."""
    return dict(records.rows(database, "SELECT id, title FROM repository_memory")[0])


def held(findings):
    """How the review findings that cite a memory ended against those that cite none: for each, the findings, those
    resolved, withdrawn and still open, and the share of the decided ones that held up (resolved)."""
    made = {}
    for name, chosen in (("cite a memory", [one for one in findings if one["cites"]]), ("cite none", [one for one in findings if not one["cites"]])):
        counted = {state: sum(one["state"] == state for one in chosen) for state in ("resolved", "withdrawn", "open")}
        decided = counted["resolved"] + counted["withdrawn"]
        made[name] = {"findings": len(chosen), **counted, "held up": 100 * counted["resolved"] / decided if decided else None}
    return made


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--database", type=Path, default=monitor.DATABASE, help="ByteQuay's database, read without writing")
    parser.add_argument("--save", type=Path, nargs="?", const=saved.FOLDER, metavar="FOLDER", help="Also save the counts as memory.json for the dashboard")
    args = parser.parse_args()
    try:
        made = table(args.database, datetime.now(timezone.utc).timestamp())
    except (OSError, sqlite3.Error) as problem:
        parser.exit(1, f"The database could not be read: {problem}\n")
    never = [one for one in made["memories"] if one["given"] and not one["cited"]]
    print(f"{len(made['memories']):,} memories; {len(made['turns']):,} turns were given some; {len(never):,} were given and never cited.")
    for name, row in held(made["findings"]).items():
        print(f"  findings that {name}: {row['findings']:,}, {row['resolved']:,} resolved, {row['withdrawn']:,} withdrawn, {row['open']:,} open")
    if args.save and made["memories"]:  # with no memory there is nothing worth replacing an earlier table with
        print("Saved to", saved.write(args.save, FILE, made))


if __name__ == "__main__":
    main()
