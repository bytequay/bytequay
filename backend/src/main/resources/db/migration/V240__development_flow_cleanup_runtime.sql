-- Explicit owner decisions for a determinate CleanupStep failure. The runner
-- never spins while a Task is waiting for a retry or optional waiver.

CREATE TABLE cleanup_step_retry_request (
    id                  TEXT    NOT NULL PRIMARY KEY,
    cleanup_step_id     TEXT    NOT NULL REFERENCES cleanup_step(id) ON DELETE CASCADE,
    cleanup_operation_id TEXT   NOT NULL REFERENCES cleanup_operation(id) ON DELETE CASCADE,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    failed_attempt      INTEGER NOT NULL CHECK (failed_attempt > 0),
    requested_by        TEXT    NOT NULL,
    reason              TEXT    NOT NULL,
    status              TEXT    NOT NULL CHECK (status IN ('PENDING', 'CONSUMED')),
    requested_at_ms     INTEGER NOT NULL,
    consumed_at_ms      INTEGER,
    UNIQUE (cleanup_step_id, failed_attempt),
    CHECK ((status = 'CONSUMED') = (consumed_at_ms IS NOT NULL))
);

CREATE UNIQUE INDEX idx_cleanup_step_one_pending_retry
    ON cleanup_step_retry_request(cleanup_step_id)
    WHERE status = 'PENDING';

CREATE TRIGGER cleanup_step_retry_request_insert
BEFORE INSERT ON cleanup_step_retry_request
WHEN NEW.status <> 'PENDING' OR NEW.consumed_at_ms IS NOT NULL
  OR NOT EXISTS (
      SELECT 1 FROM cleanup_step step
      JOIN cleanup_operation operation ON operation.id = step.cleanup_operation_id
      JOIN task_blocker blocker
        ON blocker.owner_id = operation.id
       AND blocker.subject_revision = CAST(step.ordinal AS TEXT)
      WHERE step.id = NEW.cleanup_step_id
        AND step.cleanup_operation_id = NEW.cleanup_operation_id
        AND step.task_id = NEW.task_id
        AND step.status = 'FAILED'
        AND step.failure_kind = 'DETERMINATE'
        AND step.attempt_count = NEW.failed_attempt
        AND step.execute_attempt_count < step.attempt_limit
        AND operation.status = 'ACTIVE'
        AND blocker.task_id = step.task_id
        AND blocker.stage_id = step.cleanup_stage_id
        AND blocker.owner_kind = 'OPERATION'
        AND blocker.status = 'OPEN')
BEGIN SELECT RAISE(ABORT, 'Cleanup retry lacks an exact retryable failure'); END;

CREATE TRIGGER cleanup_step_retry_request_update
BEFORE UPDATE ON cleanup_step_retry_request
WHEN NOT (OLD.status = 'PENDING' AND NEW.status = 'CONSUMED'
      AND NEW.id = OLD.id
      AND NEW.cleanup_step_id = OLD.cleanup_step_id
      AND NEW.cleanup_operation_id = OLD.cleanup_operation_id
      AND NEW.task_id = OLD.task_id
      AND NEW.failed_attempt = OLD.failed_attempt
      AND NEW.requested_by = OLD.requested_by
      AND NEW.reason = OLD.reason
      AND NEW.requested_at_ms = OLD.requested_at_ms
      AND NEW.consumed_at_ms >= OLD.requested_at_ms)
BEGIN SELECT RAISE(ABORT, 'Cleanup retry transition is invalid'); END;

CREATE TRIGGER cleanup_step_retry_request_delete
BEFORE DELETE ON cleanup_step_retry_request
BEGIN SELECT RAISE(ABORT, 'Cleanup retry evidence cannot be deleted'); END;

