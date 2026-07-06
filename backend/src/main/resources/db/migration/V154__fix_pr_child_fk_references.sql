-- V152 rebuilt `pr` via CREATE pr_new + INSERT + DROP local_pr + RENAME
-- pr_new->pr (needed to relax task_id to nullable), rather than a straight
-- `ALTER TABLE local_pr RENAME TO pr`. SQLite only rewrites OTHER tables'
-- FOREIGN KEY clauses that say `REFERENCES local_pr(...)` when the referenced
-- table itself is renamed via ALTER TABLE — dropping `local_pr` and replacing
-- it with an unrelated `pr_new`/`pr` doesn't count, so `pr_commit`,
-- `pr_timeline_event`, `pr_check`, and `pr_comment` were left with a dangling
-- `REFERENCES local_pr(id)` (a table that no longer exists), breaking every
-- insert once SQLite actually enforces the constraint. Rebuild each child
-- table with the FK correctly pointing at `pr(id)`.

CREATE TABLE pr_commit_new (
    id             TEXT    NOT NULL PRIMARY KEY,
    pr_id          TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    sha            TEXT    NOT NULL,
    message        TEXT    NOT NULL,
    additions      INTEGER NOT NULL,
    deletions      INTEGER NOT NULL,
    authored_at_ms INTEGER NOT NULL,
    pushed_at_ms   INTEGER
);
INSERT INTO pr_commit_new SELECT id, pr_id, sha, message, additions, deletions, authored_at_ms, pushed_at_ms FROM pr_commit;
DROP TABLE pr_commit;
ALTER TABLE pr_commit_new RENAME TO pr_commit;
CREATE INDEX idx_pr_commit_pr_authored ON pr_commit(pr_id, authored_at_ms);

CREATE TABLE pr_timeline_event_new (
    id                     TEXT    NOT NULL PRIMARY KEY,
    pr_id                  TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    event_type             TEXT    NOT NULL,
    actor                  TEXT    NOT NULL,
    is_local_only          INTEGER NOT NULL DEFAULT 0,
    stripped_on_push_at_ms INTEGER,
    created_at_ms          INTEGER NOT NULL,
    payload_json           TEXT,
    remote_event_id        INTEGER
);
INSERT INTO pr_timeline_event_new
    SELECT id, pr_id, event_type, actor, is_local_only, stripped_on_push_at_ms, created_at_ms, payload_json, remote_event_id
    FROM pr_timeline_event;
DROP TABLE pr_timeline_event;
ALTER TABLE pr_timeline_event_new RENAME TO pr_timeline_event;
CREATE INDEX idx_pr_timeline_pr_created ON pr_timeline_event(pr_id, created_at_ms);
CREATE INDEX idx_pr_timeline_remote_id ON pr_timeline_event(pr_id, remote_event_id);

CREATE TABLE pr_check_new (
    id             TEXT    NOT NULL PRIMARY KEY,
    pr_id          TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    kind           TEXT    NOT NULL,
    name           TEXT    NOT NULL,
    status         TEXT    NOT NULL,
    duration_ms    INTEGER,
    started_at_ms  INTEGER NOT NULL,
    finished_at_ms INTEGER,
    run_id         TEXT
);
INSERT INTO pr_check_new SELECT id, pr_id, kind, name, status, duration_ms, started_at_ms, finished_at_ms, run_id FROM pr_check;
DROP TABLE pr_check;
ALTER TABLE pr_check_new RENAME TO pr_check;
CREATE INDEX idx_pr_check_pr_kind ON pr_check(pr_id, kind);

CREATE TABLE pr_comment_new (
    id                     TEXT    NOT NULL PRIMARY KEY,
    pr_id                  TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    origin                 TEXT    NOT NULL,
    scope                  TEXT    NOT NULL,
    file_path              TEXT,
    line_number            INTEGER,
    author                 TEXT    NOT NULL,
    body                   TEXT    NOT NULL,
    created_at_ms          INTEGER NOT NULL,
    resolved_at_ms         INTEGER,
    dismissed_at_ms        INTEGER,
    stripped_on_push_at_ms INTEGER,
    parent_comment_id      TEXT    REFERENCES pr_comment_new(id),
    published_at_ms        INTEGER,
    CONSTRAINT pr_comment_scope_check
        CHECK ((scope = 'pr' AND file_path IS NULL AND line_number IS NULL)
            OR (scope = 'file-line' AND file_path IS NOT NULL AND line_number IS NOT NULL))
);
INSERT INTO pr_comment_new
    SELECT id, pr_id, origin, scope, file_path, line_number, author, body, created_at_ms,
           resolved_at_ms, dismissed_at_ms, stripped_on_push_at_ms, parent_comment_id, published_at_ms
    FROM pr_comment;
DROP TABLE pr_comment;
ALTER TABLE pr_comment_new RENAME TO pr_comment;
CREATE INDEX idx_pr_comment_pr_origin ON pr_comment(pr_id, origin);
