#!/usr/bin/env python3
"""Create or verify the isolated repository for a closed-issue learning case."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


FILES = {
    "README.md": "# Sensor windows\n\n`contains(start, end, sample)` chooses the dashboard windows that show a reading.\nRun `python3 -B test_window.py` for the ordinary checks.\n",
    "window.py": "def contains(start, end, sample):\n    return start <= sample <= end\n",
    "test_window.py": (
        "import unittest\nfrom window import contains\n\n\nclass WindowTest(unittest.TestCase):\n"
        "    def test_common_positions(self):\n        self.assertTrue(contains(10, 20, 10))\n"
        "        self.assertTrue(contains(10, 20, 15))\n        self.assertFalse(contains(10, 20, 21))\n\n\n"
        "if __name__ == '__main__':\n    unittest.main()\n"
    ),
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


def git(repository, *args):
    return subprocess.run(
        ["git", "-c", "core.hooksPath=" + os.devnull, "-c", "commit.gpgSign=false",
         "-c", "core.fsmonitor=false", "-C", str(repository), *args],
        env=environment(), capture_output=True, check=True, timeout=10).stdout


def manifest(repository):
    commit = git(repository, "rev-parse", "HEAD").decode().strip()
    if git(repository, "ls-tree", "-r", "--name-only", commit).decode().splitlines() != sorted(FILES):
        raise ValueError("Unexpected frozen source paths")
    files = {}
    for path, text in FILES.items():
        data = git(repository, "show", commit + ":" + path)
        if data != text.encode():
            raise ValueError("Frozen source changed: " + path)
        files[path] = {"blobId": git(repository, "rev-parse", commit + ":" + path).decode().strip(),
                       "sha256": hashlib.sha256(data).hexdigest()}
    return {"schemaVersion": 1, "repository": "contextfixture/issue-case", "repositoryId": 207,
            "workspaceId": "issue-case", "baselineSha": commit, "issueNumber": 12, "files": files}


def verify(output):
    repository = output / "repository"
    if repository.is_symlink() or not (repository / ".git").is_dir():
        raise ValueError("Expected a regular local fixture repository")
    expected = manifest(repository)
    if json.loads((output / "manifest.json").read_text()) != expected:
        raise ValueError("Fixture manifest changed")
    if git(repository, "remote").strip() or git(repository, "status", "--porcelain", "--untracked-files=all").strip():
        raise ValueError("Fixture repository must have no remote or changes")
    with tempfile.TemporaryDirectory(prefix="bytequay-issue-oracle-") as temporary:
        directory = Path(temporary)
        for path, text in FILES.items():
            (directory / path).write_text(text)
        suite = subprocess.run([sys.executable, "-I", "-S", "-B", "-c",
                                "import runpy,sys;sys.path.insert(0,'.');runpy.run_path('test_window.py',run_name='__main__')"],
                               cwd=directory, env=environment(), capture_output=True, text=True, timeout=10)
        probe = subprocess.run([sys.executable, "-I", "-S", "-B", "-c",
                                "import sys;sys.path.insert(0,'.');from window import contains;print(contains(10,20,20))"],
                               cwd=directory, env=environment(), capture_output=True, text=True, timeout=10)
    if suite.returncode or probe.returncode or probe.stdout.strip() != "True":
        raise ValueError("Frozen behavior check failed")
    return {"scope": "Fixture integrity and code behavior only", "manifest": expected,
            "ordinarySuiteExit": suite.returncode, "sharedEndIncluded": True}


def create(output):
    output.mkdir()
    repository = output / "repository"
    repository.mkdir()
    template = output / "empty-git-template"
    template.mkdir()
    git(repository, "init", "-q", "--initial-branch=main", "--object-format=sha1", "--template=" + str(template))
    for path, text in FILES.items():
        (repository / path).write_text(text)
    git(repository, "add", "--", *sorted(FILES))
    git(repository, "commit", "-qm", "Add sensor window grouping")
    (output / "manifest.json").write_text(json.dumps(manifest(repository), indent=2) + "\n")
    return verify(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("create", "verify"))
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        result = create(args.output.resolve()) if args.operation == "create" else verify(args.output.resolve())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, str(error) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
