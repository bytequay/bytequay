from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

import activity
from test_records import database, reviewed

START = datetime(2026, 1, 1, tzinfo=timezone.utc)
WHEN = datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone.utc)
ORDER = ["approval", "mvn", "git", "Read", "text"]


def matches_example(test, name, made):
    """The example in the repository is what the dashboard's tests read, so it must be exactly what the lab writes."""
    example = Path(__file__).with_name("fixture") / name
    if os.environ.get("UPDATE_SAVED_EXAMPLES"):
        example.write_text(json.dumps(made, indent=2) + "\n")
    test.assertEqual(json.loads(example.read_text()), made, "Run the tests once with UPDATE_SAVED_EXAMPLES=1 if the saved shape changed on purpose")


def at(second):
    return (START + timedelta(seconds=second)).isoformat().replace("+00:00", "Z")


def event(second, kind, turn="t1", item=None, **payload):
    return {"occurredAt": at(second), "type": kind, "turnId": turn, "source": {"providerItemId": item}, "payload": payload}


def turn(start, end, name="t1"):
    return [event(start, "lifecycle", name, phase="TURN_STARTED"), event(end, "lifecycle", name, phase="TURN_COMPLETED")]


def command(start, end, text, item=None, turn="t1"):
    return [event(start, "command", turn, item, state="STARTED", argv=[text], durationMs=None),
            event(end, "command", turn, item, state="COMPLETED", argv=[text], durationMs=None)]


def tool(start, end, name, item=None, turn="t1"):
    return [event(start, "tool", turn, item, state="STARTED", name=name, durationMs=None),
            event(end, "tool", turn, item, state="COMPLETED", name=name, durationMs=None)]


def in_order(*groups):
    return sorted((made for group in groups for made in group), key=lambda made: made["occurredAt"])


def group_of(item):
    """The test's own sorting: a command by its first word, a tool by its name, anything else by its kind."""
    return item.detail["argv"][0].split()[0] if item.kind == "command" else item.detail.get("name") or item.kind


def cut(events):
    """Stretches as (start, end, group, running), in seconds from the start of the test clock."""
    windows, finished, _ = activity.items(events)
    return [(round(made.start - START.timestamp(), 3), round(made.end - START.timestamp(), 3), made.group, made.running)
            for made in activity.stretches(windows, finished, group_of, ORDER)]


class TestItems(unittest.TestCase):
    def test_timestamps_with_and_without_fractions_are_read(self):
        self.assertEqual(activity.seconds("2026-01-01T00:00:01.250000Z") - activity.seconds("2026-01-01T00:00:00Z"), 1.25)
        self.assertEqual(activity.seconds("2026-01-01T00:00:00.123Z"), activity.seconds("2026-01-01T00:00:00.123000Z"))

    def test_item_that_never_ends_is_reported_apart_and_not_stretched(self):
        events = in_order(turn(0, 100), [event(10, "command", state="STARTED", argv=["mvn test"], durationMs=None)])
        windows, finished, unfinished = activity.items(events)
        self.assertEqual((windows["t1"][1] - windows["t1"][0], finished), (100, []))
        self.assertEqual([(made.kind, made.detail["argv"]) for made in unfinished], [("command", ["mvn test"])])
        self.assertEqual(cut(events), [(0, 100, activity.SILENT, ())])

    def test_end_without_a_start_uses_the_stored_duration_or_is_dropped(self):
        events = in_order(turn(0, 100), [event(40, "tool", item="a", state="COMPLETED", name="Read", durationMs=15000),
                                         event(60, "tool", item="b", state="COMPLETED", name="Read", durationMs=None)])
        self.assertEqual(cut(events), [(0, 25, activity.SILENT, ()), (25, 40, "Read", ("Read",)), (40, 100, activity.SILENT, ())])

    def test_same_named_tools_are_told_apart_by_their_item_id(self):
        events = in_order(turn(0, 100), tool(10, 50, "Read", item="a"), tool(20, 30, "Read", item="b"))
        _, finished, unfinished = activity.items(events)
        self.assertEqual((sorted(made.end - made.start for made in finished), unfinished), ([10, 40], []))

    def test_waiting_for_an_approval_is_an_item_of_its_own(self):
        events = in_order(turn(0, 100), [event(10, "approval_requested", approvalId="p1"), event(70, "approval_resolved", approvalId="p1", outcome="ALLOWED")])
        self.assertEqual(cut(events), [(0, 10, activity.SILENT, ()), (10, 70, "approval", ("approval",)), (70, 100, activity.SILENT, ())])

    def test_pieces_of_visible_text_close_together_are_one_run_of_writing(self):
        pieces = [event(second, "assistant_message", messageKind="ANSWER", phase="PARTIAL", text="x") for second in (10, 11, 12.5, 20, 21)]
        final = [event(30, "assistant_message", messageKind="ANSWER", phase="FINAL", text="the whole answer")]
        self.assertEqual(cut(in_order(turn(0, 40), pieces, final)),
                         [(0, 10, activity.SILENT, ()), (10, 12.5, "text", ("text",)), (12.5, 20, activity.SILENT, ()), (20, 21, "text", ("text",)),
                          (21, 40, activity.SILENT, ())])

    def test_turn_without_an_end_mark_ends_at_its_last_event(self):
        events = in_order([event(0, "lifecycle", phase="TURN_STARTED")], command(10, 30, "git status"))
        self.assertEqual(cut(events), [(0, 10, activity.SILENT, ()), (10, 30, "git", ("git",))])

    def test_interrupted_turn_ends_at_the_interruption_and_later_marks_do_not_move_it(self):
        events = in_order([event(0, "lifecycle", phase="TURN_STARTED"), event(50, "lifecycle", phase="INTERRUPTED"), event(90, "lifecycle", phase="TURN_COMPLETED")])
        self.assertEqual(cut(events), [(0, 50, activity.SILENT, ())])


