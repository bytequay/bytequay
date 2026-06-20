-- Task-level write mutex. Serialises the two monitor loops' write phases
-- on the shared branch: holds the task_stage id currently allowed to run a
-- write sequence, or null when free. Acquired/released via atomic
-- UPDATE ... WHERE active_write_op_stage_id IS NULL / = :holder (see
-- TaskJpaRepository), never load-then-set, so concurrent polls can't both win.
--
-- No FK clause: SQLite's ALTER TABLE ADD COLUMN cannot carry a REFERENCES
-- clause, and the value is always a valid task_stage id set in code.
ALTER TABLE tasks ADD COLUMN active_write_op_stage_id TEXT;
