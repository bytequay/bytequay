-- Per-thread scope overrides. Each Thread is a lightweight scope
-- (caps, permission guardrails, prompt addenda) that inherits from
-- the workspace and global defaults; rows here exist only when a
-- thread tightens or overrides one of those settings. Sparse-by-
-- design — a fresh thread silently inherits with no row in this
-- table.
--
-- Stored as a sibling table rather than columns on `threads` so the
-- Thread domain record (and its many constructor call sites) stays
-- untouched. ThreadSettingsService resolves effective config at
-- agent-spawn time as merge(global, workspace, thread, task).

CREATE TABLE thread_settings (
    thread_id            TEXT    PRIMARY KEY REFERENCES threads(id) ON DELETE CASCADE,

    -- Concurrency cap: the per-thread max number of Tasks that may
    -- be RUNNING simultaneously. NULL = inherit. Hard cap — the
    -- scheduler queues over-cap work via its fair-share lanes.
    max_running_tasks    INTEGER,

    -- Two-tier cost budget. soft = warn-and-continue threshold the
    -- UI surfaces as a banner; hard = pause-and-ask ceiling the
    -- scheduler refuses to cross without an explicit OK. NULL = inherit.
    -- Both are milli-USD to match the rest of the cost columns.
    soft_cost_usd_milli  INTEGER,
    hard_cost_usd_milli  INTEGER,

    -- Thread prompt addendum: free-form text concatenated onto the
    -- workspace memory at every CLI / logic-loop spawn. Guidance,
    -- not security — relaxing a guardrail goes through a different
    -- (future) column with explicit flagging.
    prompt_addendum      TEXT,

    updated_at_ms        INTEGER NOT NULL
);
