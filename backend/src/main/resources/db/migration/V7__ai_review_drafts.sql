-- A single AI review attempt for a PR, plus its line-anchored comments.
-- Each run produces one pr_review_draft row; historical runs are kept so you
-- can compare model output across runs. The Inbox UI shows the latest.
CREATE TABLE pr_review_draft (
    id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    pr_id         INTEGER NOT NULL,
    summary       TEXT,
    provider_id   TEXT    NOT NULL,
    model         TEXT    NOT NULL,
    head_sha      TEXT,
    status        TEXT    NOT NULL DEFAULT 'COMPLETE',
    created_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX pr_review_draft_pr_id_idx ON pr_review_draft(pr_id);

CREATE TABLE pr_review_comment (
    id           INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    draft_id     INTEGER NOT NULL,
    file_path    TEXT    NOT NULL,
    line_number  INTEGER NOT NULL,
    body         TEXT    NOT NULL,
    severity     TEXT    NOT NULL DEFAULT 'suggestion',
    created_at   TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (draft_id) REFERENCES pr_review_draft(id) ON DELETE CASCADE
);

CREATE INDEX pr_review_comment_draft_id_idx ON pr_review_comment(draft_id);