class TestStretches(unittest.TestCase):
    def test_one_thing_after_another(self):
        events = in_order(turn(0, 100), command(10, 40, "mvn test"), tool(50, 60, "Read"))
        self.assertEqual(cut(events), [(0, 10, activity.SILENT, ()), (10, 40, "mvn", ("mvn",)), (40, 50, activity.SILENT, ()), (50, 60, "Read", ("Read",)),
                                       (60, 100, activity.SILENT, ())])

    def test_overlap_is_counted_once_under_the_first_group_in_order(self):
        events = in_order(turn(0, 100), tool(10, 50, "Read", item="a"), command(20, 40, "mvn test"), tool(30, 45, "Read", item="b"))
        made = cut(events)
        self.assertEqual(made, [(0, 10, activity.SILENT, ()), (10, 20, "Read", ("Read",)), (20, 30, "mvn", ("mvn", "Read")), (30, 40, "mvn", ("mvn", "Read")),
                                (40, 45, "Read", ("Read",)), (45, 50, "Read", ("Read",)), (50, 100, activity.SILENT, ())])
        self.assertEqual(sum(end - start for start, end, _, _ in made), 100)

    def test_only_the_part_inside_the_turn_counts(self):
        events = in_order(command(-20, 30, "git fetch"), turn(0, 100), command(90, 130, "mvn test"),
                          command(10, 20, "git status", turn="other turn with no start mark"))
        self.assertEqual(cut(events), [(0, 30, "git", ("git",)), (30, 90, activity.SILENT, ()), (90, 100, "mvn", ("mvn",))])

    def test_each_turn_is_cut_on_its_own(self):
        events = in_order(turn(0, 50, "t1"), turn(100, 130, "t2"), command(10, 20, "mvn test", turn="t1"), command(110, 115, "git diff", turn="t2"))
        made = activity.stretches(*activity.items(events)[:2], group_of, ORDER)
        self.assertEqual([(made_one.turn, made_one.group, made_one.end - made_one.start) for made_one in made],
                         [("t1", activity.SILENT, 10), ("t1", "mvn", 10), ("t1", activity.SILENT, 30), ("t2", activity.SILENT, 10), ("t2", "git", 5),
                          ("t2", activity.SILENT, 15)])

    def test_group_missing_from_the_order_fails_loudly(self):
        with self.assertRaises(ValueError):
            cut(in_order(turn(0, 100), tool(10, 20, "A tool nobody sorted")))


class TestProgram(unittest.TestCase):
    def named(self, cases):
        for line, expected in cases:
            with self.subTest(line=line):
                self.assertEqual(activity.program(line), expected)

    def test_program_is_the_first_word_of_a_command_not_a_word_in_its_arguments(self):
        self.named([("git status --short", "git"), ("./mvnw -pl core test", "maven"), ("/opt/homebrew/bin/mvn -v", "maven"),
                    ("sed -n '1,20p' core/src/main/java/io/Foo.java", "sed"), ("cat src/main/java/A.java | grep mvn", "cat"),
                    ('git commit -m "fix; mvn test && sleep 5 | npm"', "git"), ('"$JAVA_HOME/bin/java" -version', "java"), ("ls $(./mvnw -q help:evaluate)", "ls")])

    def test_leading_cd_and_variable_settings_are_not_the_program(self):
        self.named([('cd "/Users/me/my repo" && ./mvnw test', "maven"), ('FOO=1 BAR="a b" npm test', "npm"), ("L=/tmp/x; git log", "git"),
                    ("export A=1; set -e; ls", "ls"), ("X=$(git rev-parse HEAD)", "other"), ("(cd sub && make all)", "make"), ("cd somewhere", "other")])

    def test_shell_that_only_wraps_the_command_is_removed(self):
        for argv, expected in [(["/bin/zsh", "-lc", "cd x && git diff"], "cd x && git diff"), (["/bin/zsh -lc 'git status --short'"], "git status --short"),
                               (['/bin/zsh -lc "cd \\"my repo\\" && ./mvnw test"'], 'cd "my repo" && ./mvnw test'),
                               # The provider quotes one argument in pieces when it holds both kinds of quote.
                               (['/bin/zsh -lc "./mvnw -pl core test "\'-Dtest=Foo$Bar\''], "./mvnw -pl core test -Dtest=Foo$Bar"),
                               (["bash -c 'sh -c \"npm test\"'"], "npm test"), (["cd x && git status"], "cd x && git status"),
                               (["git", "status", "--short"], "git status --short"), (['/bin/zsh -lc "never closed'], '/bin/zsh -lc "never closed'),
                               # -c is also an option of programs that are not shells.
                               (["git", "-c", "core.pager=cat"], "git -c core.pager=cat"), (["git -c core.pager=cat"], "git -c core.pager=cat"), ([], "")]:
            with self.subTest(argv=argv):
                self.assertEqual(activity.command_text({"argv": argv}), expected)
        self.assertEqual(activity.program(activity.command_text({"argv": ['/bin/zsh -lc "cd \\"my repo\\" && ./mvnw test"']})), "maven")
        self.assertEqual(activity.program("zsh run-all"), "shell script")

    def test_line_is_named_after_a_build_tool_then_a_wait_loop_then_the_first_real_work(self):
        self.named([("git status && ./mvnw test && git diff", "maven"), ("until gh run view 1 | grep -q done; do sleep 10; done", "wait loop"), ("sleep 5", "wait loop"),
                    ("for i in 1 2 3; do curl -s x; done", "curl"), ("echo start && python3 run.py", "python"), ("mkdir -p out; echo done", "mkdir"),
                    ("timeout 30 ./mvnw test", "maven"), ("time -p git gc", "git"), ("if [ -f x ]; then npm test; fi", "npm"), ("while read line; do grep x; done", "grep")])

    def test_what_follows_a_here_document_mark_is_input_not_commands(self):
        self.named([("python3 - <<'PY'\nimport git\nmvn = 1\nPY", "python"), ("cat <<EOF > notes\n./mvnw test\nEOF", "cat")])

    def test_only_plain_program_names_come_out_never_arguments(self):
        self.assertEqual(activity.programs("git commit -m 'secret words' && curl -H 'Authorization: abc123' https://example.invalid | jq .token"), ["git", "curl", "jq"])
        self.named([("bash scripts/run.sh --token abc123", "shell script"), ("./tools/check.sh", "shell script"), ("", "other"), ("$(weird)", "other"),
                    ("a" * 40 + " argument", "other"), ("/tmp/9f8a7b6c/run --password hunter2", "run")])


