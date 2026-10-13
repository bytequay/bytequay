"""What ByteQuay's own database records about each turn: its model, effort, tokens, cost and how it ended. Opened read-only."""

from __future__ import annotations

from datetime import datetime, timezone
import sqlite3
from urllib.parse import quote

# The columns the lab reads, by table: ids, times, numbers, and the names of the model, the effort, the lane and the
# repository. Titles, prompts, plans and every other text column stay unread.
COLUMNS = {
    "agent_turn": ("id", "task_id", "started_at", "elapsed_millis", "model", "requested_model", "effort", "state", "input_tokens", "cached_input_tokens", "output_tokens",
                   "cost", "tool_calls", "context_compactions"),
    "agent_task": ("id", "lane_id", "repository_owner", "repository_name"),
}
QUERY = """
    SELECT turn.task_id, turn.id, turn.started_at, turn.elapsed_millis, COALESCE(NULLIF(turn.model, ''), turn.requested_model), turn.effort, turn.state,
           turn.input_tokens, turn.cached_input_tokens, turn.output_tokens, turn.cost, turn.tool_calls, turn.context_compactions,
           task.lane_id, task.repository_owner, task.repository_name
    FROM agent_turn turn LEFT JOIN agent_task task ON task.id = turn.task_id"""
TOKENS = ("inputTokens", "cachedInputTokens", "outputTokens")
# ByteQuay stores on a Codex turn and on a Claude turn the tokens and the cost of that turn alone. For any other provider
# the lab cannot tell what the stored amounts count, so it shows neither.
OWN = {"claude", "codex"}


def read(path):
    """One record for every turn that started, keyed by (task id, turn id). Fails with OSError when the database is not
    there and with sqlite3.Error when it has another shape, so a caller never mistakes either for a database without turns."""
    if not path.is_file():
        raise FileNotFoundError(f"{path} is not there")
    # Read-only in the address itself: this connection cannot write, whatever is asked of it later.
    connection = sqlite3.connect(f"file:{quote(str(path))}?mode=ro", uri=True, timeout=3)
    try:
        rows = connection.execute(QUERY).fetchall()
    finally:
        connection.close()
    made = {(row[0], row[1]): record(*row[2:]) for row in rows if row[2]}
    for one in made.values():
        if one["provider"] not in OWN:
            one.update(dict.fromkeys((*TOKENS, "cost")))
    return made


def record(started, elapsed, model, effort, state, tokens_in, cached, tokens_out, cost, tools, compactions, lane, owner, name):
    return {"start": datetime.fromisoformat(started).astimezone(timezone.utc).timestamp(), "seconds": (elapsed or 0) / 1000,
            # A lane is named after its provider, sometimes followed by the id of an account or a key. Only the provider is kept.
            "provider": lane.split("-")[0] if lane else None, "model": model or None, "effort": effort.lower() if effort else None,
            "repository": f"{owner}/{name}" if owner and name else None, "outcome": state.lower() if state else None,
            "inputTokens": tokens_in, "cachedInputTokens": cached, "outputTokens": tokens_out, "cost": None if cost is None else float(cost),
            "toolCalls": tools, "compactions": compactions}
