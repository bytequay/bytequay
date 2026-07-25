-- A workspace may read commits and tags from one other local workspace.
-- The relation is deliberately directional: the upstream workspace has no
-- reverse row and therefore never learns about or writes to its fork.
CREATE TABLE workspace_relation (
    workspace_id                TEXT PRIMARY KEY
                                REFERENCES workspaces(id) ON DELETE CASCADE,
    upstream_workspace_id       TEXT NOT NULL
                                REFERENCES workspaces(id) ON DELETE CASCADE,
    commits_enabled             INTEGER NOT NULL DEFAULT 1
                                CHECK (commits_enabled IN (0, 1)),
    tags_enabled                INTEGER NOT NULL DEFAULT 1
                                CHECK (tags_enabled IN (0, 1)),
    last_fetched_at_ms          INTEGER,
    auto_fetch_interval_minutes INTEGER NOT NULL DEFAULT 15
                                CHECK (auto_fetch_interval_minutes BETWEEN 1 AND 1440),
    indexed_commit_count        INTEGER NOT NULL DEFAULT 0
                                CHECK (indexed_commit_count >= 0),
    created_at_ms               INTEGER NOT NULL,
    updated_at_ms               INTEGER NOT NULL,
    CHECK (workspace_id <> upstream_workspace_id)
);

CREATE INDEX idx_workspace_relation_upstream
    ON workspace_relation(upstream_workspace_id);
