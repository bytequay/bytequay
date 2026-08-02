-- Result delivery is now gated until the current execution evidence is
-- terminal, and maintenance retries finalization from the ticket's durable
-- RESULT_PENDING payload. Repair rows created before that guard where delivery
-- already committed but the execution finalizer did not.

UPDATE agent_execution
SET status = (
        SELECT CASE
            WHEN agent_execution.infrastructure_attempt
                    < ticket.infrastructure_attempts THEN 'UNKNOWN'
            WHEN ticket.status = 'SUCCEEDED' THEN 'SUCCEEDED'
            WHEN ticket.status = 'CANCELED' THEN 'CANCELED'
            ELSE 'UNKNOWN'
        END
        FROM dispatch_ticket ticket
        WHERE ticket.id = agent_execution.ticket_id),
    heartbeat_at_ms = (
        SELECT MAX(agent_execution.started_at_ms, ticket.completed_at_ms)
        FROM dispatch_ticket ticket
        WHERE ticket.id = agent_execution.ticket_id),
    finished_at_ms = (
        SELECT MAX(agent_execution.started_at_ms, ticket.completed_at_ms)
        FROM dispatch_ticket ticket
        WHERE ticket.id = agent_execution.ticket_id),
    error_class = COALESCE(error_class, (
        SELECT CASE
            WHEN agent_execution.infrastructure_attempt
                    < ticket.infrastructure_attempts
                THEN 'RECOVERED_SUPERSEDED_ATTEMPT'
            WHEN ticket.status = 'FAILED'
                THEN 'RECOVERED_AMBIGUOUS_TERMINAL_FAILURE'
            ELSE 'RECOVERED_TERMINAL_TICKET'
        END
        FROM dispatch_ticket ticket
        WHERE ticket.id = agent_execution.ticket_id)),
    error_message = CASE
        WHEN error_message IS NULL
            THEN CASE
                WHEN agent_execution.infrastructure_attempt < (
                    SELECT ticket.infrastructure_attempts
                    FROM dispatch_ticket ticket
                    WHERE ticket.id = agent_execution.ticket_id)
                    THEN 'Execution evidence finalization recovered after a later infrastructure attempt completed the ticket; the earlier raw outcome is unknown'
                WHEN (
                    SELECT ticket.status
                    FROM dispatch_ticket ticket
                    WHERE ticket.id = agent_execution.ticket_id) = 'FAILED'
                    THEN 'Execution evidence finalization recovered from a terminal failed ticket; the cleared raw outcome may have been FAILED or INDETERMINATE and is therefore unknown'
                ELSE 'Execution evidence finalization recovered from its terminal delivered ticket; raw result was already accepted by the owner'
            END
        ELSE error_message || char(10)
            || CASE
                WHEN agent_execution.infrastructure_attempt < (
                    SELECT ticket.infrastructure_attempts
                    FROM dispatch_ticket ticket
                    WHERE ticket.id = agent_execution.ticket_id)
                    THEN 'Execution evidence finalization recovered after a later infrastructure attempt completed the ticket; the earlier raw outcome is unknown'
                WHEN (
                    SELECT ticket.status
                    FROM dispatch_ticket ticket
                    WHERE ticket.id = agent_execution.ticket_id) = 'FAILED'
                    THEN 'Execution evidence finalization recovered from a terminal failed ticket; the cleared raw outcome may have been FAILED or INDETERMINATE and is therefore unknown'
                ELSE 'Execution evidence finalization recovered from its terminal delivered ticket; raw result was already accepted by the owner'
            END
    END
WHERE finished_at_ms IS NULL
  AND status IN ('STARTING', 'RUNNING', 'UNKNOWN')
  AND EXISTS (
      SELECT 1
      FROM dispatch_ticket ticket
      WHERE ticket.id = agent_execution.ticket_id
        AND ticket.async_family = 'AGENT_TURN'
        AND ticket.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
        AND ticket.delivery_acceptance IS NOT NULL
        AND ticket.completed_at_ms IS NOT NULL);

-- A cancellation accepted before provider launch has no provider payload to
-- decode. Older USER_WAIT delivery code nevertheless tried to decode one,
-- repeatedly re-arming delivery even after the Stage owner had completed.
-- Capture only the exact, immutable no-launch cancellation shape whose owner
-- is now provably stale. The captured ids keep the two terminal updates stable
-- while the StageTurn itself changes from live to canceled.
CREATE TEMP TABLE v312_stale_no_launch_stage_turn AS
SELECT ticket.id AS ticket_id,
       turn.id AS turn_id,
       MAX(ticket.created_at_ms, ticket.cancel_requested_at_ms) AS recovered_at_ms
