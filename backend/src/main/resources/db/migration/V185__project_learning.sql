-- Phase 1 of Project Intelligence: the durable, model-free learning run
-- that indexes local docs, derives a bounded project capsule, and catalogs
-- the complete merged-PR history. Everything here is deterministic — no
-- model calls, no vector/graph store — and lives beside (never inside) the
-- operational PR dashboard cache, whose replacement/expiry rules are unfit
-- for an archive.

-- One learning run per workspace repository. Its own persisted cursor and
-- counts let a restart resume incomplete work rather than restart the
-- repository from page one. Mirrors the workspace_creation coordinator:
-- a partial unique index keeps at most one live run per workspace+repo.
--
-- Column names track the design's field list; app-generated timestamps
-- carry the codebase-wide _ms epoch-millis convention. trigger_kind stands
-- in for the design's `trigger` (a reserved word in SQLite), matching the
-- distill_run precedent.
CREATE TABLE repo_learning_run (
    id                TEXT    NOT NULL PRIMARY KEY,
    workspace_id      TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo              TEXT    NOT NULL,                -- owner/name
    trigger_kind      TEXT    NOT NULL,               -- clone | manual
    state             TEXT    NOT NULL,               -- queued | indexing | cataloging | useful | caught-up | partial | failed
    snapshot_sha      TEXT,                           -- HEAD the doc index/capsule was built against
    catalog_cursor    TEXT,                           -- JSON: merge-date partitions + per-range page cursors
    counts_json       TEXT    NOT NULL DEFAULT '{}',  -- JSON: docsIndexed, cataloged, analyzed, lessons, ...
    extractor_version INTEGER NOT NULL DEFAULT 1,
    started_at_ms     INTEGER NOT NULL,
    updated_at_ms     INTEGER NOT NULL,
    completed_at_ms   INTEGER,
    last_error        TEXT
);

CREATE INDEX idx_repo_learning_run_state
    ON repo_learning_run(state, updated_at_ms);
CREATE UNIQUE INDEX idx_repo_learning_run_live
    ON repo_learning_run(workspace_id, repo)
    WHERE state IN ('queued', 'indexing', 'cataloging');

-- The complete merged-PR catalog. Cheap selection/reproducibility fields
-- only — git already holds the code and commit history, so no diff corpus
-- lands here. UNIQUE(workspace_id, repo, pr_number) plus the idempotency
-- key (source_digest + extractor_version) makes a rerun a no-op instead of
-- a duplicate. completeness_json records complete | partial:<source> |
-- unavailable so partial evidence is never mislabeled as complete.
CREATE TABLE repo_pr_source (
    workspace_id      TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo              TEXT    NOT NULL,
    pr_number         INTEGER NOT NULL,
    merged_at         TEXT,                            -- GitHub ISO-8601; ISO order drives date partitions
    merge_sha         TEXT,
    metadata_json     TEXT    NOT NULL DEFAULT '{}',   -- title, author, labels, base, counts, changed paths...
    completeness_json TEXT    NOT NULL DEFAULT '{}',   -- overall + per-source completeness markers
    source_digest     TEXT,
    priority_score    REAL,                            -- deterministic ranking is a later phase
    analysis_state    TEXT    NOT NULL DEFAULT 'cataloged', -- cataloged | analyzed
    extractor_version INTEGER NOT NULL DEFAULT 1,
    analyzed_at_ms    INTEGER,
    last_error        TEXT,
    PRIMARY KEY (workspace_id, repo, pr_number)
);

CREATE INDEX idx_repo_pr_source_analysis
    ON repo_pr_source(workspace_id, repo, analysis_state);

-- Heading-level local-document index. Markdown/rST/AsciiDoc split by
-- heading (not arbitrary windows); each section keeps enough to locate and
-- re-read the exact local span. The bulk text stays in the repo working
-- tree — only the reference lands here.
CREATE TABLE repo_doc_section (
    workspace_id      TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repo              TEXT    NOT NULL,
    path              TEXT    NOT NULL,                -- repo-relative document path
    heading_path      TEXT    NOT NULL,               -- "H1 > H2 > H3" trail; "" for the preamble
    line_start        INTEGER NOT NULL,
    line_end          INTEGER NOT NULL,
    content_digest    TEXT    NOT NULL,
    knowledge_type    TEXT,                            -- readme | contributing | architecture | adr | build | config | brief
    tags_json         TEXT    NOT NULL DEFAULT '[]',
    commit_sha        TEXT,
    indexed_at_ms     INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, repo, path, heading_path)
);

-- The bounded project capsule (<= ~4k chars) regenerated when its source
-- digests change. Stays in the local database; never written into the repo.
CREATE TABLE repo_project_capsule (
    workspace_id      TEXT    NOT NULL PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    repo              TEXT    NOT NULL,
    capsule_md        TEXT    NOT NULL,
    source_digest     TEXT    NOT NULL,
    generated_at_ms   INTEGER NOT NULL
);

-- Repair the seed-complete milestone. V181 derived it from the mere
-- presence of memory content; the milestone must instead mean an accepted
-- seed run. Clear it wherever no seed distill_run has been applied; the
-- knowledge service now sets it when a seed preview is applied.
UPDATE workspace_onboarding
   SET memory_seed_complete = 0
 WHERE workspace_id NOT IN (
       SELECT workspace_id FROM distill_run
        WHERE trigger_kind = 'seed' AND status = 'applied');
