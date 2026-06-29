-- Per-task "auto-approve" mode (default off). When on, the task's parked
-- publish gates and in-turn tool prompts are approved automatically — the
-- one exception is the final PR merge (a merge_pr gate), which always stays
-- manually gated. The user opts in explicitly per task on the task brain page.
ALTER TABLE tasks ADD COLUMN auto_approve INTEGER NOT NULL DEFAULT 0;
