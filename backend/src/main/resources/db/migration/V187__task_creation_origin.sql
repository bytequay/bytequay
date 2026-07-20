-- Immutable creator provenance for user, agent, and ByteQuay-created tasks.
-- Existing rows predate attribution and are conservatively user-authored.
ALTER TABLE tasks ADD COLUMN origin TEXT NOT NULL DEFAULT 'user';

UPDATE tasks
SET origin = 'issue-monitor'
WHERE task_type = 'BYTEQUAY_ISSUE_TRIAGE';

CREATE TRIGGER task_origin_immutable
BEFORE UPDATE OF origin ON tasks
WHEN NEW.origin <> OLD.origin
BEGIN
    SELECT RAISE(ABORT, 'task origin is immutable');
END;
