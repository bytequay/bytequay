#!/usr/bin/env python3
"""Seed one disposable ByteQuay database with the local boundary-change PR."""

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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("corpus", type=Path, help="Generated repository-context cases directory")
    parser.add_argument("repository", type=Path, help="ByteQuay managed workspace repository")
    parser.add_argument("remote", type=Path, help="Local bare Git remote")
    arguments = parser.parse_args()
    manifest = json.loads((arguments.corpus / "manifest.json").read_text())
    base = manifest["revisions"]["base"]["commit"]
    head = manifest["revisions"]["bug"]["commit"]
    patch = git(arguments.corpus / "repository", "diff", "--no-ext-diff", "--no-textconv", base, head, "--", "window.py")
    hunk = patch[patch.index("@@ "):]
    instant = datetime.now(timezone.utc)
    now = instant.isoformat().replace("+00:00", "Z")
    database_time = instant.strftime("%Y-%m-%d %H:%M:%S.%f")
    connection = sqlite3.connect(arguments.database, timeout=30)
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


if __name__ == "__main__":
    main()
