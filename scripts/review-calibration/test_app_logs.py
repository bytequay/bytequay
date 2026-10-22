from datetime import datetime, timezone
import gzip
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import app_logs

NOW = datetime(2026, 10, 22, 12, tzinfo=timezone.utc).timestamp()


def line(message, level="WARN", at=NOW - 10, logger="c.b.app.PullRequestService", pid=12):
    return f"{datetime.fromtimestamp(at, timezone.utc).isoformat()} {level} {pid} --- [bytequay-backend] [main] {logger} : {message}\n"


def timing(phase, second, task="task-1", turn="turn-1", client="client-1", provider="provider-1", pid=12):
    return line(f"send-timing phase={phase} epochMs={int((NOW - 100 + second) * 1000)} taskId={task} turnId={turn} clientId={client} "
                f"providerThreadId=thread-1 providerTurnId={provider}", "DEBUG", NOW - 100 + second, "c.b.agent.SendTiming", pid)


class TestLogs(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)

    def save(self, text):
        (self.folder / "backend.log").write_text(text)

    def test_counts_rotated_errors_and_stack_causes_without_retaining_private_content(self):
        self.save(line("Could not warm PR private/repo#42; retaining cached detail: bearer secret-token") +
                  line("Request failed /Users/alice/private", "ERROR", logger="o.a.c.c.C.[Tomcat].[localhost].[/]") +
                  "com.bytequay.agent.spi.AgentUnavailableException: secret message\nCaused by: java.lang.OutOfMemoryError: Java heap space\n\tat secret.source(file.java:10)\n")
        with gzip.open(self.folder / "backend.log.2026-10-22.0.gz", "wt") as stream:
            stream.write(line("SQLITE_BUSY database is locked", "ERROR", NOW - 1000))
            stream.write(line("old timeout secret", "ERROR", NOW - 90000))
        made = app_logs.analyze(self.folder, NOW)
        self.assertEqual((made["errors"], made["warnings"], made["coverage"]["files"]), (2, 1, 2))
        self.assertEqual({row["category"] for row in made["groups"]}, {"heap", "refresh", "database"})
        self.assertEqual(next(row["component"] for row in made["groups"] if row["category"] == "heap"), "Servlet")
        for secret in ("secret", "private", "alice", "bearer", "repo#42", "file.java"):
            self.assertNotIn(secret, json.dumps(made))

    def test_pairs_only_the_same_operation_identity_and_keeps_provider_wait_separate(self):
        self.save(timing("turn.persist.begin", 0) + timing("turn.persist.end", 1, turn="other") + timing("turn.persist.end", 2) +
                  timing("codex.initialize.begin", 3, client="a") + timing("codex.initialize.end", 4, client="b") +
                  timing("codex.turn.ack", 5) + timing("codex.provider.first_output", 10, provider="other") + timing("codex.provider.first_output", 15) +
                  timing("turn.persist.begin", 16) + timing("turn.persist.end", 17, pid=13))
        made = app_logs.analyze(self.folder, NOW)
        self.assertEqual({row["phase"]: (row["count"], row["median"]) for row in made["timings"]},
                         {"turn.persist": (1, 2.0), "codex.first_output": (1, 10.0)})
        self.assertNotIn("task-1", json.dumps(made))
        self.assertNotIn("client-1", json.dumps(made))

    def test_absence_corruption_and_limits_are_explicit(self):
        self.assertEqual(app_logs.analyze(self.folder, NOW)["coverage"]["files"], 0)
        (self.folder / "backend.log.bad.gz").write_bytes(b"not gzip")
        self.save("x" * 2000 + "\n" + line("database is locked"))
        with mock.patch.object(app_logs, "MAX_BYTES", 512):
            made = app_logs.analyze(self.folder, NOW)
        self.assertEqual((made["coverage"]["truncated"], made["coverage"]["unreadable"], made["warnings"]), (1, 1, 1))
        with mock.patch.object(app_logs, "MAX_FILES", 1):
            self.assertEqual(app_logs.analyze(self.folder, NOW)["coverage"]["omitted"], 1)

    def test_bounded_gzip_does_not_hide_partial_coverage(self):
        with gzip.open(self.folder / "backend.log.0.gz", "wt") as stream:
            stream.write(line("known warning") * 20)
        with mock.patch.object(app_logs, "MAX_BYTES", 500):
            made = app_logs.analyze(self.folder, NOW)
        self.assertEqual(made["coverage"]["truncated"], 1)
        self.assertLess(made["warnings"], 20)

    def test_archives_older_than_the_window_do_not_reduce_recent_coverage(self):
        self.save(line("Backend ready", "INFO"))
        old = self.folder / "backend.log.2026-01-01.0.gz"
        old.write_bytes(b"old archive outside this analysis")
        os.utime(old, (NOW - 2 * app_logs.REACH, NOW - 2 * app_logs.REACH))
        with mock.patch.object(app_logs, "MAX_FILES", 1):
            made = app_logs.analyze(self.folder, NOW)
        self.assertEqual((made["coverage"]["files"], made["coverage"]["omitted"], made["coverage"]["unreadable"]), (1, 0, 0))


if __name__ == "__main__":
    unittest.main()
