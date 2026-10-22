"""Local health observations and investigation briefs from bounded logs and process samples. No model or network calls."""

from datetime import datetime

import pandas

import app_logs
import resources

ATTENTION, UNKNOWN, CLEAR = "Attention needed", "Unknown", "No issue observed"
MAX_FINDINGS = 8
LOG_STALE = 3600
GROWTH_SPAN, CPU_SPAN, ORPHAN_SPAN = 600, 120, 30
END_WINDOW = 120
CPU_CORES = 0.5
PROCESS = "backend/agent-cli/src/main/java/com/bytequay/agent/cli/process/impl/DefaultProcessSupervisor.java"
TARGETS = {
    "app": ("Desktop", ("app", "window", "gpu", "helpers"), "frontend/src/main.ts"),
    "backend": ("Backend", ("backend",), "backend/agent-core/src/main/java/com/bytequay/agent/core/impl/AgentRuntimeRegistry.java"),
}


def component(name, status, evidence):
    return {"component": name, "status": status, "evidence": evidence}


def finding(identity, title, severity, seen, action, where, watch):
    return {"id": identity, "title": title, "severity": severity, "seen": seen, "do": action, "where": where, "watch": watch}


def log_health(table, now):
    if table is None:
        return [component("Backend logs", UNKNOWN, "No application-log snapshot is available. Refresh Lab data.")], []
    coverage, findings = table["coverage"], []
    for category, (_, title, severity, advice, path, watch) in app_logs.RULES.items():
        groups = [one for one in table["groups"] if one["category"] == category]
        errors = sum(one["count"] for one in groups if one["level"] == "ERROR")
        warnings = sum(one["count"] for one in groups if one["level"] == "WARN")
        if errors or warnings >= 3:
            findings.append(finding(f"logs:{category}", title, severity, f"{errors} errors and {warnings} warnings matched this category in the saved analysis's 24-hour window.",
                                    advice, path, watch))
    for group in table["groups"]:
        if group["category"] == "other" and group["count"] >= 3:
            findings.append(finding(f"logs:other:{group['component']}:{group['level']}", f"Investigate recurring {group['component']} {group['level'].lower()} entries", "medium",
                                    f"{group['component']} recorded {group['count']} {group['level'].lower()} entries in the saved analysis's 24-hour window.",
                                    "Read these entries in the local backend log, find the failing source operation, and reproduce it before changing retry or cleanup behavior.",
                                    "backend/", "recurrences of this logger and level per hour"))
    for timing in table["timings"]:
        phase = timing["phase"]
        # This interval includes provider execution and network time; it is not evidence of local app overhead.
        if phase == "codex.first_output" or phase not in app_logs.PHASES or timing["count"] < 3 or timing["median"] < 1:
            continue
        name, path = app_logs.PHASES[phase]
        findings.append(finding(f"timing:{phase}", f"Investigate slow {name.lower()}", "medium",
                                f"{name} took a median {timing['median']:.2f} seconds across {timing['count']} matched operations; p95 was {timing['p95']:.2f} seconds.",
                                "Profile this measured phase under the same workload. Check repeated work and blocking waits, make a focused change, and compare the phase timings again. "
                                "Provider startup or acknowledgement may contribute; these timings alone do not locate the cause.", path, f"median and p95 seconds for {phase}"))
    incomplete = any(coverage[name] for name in ("unreadable", "truncated", "omitted"))
    age = now - datetime.fromisoformat(table["savedAt"]).timestamp()
    available = coverage["files"] > 0 and coverage["recent"] > 0
    evidence = f"{coverage['recent']} recent entries in {coverage['files']} files; {table['errors']} errors and {table['warnings']} warnings."
    if incomplete:
        evidence += " Coverage is partial because files were unreadable, truncated or omitted."
    if not available:
        evidence += " No recent readable log coverage."
    if not 0 <= age <= LOG_STALE:
        evidence += " The saved analysis is stale; refresh Lab data."
        for one in findings:
            one["do"] = "Refresh Lab data to check whether this historical signal is still present. " + one["do"]
    status = ATTENTION if findings or table["errors"] else UNKNOWN if incomplete or not available or not 0 <= age <= LOG_STALE else CLEAR
    components = [component("Backend logs", status, evidence)]
    if table.get("activityProblem"):
        components.append(component("Agent statistics", UNKNOWN, "Application records could not be refreshed; agent statistics may be stale."))
    return components, findings


