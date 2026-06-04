-- Typed memory items — the structured shape that's replacing the
-- free-form memoryMd blob. Each row is one durable observation
-- about a workspace or a thread (DECISION, BLOCKER, CONVENTION,
-- FOCUS_SHIFT, OPEN_QUESTION, RECURRING_PATTERN).
--
-- Lifecycle, encoded as nullable timestamps so a single table holds
-- everything from "proposed" to "applied" to "superseded":
--   - proposed_at_ms is set when the distiller (or a user paste)
--     creates the row. It is NOT NULL.
--   - applied_at_ms flips from NULL → wall-clock-ms when the user
--     clicks Apply. The render-to-md pass only walks rows where
--     this is set.
--   - resolved_at_ms is for items that have a finite lifetime
--     (OPEN_QUESTION → answered, BLOCKER → cleared, etc.). Recall
--     still surfaces them but as historical.
--   - superseded_by points at the row that replaces this one.
--     A non-NULL value means the row is dead for new contexts but
--     still recallable.
--
-- sources_json carries [{threadId, taskId?, prRef?, messageStart?,
-- messageEnd?}] — provenance is non-negotiable per Phase E.
--
-- Two scopes today (WORKSPACE / THREAD); the same row shape covers
-- both so the meta-tools can read them with one query.
CREATE TABLE memory_item (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    scope_kind      TEXT    NOT NULL CHECK (scope_kind IN ('WORKSPACE', 'THREAD')),
    scope_id        TEXT    NOT NULL,
    kind            TEXT    NOT NULL CHECK (kind IN (
                        'DECISION', 'BLOCKER', 'CONVENTION',
                        'FOCUS_SHIFT', 'OPEN_QUESTION', 'RECURRING_PATTERN')),
    text            TEXT    NOT NULL,
    sources_json    TEXT    NOT NULL DEFAULT '[]',
    confidence      TEXT    NOT NULL CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    tags_json       TEXT    NOT NULL DEFAULT '[]',
    superseded_by   INTEGER REFERENCES memory_item(id) ON DELETE SET NULL,
    resolved_at_ms  INTEGER,
    proposed_at_ms  INTEGER NOT NULL,
    applied_at_ms   INTEGER,
    source          TEXT    NOT NULL CHECK (source IN ('DISTILL', 'INLINE', 'USER_TYPED'))
);

CREATE INDEX memory_item_scope_kind_idx
    ON memory_item(scope_kind, scope_id, kind, applied_at_ms);

CREATE INDEX memory_item_pending_idx
    ON memory_item(scope_kind, scope_id, applied_at_ms)
    WHERE applied_at_ms IS NULL;