class TestGroups(unittest.TestCase):
    def test_commands_are_sorted_by_what_they_run_and_then_by_what_they_read(self):
        for line, kinds, expected in [("./mvnw test", ["OTHER"], activity.BUILDS), ("cd x && npm test", [], activity.BUILDS), ("git status", ["OTHER"], activity.SHELL),
                                      ("sed -n 1,5p A.java", ["READ"], activity.READING), ("rg foo && cat a", ["SEARCH", "READ"], activity.READING),
                                      ("cat a && git add a", ["READ", "OTHER"], activity.SHELL), ("ls", [], activity.SHELL),
                                      ("cat pom.xml | ./mvnw -f - test", ["READ"], activity.BUILDS)]:
            with self.subTest(line=line):
                made = activity.Item("command", "t1", 0, 1, {"argv": [line], "actions": [{"kind": kind} for kind in kinds]})
                self.assertEqual(activity.group_of(made), expected)

    def test_tools_are_sorted_by_name_whatever_server_prefix_they_carry(self):
        for name, expected in [("Read", activity.READING), ("Grep", activity.READING), ("bytequay/codegraph_explore", activity.READING),
                               ("mcp__bytequay__review_get_context", activity.BYTEQUAY), ("bytequay/review_save_finding", activity.BYTEQUAY),
                               ("review_finish", activity.BYTEQUAY), ("repository_memory", activity.BYTEQUAY), ("list_local_review_comments", activity.BYTEQUAY),
                               ("work_items", activity.BYTEQUAY), ("hook/PreToolUse:mcp__bytequay__review_get_context", activity.OTHER_TOOLS),
                               ("Task", activity.SUBAGENTS), ("SubagentHandback", activity.SUBAGENTS), ("Context compaction", activity.COMPACTION),
                               ("AskUserQuestion", activity.YOU), ("Bash", activity.SHELL), ("Edit", activity.OTHER_TOOLS), ("Web search", activity.OTHER_TOOLS),
                               ("codex/list_mcp_resources", activity.OTHER_TOOLS), ("another_server/some_tool", activity.OTHER_TOOLS)]:
            with self.subTest(name=name):
                self.assertEqual(activity.group_of(activity.Item("tool", "t1", 0, 1, {"name": name})), expected)

    def test_approvals_and_visible_text_have_groups_of_their_own(self):
        self.assertEqual(activity.group_of(activity.Item("approval", "t1", 0, 1, {"approvalId": "p1"})), activity.YOU)
        self.assertEqual(activity.group_of(activity.Item("text", "t1", 0, 1, {})), activity.WRITING)

    def test_real_rules_and_order_cut_a_turn_where_a_build_outranks_a_read(self):
        events = in_order(turn(0, 100), tool(10, 60, "Read", item="a"), command(20, 40, "cd repo && ./mvnw test"), tool(70, 80, "mcp__bytequay__review_finish"))
        windows, finished, _ = activity.items(events)
        made = [(round(one.end - one.start), one.group) for one in activity.stretches(windows, finished, activity.group_of, activity.ORDER)]
        self.assertEqual(made, [(10, activity.SILENT), (10, activity.READING), (20, activity.BUILDS), (20, activity.READING), (10, activity.SILENT),
                                (10, activity.BYTEQUAY), (20, activity.SILENT)])


def pieces(*seconds_since_start):
    return [event(second, "assistant_message", messageKind="ANSWER", phase="PARTIAL", text="the password is hunter2") for second in seconds_since_start]


def approval(start, end, name="p1"):
    return [event(start, "approval_requested", approvalId=name, detail="rm -rf /Users/someone/secret-project"), event(end, "approval_resolved", approvalId=name, outcome="ALLOWED")]


# One ten-minute turn: a Maven run with a file read inside it, a ByteQuay tool, a wait for approval, then a little visible text.
TEN_MINUTES = in_order(turn(0, 600), command(10, 130, "cd /Users/someone/secret-project && ./mvnw -Dpassword=hunter2 test", item="c1"),
                       tool(20, 80, "Read", item="r1"), tool(140, 200, "mcp__bytequay__review_get_context", item="b1"), approval(210, 270), pieces(300, 301, 302))


