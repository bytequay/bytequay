-- Workspace tier: the persistent project brain that holds shared
-- memory across threads. One workspace owns 1..n repos and 0..n
-- threads; threads inside a workspace see its distilled memory
-- automatically while keeping their raw conversation history local.
-- See docs/mockups/workspace-thread-task-design.md for the model.
--
-- For now there is exactly one workspace per install — "ws-default"
-- named "ByteQuay" — and it adopts every existing thread plus every
-- repo on the watched list. Multi-workspace creation lands later;
-- this migration's job is to put the schema in place and stop
-- existing data from looking orphaned.

CREATE TABLE workspaces (
    id              TEXT    PRIMARY KEY,
    name            TEXT    NOT NULL,
    -- WORKSPACE.md content the app maintains in-DB. Loaded into every
    -- thread's system context; size-bounded to ~2k tokens target /
    -- ~4k cap by the service layer. Seeded blank — the distillation
    -- pass populates it over time as threads close.
    memory_md       TEXT    NOT NULL DEFAULT '',
    -- Scratch workspaces accrue no durable memory and never propose
    -- distillation upward. The default workspace is not scratch.
    is_scratch      INTEGER NOT NULL DEFAULT 0,
    created_at_ms   INTEGER NOT NULL,
    updated_at_ms   INTEGER NOT NULL
);

-- Repos attached to each workspace. Carries the per-repo merge-target
-- so "ship & continue" can cut the next task's branch from the right
-- base — e.g. "upstream/master" for a fork of trino, "main" for an
-- owned repo. Null falls back to GitRunner.defaultBranch on the
-- local clone.
CREATE TABLE workspace_repos (
    workspace_id        TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo_full_name      TEXT NOT NULL,
    default_base_branch TEXT,
    added_at_ms         INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, repo_full_name)
);

CREATE INDEX idx_workspace_repos_repo ON workspace_repos(repo_full_name);

ALTER TABLE threads ADD COLUMN workspace_id TEXT REFERENCES workspaces(id);
CREATE INDEX idx_threads_workspace_id ON threads(workspace_id);

-- Seed the default workspace and adopt everything that already exists.
INSERT INTO workspaces (id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
VALUES ('ws-default', 'ByteQuay', '', 0,
        strftime('%s','now') * 1000,
        strftime('%s','now') * 1000);

UPDATE threads
SET workspace_id = 'ws-default'
WHERE workspace_id IS NULL;

INSERT INTO workspace_repos (workspace_id, repo_full_name, default_base_branch, added_at_ms)
SELECT
    'ws-default',
    owner || '/' || repo,
    NULL,                         -- nothing known yet; user can fill in
    strftime('%s','now') * 1000
FROM watched_repos;
