#!/usr/bin/env python3
"""Seed a fresh disposable ByteQuay database with one cached closed-issue activity record."""

import argparse
import json
from pathlib import Path
import sqlite3
import subprocess


ISSUE_UPDATED = "2026-01-15T10:00:00Z"
ISSUE_REFRESHED = "2026-01-15T10:01:00Z"
DISCUSSION = (
    "Outcome after comparing adjacent dashboard panels: close this request as documented behavior, "
    "with no code fix. Keep the end inclusive so a sample at the shared boundary appears in both panels. "
    "A future product decision could change this; closure alone does not prove the implementation is correct."
)


def git(repository, *args):
    return subprocess.check_output(["git", "-c", "core.fsmonitor=false", "-C", str(repository), *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path, help="Fresh Hibernate-created SQLite database under /private/tmp")
    parser.add_argument("corpus", type=Path, help="Directory created by fixture.py")
    parser.add_argument("checkout", type=Path, help="Managed workspace checkout")
    parser.add_argument("remote", help="Read-only localhost HTTPS URL for the local bare Git remote")
    args = parser.parse_args()
    database_path = args.database.resolve(strict=True)
    if not database_path.is_relative_to(Path("/private/tmp").resolve()):
        parser.error("Only a disposable database under /private/tmp may be seeded")
    manifest = json.loads((args.corpus / "manifest.json").read_text())
    if manifest["repository"] != "contextfixture/issue-case" or manifest["repositoryId"] != 207 or manifest["issueNumber"] != 12:
        parser.error("Unexpected fixture identity")
    if git(args.corpus / "repository", "rev-parse", "HEAD") != manifest["baselineSha"]:
        parser.error("The fixture baseline changed")
    checkout = args.checkout.resolve(strict=True)
    if not args.remote.startswith("https://127.0.0.1:") or not args.remote.endswith("/remote.git"):
        parser.error("Only the local HTTPS Git fixture remote is accepted")
    if git(checkout, "rev-parse", "HEAD") != manifest["baselineSha"] or git(checkout, "remote", "get-url", "origin") != args.remote:
        parser.error("Managed checkout does not match the frozen baseline and origin")
    marker = checkout.parent / ".bytequay-workspace"
    if marker.exists():
        parser.error("The managed workspace marker already exists")
    marker.write_text("issue-case\n207\n")
    timeline = [{"githubId": 1201, "event": "commented", "actor": "fixture-maintainer",
                 "timestamp": "2026-01-15T09:55:00Z", "body": DISCUSSION,
                 "payload": {"reactions": [], "comments": []}, "localEventKey": None},
                {"githubId": 1202, "event": "closed", "actor": "fixture-maintainer",
                 "timestamp": ISSUE_UPDATED, "body": "Closed as documented behavior; no code fix was merged.",
                 "payload": {"reactions": [], "comments": []}, "localEventKey": None}]
    database = sqlite3.connect(f"file:{database_path}?mode=rw", uri=True, timeout=30)
    if database.execute("SELECT COUNT(*) FROM watched_repo_workspaces").fetchone()[0]:
        parser.error("The fixture database already has workspaces")
    with database:
        database.execute("""INSERT INTO watched_repo_workspaces
            (display_order, watched, created_at, last_code_sync_at, last_metadata_sync_at,
             monitored_repository_id, selected_repository_id, updated_at, default_branch, id,
             local_repository_path, monitored_owner, monitored_repo, name, origin_clone_url,
             selected_owner, selected_repo, topology)
            VALUES (0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    207, 207, CURRENT_TIMESTAMP, 'main', 'issue-case', ?, 'contextfixture',
                    'issue-case', 'Closed issue fixture', ?, 'contextfixture', 'issue-case', 'DIRECT')""",
                         (str(checkout), args.remote))
        database.execute("""INSERT INTO repository_issue_details
            (id, repository_id, issue_number, timeline, current_type, available_types, refreshed_at)
            VALUES ('207:12', 207, 12, ?, NULL, '[]', ?)""", (json.dumps(timeline), ISSUE_REFRESHED))
    database.close()
    print(json.dumps({"workspaceId": "issue-case", "repository": manifest["repository"],
                      "repositoryId": 207, "issueNumber": 12, "activityItems": len(timeline),
                      "baselineSha": manifest["baselineSha"]}))


if __name__ == "__main__":
    main()
