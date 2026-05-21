-- Task tier for the conversation-index/checkpoint hierarchy. Adds a
-- nullable task_id to thread_checkpoints so per-segment checkpoints
-- can be scoped to one Task's slice of the thread instead of the
-- whole conversation. Overall (is_overall=1) rows stay thread-level
-- and leave task_id null; legacy per-segment rows also stay null
-- until the scheduler is updated to attribute new segments to the
-- task that produced them.
--
-- See docs/mockups/workspace-thread-task-design.md "Three-level
-- memory hierarchy" — this is the missing Task tier that compacts
-- upward into the Thread Overall and (eventually) into workspace
-- memory.

ALTER TABLE thread_checkpoints ADD COLUMN task_id TEXT REFERENCES tasks(id) ON DELETE CASCADE;

CREATE INDEX idx_thread_checkpoints_task ON thread_checkpoints(task_id);
