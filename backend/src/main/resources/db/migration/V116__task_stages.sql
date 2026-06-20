-- Task Stages: the coarse grouping above the TaskPhase machine.
--
-- A Task is a sequence of stage instances (task_stage); each instance has
-- an open→close lifecycle whose events land in task_stage_event for
-- measurement and audit. review_comment is the unified inline-comment
-- entity across LOCAL_USER / LOCAL_AGENT / REMOTE_REVIEWER sources.
--
-- Additive only: two new tables, one new audit table, and one nullable
-- column on tasks. The existing TaskPhase machine + task_phase_event log
-- are untouched. UUID ids are TEXT and timestamps are epoch-ms INTEGER,
-- matching the rest of the SQLite schema (cf. task_phase_event in V106).

-- ── task_stage ──────────────────────────────────────────────────────────
-- One row per stage instance. stage_type / state store the StageType /
-- StageState enum name. caller_stage_id is set only for a callable
-- sub-stage (a review panel) and self-references this table.
CREATE TABLE task_stage (
    id              TEXT    NOT NULL PRIMARY KEY,
    task_id         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    stage_type      TEXT    NOT NULL,                 -- StageType enum name
    state           TEXT    NOT NULL,                 -- StageState enum name
    opened_at_ms    INTEGER NOT NULL,
    closed_at_ms    INTEGER,                          -- null until closed
    caller_stage_id TEXT    REFERENCES task_stage(id),
    summary_json    TEXT,                             -- brain-view summary
    metrics_json    TEXT                              -- per-stage metrics
);
CREATE INDEX idx_task_stage_task_state ON task_stage(task_id, stage_type, state);
CREATE INDEX idx_task_stage_task_opened ON task_stage(task_id, opened_at_ms);

-- ── task_stage_event ────────────────────────────────────────────────────
-- One row per stage lifecycle event. task_id is denormalised for cheap
-- per-Task queries. Only OPENED / CLOSED are written today; the rest of
-- the StageEventType vocabulary arrives with the loop machinery.
CREATE TABLE task_stage_event (
    id           TEXT    NOT NULL PRIMARY KEY,
    stage_id     TEXT    NOT NULL REFERENCES task_stage(id) ON DELETE CASCADE,
    task_id      TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,  -- denormalized
    event_type   TEXT    NOT NULL,                    -- StageEventType enum name
    event_at_ms  INTEGER NOT NULL,
    payload_json TEXT
);
CREATE INDEX idx_task_stage_event_stage_at ON task_stage_event(stage_id, event_at_ms);
CREATE INDEX idx_task_stage_event_task_at ON task_stage_event(task_id, event_at_ms);
CREATE INDEX idx_task_stage_event_type_at ON task_stage_event(event_type, event_at_ms);

-- ── review_comment (unified entity) ─────────────────────────────────────
-- resolved is stored 0/1 (SQLite has no native boolean). The check
-- constraint ties remote_link to the REMOTE_REVIEWER source: set iff
-- remote-sourced. Only LOCAL_USER rows may exist for now; the other two
-- sources arrive with their write sites later, so the table ships
-- empty-ready.
CREATE TABLE review_comment (
    id            TEXT    NOT NULL PRIMARY KEY,
    task_id       TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    file          TEXT    NOT NULL,                   -- relative path
    line          INTEGER NOT NULL,
    body          TEXT    NOT NULL,                   -- markdown
    created_at_ms INTEGER NOT NULL,
    source        TEXT    NOT NULL,                   -- ReviewCommentSource enum name
    remote_link   TEXT,                               -- non-null iff source=REMOTE_REVIEWER
    resolved      INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT review_comment_remote_link_check
        CHECK ((source = 'REMOTE_REVIEWER' AND remote_link IS NOT NULL)
            OR (source <> 'REMOTE_REVIEWER' AND remote_link IS NULL))
);
CREATE INDEX idx_review_comment_task_resolved ON review_comment(task_id, resolved);
CREATE INDEX idx_review_comment_task_source ON review_comment(task_id, source);

-- ── tasks.merge_notification_sent_at ────────────────────────────────────
-- The "ready to merge" notify dedup field. Nullable, default null. Nothing
-- writes it yet; the dedup + auto-reset machinery lands with the monitor
-- stages. Column-only for now (no entity mapping) so it stays inert.
ALTER TABLE tasks ADD COLUMN merge_notification_sent_at_ms INTEGER;