def trailing(rows, predicate):
    """A continuous suffix only: sleep, missing samples, restarts and changed work must not become a trend."""
    kept, later = [], None
    for row in reversed(rows.to_dict("records")):
        if not predicate(row) or (later is not None and later - row["at"] > resources.STALE):
            break
        kept.append(row)
        later = row["at"]
    return pandas.DataFrame(list(reversed(kept)), columns=rows.columns)


def covers(rows, seconds):
    return len(rows) >= 3 and rows["at"].iloc[-1] - rows["at"].iloc[0] >= seconds


def stable(rows, session):
    last = rows.iloc[-1]
    fields = (session, "turns", "tasks", "queued", "processes")
    if last[list(fields)].isna().any() or last["processes"] <= 0:
        return rows.iloc[:0]
    return trailing(rows, lambda row: all(pandas.notna(row[name]) and row[name] == last[name] for name in fields))


def resource_health(samples, now):
    if samples is None or samples["parts"].empty or samples["machine"].empty:
        return [component("Resources", UNKNOWN, "No process samples are available. Start the monitor to measure resource use.")], []
    parts = samples["parts"]
    parts = parts[(parts["at"] <= now) & (parts["at"] >= now - 3600)]
    machine = samples["machine"]
    machine = machine[(machine["at"] <= now) & (machine["at"] >= now - 3600)].sort_values("at")
    if parts.empty or machine.empty or now - min(parts["at"].max(), machine["at"].max()) > resources.STALE:
        return [component("Resources", UNKNOWN, "Process samples are stale; the monitor is not reporting current resource use.")], []
    latest = machine["at"].max()
    current = parts[parts["at"] == latest].set_index("part")
    components, findings = [], []
    for part, title in (("app", "Desktop app"), ("backend", "Java backend")):
        count = current.loc[part, "processes"] if part in current.index else None
        present = count is not None and pandas.notna(count) and count > 0
        components.append(component(title, CLEAR if present else UNKNOWN,
                                    f"{int(count)} root process observed in the latest sample." if present else "No root process observed; it may be stopped or unavailable to the monitor."))
    orphaned = parts[parts["part"] == "orphaned"].sort_values("at")
    if orphaned.empty or orphaned["at"].iloc[-1] != latest:
        components.append(component("Process cleanup", UNKNOWN, "Detached-process observations are missing."))
    else:
        sustained = trailing(orphaned, lambda row: pandas.notna(row["processes"]) and row["processes"] > 0)
        if covers(sustained, ORPHAN_SPAN):
            duration = sustained["at"].iloc[-1] - sustained["at"].iloc[0]
            seen = f"Previously observed descendants remained detached across {len(sustained)} samples over {duration:.0f} seconds; {int(sustained['processes'].iloc[-1])} remain now."
            findings.append(finding("resources:detached", "Investigate processes surviving their parent", "medium", seen,
                                    "Reproduce the shutdown or cancellation, identify the detached descendants, and check their ownership receipts and cleanup paths. "
                                    "Verify intended background processes before changing termination behavior; detachment alone does not prove a leak.",
                                    PROCESS, "detached descendants still present 30 seconds after parent exit"))
            components.append(component("Process cleanup", ATTENTION, seen))
        else:
            observed = orphaned["processes"].iloc[-1]
            status = CLEAR if pandas.notna(observed) and observed == 0 else UNKNOWN
            components.append(component("Process cleanup", status, "No detached descendants observed in the latest sample." if status == CLEAR else
                                        "Detached descendants were observed, but duration is not yet established."))
    work = machine.reindex(columns=["at", "turns", "tasks", "queued", "app_session", "backend_session"])
    for root, (name, members, path) in TARGETS.items():
        own = parts[parts["part"].isin(members)]
        amounts = own.groupby("at")[["processes", "memory", "cpu", "files"]].sum(min_count=len(members))
        complete = own.groupby("at")["part"].nunique() == len(members)
        rows = amounts[complete].reset_index().merge(work, on="at", how="left").sort_values("at")
        if rows.empty or rows["at"].iloc[-1] != latest:
            components.append(component(f"{name} resource trends", UNKNOWN, "Samples of this component are incomplete."))
            continue
        same = stable(rows, f"{root}_session")
        first_finding = len(findings)
        busy = trailing(same, lambda row: row["turns"] == 0 and pandas.notna(row["cpu"]) and row["cpu"] >= CPU_CORES)
        if covers(busy, CPU_SPAN):
            duration = busy["at"].iloc[-1] - busy["at"].iloc[0]
            findings.append(finding(f"resources:{root}:cpu", f"Investigate {name.lower()} CPU without agent turns", "medium",
                                    f"{name} used a median {busy['cpu'].median():.2f} CPU cores across {len(busy)} samples over {duration / 60:.1f} minutes with zero agent turns.",
                                    "Capture a CPU profile while reproducing this state. Check polling, repeated refreshes and loops, then compare CPU under the same workload. "
                                    "No agent turns does not mean the app is idle; UI and background work may explain the usage.", path,
                                    "CPU cores over two minutes with the same workload and no agent turns"))
        measured = trailing(same, lambda row: all(pandas.notna(row[value]) and row[value] >= 0 for value in ("memory", "files", "cpu")))
        enough = covers(measured, GROWTH_SPAN)
        if enough:
            early = measured[measured["at"] <= measured["at"].iloc[0] + END_WINDOW]
            late = measured[measured["at"] >= measured["at"].iloc[-1] - END_WINDOW]
            duration = measured["at"].iloc[-1] - measured["at"].iloc[0]
            for amount, minimum, unit, divisor in (("memory", 256 * resources.MB, "MB", resources.MB), ("files", 100, "open file descriptors", 1)):
                before, after = early[amount].median(), late[amount].median()
                if after - before < minimum or (amount == "memory" and after - before < before * .25):
                    continue
                findings.append(finding(f"resources:{root}:{amount}", f"Investigate growing {name.lower()} {amount}", "medium",
                                        f"The first and last two-minute medians rose from {before / divisor:,.0f} to {after / divisor:,.0f} {unit} over {duration / 60:.1f} minutes "
                                        f"in one process session with unchanged process, turn, task and queue counts ({len(measured)} samples).",
                                        "Reproduce the same workload and compare retained objects or open handles before and after. Trace ownership and release paths, then add a focused cleanup test. "
                                        "This growth is a signal to investigate, not proof of a leak.", path, f"{name.lower()} {amount} growth in one session under the same workload"))
        status = ATTENTION if len(findings) > first_finding else CLEAR if enough else UNKNOWN
        evidence = ("At least ten minutes of continuous samples under the same session and workload; no configured growth or CPU threshold was reached." if status == CLEAR else
                    "Resource thresholds were reached; inspect the evidence below." if status == ATTENTION else
                    "Need ten minutes of continuous samples with known session and unchanged process, turn, task and queue counts to assess growth.")
        components.append(component(f"{name} resource trends", status, evidence))
    return components, findings


def overall(table, samples, now):
    """No issue observed describes measured coverage only; absent, stale or partial evidence stays unknown."""
    log_components, log_findings = log_health(table, now)
    resource_components, resource_findings = resource_health(samples, now)
    components = log_components + resource_components
    findings = sorted(log_findings + resource_findings,
                      key=lambda one: ({"high": 0, "medium": 1, "low": 2}[one["severity"]], one["id"].startswith("logs:other:"), one["id"]))[:MAX_FINDINGS]
    states = {one["status"] for one in components}
    status = ATTENTION if ATTENTION in states else UNKNOWN if UNKNOWN in states else CLEAR
    return {"status": status, "components": components, "findings": findings}