CREATE TRIGGER cleanup_step_determinate_retry_requires_request
BEFORE UPDATE OF status ON cleanup_step
WHEN OLD.status = 'FAILED' AND OLD.failure_kind = 'DETERMINATE'
  AND NEW.status = 'CLAIMED'
  AND NOT EXISTS (
      SELECT 1 FROM cleanup_step_retry_request retry
      WHERE retry.cleanup_step_id = OLD.id
        AND retry.cleanup_operation_id = OLD.cleanup_operation_id
        AND retry.task_id = OLD.task_id
        AND retry.failed_attempt = OLD.attempt_count
        AND retry.status = 'PENDING')
BEGIN SELECT RAISE(ABORT, 'Determinate Cleanup retry lacks owner authorization'); END;

CREATE TRIGGER cleanup_step_determinate_retry_consumed
AFTER UPDATE OF status ON cleanup_step
WHEN OLD.status = 'FAILED' AND OLD.failure_kind = 'DETERMINATE'
  AND NEW.status = 'CLAIMED'
BEGIN
    UPDATE cleanup_step_retry_request
       SET status = 'CONSUMED', consumed_at_ms = NEW.claimed_at_ms
     WHERE cleanup_step_id = NEW.id
       AND cleanup_operation_id = NEW.cleanup_operation_id
       AND task_id = NEW.task_id
       AND failed_attempt = OLD.attempt_count
       AND status = 'PENDING';
END;

CREATE TRIGGER cleanup_step_waiver_excludes_retry
BEFORE INSERT ON cleanup_step_waiver
WHEN EXISTS (
    SELECT 1 FROM cleanup_step_retry_request retry
    WHERE retry.cleanup_step_id = NEW.cleanup_step_id
      AND retry.status = 'PENDING')
BEGIN SELECT RAISE(ABORT, 'Cleanup waiver conflicts with a pending retry'); END;

-- V2StageStore already has durable public command names for these two moves.
-- Require the transition row before the aggregate row changes, while accepting
-- those established names as the exact Cleanup operation proof.
DROP TRIGGER cleanup_stage_checkpoint_update;
CREATE TRIGGER cleanup_stage_checkpoint_update
BEFORE UPDATE OF checkpoint, completed_at_ms, end_reason ON stage
WHEN OLD.kind = 'CLEANUP'
  AND NOT ((OLD.checkpoint = 'WAITING_QUIESCENCE'
            AND NEW.checkpoint = 'CLEANING'
            AND NEW.completed_at_ms IS NULL AND NEW.end_reason IS NULL
            AND EXISTS (
                SELECT 1 FROM cleanup_operation operation
                WHERE operation.cleanup_stage_id = OLD.id
                  AND operation.status = 'ACTIVE')
            AND EXISTS (
                SELECT 1 FROM stage_transition transition
                WHERE transition.stage_id = OLD.id
                  AND transition.generation = OLD.generation
                  AND transition.from_checkpoint = OLD.checkpoint
                  AND transition.to_checkpoint = NEW.checkpoint
                  AND transition.stage_version = NEW.version
                  AND transition.cause = 'ACCEPT_CLEANUP_QUIESCENCE'))
        OR (OLD.checkpoint = 'CLEANING'
            AND NEW.checkpoint = 'COMPLETED'
            AND NEW.completed_at_ms IS NOT NULL
            AND NEW.end_reason = 'NORMAL'
            AND EXISTS (
                SELECT 1 FROM cleanup_operation operation
                WHERE operation.cleanup_stage_id = OLD.id
                  AND operation.status = 'COMPLETED')
            AND EXISTS (
                SELECT 1 FROM stage_transition transition
                WHERE transition.stage_id = OLD.id
                  AND transition.generation = OLD.generation
                  AND transition.from_checkpoint = OLD.checkpoint
                  AND transition.to_checkpoint = NEW.checkpoint
                  AND transition.stage_version = NEW.version
                  AND transition.cause = 'ACCEPT_CLEANUP_COMPLETE')))
BEGIN SELECT RAISE(ABORT, 'Cleanup Stage checkpoint lacks its exact operation transition'); END;
