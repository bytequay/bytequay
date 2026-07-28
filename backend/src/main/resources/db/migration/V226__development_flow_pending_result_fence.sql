-- Persist the result handler's raw fence separately from the ticket envelope.
-- A mismatched fence is stale evidence, not invalid storage: the domain owner
-- must receive it verbatim and decide ACCEPTED versus SUPERSEDED.

ALTER TABLE dispatch_ticket ADD COLUMN pending_result_task_epoch INTEGER;
ALTER TABLE dispatch_ticket ADD COLUMN pending_result_stage_id TEXT;
ALTER TABLE dispatch_ticket ADD COLUMN pending_result_stage_generation INTEGER;
ALTER TABLE dispatch_ticket ADD COLUMN pending_result_operation_id TEXT;
ALTER TABLE dispatch_ticket ADD COLUMN pending_result_attempt INTEGER;
ALTER TABLE dispatch_ticket ADD COLUMN pending_result_expected_code_fingerprint TEXT;
ALTER TABLE dispatch_ticket ADD COLUMN pending_result_expected_head_sha TEXT;
ALTER TABLE dispatch_ticket ADD COLUMN pending_result_expected_base_sha TEXT;

-- V2 routing was inert before this migration. Backfill any development/test
-- pending rows from their envelope so an upgrade never creates partial shape.
-- This is a shape migration, not a ticket transition: preserve the version and
-- any exact delivery claim while the new columns are populated.
DROP TRIGGER dispatch_ticket_version_monotonic;
DROP TRIGGER dispatch_ticket_delivery_claim_guard;

UPDATE dispatch_ticket
SET pending_result_task_epoch = task_epoch,
    pending_result_stage_id = stage_id,
    pending_result_stage_generation = stage_generation,
    pending_result_operation_id = operation_id,
    pending_result_attempt = attempt,
    pending_result_expected_code_fingerprint = expected_code_fingerprint,
    pending_result_expected_head_sha = expected_head_sha,
    pending_result_expected_base_sha = expected_base_sha
WHERE pending_result_outcome IS NOT NULL;

CREATE TRIGGER dispatch_ticket_version_monotonic
BEFORE UPDATE ON dispatch_ticket
WHEN NEW.version <> OLD.version + 1
BEGIN SELECT RAISE(ABORT, 'DispatchTicket version must advance'); END;

CREATE TRIGGER dispatch_ticket_delivery_claim_guard
BEFORE UPDATE ON dispatch_ticket
WHEN OLD.status = 'RESULT_PENDING'
  AND EXISTS (
      SELECT 1 FROM dispatch_delivery_claim c WHERE c.ticket_id = OLD.id)
BEGIN
    SELECT RAISE(ABORT, 'release the exact delivery claim before changing its ticket');
END;

CREATE TRIGGER dispatch_ticket_pending_fence_insert
BEFORE INSERT ON dispatch_ticket
WHEN (NEW.pending_result_outcome IS NULL AND (
        NEW.pending_result_task_epoch IS NOT NULL
        OR NEW.pending_result_stage_id IS NOT NULL
        OR NEW.pending_result_stage_generation IS NOT NULL
        OR NEW.pending_result_operation_id IS NOT NULL
        OR NEW.pending_result_attempt IS NOT NULL
        OR NEW.pending_result_expected_code_fingerprint IS NOT NULL
        OR NEW.pending_result_expected_head_sha IS NOT NULL
        OR NEW.pending_result_expected_base_sha IS NOT NULL))
  OR (NEW.pending_result_outcome IS NOT NULL AND (
        NEW.pending_result_operation_id IS NULL
        OR length(NEW.pending_result_operation_id) = 0
        OR NEW.pending_result_attempt IS NULL
        OR NEW.pending_result_attempt <= 0
        OR NEW.pending_result_task_epoch IS NOT NULL
            AND NEW.pending_result_task_epoch <= 0
        OR (NEW.pending_result_stage_id IS NULL)
            <> (NEW.pending_result_stage_generation IS NULL)
        OR NEW.pending_result_stage_generation IS NOT NULL
            AND NEW.pending_result_stage_generation <= 0))
BEGIN SELECT RAISE(ABORT, 'pending DispatchResult requires its complete raw fence'); END;

