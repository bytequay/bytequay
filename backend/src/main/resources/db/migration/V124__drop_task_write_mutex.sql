-- Drop the inert task-level write mutex. The mutex was never wired to a
-- caller — per-thread serialization in the Agent Scheduler already provides
-- the write safety it was meant to give — so the column stayed null and its
-- MUTEX_ACQUIRED / MUTEX_SKIPPED audit events were never written in
-- production. Remove the column and defensively clear any stray mutex events
-- so dropping the enum values can't trip the event-type row mapper on a dev
-- database that recorded some during the feature's short inert life.
DELETE FROM task_stage_event WHERE event_type IN ('MUTEX_ACQUIRED', 'MUTEX_SKIPPED');

ALTER TABLE tasks DROP COLUMN active_write_op_stage_id;
