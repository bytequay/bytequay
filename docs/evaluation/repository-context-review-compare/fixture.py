#!/usr/bin/env python3
"""Prepare and summarize the disposable repository-context review comparison."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import subprocess


def git(repository, *arguments):
    return subprocess.check_output(["git", "-C", str(repository), *arguments], text=True).strip()


def insert(connection, table, values):
    columns = ", ".join(values)
    placeholders = ", ".join("?" for _ in values)
    connection.execute(f"INSERT INTO {table} ({columns}) VALUES ({placeholders})", tuple(values.values()))


def seed(arguments):
    database_path = arguments.database.resolve(strict=True)
    if not database_path.is_relative_to(Path("/private/tmp").resolve()):
        raise ValueError("Only a disposable database under /private/tmp may be seeded")
    manifest = json.loads((arguments.corpus / "manifest.json").read_text())
    base = manifest["revisions"]["base"]["commit"]
    head = manifest["revisions"]["bug"]["commit"]
    patch = git(arguments.corpus / "repository", "diff", "--no-ext-diff", "--no-textconv", base, head, "--", "window.py")
    hunk = patch[patch.index("@@ "):]
    instant = datetime.now(timezone.utc)
    now = instant.isoformat().replace("+00:00", "Z")
    database_time = instant.strftime("%Y-%m-%d %H:%M:%S.%f")
    connection = sqlite3.connect(f"file:{database_path}?mode=rw", uri=True, timeout=30)
    if connection.execute("SELECT COUNT(*) FROM watched_repo_workspaces").fetchone()[0]:
        raise ValueError("The fixture database already has workspaces")
    with connection:
        insert(connection, "watched_repo_workspaces", {
            "display_order": 0, "watched": 1, "created_at": database_time, "last_code_sync_at": database_time,
            "last_metadata_sync_at": database_time, "monitored_repository_id": 101, "selected_repository_id": 101,
            "updated_at": database_time, "default_branch": "main", "id": "review-case",
            "local_repository_path": str(arguments.repository.resolve()), "monitored_owner": "contextfixture",
            "monitored_repo": "review-case", "name": "Isolated boundary review",
            "origin_clone_url": str(arguments.remote.resolve()), "selected_owner": "contextfixture",
            "selected_repo": "review-case", "topology": "DIRECT",
        })
        insert(connection, "pull_requests", {
            "additions": 1, "comment_count": 0, "deletions": 1, "draft": 0, "mergeable": 1,
            "number": 1, "id": 1, "author": "fixture-reviewer", "ci_status": "NONE",
            "created_at": now, "head_ref": "boundary-change",
            "html_url": "https://github.com/contextfixture/review-case/pull/1",
            "labels": "[]", "origin": "OTHER", "repo": "contextfixture/review-case",
            "state": "open", "synced_at": now, "title": "Update window boundary comparison",
            "updated_at": now,
        })
        insert(connection, "pr_detail", {
            "additions": 1, "changed_files": 1, "deletions": 1, "draft": 0, "mergeable": 1,
            "viewer_can_write": 0, "pr_id": 1, "author": "fixture-reviewer", "base_ref": "main",
            "body": "Adjust the end comparison in contains.", "created_at": now,
            "head_ref": "boundary-change", "head_sha": head,
            "html_url": "https://github.com/contextfixture/review-case/pull/1",
            "labels": "[]", "state": "open", "synced_at": now,
            "title": "Update window boundary comparison", "unavailable_sections": "[]",
            "updated_at": now,
        })
        insert(connection, "pr_diff", {
            "pr_id": 1, "commits": "[]", "file_contents": "{}",
            "files": json.dumps([{"filename": "window.py", "status": "modified", "additions": 1,
                                  "deletions": 1, "patch": hunk + "\n"}]), "id": "1:default",
        })
    connection.close()
    print(json.dumps({"workspaceId": "review-case", "repo": "contextfixture/review-case", "pr": 1,
                      "baseSha": base, "headSha": head}))


def split(arguments):
    arguments.output_root.mkdir(parents=True, exist_ok=True)
    source = sqlite3.connect(f"file:{arguments.source_database.resolve()}?mode=ro", uri=True)
    try:
        for arm in ("off", "on"):
            root = arguments.output_root / arm
            root.mkdir()  # Refuse to replace earlier evidence.
            repository = root / "workspaces" / "review-case" / "repository"
            repository.parent.mkdir(parents=True)
            subprocess.run(["git", "clone", "--quiet", str(arguments.remote.resolve()), str(repository)], check=True)
            if subprocess.check_output(["git", "-C", str(repository), "tag", "-l"], text=True).strip():
                raise ValueError("Evaluator tags must not be exposed to review arms")
            subprocess.run(["git", "-C", str(repository), "remote", "set-url", "origin", arguments.https_url], check=True)
            subprocess.run(["git", "-C", str(repository), "config", f"http.{arguments.https_url}.sslCAInfo",
                            str(arguments.ca_certificate.resolve())], check=True)
            (repository.parent / ".bytequay-workspace").write_text("review-case\n101\n")
            database = root / "fixture.db"
            destination = sqlite3.connect(database)
            source.backup(destination)
            with destination:
                destination.execute("UPDATE watched_repo_workspaces SET local_repository_path = ?, origin_clone_url = ? WHERE id = 'review-case'",
                                    (str(repository.resolve()), arguments.https_url))
                # The copied Learn task is complete and its worktree was removed. Its old
                # runtime launch marker belongs to the source runtime, not either new arm.
                stale = destination.execute("""
                    SELECT launch.id FROM agent_process_launch AS launch
                    JOIN agent_turn AS turn ON turn.id = launch.owner_id
                    JOIN agent_task AS task ON task.id = turn.task_id
                    WHERE launch.owner_kind = 'CLAUDE_TURN'
                      AND turn.state = 'COMPLETED'
                      AND task.state = 'COMPLETED'
                      AND task.worktree_removed_at IS NOT NULL
                    """).fetchall()
                if destination.execute("SELECT count(*) FROM agent_process_launch").fetchone()[0] != len(stale):
                    raise ValueError("A copied provider launch is not safely completed")
                destination.executemany("DELETE FROM agent_process_launch WHERE id = ?", stale)
            destination.close()
    finally:
        source.close()


def elapsed_seconds(events):
    turns = [event for event in events if event["type"] == "lifecycle"
             and event["payload"]["phase"] in ("TURN_STARTED", "TURN_COMPLETED")]
    start = datetime.fromisoformat(turns[0]["occurredAt"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(turns[-1]["occurredAt"].replace("Z", "+00:00"))
    return round((end - start).total_seconds(), 1)


def arm(root, enabled):
    review = json.loads((root / "final-review.json").read_text())
    events = json.loads((root / "events.json").read_text())
    usage = json.loads((root / "usage.json").read_text())
    run = review["runs"][-1]
    context = run["context"]
    provider_usage = [event["payload"] for event in events if event["type"] == "usage"
                      and event["payload"]["semantics"] == "FINAL_FOR_INVOCATION"]
    selected = [{"entryId": entry["entryId"], "version": entry["entryVersion"],
                 "inclusion": entry["inclusion"], "applicability": entry["applicability"],
                 "matchReasons": entry["matchReasons"]}
                for entry in context["selectedEntries"]]
    findings = [{"title": finding["title"], "severity": finding["severity"],
                 "path": finding["path"], "line": finding["startLine"],
                 "body": finding["body"],
                 "evidenceExplanations": [item["explanation"] for item in finding["evidence"]]}
                for finding in review["findings"]]
    return {
        "reviewId": review["id"], "runId": run["id"], "runState": run["state"],
        "contextEnabled": enabled, "coverage": run["coverage"],
        "summary": run["summary"], "findings": findings,
        "context": {
            "selectedEntries": selected,
            "bootstrapUtf8Bytes": context["bootstrap"]["utf8Bytes"] if context["bootstrap"] else 0,
            "deliveries": [{"turnId": item["turnId"], "utf8Bytes": item["utf8Bytes"],
                            "packetDigest": item["packetDigest"],
                            "outcome": run["contextDeliveryStates"][item["turnId"]]["outcome"]}
                           for item in context["deliveries"]],
            "dynamicNoteReads": run["contextNoteReads"],
        },
        "usageDashboard": {
            "summary": usage["summary"],
            "entries": [{"entryId": item["entryId"], "currentVersionCounts": item["currentVersionCounts"]}
                        for item in usage["entries"]],
        },
        "provider": {
            "completedTurns": len(provider_usage),
            "turnSpanSeconds": elapsed_seconds(events),
            "inputTokens": sum(item["inputTokens"] for item in provider_usage),
            "cachedInputTokens": sum(item["cachedInputTokens"] for item in provider_usage),
            "outputTokens": sum(item["outputTokens"] for item in provider_usage),
            "reportedCost": round(sum(item["cost"] for item in provider_usage), 6),
            "rateLimitWarnings": sum(event["type"] == "lifecycle" and
                                     event["payload"]["phase"] == "RATE_LIMITED" for event in events),
        },
    }


def summarize(arguments):
    result = {
        "fixture": {
            "repository": "contextfixture/review-case", "pullRequest": 1,
            "baseSha": "99dac517a7758debe9005eec0b910109c7ce8d32",
            "headSha": "a8cd427b4b6b04af139b329269fdc3aa231d0c2e",
            "expectedBug": "window.py accepts the excluded end boundary",
        },
        "provider": {"name": "Claude", "model": "Sonnet", "effort": "high", "cliVersion": "2.1.281"},
        "backendJarSha256": "1204933b67c0aa171ba4f10d224a6c2bd42e1b158f898e785feefd441a58ad2d",
        "arms": {"off": arm(arguments.arms_root / "off", False),
                 "warmOn": arm(arguments.arms_root / "on", True)},
    }
    arguments.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    seed_parser = commands.add_parser("seed", help="Seed the cached PR in an initialized disposable database")
    seed_parser.add_argument("database", type=Path)
    seed_parser.add_argument("corpus", type=Path, help="Generated repository-context cases directory")
    seed_parser.add_argument("repository", type=Path, help="ByteQuay managed workspace repository")
    seed_parser.add_argument("remote", type=Path, help="Local bare Git remote")
    seed_parser.set_defaults(run=seed)

    split_parser = commands.add_parser("split", help="Copy a completed Learn fixture into Off and warm-On arms")
    split_parser.add_argument("source_database", type=Path)
    split_parser.add_argument("output_root", type=Path)
    split_parser.add_argument("remote", type=Path)
    split_parser.add_argument("https_url", help="Safe localhost HTTPS URL serving the same bare remote")
    split_parser.add_argument("ca_certificate", type=Path)
    split_parser.set_defaults(run=split)

    summary_parser = commands.add_parser("summarize", help="Retain fixture review results, context receipts and provider usage")
    summary_parser.add_argument("arms_root", type=Path)
    summary_parser.add_argument("output", type=Path)
    summary_parser.set_defaults(run=summarize)

    arguments = parser.parse_args()
    arguments.run(arguments)


if __name__ == "__main__":
    main()
