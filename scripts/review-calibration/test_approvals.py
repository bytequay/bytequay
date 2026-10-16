import json
from pathlib import Path
import tempfile
import unittest

import activity
import approvals
from test_activity import START, command, event, in_order, tool

CODEX = "Allow Codex command"
CLAUDE = "Allow Claude Bash"


def asked(start, end, title, detail, outcome="ALLOWED", turn="t1", key="a1", kind="COMMAND"):
    return [event(start, "approval_requested", turn, approvalId=key, approvalKind=kind, title=title, detail=detail, scope=[detail]),
            event(end, "approval_resolved", turn, approvalId=key, outcome=outcome)]


def claude_detail(line, **more):
    return json.dumps({"command": line, "description": "Run it", **more})


def ended(start, end, mark="TURN_COMPLETED", name="t1"):
    return [event(start, "lifecycle", name, phase="TURN_STARTED"), event(end, "lifecycle", name, phase=mark)]


def reviewed(start, end, summary, item, state="COMPLETED"):
    return [event(start, "tool", "t1", item, state="STARTED", name=approvals.AUTOMATIC, summary="Reviewing action", durationMs=None),
            event(end, "tool", "t1", item, state=state, name=approvals.AUTOMATIC, summary=summary, durationMs=None)]


def read(events, since=None):
    with tempfile.TemporaryDirectory() as folder:
        log = Path(folder) / "task-1" / "events.jsonl"
        log.parent.mkdir()
        log.write_text("".join(json.dumps(made) + "\n" for made in events))
        return approvals.read_log(log, since)


def one(item_detail):
    return approvals.card(activity.Item("approval", "t1", 0, 1, item_detail))


class TestCard(unittest.TestCase):
    def test_codex_command_is_its_line_without_the_shell_and_what_codex_added(self):
        detail = "/bin/zsh -lc './mvnw -o test'\nReason: needs the network\nNetwork approval: {\"host\": \"repo.maven.apache.org\"}"
        self.assertEqual(one({"title": CODEX, "approvalKind": "COMMAND", "detail": detail}), ("codex", "command", "./mvnw -o test", frozenset({"network"})))

    def test_codex_command_with_no_reason_is_the_whole_line(self):
        self.assertEqual(one({"title": CODEX, "approvalKind": "COMMAND", "detail": "/bin/zsh -lc 'gh api repos'"})[2], "gh api repos")

    def test_claude_command_is_read_from_the_tool_input(self):
        self.assertEqual(one({"title": CLAUDE, "approvalKind": "COMMAND", "detail": claude_detail("git status")}), ("claude", "command", "git status", frozenset()))

    def test_unsandboxed_claude_command_says_so_and_still_gives_its_line(self):
        detail = approvals.UNSANDBOXED + " with host file access.\n" + claude_detail("make", dangerouslyDisableSandbox=True)
        self.assertEqual(one({"title": "Allow Claude unsandboxed command", "approvalKind": "COMMAND", "detail": detail}), ("claude", "command", "make", frozenset({"unsandboxed"})))

    def test_card_cut_short_is_a_command_without_a_line(self):
        self.assertEqual(one({"title": CLAUDE, "approvalKind": "COMMAND", "detail": claude_detail("make")[:20] + "…"})[1:3], ("command", None))

    def test_cards_that_are_not_commands_have_no_line(self):
        cards = [({"title": "Allow Claude AskUserQuestion", "approvalKind": "OTHER", "detail": "{}"}, "question"),
                 ({"title": "Allow Claude Edit", "approvalKind": "FILE_CHANGE", "detail": "{}"}, "file change"),
                 ({"title": "Allow Codex requested permissions", "approvalKind": "OTHER", "detail": "Permission request"}, "permissions"),
                 ({"title": "Allow Claude Read", "approvalKind": "OTHER", "detail": "{}"}, "tool")]
        self.assertEqual([one(made)[1:3] for made, _ in cards], [(what, None) for _, what in cards])


