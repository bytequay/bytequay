import ast
from datetime import date, datetime
import json
import logging
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlsplit

from streamlit import config
from streamlit.testing.v1 import AppTest

import activity
import batch
import charts
import dashboard
import suggest
from test_activity import command, in_order
from test_approvals import CODEX, asked, ended
from test_desktop import database
import test_suggest

EXAMPLE = Path(__file__).with_name("fixture") / "saved-turns.json"
# Outside a running page Streamlit warns on every start that there is no page, which is how these tests run it.
# A filter and not a level, because Streamlit sets the levels again whenever an option changes.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())
SAVED = "Save the turn table with `uv run activity.py --save`"
OVERVIEW = ["Where the time went", "How the turns ended", "Turn time per day"]
TIME = ["Every part of the time", "Waiting for a tool to finish: what was running", "Shell time by program, summed over runs", "Model time by what just finished",
        "The tasks that took the most time", "Turn time by repository"]
SLOW = ["Long single commands", "Waiting for you", "Rate limits and retries"]
TOOLS = ["Worth a tool?", "Every command and tool", "Why commands failed", "What commands hand back", "Would merging or caching pay?", "By provider"]
MODELS = ["Typical turn, by model and effort", "How long a turn took", "How each split its time", "How the turns ended"]
COST = ["Tokens by kind", "Tokens by model and effort", "Tokens per day", "The tasks that used the most tokens", "Cost in dollars"]
REVIEWS = ["Reviews ByteQuay ran", "Scored against a human reviewer"]
NO_REVIEWS = ["Nothing saved yet. Save the reviews with `uv run activity.py --save`", "Nothing scored yet. Run and score an experiment with `uv run batch.py NAME --judge`"]
FIXTURE = Path(__file__).with_name("fixture")


def table(page, column):
    """The one table on the page that has this column, wherever it stands."""
    (found,) = [frame.value for frame in page.dataframe if column in frame.value.columns]
    return found


def run_buttons(page):
    """The labels of the buttons that start or stop an experiment's runs."""
    return [button.label for button in page.button if button.key in ("start_runs", "stop_runs")]


def headings(page):
    return [heading.value for heading in page.subheader]


def captions(page):
    return [caption.value for caption in page.caption]


def started_by_bytequay(case):
    """What the desktop's start-up sets for the test's pages: a path of the page's own and a port it picked."""
    for name, value, default in (("server.baseUrlPath", "lab-session", ""), ("server.port", 49152, 8501)):
        config.set_option(name, value)
        case.addCleanup(config.set_option, name, default)


def write_fix(runtime, *events):
    """The log of a ByteQuay task, with only the events the lab reads to find a fix."""
    log = runtime / "fix-1" / "events.jsonl"
    log.parent.mkdir(parents=True)
    log.write_text("".join(json.dumps(event) + "\n" for event in events))


