#!/usr/bin/env python3
"""Create or verify a small, history-dependent repository-context review case."""

import argparse
import hashlib
import json
import os
from pathlib import Path
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
