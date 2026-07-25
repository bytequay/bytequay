-- Project Intelligence phase 3: promote knowledge_item to the canonical
-- long-term repository-knowledge store and fold kb_entry into it, so the
-- app keeps exactly two knowledge stores — knowledge_item (repository
-- knowledge) and memory_item (experiential workspace/thread memory) — plus
-- distill_run as the reversible proposal envelope.
--
-- knowledge_item and knowledge_provenance were created by V166 for the
-- investigation-review flow but no writer for either has ever shipped
-- (readers only), so both tables are empty in every real database and can
-- be recreated with the canonical shape instead of patched by ALTERs.

DROP TABLE knowledge_provenance;
DROP TABLE knowledge_item;

-- The canonical learned-knowledge row. Column names keep V166's vocabulary
-- where it overlaps the canonical model: subtype is the knowledge kind
-- (architecture-principle | domain-invariant | investigation-recipe |
-- recurring-concern | design-rationale | performance-assumption |
-- compatibility-contract | glossary | build-test-rule | doc-note), state is
-- the lifecycle (pending | active | decayed | retired). Only active rows may
-- influence an agent. workspace_id is nullable so repo-scoped rows written
-- by the investigation-review flow stay representable.
CREATE TABLE knowledge_item (
    id                  TEXT    NOT NULL PRIMARY KEY,
    workspace_id        TEXT    REFERENCES workspaces(id) ON DELETE CASCADE,
    repo_id             TEXT    NOT NULL,               -- owner/name
    subtype             TEXT    NOT NULL,               -- knowledge kind
    title               TEXT,
    statement           TEXT    NOT NULL,               -- one concise, actionable fact
    rationale           TEXT,
    steps_json          TEXT,                           -- investigation-recipe steps
    trigger_json        TEXT    NOT NULL DEFAULT '{}',
    state               TEXT    NOT NULL DEFAULT 'pending',
    counters_json       TEXT    NOT NULL DEFAULT '{}',  -- confirmations, conflictsWith, notes
    audiences_json      TEXT    NOT NULL DEFAULT '[]',  -- plan | dev | review | ci-fix
    confidence          TEXT    NOT NULL DEFAULT 'medium',
    validated_at_commit TEXT,                           -- default-branch SHA currentness ran against
    last_verified_at_ms INTEGER,
    created_by          TEXT    NOT NULL DEFAULT 'user', -- user | docs-bootstrap | distill | pr-learning | review-synthesizer
    statement_digest    TEXT,                           -- normalized-statement dedup key
    created_at_ms       INTEGER NOT NULL DEFAULT 0,
    updated_at_ms       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_knowledge_item_workspace
    ON knowledge_item(workspace_id, state, updated_at_ms DESC);
CREATE INDEX idx_knowledge_item_repo
    ON knowledge_item(repo_id, state);
CREATE INDEX idx_knowledge_item_digest
    ON knowledge_item(workspace_id, repo_id, statement_digest);

-- First-class evidence links. The composite primary key makes re-adding the
-- same source a no-op, which is how equivalent lessons from later PRs merge
-- provenance instead of duplicating rows.
CREATE TABLE knowledge_provenance (
    knowledge_item_id TEXT NOT NULL REFERENCES knowledge_item(id) ON DELETE CASCADE,
    source_kind       TEXT NOT NULL,   -- pr | review | thread | commit | file | symbol | test | timeline | doc | distill-operation | imported | user
    source_ref        TEXT NOT NULL,   -- stable ref, e.g. owner/repo#123 or a GitHub id
    commit_sha        TEXT,
    file_path         TEXT,
    url               TEXT,
    content_digest    TEXT,
    PRIMARY KEY (knowledge_item_id, source_kind, source_ref)
);

-- Structured applicability, one row per tag, so retrieval can filter on
-- module/path/symbol/concept without parsing JSON.
CREATE TABLE knowledge_applicability (
    knowledge_item_id TEXT NOT NULL REFERENCES knowledge_item(id) ON DELETE CASCADE,
    kind              TEXT NOT NULL,   -- module | path | symbol | concept
    value             TEXT NOT NULL,
    PRIMARY KEY (knowledge_item_id, kind, value)
);

CREATE INDEX idx_knowledge_applicability_value
    ON knowledge_applicability(kind, value);

-- Fold the workspace knowledge base into the canonical store. kb_entry rows
-- only ever existed through user action (manual CRUD or an accepted distill
-- run), so they land active with high confidence; body becomes the
-- statement and the repo comes from the workspace's owner/name.
INSERT INTO knowledge_item (
    id, workspace_id, repo_id, subtype, title, statement,
    trigger_json, state, audiences_json, confidence, created_by,
    created_at_ms, updated_at_ms)
SELECT e.id, e.workspace_id, coalesce(w.name, ''), 'doc-note', e.title, e.body,
       '{}', 'active', e.audience_json, 'high', 'user',
       e.created_at_ms, e.updated_at_ms
FROM kb_entry e
LEFT JOIN workspaces w ON w.id = e.workspace_id;

-- Preserve kb_entry provenance: the distill-operation pointer becomes a
-- typed provenance row; any other non-empty provenance map is kept verbatim
-- under 'imported' so nothing is dropped.
INSERT INTO knowledge_provenance (knowledge_item_id, source_kind, source_ref)
SELECT id, 'distill-operation', json_extract(provenance_json, '$.distillOperation')
FROM kb_entry
WHERE json_extract(provenance_json, '$.distillOperation') IS NOT NULL;

INSERT INTO knowledge_provenance (knowledge_item_id, source_kind, source_ref)
SELECT id, 'imported', provenance_json
FROM kb_entry
WHERE provenance_json NOT IN ('{}', '')
  AND json_extract(provenance_json, '$.distillOperation') IS NULL;

DROP TABLE kb_entry;
