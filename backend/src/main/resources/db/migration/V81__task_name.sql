-- Per-task user-renamable label. The auto-derived label (humanised
-- branch name) is still the source of truth when this column is
-- null, so legacy rows stay readable without a backfill — the
-- frontend's taskLabel() helper picks {@code name} when set and
-- falls back to humanising {@code branch_name} otherwise.
ALTER TABLE tasks ADD COLUMN name TEXT;
