-- Iteration summaries land on the chronological scheduler-event log as
-- well as the iteration row, so the brain feed can merge them with stage
-- events. is_summary flags those rows; stage_id ties a summary to the
-- stage whose iteration it describes.
--
-- is_summary is 0/1 (SQLite has no native boolean). The composite index
-- backs the brain feed's "summary rows for a task, chronological" lookup.
ALTER TABLE thread_turn_events ADD COLUMN is_summary INTEGER NOT NULL DEFAULT 0;
ALTER TABLE thread_turn_events ADD COLUMN stage_id TEXT;

CREATE INDEX idx_thread_turn_events_summary
    ON thread_turn_events(task_id, is_summary, created_at_ms);