CREATE TRIGGER dispatch_ticket_pending_fence_update
BEFORE UPDATE ON dispatch_ticket
WHEN (NEW.pending_result_outcome IS NULL AND (
        NEW.pending_result_task_epoch IS NOT NULL
        OR NEW.pending_result_stage_id IS NOT NULL
        OR NEW.pending_result_stage_generation IS NOT NULL
        OR NEW.pending_result_operation_id IS NOT NULL
        OR NEW.pending_result_attempt IS NOT NULL
        OR NEW.pending_result_expected_code_fingerprint IS NOT NULL
        OR NEW.pending_result_expected_head_sha IS NOT NULL
        OR NEW.pending_result_expected_base_sha IS NOT NULL))
  OR (NEW.pending_result_outcome IS NOT NULL AND (
        NEW.pending_result_operation_id IS NULL
        OR length(NEW.pending_result_operation_id) = 0
        OR NEW.pending_result_attempt IS NULL
        OR NEW.pending_result_attempt <= 0
        OR NEW.pending_result_task_epoch IS NOT NULL
            AND NEW.pending_result_task_epoch <= 0
        OR (NEW.pending_result_stage_id IS NULL)
            <> (NEW.pending_result_stage_generation IS NULL)
        OR NEW.pending_result_stage_generation IS NOT NULL
            AND NEW.pending_result_stage_generation <= 0))
BEGIN SELECT RAISE(ABORT, 'pending DispatchResult requires its complete raw fence'); END;

CREATE TRIGGER dispatch_ticket_pending_fence_immutable
BEFORE UPDATE OF pending_result_outcome, pending_result_payload,
        pending_result_evidence, pending_result_error,
        pending_result_task_epoch, pending_result_stage_id,
        pending_result_stage_generation, pending_result_operation_id,
        pending_result_attempt, pending_result_expected_code_fingerprint,
        pending_result_expected_head_sha, pending_result_expected_base_sha
ON dispatch_ticket
WHEN OLD.pending_result_outcome IS NOT NULL
  AND NEW.pending_result_outcome IS NOT NULL
  AND (NEW.pending_result_outcome IS NOT OLD.pending_result_outcome
    OR NEW.pending_result_payload IS NOT OLD.pending_result_payload
    OR NEW.pending_result_evidence IS NOT OLD.pending_result_evidence
    OR NEW.pending_result_error IS NOT OLD.pending_result_error
    OR NEW.pending_result_task_epoch IS NOT OLD.pending_result_task_epoch
    OR NEW.pending_result_stage_id IS NOT OLD.pending_result_stage_id
    OR NEW.pending_result_stage_generation IS NOT OLD.pending_result_stage_generation
    OR NEW.pending_result_operation_id IS NOT OLD.pending_result_operation_id
    OR NEW.pending_result_attempt IS NOT OLD.pending_result_attempt
    OR NEW.pending_result_expected_code_fingerprint
        IS NOT OLD.pending_result_expected_code_fingerprint
    OR NEW.pending_result_expected_head_sha IS NOT OLD.pending_result_expected_head_sha
    OR NEW.pending_result_expected_base_sha IS NOT OLD.pending_result_expected_base_sha)
BEGIN SELECT RAISE(ABORT, 'pending DispatchResult is immutable'); END;

CREATE TRIGGER dispatch_ticket_pending_result_terminal_clear
BEFORE UPDATE ON dispatch_ticket
WHEN OLD.pending_result_outcome IS NOT NULL
  AND NEW.pending_result_outcome IS NULL
  AND NOT (
      (OLD.pending_result_outcome = 'SUCCEEDED' AND NEW.status = 'SUCCEEDED')
      OR (OLD.pending_result_outcome IN ('FAILED', 'INDETERMINATE')
          AND NEW.status = 'FAILED')
      OR (OLD.pending_result_outcome = 'CANCELED' AND NEW.status = 'CANCELED'))
BEGIN SELECT RAISE(ABORT, 'terminal status must match the pending DispatchResult'); END;

CREATE TRIGGER dispatch_ticket_terminal_immutable
BEFORE UPDATE ON dispatch_ticket
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
BEGIN SELECT RAISE(ABORT, 'terminal DispatchTicket is immutable'); END;
