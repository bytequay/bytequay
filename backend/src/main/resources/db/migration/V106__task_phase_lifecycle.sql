-- Dev-task PR-collaboration lifecycle (TaskPhase).
--
-- `tasks.status` stays as the agent *runtime* liveness state
-- (PENDING|RUNNING|AWAITING|IDLE|… — "is the subprocess alive / at a
-- permission gate"), which findActiveTaskForThread + orphan recovery
-- depend on. `phase` is the new, orthogonal dev *lifecycle* axis: a
-- task can be phase=ADDRESSING_COMMENTS while status=RUNNING or IDLE.
-- Lifecycle decisions migrate onto `phase`; the runtime axis is
-- untouched. (SQLite has no DROP COLUMN dance here — we add, not
-- rename, so the live runtime never breaks mid-release.)
ALTER TABLE tasks ADD COLUMN phase TEXT NOT NULL DEFAULT 'IMPLEMENTING';
ALTER TABLE tasks ADD COLUMN agenda_json TEXT;
ALTER TABLE tasks ADD COLUMN consecutive_auto_pushes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN linked_pr_ref TEXT;   -- 'owner/repo#5677'; null until linked

-- One ACTIVE task per PR — completed/cancelled tasks drop out of the
-- index so they stay in the audit log without blocking a re-link.
-- SQLite (3.43) supports partial indexes.
CREATE UNIQUE INDEX task_active_pr_idx
    ON tasks(linked_pr_ref)
    WHERE phase != 'COMPLETED' AND linked_pr_ref IS NOT NULL;

-- Backfill phase from the current runtime/parked status. The runtime
-- states all start the lifecycle at IMPLEMENTING; the parked/terminal
-- ones map to their closest lifecycle phase.
UPDATE tasks SET phase = CASE status
    WHEN 'PENDING'         THEN 'IMPLEMENTING'
    WHEN 'RUNNING'         THEN 'IMPLEMENTING'
    WHEN 'AWAITING'        THEN 'IMPLEMENTING'
    WHEN 'IDLE'            THEN 'IMPLEMENTING'
    WHEN 'AWAITING_REVIEW' THEN 'AWAITING_PUSH'
    WHEN 'IN_REVIEW'       THEN 'AWAITING_REMOTE_REVIEW'
    WHEN 'COMPLETED'       THEN 'COMPLETED'
    WHEN 'ERRORED'         THEN 'NEEDS_ATTENTION'
    WHEN 'NEEDS_ATTENTION' THEN 'NEEDS_ATTENTION'
    ELSE                        'IMPLEMENTING'
END;

CREATE INDEX idx_tasks_phase ON tasks(phase);

-- Phase-transition audit log. One row per transition; powers the
-- stepper and the agenda render history. Epoch-ms timestamps + TEXT
-- task ids match the rest of the schema.
CREATE TABLE task_phase_event (
    id                INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    task_id           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    from_phase        TEXT,                       -- null on the first transition
    to_phase          TEXT    NOT NULL,
    transitioned_at_ms INTEGER NOT NULL,
    reason            TEXT,                        -- 'validation_passed', 'human_approved', …
    actor             TEXT                         -- 'agent' | 'human' | 'webhook' | 'scheduler'
);
CREATE INDEX task_phase_event_task_idx ON task_phase_event(task_id, transitioned_at_ms);
