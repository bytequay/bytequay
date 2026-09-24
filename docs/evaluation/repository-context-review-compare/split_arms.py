#!/usr/bin/env python3
"""Fork a completed Learn fixture into independent Off and warm-On databases."""

import argparse
from pathlib import Path
import sqlite3
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_database", type=Path)
    parser.add_argument("output_root", type=Path)
    parser.add_argument("remote", type=Path)
    parser.add_argument("https_url", help="Safe localhost HTTPS URL serving the same bare remote")
    parser.add_argument("ca_certificate", type=Path)
    arguments = parser.parse_args()
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


if __name__ == "__main__":
    main()
