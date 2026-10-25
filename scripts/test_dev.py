"""Exercise the dev launcher with fake build, backend, and frontend commands."""

import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest


class TestDevLauncher(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="bytequay-dev-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "bin").mkdir()
        (self.root / "frontend/node_modules").mkdir(parents=True)
        (self.root / "backend/app/target").mkdir(parents=True)
        (self.root / "backend/app/target/bytequay-backend.jar").touch()
        source = Path(__file__).resolve().parents[1] / "dev.sh"
        # Keep the real launcher, but put its session token in the test directory.
        (self.root / "dev.sh").write_text(source.read_text().replace(
            'SESSION_DIRECTORY="$HOME/Library/Application Support/ByteQuay"',
            'SESSION_DIRECTORY="$BYTEQUAY_TEST_ROOT/data"',
        ))
        self.env = {
            **os.environ,
            "PATH": f"{self.root / 'bin'}:{os.environ['PATH']}",
            "BYTEQUAY_TEST_ROOT": str(self.root),
            "BYTEQUAY_DESKTOP_SESSION_TOKEN": "dev-launcher-test",
        }
        self.command("node", "exit 0")
        self.command("mvn", '''
echo $$ > "$BYTEQUAY_TEST_ROOT/build.pid"
sleep "${BYTEQUAY_TEST_BUILD_DELAY:-0}"
exit "${BYTEQUAY_TEST_BUILD_STATUS:-0}"
''')
        self.command("java", '''
echo 19527 > "$BYTEQUAY_BACKEND_PORT_FILE"
echo "test backend started"
exec sleep 120
''')
        self.command("curl", "echo ByteQuay-backend ok")
        self.command("npm", "echo 'test frontend started'")

    def command(self, name, body):
        path = self.root / "bin" / name
        path.write_text(f"#!/bin/bash\n{body}\n")
        path.chmod(0o755)

    def start(self, **overrides):
        process = subprocess.Popen(
            ["bash", str(self.root / "dev.sh")],
            env={**self.env, **overrides},
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        self.addCleanup(self.stop, process)
        return process

    @staticmethod
    def stop(process):
        if process.poll() is None:
            process.terminate()
            process.communicate(timeout=15)

    def test_build_longer_than_readiness_timeout(self):
        launcher = self.root / "dev.sh"
        source = launcher.read_text()
        self.assertEqual(source.count("SECONDS + 60"), 1)
        # Keep the build longer than readiness allows without waiting a minute.
        launcher.write_text(source.replace("SECONDS + 60", "SECONDS + 2"))
        process = self.start(BYTEQUAY_TEST_BUILD_DELAY="3")
        output, _ = process.communicate(timeout=15)
        self.assertEqual(process.returncode, 0, output)
        self.assertIn("[dev] backend ready at http://127.0.0.1:19527", output)
        self.assertIn("test frontend started", output)

    def test_failed_build_does_not_start_backend(self):
        process = self.start(BYTEQUAY_TEST_BUILD_STATUS="1")
        output, _ = process.communicate(timeout=15)
        self.assertEqual(process.returncode, 1, output)
        self.assertIn("[dev] error: backend build failed", output)
        self.assertNotIn("test backend started", output)
        self.assertNotIn("test frontend started", output)

    def test_interrupt_stops_build(self):
        process = self.start(BYTEQUAY_TEST_BUILD_DELAY="120")
        pid_file = self.root / "build.pid"
        deadline = time.monotonic() + 10
        while not pid_file.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(pid_file.exists(), "Maven did not start")
        build_pid = int(pid_file.read_text())
        process.send_signal(signal.SIGINT)
        output, _ = process.communicate(timeout=15)
        self.assertEqual(process.returncode, 130, output)
        self.assertNotIn("test backend started", output)
        with self.assertRaises(ProcessLookupError):
            os.kill(build_pid, 0)


if __name__ == "__main__":
    unittest.main()
