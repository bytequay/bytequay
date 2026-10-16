import json
import logging
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest import mock

from streamlit import config
from streamlit.testing.v1 import AppTest

import dashboard
import memory
import saved

PAGE = Path(__file__).with_name("pages") / "Memory.py"
# As in test_dashboard: outside a running page Streamlit warns on every start that there is no page.
logging.getLogger("streamlit.runtime.scriptrunner_utils.script_run_context").addFilter(lambda record: "missing ScriptRunContext" not in record.getMessage())


class Database(unittest.TestCase):
    """Three memories, two turns given two of them, and five findings, two of which cite one."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name).resolve()
        self.database = self.folder / "bytequay-1.0.db"
        connection = sqlite3.connect(self.database)
        connection.executescript("""
            CREATE TABLE repository_memory (id TEXT, kind TEXT, delivered INTEGER, cited INTEGER, created_at TEXT, title TEXT, text TEXT);
            CREATE TABLE agent_turn (id TEXT, task_id TEXT, memories_json TEXT, input_tokens INTEGER, model TEXT);
            CREATE TABLE agent_review_finding (id TEXT, state TEXT, memories_json TEXT);
            INSERT INTO repository_memory VALUES ('m1', 'RULE', 2, 2, '2026-10-03T00:00:00Z', 'Assert the cause chain', 'secret memory text'),
                                                 ('m2', 'RULE', 2, 0, '2026-10-03T00:00:00Z', 'Prefer small diffs', 'secret memory text'),
                                                 ('m3', 'RECIPE', 0, 0, '2026-10-04T00:00:00Z', 'Run one module', 'secret memory text');
            INSERT INTO agent_turn VALUES ('turn-1', 'task-1', '[{"id":"m1","line":"secret line one"},{"id":"m2","line":"secret line two"}]', 1000, 'opus'),
                                          ('turn-2', 'task-2', '[{"id":"m1","line":"secret line"}]', 2000, 'opus'), ('turn-3', 'task-2', NULL, 3000, 'opus');
            INSERT INTO agent_review_finding VALUES ('f1', 'RESOLVED', '[{"id":"m1","line":"secret line"}]'), ('f2', 'OPEN', '[{"id":"m1","line":"secret line"}]'),
                                                    ('f3', 'RESOLVED', '[]'), ('f4', 'WITHDRAWN', NULL), ('f5', 'RESOLVED', NULL);
        """)
        connection.commit()
        connection.close()


class TestMemory(Database):
    def test_counts_sizes_and_citations_are_read_and_no_text_is_kept(self):
        made = memory.table(self.database, 1_800_000_000)
        self.assertEqual([(one["id"], one["kind"], one["given"], one["cited"]) for one in made["memories"]], [("m1", "RULE", 2, 2), ("m2", "RULE", 2, 0), ("m3", "RECIPE", 0, 0)])
        self.assertEqual([(one["task"], one["turn"], one["given"], one["characters"]) for one in made["turns"]],
                         [("task-1", "turn-1", ["m1", "m2"], 30), ("task-2", "turn-2", ["m1"], 11)])
        self.assertEqual([(one["finding"], one["state"], one["cites"]) for one in made["findings"]],
                         [("f1", "resolved", ["m1"]), ("f2", "open", ["m1"]), ("f3", "resolved", []), ("f4", "withdrawn", []), ("f5", "resolved", [])])
        self.assertNotIn("secret", json.dumps(made))
        self.assertNotIn("Assert the cause chain", json.dumps(made))  # titles are read only to show them
        self.assertEqual(memory.titles(self.database)["m2"], "Prefer small diffs")

    def test_findings_that_cite_a_memory_are_set_against_those_that_cite_none(self):
        made = memory.held(memory.table(self.database, 1_800_000_000)["findings"])
        self.assertEqual(made, {"cite a memory": {"findings": 2, "resolved": 1, "withdrawn": 0, "open": 1, "held up": 100.0},
                                "cite none": {"findings": 3, "resolved": 2, "withdrawn": 1, "open": 0, "held up": 200 / 3}})


class TestPage(Database):
    def setUp(self):
        super().setUp()
        arguments = mock.patch.object(sys, "argv", ["dashboard.py", str(self.folder), "--database", str(self.database)])
        arguments.start()
        self.addCleanup(arguments.stop)
        config.set_option("server.address", dashboard.HOST)
        self.addCleanup(config.set_option, "server.address", None)

    def page(self):
        return AppTest.from_file(str(PAGE), default_timeout=30).run()

    def test_memories_never_cited_come_first_with_their_titles(self):
        saved.write(self.folder, memory.FILE, memory.table(self.database, 1_800_000_000))
        page = self.page()
        self.assertEqual(list(page.exception), [])
        self.assertEqual([(tile.label, tile.value) for tile in page.metric], [("Memories", "3"), ("Never cited", "1"), ("Turns given memory", "2"), ("Memory tokens a turn", "5")])
        self.assertEqual(page.dataframe[0].value[["memory", "given", "cited"]].values.tolist(),
                         [["Prefer small diffs", 2, 0], ["Run one module", 0, 0], ["Assert the cause chain", 2, 2]])
        self.assertIn("Held up is the share of the decided findings that were resolved rather than withdrawn. Fewer than 20 findings that cite a memory or cite none "
                      "are decided, so the shares cannot tell yet whether memory helps.", [caption.value for caption in page.caption])

    def test_nothing_saved_says_how_to_save_it(self):
        self.assertEqual([note.value for note in self.page().info], ["Nothing is saved yet. Save it with `uv run memory.py --save`; the monitor saves it every hour while it runs."])


if __name__ == "__main__":
    unittest.main()
