-- Many-to-many task ↔ group membership.
--
-- Replaces the single nullable `tasks.group_id` column with a join
-- table so one task can live in several groups (a "PR review" group
-- and a "Trino refactor" group, say) without forcing the user to pick
-- one. Two new invariants land alongside the schema change and are
-- enforced in TaskService:
--
--   1. A group must always have at least one member. Creation requires
--      `initialTaskIds.size() >= 1`; removing the last member is
--      rejected (caller must `deleteGroup` instead).
--   2. A group caps at 4 members so the tile grid in
--      `docs/mockups/design/tasks/tasks-group.png` always fits in the
--      auto-by-count layout (1 / 2 / 3 / 4 tiles).
--
-- The legacy `tasks.group_id` column stays in the schema — SQLite
-- can't `DROP COLUMN` on a column that's the source of a `REFERENCES`
-- clause without rebuilding the whole table, and we'd rather not
-- thread a rebuild through `task_messages` / `task_files` etc. The
-- application stops reading and writing it after this migration;
-- a future migration can do the table rebuild if storage matters.

CREATE TABLE task_group_members (
    task_id     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    group_id    TEXT    NOT NULL REFERENCES task_groups(id) ON DELETE CASCADE,
    added_at_ms INTEGER NOT NULL,
    PRIMARY KEY (task_id, group_id)
);

CREATE INDEX idx_task_group_members_group_id ON task_group_members(group_id);
CREATE INDEX idx_task_group_members_task_id  ON task_group_members(task_id);

-- Backfill from the legacy 1:N column. Idempotent via the composite
-- primary key — running this on a partially-migrated DB just
-- ignores rows that already exist.
INSERT INTO task_group_members (task_id, group_id, added_at_ms)
SELECT id, group_id, updated_at_ms
FROM tasks
WHERE group_id IS NOT NULL;

-- Apply the new non-empty invariant retroactively: any group that
-- exists today with zero members in the join table is dropped here
-- so the service layer doesn't have to special-case legacy data.
DELETE FROM task_groups
WHERE id NOT IN (SELECT DISTINCT group_id FROM task_group_members);
