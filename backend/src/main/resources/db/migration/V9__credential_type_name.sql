-- Switch the credential identifier from a single `kind` enum to (type, name).
-- This unlocks per-repo PATs and keeps account / AI credentials in the same
-- table, matching the design at docs/design/settings-redesign.md §2.
--
-- Mapping of legacy kinds:
--   GITHUB_PAT          -> (ACCOUNT, 'github')
--   ANTHROPIC_API_KEY   -> (AI,      'anthropic')
--   OPENAI_API_KEY      -> (AI,      'openai')
--   LOCAL_LLM_ENDPOINT  -> (AI,      'local')

ALTER TABLE credentials ADD COLUMN type TEXT;
ALTER TABLE credentials ADD COLUMN name TEXT;

UPDATE credentials SET type = 'ACCOUNT', name = 'github'    WHERE kind = 'GITHUB_PAT';
UPDATE credentials SET type = 'AI',      name = 'anthropic' WHERE kind = 'ANTHROPIC_API_KEY';
UPDATE credentials SET type = 'AI',      name = 'openai'    WHERE kind = 'OPENAI_API_KEY';
UPDATE credentials SET type = 'AI',      name = 'local'     WHERE kind = 'LOCAL_LLM_ENDPOINT';

-- SQLite can't drop a column or change its UNIQUE constraint in place. Rebuild
-- the table with the new schema (NOT NULL on type/name, no `kind` column,
-- UNIQUE on (type, name) instead of on `kind`).
CREATE TABLE credentials_new (
    id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    type          TEXT    NOT NULL,
    name          TEXT    NOT NULL,
    label         TEXT,
    ciphertext    TEXT    NOT NULL,
    preview       TEXT    NOT NULL,
    notes         TEXT,
    created_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TEXT
);

INSERT INTO credentials_new (id, type, name, label, ciphertext, preview, notes,
                             created_at, updated_at, last_used_at)
SELECT id, type, name, label, ciphertext, preview, notes,
       created_at, updated_at, last_used_at
FROM credentials;

DROP TABLE credentials;
ALTER TABLE credentials_new RENAME TO credentials;

CREATE UNIQUE INDEX uq_credentials_type_name ON credentials(type, name);