class TestRules(unittest.TestCase):
    def test_a_rule_is_read_in_claudes_shape(self):
        self.assertEqual(approvals.parse_rule("Bash(./mvnw  -o:*)"), ("./mvnw -o", True))
        self.assertEqual(approvals.parse_rule(" Bash(git status) "), ("git status", False))
        for wrong in ("./mvnw", "Bash()", "Bash(:*)", "Read(~/x)", "Bash(a) Bash(b)"):
            with self.subTest(wrong=wrong), self.assertRaises(ValueError):
                approvals.parse_rule(wrong)

    def test_a_line_is_cut_into_its_commands_but_not_inside_quotes(self):
        self.assertEqual(approvals.pieces("cd x && ./mvnw  test 2>&1 | tail -5; echo 'a; b && c' || true & wait\nls"),
                         ["cd x", "./mvnw test 2>&1", "tail -5", "echo 'a; b && c'", "true", "wait", "ls"])

    def test_a_line_that_runs_hidden_commands_has_no_pieces(self):
        for line in ("echo $(rm -r x)", "echo \"$(id)\"", "echo `id`", "diff <(ls) y", "tee >(sh)"):
            with self.subTest(line=line):
                self.assertIsNone(approvals.pieces(line))

    def test_a_prefix_ends_where_a_word_ends_and_every_command_must_match(self):
        rules = [approvals.parse_rule("Bash(./mvnw -o:*)"), approvals.parse_rule("Bash(cd:*)"), approvals.parse_rule("Bash(git status)")]
        self.assertTrue(approvals.covered("./mvnw -o -pl core test", rules))
        self.assertTrue(approvals.covered("./mvnw -o", rules))
        self.assertTrue(approvals.covered("cd core && ./mvnw -o test", rules))
        self.assertTrue(approvals.covered("git status", rules))
        self.assertFalse(approvals.covered("./mvnw -offline test", rules))
        self.assertFalse(approvals.covered("cd core && rm -r target", rules))
        self.assertFalse(approvals.covered("git status --short", rules), "a rule without :* covers only its own command")
        self.assertFalse(approvals.covered("./mvnw -o $(cat flags)", rules))
        self.assertFalse(approvals.covered(None, rules))
        self.assertFalse(approvals.covered("./mvnw -o", []))

    def test_suggested_rules_stop_at_a_path_a_value_or_quoted_text(self):
        self.assertEqual(approvals.suggested("cd /work/core && ./mvnw -o -pl core -Dtest=Foo test | tail -5 && cd /work"),
                         ("Bash(cd:*)", "Bash(./mvnw -o -pl:*)", "Bash(tail:*)"))
        self.assertEqual(approvals.suggested("gh api 'search/issues?q=x'"), ("Bash(gh api:*)",))
        self.assertEqual(approvals.suggested("git log --oneline -5"), ("Bash(git log --oneline:*)",))
        self.assertEqual(approvals.suggested("git -C ../other log --oneline"), ("Bash(git -C:*)",))
        self.assertEqual(approvals.suggested("rm -rf target"), ("Bash(rm -rf:*)",), "a word after a flag may name a file")

    def test_no_rule_is_suggested_for_a_line_no_rule_can_cover(self):
        for line in ("TOKEN=abc gh api user", "echo $(id)", "(cd x && make)", "\"./mvnw\" test", None):
            with self.subTest(line=line):
                self.assertIsNone(approvals.suggested(line))

    def test_the_suggested_rules_cover_the_line_they_came_from(self):
        line = "cd x && ./mvnw -o -pl core test 2>&1 | grep -n ERROR"
        self.assertTrue(approvals.covered(line, [approvals.parse_rule(rule) for rule in approvals.suggested(line)]))

    def test_risks_are_labels_in_a_fixed_order_with_what_the_card_says_first(self):
        def labels(line, flags=()):
            return approvals.risks(approvals.Ask("t", 0, "codex", "command", line, "allowed", 1, False, frozenset(flags)))
        self.assertEqual(labels("./mvnw -o test"), [])
        self.assertEqual(labels("./mvnw test > /tmp/build.log 2>&1"), ["writes a file"])
        self.assertEqual(labels("./mvnw test 2>/dev/null"), [])
        self.assertEqual(labels("git push origin x && rm -rf build", {"network"}), ["network", "writes to GitHub", "deletes"])
        self.assertEqual(labels("gh api -X POST repos/a/b/issues"), ["writes to GitHub", "network"])
        self.assertEqual(labels("find . -name x -exec rm {} \\;"), ["deletes", "runs another command"])
        self.assertEqual(labels("bash -c \"make\"", {"unsandboxed"}), ["outside the sandbox", "runs another command"])


