#!/usr/bin/env python3
"""Seed only a disposable ByteQuay database with the two local fixture PRs."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import subprocess


def git(repository, *arguments):
    return subprocess.check_output(
        ["git", "-c", "core.fsmonitor=false", "-C", str(repository), *arguments], text=True).strip()


def insert(database, table, values):
    columns = ", ".join(values)
    placeholders = ", ".join("?" for _ in values)
    database.execute(f"INSERT INTO {table} ({columns}) VALUES ({placeholders})", tuple(values.values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path, help="Fresh Hibernate-created SQLite file under /private/tmp")
    parser.add_argument("corpus", type=Path, help="Directory created by fixture.py")
    parser.add_argument("repository", type=Path, help="Managed workspace checkout of the local remote")
    parser.add_argument("remote", type=Path, help="Local bare Git remote")
    args = parser.parse_args()
    db_path = args.database.resolve(strict=True)
    if not db_path.is_relative_to(Path("/private/tmp").resolve()):
        parser.error("Only a disposable database under /private/tmp may be seeded")
    case = json.loads((Path(__file__).parent / "case.json").read_text())
    manifest = json.loads((args.corpus / "manifest.json").read_text())
    if case["revisions"] != manifest["revisions"]:
        parser.error("The generated corpus does not match the saved case")
    if git(args.corpus / "repository", "rev-parse", "HEAD") != case["revisions"]["base"]:
        parser.error("The generated corpus is not at its frozen baseline")
    now = datetime.now(timezone.utc)
    now_iso = now.isoformat().replace("+00:00", "Z")
    now_db = now.strftime("%Y-%m-%d %H:%M:%S.%f")
    historical = case["historicalPr"]
    reviewed = case["reviewPr"]
    database = sqlite3.connect(f"file:{db_path}?mode=rw", uri=True, timeout=30)
    if database.execute("SELECT COUNT(*) FROM watched_repo_workspaces").fetchone()[0]:
        parser.error("The fixture database already has workspaces")
    with database:
        insert(database, "watched_repo_workspaces", {
            "display_order": 0, "watched": 1, "created_at": now_db, "last_code_sync_at": now_db,
            "last_metadata_sync_at": now_db, "monitored_repository_id": 107, "selected_repository_id": 107,
            "updated_at": now_db, "default_branch": "main", "id": "history-case",
            "local_repository_path": str(args.repository.resolve(strict=True)), "monitored_owner": "contextfixture",
            "monitored_repo": "history-case", "name": "Isolated history comparison",
            "origin_clone_url": str(args.remote.resolve(strict=True)), "selected_owner": "contextfixture",
            "selected_repo": "history-case", "topology": "DIRECT",
        })
        for number, data, base_sha, head_sha, state, updated_at in (
                (7, historical, case["revisions"]["pre"], case["revisions"]["base"], "merged", historical["mergedAt"]),
                (8, reviewed, case["revisions"]["base"], case["revisions"]["head"], "open", now_iso)):
            patch = git(args.corpus / "repository", "diff", "--no-ext-diff", "--no-textconv", base_sha, head_sha, "--", "window.py")
            hunk = patch[patch.index("@@ "):]
            insert(database, "pull_requests", {
                "additions": 1, "comment_count": 1 if number == 7 else 0, "deletions": 1, "draft": 0,
                "mergeable": 1, "number": number, "id": number, "author": "fixture-author", "ci_status": "NONE",
                "created_at": updated_at, "head_ref": f"case-{number}",
                "html_url": f"https://github.com/contextfixture/history-case/pull/{number}",
                "labels": "[]", "origin": "OTHER", "repo": case["repository"], "state": state,
                "closed_at": historical["mergedAt"] if number == 7 else None,
                "merged_at": historical["mergedAt"] if number == 7 else None,
                "synced_at": now_iso, "title": data["title"], "updated_at": updated_at,
            })
            insert(database, "pr_detail", {
                "additions": 1, "changed_files": 1, "deletions": 1, "draft": 0, "mergeable": 1,
                "viewer_can_write": 0, "pr_id": number, "author": "fixture-author", "base_ref": "main",
                "body": data["body"], "created_at": updated_at, "head_ref": f"case-{number}",
                "head_sha": head_sha, "html_url": f"https://github.com/contextfixture/history-case/pull/{number}",
                "labels": "[]", "state": "closed" if number == 7 else "open", "synced_at": now_iso,
                "title": data["title"], "unavailable_sections": "[]", "updated_at": updated_at,
                "merged_at": historical["mergedAt"] if number == 7 else None,
            })
            insert(database, "pr_diff", {
                "pr_id": number, "commits": "[]", "file_contents": "{}",
                "files": json.dumps([{"filename": "window.py", "status": "modified", "additions": 1,
                                      "deletions": 1, "patch": hunk + "\n"}]), "id": f"{number}:default",
            })
        discussion = historical["discussion"]
        insert(database, "pr_timeline", {
            "pr_id": 7, "event": discussion["event"], "actor": discussion["actor"],
            "timestamp": "2026-01-15T09:55:00Z", "body": discussion["body"],
            "github_id": discussion["githubId"],
        })
    database.close()
    print(json.dumps({"workspaceId": "history-case", "repository": case["repository"],
                      "historicalPr": 7, "reviewPr": 8, "baseSha": case["revisions"]["base"],
                      "headSha": case["revisions"]["head"]}))


if __name__ == "__main__":
    main()
