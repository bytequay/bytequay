--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--

-- General skill table. Replaces review_skill, which only modelled a single
-- per-repo review hint and overloaded the repo column as a scope sentinel
-- ('*', 'domain:<role>', else 'owner/name').
--
-- Three scopes ('global' / 'repo' / 'thread'); kind separates library skills
-- (model picks up via list_skills / load_skill), personas (always-on identity
-- per role), and rubrics (deterministic review-time rules). role_tag binds a
-- row to an agent role independently of scope so a global persona can target
-- "reviewer" without inventing a sentinel repo string.
--
-- Bodies live in this table; skill_asset holds optional companion files
-- (e.g. a checklist the body references). Nothing on disk in the user's
-- repo — the DB stays the source of truth; CLI lanes materialise resolved
-- skills into a session-scoped temp dir at turn start.
CREATE TABLE skill (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope TEXT NOT NULL CHECK (scope IN ('global', 'repo', 'thread')),
    repo TEXT,
    thread_id TEXT,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    body TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('library', 'persona', 'rubric')),
    role_tag TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    is_default INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT 'authored' CHECK (source IN ('authored', 'ai_drafted')),
    provenance TEXT,
    content_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX skill_scope_idx ON skill(scope, repo, enabled);
CREATE INDEX skill_role_idx ON skill(role_tag, enabled);

CREATE TABLE skill_asset (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_id INTEGER NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    filename TEXT NOT NULL,
    content BLOB NOT NULL
);

CREATE INDEX skill_asset_skill_idx ON skill_asset(skill_id);

-- Migrate existing review_skill rows.
--   '*'         → scope='global', role_tag=NULL  (general library)
--   'domain:X'  → scope='global', role_tag='X'   (persona for role X)
--   else        → scope='repo',   repo=<value>   (rubric for that repo)
INSERT INTO skill (
    scope, repo, thread_id, name, description, body, kind,
    role_tag, enabled, is_default, source, provenance, content_hash,
    created_at, updated_at)
SELECT
    CASE
        WHEN repo = '*' THEN 'global'
        WHEN repo LIKE 'domain:%' THEN 'global'
        ELSE 'repo'
    END,
    CASE
        WHEN repo = '*' THEN NULL
        WHEN repo LIKE 'domain:%' THEN NULL
        ELSE repo
    END,
    NULL,
    skill_name,
    COALESCE(description, ''),
    COALESCE(context, ''),
    CASE
        WHEN repo = '*' THEN 'library'
        WHEN repo LIKE 'domain:%' THEN 'persona'
        ELSE 'rubric'
    END,
    CASE
        WHEN repo LIKE 'domain:%' THEN substr(repo, 8)
        ELSE NULL
    END,
    enabled,
    0,
    'authored',
    NULL,
    'migrated:' || id,
    created_at,
    updated_at
FROM review_skill;

DROP TABLE review_skill;
