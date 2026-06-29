-- Per-stage transcript storage, decoupled from the shared per-thread
-- thread_messages table. Each work stage's CLI agent owns its own seq space
-- here (UNIQUE(stage_id, seq)), so concurrent per-stage agents can no longer
-- collide on the thread-global (thread_id, seq) key. task_id + thread_id are
-- denormalised for per-task aggregation and cascade cleanup; the row's place
-- in the hierarchy is the stage, so there is no `scope` column (every row is
-- STAGE-scoped by construction).
--
-- Mirrors thread_messages' column set + the house per-stage UNIQUE pattern
-- from task_stage_iteration (V120). This migration only CREATES the table;
-- the backfill of existing STAGE-scoped rows out of thread_messages happens
-- in a later migration once the read/write paths are switched over, so the
-- running system is never left mid-split.
CREATE TABLE stage_messages (
    id              TEXT    NOT NULL PRIMARY KEY,
    stage_id        TEXT    NOT NULL REFERENCES task_stage(id) ON DELETE CASCADE,
    task_id         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    thread_id       TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    seq             INTEGER NOT NULL,             -- monotonic within a stage; ORDER BY without timestamps
    role            TEXT    NOT NULL,             -- 'user' | 'assistant' | 'tool' | 'system'
    type            TEXT    NOT NULL,             -- 'text' | 'tool_call' | 'tool_result' | 'thinking' | 'permission_request' | 'error'
    content_json    TEXT    NOT NULL,             -- shape varies by type
    duration_ms     INTEGER,                      -- present for tool_call / tool_result rows
    tokens_in       INTEGER,
    tokens_out      INTEGER,
    cost_usd_milli  INTEGER,
    ts_ms           INTEGER NOT NULL,
    CONSTRAINT stage_messages_seq_unique UNIQUE (stage_id, seq)
);

-- The stage detail pane reads in seq order; this index serves both that and
-- the seq-range token/checkpoint queries.
CREATE INDEX idx_stage_messages_stage_seq ON stage_messages(stage_id, seq);
-- Per-task aggregation unions a task's stage transcripts.
CREATE INDEX idx_stage_messages_task_seq ON stage_messages(task_id, seq);
