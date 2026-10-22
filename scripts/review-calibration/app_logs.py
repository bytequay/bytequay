"""Bounded, read-only analysis of existing backend logs. Saved results contain counters and fixed labels, never messages."""

from collections import Counter
from datetime import datetime
import gzip
import math
from pathlib import Path
import re
import statistics
import zlib

import saved

FILE = "app-health.json"
LOGS = Path.home() / "Library/Logs/ByteQuay"
REACH = 86400
MAX_FILES, MAX_BYTES = 8, 4 * 1024 * 1024
HEADER = re.compile(r"^(\d{4}-\d\d-\d\d[T ][\d:.]+(?:Z|[+-]\d\d:\d\d)?)\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+(\d+)\s+.*?\s(\S+)\s+:\s?(.*)$")
FIELD = re.compile(r"\b(phase|epochMs|taskId|turnId|clientId|providerThreadId|providerTurnId)=([\w.:-]+)")
PR = "backend/app/src/main/java/com/bytequay/app/service/pr/PullRequestService.java"
PROCESS = "backend/agent-cli/src/main/java/com/bytequay/agent/cli/process"
RULES = {
    "heap": (r"outofmemoryerror|java heap space|gc overhead limit", "Backend memory exhaustion", "high",
             "Inspect heap retention and bound the growing cache or collection. Measure retained memory before changing the heap limit.",
             "backend/app/src/main/java", "memory-exhaustion errors and backend retained memory"),
    "database": (r"sqlite_busy|database is locked|connection is not available.*timed out", "Database contention", "medium",
                 "Find the operation holding the database connection; move network and process waits outside its transaction and retest concurrent reads.",
                 "backend/app/src/main/resources/application.properties", "database-contention errors per hour"),
    "connection": (r"connection leak detection|apparent connection leak", "Database connection retained", "medium",
                   "Trace connection ownership and close it on success, failure and cancellation. Verify the warning disappears under the same workload.",
                   "backend/persistence-sqlite/src/main/java", "connection-leak warnings per hour"),
    "cleanup": (r"cleanup remains pending|not verified stopped|failed to (?:stop|terminate)|unable to (?:stop|terminate)", "Process cleanup incomplete", "medium",
                "Trace shutdown and cancellation, including reparented descendants. Preserve process receipts until exit is verified; add a cancellation regression test.",
                PROCESS, "cleanup warnings and detached descendants after a turn ends"),
    "refresh": (r"retaining cached|enrichment failed|failed to sync diff", "GitHub refresh failures", "medium",
                "Inspect refresh failure handling. Bound and deduplicate retries, preserve cached data, and surface authentication or rate-limit failures to the user.",
                PR, "refresh failures per hour and successful refresh latency"),
    "timeout": (r"timed out|timeoutexception|timeout waiting", "Operation timeouts", "medium",
                "Identify the timed-out operation in the local source log, measure its slow phase, and bound retries or cancel abandoned work before increasing timeouts.",
                "backend/", "timeouts per hour and the operation's measured latency"),
}
PATTERNS = {key: re.compile(rule[0], re.I) for key, rule in RULES.items()}
PHASES = {
    "task.worktree": ("Worktree preparation", "backend/app/src/main/java/com/bytequay/app/workspace/ManagedWorktreeService.java"),
    "turn.persist": ("Turn persistence", "backend/agent-core/src/main/java/com/bytequay/agent/core/impl/DefaultAgentTurnService.java"),
    "turn.session_open": ("Agent session startup", "backend/agent-core/src/main/java/com/bytequay/agent/core/impl/DefaultAgentTurnService.java"),
    "codex.initialize": ("Codex initialization", "backend/agent-cli/src/main/java/com/bytequay/agent/cli/codex/CodexAppServerClient.java"),
    "turn.provider_send": ("Turn submission", "backend/agent-core/src/main/java/com/bytequay/agent/core/impl/DefaultAgentTurnService.java"),
    "codex.first_output": ("Provider acknowledgement to first output", "backend/agent-cli/src/main/java/com/bytequay/agent/cli/codex/CodexAppServerClient.java"),
}


def content(path):
    """Tail plain logs; bound decompression too. Truncated archives explicitly reduce coverage."""
    if path.suffix == ".gz":
        with gzip.open(path, "rb") as stream:
            raw = stream.read(MAX_BYTES + 1)
        return raw[:MAX_BYTES].decode("utf-8", errors="replace"), len(raw) > MAX_BYTES
    with path.open("rb") as stream:
        size = stream.seek(0, 2)
        stream.seek(max(0, size - MAX_BYTES))
        if size > MAX_BYTES:
            stream.readline(MAX_BYTES)
        raw = stream.read(MAX_BYTES)
    return raw.decode("utf-8", errors="replace"), size > MAX_BYTES


