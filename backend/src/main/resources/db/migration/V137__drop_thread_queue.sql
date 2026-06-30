-- The trunk task queue / successor model is gone: a thread may run many
-- tasks concurrently, and the only way to create one is create_task at the
-- trunk (which cuts immediately). The per-thread queue_json accumulator
-- (V110) is therefore obsolete and dropped. parallel_slots (V110) and the
-- task opening_prompt column stay — both outlive the queue.
ALTER TABLE threads DROP COLUMN queue_json;
