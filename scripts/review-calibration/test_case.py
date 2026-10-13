from pathlib import Path
import subprocess
import sys
import unittest

import case

PULL = {"number": 1, "user": {"login": "author"}, "html_url": "https://example.invalid/pull/1", "title": "Read rows lazily",
        "base": {"ref": "main", "repo": {"private": False}}}


def comment(identity, login, at, commit="aaa", reply=None, kind="User"):
    made = {"id": identity, "user": {"login": login, "type": kind}, "created_at": at, "original_commit_id": commit, "path": "rows.py", "original_line": 7,
            "original_start_line": None, "side": "RIGHT", "diff_hunk": "@@ -1 +1 @@", "body": f"Request {identity}", "html_url": f"https://example.invalid/c/{identity}"}
    if reply is not None:
        made["in_reply_to_id"] = reply
    return made


class TestPick(unittest.TestCase):
    def test_reviewer_with_most_threads_and_the_commit_of_their_first_comment(self):
        picked = case.pick(PULL, [comment(4, "ana", "2026-01-02T00:00:00Z", commit="bbb"), comment(2, "ana", "2026-01-01T10:00:00Z"),
                                  comment(3, "ana", "2026-01-01T10:05:00Z"), comment(1, "ben", "2026-01-01T09:00:00Z")])
        self.assertEqual((picked["reviewer"], picked["head"], picked["cutoff"]), ("ana", "aaa", "2026-01-01T10:00:00Z"))
        self.assertEqual([kept["id"] for kept in picked["comments"]], [2, 3])
        self.assertEqual(picked["comments"][0], {"id": 2, "path": "rows.py", "line": 7, "startLine": None, "side": "RIGHT", "hunk": "@@ -1 +1 @@",
                                                 "body": "Request 2", "url": "https://example.invalid/c/2"})

    def test_replies_bots_and_the_author_do_not_count(self):
        picked = case.pick(PULL, [comment(1, "bot", "2026-01-01T01:00:00Z", kind="Bot"), comment(2, "author", "2026-01-01T02:00:00Z"),
                                  comment(3, "ana", "2026-01-01T03:00:00Z"), comment(4, "ana", "2026-01-01T04:00:00Z", reply=3),
                                  comment(5, "bot", "2026-01-01T05:00:00Z", kind="Bot")])
        self.assertEqual((picked["reviewer"], [kept["id"] for kept in picked["comments"]]), ("ana", [3]))

    def test_a_tie_goes_to_the_first_commenter(self):
        picked = case.pick(PULL, [comment(2, "ana", "2026-01-01T02:00:00Z"), comment(1, "ben", "2026-01-01T01:00:00Z")])
        self.assertEqual(picked["reviewer"], "ben")

    def test_named_reviewer_and_dropped_chatter(self):
        comments = [comment(1, "ana", "2026-01-01T01:00:00Z"), comment(2, "ana", "2026-01-01T02:00:00Z"), comment(3, "ben", "2026-01-01T03:00:00Z")]
        picked = case.pick(PULL, comments, reviewer="ben")
        self.assertEqual((picked["reviewer"], picked["cutoff"]), ("ben", "2026-01-01T03:00:00Z"))
        picked = case.pick(PULL, comments, drop={1, 99})
        self.assertEqual(([kept["id"] for kept in picked["comments"]], picked["dropped"], picked["cutoff"]), ([2], [1], "2026-01-01T01:00:00Z"))

    def test_nothing_to_score_is_an_error(self):
        for comments, reviewer, drop in [([], None, ()), ([comment(1, "ana", "2026-01-01T01:00:00Z")], "ben", ()),
                                         ([comment(1, "ana", "2026-01-01T01:00:00Z")], None, {1})]:
            with self.subTest(reviewer=reviewer, drop=drop), self.assertRaises(ValueError):
                case.pick(PULL, comments, reviewer, drop)


