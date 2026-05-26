-- Persist the per-skill enable/disable toggle the Skills surface
-- shows in the row header. SQLite has no native boolean — INTEGER
-- 0/1 matches the existing pattern (workspaces.is_scratch,
-- credentials.is_default). Existing rows default to enabled so the
-- review path's behaviour doesn't change at migration time.

ALTER TABLE review_skill ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1;
