#!/usr/bin/env python3
"""Refresh the desktop Lab's saved statistics from local application data, without starting agents or a monitor."""

import argparse
from datetime import datetime, timezone
from pathlib import Path
import sqlite3

import activity
import app_logs
import context
import memory
import records
import saved
import tuning


def refresh(folder, runtime, database, logs=app_logs.LOGS):
    """Keep agent snapshots together; a database failure must still let the health page explain what is known."""
    now = datetime.now(timezone.utc)
    health = app_logs.analyze(logs, now.timestamp()) | {"activityProblem": None}
    try:
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
    except (OSError, sqlite3.Error, ValueError):
        health["activityProblem"] = "Application records could not be refreshed; agent statistics may be stale."
        saved.write(folder, app_logs.FILE, health)
        return health
    # Empty tables replace older results too: deleting task history must not leave old statistics on screen.
    for name, table in tables.items():
        saved.write(folder, name, table)
    saved.write(folder, app_logs.FILE, health)
    return health


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, required=True, help="Folder for the Lab's saved statistics")
    parser.add_argument("--runtime", type=Path, required=True, help="ByteQuay's task runtime folder, read only")
    parser.add_argument("--database", type=Path, required=True, help="ByteQuay's database, read only")
    parser.add_argument("--logs", type=Path, default=app_logs.LOGS, help="Folder containing backend.log and its rotated files, read only")
    args = parser.parse_args()
    try:
        health = refresh(args.folder, args.runtime, args.database, args.logs)
    except (OSError, sqlite3.Error, ValueError) as problem:
        parser.exit(1, f"Lab statistics could not be refreshed: {problem}\n")
    print(health["activityProblem"] or "Lab statistics refreshed.")


if __name__ == "__main__":
    main()
