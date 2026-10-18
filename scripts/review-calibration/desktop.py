#!/usr/bin/env python3
"""Refresh the desktop Lab's saved statistics from local application data, without starting agents or a monitor."""

import argparse
from datetime import datetime, timezone
from pathlib import Path
import sqlite3

import activity
import context
import memory
import records
import saved
import tuning


def refresh(folder, runtime, database):
    """Read every source before saving, so a failed read preserves the last useful snapshots."""
    now = datetime.now(timezone.utc)
    recorded = records.read(database)
    tables = {
        "turns.json": activity.turn_table(activity.measure(sorted(runtime.glob("*/events.jsonl"))), now, recorded=recorded),
        "reviews.json": activity.review_table(records.reviews(database, recorded), now),
        "tuning.json": tuning.table(runtime, database, now),
        context.FILE: context.table(runtime, database, now.timestamp()),
        memory.FILE: memory.table(database, now.timestamp()),
    }
    for name in ("tuning.json", context.FILE):
        if problem := tables[name]["database"]["problem"]:
            raise ValueError(problem)
    # Empty tables replace older results too: deleting task history must not leave old statistics on screen.
    for name, table in tables.items():
        saved.write(folder, name, table)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, required=True, help="Folder for the Lab's saved statistics")
    parser.add_argument("--runtime", type=Path, required=True, help="ByteQuay's task runtime folder, read only")
    parser.add_argument("--database", type=Path, required=True, help="ByteQuay's database, read only")
    args = parser.parse_args()
    try:
        refresh(args.folder, args.runtime, args.database)
    except (OSError, sqlite3.Error, ValueError) as problem:
        parser.exit(1, f"Lab statistics could not be refreshed: {problem}\n")
    print("Lab statistics refreshed.")


if __name__ == "__main__":
    main()
