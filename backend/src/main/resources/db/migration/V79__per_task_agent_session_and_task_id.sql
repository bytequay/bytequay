-- Pass 4 of the workspace/thread/task migration: per-Task agent
-- sessions, and a task_id label on every conversation-ledger row.
--
-- A Thread is now a trunk planning session plus N Task conversations
-- forked from the trunk. Each Task owns its own agent_session_id;
-- threads.agent_session_id becomes the trunk/planning session (NULL
-- until a planning turn happens). thread_messages / thread_turns /
-- thread_turn_events grow a nullable task_id so any row can be sliced
-- to its Task — task_id IS NULL marks a trunk row.
--
-- Backfill notes:
--   - Every legacy thread had exactly one Task (the V72 1:1 invariant),
--     so each existing message / turn / event maps unambiguously to
--     that task. We use tasks.first_msg_seq..last_msg_seq for messages
--     where it covers the row, and fall back to "the latest task for
--     this thread" for rows outside any range (e.g. ones written after
--     ship-and-continue cut a new task whose first/last seq stayed NULL
--     until the next checkpoint).
--   - threads.agent_session_id moves down to the single backfilled
--     task, then thread.agent_session_id is nulled — there was no
--     trunk session in the old model.
--
-- Why drop tasks.first_msg_seq / last_msg_seq:
--   jump-back interleaves a task's rows non-contiguously in the single
--   per-thread seq stream, so a contiguous range column can no longer
--   answer "which task does this row belong to". An explicit per-row
--   task_id is what survives non-linear task switching.

ALTER TABLE tasks ADD COLUMN agent_session_id TEXT;

-- Move the legacy resume id from the thread row down to its single task.
UPDATE tasks
   SET agent_session_id = (
       SELECT t.agent_session_id
         FROM threads t
        WHERE t.id = tasks.thread_id
   )
 WHERE EXISTS (
       SELECT 1
         FROM threads t
        WHERE t.id = tasks.thread_id
          AND t.agent_session_id IS NOT NULL
 );

UPDATE threads SET agent_session_id = NULL;

ALTER TABLE thread_messages     ADD COLUMN task_id TEXT REFERENCES tasks(id);
ALTER TABLE thread_turns        ADD COLUMN task_id TEXT REFERENCES tasks(id);
ALTER TABLE thread_turn_events  ADD COLUMN task_id TEXT REFERENCES tasks(id);

-- Messages: prefer the seq-range match; fall back to the thread's
-- latest task for rows written outside any recorded range.
UPDATE thread_messages
   SET task_id = COALESCE(
       (SELECT tk.id FROM tasks tk
         WHERE tk.thread_id = thread_messages.thread_id
           AND tk.first_msg_seq IS NOT NULL
           AND thread_messages.seq BETWEEN tk.first_msg_seq AND tk.last_msg_seq
         LIMIT 1),
       (SELECT tk.id FROM tasks tk
         WHERE tk.thread_id = thread_messages.thread_id
         ORDER BY tk.seq DESC
         LIMIT 1));

-- Turns and events: no per-row seq to slice by, so map every row to
-- the thread's latest task (1:1 invariant — there's only one task).
UPDATE thread_turns
   SET task_id = (SELECT tk.id FROM tasks tk
                   WHERE tk.thread_id = thread_turns.thread_id
                   ORDER BY tk.seq DESC
                   LIMIT 1);

UPDATE thread_turn_events
   SET task_id = (SELECT tk.id FROM tasks tk
                   WHERE tk.thread_id = thread_turn_events.thread_id
                   ORDER BY tk.seq DESC
                   LIMIT 1);

CREATE INDEX idx_thread_messages_task_seq      ON thread_messages(task_id, seq);
CREATE INDEX idx_thread_turns_task_id          ON thread_turns(task_id);
CREATE INDEX idx_thread_turn_events_task_id    ON thread_turn_events(task_id);

ALTER TABLE tasks DROP COLUMN first_msg_seq;
ALTER TABLE tasks DROP COLUMN last_msg_seq;
