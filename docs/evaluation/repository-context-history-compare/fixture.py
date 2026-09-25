#!/usr/bin/env python3
"""Create, verify, seed, and summarize the history and component evaluation cases."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import textwrap


def source(text):
    return textwrap.dedent(text).lstrip()


COMMON = {
    "README.md": source("""
        # Sensor window grouping

        `contains(start, end, sample)` decides which dashboard windows show a
        reading. Run `python3 -B test_window.py` to check the basic behavior.
    """),
    "test_window.py": source("""
        import unittest
        from window import contains


        class WindowTest(unittest.TestCase):
            def test_common_positions(self):
                self.assertTrue(contains(10, 20, 10))
                self.assertTrue(contains(10, 20, 15))
                self.assertFalse(contains(10, 20, 21))


        if __name__ == "__main__":
            unittest.main()
    """),
}
PRE = dict(COMMON, **{"window.py": "def contains(start, end, sample):\n    return start <= sample < end\n"})
BASE = dict(COMMON, **{"window.py": "def contains(start, end, sample):\n    return start <= sample <= end\n"})
HEAD = PRE
REVISIONS = {"pre": PRE, "base": BASE, "head": HEAD}
TITLES = {
    "pre": "Add basic sensor window grouping",
    "base": "Adjust dashboard window comparison",
    "head": "Simplify window end comparison",
}


def environment():
    return {
        "PATH": os.environ.get("PATH", os.defpath), "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_NO_REPLACE_OBJECTS": "1", "GIT_NO_LAZY_FETCH": "1", "GIT_TERMINAL_PROMPT": "0",
        "GIT_AUTHOR_NAME": "ByteQuay fixture", "GIT_COMMITTER_NAME": "ByteQuay fixture",
        "GIT_AUTHOR_EMAIL": "fixture@example.invalid", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
        "GIT_AUTHOR_DATE": "@0 +0000", "GIT_COMMITTER_DATE": "@0 +0000",
    }


def git(repository, *arguments):
    return git_bytes(repository, *arguments).decode().strip()


def git_bytes(repository, *arguments):
    return subprocess.run(
        ["git", "-c", "core.hooksPath=" + os.devnull, "-c", "commit.gpgSign=false",
         "-c", "core.fsmonitor=false", "-C", str(repository), *arguments],
        env=environment(), capture_output=True, check=True, timeout=10).stdout


def manifest(repository):
    revisions = {}
    for name, files in REVISIONS.items():
        sha = git(repository, "rev-parse", "--verify", "refs/tags/fixture-" + name + "^{commit}")
        actual = git(repository, "ls-tree", "-r", "--name-only", sha).splitlines()
        if actual != sorted(files):
            raise ValueError("Unexpected files at " + name)
        for path, text in files.items():
            if git_bytes(repository, "show", sha + ":" + path) != text.encode():
                raise ValueError("Unexpected source at " + name + "/" + path)
        revisions[name] = sha
    if git(repository, "rev-parse", revisions["base"] + "^") != revisions["pre"]:
        raise ValueError("Historical PR is not the baseline parent")
    if git(repository, "rev-parse", revisions["head"] + "^") != revisions["base"]:
        raise ValueError("Reviewed head is not based on the merged change")
    if git(repository, "rev-parse", "main") != revisions["base"]:
        raise ValueError("main moved away from the frozen baseline")
    return {
        "schemaVersion": 1,
        "revisions": revisions,
        "historicalPr": {"number": 7, "headSha": revisions["base"]},
        "reviewPr": {"number": 8, "baseSha": revisions["base"], "headSha": revisions["head"]},
        "patchSha256": hashlib.sha256(git_bytes(repository, "diff", revisions["base"], revisions["head"])).hexdigest(),
    }


def check_behavior(repository, sha):
    with tempfile.TemporaryDirectory(prefix="bytequay-history-oracle-") as temporary:
        directory = Path(temporary)
        for path in COMMON | {"window.py": ""}:
            (directory / path).write_bytes(subprocess.run(
                ["git", "-C", str(repository), "show", sha + ":" + path],
                env=environment(), capture_output=True, check=True, timeout=10).stdout)
        suite = subprocess.run([sys.executable, "-I", "-S", "-B", "-c",
                                "import runpy,sys;sys.path.insert(0,'.');runpy.run_path('test_window.py',run_name='__main__')"], cwd=directory,
                               env=environment(), capture_output=True, text=True, timeout=10)
        probe = subprocess.run([sys.executable, "-I", "-S", "-B", "-c",
                                "import sys;sys.path.insert(0,'.');from window import contains;print(contains(10,20,20))"],
                               cwd=directory, env=environment(), capture_output=True, text=True, timeout=10)
        return {"suiteExit": suite.returncode, "suiteOutput": suite.stderr.strip(),
                "boundaryProbeExit": probe.returncode, "containsSharedEnd": probe.stdout.strip() == "True"}


def verify(output):
    repository = output / "repository"
    if repository.is_symlink() or not (repository / ".git").is_dir():
        raise ValueError("Expected a regular local fixture repository")
    data = manifest(repository)
    if json.loads((output / "manifest.json").read_text()) != data:
        raise ValueError("Fixture manifest changed")
    if git(repository, "remote") or git(repository, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Fixture repository has a remote or local changes")
    if git(repository, "rev-parse", "HEAD") != data["revisions"]["base"]:
        raise ValueError("Fixture checkout is not the baseline")
    checks = {name: check_behavior(repository, sha) for name, sha in data["revisions"].items()}
    if any(result["suiteExit"] or result["boundaryProbeExit"] for result in checks.values()):
        raise ValueError("A fixed behavior check failed")
    if [checks[name]["containsSharedEnd"] for name in ("pre", "base", "head")] != [False, True, False]:
        raise ValueError("The boundary contrast changed")
    return {"scope": "Fixture integrity and behavior only; human review-quality judgment is not included",
            "manifest": data, "checks": checks}


def create(output):
    output.mkdir()  # Refuse any existing path, including an empty directory.
    repository = output / "repository"
    repository.mkdir()
    template = output / "empty-git-template"
    template.mkdir()
    git(repository, "init", "-q", "--initial-branch=main", "--object-format=sha1", "--template=" + str(template))
    for name, files in REVISIONS.items():
        for path, text in files.items():
            (repository / path).write_text(text, encoding="utf-8")
        git(repository, "add", "--", *sorted(files))
        git(repository, "commit", "-qm", TITLES[name])
        git(repository, "tag", "fixture-" + name)
    git(repository, "reset", "--hard", "fixture-base")
    (output / "manifest.json").write_text(json.dumps(manifest(repository), indent=2) + "\n")
    return verify(output)


def create_component(output):
    output.mkdir()
    repository = output / "repository"
    repository.mkdir()
    (repository / "sensors").mkdir()
    template = output / "empty-git-template"
    template.mkdir()
    git(repository, "init", "-q", "--initial-branch=main", "--object-format=sha1", "--template=" + str(template))
    readme = "# Sensor readings\n\n`contains(start, end, sample)` decides which dashboard panels show a reading. Run `python3 -B sensors/test_window.py` to check the basic behavior.\n"
    test = """import unittest
