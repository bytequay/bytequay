#!/usr/bin/env python3
"""Create or verify proposed local review cases; never imports data or starts a provider."""

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


BASE = {
    "README.md": source("""
        # Window and field contracts

        Windows include their start and exclude their end: [start, end).
        Adjacent windows must not both accept their shared boundary.

        Fields retain their original order, including hidden fields. index_of
        reads one immutable map and returns the same index on repeated calls.
        visible_names is a separate filtered view, never an index namespace.
        resolve adds the supplied parent-field offset to the all-fields index.

        Run the dependency-free tests with `python3 -B test_contract.py`.
    """),
    "window.py": "def contains(start, end, value):\n    return start <= value < end\n",
    "fields.py": source("""
        from types import MappingProxyType


        class Fields:
            def __init__(self, fields):
                self._fields = tuple(fields)
                self._indexes = MappingProxyType({name: index for index, (name, hidden) in enumerate(self._fields)})

            def index_of(self, name):
                return self._indexes[name]

            def visible_names(self):
                return tuple(name for name, hidden in self._fields if not hidden)
    """),
    "resolver.py": source("""
        def resolve(fields, name, parent_offset):
            relation_index = fields.index_of(name)
            hierarchy_index = fields.index_of(name) + parent_offset
            return relation_index, hierarchy_index
    """),
    "test_contract.py": source("""
        import unittest

        from fields import Fields
        from resolver import resolve
        from window import contains


        class ContractTest(unittest.TestCase):
            def test_window_end(self):
                self.assertTrue(contains(10, 20, 10))
                self.assertTrue(contains(10, 20, 19))
                self.assertFalse(contains(10, 20, 20), "END_EXCLUSIVE")

            def test_field_indexes(self):
                fields = Fields((("hidden", True), ("value", False)))
                self.assertEqual(fields.visible_names(), ("value",))
                self.assertEqual(fields.index_of("value"), 1)
                self.assertEqual(fields.index_of("value"), 1)
                self.assertEqual(resolve(fields, "value", 3), (1, 4))


        if __name__ == "__main__":
            unittest.main()
    """),
}
REVISIONS = {
    "legacy": dict(BASE, **{
        "README.md": BASE["README.md"].replace(
            "Windows include their start and exclude their end: [start, end).\nAdjacent windows must not both accept their shared boundary.",
            "Windows include both endpoints: [start, end].\nAdjacent windows both accept their shared boundary."),
        "window.py": BASE["window.py"].replace("value < end", "value <= end"),
        "test_contract.py": BASE["test_contract.py"].replace(
            'self.assertFalse(contains(10, 20, 20), "END_EXCLUSIVE")',
            'self.assertTrue(contains(10, 20, 20), "LEGACY_END_INCLUSIVE")'),
    }),
    "base": BASE,
    "bug": dict(BASE, **{"window.py": BASE["window.py"].replace("value < end", "value <= end")}),
    "safe": dict(BASE, **{"resolver.py": BASE["resolver.py"].replace(
        "hierarchy_index = fields.index_of(name) + parent_offset", "hierarchy_index = relation_index + parent_offset")}),
}
ORACLE = "import runpy,sys; sys.path.insert(0,'.'); runpy.run_path('test_contract.py',run_name='__main__')"
COMMIT_TITLES = {
    "legacy": "Define window and field contracts",
    "base": "Use half-open windows",
    "bug": "Update window boundary comparison",
    "safe": "Reuse field index lookup",
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
    return subprocess.run(
        ["git", "-c", "core.hooksPath=" + os.devnull, "-c", "commit.gpgSign=false", "-c", "core.autocrlf=false",
         "-c", "core.fsmonitor=false", "-C", str(repository), *arguments],
        env=environment(), capture_output=True, check=True, timeout=10).stdout


def digest(data):
    return hashlib.sha256(data).hexdigest()


def variants(revisions):
    def note(key, case, claim, revision, path, kind="MEMORY", **extra):
        identity = revisions[revision]["files"][path]
        return dict(key=key, case=case, proposedKind=kind, scopeHint="project", claim=claim,
                    proposedSource=dict(commit=revisions[revision]["commit"], path=path, **identity), **extra)

    return [
        note("correct", "bug", "The end is exclusive; including it assigns a boundary to two windows.", "base", "README.md", kind="KNOWLEDGE"),
        note("false", "safe", "The first index_of call returns a visible-field index and the second an all-fields index; reusing the first misbinds hidden fields.",
             "base", "fields.py", logicalNote="field-index-lesson", draftVersion=1),
        note("stale", "bug", "Both endpoints must be included, including the shared boundary.", "legacy", "README.md", kind="KNOWLEDGE"),
        note("irrelevant", "safe", "Window end boundaries are exclusive.", "base", "README.md"),
        dict(key="missing", case="bug", proposedKind=None, scopeHint="project", instruction="Start with no saved entry or preparation result."),
        note("corrected", "safe", "Every index_of call reads the same immutable all-fields map; reusing its result preserves hidden-field indexes.",
             "base", "fields.py", logicalNote="field-index-lesson", draftVersion=2),
        dict(key="disabled", case="safe", logicalNote="field-index-lesson", instruction="Disable the previously false lesson before a fresh review."),
        dict(key="removed", case="safe", logicalNote="field-index-lesson", instruction="Remove the previously false lesson before a fresh review."),
    ]


def manifest(repository):
    revisions = {}
    for name, expected in REVISIONS.items():
        commit = git(repository, "rev-parse", "--verify", "refs/tags/fixture-" + name + "^{commit}").decode().strip()
        paths = git(repository, "ls-tree", "-r", "--name-only", commit).decode().splitlines()
        if paths != sorted(expected):
            raise ValueError("Unexpected frozen files in " + name)
        files = {}
        for path, text in expected.items():
            data = git(repository, "show", commit + ":" + path)
            if data != text.encode():
                raise ValueError("Frozen source integrity mismatch: " + name + "/" + path)
            files[path] = dict(blobId=git(repository, "rev-parse", commit + ":" + path).decode().strip(), sha256=digest(data), utf8Bytes=len(data))
        revisions[name] = dict(commit=commit, files=files)
    cases = []
    for name, expected_exit in (("bug", 1), ("safe", 0)):
        base, head = revisions["base"]["commit"], revisions[name]["commit"]
        if git(repository, "rev-parse", head + "^").decode().strip() != base:
            raise ValueError("Review head does not have the frozen baseline as its parent")
        patch = git(repository, "diff", "--no-ext-diff", "--no-textconv", base, head)
        cases.append(dict(id=name, baseSha=base, diffBaseSha=base, headSha=head, patchSha256=digest(patch),
                          expectedOracleExit=expected_exit, expectedFailure="END_EXCLUSIVE" if name == "bug" else None))
    return dict(schemaVersion=1, purpose="Proposed cases for human assessment; machine oracles do not establish review quality.",
                repository="repository", revisions=revisions, cases=cases, proposedNotes=variants(revisions))


def guide(repository, data):
    text = """# Proposed repository-context cases

This corpus is for human assessment. Executable tests establish only the stated
behavior and fixture integrity, not approval or improved model review quality.
The reviewed repository contains five small files and no remote. This guide and
the note proposals stay outside it, so they are not source context by accident.

Run `python3 scripts/repository-context-cases.py verify OUTPUT_DIR` from ByteQuay
to check frozen bytes and run the fixed dependency-free Python tests in temporary
directories. Verification does not execute commands supplied by manifest.json.
The bug must fail END_EXCLUSIVE; base, legacy and safe must pass their tests.

For manual inspection, `git -C OUTPUT_DIR/repository show fixture-base:README.md`
shows the current contract; fixture-legacy retains the earlier inclusive rule.
The bug includes the end of [start,end), assigning the shared boundary twice.
The safe change reuses a pure all-fields lookup. The hidden field remains index
zero; the visible field remains index one and resolves to hierarchy index four
with parent offset three. Repeated lookup does not switch index namespaces.

manifest.json records exact code/source identities and eight proposed note
variants. Its proposedNotes are NOT ContextEntry JSON or import/API payloads.
No BYTEQUAY_READ capture, provider receipt, repository ID or applicability label
is fabricated. Actual capture and applicability require normal ByteQuay reads.
After real Learn, existing entry editing can create USER-edited knowledge
variants. MEMORY creation must use Save lesson with real retained review evidence
or message IDs. Such lesson evidence is normally UNVERIFIED; the stale identity
case instead needs KNOWLEDGE with a real captured historical repository source.
draftVersion identifies only the proposal revision. Actual notebook and entry
versions come from service mutations and must be recorded during the later run.
Corrections edit the same saved lesson; disabled/removed variants
must be fresh runs, while old snapshots retain the original version and text.

This is not a turnkey app-review runner: no workspace, PR cache/ref routing,
notebook, login, provider conversation or app database is created. A later live
comparison needs isolated app setup and fresh independent Off/On conversations
with identical frozen code/settings. Do not let earlier conclusions or this
guide leak into another arm. Assess findings, counterevidence and limitations
against the code below; keep any human judgment separately from this corpus.

## Frozen review diffs
"""
    for case in data["cases"]:
        patch = git(repository, "diff", "--no-ext-diff", "--no-textconv", case["baseSha"], case["headSha"]).decode()
        text += "\n### " + case["id"] + "\n\nBase: " + case["baseSha"] + "\n\nHead: " + case["headSha"] + "\n\n```diff\n" + patch + "```\n"
    text += "\n## Proposed context variants\n\n| Variant | Review diff | Note or action |\n| --- | --- | --- |\n"
    for note in data["proposedNotes"]:
        prose = note.get("claim", note.get("instruction", "")).replace("|", "\\|")
        text += "| " + note["key"] + " | " + note["case"] + " | " + prose + " |\n"
    return text


def create(output):
    output = Path(output).resolve()
    output.mkdir()  # Even an existing empty directory is refused; no caller data is replaced.
    repository = output / "repository"
    repository.mkdir()
    template = output / "empty-git-template"
    template.mkdir()
    git(repository, "init", "-q", "--initial-branch=fixture", "--object-format=sha1", "--template=" + str(template))
    for name, files in REVISIONS.items():
        if name in ("bug", "safe"):
            git(repository, "checkout", "--detach", "-q", "fixture-base")
        for path, text in files.items():
            (repository / path).write_text(text, encoding="utf-8")
        git(repository, "add", "--", *sorted(files))
        git(repository, "commit", "-qm", COMMIT_TITLES[name])
        git(repository, "tag", "fixture-" + name)
    git(repository, "checkout", "--detach", "-q", "fixture-base")
    data = manifest(repository)
    (output / "manifest.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    (output / "REVIEW.md").write_text(guide(repository, data), encoding="utf-8")
    return verify(output)


def run_oracles():
    results = {}
    with tempfile.TemporaryDirectory(prefix="bytequay-context-oracles-") as temporary:
        for name, files in REVISIONS.items():
            directory = Path(temporary) / name
            directory.mkdir()
            for path, text in files.items():
                (directory / path).write_text(text, encoding="utf-8")
            result = subprocess.run([sys.executable, "-I", "-S", "-B", "-c", ORACLE], cwd=directory,
                                    env=environment(), capture_output=True, text=True, timeout=10)
            expected = 1 if name == "bug" else 0
            if result.returncode != expected or (name == "bug" and "END_EXCLUSIVE" not in result.stderr):
                raise ValueError("Unexpected fixed oracle result for " + name + ": " + result.stderr)
            results[name] = dict(exitCode=result.returncode, expectedExitCode=expected, output=result.stderr.strip())
    return results


def verify(output):
    output = Path(output).resolve()
    repository = output / "repository"
    metadata = repository / ".git"
    if repository.is_symlink() or not repository.is_dir() or metadata.is_symlink() or not metadata.is_dir():
        raise ValueError("Corpus must contain a regular repository and .git directory")
    if git(repository, "remote").strip() or git(repository, "status", "--porcelain", "--untracked-files=all").strip():
        raise ValueError("Corpus repository must have no remote or working-tree changes")
    expected = manifest(repository)
    if json.loads((output / "manifest.json").read_text(encoding="utf-8")) != expected:
        raise ValueError("Manifest integrity mismatch")
    if (output / "REVIEW.md").read_text(encoding="utf-8") != guide(repository, expected):
        raise ValueError("Review guide integrity mismatch")
    if git(repository, "rev-parse", "HEAD").decode().strip() != expected["revisions"]["base"]["commit"]:
        raise ValueError("Corpus checkout must remain at fixture-base")
    if {path.name for path in repository.iterdir()} != set(BASE) | {".git"}:
        raise ValueError("Checkout integrity mismatch: unexpected files, including ignored files")
    for name, text in BASE.items():
        path = repository / name
        if path.is_symlink() or not path.is_file() or path.read_bytes() != text.encode():
            raise ValueError("Checkout integrity mismatch: " + name)
    return dict(scope="Machine integrity and expected test outcomes only", output=str(output), oracles=run_oracles())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("create", "verify"))
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    try:
        result = create(arguments.output) if arguments.operation == "create" else verify(arguments.output)
    except (OSError, ValueError, subprocess.SubprocessError) as failure:
        parser.exit(1, str(failure) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
