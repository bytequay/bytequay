from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock

import saved


class TestSaved(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)

    def test_concurrent_writers_replace_only_their_own_complete_file(self):
        together = threading.Barrier(2)
        replace = Path.replace
        contents = ["a" * 100_000, "b" * 100_000]

        def after_both_wrote(partial, target):
            together.wait(timeout=5)
            return replace(partial, target)

        with mock.patch.object(Path, "replace", after_both_wrote), ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda text: saved.write_text(self.folder, "turns.json", text), contents))
        self.assertEqual(results, [self.folder / "turns.json"] * 2)
        self.assertIn((self.folder / "turns.json").read_text(), contents)
        self.assertEqual(list(self.folder.iterdir()), [self.folder / "turns.json"])

    def test_failed_replace_keeps_the_previous_file_and_cleans_the_temporary_file(self):
        target = saved.write_text(self.folder, "turns.json", "previous")
        with mock.patch.object(Path, "replace", side_effect=OSError("disk error")):
            with self.assertRaisesRegex(OSError, "disk error"):
                saved.write_text(self.folder, "turns.json", "new")
        self.assertEqual(target.read_text(), "previous")
        self.assertEqual(list(self.folder.iterdir()), [target])


if __name__ == "__main__":
    unittest.main()