def entries(text):
    """Stack traces contribute only known failure categories to their header's single occurrence."""
    current = None
    for line in text.splitlines():
        match = HEADER.match(line)
        if match:
            if current:
                yield current
            stamp, level, pid, logger, message = match.groups()
            try:
                at = datetime.fromisoformat(stamp).timestamp()
            except ValueError:
                current = None
                continue
            component = logger.rsplit(".", 1)[-1][:80] if re.fullmatch(r"[\w.$]+", logger) else "Servlet"
            current = {"at": at, "level": level, "pid": pid, "component": component,
                       "categories": {key for key, pattern in PATTERNS.items() if pattern.search(message)},
                       "timing": dict(FIELD.findall(message)) if "send-timing phase=" in message else {}}
        elif current and (line.startswith(("\t", " ", "Caused by:")) or re.match(r"[\w.$]+(?:Exception|Error)(?::|$)", line)):
            current["categories"].update(key for key, pattern in PATTERNS.items() if pattern.search(line))
        elif line and not line.isspace():
            if current:
                yield current
            current = None
    if current:
        yield current


def analyze(directory, now):
    """Last-day counters and matched timing pairs. Unreadable, absent and partial sources never imply health."""
    coverage = {"files": 0, "unreadable": 0, "truncated": 0, "omitted": 0, "parsed": 0, "recent": 0, "first": None, "last": None}
    all_entries = []
    try:
        files = sorted((path for path in directory.glob("backend.log*") if path.is_file()
                        and (path.name == "backend.log" or path.stat().st_mtime >= now - REACH)), key=lambda path: path.stat().st_mtime, reverse=True)
    except OSError:
        files = []
        coverage["unreadable"] += 1
    coverage["omitted"] = max(0, len(files) - MAX_FILES)
    for path in files[:MAX_FILES]:
        try:
            text, truncated = content(path)
        except (OSError, EOFError, zlib.error):
            coverage["unreadable"] += 1
            continue
        coverage["files"] += 1
        coverage["truncated"] += truncated
        parsed = list(entries(text))
        coverage["parsed"] += len(parsed)
        all_entries.extend(one for one in parsed if now - REACH <= one["at"] <= now)
    all_entries.sort(key=lambda one: one["at"])
    coverage["recent"] = len(all_entries)
    if all_entries:
        coverage.update(first=all_entries[0]["at"], last=all_entries[-1]["at"])
    groups, levels, pending, timings = {}, Counter(), {}, {}
    for one in all_entries:
        level = one["level"]
        if level in ("WARN", "ERROR"):
            levels[level] += 1
            categories = one["categories"] or {"other"}
            for category in categories:
                key = (category, one["component"], level)
                group = groups.setdefault(key, {"category": category, "component": one["component"], "level": level, "count": 0,
                                                "first": one["at"], "last": one["at"]})
                group["count"] += 1
                group["last"] = one["at"]
        fields = one["timing"]
        phase = fields.get("phase", "")
        stem, _, end = phase.rpartition(".")
        if phase in ("codex.turn.ack", "codex.provider.first_output"):
            stem, end = "codex.first_output", "begin" if phase == "codex.turn.ack" else "end"
            if fields.get("providerTurnId", "-") == "-":
                continue
        if stem not in PHASES or end not in ("begin", "end") or fields.get("taskId", "-") == "-":
            continue
        try:
            at = int(fields["epochMs"]) / 1000
        except (KeyError, ValueError):
            continue
        identity = (one["pid"], stem, *(fields.get(name, "-") for name in ("taskId", "turnId", "clientId")),
                    fields.get("providerTurnId", "-") if stem == "codex.first_output" else "-")
        if end == "begin":
            pending[identity] = at
        elif identity in pending:
            seconds = at - pending.pop(identity)
            if 0 <= seconds <= REACH:
                timings.setdefault(stem, []).append(seconds)
    measured = [{"phase": phase, "name": PHASES[phase][0], "count": len(values), "median": statistics.median(values),
                 "p95": sorted(values)[max(0, math.ceil(.95 * len(values)) - 1)], "max": max(values)} for phase, values in sorted(timings.items())]
    return {"version": 1, "savedAt": saved.stamp(now), "coverage": coverage, "errors": levels["ERROR"], "warnings": levels["WARN"],
            "groups": sorted(groups.values(), key=lambda one: (-one["count"], one["category"], one["component"])), "timings": measured}
