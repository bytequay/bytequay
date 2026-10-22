"""What ByteQuay's own database records about each turn and each review run: model, effort, tokens, cost, how it ended,
the setup it ran with, and what became of a review's findings. Opened read-only."""

from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
import json
import sqlite3
from urllib.parse import quote

import saved

# The columns the lab reads, by table: ids, times, numbers, and the names of the model, the effort, the lane, the
# repository and the setup. Titles, prompts, plans and every other text column stay unread.
COLUMNS = {
    "agent_turn": ("id", "task_id", "started_at", "elapsed_millis", "model", "requested_model", "effort", "state", "input_tokens", "cached_input_tokens", "output_tokens",
                   "cost", "tool_calls", "context_compactions", "setup_fingerprint", "lane_id"),
    "agent_task": ("id", "repository_owner", "repository_name"),
    "agent_review": ("id", "repo"),
    "agent_review_run": ("id", "review_id", "state", "created_at", "ended_at", "completion_repair_used", "turn_ids_json"),
    "agent_review_finding": ("run_id", "state", "severity"),
}
QUERY = """
    SELECT turn.task_id, turn.id, turn.started_at, turn.elapsed_millis, COALESCE(NULLIF(turn.model, ''), turn.requested_model), turn.effort, turn.state,
           turn.input_tokens, turn.cached_input_tokens, turn.output_tokens, turn.cost, turn.tool_calls, turn.context_compactions,
           turn.lane_id, task.repository_owner, task.repository_name, turn.setup_fingerprint
    FROM agent_turn turn LEFT JOIN agent_task task ON task.id = turn.task_id"""
SETUP = "setup_fingerprint"  # only a database from after ByteQuay began to name a turn's setup has this column
RUNS = """
    SELECT run.id, run.review_id, review.repo, run.state, run.created_at, run.ended_at, run.completion_repair_used, run.turn_ids_json
    FROM agent_review_run run LEFT JOIN agent_review review ON review.id = run.review_id"""
# Of a finding the lab reads how it stands and how grave it was called. Its title, its text and the reason given for its state stay unread.
FINDINGS = """
    SELECT finding.run_id, finding.state, finding.severity
    FROM agent_review_finding finding"""
TOKENS = ("inputTokens", "cachedInputTokens", "outputTokens")
# ByteQuay stores on a Codex turn and on a Claude turn the tokens and the cost of that turn alone. For any other provider
# the lab cannot tell what the stored amounts count, so it shows neither.
OWN = {"claude", "codex"}


def read(path):
    """One record for every turn that started, keyed by (task id, turn id). Fails with OSError when the database is not
    there and with sqlite3.Error when it has another shape, so a caller never mistakes either for a database without turns."""
    named = SETUP in {row[1] for row in rows(path, "PRAGMA table_info(agent_turn)")[0]}
    made = {(row[0], row[1]): record(*row[2:]) for row in rows(path, QUERY if named else QUERY.replace(f"turn.{SETUP}", "NULL"))[0] if row[2]}
    for one in made.values():
        if one["provider"] not in OWN:
            one.update(dict.fromkeys((*TOKENS, "cost")))
    return made


def rows(path, *queries):
    """The rows of each query, in the order of the queries."""
    if not path.is_file():
        raise FileNotFoundError(f"{path} is not there")
    # Read-only in the address itself: this connection cannot write, whatever is asked of it later.
    connection = sqlite3.connect(f"file:{quote(str(path))}?mode=ro", uri=True, timeout=3)
    try:
        return [connection.execute(query).fetchall() for query in queries]
    finally:
        connection.close()


def moment(text):
    return datetime.fromisoformat(text).astimezone(timezone.utc).timestamp()


def reviews(path, turns, since=None, task=None):
    """Every review run and every finding, as rows to save. `turns` is what `read` returned: a run takes its model, its
    effort, its tokens and its cost from its own turns. `since` and `task` are the same choices the turn table is saved with."""
    found_runs, found_findings = rows(path, RUNS, FINDINGS)
    by_turn = {turn: (owner, facts) for (owner, turn), facts in turns.items()}
    runs, number = [], {}
    for run, review, repository, state, created, ended, repaired, turn_ids in sorted(found_runs, key=lambda row: (row[4] or "", row[0])):
        own = [by_turn[turn] for turn in json.loads(turn_ids or "[]") if turn in by_turn]
        if not created or (since is not None and moment(created) < since) or (task is not None and not any(owner.startswith(task) for owner, _ in own)):
            continue
        facts = [one for _, one in own]
        # A run is nearly always one model at one effort. Where its turns differ, it is named after the one most of them used.
        named = Counter((one["provider"], one["model"], one["effort"]) for one in facts if one["model"]).most_common(1)
        provider, model, effort = named[0][0] if named else (None, None, None)
        number[run] = len(runs)
        runs.append({"review": review, "repository": repository or None, "start": saved.stamp(moment(created)),
                     "seconds": round(moment(ended) - moment(created), 3) if ended else None, "state": (state or "unknown").lower(),
                     "repaired": bool(repaired), "turns": len(facts), "provider": provider, "model": model, "effort": effort,
                     **{name: sum(one[name] for one in facts if one[name] is not None) if any(one[name] is not None for one in facts) else None for name in (*TOKENS, "cost")}})
    findings = [{"run": number[run], "state": (state or "unknown").lower(), "severity": severity.lower() if severity else None}
                for run, state, severity in found_findings if run in number]
    return {"runs": runs, "findings": findings}


def record(started, elapsed, model, effort, state, tokens_in, cached, tokens_out, cost, tools, compactions, lane, owner, name, setup):
    return {"start": moment(started), "seconds": (elapsed or 0) / 1000,
            # The turn's lane preserves its provider when the task later changes providers; account and key ids are left out.
            "provider": lane.split("-")[0] if lane else None, "model": model or None, "effort": effort.lower() if effort else None,
            "repository": f"{owner}/{name}" if owner and name else None, "outcome": state.lower() if state else None,
            "inputTokens": tokens_in, "cachedInputTokens": cached, "outputTokens": tokens_out, "cost": None if cost is None else float(cost),
            "toolCalls": tools, "compactions": compactions, "setup": setup or None}
