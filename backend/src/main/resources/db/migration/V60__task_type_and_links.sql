-- Task create page (docs/mockups/design/tasks/task-create.png) adds
-- three persisted fields:
--
--   * task_type — DEVELOP / FIX today, more values likely later.
--     Kept as TEXT (not an enum) so we don't need a migration the
--     next time a value is added.
--
--   * linked_pr_number — GitHub PR number the task is associated
--     with. Scoped to the task's own repo (resolved via the task's
--     working_dir → tracked_repos lookup), so we don't denormalise
--     the owner/repo here.
--
--   * linked_issue_number — same pattern for an issue.
--
-- All three are nullable so existing rows survive the migration.
-- Existing rows get task_type=DEVELOP since most ad-hoc agent runs
-- so far have been feature work; the user can rename via the
-- detail page once a rename surface lands.

ALTER TABLE tasks ADD COLUMN task_type TEXT NOT NULL DEFAULT 'DEVELOP';
ALTER TABLE tasks ADD COLUMN linked_pr_number INTEGER;
ALTER TABLE tasks ADD COLUMN linked_issue_number INTEGER;
