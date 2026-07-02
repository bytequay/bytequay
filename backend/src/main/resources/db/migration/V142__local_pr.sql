-- Local PR: a full pull request that lives entirely in ByteQuay before it
-- ever reaches GitHub — description, commits, timeline, checks, comments —
-- and is pushed to GitHub only on explicit user approval.
--
-- One task = one local PR (multi-PR tasks are a later milestone), so local_pr
-- is UNIQUE on task_id. Branch/push/merge *runtime* state still lives on the
-- tasks row (branch_name, pushed_at_ms, linked_pr_ref, merge_authorized_at_ms);
-- local_pr owns the PR *artifact* (title/description/status + its child
-- timeline/commits/checks/comments) and copies branch_name from the task at
-- create time. Nothing here mirrors GitHub — the pull_requests / pr_detail
-- caches (V4) stay the GitHub-id-keyed sync mirror and are untouched.
--
-- Additive only. UUID ids are TEXT and timestamps are epoch-ms INTEGER,
-- matching the rest of the SQLite schema (cf. task_stage in V116). Booleans
-- are 0/1 INTEGER (SQLite has no native boolean).
--
-- NOTE (reconcile deferred): review_comment (V116) already unifies inline
-- LOCAL_USER / LOCAL_AGENT / REMOTE_REVIEWER comments task-scoped. local_pr_comment
-- adds PR-level scope, threading, origin, and stripped-on-push tracking that
-- review_comment lacks; it ships here inert (no reader yet) and the two are
-- reconciled when the Code Diff / PR comment UI is wired.

-- ── local_pr ────────────────────────────────────────────────────────────
-- status stores the LocalPR status wire value: local-drafted / local-open /
-- remote-drafted / remote-open / merged / closed (see design #45).
CREATE TABLE local_pr (
    id                TEXT    NOT NULL PRIMARY KEY,
    task_id           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    branch_name       TEXT    NOT NULL,               -- copied from tasks.branch_name
    base_branch       TEXT    NOT NULL,
    title             TEXT    NOT NULL,
    description       TEXT    NOT NULL DEFAULT '',     -- markdown
    status            TEXT    NOT NULL,                -- LocalPRStatus wire value
    created_at_ms     INTEGER NOT NULL,
    pushed_at_ms      INTEGER,                         -- null until Remote Push
    remote_pr_number  INTEGER,                         -- populated on push
    remote_pr_url     TEXT,
    merged_at_ms      INTEGER,
    closed_at_ms      INTEGER
);
-- One local PR per task — the create endpoint is idempotent on this.
CREATE UNIQUE INDEX idx_local_pr_task ON local_pr(task_id);

-- ── local_pr_commit ─────────────────────────────────────────────────────
-- Unpushed local commits carry pushed_at_ms = null; set on Remote Push.
CREATE TABLE local_pr_commit (
    id             TEXT    NOT NULL PRIMARY KEY,
    local_pr_id    TEXT    NOT NULL REFERENCES local_pr(id) ON DELETE CASCADE,
    sha            TEXT    NOT NULL,                   -- short sha shown in UI
    message        TEXT    NOT NULL,
    additions      INTEGER NOT NULL,
    deletions      INTEGER NOT NULL,
    authored_at_ms INTEGER NOT NULL,
    pushed_at_ms   INTEGER
);
CREATE INDEX idx_local_pr_commit_pr_authored ON local_pr_commit(local_pr_id, authored_at_ms);

-- ── local_pr_timeline_event ─────────────────────────────────────────────
-- The unified event stream (commit / ci / amend / branch / status / review /
-- comment / follow-up). is_local_only rows render with a lock marker and are
-- stripped on push (stripped_on_push_at_ms stamped, never migrated to GitHub).
CREATE TABLE local_pr_timeline_event (
    id                     TEXT    NOT NULL PRIMARY KEY,
    local_pr_id            TEXT    NOT NULL REFERENCES local_pr(id) ON DELETE CASCADE,
    event_type             TEXT    NOT NULL,           -- LocalPRTimelineEventType wire value
    actor                  TEXT    NOT NULL,           -- "claude-code" | "you" | "@<github-user>"
    is_local_only          INTEGER NOT NULL DEFAULT 0,
    stripped_on_push_at_ms INTEGER,
    created_at_ms          INTEGER NOT NULL,
    payload_json           TEXT
);
CREATE INDEX idx_local_pr_timeline_pr_created ON local_pr_timeline_event(local_pr_id, created_at_ms);

-- ── local_pr_check ──────────────────────────────────────────────────────
-- kind = local (mvn verify / tsc / vitest, every dev iteration) or remote
-- (GitHub Actions, populated after push). run_id is the Actions run id,
-- remote-only.
CREATE TABLE local_pr_check (
    id             TEXT    NOT NULL PRIMARY KEY,
    local_pr_id    TEXT    NOT NULL REFERENCES local_pr(id) ON DELETE CASCADE,
    kind           TEXT    NOT NULL,                   -- local | remote
    name           TEXT    NOT NULL,                   -- "mvn verify" | "backend / unit-tests"
    status         TEXT    NOT NULL,                   -- pending|running|passed|failed|neutral
    duration_ms    INTEGER,
    started_at_ms  INTEGER NOT NULL,
    finished_at_ms INTEGER,
    run_id         TEXT                                -- GitHub Actions run id, remote only
);
CREATE INDEX idx_local_pr_check_pr_kind ON local_pr_check(local_pr_id, kind);

-- ── local_pr_comment ────────────────────────────────────────────────────
-- origin = local (never migrates — stripped on push) or remote (GitHub).
-- scope = pr (file_path / line_number null) or file-line. parent_comment_id
-- self-references for a single-reply thread (deeper threading is later).
CREATE TABLE local_pr_comment (
    id                     TEXT    NOT NULL PRIMARY KEY,
    local_pr_id            TEXT    NOT NULL REFERENCES local_pr(id) ON DELETE CASCADE,
    origin                 TEXT    NOT NULL,           -- local | remote
    scope                  TEXT    NOT NULL,           -- pr | file-line
    file_path              TEXT,                       -- null iff scope = pr
    line_number            INTEGER,
    author                 TEXT    NOT NULL,
    body                   TEXT    NOT NULL,
    created_at_ms          INTEGER NOT NULL,
    resolved_at_ms         INTEGER,
    stripped_on_push_at_ms INTEGER,
    parent_comment_id      TEXT    REFERENCES local_pr_comment(id),
    CONSTRAINT local_pr_comment_scope_check
        CHECK ((scope = 'pr' AND file_path IS NULL AND line_number IS NULL)
            OR (scope = 'file-line' AND file_path IS NOT NULL AND line_number IS NOT NULL))
);
CREATE INDEX idx_local_pr_comment_pr_origin ON local_pr_comment(local_pr_id, origin);
