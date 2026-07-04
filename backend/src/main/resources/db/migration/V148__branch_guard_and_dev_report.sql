-- DevReport: the dev agent's typed handoff to whatever addresses review
-- comments next (a review_round run) — summary, decisions, invariants,
-- tricky spots, test map, followups (plan-rail-runs.md R14). One row per
-- task; record_dev_report upserts it until the local-open flip.
CREATE TABLE dev_report (
    id                TEXT    NOT NULL PRIMARY KEY,
    task_id           TEXT    NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
    summary           TEXT    NOT NULL,
    decisions_json    TEXT,                              -- jsonb: [{what, why, rejectedAlternatives[]}]
    invariants_json   TEXT,                              -- jsonb: string[]
    tricky_spots_json TEXT,                              -- jsonb: [{file, note}]
    test_map_json     TEXT,                              -- jsonb: [{area, tests[]}]
    followups_json    TEXT,                              -- jsonb: string[]
    created_at_ms     INTEGER NOT NULL
);

-- BranchGuard: per-task, always-on drift maintenance against a moving
-- main (R18). One row per task, created (disabled) lazily and enabled on
-- first push. schedule is an enum-ish label (only "nightly" for v1) that
-- BranchGuardJob maps to a fixed interval in Java — no cron parser exists
-- in this codebase and none is added for this.
CREATE TABLE branch_guard (
    task_id            TEXT    NOT NULL PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    enabled            INTEGER NOT NULL DEFAULT 0,
    schedule           TEXT    NOT NULL DEFAULT 'nightly',
    state              TEXT    NOT NULL DEFAULT 'in_sync',  -- in_sync | drifting | fixing | needs_attention
    last_run_id        TEXT,
    last_checked_at_ms INTEGER
);
