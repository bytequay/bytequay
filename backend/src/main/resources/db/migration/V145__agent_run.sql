-- AgentRun: an isolated agent session + log attached to whatever it
-- serves (a stage, a review round, or the per-task branch guard),
-- generalizing the "callable affordance, not a stage" pattern already
-- used for the review panel to CI fixing (this migration), review
-- rounds, and the branch guard (later migrations).
--
-- stage_id is the run's OWN backing task_stage row — every run gets one
-- purely so its turns land in stage_messages via the existing FK-scoped
-- mechanism (no new message-storage path). parent_stage_id is the
-- SEMANTIC parent the rail groups the run's sub-row under (e.g. the
-- Development stage for a local ci_fix) and is independent of stage_id.
CREATE TABLE agent_run (
    id              TEXT    NOT NULL PRIMARY KEY,
    task_id         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind            TEXT    NOT NULL,                 -- ci_fix | review_round | branch_guard | panel_review
    source          TEXT,                             -- local | remote | scheduled; null where kind has no source axis
    parent_stage_id TEXT    REFERENCES task_stage(id), -- the stage node the rail attaches this run's sub-row to
    review_round_id TEXT,                              -- nullable; review_round table lands in Phase 2
    stage_id        TEXT    NOT NULL REFERENCES task_stage(id) ON DELETE CASCADE,
    status          TEXT    NOT NULL,                 -- running | awaiting_gate | succeeded | failed | cancelled
    iterations      INTEGER NOT NULL DEFAULT 0,
    budget          INTEGER,                          -- nullable; ci_fix / branch_guard iteration budget
    headline        TEXT,                             -- agent-authored fold-bar summary
    metrics_json    TEXT,                             -- jsonb: tokens, toolCalls, commits, checksFixed
    started_at_ms   INTEGER NOT NULL,
    finished_at_ms  INTEGER
);
CREATE INDEX idx_agent_run_task_status ON agent_run(task_id, status);
CREATE INDEX idx_agent_run_task_kind ON agent_run(task_id, kind);
CREATE INDEX idx_agent_run_parent_stage ON agent_run(parent_stage_id);

-- Backfill: every historical CI_FIXING_STAGE row becomes a finished
-- agent_run(kind=ci_fix) so old tasks still render. stage_id reuses the
-- SAME task_stage row (not a new one) — its stage_messages are already
-- scoped to that id, so history renders with no message rewrite.
-- iterations counts that stage's own LOOP_ITERATION_STARTED events.
-- parent_stage_id is left null: historical rows were top-level, not
-- nested under a reconstructed parent.
INSERT INTO agent_run (
    id, task_id, kind, source, parent_stage_id, review_round_id, stage_id,
    status, iterations, budget, headline, metrics_json, started_at_ms, finished_at_ms)
SELECT
    lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4'
        || substr(hex(randomblob(2)), 2) || '-'
        || substr('89ab', abs(random()) % 4 + 1, 1)
        || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))),
    ts.task_id,
    'ci_fix',
    NULL,
    NULL,
    NULL,
    ts.id,
    CASE WHEN ts.state = 'CLOSED' THEN 'succeeded' ELSE 'running' END,
    (SELECT COUNT(*) FROM task_stage_event e
        WHERE e.stage_id = ts.id AND e.event_type = 'LOOP_ITERATION_STARTED'),
    NULL,
    NULL,
    NULL,
    ts.opened_at_ms,
    ts.closed_at_ms
FROM task_stage ts
WHERE ts.stage_type = 'CI_FIXING_STAGE';
