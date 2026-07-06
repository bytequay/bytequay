-- Generalizes the local-PR aggregate into the unified PR surface: a `pr` row
-- now represents either a task-origin PR (the local-phase artifact this
-- table already stored) or an external PR opened outside the app and synced
-- in from GitHub.
--
-- task_id becomes nullable (external PRs have none) — SQLite can't relax a
-- NOT NULL column in place, so the table is rebuilt (cf. V9). New columns:
-- origin (task | external), repo (owner/name, required for external),
-- author, synced_at_ms (last successful GitHub sync). The origin/status
-- CHECK mirrors the state machine: an external PR never occupies the
-- local-only statuses (local-drafted / local-open).
CREATE TABLE pr_new (
    id                         TEXT    NOT NULL PRIMARY KEY,
    task_id                    TEXT    REFERENCES tasks(id) ON DELETE CASCADE,
    branch_name                TEXT    NOT NULL,
    base_branch                TEXT    NOT NULL,
    title                      TEXT    NOT NULL,
    description                TEXT    NOT NULL DEFAULT '',
    status                     TEXT    NOT NULL,
    created_at_ms              INTEGER NOT NULL,
    pushed_at_ms               INTEGER,
    remote_pr_number           INTEGER,
    remote_pr_url              TEXT,
    merged_at_ms               INTEGER,
    closed_at_ms               INTEGER,
    local_addressed_through_ms INTEGER,
    origin                     TEXT    NOT NULL DEFAULT 'task',
    repo                       TEXT,
    author                     TEXT,
    synced_at_ms               INTEGER,
    CONSTRAINT pr_origin_status_check
        CHECK (origin = 'task' OR status IN ('remote-drafted', 'remote-open', 'merged', 'closed'))
);

INSERT INTO pr_new (id, task_id, branch_name, base_branch, title, description, status,
                     created_at_ms, pushed_at_ms, remote_pr_number, remote_pr_url,
                     merged_at_ms, closed_at_ms, local_addressed_through_ms, origin)
SELECT id, task_id, branch_name, base_branch, title, description, status,
       created_at_ms, pushed_at_ms, remote_pr_number, remote_pr_url,
       merged_at_ms, closed_at_ms, local_addressed_through_ms, 'task'
FROM local_pr;

DROP TABLE local_pr;
ALTER TABLE pr_new RENAME TO pr;

CREATE UNIQUE INDEX idx_pr_task ON pr(task_id);
-- One row per (repo, remote PR number) among synced-in external PRs.
CREATE UNIQUE INDEX idx_pr_repo_remote_number ON pr(repo, remote_pr_number) WHERE origin = 'external';

-- Child tables: rename table + FK column only, no shape changes.
ALTER TABLE local_pr_commit RENAME TO pr_commit;
ALTER TABLE pr_commit RENAME COLUMN local_pr_id TO pr_id;
DROP INDEX idx_local_pr_commit_pr_authored;
CREATE INDEX idx_pr_commit_pr_authored ON pr_commit(pr_id, authored_at_ms);

ALTER TABLE local_pr_timeline_event RENAME TO pr_timeline_event;
ALTER TABLE pr_timeline_event RENAME COLUMN local_pr_id TO pr_id;
DROP INDEX idx_local_pr_timeline_pr_created;
CREATE INDEX idx_pr_timeline_pr_created ON pr_timeline_event(pr_id, created_at_ms);
DROP INDEX idx_local_pr_timeline_remote_id;
CREATE INDEX idx_pr_timeline_remote_id ON pr_timeline_event(pr_id, remote_event_id);

ALTER TABLE local_pr_check RENAME TO pr_check;
ALTER TABLE pr_check RENAME COLUMN local_pr_id TO pr_id;
DROP INDEX idx_local_pr_check_pr_kind;
CREATE INDEX idx_pr_check_pr_kind ON pr_check(pr_id, kind);

ALTER TABLE local_pr_comment RENAME TO pr_comment;
ALTER TABLE pr_comment RENAME COLUMN local_pr_id TO pr_id;
DROP INDEX idx_local_pr_comment_pr_origin;
CREATE INDEX idx_pr_comment_pr_origin ON pr_comment(pr_id, origin);