class Logs(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        # As in the application's own folder, the runtime folder has the database for a neighbour.
        self.runtime = Path(directory.name) / "agent-runtime"
        self.runtime.mkdir()

    def log(self, task, *groups, extra=""):
        folder = self.runtime / task
        folder.mkdir()
        (folder / "events.jsonl").write_text("".join(json.dumps(made) + "\n" for made in in_order(*groups)) + extra)
        return folder / "events.jsonl"


class TestMeasure(Logs):
    def test_every_second_of_a_turn_is_in_exactly_one_of_the_three_states(self):
        total = activity.measure([self.log("aaaa", TEN_MINUTES)])
        self.assertEqual((total.tasks, total.turns, total.seconds), (1, 1, 600))
        self.assertEqual({group: length for group, length in total.groups.items() if length},
                         {activity.SILENT: 358, activity.WRITING: 2, activity.BUILDS: 120, activity.BYTEQUAY: 60, activity.YOU: 60})
        by_state = {state: sum(total.groups[group] for group in groups) for state, groups in activity.STATES}
        self.assertEqual(by_state, {"The model is working": 360, "Waiting for a tool to finish": 180, "Waiting for you": 60})
        self.assertEqual(sum(by_state.values()), total.seconds)
        self.assertEqual(set(total.groups) - {group for _, groups in activity.STATES for group in groups}, set())

    def test_every_group_belongs_to_exactly_one_state(self):
        in_states = [group for _, groups in activity.STATES for group in groups]
        self.assertEqual(sorted(in_states), sorted([activity.SILENT, *activity.ORDER]))

    def test_model_time_is_counted_under_what_just_finished(self):
        total = activity.measure([self.log("aaaa", TEN_MINUTES)])
        self.assertEqual(dict(total.after), {"at the start of a turn": 10, "after tests and builds": 10, "after ByteQuay tools": 10, "after you answered": 330})
        # Silent, writing and silent again after the approval are one stretch of model time.
        self.assertEqual(dict(total.after_times), {"at the start of a turn": 1, "after tests and builds": 1, "after ByteQuay tools": 1, "after you answered": 1})

    def test_shell_time_by_program_sums_every_run_and_only_the_part_inside_its_turn(self):
        total = activity.measure([self.log("aaaa", turn(0, 100), command(-20, 30, "git fetch", item="g1"), command(40, 50, "git status", item="g2"),
                                           command(60, 70, "cd x && ./mvnw -q test", item="m1"), command(80, 130, "sleep 60", item="s1"))])
        self.assertEqual((dict(total.program_seconds), dict(total.program_runs)), ({"git": 40, "maven": 10, "wait loop": 20}, {"git": 2, "maven": 1, "wait loop": 1}))

    def test_parallel_work_counts_tools_running_at_once_and_not_waits_or_text(self):
        total = activity.measure([self.log("aaaa", turn(0, 100), tool(10, 40, "Read", item="a"), tool(20, 50, "Read", item="b"), tool(25, 30, "Grep", item="c"),
                                           approval(60, 90), tool(70, 80, "Read", item="d"), pieces(71, 72))])
        self.assertEqual((total.parallel, total.most_at_once), (20, 3))

    def test_since_leaves_out_turns_that_started_earlier(self):
        log = self.log("aaaa", turn(0, 50, "t1"), turn(100, 130, "t2"), command(10, 20, "git status", turn="t1"), command(110, 120, "git diff", turn="t2"))
        total = activity.measure([log, self.log("bbbb", turn(0, 40))], since=START.timestamp() + 60)
        self.assertEqual((total.tasks, total.turns, total.seconds, dict(total.program_runs), total.unread), (1, 1, 30, {"git": 1}, 0))

    def test_what_was_left_out_is_named(self):
        old_format = json.dumps({"occurredAt": at(5), "type": "COMMAND", "event": {}}) + "\n"
        logs = [self.log("aaaa", turn(0, 100), [event(10, "command", item="c1", state="STARTED", argv=["./mvnw test"], durationMs=None)], extra=old_format + '{"cut off'),
                self.log("bbbb", extra=old_format)]
        total = activity.measure(logs)
        self.assertEqual((total.unfinished, total.skipped, total.unread, total.groups[activity.SILENT]), (1, 2, 1, 100))
        printed = activity.render(total)
        for sentence in ["1 command or tool never reported an end and is not counted.", "2 log lines cut off or in an old format were skipped.",
                         "1 task log with no turn in the current format was left out."]:
            self.assertIn(sentence, printed)

    def test_no_turn_at_all_says_so(self):
        self.assertEqual(activity.render(activity.measure([self.log("aaaa", command(0, 10, "git status"))])), "No turns found.")


class TestTables(Logs):
    def test_tables_show_the_three_states_their_parts_and_the_top_programs(self):
        log = self.log("aaaa", TEN_MINUTES, command(400, 430, "git status", item="g1"), command(440, 450, "ls", item="l1"), command(460, 470, "gh pr view 1", item="h1"))
        printed = activity.render(activity.measure([log]), top=2).splitlines()
        self.assertEqual(printed[0], "Agent time: 1 task, 1 turn, 0.2 hours of turn time, 2026-01-01 to 2026-01-01")
        for expected in [f"{'What is happening':<46}{'minutes':>9}{'share':>9}", f"{'The model is working':<46}{5.2:>9}{'51.7%':>9}", f"{'    model silent':<46}{5.1:>9}{'51.3%':>9}",
                         f"{'Waiting for a tool to finish':<46}{3.8:>9}{'38.3%':>9}", f"{'    tests and builds':<46}{2.0:>9}{'20.0%':>9}", f"{'Waiting for you':<46}{1.0:>9}{'10.0%':>9}",
                         f"{'    maven':<46}{2.0:>9}{1:>9}", f"{'    git':<46}{0.5:>9}{1:>9}", f"{'    every other program':<46}{0.3:>9}{2:>9}",
                         f"{'Model time by what just finished':<46}{'minutes':>9}{'share':>9}{'times':>9}", f"{'    after you answered':<46}{2.2:>9}{'41.9%':>9}{1:>9}"]:
            with self.subTest(expected=expected):
                self.assertIn(expected, printed)
        self.assertNotIn("reading and searching files", "\n".join(printed))  # the read ran inside the build, so it has no seconds of its own

    def test_nothing_private_is_printed(self):
        log = self.log("aaaa", TEN_MINUTES, command(400, 460, 'curl -H "Authorization: Bearer sk-live-SECRET123" https://internal.example.invalid/private/path', item="x1"),
                       [event(470, "tool", item="x2", state="STARTED", name="Read", summary="/Users/someone/secret-project/keys.pem", durationMs=None),
                        event(520, "tool", item="x2", state="COMPLETED", name="Read", summary="/Users/someone/secret-project/keys.pem", durationMs=None),
                        event(530, "user_message", text="my token is sk-live-SECRET123", attachments=[], deliveryKind="TURN")])
        total = activity.measure([log])
        printed = activity.render(total) + activity.timeline(total)
        for secret in ["hunter2", "SECRET123", "Authorization", "internal.example", "secret-project", "keys.pem", "someone", "password", "rm -rf", "/Users"]:
            with self.subTest(secret=secret):
                self.assertNotIn(secret, printed)
        self.assertIn("curl", printed)
        self.assertIn("maven", printed)


class TestBusyTurn(Logs):
    def busy_day(self):
        """One turn with every kind of item in it, a second log in the old format, and two lines that cannot be read."""
        old_format = json.dumps({"occurredAt": at(5), "type": "COMMAND", "event": {}}) + "\n"
        turn_of_twenty_minutes = in_order(
            turn(0, 1200), command(10, 130, "cd /Users/someone/secret-project && ./mvnw -Dpassword=hunter2 test", item="c1"), tool(20, 80, "Read", item="r1"),
            tool(140, 200, "mcp__bytequay__review_get_context", item="b1"), approval(210, 270), pieces(300, 301, 302), command(400, 430, "git status", item="g1"),
            command(440, 450, "ls", item="l1"), command(460, 470, "gh pr view 1", item="h1"), tool(500, 520, "Read", item="r2"), tool(505, 530, "Grep", item="r3"),
            tool(600, 700, "Task", item="s1"), tool(720, 760, "Context compaction", item="k1"),
            [event(800, "command", item="never", state="STARTED", argv=["./mvnw verify"], durationMs=None)])
        return activity.measure([self.log("aaaa", turn_of_twenty_minutes, extra=old_format + '{"cut off'), self.log("bbbb", extra=old_format)])

    def test_turn_table_of_a_busy_turn_holds_what_the_tables_show(self):
        made = activity.turn_table(self.busy_day(), WHEN)
        (row,) = made["turns"]
        states = {state: sum(row["groups"].get(group, 0) for group in groups) for state, groups in activity.STATES}
        self.assertEqual(states, {"The model is working": 740, "Waiting for a tool to finish": 400, "Waiting for you": 60})
        self.assertEqual((row["seconds"], sum(row["groups"].values())), (1200, 1200))
        self.assertEqual(row["programs"]["maven"], {"seconds": 120, "runs": 1})
        # A file read ran for a minute inside the Maven run, and two reads overlapped for 15 seconds.
        self.assertEqual((row["parallel"], row["mostAtOnce"], row["unfinished"], made["leftOut"]), (75, 2, 1, {"skippedLines": 2, "unreadLogs": 1}))
        self.assertEqual(sum(after["seconds"] for after in row["after"].values()), states["The model is working"])

    def test_turn_table_names_no_argument_path_or_text(self):
        saved_text = json.dumps(activity.turn_table(self.busy_day(), WHEN, "2026-01-01", "aaaa"))
        for secret in ["hunter2", "secret-project", "someone", "password", "rm -rf", "/Users", "verify"]:
            with self.subTest(secret=secret):
                self.assertNotIn(secret, saved_text)
        self.assertIn('"since": "2026-01-01", "task": "aaaa"', saved_text)


class TestTurns(Logs):
    def two_days(self):
        """Two tasks over two days: a full turn, a turn that was interrupted, and a turn that is still running. Two of them ran two tools at once."""
        day = 86400
        first = self.log("aaaa-1111", TEN_MINUTES, [event(day, "lifecycle", "t2", phase="TURN_STARTED"), event(day + 300, "lifecycle", "t2", phase="INTERRUPTED")],
                         command(day + 20, day + 140, "cd /Users/someone/secret-project && npm test -- --token=hunter2", item="n1", turn="t2"),
                         [event(day + second, "lifecycle", "t2", phase=phase, message=message) for second, phase, message in (
                             (150, "RATE_LIMITED", "Claude rate limit: allowed_warning"), (155, "RATE_LIMITED", "Claude rate limit: allowed_warning"),
                             (160, "RATE_LIMITED", "Claude rate limit: rejected"), (170, "RETRYING", "Claude API retry 2 for someone@example.invalid"))])
        second = self.log("bbbb-2222", [event(day + 3600, "lifecycle", phase="TURN_STARTED")], command(day + 3600, day + 3640, "git gc", item="g1"),
                          tool(day + 3610, day + 3630, "Read", item="r1"), tool(day + 3700, day + 3720, "Read", item="r2"))
        return activity.measure([first, second])

    def test_totals_are_the_sum_of_their_turns(self):
        total = self.two_days()
        self.assertEqual((total.tasks, total.turns, len(total.each)), (2, 3, 3))
        self.assertEqual([round(one.end - one.start) for one in total.each], [600, 300, 120])
        for one in total.each:
            self.assertAlmostEqual(sum(one.groups.values()), one.end - one.start)  # every second of a turn is in exactly one group
        self.assertAlmostEqual(total.seconds, sum(one.end - one.start for one in total.each))
        for whole, part in (("groups", "groups"), ("program_seconds", "program_seconds"), ("program_runs", "program_runs"), ("after", "after"), ("after_times", "after_times")):
            added = sum((getattr(one, part) for one in total.each), activity.Counter())
            self.assertEqual({name: round(amount, 6) for name, amount in getattr(total, whole).items()}, {name: round(amount, 6) for name, amount in added.items()})
        # Overlapping time adds up over the turns. The most tools at once is the most in any one turn, not a sum.
        self.assertEqual([(one.parallel, one.most_at_once) for one in total.each], [(60, 2), (0, 0), (20, 2)])
        self.assertEqual((total.parallel, total.most_at_once), (80, 2))

    def test_each_turn_names_its_task_and_how_it_ended(self):
        self.assertEqual([(one.task, one.ended) for one in self.two_days().each],
                         [("aaaa-1111", "TURN_COMPLETED"), ("aaaa-1111", "INTERRUPTED"), ("bbbb-2222", "")])
        # A mark after the turn has closed does not change how it ended, and neither does a mark before it started.
        late = self.log("cccc-3333", [event(0, "lifecycle", phase="TASK_FAILED")], turn(10, 70), [event(90, "lifecycle", phase="INTERRUPTED")])
        self.assertEqual([one.ended for one in activity.measure([late]).each], ["TURN_COMPLETED"])

    def recorded(self):
        """What the database holds: the two turns of the first task, nothing for the second task, and a turn that has no log."""
        day = START.timestamp() + 86400
        blank = dict.fromkeys(activity.FACTS)
        return {("aaaa-1111", "t1"): {**blank, "start": START.timestamp(), "seconds": 601, "outcome": "completed", "provider": "codex", "model": "gpt-6-astra", "effort": "max",
                                      "repository": "trinodb/trino", "inputTokens": 900000, "cachedInputTokens": 850000, "outputTokens": 4000, "toolCalls": 30, "compactions": 0},
                ("aaaa-1111", "t2"): {**blank, "start": day, "seconds": 299, "outcome": "cancelled", "provider": "codex", "model": "gpt-6-astra", "effort": "medium",
                                      "repository": "trinodb/trino", "inputTokens": 200000, "cachedInputTokens": 150000, "outputTokens": 1000, "toolCalls": 8, "compactions": 0},
                ("cccc-3333", "t9"): {**blank, "start": day + 7200, "seconds": 240, "outcome": "completed", "provider": "claude", "model": "opus", "effort": "high",
                                      "repository": "bytequay/bytequay", "inputTokens": 50000, "cachedInputTokens": 40000, "outputTokens": 2000, "cost": 1.5, "toolCalls": 12,
                                      "compactions": 1}}

    def test_turn_table_has_one_row_per_turn_and_is_the_example_the_dashboard_tests_read(self):
        made = activity.turn_table(self.two_days(), WHEN, recorded=self.recorded())
        matches_example(self, "saved-turns.json", made)
        self.assertEqual((made["version"], made["savedAt"], made["since"], made["task"], made["leftOut"]), (1, "2026-01-02T03:04:05Z", None, None, {"skippedLines": 0, "unreadLogs": 0}))
        self.assertEqual([(row["task"], row["start"], row["seconds"], row["logged"], row["outcome"]) for row in made["turns"]],
                         [("aaaa-1111", "2026-01-01T00:00:00Z", 600, True, "completed"), ("aaaa-1111", "2026-01-02T00:00:00Z", 300, True, "cancelled"),
                          ("bbbb-2222", "2026-01-02T01:00:00Z", 120, True, "no end mark"), ("cccc-3333", "2026-01-02T02:00:00Z", 240, False, "completed")])
        first = made["turns"][0]
        self.assertEqual(first["groups"], {"model silent": 358, "tests and builds": 120, "ByteQuay tools": 60, "waiting for you": 60, "model writing": 2})
        self.assertEqual(first["programs"], {"maven": {"seconds": 120, "runs": 1}})
        self.assertEqual(first["after"]["after you answered"], {"seconds": 330, "times": 1})
        self.assertEqual((first["parallel"], first["mostAtOnce"], first["unfinished"]), (60, 2, 0))
        self.assertEqual((first["longRuns"], first["approvals"], first["notices"]),
                         ([{"program": "maven", "seconds": 120}], [{"kind": "other", "outcome": "allowed", "seconds": 60}], {}))
        self.assertEqual((made["turns"][1]["longRuns"], made["turns"][1]["approvals"], made["turns"][1]["notices"]),
                         ([{"program": "npm", "seconds": 120}], [], {"rate limit: allowed warning": 2, "rate limit: rejected": 1, "retry": 1}))
        self.assertEqual((made["turns"][3]["longRuns"], made["turns"][3]["approvals"], made["turns"][3]["notices"]), ([], [], {}))  # no log, so none of these are known
        for row in made["turns"][:3]:  # the turns with a log
            self.assertEqual(sum(row["groups"].values()), row["seconds"])
            self.assertEqual(sum(after["seconds"] for after in row["after"].values()), sum(row["groups"].get(group, 0) for group in activity.STATES[0][1]))

    def test_turn_in_the_log_and_the_database_gets_its_model_effort_tokens_and_outcome_from_the_database(self):
        made = activity.turn_table(self.two_days(), WHEN, recorded=self.recorded())
        first, second, only_logged, only_recorded = made["turns"]
        self.assertEqual({name: first[name] for name in activity.FACTS},
                         {"provider": "codex", "model": "gpt-6-astra", "effort": "max", "repository": "trinodb/trino", "inputTokens": 900000, "cachedInputTokens": 850000,
                          "outputTokens": 4000, "cost": None, "toolCalls": 30, "compactions": 0})
        self.assertEqual((first["seconds"], second["effort"], second["outcome"]), (600, "medium", "cancelled"))  # time is the log's, the ending is the database's
        # Only in the log: its time split is known, and nothing else is made up.
        self.assertEqual(({name: only_logged[name] for name in activity.FACTS}, only_logged["outcome"]), (dict.fromkeys(activity.FACTS), "no end mark"))
        # Only in the database: no time split, and its time is the database's own.
        self.assertEqual((only_recorded["model"], only_recorded["cost"], only_recorded["seconds"], only_recorded["groups"], only_recorded["programs"], only_recorded["after"]),
                         ("opus", 1.5, 240, {}, {}, {}))
        self.assertEqual(made["database"], {"turns": 3, "matched": 2})

    def test_turn_only_in_the_database_follows_the_same_filters_as_the_logs(self):
        total, recorded = self.two_days(), self.recorded()
        rows = lambda **filters: [row["task"] for row in activity.turn_table(total, WHEN, recorded=recorded, **filters)["turns"] if not row["logged"]]
        self.assertEqual((rows(), rows(since="2026-01-02"), rows(since="2026-01-03"), rows(task="cccc"), rows(task="aaaa")), (["cccc-3333"], ["cccc-3333"], [], ["cccc-3333"], []))

    def test_database_that_was_not_read_is_named_and_nothing_is_made_up(self):
        total = self.two_days()
        made = activity.turn_table(total, WHEN, problem="the database is not there")
        self.assertEqual((made["database"], len(made["turns"])), ({"problem": "the database is not there"}, 3))
        self.assertEqual({row[name] for row in made["turns"] for name in activity.FACTS}, {None})
        self.assertEqual([row["outcome"] for row in made["turns"]], ["completed", "interrupted", "no end mark"])  # as the logs say it
        self.assertEqual(activity.turn_table(total, WHEN)["database"], {"problem": "the database was not read"})

    def test_single_commands_of_half_a_minute_or_more_are_kept_longest_first_and_cut_to_their_turn(self):
        log = self.log("aaaa-1111", turn(0, 300), command(10, 39, "git fetch", item="a"), command(40, 70, "./mvnw test", item="b"), command(80, 200, "./mvnw verify", item="c"),
                       command(250, 400, "npm test", item="d"))
        (one,) = activity.measure([log]).each
        # 29 seconds is not long; the run that outlived its turn counts for the 50 seconds inside it.
        self.assertEqual(one.long_runs, [("maven", 30), ("maven", 120), ("npm", 50)])
        (row,) = activity.turn_table(activity.measure([log]), WHEN)["turns"]
        self.assertEqual(row["longRuns"], [{"program": "maven", "seconds": 120}, {"program": "npm", "seconds": 50}, {"program": "maven", "seconds": 30}])
        self.assertEqual(activity.LONG_RUN, 30)

    def test_each_wait_for_the_user_keeps_its_kind_its_outcome_and_how_long_it_took(self):
        asked = lambda second, name, kind: event(second, "approval_requested", approvalId=name, approvalKind=kind, title="rm -rf", detail="rm -rf /Users/someone/secret-project")
        answered = lambda second, name, outcome: event(second, "approval_resolved", approvalId=name, outcome=outcome)
        log = self.log("aaaa-1111", turn(0, 600), [asked(10, "p1", "COMMAND"), answered(40, "p1", "ALLOWED"), asked(100, "p2", "FILE_CHANGE"), answered(400, "p2", "EXPIRED"),
                                                   asked(500, "p3", None), answered(520, "p3", None), asked(580, "p4", "COMMAND"),
                                                   asked(590, "p5", "COMMAND"), answered(650, "p5", "ALLOWED")])
        (one,) = activity.measure([log]).each
        # The last wait was answered 50 seconds after the turn had ended; only its 10 seconds inside the turn count.
        self.assertEqual(one.approvals, [("command", "allowed", 30), ("file_change", "expired", 300), ("other", "unknown", 20), ("command", "allowed", 10)])
        self.assertEqual((one.unfinished, one.groups[activity.YOU]), (1, 360))  # the wait nobody answered before the log ended is left out, as for a command
        saved_text = json.dumps(activity.turn_table(activity.measure([log]), WHEN))
        for secret in ["rm -rf", "secret-project", "someone", "/Users"]:
            self.assertNotIn(secret, saved_text)

    def test_rate_limit_notices_and_retries_are_counted_in_the_turn_they_came_in(self):
        said = lambda second, name, phase, message: event(second, "lifecycle", name, phase=phase, message=message)
        log = self.log("aaaa-1111", turn(0, 100), turn(200, 300, "t2"),
                       [said(10, "t1", "RATE_LIMITED", "Claude rate limit: allowed_warning"), said(20, "t1", "RATE_LIMITED", "Claude rate limit: allowed_warning"),
                        said(30, "t1", "RETRYING", "Claude API retry 1"), said(210, "t2", "RATE_LIMITED", "Claude rate limit: rejected"),
                        said(220, "t2", "RATE_LIMITED", "a notice in words the lab does not know"), said(230, "t2", "WORKTREE_UPDATED", "rate limit: not a notice")])
        first, second = activity.measure([log]).each
        self.assertEqual((dict(first.notices), dict(second.notices)), ({"rate limit: allowed warning": 2, "retry": 1}, {"rate limit: rejected": 1, "rate limit: other": 1}))

    def test_turn_table_names_programs_and_never_their_arguments(self):
        saved_text = json.dumps(activity.turn_table(self.two_days(), WHEN))
        for secret in ["hunter2", "secret-project", "someone", "token", "/Users", "gc", "example.invalid"]:
            with self.subTest(secret=secret):
                self.assertNotIn(secret, saved_text)
        self.assertIn('"npm"', saved_text)


class TestReviewTable(Logs):
    def test_review_table_is_what_the_database_holds_and_the_example_the_dashboard_tests_read(self):
        path = reviewed(self.runtime.parent / "bytequay-1.0.db")
        made = activity.review_table(activity.records.reviews(path, activity.records.read(path)), WHEN)
        matches_example(self, "saved-reviews.json", made)
        self.assertEqual((made["version"], made["savedAt"], made["since"], made["task"], len(made["runs"]), len(made["findings"])), (1, "2026-01-02T03:04:05Z", None, None, 3, 4))
        self.assertEqual(activity.review_table({"runs": [], "findings": []}, WHEN, "2026-01-01", "aaaa")["task"], "aaaa")


class TestTimeline(Logs):
    def test_long_stretches_are_listed_in_order_and_short_ones_are_only_counted(self):
        total = activity.measure([self.log("aaaa", turn(0, 300), command(10, 50, "./mvnw compile", item="m1"), command(50, 100, "./mvnw test", item="m2"),
                                           tool(105, 106, "Read", item="r1"))])
        self.assertEqual(activity.timeline(total).splitlines(),
                         ["Timeline: stretches of 30 seconds or more, with the time since the turn started", "", "Turn 1, 5.0 minutes",
                          "           (1 shorter stretch, 0.2 minutes)", "     0:10    1.5 min  tests and builds", "           (2 shorter stretches, 0.1 minutes)",
                          "     1:46    3.2 min  model silent"])

    def test_each_turn_has_its_own_part_and_short_stretches_at_its_end_are_counted(self):
        total = activity.measure([self.log("aaaa", turn(0, 60, "t1"), turn(100, 140, "t2"), command(0, 50, "git gc", item="g1", turn="t1"))])
        self.assertEqual(activity.timeline(total).splitlines()[2:],
                         ["Turn 1, 1.0 minutes", "     0:00    0.8 min  other shell commands", "           (1 shorter stretch, 0.2 minutes)", "",
                          "Turn 2, 0.7 minutes", "     0:00    0.7 min  model silent"])


class TestCommandLine(Logs):
    def run_script(self, *arguments):
        return subprocess.run([sys.executable, activity.__file__, "--runtime", str(self.runtime), *arguments], capture_output=True, text=True)

    def test_all_tasks_are_added_up_and_one_task_comes_with_its_timeline(self):
        self.log("aaaa-1111", TEN_MINUTES)
        self.log("bbbb-2222", turn(0, 60), command(0, 40, "git gc", item="g1"))
        everything = self.run_script()
        self.assertEqual(everything.returncode, 0, everything.stderr)
        self.assertIn("Agent time: 2 tasks, 2 turns, 0.2 hours of turn time", everything.stdout)
        self.assertNotIn("Timeline", everything.stdout)
        one = self.run_script("--task", "bbbb")
        self.assertIn("Agent time: 1 task, 1 turn", one.stdout)
        self.assertIn("\n\nTimeline: stretches of 30 seconds or more", one.stdout)
        self.assertIn("     0:00    0.7 min  other shell commands", one.stdout)
        self.assertIn("No turns found.", self.run_script("--since", "2030-01-01").stdout)

    def test_save_writes_the_turn_table_and_keeps_an_earlier_one_when_there_are_no_turns(self):
        self.log("aaaa-1111", TEN_MINUTES)
        folder = self.runtime.parent / (self.runtime.name + " saved")
        self.addCleanup(lambda: [path.unlink() for path in folder.glob("*")] and folder.rmdir())
        first = self.run_script("--task", "aaaa", "--save", str(folder))
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn(f"Saved to {folder / 'turns.json'}", first.stdout)
        self.assertEqual([path.name for path in folder.iterdir()], ["turns.json"])
        written = json.loads((folder / "turns.json").read_text())
        self.assertEqual((written["version"], written["task"], [(row["task"], row["seconds"]) for row in written["turns"]]), (1, "aaaa", [("aaaa-1111", 600)]))
        # No database beside the runtime folder: the table is saved from the logs alone and says so.
        self.assertEqual((written["database"], written["turns"][0]["model"]), ({"problem": "the database is not there"}, None))
        self.assertIn("Model, effort, tokens and cost are left out: the database is not there.", first.stdout)
        self.assertIn("Reviews are not saved: the database is not there.", first.stdout)
        self.assertTrue(re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", written["savedAt"]))
        nothing = self.run_script("--since", "2030-01-01", "--save", str(folder))
        self.assertEqual((nothing.returncode, nothing.stderr), (0, ""))
        self.assertNotIn("Saved to", nothing.stdout)
        self.assertEqual(json.loads((folder / "turns.json").read_text()), written)

    def test_save_adds_what_the_database_beside_the_runtime_folder_knows_and_never_changes_it(self):
        self.log("aaaa-1111", TEN_MINUTES)
        beside = database(self.runtime.parent / "bytequay-1.0.db", tasks=[("aaaa-1111", "codex", "trinodb", "trino"), ("zzzz-9999", "claude", "o", "n")], turns=[
            ("aaaa-1111", "t1", at(0), 600_000, None, "gpt-6-astra", "Max", "COMPLETED", 900, 800, 40, None, 7, 0),
            ("zzzz-9999", "t1", at(50), 30_000, None, "opus", "high", "FAILED", 100, 0, 10, 0.25, 1, 0)])
        before = beside.read_bytes()
        folder = self.runtime.parent / "saved"
        saved_all = self.run_script("--save", str(folder))
        self.assertEqual((saved_all.returncode, saved_all.stderr), (0, ""))
        self.assertNotIn("left out", saved_all.stdout)
        written = json.loads((folder / "turns.json").read_text())
        self.assertEqual(written["database"], {"turns": 2, "matched": 1})
        self.assertIn(f"Saved to {folder / 'reviews.json'}", saved_all.stdout)
        self.assertEqual({name: value for name, value in json.loads((folder / "reviews.json").read_text()).items() if name != "savedAt"},
                         {"version": 1, "since": None, "task": None, "runs": [], "findings": []})  # this database holds no review
        self.assertEqual([(row["task"], row["logged"], row["model"], row["effort"], row["outcome"], row["inputTokens"], row["cost"]) for row in written["turns"]],
                         [("aaaa-1111", True, "gpt-6-astra", "max", "completed", 900, None), ("zzzz-9999", False, "opus", "high", "failed", 100, 0.25)])
        self.assertEqual(beside.read_bytes(), before)
        # The tables in the terminal are the same with and without the database: they come from the logs alone.
        self.assertEqual(saved_all.stdout.split("\n\nSaved to")[0], self.run_script().stdout.rstrip("\n"))
        elsewhere = database(self.runtime.parent / "another.db", without=("effort",))
        other_shape = self.run_script("--save", str(folder), "--database", str(elsewhere))
        self.assertEqual(other_shape.returncode, 0, other_shape.stderr)
        self.assertIn("Model, effort, tokens and cost are left out: the database has another shape (no such column: turn.effort).", other_shape.stdout)
        self.assertEqual(json.loads((folder / "turns.json").read_text())["database"], {"problem": "the database has another shape (no such column: turn.effort)"})

    def test_save_writes_the_review_runs_and_findings_under_the_same_choices_as_the_turn_table(self):
        self.log("aaaa-0001", turn(0, 60, "t1"))
        self.log("bbbb-0002", turn(3 * 3600, 3 * 3600 + 60, "u1"))
        reviewed(self.runtime.parent / "bytequay-1.0.db")
        folder = self.runtime.parent / "saved"
        everything = self.run_script("--save", str(folder))
        self.assertEqual((everything.returncode, everything.stderr), (0, ""))
        written = json.loads((folder / "reviews.json").read_text())
        self.assertEqual(([run["start"] for run in written["runs"]], [finding["run"] for finding in written["findings"]], written["since"], written["task"]),
                         (["2026-01-01T01:00:00Z", "2026-01-01T03:00:00Z", "2026-01-01T04:00:00Z"], [0, 0, 0, 2], None, None))
        one_task = self.run_script("--task", "bbbb-0002", "--save", str(folder))
        self.assertEqual(one_task.returncode, 0, one_task.stderr)
        written = json.loads((folder / "reviews.json").read_text())
        self.assertEqual(([run["model"] for run in written["runs"]], len(written["findings"]), written["task"]), (["opus"], 1, "bbbb-0002"))
        # Review tables in another shape leave the earlier review file as it is, say so, and still save the turns.
        other_shape = self.run_script("--save", str(folder), "--database", str(database(self.runtime.parent / "another.db", without=("turn_ids_json",))))
        self.assertEqual(other_shape.returncode, 0, other_shape.stderr)
        self.assertIn("Reviews are not saved: the database has another shape (no such column: run.turn_ids_json).", other_shape.stdout)
        self.assertEqual((json.loads((folder / "reviews.json").read_text()), "problem" in json.loads((folder / "turns.json").read_text())["database"]), (written, False))

    def test_wrong_arguments_are_refused_with_a_reason(self):
        self.log("aaaa-1111", TEN_MINUTES)
        for arguments, reason in [(["--task", "cccc"], "No task log matches"), (["--task", "../aaaa"], "--task takes the start of a task id"),
                                  (["--since", "yesterday"], "--since takes a day as YYYY-MM-DD")]:
            with self.subTest(arguments=arguments):
                result = self.run_script(*arguments)
                self.assertEqual(result.returncode, 2)
                self.assertIn(reason, result.stderr)


class TestRead(unittest.TestCase):
    def test_cut_off_and_old_format_lines_are_skipped_and_counted(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "events.jsonl"
            good = turn(0, 10)
            log.write_text("".join(json.dumps(made) + "\n" for made in good) + json.dumps({"occurredAt": at(5), "type": "COMMAND", "event": {}}) + "\n"
                           + '{"occurredAt": "2026-01-01T00:00:0')
            self.assertEqual(activity.read(log), (good, 2))


if __name__ == "__main__":
    unittest.main()
