import json
import logging
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import dashboard
import judge
import labels
import saved

PAGE = Path(__file__).with_name("pages") / "Judge.py"
# As in test_dashboard: outside a running page Streamlit warns on every start that there is no page.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())


def run(experiment, setting, name, verdicts, calls):
    folder = experiment / setting / name
    folder.mkdir(parents=True)
    (folder / "judgement.json").write_text(json.dumps({"verdicts": [{"commentId": comment, "verdict": verdict, "leadIds": [], "reason": "why"}
                                                                    for comment, verdict in verdicts]}))
    (folder / "judge-calls.json").write_text(json.dumps({"system": judge.SYSTEM, "calls": [{"commentId": comment, "user": asked, "answer": "{}", "error": None}
                                                                                           for comment, asked in calls]}))


class Experiment(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve()
        self.experiment = self.folder / "experiments" / "trial"
        run(self.experiment, "opus", "pr-1_rep1", [(11, "FOUND"), (12, "MISSED")], [(11, "leads A, comment 11"), (11, "asked again"), (12, "leads A, comment 12")])
        run(self.experiment, "astra", "pr-1_rep1", [(11, "PARTLY"), (12, "MISSED")], [(11, "leads B, comment 11"), (12, "leads B, comment 12")])
        run(self.experiment, "astra", "pr-2_rep1", [(21, "MISSED")], [])  # a run without leads, so the judge was asked nothing
        (self.experiment / "opus" / "pr-2_rep1").mkdir()  # a run not judged yet


class TestLabels(Experiment):
    def test_every_comment_the_judge_answered_is_one_pair_in_a_fixed_mixed_order(self):
        found = labels.pairs(self.experiment)
        self.assertEqual(sorted((pair.key, pair.shown, pair.verdict) for pair in found),
                         [("astra/pr-1_rep1/11", "leads B, comment 11", "PARTLY"), ("astra/pr-1_rep1/12", "leads B, comment 12", "MISSED"),
                          ("opus/pr-1_rep1/11", "leads A, comment 11", "FOUND"), ("opus/pr-1_rep1/12", "leads A, comment 12", "MISSED")])
        self.assertEqual(labels.pairs(self.experiment), found)  # the same order on every visit

    def test_labels_are_kept_in_the_order_given_and_one_can_be_taken_back(self):
        labels.give(self.experiment, "opus/pr-1_rep1/11", "FOUND")
        labels.give(self.experiment, "astra/pr-1_rep1/11", "MISSED")
        labels.give(self.experiment, "opus/pr-1_rep1/11", "PARTLY")  # given again, it becomes the newest
        self.assertEqual(list(labels.read(self.experiment).items()), [("astra/pr-1_rep1/11", "MISSED"), ("opus/pr-1_rep1/11", "PARTLY")])
        labels.give(self.experiment, "opus/pr-1_rep1/11", None)
        self.assertEqual(labels.read(self.experiment), {"astra/pr-1_rep1/11": "MISSED"})
        saved.write(self.experiment, labels.FILE, {"version": 1, "labels": [{"pair": "opus/pr-1_rep1/11", "label": "found"}]})
        with self.assertRaises(ValueError):  # a file changed by hand is never read wrong
            labels.read(self.experiment)

    def test_agreement_counts_the_labelled_pairs_by_label_and_answer(self):
        found = labels.pairs(self.experiment)
        made = labels.agreement(found, {"opus/pr-1_rep1/11": "FOUND", "astra/pr-1_rep1/11": "FOUND", "opus/pr-1_rep1/12": "MISSED", "gone/run/1": "FOUND"})
        self.assertEqual((made["compared"], made["agree"], round(made["rate"], 3)), (3, 2, 0.667))
        self.assertEqual((made["table"]["FOUND"], made["table"]["MISSED"]), ({"FOUND": 1, "PARTLY": 1, "MISSED": 0}, {"FOUND": 0, "PARTLY": 0, "MISSED": 1}))
        self.assertIsNone(labels.agreement(found, {})["rate"])


class TestPage(Experiment):
    def setUp(self):
        super().setUp()
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder)])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self):
        return AppTest.from_file(str(PAGE), default_timeout=30).run()

    def test_a_pair_is_shown_as_the_judge_was_asked_and_each_label_is_kept(self):
        page = self.page()
        first, second = labels.pairs(self.experiment)[:2]
        self.assertEqual(([block.value for block in page.code], list(page.exception)), ([first.shown], []))
        self.assertIn("Labelled 0 of 4. 30 give a first number.", [caption.value for caption in page.caption])
        self.assertEqual((len(page.metric), len(page.dataframe)), (0, 0))  # nothing of the judge's answers before a label
        page.button(key=f"label_{first.verdict}").click().run()
        self.assertEqual(labels.read(self.experiment), {first.key: first.verdict})
        self.assertEqual([(tile.label, tile.value) for tile in page.metric], [("You and the judge agree", "1 of 1")])
        self.assertEqual([block.value for block in page.code], [second.shown])
        page.button(key="label_back").click().run()
        self.assertEqual(([block.value for block in page.code], labels.read(self.experiment)), ([first.shown], {}))

    def test_with_every_pair_labelled_it_says_so_and_how_far_the_two_agree(self):
        for pair in labels.pairs(self.experiment):
            labels.give(self.experiment, pair.key, "MISSED")
        page = self.page()
        self.assertEqual(([note.value for note in page.success], page.metric[0].value), (["Every pair is labelled."], "2 of 4"))
        self.assertEqual(page.dataframe[0].value.set_index("you").loc["MISSED"].tolist(), [1, 1, 2])

    def test_an_experiment_of_unseen_cases_is_never_offered_for_labels(self):
        unseen = self.folder / "experiments" / "unseen"
        run(unseen, "astra", "pr-3_rep1", [(31, "FOUND")], [(31, "leads C, comment 31")])
        (unseen / "plan.json").write_text(json.dumps({"version": 1, "unseen": True}))
        self.assertEqual(self.page().selectbox[0].options, ["trial"])
        shutil.rmtree(self.experiment)
        self.assertEqual([note.value for note in self.page().info], ["No experiment has a judged run yet. Run and score one with `uv run batch.py NAME --judge`"])

    def test_without_a_judged_run_it_says_how_to_get_one(self):
        shutil.rmtree(self.experiment)
        self.assertEqual([note.value for note in self.page().info], ["No experiment has a judged run yet. Run and score one with `uv run batch.py NAME --judge`"])


if __name__ == "__main__":
    unittest.main()
