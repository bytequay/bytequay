-- One admitted Agent execution may launch a resume process and, only when the
-- provider rejects that session before doing work, one fresh fallback process.
-- Keep the parent PID as the current recovery pointer while retaining every
-- process launch as immutable evidence.
CREATE TABLE agent_execution_process_attempt (
    execution_id   TEXT    NOT NULL
        REFERENCES agent_execution(id) ON DELETE CASCADE,
    process_attempt INTEGER NOT NULL CHECK (process_attempt > 0),
    process_pid    INTEGER NOT NULL CHECK (process_pid > 0),
    log_ref        TEXT,
    PRIMARY KEY (execution_id, process_attempt),
    CHECK (log_ref IS NULL OR length(trim(log_ref)) > 0)
);

CREATE TRIGGER agent_execution_process_attempt_sequence
BEFORE INSERT ON agent_execution_process_attempt
WHEN NEW.process_attempt <> COALESCE((
    SELECT MAX(process_attempt)
    FROM agent_execution_process_attempt
    WHERE execution_id = NEW.execution_id), 0) + 1
BEGIN
    SELECT RAISE(ABORT, 'Agent execution process attempts must be sequential');
END;

INSERT INTO agent_execution_process_attempt(
    execution_id, process_attempt, process_pid, log_ref)
SELECT id, 1, process_pid, log_ref
FROM agent_execution
WHERE process_pid IS NOT NULL;

CREATE TRIGGER agent_execution_process_attempt_immutable
BEFORE UPDATE ON agent_execution_process_attempt
BEGIN
    SELECT RAISE(ABORT, 'Agent execution process attempt is immutable');
END;
