"""The lab's own programs: every one the lab's Python runs now, what it is, and a way to stop it."""

from __future__ import annotations

import contextlib
import os
from pathlib import Path
import signal

import psutil

import batch
import dashboard
import monitor

PYTHON = Path(__file__).resolve().parent / ".venv"  # every lab program runs on the lab's own Python, however it was started


def what(arguments):
    """What a lab program is, from its arguments: a page and its port, the monitor, an experiment and its name, and so on."""
    script = next((word for word in arguments[1:] if word.endswith(".py")), None)
    if script is None:
        return " ".join(["python", *arguments[1:3]])  # such as `python -m unittest`
    name, rest = Path(script).name, arguments[arguments.index(script) + 1:]
    if name == "dashboard.py":
        return f"Page on port {rest[rest.index('--port') + 1] if '--port' in rest[:-1] else dashboard.PORT}"
    if name == "batch.py":
        return "Nightly rounds" if "--due" in rest else "Experiment " + next((word for word in rest if not word.startswith("-")), "")
    return {"monitor.py": "Monitor", "run.py": "Review run"}.get(name, name)


def running():
    """Every lab program that runs now, oldest first."""
    made = []
    for process in psutil.process_iter(["cmdline", "create_time", "memory_info"]):
        arguments, memory = process.info["cmdline"] or [], process.info["memory_info"]
        if arguments and Path(arguments[0]).is_relative_to(PYTHON):
            made.append({"pid": process.pid, "what": what(arguments), "started": process.info["create_time"], "memory": memory and memory.rss})
    return sorted(made, key=lambda row: row["started"])


def stop(pid):
    """Stop one lab program as Ctrl+C would. A number that is no longer a lab program's is left alone: the system may have
    given it to another process since the list was drawn."""
    found = next((row for row in running() if row["pid"] == pid), None)
    if found is None:
        return
    if found["what"] == "Monitor":
        batch.keep_agent(monitor.AGENT, None)  # its LaunchAgent would start it again
    with contextlib.suppress(ProcessLookupError):
        os.kill(pid, signal.SIGTERM)


def loaded(label):
    """Whether one of the lab's LaunchAgents is loaded."""
    return batch.launchctl("print", f"gui/{os.getuid()}/{label}").returncode == 0
