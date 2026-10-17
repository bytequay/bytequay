import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

import advisor
import fixer
import saved
from test_advisor import ANSWER
from test_agent import Script, Stand, call, completion, said

SCRIPT = Path(fixer.__file__).resolve()
RISK = ANSWER["suggestions"][0]  # the backend's heap, set in dev.sh
GOOD = {"summary": "Give the backend 4 GB.", "edits": [{"path": "dev.sh", "old": "-Xmx2g", "new": "-Xmx4g"}], "test": "Run ./dev.sh and watch the backend's peak."}


class Repository(unittest.TestCase):
    """A repository with one commit, a change not committed and a file git does not keep."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name).resolve()
        self.folder, self.repo, self.source = root / "lab", root / "repo", root / "source"
        self.repo.mkdir()
        (self.repo / "dev.sh").write_text('JVM_ARGS="-Xmx2g"\nexec java $JVM_ARGS\n')
        for arguments in (("init", "-q"), ("add", "dev.sh"), ("-c", "user.name=Lab", "-c", "user.email=lab@example.com", "commit", "-q", "-m", "Start")):
            subprocess.run(["git", "-C", str(self.repo), *arguments], check=True, capture_output=True)
        (self.repo / "dev.sh").write_text("changed and not committed\n")
        (self.repo / "local.env").write_text("TOKEN=not kept by git\n")


class TestAnswer(Repository):
    def test_a_handed_in_plan_is_checked_before_it_counts(self):
        self.source.mkdir()
        (self.source / "dev.sh").write_text("a\na\nend")  # no newline at its end
        (self.source / "docs").mkdir()
        edit = {"path": "dev.sh", "old": "end", "new": "the end"}
        for answer, wrong in (({"summary": "x"}, "exactly summary, edits and test"), ({**GOOD, "test": " "}, "summary and test must be text"),
                              ({**GOOD, "edits": "x"}, "edits must be a list"), ({**GOOD, "edits": [{"path": "dev.sh", "old": "a"}]}, "edit 1 must have exactly path, old and new"),
                              ({**GOOD, "edits": [edit, {**edit, "path": "../repo/dev.sh"}]}, "edit 2: ../repo/dev.sh is outside the input folder"),
                              ({**GOOD, "edits": [{**edit, "path": "docs"}]}, "edit 1: docs is a folder"),
                              ({**GOOD, "edits": [{**edit, "old": ""}]}, "edit 1: dev.sh exists, so old must be the text to replace"),
                              ({**GOOD, "edits": [{**edit, "old": "a"}]}, "edit 1: old must be found exactly once in dev.sh, and is found 2 times"),
                              ({**GOOD, "edits": [{"path": "new.sh", "old": "x", "new": "y"}]}, "edit 1: new.sh does not exist; give an empty old to make it")):
            with self.subTest(wrong=wrong):
                self.assertIn(wrong, fixer.problem_of(answer, self.source))
        self.assertIsNone(fixer.problem_of({**GOOD, "edits": []}, self.source))  # no change to the code helps
        edits = [edit, {"path": "docs/new.md", "old": "", "new": "made\n"}, {"path": "docs/new.md", "old": "made", "new": "made here"}]
        self.assertIsNone(fixer.problem_of({**GOOD, "edits": edits}, self.source))
        patch = fixer.patch_of(edits, self.source)
        self.assertEqual(fixer.files_of(patch, self.source), [{"path": "dev.sh", "added": 1, "deleted": 1}, {"path": "docs/new.md", "added": 1, "deleted": 0}])
        subprocess.run(["git", "apply", "-"], cwd=self.source, input=patch, text=True, check=True)
        self.assertEqual(((self.source / "dev.sh").read_text(), (self.source / "docs/new.md").read_text()), ("a\na\nthe end", "made here\n"))


class TestPlan(Repository):
    def plan(self, script):
        return fixer.plan(self.folder, RISK, script, "deepseek-v4-pro", self.repo)

    def test_a_plan_reads_the_source_as_committed_and_keeps_a_patch_that_applies(self):
        script = Script(said(call("c1", "list_files", {"path": "."})), said(call("c2", "read_file", {"path": "dev.sh"})),
                        said(call("c3", "submit_fix", {**GOOD, "edits": [{**GOOD["edits"][0], "old": "-Xmx8g"}]})), said(call("c4", "submit_fix", GOOD)))
        made = self.plan(script)
        self.assertTrue(script.sent[0][-1]["content"].startswith(f"Risk: {RISK['title']}\nSeen: {RISK['seen']}\n"))
        self.assertEqual(script.sent[1][-1]["content"], "dev.sh")  # what git keeps, and nothing else
        self.assertIn("-Xmx2g", script.sent[2][-1]["content"])  # as committed
        self.assertEqual(script.sent[3][-1]["content"], "error: edit 1: old must be found exactly once in dev.sh, and is found 0 times. Call submit_fix again with the whole plan.")
        head = subprocess.run(["git", "-C", str(self.repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        self.assertEqual((made["state"], made["commit"], made["files"], made["steps"]), ("done", head, [{"path": "dev.sh", "added": 1, "deleted": 1}], 4))
        self.assertEqual(made["patch"], '--- a/dev.sh\n+++ b/dev.sh\n@@ -1,2 +1,2 @@\n-JVM_ARGS="-Xmx2g"\n+JVM_ARGS="-Xmx4g"\n exec java $JVM_ARGS\n')
        self.assertEqual(subprocess.run(["git", "apply", "--check", "--cached", "-"], cwd=self.repo, input=made["patch"], text=True).returncode, 0)
        self.assertEqual((self.repo / "dev.sh").read_text(), "changed and not committed\n")  # the repository is never changed
        self.assertEqual((fixer.kept(self.folder, RISK["title"]), saved.read(self.folder / "fix", fixer.STATE)), (made, made))

    def test_reading_ends_before_the_last_model_calls_so_there_is_room_to_hand_in(self):
        read, hand_in = said(call("c1", "read_file", {"path": "dev.sh"})), said(call("c2", "submit_fix", GOOD))
        ended = "error: reading has ended: call submit_fix now with the plan you have"
        with mock.patch.object(fixer, "READING", 1):
            script = Script(read, read, hand_in)
            self.assertEqual(self.plan(script)["state"], "done")
        self.assertEqual(("-Xmx2g" in script.sent[1][-1]["content"], script.sent[2][-1]["content"]), (True, ended))  # by model calls
        with mock.patch.object(fixer, "READ_TOKENS", 999):
            script = Script(read, hand_in)  # every call here reads 1,000 prompt tokens
            self.assertEqual(self.plan(script)["state"], "done")
        self.assertEqual(script.sent[1][-1]["content"], ended)  # by the conversation's size

    def test_a_plan_that_cannot_finish_says_why_and_keeps_the_last_plan(self):
        done = self.plan(Script(said(call("c1", "submit_fix", GOOD))))
        failed = self.plan(Script(RuntimeError("the API is down")))
        self.assertEqual((failed["state"], failed["problem"]), ("failed", "RuntimeError: the API is down"))
        self.assertEqual((fixer.kept(self.folder, RISK["title"]), saved.read(self.folder / "fix", fixer.STATE)), (done, failed))


class TestCommandLine(Repository):
    def test_it_plans_a_risk_of_the_last_look_by_its_title(self):
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stand)
        server.requests, server.replies = [], [completion(said(call("c1", "submit_fix", {**GOOD, "edits": []})))]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        environment = dict(os.environ, DEEPSEEK_API_KEY="a-test-key", DEEPSEEK_BASE_URL=f"http://127.0.0.1:{server.server_address[1]}")
        command = lambda title: subprocess.run([sys.executable, str(SCRIPT), "--folder", str(self.folder), title], capture_output=True, text=True,
                                               stdin=subprocess.DEVNULL, env=environment)
        done = command(RISK["title"])
        self.assertEqual((done.returncode, done.stderr), (1, f"The last look found no risk titled {RISK['title']!r}\n"))
        saved.write(advisor.place(self.folder)["folder"], advisor.STATE, {"version": 1, "state": "done", "suggestions": ANSWER["suggestions"]})
        done = command(RISK["title"])
        self.assertEqual((done.returncode, done.stdout.splitlines()[:2]), (0, [GOOD["summary"], "No change to the code."]), done.stderr)
        self.assertEqual([one["function"]["name"] for one in server.requests[0]["tools"]], ["list_files", "read_file", "search", "submit_fix"])
        self.assertEqual(fixer.kept(self.folder, RISK["title"])["state"], "done")
        self.assertFalse((self.folder / "fix" / "running.pid").exists())


if __name__ == "__main__":
    unittest.main()
