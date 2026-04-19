-- Cached PR detail — one row per pull request.
-- Populated by the sync job whenever the PR's updated_at changes.
CREATE TABLE pr_detail (
    pr_id                    INTEGER NOT NULL PRIMARY KEY,
    body                     TEXT,
    labels                   TEXT    NOT NULL DEFAULT '[]',
    draft                    INTEGER NOT NULL DEFAULT 0,
    mergeable                INTEGER,
    mergeable_state          TEXT,
    additions                INTEGER NOT NULL DEFAULT 0,
    deletions                INTEGER NOT NULL DEFAULT 0,
    changed_files            INTEGER NOT NULL DEFAULT 0,
    requested_reviewer_count INTEGER NOT NULL DEFAULT 0,
    head_sha                 TEXT,
    synced_at                TEXT    NOT NULL
);

-- Review states per PR (APPROVED / CHANGES_REQUESTED / etc.).
-- Replaced wholesale on each detail sync.
CREATE TABLE pr_reviews (
    id      INTEGER NOT NULL PRIMARY KEY,
    pr_id   INTEGER NOT NULL,
    login   TEXT    NOT NULL,
    state   TEXT    NOT NULL
);

-- Files changed by a pull request.
-- Replaced wholesale on each detail sync.
CREATE TABLE pr_files (
    id        INTEGER NOT NULL PRIMARY KEY,
    pr_id     INTEGER NOT NULL,
    filename  TEXT    NOT NULL,
    additions INTEGER NOT NULL DEFAULT 0,
    deletions INTEGER NOT NULL DEFAULT 0,
    status    TEXT
);

-- Selected timeline events for a pull request.
-- Replaced wholesale on each detail sync.
CREATE TABLE pr_timeline (
    id        INTEGER NOT NULL PRIMARY KEY,
    pr_id     INTEGER NOT NULL,
    event     TEXT,
    actor     TEXT,
    state     TEXT,
    timestamp TEXT
);

-- CI check-run outcomes for the head commit of a pull request.
-- Replaced wholesale on each detail sync.
CREATE TABLE pr_check_runs (
    id         INTEGER NOT NULL PRIMARY KEY,
    pr_id      INTEGER NOT NULL,
    status     TEXT,
    conclusion TEXT
);
