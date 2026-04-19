-- Persistent store for GitHub's per-line PR review comments. These power
-- the threaded conversation in the PR detail page (see
-- docs/mockups/v2/conversation.png) — each top-level comment plus its
-- replies forms a thread anchored to a specific (file, line) in the
-- PR's diff, with the surrounding diff hunk preserved as context.
--
-- Sourced from /repos/{owner}/{repo}/pulls/{pull_number}/comments. The
-- pr_id+github_id pair is unique so a refetch upserts cleanly. Threads
-- group on coalesce(in_reply_to, github_id).
CREATE TABLE pr_review_thread_message (
    id              INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    pr_id           INTEGER NOT NULL,
    github_id       INTEGER NOT NULL,
    in_reply_to     INTEGER,
    review_id       INTEGER,
    author          TEXT,
    body            TEXT,
    file_path       TEXT,
    line_number     INTEGER,
    side            TEXT,
    diff_hunk       TEXT,
    commit_id       TEXT,
    created_at      TEXT,
    UNIQUE (pr_id, github_id)
);

CREATE INDEX pr_review_thread_message_pr_id_idx ON pr_review_thread_message(pr_id);
