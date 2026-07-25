-- Project Intelligence phase 4: record exactly which knowledge/memory the
-- session projection inserted into an agent's context, so the context
-- inspector can explain good and bad influence instead of guessing. One row
-- per (workspace, audience) — the latest projection wins; the full history
-- is not an audit requirement.
--
-- The FTS5 search index over knowledge_item is deliberately NOT created
-- here: it is managed at runtime so a packaged SQLite without FTS5 degrades
-- to indexed LIKE search instead of failing every migration at startup.
CREATE TABLE session_context_projection (
    workspace_id    TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    audience        TEXT    NOT NULL,               -- plan | dev | review | ci-fix
    query_hint      TEXT,                           -- thread/task text the retrieval used
    item_ids_json   TEXT    NOT NULL DEFAULT '[]',  -- knowledge ids inserted into context
    capsule_chars   INTEGER NOT NULL DEFAULT 0,
    brain_chars     INTEGER NOT NULL DEFAULT 0,
    retrieved_chars INTEGER NOT NULL DEFAULT 0,
    created_at_ms   INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, audience)
);
