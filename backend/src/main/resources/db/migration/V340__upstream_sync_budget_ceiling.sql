-- A sync run's budget is the only hard stop on phase 2, now that fix rounds are
-- deliberately not bounded by a count: a large range legitimately needs many
-- rounds, because a compile failure masks every test behind it and each round
-- can reveal work the previous one could not see.
--
-- That makes the old $100 ceiling the thing that ends a long run, which is not
-- what it was for — it was a guard against an unattended agent running away, and
-- three rounds on a big range can reach $60 on their own. Raised to $1,000.
--
-- SQLite cannot alter a CHECK in place, so the table is rebuilt. Every column,
-- index and default is reproduced exactly as the schema stands at V339; only the
-- budget bound changes. Foreign keys are left off during the swap so the child
-- event table does not cascade when the old table is dropped.
PRAGMA foreign_keys = OFF;

CREATE TABLE upstream_cherry_pick_job_new (
    id                       TEXT PRIMARY KEY,
    workspace_id             TEXT NOT NULL
                             REFERENCES workspaces(id) ON DELETE CASCADE,
    upstream_workspace_id    TEXT NOT NULL
                             REFERENCES workspaces(id) ON DELETE CASCADE,
    status                   TEXT NOT NULL
                             CHECK (status IN (
                                 'QUEUED', 'RUNNING', 'PAUSED_CONFLICT',
                                 'COMPLETED', 'FAILED')),
    source_branch            TEXT NOT NULL,
    source_ref               TEXT NOT NULL,
    base_branch              TEXT NOT NULL,
    base_ref                 TEXT NOT NULL,
    result_branch            TEXT NOT NULL,
    commit_specs_json        TEXT NOT NULL,
    applied_shas_json        TEXT NOT NULL DEFAULT '[]',
    skipped_shas_json        TEXT NOT NULL DEFAULT '[]',
    next_commit_index        INTEGER NOT NULL DEFAULT 0,
    conflict_paths_json      TEXT NOT NULL DEFAULT '[]',
    worktree_path            TEXT NOT NULL,
    open_draft_pr            INTEGER NOT NULL DEFAULT 0
                             CHECK (open_draft_pr IN (0, 1)),
    create_harness_watch     INTEGER NOT NULL DEFAULT 0
                             CHECK (create_harness_watch IN (0, 1)),
    budget_milli_usd         INTEGER NOT NULL DEFAULT 5000
                             CHECK (budget_milli_usd BETWEEN 100 AND 1000000),
    pr_number                INTEGER,
    pr_url                   TEXT,
    harness_watch_id         TEXT,
    error_message            TEXT,
    created_at_ms            INTEGER NOT NULL,
    updated_at_ms            INTEGER NOT NULL,
    pr_description           TEXT,
    skip_filters_json        TEXT NOT NULL
                             DEFAULT '{"startsWith":[],"contains":[]}',
    compile_script           TEXT,
    ci_job_name              TEXT,
    conflicted_shas_json     TEXT NOT NULL DEFAULT '[]',
    pause_requested          INTEGER NOT NULL DEFAULT 0
                             CHECK (pause_requested IN (0, 1)),
    closed_at_ms             INTEGER,
    repair_pending           INTEGER NOT NULL DEFAULT 0
                             CHECK (repair_pending IN (0, 1)),
    local_gate_unavailable   INTEGER NOT NULL DEFAULT 0
                             CHECK (local_gate_unavailable IN (0, 1)),
    spent_milli_usd          INTEGER NOT NULL DEFAULT 0,
    agent_session_id         TEXT,
    pr_result                TEXT
                             CHECK (pr_result IS NULL
                                    OR pr_result IN ('merged', 'closed'))
);

INSERT INTO upstream_cherry_pick_job_new (
    id, workspace_id, upstream_workspace_id, status,
    source_branch, source_ref, base_branch, base_ref, result_branch,
    commit_specs_json, applied_shas_json, skipped_shas_json,
    next_commit_index, conflict_paths_json, worktree_path,
    open_draft_pr, create_harness_watch, budget_milli_usd,
    pr_number, pr_url, harness_watch_id, error_message,
    created_at_ms, updated_at_ms, pr_description, skip_filters_json,
    compile_script, ci_job_name, conflicted_shas_json, pause_requested,
    closed_at_ms, repair_pending, local_gate_unavailable, spent_milli_usd,
    agent_session_id, pr_result)
SELECT
    id, workspace_id, upstream_workspace_id, status,
    source_branch, source_ref, base_branch, base_ref, result_branch,
    commit_specs_json, applied_shas_json, skipped_shas_json,
    next_commit_index, conflict_paths_json, worktree_path,
    open_draft_pr, create_harness_watch, budget_milli_usd,
    pr_number, pr_url, harness_watch_id, error_message,
    created_at_ms, updated_at_ms, pr_description, skip_filters_json,
    compile_script, ci_job_name, conflicted_shas_json, pause_requested,
    closed_at_ms, repair_pending, local_gate_unavailable, spent_milli_usd,
    agent_session_id, pr_result
FROM upstream_cherry_pick_job;

DROP TABLE upstream_cherry_pick_job;
ALTER TABLE upstream_cherry_pick_job_new RENAME TO upstream_cherry_pick_job;

CREATE INDEX idx_upstream_cherry_pick_job_workspace
    ON upstream_cherry_pick_job(workspace_id, created_at_ms DESC);
CREATE UNIQUE INDEX idx_upstream_cherry_pick_job_one_live
    ON upstream_cherry_pick_job(workspace_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED_CONFLICT')
      AND closed_at_ms IS NULL;

PRAGMA foreign_keys = ON;