from window import contains


class WindowTest(unittest.TestCase):
    def test_common_positions(self):
        self.assertTrue(contains(10, 20, 10))
        self.assertTrue(contains(10, 20, 15))
        self.assertFalse(contains(10, 20, 21))


if __name__ == "__main__":
    unittest.main()
"""
    revisions = {}
    for name, end, title in (
            ("pre", "<", "Add basic sensor grouping"),
            ("base", "<=", "Adjust dashboard comparison"),
            ("head", "<", "Simplify end comparison")):
        (repository / "README.md").write_text(readme)
        (repository / "sensors" / "window.py").write_text("def contains(start, end, sample):\n    return start <= sample " + end + " end\n")
        (repository / "sensors" / "test_window.py").write_text(test)
        git(repository, "add", "--", "README.md", "sensors/window.py", "sensors/test_window.py")
        git(repository, "commit", "-qm", title)
        git(repository, "tag", "fixture-" + name)
        revisions[name] = git(repository, "rev-parse", "HEAD")
    git(repository, "reset", "--hard", "fixture-base")
    component_manifest = {
        "revisions": revisions,
        "historicalPr": {"number": 7, "headSha": revisions["base"]},
        "developmentHead": revisions["head"],
        "historyPath": "sensors/window.py",
        "test": "python3 -B sensors/test_window.py",
    }
    (output / "manifest.json").write_text(json.dumps(component_manifest, indent=2) + "\n")
    return component_manifest


def insert(database, table, values):
    columns = ", ".join(values)
    placeholders = ", ".join("?" for _ in values)
    database.execute(f"INSERT INTO {table} ({columns}) VALUES ({placeholders})", tuple(values.values()))


def seed(database_path, corpus, repository, remote):
    db_path = database_path.resolve(strict=True)
    if not db_path.is_relative_to(Path("/private/tmp").resolve()):
        raise ValueError("Only a disposable database under /private/tmp may be seeded")
    case = json.loads((Path(__file__).parent / "case.json").read_text())
    saved_manifest = json.loads((corpus / "manifest.json").read_text())
    if case["revisions"] != saved_manifest["revisions"]:
        raise ValueError("The generated corpus does not match the saved case")
    if git(corpus / "repository", "rev-parse", "HEAD") != case["revisions"]["base"]:
        raise ValueError("The generated corpus is not at its frozen baseline")
    now = datetime.now(timezone.utc)
    now_iso = now.isoformat().replace("+00:00", "Z")
    now_db = now.strftime("%Y-%m-%d %H:%M:%S.%f")
    historical = case["historicalPr"]
    reviewed = case["reviewPr"]
    database = sqlite3.connect(f"file:{db_path}?mode=rw", uri=True, timeout=30)
    if database.execute("SELECT COUNT(*) FROM watched_repo_workspaces").fetchone()[0]:
        raise ValueError("The fixture database already has workspaces")
    with database:
        insert(database, "watched_repo_workspaces", {
            "display_order": 0, "watched": 1, "created_at": now_db, "last_code_sync_at": now_db,
            "last_metadata_sync_at": now_db, "monitored_repository_id": 107, "selected_repository_id": 107,
            "updated_at": now_db, "default_branch": "main", "id": "history-case",
            "local_repository_path": str(repository.resolve(strict=True)), "monitored_owner": "contextfixture",
            "monitored_repo": "history-case", "name": "Isolated history comparison",
            "origin_clone_url": str(remote.resolve(strict=True)), "selected_owner": "contextfixture",
            "selected_repo": "history-case", "topology": "DIRECT",
        })
        for number, data, base_sha, head_sha, state, updated_at in (
                (7, historical, case["revisions"]["pre"], case["revisions"]["base"], "merged", historical["mergedAt"]),
                (8, reviewed, case["revisions"]["base"], case["revisions"]["head"], "open", now_iso)):
            patch = git(corpus / "repository", "diff", "--no-ext-diff", "--no-textconv", base_sha, head_sha, "--", "window.py")
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
    return {"workspaceId": "history-case", "repository": case["repository"],
            "historicalPr": 7, "reviewPr": 8, "baseSha": case["revisions"]["base"],
            "headSha": case["revisions"]["head"]}


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def turn_span(events):
    lifecycle = [event for event in events if event["type"] == "lifecycle"
                 and event["payload"].get("phase") in ("TURN_STARTED", "TURN_COMPLETED")]
    start = datetime.fromisoformat(lifecycle[0]["occurredAt"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(lifecycle[-1]["occurredAt"].replace("Z", "+00:00"))
    return round((end - start).total_seconds(), 1)


def arm(root, expected_enabled, case):
    review = read(root / "final-review.json")
    events = read(root / "events.json")
    usage = read(root / "usage.json")
    run = review["runs"][-1]
    context = run["context"]
    assert run["state"] == "ACCEPTED"
    assert run["baseSha"] == case["revisions"]["base"]
    assert run["headSha"] == case["revisions"]["head"]
    assert context["enabled"] is expected_enabled
    provider_usage = [event["payload"] for event in events if event["type"] == "usage"
                      and event["payload"]["semantics"] == "FINAL_FOR_INVOCATION"]
    return {
        "reviewId": review["id"],
        "runId": run["id"],
        "runState": run["state"],
        "coverageComplete": run["coverage"]["complete"],
        "frozenBaseSha": run["baseSha"],
        "frozenHeadSha": run["headSha"],
        "taskModel": review["task"]["model"],
        "findings": [{
            "title": finding["title"], "body": finding["body"],
            "severity": finding["severity"], "path": finding["path"],
            "line": finding["startLine"], "headSha": finding["validatedHeadSha"],
            "evidence": [{"source": item["source"], "explanation": item["explanation"]}
                         for item in finding["evidence"]],
        } for finding in review["findings"]],
        "context": {
            "enabled": context["enabled"],
            "selectedNotes": [{"entryId": entry["entryId"], "version": entry["entryVersion"],
                               "inclusion": entry["inclusion"], "applicability": entry["applicability"],
                               "matchReasons": entry["matchReasons"]}
                              for entry in context["selectedEntries"]],
            "bootstrapUtf8Bytes": context["bootstrap"]["utf8Bytes"] if context["bootstrap"] else 0,
            "deliveries": [{"utf8Bytes": item["utf8Bytes"],
                            "outcome": run["contextDeliveryStates"][item["turnId"]]["outcome"]}
                           for item in context["deliveries"]],
            "dynamicNoteReads": len(run["contextNoteReads"]),
            "sourceReads": len(context["sourceReads"]),
        },
        "usageDashboard": {
            "trackingSince": usage["trackingSince"],
            "coverage": usage["coverage"], "summary": usage["summary"],
            "entries": [{"entryId": entry["entryId"], "currentVersionCounts": entry["currentVersionCounts"]}
                        for entry in usage["entries"]],
        },
        "provider": {
            "completedTurns": len(provider_usage),
            "turnSpanSeconds": turn_span(events),
            "inputTokens": sum(item["inputTokens"] for item in provider_usage),
            "cachedInputTokens": sum(item["cachedInputTokens"] for item in provider_usage),
            "outputTokens": sum(item["outputTokens"] for item in provider_usage),
            "reportedCost": round(sum(item["cost"] for item in provider_usage), 6),
            "rateLimitWarnings": sum(event["type"] == "lifecycle" and
                                     event["payload"].get("phase") == "RATE_LIMITED" for event in events),
        },
    }


def summarize(fixture, output):
    case = read(output / "case.json")
    build = read(fixture / "learn-latest.json")["build"]
    notebook = read(fixture / "notebook-learned.json")
    attempt = build["results"][0]
    note = next(entry for entry in notebook["entries"] if entry["id"] == "knowledge:project")
    assert build["task"]["state"] == "COMPLETED" and attempt["outcome"] == "READY"
    assert note["version"] == 1 and note["kind"] == "KNOWLEDGE"
    assert any(claim["basis"] == "RECORDED_DECISION" for claim in note["claims"])
    assert any(item["kind"] == "HISTORY" and item["reference"].get("number") == 7
               for item in attempt["checkedReads"])
    write(output / "learn-build.json", {
        "taskState": build["task"]["state"], "baselineSha": build["baselineSha"],
        "selectedHistory": build["history"], "outcome": attempt["outcome"],
        "noteIds": attempt["noteIds"], "explanation": attempt["explanation"],
        "checkedReads": [{"kind": item["kind"], "reference": item["reference"],
                          "truncated": item["truncated"]} for item in attempt["checkedReads"]],
        "gaps": build["gaps"],
    })
    write(output / "learned-notebook.json", {
        "schemaVersion": notebook["schemaVersion"], "revision": notebook["revision"],
        "scope": {"id": notebook["scopes"][0]["id"],
                  "preparationOutcome": notebook["scopes"][0]["preparation"]["outcome"]},
        "note": note,
        "sourceEvidence": [{"id": item["id"], "reference": item["reference"],
                            "excerpt": item["excerpt"], "truncated": item["truncated"]}
                           for item in notebook["sourceEvidence"]],
    })
    write(output / "review-results.json", {
        "schemaVersion": 1,
        "qualityStatus": "UNSCORED_HUMAN_ORACLE_PENDING",
        "fixture": {"repository": case["repository"], "historicalPr": 7, "reviewPr": 8,
                    "baseSha": case["revisions"]["base"], "headSha": case["revisions"]["head"]},
        "provider": {"name": "Claude", "model": "Sonnet", "effort": "High", "cliVersion": "2.1.281"},
        "backendJarSha256": hashlib.sha256((fixture / "backend.jar").read_bytes()).hexdigest(),
        "arms": {"off": arm(fixture / "arms/off", False, case),
                 "warmOn": arm(fixture / "arms/on", True, case)},
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    for name in ("create", "verify", "component"):
        commands.add_parser(name).add_argument("output", type=Path)
    seed_command = commands.add_parser("seed")
    seed_command.add_argument("database", type=Path, help="Fresh Hibernate-created SQLite file under /private/tmp")
    seed_command.add_argument("corpus", type=Path, help="Directory created by the create command")
    seed_command.add_argument("repository", type=Path, help="Managed workspace checkout of the local remote")
    seed_command.add_argument("remote", type=Path, help="Local bare Git remote")
    summary_command = commands.add_parser("summarize")
    summary_command.add_argument("fixture_root", type=Path)
    summary_command.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    try:
        if args.operation == "create":
            result = create(args.output.resolve())
        elif args.operation == "verify":
            result = verify(args.output.resolve())
        elif args.operation == "component":
            result = create_component(args.output.resolve())
        elif args.operation == "seed":
            result = seed(args.database, args.corpus, args.repository, args.remote)
        else:
            summarize(args.fixture_root, args.output_dir)
            return
    except (OSError, ValueError, sqlite3.Error, subprocess.SubprocessError) as error:
        parser.exit(1, str(error) + "\n")
    print(json.dumps(result, indent=2 if args.operation != "seed" else None))


if __name__ == "__main__":
    main()
