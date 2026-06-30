-- Write-only audit log of explicit user decisions (skip / revive / start a
-- backlog item, approve / reject a plan, dismiss / address a follow-up, …).
-- A future "memory" function reads these to learn the user's preferences
-- across the whole app; v1 only writes them, so there's no read path yet.
--
-- thread_id / workspace_id are plain pointers (no FK) so the trail survives
-- the thread/workspace being deleted. context_snapshot_json is a small JSON
-- blob capturing what was on screen at decision time.
CREATE TABLE distillation_signal (
    id                    TEXT    NOT NULL PRIMARY KEY,
    event_type            TEXT    NOT NULL,
    source_id             TEXT    NOT NULL,
    user_decision         TEXT    NOT NULL,
    reason                TEXT,
    context_snapshot_json TEXT    NOT NULL DEFAULT '{}',
    thread_id             TEXT,
    workspace_id          TEXT,
    created_at_ms         INTEGER NOT NULL
);

CREATE INDEX idx_distillation_signal_created ON distillation_signal(created_at_ms);
CREATE INDEX idx_distillation_signal_event ON distillation_signal(event_type, created_at_ms);
