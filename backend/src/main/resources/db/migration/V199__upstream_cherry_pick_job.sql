-- Restart-safe setup job for bringing a contiguous upstream range into a
-- fork workspace. Conflicts stay in the owned worktree for a human to resolve;
-- this table has no task/session/agent linkage by design.
CREATE TABLE upstream_cherry_pick_job (
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
                             CHECK (budget_milli_usd BETWEEN 100 AND 100000),
    pr_number                INTEGER,
    pr_url                   TEXT,
    harness_watch_id         TEXT,
    error_message            TEXT,
    created_at_ms            INTEGER NOT NULL,
    updated_at_ms            INTEGER NOT NULL
);

CREATE INDEX idx_upstream_cherry_pick_job_workspace
    ON upstream_cherry_pick_job(workspace_id, created_at_ms DESC);

CREATE UNIQUE INDEX idx_upstream_cherry_pick_job_one_live
    ON upstream_cherry_pick_job(workspace_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED_CONFLICT');