class Page(unittest.TestCase):
    """The whole page, run without a browser on the saved example."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)
        self.runtime = self.folder / "runtime"  # the page reads no real task log
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder), "--runtime", str(self.runtime), "--logs", str(self.folder / "logs")])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)  # what the page's own start-up sets
        self.addCleanup(config.set_option, "server.address", None)

    def page(self):
        page = AppTest.from_file(dashboard.__file__, default_timeout=30)
        page.session_state["lab_view"] = "Agent activity"
        return page.run()

    def save(self, text=None):
        (self.folder / "turns.json").write_text(EXAMPLE.read_text() if text is None else text)

    def save_reviews(self, reviews=None, scores=None, experiment="first"):
        (self.folder / "reviews.json").write_text((FIXTURE / "saved-reviews.json").read_text() if reviews is None else reviews)
        self.save_scores(experiment, scores)

    def save_scores(self, experiment, scores=None):
        """Scores kept inside an experiment, as report.py --save leaves them."""
        (self.folder / "experiments" / experiment).mkdir(parents=True, exist_ok=True)
        (self.folder / "experiments" / experiment / "scores.json").write_text((FIXTURE / "saved-scores.json").read_text() if scores is None else scores)

    def save_plan(self, experiment):
        """An experiment of one case and one setting that nothing has run yet."""
        case = self.folder / "cases" / "pr-1"
        case.mkdir(parents=True, exist_ok=True)
        (case / "case.json").write_text(json.dumps({"repo": "o/n", "base": "b", "head": "h"}))
        (case / "context.md").write_text("intent\n")
        kept = self.folder / "experiments" / experiment
        kept.mkdir(parents=True, exist_ok=True)
        (kept / "rules.md").write_text("the rules\n")
        (kept / "plan.json").write_text(json.dumps({"version": 1, "policy": "rules.md", "clones": {"o/n": str(self.folder)}, "cases": ["pr-1"],
                                                    "settings": {"quick": {"provider": "codex", "model": "small", "effort": "low"}}}))
        return kept

    def test_page_shows_the_saved_turns_in_tiles_and_charts(self):
        self.save()
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([title.value for title in page.title], ["ByteQuay Lab"])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:5],
                         [("Turn time", "0.3 h"), ("Tasks", "2"), ("Turns", "3"), ("Typical turn", "5.0 min"), ("Longest turn", "10 min")])
        self.assertEqual(headings(page), OVERVIEW + TIME + SLOW + TOOLS + COST + REVIEWS)  # the models tab has too few turns to draw
        self.assertEqual([tab.label for tab in page.tabs], ["Suggestions", "Overview", "Time", "Slow spots", "Tools", "Approvals", "Models", "Cost", "Reviews"])
        self.assertEqual([heading.value for heading in page.tabs[4].subheader], TOOLS)  # each tab holds what its name says
        self.assertEqual([note.value for note in page.info], NO_REVIEWS)  # the last tab says how to save what it would show
        self.assertEqual(len(page.get("plotly_chart")), 22)  # three, six, four, four, none and five on the six tabs
        self.assertIn("Two or more tools ran at once for 1.3 minutes; the most at once was 2.", captions(page))  # 60 seconds in one turn and 20 in another
        self.assertIn("4 of 4 turns. Days are in your own time zone.", captions(page))
        self.assertIn("With a log, so the time split is known: 3. In ByteQuay's records, so the model is known: 3. In both: 2.", captions(page))
        self.assertEqual(table(page, "runs").values.tolist(), [["maven", 2, 1], ["npm", 2, 1], ["git", 0.7, 1]])

    def test_models_tab_lists_every_model_and_effort_and_draws_only_those_with_enough_turns(self):
        self.save()
        page = self.page()
        self.assertEqual(table(page, "typical minutes")[["model and effort", "turns", "typical minutes", "typical tokens in", "completed %"]].values.tolist(),
                         [["gpt-6-astra · max", 1, 10, 900000, 100], ["gpt-6-astra · medium", 1, 5, 200000, 0], ["opus · high", 1, 4, 50000, 100]])
        self.assertIn("Different tasks ran on each model and effort, so this shows what happened, not which one is better. One with fewer than 5 turns is in the table only. "
                      "Typical means the middle turn: half are below it, half above. A range says where a number could lie had other tasks come, drawing whole tasks "
                      "again; it needs 3 tasks.", [caption.value for caption in page.caption])
        self.assertNotIn("How long a turn took", headings(page))  # one turn each: the table has them, the charts do not
        with mock.patch.object(charts, "MIN_TURNS", 1):
            drawn = self.page()
        self.assertEqual(list(drawn.exception), [])
        self.assertEqual(headings(drawn), OVERVIEW + TIME + SLOW + TOOLS + MODELS + COST + REVIEWS)
        self.assertEqual(len(drawn.get("plotly_chart")) - len(page.get("plotly_chart")), 4)

    def test_choosing_a_model_and_effort_narrows_every_tab(self):
        self.save()
        page = self.page()
        self.assertEqual(page.sidebar.multiselect[0].options, ["gpt-6-astra · max", "gpt-6-astra · medium", "opus · high"])
        page.sidebar.multiselect[0].set_value(["gpt-6-astra · medium"]).run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:3], [("Turn time", "0.1 h"), ("Tasks", "1"), ("Turns", "1")])
        self.assertEqual(table(page, "runs")["program"].tolist(), ["npm"])
        self.assertEqual(table(page, "typical minutes")["model and effort"].tolist(), ["gpt-6-astra · medium"])
        # A turn the database knows and no log holds: the time tabs say so, and the Models tab still has it.
        page.sidebar.multiselect[0].set_value(["opus · high"]).run()
        self.assertEqual(([note.value for note in page.info], [tile.label for tile in page.metric][0]), (["No turn of this choice has a log."] * 4 + NO_REVIEWS, "Tokens in"))
        self.assertEqual(table(page, "typical minutes")["model and effort"].tolist(), ["opus · high"])

    def test_slow_spots_tab_lists_long_commands_waits_for_the_user_and_rate_limit_notices(self):
        self.save()
        page = self.page()
        self.assertIn("3 single commands ran for 30 seconds or more: 4.7 minutes in all.", captions(page))
        self.assertEqual(table(page, "day")[["program", "minutes", "task"]].values.tolist(), [["maven", 2, "aaaa-1111"], ["npm", 2, "aaaa-1111"], ["git", 0.7, "bbbb-2222"]])
        self.assertIn("The agent asked 1 time and waited 1.0 minutes in all. A typical answer took 60 seconds.", captions(page))
        self.assertIn("The logs say how often these came, not how long they held a turn up. rate limit: allowed warning: 2, rate limit: rejected: 1, retry: 1.", captions(page))

    def test_slow_spots_tab_says_so_when_there_is_nothing_slow_and_when_a_wait_ran_out(self):
        saved = json.loads(EXAMPLE.read_text())
        quiet = [{**row, "longRuns": [], "approvals": [], "notices": {}} for row in saved["turns"]]
        self.save(json.dumps({**saved, "turns": quiet}))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        for expected in ["No single command ran for 30 seconds or more.", "The agent never had to wait for your answer.", "No rate limit notice and no retry."]:
            self.assertIn(expected, captions(page))
        self.assertEqual(len(page.get("plotly_chart")), 18)  # the four charts of this tab are not drawn
        waited = [{**row, "approvals": [{"kind": "command", "outcome": "expired", "seconds": 300}, {"kind": "command", "outcome": "allowed", "seconds": 20}] * row["logged"]}
                  for row in saved["turns"]]
        self.save(json.dumps({**saved, "turns": waited}))
        self.assertIn("The agent asked 6 times and waited 16.0 minutes in all. A typical answer took 160 seconds. 3 waits ran out with no answer, which ends the turn.",
                      captions(self.page()))

    def test_tools_tab_adds_up_the_calls_and_lists_every_command_every_tool_and_every_provider(self):
        self.save()
        page = self.page()
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][5:10],
                         [("Calls", "7"), ("Commands failed", "33.3%"), ("Tool calls failed", "25.0%"), ("Found nothing", "0"), ("Time in calls", "0.1 h")])
        self.assertEqual(table(page, "seconds a call")[["name", "kind", "calls", "failed", "failed %", "minutes"]].values.tolist(),
                         [["Read", "tool call", 3, 1, 33.3, 1.7], ["git", "command", 1, 0, 0, 0.7], ["maven", "command", 1, 0, 0, 2], ["npm", "command", 1, 1, 100, 2],
                          ["review_get_context", "tool call", 1, 0, 0, 1]])
        self.assertEqual(table(page, "provider")[["provider", "turns", "commands", "commands failed %", "tool calls", "tool calls failed %"]].values.tolist(),
                         [["codex", 2, 2, 50, 2, 0], ["not recorded", 1, 1, 0, 2, 50]])
        # Nothing in the example was called often enough for a failure rate to mean something, so the chart of candidates is not drawn.
        self.assertIn("No program or tool was called 5 times or more.", captions(page))
        with mock.patch.object(charts, "MIN_RUNS", 2):
            drawn = self.page()
        self.assertEqual((list(drawn.exception), len(drawn.get("plotly_chart")) - len(page.get("plotly_chart"))), ([], 1))
        self.assertTrue(any(caption.startswith("One bubble for every program and tool with 2 calls or more.") for caption in captions(drawn)))

    def test_tools_tab_says_why_commands_failed_and_how_much_they_printed(self):
        self.save()
        page = self.page()
        self.assertEqual(table(page, "reason").values.tolist(), [["test failure", 1, "npm 1"]])
        self.assertIn("Commands printed 2.5 K characters, which the model then reads. Characters are a rough stand-in for tokens. What a tool call hands back is not in the logs.",
                      captions(page))
        saved = json.loads(EXAMPLE.read_text())
        quiet = [{**row, "calls": [{**call, "failed": 0, **({"characters": 0, "reasons": {}} if call["kind"] == "command" else {})} for call in row["calls"]]} for row in saved["turns"]]
        self.save(json.dumps({**saved, "turns": quiet}))
        silent = self.page()
        self.assertEqual(list(silent.exception), [])
        for expected in ["No command failed.", "The log kept no output of a command."]:
            self.assertIn(expected, captions(silent))
        self.assertEqual((len(page.get("plotly_chart")) - len(silent.get("plotly_chart")), headings(silent)), (2, headings(page)))  # the two charts are not drawn empty

    def test_tools_tab_gives_the_best_case_for_merging_calls_and_for_a_cache(self):
        self.save()
        page = self.page()
        # In the example every call came after a long step and no line ran twice, so neither would save anything.
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][10:13], [("Merging, at most", "0.0%"), ("Caching, at most", "0.0%"), ("Already together", "0%")])
        self.assertIn("No command line ran twice in a task.", captions(page))
        self.assertEqual([frame for frame in page.dataframe if "the call" in frame.value.columns], [])  # no short hop to list
        saved = json.loads(EXAMPLE.read_text())
        first = {**saved["turns"][0], "hops": saved["turns"][0]["hops"] + [{"from": "other shell commands", "to": "ByteQuay tools", "gap": "short hop", "times": 3, "seconds": 12},
                                                                            {"from": "ByteQuay tools", "to": "ByteQuay tools", "gap": "in one answer", "times": 5, "seconds": 0.5}],
                 "repeats": [{"program": "maven", "runs": 2, "seconds": 60, "unchangedRuns": 1, "unchangedSeconds": 18}]}
        self.save(json.dumps({**saved, "turns": [first, *saved["turns"][1:]]}))
        busy = self.page()
        self.assertEqual(list(busy.exception), [])
        # Of 1,020 seconds of logged turn time: 12 in short hops and 18 in a run a cache could have answered. Five of the twelve calls came in one answer.
        self.assertEqual([(tile.label, tile.value) for tile in busy.metric][10:13], [("Merging, at most", "1.2%"), ("Caching, at most", "1.8%"), ("Already together", "42%")])
        self.assertEqual(table(busy, "the call").values.tolist(), [["other shell commands", "ByteQuay tools", 3, 0.2]])
        self.assertEqual(len(busy.get("plotly_chart")) - len(page.get("plotly_chart")), 1)  # the repeats are drawn
        busy.sidebar.multiselect[0].set_value(["gpt-6-astra · medium"]).run()
        self.assertEqual([(tile.label, tile.value) for tile in busy.metric][10:13], [("Merging, at most", "0.0%"), ("Caching, at most", "0.0%"), ("Already together", "no calls")])

    def test_tools_tab_follows_the_side_bar_and_gives_no_rate_for_a_kind_that_never_ran(self):
        self.save()
        page = self.page()
        page.sidebar.multiselect[0].set_value(["gpt-6-astra · medium"]).run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][5:8], [("Calls", "1"), ("Commands failed", "100.0%"), ("Tool calls failed", "none ran")])
        self.assertEqual(table(page, "seconds a call")["name"].tolist(), ["npm"])
        self.assertEqual(table(page, "reason").values.tolist(), [["test failure", 1, "npm 1"]])
        page.sidebar.multiselect[0].set_value(["gpt-6-astra · max"]).run()
        self.assertIn("No command failed.", captions(page))  # the one failure of the example is in another turn

    def test_table_saved_before_calls_were_counted_says_so_on_the_tools_tab_and_every_other_tab_is_drawn(self):
        saved = json.loads(EXAMPLE.read_text())
        earlier = [{name: value for name, value in row.items() if name != "calls"} for row in saved["turns"]]
        self.save(json.dumps({**saved, "turns": earlier}))
        page = self.page()
        self.assertEqual((list(page.exception), [note.value for note in page.warning]), ([], []))
        self.assertEqual([note.value for note in page.info], ["This table was saved before the lab counted what this tab shows. Save it again with `uv run activity.py --save`", *NO_REVIEWS])
        # A table from before the lab read how much a command printed and why it failed, or how calls followed each other and which ran again, is treated the same.
        for missing in ("characters", "reasons"):
            between = [{**row, "calls": [{name: value for name, value in call.items() if name != missing} for call in row["calls"]]} for row in saved["turns"]]
            self.save(json.dumps({**saved, "turns": between}))
            self.assertEqual([note.value for note in self.page().info][0], "This table was saved before the lab counted what this tab shows. Save it again with `uv run activity.py --save`", missing)
        for missing in ("hops", "repeats"):
            self.save(json.dumps({**saved, "turns": [{name: value for name, value in row.items() if name != missing} for row in saved["turns"]]}))
            self.assertEqual([note.value for note in self.page().info][0], "This table was saved before the lab counted what this tab shows. Save it again with `uv run activity.py --save`", missing)
        self.assertEqual((headings(page), len(page.get("plotly_chart"))), (OVERVIEW + TIME + SLOW + COST + REVIEWS, 18))  # none of its tiles, tables or charts is drawn as empty
        self.assertEqual([tile.label for tile in page.metric][5:], ["Tokens in", "From the cache", "Tokens out", "Recorded cost"])

    def test_tools_tab_says_so_when_no_turn_called_anything(self):
        saved = json.loads(EXAMPLE.read_text())
        self.save(json.dumps({**saved, "turns": [{**row, "calls": []} for row in saved["turns"]]}))
        page = self.page()
        self.assertEqual((list(page.exception), [note.value for note in page.info]), ([], ["No turn of this choice called a command or a tool.", *NO_REVIEWS]))

    def test_choosing_a_repository_narrows_every_tab(self):
        self.save()
        page = self.page()
        self.assertEqual(page.sidebar.multiselect[1].options, ["bytequay/bytequay", "owner/repository"])
        page.sidebar.multiselect[1].set_value(["owner/repository"]).run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:3], [("Turn time", "0.2 h"), ("Tasks", "1"), ("Turns", "2")])
        self.assertEqual((table(page, "runs")["program"].tolist(), table(page, "typical minutes")["model and effort"].tolist()),
                         (["maven", "npm"], ["gpt-6-astra · max", "gpt-6-astra · medium"]))
        self.assertIn("2 of 4 turns. Days are in your own time zone.", captions(page))

    def test_reviews_tab_shows_what_became_of_the_findings_and_what_the_scorer_found(self):
        self.save()
        self.save_reviews()
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][-5:], [("Reviews", "2"), ("Runs", "3"), ("Findings", "4"), ("Held up", "50%"), ("Still open", "2")])
        self.assertEqual(table(page, "findings a run")[["model and effort", "runs", "asked again", "findings", "resolved", "withdrawn", "open"]].values.tolist(),
                         [["gpt-6-astra · max", 1, 0, 3, 1, 1, 1], ["gpt-6-sol · high", 1, 1, 0, 0, 0, 0], ["opus · extra", 1, 0, 1, 0, 0, 1]])
        self.assertEqual(table(page, "scored runs")[["setting", "scored runs", "comments", "found", "judge errors"]].values.tolist(),
                         [["before", "1 of 2", 2, 1, 0], ["today", "2 of 3", 5, 3, 1]])
        self.assertEqual(table(page, "run")[["setting", "run", "found"]].values.tolist(), [["before", "pr1_rep1", 1], ["today", "pr1_rep1", 1], ["today", "pr1_rep2", 2]])
        # The first two settings are set side by side at once, comment by comment.
        self.assertEqual(([(box.label, box.value) for box in page.selectbox], table(page, "better").values.tolist()),
                         ([("Experiment", "first"), ("Setting", "before"), ("Against", "today")], [["o/n#1", "0", "found", "found, found", "neither"], ["o/n#1", "1", "missed", "partly, found", "today"]]))
        self.assertIn("On 2 comments: before did better on 0, today on 1, and on 1 they did the same. Chance alone gives a split this uneven about 100% of the time, "
                      "so this does not show that one is better.", captions(page))
        for expected in ["Resolved: you or a development agent marked the finding done. Withdrawn: the reviewer took it back. Open: not decided yet. Held up is the resolved share of the first two.",
                         "before: not scored, and not counted as zero: pr1_rep2 (timed_out)", "How far to trust the judge: label pairs by hand on the Judge page.",
                         "today: mixed rules files, models or efforts in this setting, so its runs are not one comparison: aaaaaaaa reviewer-model high, bbbbbbbb reviewer-model high"]:
            self.assertIn(expected, captions(page))
        self.assertEqual((len(page.get("plotly_chart")), [note.value for note in page.info]), (27, []))  # five more charts, and nothing left to say how to save
        self.assertEqual([note.value for note in page.warning], [])

    def test_reviews_tab_says_when_the_reviews_were_not_saved_together_with_the_turns(self):
        self.save()
        reviews = json.loads((FIXTURE / "saved-reviews.json").read_text())
        self.save_reviews(json.dumps({**reviews, "savedAt": "2025-12-31T23:00:00Z"}))
        page = self.page()
        self.assertEqual(([note.value for note in page.warning], list(page.exception)),
                         ([f"These reviews were saved {dashboard.when('2025-12-31T23:00:00Z')}, at another time than the turns. Save both again with `uv run activity.py --save`"], []))
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][-5:][1], ("Runs", "3"))  # and they are still shown

    def test_reviews_tab_follows_the_days_the_model_and_the_repository_chosen_in_the_side_bar(self):
        self.save()
        self.save_reviews()
        page = self.page()
        page.sidebar.multiselect[0].set_value(["gpt-6-astra · max"]).run()
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][-5:], [("Reviews", "1"), ("Runs", "1"), ("Findings", "3"), ("Held up", "50%"), ("Still open", "1")])
        page.sidebar.multiselect[0].set_value([]).run()
        page.sidebar.multiselect[1].set_value(["bytequay/bytequay"]).run()
        self.assertIn("No review run matches this choice.", [note.value for note in page.info])  # the reviews were of other repositories
        page.sidebar.multiselect[1].set_value([]).run()
        last = charts.frames(json.loads(EXAMPLE.read_text()))["turns"]["day"].max()
        page.sidebar.date_input[0].set_value((last, last)).run()
        self.assertEqual((list(page.exception), "No review run matches this choice." in [note.value for note in page.info]), ([], True))  # every run started the day before

    def test_reviews_tab_says_none_decided_when_no_finding_is_resolved_or_withdrawn(self):
        self.save()
        reviews = json.loads((FIXTURE / "saved-reviews.json").read_text())
        self.save_reviews(json.dumps({**reviews, "findings": [{**finding, "state": "open"} for finding in reviews["findings"]]}))
        self.assertEqual([(tile.label, tile.value) for tile in self.page().metric][-2:], [("Held up", "none decided"), ("Still open", "4")])
        self.save_reviews(json.dumps({**reviews, "findings": []}))
        page = self.page()
        self.assertEqual((list(page.exception), [(tile.label, tile.value) for tile in page.metric][-3:]), ([], [("Findings", "0"), ("Held up", "none decided"), ("Still open", "0")]))

    def test_reviews_tab_names_a_saved_file_it_cannot_read_and_draws_nothing_from_it(self):
        self.save()
        reviews, scores = json.loads((FIXTURE / "saved-reviews.json").read_text()), json.loads((FIXTURE / "saved-scores.json").read_text())
        lost_run = {**reviews, "findings": [{**reviews["findings"][0], "run": 9}]}
        for name, text in [("cut off", '{"version": 1, "runs": ['), ("later version", json.dumps({**reviews, "version": 2})), ("no findings", json.dumps({"version": 1, "runs": []})),
                           ("a finding of a run that is not saved", json.dumps(lost_run)),
                           ("no time it was saved at", json.dumps({name: value for name, value in reviews.items() if name != "savedAt"}))]:
            with self.subTest(reviews=name):
                self.save_reviews(reviews=text)
                page = self.page()
                self.assertEqual(([note.value for note in page.warning], list(page.exception)),
                                 (["The saved reviews could not be read. Save them again with `uv run activity.py --save`"], []))
                self.assertEqual(([tile.label for tile in page.metric][-1], len(table(page, "scored runs"))), ("Recorded cost", 2))  # the scores below are still shown
        for name, text in [("cut off", '{"version": 1, "settings": ['), ("later version", json.dumps({**scores, "version": 3})),
                           ("a run without its counts", json.dumps({**scores, "settings": [{**scores["settings"][0], "runs": [{"run": "r", "case": "c"}]}]}))]:
            with self.subTest(scores=name):
                self.save_reviews(scores=text)
                page = self.page()
                self.assertEqual(([note.value for note in page.warning], list(page.exception)),
                                 (["The saved scores of first could not be read. Save them again with `uv run report.py EXPERIMENT --save`"], []))
                self.assertEqual([tile.label for tile in page.metric][-1], "Still open")  # the real reviews above are still shown
        self.save_reviews(scores=json.dumps({**scores, "settings": []}))
        self.assertIn("The saved scores of first hold no setting.", [note.value for note in self.page().info])

    def test_reviews_tab_lets_the_reader_pick_the_experiment_and_the_two_settings_to_compare(self):
        self.save()
        scores = json.loads((FIXTURE / "saved-scores.json").read_text())
        self.save_reviews()
        swapped = {**scores, "settings": [{**scores["settings"][1], "name": "later"}, {**scores["settings"][0], "name": "earlier"}, {**scores["settings"][0], "name": "unscored", "runs": []}]}
        self.save_scores("second", json.dumps(swapped))
        os.utime(self.folder / "experiments" / "first", (1_000_000_000, 1_000_000_000))  # worked on long before the second
        page = self.page()
        self.assertEqual(list(page.exception), [])
        experiment, first, second = page.selectbox
        self.assertEqual((experiment.options, experiment.value, first.options, first.value, second.value), (["second", "first"], "second", ["later", "earlier"], "later", "earlier"))
        self.assertEqual(table(page, "better")["better"].tolist(), ["neither", "later"])
        second.set_value("later").run()
        self.assertIn("On 2 comments: later did better on 0, later on 0, and on 2 they did the same.", " ".join(captions(page)))  # a setting against itself
        page.selectbox[0].set_value("first").run()
        self.assertEqual(([box.value for box in page.selectbox], table(page, "better")["better"].tolist()), (["first", "before", "today"], ["neither", "today"]))
        one = {**scores, "settings": [scores["settings"][0]]}
        self.save_scores("first", json.dumps(one))
        page = self.page()
        page.selectbox[0].set_value("first").run()
        self.assertEqual(([box.label for box in page.selectbox], list(page.exception)), (["Experiment"], []))  # one scored setting has nothing to be set against

    def test_reviews_tab_runs_the_picked_experiment_and_stops_it(self):
        self.save()
        kept = self.save_plan("trial")
        hint = ("Judging needs the DeepSeek key. Store it once in the Keychain with `uv run batch.py --store-key`, or judge in a terminal with "
                "`uv run batch.py trial --judge`")
        keyed = self.enterContext(mock.patch.object(batch, "has_key", return_value=False))  # never the real Keychain
        with mock.patch.object(batch, "start") as started:
            page = self.page()
            self.assertEqual(list(page.exception), [])
            self.assertEqual(([box.value for box in page.selectbox], run_buttons(page)), (["trial"], ["Run and judge what is missing"]))
            self.assertTrue({"Runs: 1 missing.", hint} <= set(captions(page)))
            self.assertIn("trial is not scored yet.", [note.value for note in page.info])
            page.button(key="start_runs").click().run()
        self.assertEqual([call.args[0]["folder"] for call in started.call_args_list], [kept])
        run = kept / "quick" / "pr-1_rep1"
        run.mkdir(parents=True)  # begun and not ended
        with mock.patch.object(batch, "runner", return_value=4321), mock.patch.object(batch, "stop") as stopped:
            page = self.page()
            self.assertEqual(("Runs: 1 running." in captions(page), run_buttons(page)), (True, ["Stop"]))
            page.button(key="stop_runs").click().run()
        self.assertEqual([call.args[0]["folder"] for call in stopped.call_args_list], [kept])
        (run / "runtime.json").write_text(json.dumps({"status": "completed"}))
        keyed.return_value = True
        page = self.page()
        keyed.return_value = False
        self.assertEqual(("Runs: 1 completed, 1 of them not judged yet." in captions(page), hint in captions(page), run_buttons(page)),
                         (True, False, ["Run and judge what is missing"]))  # a run left to judge, and the key to judge it
        self.save_scores("trial")
        page = self.page()
        self.assertEqual((hint in captions(page), run_buttons(page)), (True, ["Run and judge what is missing"]))  # scored before, and a run left to judge since
        (kept / "scores.json").unlink()
        (run / "judgement.json").write_text("{}")
        page = self.page()
        self.assertEqual(("Runs: 1 completed." in captions(page), hint in captions(page), run_buttons(page)),
                         (True, False, ["Run and judge what is missing"]))  # judged and not scored: a start only saves the scores, which needs no key
        self.save_scores("trial")
        (kept / "batch.log").write_text("".join(f"line {number}\n" for number in range(8)))
        page = self.page()
        self.assertEqual(("Runs: 1 completed." in captions(page), run_buttons(page), page.code[-1].value),
                         (True, [], "line 3\nline 4\nline 5\nline 6\nline 7"))  # nothing left to do, and the end of what the last start said
        chosen = {"every": "week", "last": "2026-10-14T03:00:00+00:00"}
        (self.folder / "schedule.json").write_text(json.dumps({"version": 1, "experiments": {"trial": chosen}}))
        self.assertIn(f"A new round every week, the next on {batch.next_round(chosen).astimezone():%Y-%m-%d}.", captions(self.page()))
        (self.folder / "schedule.json").write_text("{")
        self.assertIn("The schedule cannot be read. Set it again with `uv run batch.py NAME --every week`", captions(self.page()))
        (kept / "scores.json").unlink()
        (kept / "plan.json").write_text("{")
        page = self.page()
        self.assertEqual((list(page.exception), run_buttons(page), [note.value for note in page.info][-1]),
                         ([], [], "trial is not scored yet. Score it with `uv run batch.py trial --judge`"))  # the page cannot run it, so it says how
        self.assertIn(f"It cannot run: {kept / 'plan.json'}: it is not JSON", captions(page))

    def test_models_tab_says_why_when_the_database_was_not_read(self):
        table = json.loads(EXAMPLE.read_text())
        logged = [{**row, **dict.fromkeys(activity.FACTS)} for row in table["turns"] if row["logged"]]
        self.save(json.dumps({**table, "database": {"problem": "the database is not there"}, "turns": logged}))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([note.value for note in page.info], ["Model, effort and tokens were not saved: the database is not there. Save again with `uv run activity.py --save`",
                                                              "Tokens and cost were not saved: the database is not there. Save again with `uv run activity.py --save`", *NO_REVIEWS])
        self.assertEqual((len(page.metric), len(page.sidebar.multiselect)), (13, 0))  # the tabs read from the logs are whole, and there is no model to pick

    def test_cost_tab_adds_up_tokens_and_keeps_recorded_cost_apart_from_what_it_cannot_know(self):
        self.save()
        page = self.page()
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][13:], [("Tokens in", "1.1 M"), ("From the cache", "90.4%"), ("Tokens out", "7.0 K"), ("Recorded cost", "$1.50")])
        self.assertEqual(headings(page)[-7:], COST + REVIEWS)
        self.assertIn("Tokens are known for 3 of 4 turns. Each amount is the turn's own.", captions(page))
        cost = table(page, "how")
        self.assertEqual(cost[["model and effort", "dollars", "how"]].values.tolist()[0], ["opus · high", 1.5, "recorded"])
        self.assertEqual(cost["how"].tolist()[1:], ["unknown", "unknown"])  # no recorded cost and no price: nothing is made up
        # The models without a cost are named with a price table to fill in, and nothing was written for the reader.
        self.assertIn(f"No cost is known for gpt-6-astra. To estimate it, save this as `prices.json` in `{self.folder}` with your own prices, in dollars for a million tokens:",
                      captions(page))
        self.assertEqual(json.loads(page.code[0].value), {"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": 0, "cachedInput": 0, "output": 0}}})
        self.assertEqual(sorted(path.name for path in self.folder.iterdir()), ["turns.json"])

    def test_cost_tab_estimates_from_the_readers_own_prices(self):
        self.save()
        prices = {"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": 2, "cachedInput": 0.2, "output": 10}}}
        (self.folder / "prices.json").write_text(json.dumps(prices))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][16:], [("Recorded cost", "$1.50"), ("Estimated cost", "$0.45")])
        self.assertEqual(table(page, "how")[["model and effort", "dollars", "how"]].values.tolist(),
                         [["opus · high", 1.5, "recorded"], ["gpt-6-astra · max", 0.31, "estimated"], ["gpt-6-astra · medium", 0.14, "estimated"]])
        self.assertEqual((len(page.code), [caption for caption in captions(page) if caption.startswith("No cost is known")]), (0, []))

    def test_price_table_to_fill_in_keeps_the_prices_already_given(self):
        self.save()
        opus = {"input": 15.0, "cachedInput": 1.5, "output": 75.0}
        (self.folder / "prices.json").write_text(json.dumps({"version": 1, "dollarsPerMillionTokens": {"opus": opus}}))
        page = self.page()
        self.assertEqual(json.loads(page.code[0].value), {"version": 1, "dollarsPerMillionTokens": {"opus": opus, "gpt-6-astra": {"input": 0, "cachedInput": 0, "output": 0}}})

    def test_price_table_in_another_shape_estimates_nothing_and_says_so(self):
        self.save()
        for name, text in [("cut off", '{"version": 1, "dollarsPer'), ("later version", json.dumps({"version": 2, "dollarsPerMillionTokens": {}})),
                           ("no prices", json.dumps({"version": 1})), ("a price missing", json.dumps({"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": 2}}})),
                           ("text for a price", json.dumps({"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": "cheap", "cachedInput": 0, "output": 0}}})),
                           ("below zero", json.dumps({"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": -2, "cachedInput": 0, "output": 0}}})),
                           ("a list", json.dumps({"version": 1, "dollarsPerMillionTokens": [1, 2]}))]:
            with self.subTest(name=name):
                (self.folder / "prices.json").write_text(text)
                page = self.page()
                self.assertEqual(([note.value for note in page.warning], list(page.exception)), (["The price table `prices.json` could not be read, so no cost is estimated."], []))
                self.assertEqual([tile.label for tile in page.metric][13:], ["Tokens in", "From the cache", "Tokens out", "Recorded cost"])
                self.assertEqual(table(page, "how")["how"].tolist(), ["recorded", "unknown", "unknown"])

    def test_choosing_days_changes_every_number(self):
        self.save()
        page = self.page()
        last = charts.frames(json.loads(EXAMPLE.read_text()))["turns"]["day"].max()
        page.sidebar.date_input[0].set_value((last, last)).run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric][:3], [("Turn time", "0.1 h"), ("Tasks", "2"), ("Turns", "2")])
        self.assertIn("3 of 4 turns. Days are in your own time zone.", captions(page))
        self.assertIn("Two or more tools ran at once for 0.3 minutes; the most at once was 2.", captions(page))
        self.assertEqual(table(page, "runs")["program"].tolist(), ["npm", "git"])

    def test_what_was_left_out_when_saving_is_said_in_the_side_bar(self):
        table = json.loads(EXAMPLE.read_text())
        self.save(json.dumps({**table, "since": "2026-01-01", "task": "aaaa", "leftOut": {"skippedLines": 2, "unreadLogs": 1}}))
        captions = [caption.value for caption in self.page().caption]
        for expected in ["Only turns that started on or after 2026-01-01 were saved.", "Only the task whose id starts with aaaa was saved.",
                         "2 log lines cut off or in an old format were skipped.", "1 task log with no turn in the current format was left out."]:
            self.assertIn(expected, captions)

    def test_nothing_saved_says_how_to_save(self):
        page = self.page()
        self.assertEqual(([note.value for note in page.info], len(page.metric), list(page.exception)), ([f"Nothing saved yet. {SAVED}"], 0, []))

    def test_table_without_a_turn_says_so(self):
        self.save(json.dumps({**json.loads(EXAMPLE.read_text()), "turns": []}))
        page = self.page()
        self.assertEqual(([note.value for note in page.info], len(page.metric), list(page.exception)), (["The saved turn table holds no turn."], 0, []))

    def test_file_in_another_shape_is_said_to_be_unreadable_and_is_never_drawn(self):
        table = json.loads(EXAMPLE.read_text())
        unknown_part = json.loads(EXAMPLE.read_text())
        unknown_part["turns"][0]["groups"]["a part from a later version"] = 5
        for name, text in [("cut off", EXAMPLE.read_text()[:200]), ("not a table", "[]"), ("later version", json.dumps({**table, "version": 2})),
                           ("no turns", json.dumps({"version": 1, "savedAt": table["savedAt"]})), ("unknown part", json.dumps(unknown_part)),
                           ("text for a number", json.dumps({**table, "turns": [{**table["turns"][0], "seconds": "long"}]}))]:
            with self.subTest(name=name):
                self.save(text)
                page = self.page()
                self.assertEqual(([note.value for note in page.warning], list(page.exception)),
                                 (["The saved turn table could not be read. Save it again with `uv run activity.py --save`"], []))
                self.assertEqual((len(page.metric), len(page.get("plotly_chart")), len(page.info)), (0, 0, 0))

    def test_page_started_another_way_shows_nothing(self):
        self.save()
        for address in (None, "0.0.0.0", "192.168.1.5"):
            with self.subTest(address=address):
                config.set_option("server.address", address)
                page = self.page()
                self.assertEqual([note.value for note in page.error], ["This page is only shown to this machine. Start it with `uv run dashboard.py`"])
                self.assertEqual((len(page.title), len(page.metric), len(page.get("plotly_chart"))), (0, 0, 0))

    def save_turns(self, *rows):
        self.save(json.dumps(test_suggest.table(*rows)))

    def write_log(self, *events):
        log = self.runtime / "task-1" / "events.jsonl"
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text("".join(json.dumps(made) + "\n" for made in in_order(*events)))

    def test_suggestions_tab_lists_what_to_change_for_the_days_picked(self):
        self.save_turns(test_suggest.row("2026-01-01T12:00:00Z", seconds=1200.0, long_runs=[("maven", 1830.0)]),
                        test_suggest.row("2026-01-11T12:00:00Z", seconds=1200.0, long_runs=[("maven", 900.0)]))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([text.value for text in page.markdown if text.value.startswith("##### ")], ["##### 1. Make long maven runs shorter"])
        self.assertIn("Could save up to 28.3 minutes a week. Watch: long maven minutes in an agent hour, now 68.2.", captions(page))  # both turns, over 11 days
        self.assertIn("Where: a recipe in the repository's memory, on the Memory page, which every turn of that repository is given; "
                      f"`{suggest.INSTRUCTIONS}` (compose)", [text.value for text in page.markdown])
        page.radio(key="look").set_value("7 days").run()
        self.assertIn("Could save up to 14.5 minutes a week. Watch: long maven minutes in an agent hour, now 45.0.", captions(page))  # only the last turn

    def test_suggestions_tab_notes_a_change_and_then_shows_its_number_before_and_since(self):
        self.save_turns(test_suggest.row("2026-01-11T12:00:00Z", seconds=1200.0, long_runs=[("maven", 900.0)]))
        page = self.page()
        self.assertEqual([button.label for button in page.button if button.key == "noted_1"], ["I made this change"])
        page.button(key="noted_1").click().run()
        self.assertEqual(json.loads((self.folder / "changes.json").read_text())["changes"], [{"day": date.today().isoformat(), "what": "Make long maven runs shorter"}])
        self.assertIn(f"You noted this change on {suggest.said(date.today())}. long maven minutes in an agent hour: - in the 7 days before, - since, over 0 turns. "
                      "Too few tasks of one model and effort on both sides to tell yet.",
                      captions(page))
        self.assertEqual([button.label for button in page.button if button.key == "noted_1"], ["I changed it again"])
        self.assertEqual([button.key for button in page.button if button.key == "doing_1"], [])  # made, so no longer being worked on
        self.assertEqual(page.dataframe[0].value[["suggestion", "stands"]].values.tolist(), [["Make long maven runs shorter", "made"]])

    def test_suggestions_tab_marks_one_as_being_worked_on_or_not_wanted_and_brings_it_back(self):
        self.save_turns(test_suggest.row("2026-01-11T12:00:00Z", seconds=1200.0, long_runs=[("maven", 900.0)]))
        page = self.page()
        page.button(key="doing_1").click().run()
        self.assertEqual(json.loads((self.folder / "record.json").read_text())["suggestions"],
                         [{"title": "Make long maven runs shorter", "state": "doing", "day": date.today().isoformat(), "reason": ""}])
        self.assertIn(f"Being worked on since {suggest.said(date.today())}.", captions(page))
        self.assertEqual([button.label for button in page.button if button.key == "doing_1"], ["Not working on it"])
        page.button(key="doing_1").click().run()
        self.assertEqual(([button.label for button in page.button if button.key == "doing_1"], json.loads((self.folder / "record.json").read_text())["suggestions"]),
                         (["Working on it"], []))
        page.text_input(key="why_1").input("  the agent needs the full build ").run()
        page.button(key="drop_1").click().run()
        self.assertEqual([text.value for text in page.markdown if text.value.startswith("##### 1.")], [])  # no longer listed
        self.assertIn("Every suggestion of these days is marked not wanted.", captions(page))
        self.assertEqual(page.dataframe[0].value[["suggestion", "stands"]].values.tolist(), [["Make long maven runs shorter", "not wanted: the agent needs the full build"]])
        page.button(key="back_0").click().run()
        self.assertEqual(([text.value for text in page.markdown if text.value.startswith("##### 1.")], json.loads((self.folder / "record.json").read_text())["suggestions"]),
                         (["##### 1. Make long maven runs shorter"], []))

    def test_suggestions_tab_hands_its_brief_to_bytequay_only_on_the_page_bytequay_started(self):
        self.save_turns(test_suggest.row("2026-01-11T12:00:00Z", seconds=1200.0, long_runs=[("maven", 900.0)]))
        page = self.page()
        (brief,) = [code.value for code in page.tabs[0].code]
        self.assertTrue(brief.startswith("Make long maven runs shorter\n\nWhat the lab saw: "))
        self.assertEqual(brief.splitlines()[-1], f"{suggest.LINE}Make long maven runs shorter")
        self.assertEqual(page.get("link_button"), [], "started from the terminal, ByteQuay would not take the link")
        started_by_bytequay(self)
        page = self.page()
        self.assertEqual(list(page.exception), [])
        (link,) = page.get("link_button")
        address = urlsplit(link.proto.url)
        self.assertEqual((link.proto.label, address.scheme, address.netloc, address.path), ("Fix it", "http", "127.0.0.1:49152", "/lab-session/task"))
        self.assertEqual(parse_qs(address.query), {"repository": [suggest.REPOSITORY], "text": [brief]})
        page.button(key="noted_1").click().run()
        self.assertEqual(page.get("link_button"), [], "a made change is not fixed again from here")

    def test_a_task_started_from_a_brief_is_on_it_and_needs_no_mark(self):
        self.save_turns(test_suggest.row("2026-01-11T12:00:00Z", seconds=1200.0, long_runs=[("maven", 900.0)]))
        started_by_bytequay(self)
        write_fix(self.runtime, {"type": "user_message", "occurredAt": "2026-01-12T09:00:00Z", "payload": {"text": f"edited\n{suggest.LINE}Make long maven runs shorter"}})
        page = self.page()
        self.assertIn(f"A ByteQuay task is on it since {suggest.said(date(2026, 1, 12))}. Completing that task notes the change here.", captions(page))
        self.assertEqual((page.get("link_button"), (self.folder / "changes.json").exists(), (self.folder / "record.json").exists()), ([], False, False))

    def test_a_completed_task_started_from_a_brief_notes_the_change_on_its_day(self):
        self.save_turns(test_suggest.row("2026-01-11T12:00:00Z", seconds=1200.0, long_runs=[("maven", 900.0)]))
        write_fix(self.runtime, {"type": "user_message", "occurredAt": "2026-01-12T09:00:00Z", "payload": {"text": f"x\n{suggest.LINE}Make long maven runs shorter"}},
                       {"type": "lifecycle", "occurredAt": "2026-01-13T10:00:00Z", "payload": {"phase": "TASK_COMPLETED", "message": "Task completed"}})
        day = datetime.fromisoformat("2026-01-13T10:00:00Z").astimezone().date()
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual(json.loads((self.folder / "changes.json").read_text())["changes"], [{"day": day.isoformat(), "what": "Make long maven runs shorter"}])
        self.assertTrue(any(text.startswith(f"You noted this change on {suggest.said(day)}.") for text in captions(page)))
        page.run()
        self.assertEqual(len(json.loads((self.folder / "changes.json").read_text())["changes"]), 1, "noted once")

    def test_suggestions_tab_with_nothing_to_suggest_says_so(self):
        self.save()
        self.assertIn("Nothing in these days passes the limits for a suggestion.", captions(self.page()))

    def test_refresh_button_saves_the_tables_again_from_the_logs(self):
        self.save()
        page = self.page()
        page.button(key="refresh").click().run()
        self.assertEqual([note.value for note in page.warning], ["Application records could not be refreshed; agent statistics may be stale."])
        database(self.folder / "bytequay-1.0.db")
        self.write_log(ended(43200, 43800))
        page.button(key="refresh").click().run()
        self.assertEqual((list(page.error), list(page.exception)), ([], []))
        self.assertEqual([(row["task"], row["seconds"]) for row in json.loads((self.folder / "turns.json").read_text())["turns"]], [("task-1", 600)])
        self.assertIn("1 of 1 turns. Days are in your own time zone.", captions(page))  # the page shows what it just saved
        self.assertTrue(all((self.folder / name).is_file() for name in ("reviews.json", "tuning.json", "context.json", "memory.json")))

    def test_external_refresh_invalidates_cached_logs_without_waiting_five_minutes(self):
        self.save_turns(test_suggest.row("2026-01-01T12:00:00Z"))
        first = asked(43201, 43501, CODEX, "/bin/zsh -lc 'git status'")
        self.write_log(ended(43200, 44000), first)
        page = self.page()
        asks = lambda: next(tile.value for tile in page.metric if tile.label == "Asks")
        self.assertEqual(asks(), "1")
        self.write_log(ended(43200, 44000), first, asked(43502, 43562, CODEX, "/bin/zsh -lc 'git diff'", key="a2"))
        page.run()
        self.assertEqual(asks(), "1")
        made = json.loads((self.folder / "turns.json").read_text())
        self.save(json.dumps(made | {"savedAt": "2026-01-02T01:00:00Z"}))
        page.run()
        self.assertEqual(list(page.exception), [])
        self.assertEqual(asks(), "2")

    def test_external_refresh_also_invalidates_completed_fix_status(self):
        import test_record
        started_by_bytequay(self)
        self.save_turns(*[test_suggest.row("2026-01-01T12:00:00Z", task=f"task-{number}", long_runs=[("maven", 600)]) for number in range(3)])
        page = self.page()
        self.assertTrue(any(button.label == "Fix it" for button in page.get("link_button")))
        write_fix(self.runtime, test_record.asked(test_record.BRIEF, "2026-01-01T13:00:00Z"), test_record.phase("TASK_COMPLETED", "2026-01-02T00:00:00Z"))
        made = json.loads((self.folder / "turns.json").read_text())
        self.save(json.dumps(made | {"savedAt": "2026-01-02T01:00:00Z"}))
        page.run()
        self.assertEqual(list(page.exception), [])
        changes = json.loads((self.folder / "changes.json").read_text())["changes"]
        self.assertTrue(any(change["what"] == test_record.MAVEN for change in changes))
        self.assertFalse(any(button.label == "Fix it" and test_record.MAVEN in parse_qs(urlsplit(button.url).query).get("text", [""])[0]
                             for button in page.get("link_button")))

    def test_approvals_tab_shows_the_asks_and_what_the_rules_picked_would_answer(self):
        self.save_turns(test_suggest.row("2026-01-01T12:00:00Z"))
        self.write_log(ended(43200, 44000), asked(43201, 43501, CODEX, "/bin/zsh -lc './mvnw -o -pl core test > /tmp/x.log'", "EXPIRED"),
                       asked(43502, 43562, CODEX, "/bin/zsh -lc './mvnw -o -pl web test'", key="a2"), command(43600, 43610, "/bin/zsh -lc './mvnw -o -pl web verify'"),
                       ended(216000, 216100, name="t2"), asked(216001, 216050, CODEX, "/bin/zsh -lc './mvnw -o -pl late test'", turn="t2", key="a3"))  # a day the table does not hold
        page = self.page()
        tiles = lambda: {tile.label: tile.value for tile in page.metric}
        self.assertEqual(list(page.exception), [])
        self.assertEqual({label: tiles()[label] for label in ("Asks", "Minutes waited", "Ran out", "Ended a turn", "Codex reviewer")},
                         {"Asks": "2", "Minutes waited": "6.0", "Ran out": "1", "Ended a turn": "0", "Codex reviewer": "0"})
        self.assertEqual(table(page, "kind").values.tolist(), [["codex", "command", 2, 1, 6.0]])
        self.assertEqual(table(page, "asks that need it").values.tolist(), [["Bash(./mvnw -o -pl:*)", 2, 2, 6.0, 2, 0]])
        self.assertIn("Pick or write a rule to see what it would have answered.", captions(page))
        page.multiselect(key="rules").set_value(["Bash(./mvnw -o -pl:*)"]).run()
        self.assertEqual({label: tiles()[label] for label in ("Asks answered", "Minutes saved", "Turns kept going", "Commands covered")},
                         {"Asks answered": "2 of 2", "Minutes saved": "6.0", "Turns kept going": "0 of 0", "Commands covered": "1"})
        self.assertIn("Risks in what they answer: writes a file 1. A risk is a word in the line worth a closer look, not a judgment that a line is safe.", captions(page))
        page.text_area(key="own_rules").input("./mvnw -o").run()
        self.assertEqual([note.value for note in page.warning], ["Not a rule, so left out: ./mvnw -o. A rule looks like Bash(./mvnw -o:*) or Bash(git status)."])
        shown = [text.value for text in [*page.tabs[5].caption, *page.tabs[5].markdown, *page.tabs[5].warning]]
        for private in ("core", "web", "/tmp", "x.log"):
            self.assertFalse(any(private in text for text in shown), private)  # rule words only, never a path or an argument


class TestSource(unittest.TestCase):
    def test_text_is_shown_as_it_is_and_never_read_as_markdown(self):
        self.assertEqual(dashboard.plain("Bash(./mvnw -o:*) costs $5, a_b and `x`"), "Bash(./mvnw -o:\\*) costs \\$5, a\\_b and \\`x\\`")

    def test_page_has_no_bare_expression_that_streamlit_would_print(self):
        # Streamlit writes the value of any expression that stands alone in the page's file, wherever it stands. A call is
        # the one kind it leaves alone, so `draw(x) if y else z` on a line of its own prints "None" under the chart.
        tree = ast.parse(Path(dashboard.__file__).read_text())
        bare = [node.lineno for node in ast.walk(tree) if isinstance(node, ast.Expr) and not isinstance(node.value, ast.Call)
                and not (isinstance(node.value, ast.Constant) and isinstance(node.value.value, str))]
        self.assertEqual(bare, [])


    def test_the_task_link_is_the_one_the_desktop_takes(self):
        source = test_suggest.ROOT / "frontend" / "plugins" / "lab" / "src" / "labProcess.ts"
        if not source.is_file():
            self.skipTest("the desktop's source is not beside the lab")
        text = source.read_text()
        # The desktop starts the page at its own address, then takes only a link under it that names a repository and a text.
        for part in ("`http://127.0.0.1:${port}/${basePath}/`", "'--port', String(port), '--base-path', basePath", "url.startsWith(`${dashboard.address}task?`)",
                     "query.get('repository')", "query.get('text')", "const TASK_TEXT_LIMIT = 20_000;"):
            self.assertIn(part, text)
        self.assertEqual(dashboard.HOST, "127.0.0.1")


class TestStart(unittest.TestCase):
    def test_plain_start_hands_over_to_streamlit_and_keeps_the_page_on_this_machine(self):
        seen = []
        with mock.patch.object(sys, "argv", ["dashboard.py", "/some/folder", "--port", "9000", "--base-path", "lab-session"]), mock.patch.object(dashboard, "get_script_run_ctx", return_value=None), \
                mock.patch("streamlit.web.cli.main", side_effect=lambda: seen.extend(sys.argv) or 7), self.assertRaises(SystemExit) as stopped:
            dashboard.main()
        self.assertEqual(stopped.exception.code, 7)  # the page's start ends as Streamlit ends
        self.assertEqual(seen[:3], ["streamlit", "run", dashboard.__file__])
        separator = seen.index("--")
        options = dict(zip(seen[3:separator:2], seen[4:separator:2]))
        self.assertEqual((options["--server.address"], options["--server.port"], options["--server.headless"], options["--browser.gatherUsageStats"]),
                         ("127.0.0.1", "9000", "true", "false"))
        self.assertEqual(options["--server.baseUrlPath"], "lab-session")
        self.assertEqual(seen[separator:], ["--", "/some/folder", "--runtime", str(activity.RUNTIME), "--database", str(activity.RUNTIME.parent / dashboard.monitor.DATABASE.name),
                                           "--logs", str(dashboard.app_logs.LOGS)])

    def test_explicit_database_and_runtime_are_forwarded_to_every_page(self):
        seen = []
        with mock.patch.object(sys, "argv", ["dashboard.py", "/lab", "--runtime", "/logs", "--database", "/db", "--logs", "/backend-logs"]), \
                mock.patch.object(dashboard, "get_script_run_ctx", return_value=None), \
                mock.patch("streamlit.web.cli.main", side_effect=lambda: seen.extend(sys.argv) or 0), self.assertRaises(SystemExit):
            dashboard.main()
        self.assertEqual(seen[seen.index("--"):], ["--", "/lab", "--runtime", "/logs", "--database", "/db", "--logs", "/backend-logs"])

    def test_managed_dashboard_exits_when_its_parent_disappears(self):
        with mock.patch.dict(os.environ, {"BYTEQUAY_LAB_PARENT_PID": "321"}), mock.patch.object(dashboard.threading, "Thread") as thread:
            dashboard.stop_with_parent()
        with mock.patch.object(os, "getppid", side_effect=[321, 1]), mock.patch.object(dashboard.clock, "sleep") as sleep, mock.patch.object(os, "_exit") as exit:
            thread.call_args.kwargs["target"]()
        self.assertTrue(thread.call_args.kwargs["daemon"])
        sleep.assert_called_once_with(1)
        exit.assert_called_once_with(0)

    def test_manual_dashboard_has_no_parent_watch(self):
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(dashboard.threading, "Thread") as thread:
            dashboard.stop_with_parent()
        thread.assert_not_called()

    def test_default_folder_and_port_are_the_labs_own(self):
        seen = []
        with mock.patch.object(sys, "argv", ["dashboard.py"]), mock.patch.object(dashboard, "get_script_run_ctx", return_value=None), \
                mock.patch("streamlit.web.cli.main", side_effect=lambda: seen.extend(sys.argv) or 0), self.assertRaises(SystemExit):
            dashboard.main()
        self.assertEqual((seen[seen.index("--server.port") + 1], seen[seen.index("--") + 1], seen[seen.index("--runtime") + 1]),
                         (str(dashboard.PORT), str(dashboard.saved.FOLDER), str(activity.RUNTIME)))
        self.assertEqual(seen[seen.index("--server.baseUrlPath") + 1], "")


if __name__ == "__main__":
    unittest.main()
