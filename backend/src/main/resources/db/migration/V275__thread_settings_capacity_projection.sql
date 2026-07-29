-- Preserve explicit legacy Trunk concurrency when capacity policy moves to
-- thread_settings. A nonpositive override never represented a valid limit;
-- normalize it to inheritance before applying the one-time legacy fallback.
UPDATE thread_settings
SET max_running_tasks = NULL
WHERE max_running_tasks IS NOT NULL
  AND max_running_tasks < 1;

-- Fill only inherited/missing settings. Existing positive overrides win.
-- Keep threads.parallel_slots intact while legacy execution still reads it.
INSERT INTO thread_settings(thread_id, max_running_tasks, updated_at_ms)
SELECT id, parallel_slots, updated_at_ms
FROM threads
WHERE parallel_slots > 1
ON CONFLICT(thread_id) DO UPDATE SET
    max_running_tasks = excluded.max_running_tasks
WHERE thread_settings.max_running_tasks IS NULL;

CREATE TRIGGER thread_settings_capacity_insert_v275
BEFORE INSERT ON thread_settings
WHEN NEW.max_running_tasks IS NOT NULL
 AND NEW.max_running_tasks < 1
BEGIN SELECT RAISE(ABORT,
    'thread_settings.max_running_tasks must be positive'); END;

CREATE TRIGGER thread_settings_capacity_update_v275
BEFORE UPDATE OF max_running_tasks ON thread_settings
WHEN NEW.max_running_tasks IS NOT NULL
 AND NEW.max_running_tasks < 1
BEGIN SELECT RAISE(ABORT,
    'thread_settings.max_running_tasks must be positive'); END;
