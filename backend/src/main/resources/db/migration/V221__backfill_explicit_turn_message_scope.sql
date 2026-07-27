-- Runtime routing no longer infers hierarchy from nullable ids. Preserve
-- pre-V130 data once, at migration time, so every row read by current code
-- carries an explicit scope discriminator.
UPDATE thread_turns
SET scope = CASE
    WHEN stage_id IS NOT NULL THEN 'STAGE'
    WHEN task_id IS NOT NULL THEN 'TASK'
    ELSE 'TRUNK'
END
WHERE scope IS NULL;

UPDATE thread_messages
SET scope = CASE
    WHEN stage_id IS NOT NULL THEN 'STAGE'
    WHEN task_id IS NOT NULL THEN 'TASK'
    ELSE 'TRUNK'
END
WHERE scope IS NULL;
