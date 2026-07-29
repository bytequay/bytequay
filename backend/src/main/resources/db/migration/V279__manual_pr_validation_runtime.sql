-- A user-requested local test run is still Task-owned V2 work. Persist the
-- exact code subject and dispatch it through the shared validation lane
-- instead of running a process on the servlet thread.
CREATE TABLE manual_pr_validation_operation (
    id                        TEXT    NOT NULL PRIMARY KEY,
    command_id                TEXT    NOT NULL,
    pr_id                     TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    task_id                   TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                INTEGER NOT NULL CHECK (task_epoch > 0),
    worktree_path             TEXT    NOT NULL,
    code_fingerprint          TEXT    NOT NULL,
    expected_head_sha         TEXT    NOT NULL,
    expected_base_sha         TEXT    NOT NULL,
    status                    TEXT    NOT NULL
        CHECK (status IN ('REQUESTED', 'COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    result_json               TEXT,
    error_message             TEXT,
    requested_at_ms           INTEGER NOT NULL,
    completed_at_ms           INTEGER,
    UNIQUE (task_id, command_id),
    CHECK (length(command_id) > 0 AND length(worktree_path) > 0),
    CHECK ((status = 'REQUESTED') = (completed_at_ms IS NULL)),
    CHECK ((status = 'REQUESTED') = (result_json IS NULL))
);

CREATE TRIGGER manual_pr_validation_operation_insert
BEFORE INSERT ON manual_pr_validation_operation
WHEN NOT EXISTS (
    SELECT 1
    FROM pr
    JOIN tasks task ON task.id = pr.task_id
    JOIN task_code_identity identity ON identity.task_id = task.id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE pr.id = NEW.pr_id
      AND task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND identity.worktree_path = NEW.worktree_path
      AND code.code_fingerprint = NEW.code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND NEW.status = 'REQUESTED')
BEGIN
    SELECT RAISE(ABORT, 'Manual PR validation requires an exact active V2 Task subject');
END;

CREATE TRIGGER manual_pr_validation_operation_identity_immutable
BEFORE UPDATE OF id, command_id, pr_id, task_id, task_epoch, worktree_path,
    code_fingerprint, expected_head_sha, expected_base_sha, requested_at_ms
ON manual_pr_validation_operation
BEGIN
    SELECT RAISE(ABORT, 'Manual PR validation identity is immutable');
END;

CREATE TRIGGER manual_pr_validation_operation_transition
BEFORE UPDATE OF status ON manual_pr_validation_operation
WHEN OLD.status <> 'REQUESTED' OR NEW.status = 'REQUESTED'
BEGIN
    SELECT RAISE(ABORT, 'Manual PR validation has one terminal transition');
END;

CREATE TRIGGER manual_pr_validation_operation_terminal_immutable
BEFORE UPDATE ON manual_pr_validation_operation
WHEN OLD.status <> 'REQUESTED'
BEGIN
    SELECT RAISE(ABORT, 'Terminal Manual PR validation is immutable');
END;

-- The test process is deliberately projection-free. Accepting its exact
-- durable result projects the PR check and timeline row in the same SQLite
-- transaction, with stable ids, so restart delivery cannot append duplicates.
CREATE TRIGGER manual_pr_validation_result_projection
AFTER UPDATE OF status ON manual_pr_validation_operation
WHEN NEW.status = 'COMPLETED'
  AND json_type(NEW.result_json, '$.testRun') = 'object'
BEGIN
    INSERT INTO pr_check(
        id, pr_id, kind, name, status, duration_ms,
        started_at_ms, finished_at_ms, run_id)
    VALUES (
        'manual-pr-validation-check:' || NEW.id,
        NEW.pr_id,
        'local',
        json_extract(NEW.result_json, '$.testRun.ecosystem') || ' test',
        CASE json_extract(NEW.result_json, '$.testRun.passed')
            WHEN 1 THEN 'passed' ELSE 'failed' END,
        json_extract(NEW.result_json, '$.testRun.durationMs'),
        json_extract(NEW.result_json, '$.testRun.startedAtMs'),
        json_extract(NEW.result_json, '$.testRun.completedAtMs'),
        NEW.id);

    INSERT INTO pr_timeline_event(
        id, pr_id, event_type, actor, is_local_only,
        stripped_on_push_at_ms, created_at_ms, payload_json, remote_event_id)
    VALUES (
        'manual-pr-validation-event:' || NEW.id,
        NEW.pr_id,
        'ci',
        'claude-code',
        1,
        NULL,
        json_extract(NEW.result_json, '$.testRun.completedAtMs'),
        json_object(
            'kind', 'local',
            'name', json_extract(
                NEW.result_json, '$.testRun.ecosystem') || ' test',
            'status', CASE json_extract(
                NEW.result_json, '$.testRun.passed')
                WHEN 1 THEN 'passed' ELSE 'failed' END,
            'durationMs', json_extract(
                NEW.result_json, '$.testRun.durationMs')),
        NULL);
END;

CREATE TRIGGER dispatch_ticket_manual_pr_validation_v279
BEFORE INSERT ON dispatch_ticket
WHEN NEW.callback_route = 'MANUAL_PR_VALIDATION_RESULT'
  OR NEW.operation_kind = 'VALIDATE_PR_MANUALLY'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM manual_pr_validation_operation operation
        WHERE operation.id = NEW.operation_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.code_fingerprint = NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND operation.status = 'REQUESTED'
          AND NEW.operation_kind = 'VALIDATE_PR_MANUALLY'
          AND NEW.async_family = 'VALIDATION'
          AND NEW.owner_kind = 'TASK'
          AND NEW.owner_id = NEW.task_id
          AND NEW.callback_route = 'MANUAL_PR_VALIDATION_RESULT'
          AND NEW.lane_mask = 4
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.stage_id IS NULL
          AND NEW.stage_generation IS NULL
          AND NEW.attempt = 1
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Manual PR validation DispatchTicket is not exact') END;
END;
