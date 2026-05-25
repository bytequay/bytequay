-- Default-per-provider for the credentials vault. Each
-- (type, name) group resolves an unnamed scope to exactly one row
-- — the one with is_default = 1. Naming an instance still wins, so
-- callers that want "the work key" can still ask for it by name.
--
-- The service layer owns the single-default invariant (clearing the
-- previous default in the same transaction); the partial unique
-- index here is a belt-and-suspenders guard so a stray UPDATE that
-- forgets the clear can't silently install two defaults.

ALTER TABLE credentials ADD COLUMN is_default INTEGER NOT NULL DEFAULT 0;

-- Backfill: every (type, name) group with at least one row gets a
-- default — the earliest-created instance, since that's what the
-- legacy "no instance picked" path already resolved to. Existing
-- installs come up resolving to the same row they had been.
UPDATE credentials
SET is_default = 1
WHERE id IN (
    SELECT MIN(id) FROM credentials
    GROUP BY type, name
);

-- One default per (type, name). SQLite supports partial indexes, so
-- only "is_default = 1" rows count toward uniqueness — non-defaults
-- are free to coexist.
CREATE UNIQUE INDEX uq_credentials_type_name_default
    ON credentials(type, name)
    WHERE is_default = 1;
