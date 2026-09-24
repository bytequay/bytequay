#!/usr/bin/env python3
"""Create the component variant of the saved three-commit history case."""

import argparse
import importlib.util
import json
from pathlib import Path


def history_fixture():
    path = Path(__file__).parents[1] / "repository-context-history-compare" / "fixture.py"
    spec = importlib.util.spec_from_file_location("history_fixture", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def create(output):
    history = history_fixture()
    output.mkdir()
    repository = output / "repository"
    repository.mkdir()
    (repository / "sensors").mkdir()
    template = output / "empty-git-template"
    template.mkdir()
    history.git(repository, "init", "-q", "--initial-branch=main", "--object-format=sha1", "--template=" + str(template))
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
        history.git(repository, "add", "--", "README.md", "sensors/window.py", "sensors/test_window.py")
        history.git(repository, "commit", "-qm", title)
        history.git(repository, "tag", "fixture-" + name)
        revisions[name] = history.git(repository, "rev-parse", "HEAD")
    history.git(repository, "reset", "--hard", "fixture-base")
    manifest = {
        "revisions": revisions,
        "historicalPr": {"number": 7, "headSha": revisions["base"]},
        "developmentHead": revisions["head"],
        "historyPath": "sensors/window.py",
        "test": "python3 -B sensors/test_window.py",
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    print(json.dumps(create(parser.parse_args().output.resolve()), indent=2))
