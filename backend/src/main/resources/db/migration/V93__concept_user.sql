-- User-defined concepts (the Saved Views surface). Each row is one
-- USER-scoped ConceptSpec; the registry re-loads them at startup and
-- after every mutation so the agent's list_terms / lookup_term sees
-- them alongside the workspace and APP-scoped seeds.
--
-- `name` is the canonical concept name and the PRIMARY KEY because
-- USER-scoped specs collapse to one row per name. `aka_json` and
-- `criteria_json` are stringly-typed JSON blobs for flexibility — v1
-- only reads aka, but criteria stays as a column so the predicate DSL
-- can land without another migration.
CREATE TABLE concept_user (
    name TEXT NOT NULL PRIMARY KEY,
    kind TEXT NOT NULL,
    definition TEXT NOT NULL,
    aka_json TEXT NOT NULL DEFAULT '[]',
    criteria_json TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);
