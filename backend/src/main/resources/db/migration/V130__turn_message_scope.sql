-- Make a turn/message's place in the thread hierarchy explicit instead of
-- inferring trunk from a null task_id and the stage from a time window.
-- stage_id points at the owning task_stage (null = task- or trunk-level);
-- scope is the validated TRUNK | TASK | STAGE discriminator. Existing rows
-- stay null and fall back to the old time-window attribution on read.
ALTER TABLE thread_turns ADD COLUMN stage_id TEXT;
ALTER TABLE thread_turns ADD COLUMN scope TEXT;
ALTER TABLE thread_messages ADD COLUMN stage_id TEXT;
ALTER TABLE thread_messages ADD COLUMN scope TEXT;
