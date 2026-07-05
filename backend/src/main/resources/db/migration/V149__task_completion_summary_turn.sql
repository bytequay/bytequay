-- Tracks a pending "summarize this task for the trunk" brain turn, enqueued
-- when the task reaches COMPLETED (BrainServiceImpl.onTaskCompleted). Set
-- while the turn is in flight; cleared once TaskCompletionAnnouncer picks up
-- its TaskTurnFinishedEvent (or the stale-completion sweep gives up and
-- writes the mechanical fallback instead). Null the rest of the time.
ALTER TABLE tasks ADD COLUMN pending_completion_summary_turn_id TEXT;