FROM dispatch_ticket ticket
JOIN stage_turn turn ON turn.id = ticket.owner_id
JOIN stage owner ON owner.id = turn.stage_id
JOIN tasks task ON task.id = owner.task_id
LEFT JOIN task_current_stage current ON current.task_id = task.id
WHERE task.workflow_version = 'V2'
  AND ticket.operation_kind = 'EXECUTE_STAGE_TURN'
  AND ticket.async_family = 'AGENT_TURN'
  AND ticket.owner_kind = 'STAGE_TURN'
  AND ticket.status = 'RESULT_PENDING'
  AND ticket.pending_result_outcome = 'CANCELED'
  AND ticket.pending_result_payload IS NULL
  AND ticket.cancel_requested_at_ms IS NOT NULL
  AND ticket.infrastructure_attempts = 0
  AND NOT EXISTS (
      SELECT 1 FROM agent_execution execution
      WHERE execution.ticket_id = ticket.id)
  AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
  AND ticket.operation_id = turn.operation_id
  AND ticket.task_id = task.id
  AND ticket.task_epoch = task.epoch
  AND ticket.task_epoch = turn.task_epoch
  AND ticket.stage_id = owner.id
  AND ticket.stage_id = turn.stage_id
  AND ticket.stage_generation = owner.generation
  AND ticket.stage_generation = turn.stage_generation
  AND ticket.attempt = turn.attempt
  AND ticket.expected_code_fingerprint IS turn.expected_code_fingerprint
  AND ticket.expected_head_sha IS turn.expected_head_sha
  AND ticket.expected_base_sha IS turn.expected_base_sha
  AND ticket.pending_result_task_epoch IS ticket.task_epoch
  AND ticket.pending_result_stage_id IS ticket.stage_id
  AND ticket.pending_result_stage_generation IS ticket.stage_generation
  AND ticket.pending_result_operation_id IS ticket.operation_id
  AND ticket.pending_result_attempt IS ticket.attempt
  AND ticket.pending_result_expected_code_fingerprint
      IS ticket.expected_code_fingerprint
  AND ticket.pending_result_expected_head_sha IS ticket.expected_head_sha
  AND ticket.pending_result_expected_base_sha IS ticket.expected_base_sha
  AND (owner.completed_at_ms IS NOT NULL
    OR current.stage_id IS NULL
    OR current.stage_id <> owner.id
    OR current.stage_generation <> owner.generation
    OR task.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED'));

DELETE FROM dispatch_delivery_claim
WHERE ticket_id IN (
    SELECT ticket_id FROM v312_stale_no_launch_stage_turn);

UPDATE stage_turn
SET status = 'CANCELED',
    finished_at_ms = (
        SELECT candidate.recovered_at_ms
        FROM v312_stale_no_launch_stage_turn candidate
        WHERE candidate.turn_id = stage_turn.id),
    error_message = CASE
        WHEN error_message IS NULL
            THEN 'Recovered stale cancellation that was accepted before provider launch'
        ELSE error_message || char(10)
            || 'Recovered stale cancellation that was accepted before provider launch'
    END
WHERE id IN (SELECT turn_id FROM v312_stale_no_launch_stage_turn);

UPDATE dispatch_ticket
SET version = version + 1,
    status = 'CANCELED',
    claim_purpose = NULL,
    claim_owner = NULL,
    capacity_lease_id = NULL,
    claim_expires_at_ms = NULL,
    next_attempt_at_ms = NULL,
    pending_result_outcome = NULL,
    pending_result_payload = NULL,
    pending_result_evidence = NULL,
    pending_result_error = NULL,
    pending_result_task_epoch = NULL,
    pending_result_stage_id = NULL,
    pending_result_stage_generation = NULL,
    pending_result_operation_id = NULL,
    pending_result_attempt = NULL,
    pending_result_expected_code_fingerprint = NULL,
    pending_result_expected_head_sha = NULL,
    pending_result_expected_base_sha = NULL,
    delivery_acceptance = 'SUPERSEDED',
    delivery_evidence = json_object(
        'schema', 'V312_STALE_NO_LAUNCH_CANCELLATION',
        'acceptance', 'SUPERSEDED',
        'ticketId', id,
        'stageTurnId', owner_id),
    completed_at_ms = (
        SELECT candidate.recovered_at_ms
        FROM v312_stale_no_launch_stage_turn candidate
        WHERE candidate.ticket_id = dispatch_ticket.id),
    last_error = CASE
        WHEN last_error IS NULL
            THEN 'Recovered stale cancellation that was accepted before provider launch'
        ELSE last_error || char(10)
            || 'Recovered stale cancellation that was accepted before provider launch'
    END
WHERE id IN (SELECT ticket_id FROM v312_stale_no_launch_stage_turn);

DROP TABLE v312_stale_no_launch_stage_turn;

-- Once an Agent attempt launched, its result cannot become a terminal ticket
-- merely because execution evidence is absent. Require the exact current
-- attempt and its durable raw result. Only a cancellation committed before
-- any launch may omit evidence; this is the database backstop for the
-- delivery-claim eligibility check in SqliteDispatchTicketStore.
CREATE TRIGGER dispatch_ticket_terminal_execution_evidence_v312
BEFORE UPDATE ON dispatch_ticket
WHEN OLD.status = 'RESULT_PENDING'
  AND OLD.async_family = 'AGENT_TURN'
  AND NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
  AND NOT (OLD.pending_result_outcome = 'CANCELED'
    AND OLD.infrastructure_attempts = 0
    AND NOT EXISTS (
        SELECT 1 FROM agent_execution execution
        WHERE execution.ticket_id = OLD.id))
  AND NOT EXISTS (
      SELECT 1
      FROM agent_execution execution
      WHERE execution.ticket_id = OLD.id
        AND execution.infrastructure_attempt = OLD.infrastructure_attempts
        AND execution.finished_at_ms IS NOT NULL
        AND execution.status = CASE OLD.pending_result_outcome
            WHEN 'SUCCEEDED' THEN 'SUCCEEDED'
            WHEN 'FAILED' THEN 'FAILED'
            WHEN 'INDETERMINATE' THEN 'UNKNOWN'
            ELSE 'CANCELED'
        END
        AND execution.raw_result IS NOT NULL
        AND json_valid(execution.raw_result)
        AND json_extract(execution.raw_result, '$.outcome') =
            OLD.pending_result_outcome
        AND json_extract(execution.raw_result, '$.fence.taskEpoch') IS
            OLD.pending_result_task_epoch
        AND json_extract(execution.raw_result, '$.fence.stageId') IS
            OLD.pending_result_stage_id
        AND json_extract(execution.raw_result, '$.fence.stageGeneration') IS
            OLD.pending_result_stage_generation
        AND json_extract(execution.raw_result, '$.fence.operationId') IS
            OLD.pending_result_operation_id
        AND json_extract(execution.raw_result, '$.fence.attempt') IS
            OLD.pending_result_attempt
        AND json_extract(execution.raw_result,
            '$.fence.expectedCodeFingerprint') IS
            OLD.pending_result_expected_code_fingerprint
        AND json_extract(execution.raw_result, '$.fence.expectedHeadSha') IS
            OLD.pending_result_expected_head_sha
        AND json_extract(execution.raw_result, '$.fence.expectedBaseSha') IS
            OLD.pending_result_expected_base_sha
        AND json_extract(execution.raw_result, '$.payloadJson') IS
            OLD.pending_result_payload
        AND json_extract(execution.raw_result, '$.evidenceJson') IS
            OLD.pending_result_evidence
        AND json_extract(execution.raw_result, '$.error') IS
            OLD.pending_result_error)
BEGIN
    SELECT RAISE(ABORT,
        'terminal DispatchTicket requires exact terminal execution evidence');
END;

DROP VIEW task_control_live_work_v256;

CREATE VIEW task_control_live_work_v256 AS
SELECT task.id AS task_id,
       (SELECT COUNT(*) FROM task_turn turn
        WHERE turn.task_id = task.id
          AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
           AS active_task_turn_count,
       (SELECT COUNT(*) FROM stage_turn turn
        JOIN stage owner ON owner.id = turn.stage_id
        WHERE owner.task_id = task.id
          AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
           AS active_stage_turn_count,
       (SELECT COUNT(*)
        FROM review_assignment_turn turn
        JOIN review_assignment assignment ON assignment.id = turn.assignment_id
        JOIN review_round round ON round.id = assignment.round_id
        JOIN review_session session ON session.id = round.session_id
        JOIN pr pull_request ON pull_request.id = session.pr_id
        WHERE pull_request.origin = 'task' AND pull_request.task_id = task.id
          AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
           AS active_review_turn_count,
       (SELECT COUNT(*) FROM dispatch_ticket ticket
        WHERE ticket.task_id = task.id
          AND ticket.status IN (
              'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
              'CLAIMED', 'RUNNING', 'DELIVERING'))
           AS active_dispatch_count,
       (SELECT COUNT(*) FROM agent_execution execution
        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
        WHERE ticket.task_id = task.id
          AND execution.finished_at_ms IS NULL)
           AS active_agent_execution_count
FROM tasks task
WHERE task.workflow_version = 'V2';
