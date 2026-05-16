-- User-defined groupings for tasks: a "Trino refactor" group might
-- pin a small handful of related Claude / Codex sessions so the user
-- can pivot between them. Tasks can live outside any group
-- (group_id IS NULL).
CREATE TABLE task_groups (
    id              TEXT    NOT NULL PRIMARY KEY,
    name            TEXT    NOT NULL,
    -- Single-character (or short) display glyph rendered in the
    -- left-rail badge. Free-form so users can paste an emoji.
    glyph           TEXT    NOT NULL DEFAULT '•',
    -- CSS-compatible color string for the glyph background. Free-form
    -- so we don't have to migrate every time we add a swatch.
    color           TEXT    NOT NULL DEFAULT 'slate',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at_ms   INTEGER NOT NULL,
    updated_at_ms   INTEGER NOT NULL
);

-- Nullable FK on the tasks side so existing rows continue to work.
ALTER TABLE tasks ADD COLUMN group_id TEXT REFERENCES task_groups(id);

CREATE INDEX idx_tasks_group_id ON tasks(group_id);
