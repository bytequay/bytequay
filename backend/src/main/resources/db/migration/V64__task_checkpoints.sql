-- AI-written summaries of conversation chunks. Two flavours share
-- this table: Overall (one per task, seq=0, is_overall=1) and the
-- per-segment summaries that cover consecutive turn ranges
-- (seq>=1). The doc
-- (docs/mockups/conversation-index-and-checkpoints-design.md) is
-- authoritative; this DDL mirrors it.
--
-- Distinct from snapshots — those rewind state, these summarise
-- it. The two live in different rail sections.
CREATE TABLE task_checkpoints (
    id                  TEXT    PRIMARY KEY,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,

    -- Per-segment identity. seq=0 is reserved for the Overall
    -- rollup; per-segment checkpoints start at 1.
    seq                 INTEGER NOT NULL,
    is_overall          INTEGER NOT NULL DEFAULT 0,

    -- Inclusive turn coverage. For Overall, last_msg_seq is the
    -- task's max seq at the moment it was generated.
    first_msg_seq       INTEGER NOT NULL,
    last_msg_seq        INTEGER NOT NULL,
    tokens_covered      INTEGER NOT NULL,

    -- Summary content. summary_md is Markdown; bullet_titles is a
    -- JSON array of 1-3 short bullets the UI uses for the rail
    -- preview without parsing the full Markdown.
    summary_md          TEXT    NOT NULL,
    bullet_titles       TEXT    NOT NULL DEFAULT '[]',

    -- Audit / provenance.
    model_used          TEXT    NOT NULL,
    prompt_tokens       INTEGER NOT NULL,
    completion_tokens   INTEGER NOT NULL,
    cost_usd_milli      INTEGER NOT NULL,
    generated_at_ms     INTEGER NOT NULL,

    -- Set on Overall rows when a newer Overall replaces them; the
    -- prior rows stay for history (cheap, gives time-travel via
    --   SELECT ... WHERE is_overall=1 ORDER BY generated_at_ms).
    superseded_at_ms    INTEGER,

    UNIQUE(task_id, seq)
);

-- Per-task ordered scan for the rail card: Overall first
-- (is_overall DESC), then segments newest-first.
CREATE INDEX idx_task_checkpoints_task_seq
    ON task_checkpoints(task_id, is_overall DESC, seq DESC);

-- Active-only filter: when serving the rail / cross-task picker we
-- want every row that hasn't been superseded — equivalent to
-- WHERE superseded_at_ms IS NULL.
CREATE INDEX idx_task_checkpoints_task_active
    ON task_checkpoints(task_id, superseded_at_ms);
