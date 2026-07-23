-- Phase 2 of Project Intelligence: the deterministic ranking + snapshot-pinned
-- evidence stage over the Phase 1 merged-PR catalog. Still model-free — no
-- extraction, no learned lessons. The evidence store lives beside
-- repo_pr_source (never inside the operational PrDetailStore cache, whose
-- replacement/expiry rules are unfit for an archive).
--
-- Every row is pinned to a repository snapshot: base/head/merge SHA plus the
-- default-branch (checkout) SHA the "current code" mapping ran against. No
-- bulk diff lands here — only stable refs (GitHub ids, URLs, commit SHAs,
-- file paths + line spans) and content digests. A ref must never cross the
-- pinned repo SHA.

-- One evidence bundle per analyzed PR. completeness_json records per-source
-- markers (complete | partial:<source> | unavailable); overall_completeness
-- carries the single roll-up marker so a partial bundle is never mislabeled
-- as complete.
CREATE TABLE repo_pr_evidence_bundle (
    workspace_id         TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo                 TEXT    NOT NULL,
    pr_number            INTEGER NOT NULL,
    base_sha             TEXT,
    head_sha             TEXT,
    merge_sha            TEXT,
    repo_sha             TEXT,                          -- pinned default-branch/checkout snapshot
    overall_completeness TEXT    NOT NULL,              -- complete | partial:<source> | unavailable
    completeness_json    TEXT    NOT NULL DEFAULT '{}', -- per-source markers
    priority_score       REAL,
    extractor_version    INTEGER NOT NULL DEFAULT 1,
    built_at_ms          INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, repo, pr_number)
);

-- Stable references + digests for one bundle. No bulk diff — the diff is
-- fetched during analysis, mapped to files/symbols, and discarded; only the
-- span coordinates and a content digest survive. commit_sha is the snapshot
-- the ref is pinned to and must be one of the bundle's pinned SHAs.
CREATE TABLE repo_pr_evidence_ref (
    id             INTEGER PRIMARY KEY,
    workspace_id   TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo           TEXT    NOT NULL,
    pr_number      INTEGER NOT NULL,
    ref_kind       TEXT    NOT NULL,   -- review | thread | commit | file | symbol | test | timeline
    github_id      TEXT,               -- PR / thread / comment GitHub id
    url            TEXT,
    commit_sha     TEXT,               -- pinned snapshot; never crosses repo_sha
    file_path      TEXT,
    line_start     INTEGER,
    line_end       INTEGER,
    content_digest TEXT,
    detail_json    TEXT    NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_repo_pr_evidence_ref_pr
    ON repo_pr_evidence_ref(workspace_id, repo, pr_number);

-- Reconstructed reviewer-concern -> author-change -> resolution -> merge
-- chains. Depth measures resolved concern->change linkage, not comment count,
-- so a 40-comment naming debate with no linked change scores below a
-- three-message fix that actually changed code.
CREATE TABLE repo_pr_evidence_outcome_chain (
    id                  INTEGER PRIMARY KEY,
    workspace_id        TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo                TEXT    NOT NULL,
    pr_number           INTEGER NOT NULL,
    concern_author      TEXT,
    concern_path        TEXT,
    concern_ref         TEXT,               -- stable ref (root comment id)
    addressed_by_commit TEXT,               -- author follow-up commit SHA
    resolved            INTEGER NOT NULL DEFAULT 0,
    merged              INTEGER NOT NULL DEFAULT 0,
    depth               INTEGER NOT NULL DEFAULT 0,
    content_digest      TEXT,
    detail_json         TEXT    NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_repo_pr_evidence_chain_pr
    ON repo_pr_evidence_outcome_chain(workspace_id, repo, pr_number);