class TestReadLog(unittest.TestCase):
    def test_an_ask_that_ran_out_ended_its_turn_only_when_the_turn_stopped_right_after(self):
        codex = in_order(ended(0, 302, "INTERRUPTED"), asked(1, 301, CODEX, "/bin/zsh -lc 'curl x'", "EXPIRED"))
        claude = in_order(ended(0, 900), asked(1, 301, CLAUDE, claude_detail("make"), "EXPIRED"), command(400, 410, "ls"))
        finished = in_order(ended(0, 303), asked(1, 301, CLAUDE, claude_detail("make"), "EXPIRED"))
        self.assertEqual([(ask.outcome, ask.seconds, ask.stopped) for ask in read(codex)[0]], [("expired", 300, True)])
        self.assertEqual([(ask.outcome, ask.stopped) for ask in read(claude)[0]], [("expired", False)])
        self.assertEqual([(ask.outcome, ask.stopped) for ask in read(finished)[0]], [("expired", False)], "a turn that completed was not ended by the ask")

    def test_an_answered_ask_never_ended_its_turn(self):
        events = in_order(ended(0, 10, "INTERRUPTED"), asked(1, 9, CODEX, "/bin/zsh -lc 'curl x'"))
        self.assertEqual([(ask.outcome, ask.stopped) for ask in read(events)[0]], [("allowed", False)])

    def test_asks_reviews_and_commands_of_earlier_turns_are_left_out(self):
        events = in_order(ended(0, 50, name="old"), asked(1, 5, CODEX, "/bin/zsh -lc 'gh api x'", turn="old"), command(10, 11, "ls", turn="old"),
                          ended(86400, 86500), asked(86401, 86411, CLAUDE, claude_detail("make")), command(86420, 86421, "git log"))
        made, _, lines, _ = read(events, since=START.timestamp() + 86400)
        self.assertEqual(([ask.line for ask in made], lines), (["make"], ["git log"]))

    def test_asks_and_commands_of_later_turns_are_left_out_when_an_end_is_given(self):
        events = in_order(ended(0, 50, name="old"), asked(1, 5, CODEX, "/bin/zsh -lc 'gh api x'", turn="old"), command(10, 11, "ls", turn="old"),
                          ended(86400, 86500), asked(86401, 86411, CLAUDE, claude_detail("make")), command(86420, 86421, "git log"))
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "task-1" / "events.jsonl"
            log.parent.mkdir()
            log.write_text("".join(json.dumps(made) + "\n" for made in events))
            made, _, lines, _ = approvals.read_log(log, until=START.timestamp() + 86400)
        self.assertEqual(([ask.line for ask in made], lines), (["gh api x"], ["ls"]))

    def test_an_ask_with_no_answer_is_counted_apart(self):
        events = in_order(ended(0, 50), [event(1, "approval_requested", "t1", approvalId="a1", approvalKind="COMMAND", title=CODEX, detail="ls", scope=["ls"])])
        made, _, _, unanswered = read(events)
        self.assertEqual((made, unanswered), ([], 1))

    def test_codex_reviews_give_their_time_and_the_first_word_of_the_verdict(self):
        events = in_order(ended(0, 50), reviewed(1, 4, "Approved · low risk · a bounded local test", "r1"), reviewed(5, 7.5, "", "r2", state="FAILED"),
                          [event(8, "tool", "t1", "r3", state="STARTED", name=approvals.AUTOMATIC, summary="Reviewing action", durationMs=None)])
        self.assertEqual(read(events)[1], [(3, "approved"), (2.5, "failed")])



if __name__ == "__main__":
    unittest.main()