class TestDescription(unittest.TestCase):
    def edits(self, *nodes, total=None):
        return {"totalCount": len(nodes) if total is None else total,
                "nodes": [{"editedAt": at, "deletedAt": deleted, "diff": text} for at, deleted, text in nodes]}

    def test_description_as_it_stood_at_the_cut_off(self):
        cutoff = "2026-01-02T00:00:00Z"
        original, answered = ("2026-01-01T00:00:00Z", None, "original"), ("2026-01-03T00:00:00Z", None, "now mentions the fix")
        self.assertEqual(case.body_at("never edited", self.edits(), cutoff), "never edited")
        self.assertEqual(case.body_at("now mentions the fix", self.edits(answered, original), cutoff), "original")
        self.assertEqual(case.body_at("x", self.edits(original, ("2026-01-01T12:00:00Z", None, "clarified")), cutoff), "clarified")

    def test_missing_history_leaves_the_description_out(self):
        cutoff = "2026-01-02T00:00:00Z"
        for edits in [self.edits(("2026-01-03T00:00:00Z", None, "later only")), self.edits(("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", None)),
                      self.edits(("2026-01-01T00:00:00Z", None, "first page only"), total=101)]:
            with self.subTest(edits=edits):
                self.assertIsNone(case.body_at("today's text", edits, cutoff))


class TestBuild(unittest.TestCase):
    def fetch(self, private=False):
        def answer(*args):
            if args[0] == "graphql":
                self.assertIn("number=1", args)
                return {"data": {"repository": {"pullRequest": {"body": "today", "userContentEdits": {"totalCount": 0, "nodes": []}}}}}
            return {"repos/o/n/pulls/1": {**PULL, "base": {"ref": "main", "repo": {"private": private}}},
                    "repos/o/n/pulls/1/comments?per_page=100": [[comment(1, "ana", "2026-01-01T01:00:00Z")], [comment(2, "ana", "2026-01-01T02:00:00Z")]],
                    "repos/o/n/compare/main...aaa": {"merge_base_commit": {"sha": "fork-point"}}}[args[-1]]
        return answer

    def test_case_holds_the_fork_point_the_answer_key_and_the_text_from_before_the_review(self):
        built = case.build("o/n", 1, fetch=self.fetch())
        self.assertEqual({key: built[key] for key in ("repo", "number", "base", "head", "reviewer", "cutoff", "body", "title")},
                         {"repo": "o/n", "number": 1, "base": "fork-point", "head": "aaa", "reviewer": "ana", "cutoff": "2026-01-01T01:00:00Z",
                          "body": "today", "title": "Read rows lazily"})
        self.assertEqual([kept["id"] for kept in built["comments"]], [1, 2])
        self.assertIn("before the first review comment (2026-01-01T01:00:00Z)", case.context(built))
        self.assertIn("left out", case.context({**built, "body": None}))

    def test_private_repository_is_refused_before_its_comments_are_read(self):
        with self.assertRaisesRegex(ValueError, "Only public repositories"):
            case.build("o/n", 1, fetch=self.fetch(private=True))

    def test_candidates_are_merged_recently_and_have_enough_threads_from_one_reviewer(self):
        pulls = [{**PULL, "number": 1, "merged_at": "2026-01-05T00:00:00Z"}, {**PULL, "number": 2, "merged_at": None},
                 {**PULL, "number": 3, "merged_at": "2025-12-01T00:00:00Z"}, {**PULL, "number": 4, "merged_at": "2026-01-06T00:00:00Z"}]
        threads = {1: [comment(identity, "ana", f"2026-01-01T0{identity}:00:00Z") for identity in (1, 2, 3)], 4: [comment(9, "ana", "2026-01-01T01:00:00Z")]}

        def answer(*args):
            if "pulls?state=closed" in args[-1]:
                return pulls
            number = int(args[-1].split("/pulls/")[1].split("/")[0])
            return [threads[number]] if args[-1].endswith("comments?per_page=100") else {"changed_files": 2}
        self.assertEqual(case.candidates("o/n", "2026-01-01", 1, 3, fetch=answer),
                         [{"number": 1, "merged": "2026-01-05", "files": 2, "reviewer": "ana", "head": "aaa", "comments": 3, "title": "Read rows lazily"}])


class TestArguments(unittest.TestCase):
    def test_output_inside_this_repository_is_refused_before_github_is_read(self):
        inside = Path(case.__file__).resolve().parent / "answers"
        result = subprocess.run([sys.executable, case.__file__, "build", "--repo", "o/n", "--pr", "1", "--clone", "unused", "--output", str(inside)],
                                capture_output=True, text=True, env={"PATH": ""})
        self.assertEqual(result.returncode, 2)
        self.assertIn("outside this repository", result.stderr)
        self.assertFalse(inside.exists())


if __name__ == "__main__":
    unittest.main()
