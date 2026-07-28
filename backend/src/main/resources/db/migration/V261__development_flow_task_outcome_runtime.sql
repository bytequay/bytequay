-- A TaskOutcome creates its user-visible Trunk completion marker in the same
-- transaction.  Optional Brain enrichment is one ordinary typed ThreadTurn;
-- the retired TaskTurn-specific summary protocol admits no new work.

DROP TRIGGER task_outcome_create_trunk_inbox;
DROP TRIGGER trunk_outcome_inbox_insert;
DROP TRIGGER trunk_outcome_inbox_transition;
DROP TRIGGER task_outcome_summary_update;

ALTER TABLE task_outcome ADD COLUMN summary_thread_turn_id TEXT
    REFERENCES thread_turn(id);
CREATE UNIQUE INDEX idx_task_outcome_summary_thread_turn
    ON task_outcome(summary_thread_turn_id)
    WHERE summary_thread_turn_id IS NOT NULL;

ALTER TABLE trunk_outcome_inbox ADD COLUMN fallback_summary_text TEXT;
ALTER TABLE trunk_outcome_inbox ADD COLUMN returned_trunk_version INTEGER;

-- Drain work admitted by the retired TaskTurn protocol.  A process restart
-- cannot safely resume its old callback after the exact update guard below is
-- installed, so settle its infrastructure records and let the durable
-- fallback qualify for a new typed ThreadTurn.
DROP TRIGGER task_outcome_summary_operation_non_success;

DELETE FROM dispatch_delivery_claim
WHERE ticket_id IN (
    SELECT dispatch_ticket_id FROM task_outcome_summary_operation);

UPDATE capacity_lease
SET released_at_ms = acquired_at_ms,
    release_reason = 'Task outcome summary protocol retired'
WHERE released_at_ms IS NULL
  AND ticket_id IN (
      SELECT dispatch_ticket_id FROM task_outcome_summary_operation);

UPDATE dispatch_ticket
SET version = version + 1,
    status = CASE WHEN status = 'RESULT_PENDING' THEN
        CASE pending_result_outcome
          WHEN 'SUCCEEDED' THEN 'SUCCEEDED'
          WHEN 'CANCELED' THEN 'CANCELED'
          ELSE 'FAILED'
        END
      ELSE 'CANCELED' END,
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
    delivery_evidence = 'Task outcome summary protocol retired',
    completed_at_ms = created_at_ms
WHERE id IN (
    SELECT dispatch_ticket_id FROM task_outcome_summary_operation)
  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED');

UPDATE task_turn
SET status = 'CANCELED',
    finished_at_ms = requested_at_ms,
    error_message = 'Task outcome summary protocol retired'
WHERE id IN (
    SELECT task_turn_id FROM task_outcome_summary_operation)
  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING');

UPDATE task_outcome_summary_operation
SET status = 'CANCELED',
    error_message = 'Task outcome summary protocol retired',
    completed_at_ms = requested_at_ms
WHERE status = 'REQUESTED';

-- Preserve every pre-upgrade marker while replacing its opaque digest label
-- with the same deterministic, friendly wording used for new outcomes.
UPDATE trunk_outcome_inbox AS inbox
SET fallback_summary_text = (
    SELECT CASE outcome.terminal_reason
        WHEN 'CANCELED' THEN CASE
            WHEN binding.remote_pr_number IS NULL
                THEN 'Canceled ' || COALESCE(
                    NULLIF(trim(task.name), ''),
                    NULLIF(trim(task.branch_name), ''),
                    'Task ' || task.seq)
                    || ' before opening a pull request.'
            ELSE 'Canceled ' || COALESCE(
                    NULLIF(trim(task.name), ''),
                    NULLIF(trim(task.branch_name), ''),
                    'Task ' || task.seq)
                    || ' (PR #' || binding.remote_pr_number
                    || ' remains open).'
            END
        WHEN 'REMOTE_CLOSED' THEN
            'Shipped ' || COALESCE(
                NULLIF(trim(task.name), ''),
                NULLIF(trim(task.branch_name), ''),
                'Task ' || task.seq)
                || CASE WHEN binding.remote_pr_number IS NULL
                    THEN ' — closed without merging.'
                    ELSE ' (PR #' || binding.remote_pr_number
                        || ') — closed without merging.' END
        ELSE 'Shipped ' || COALESCE(
                NULLIF(trim(task.name), ''),
                NULLIF(trim(task.branch_name), ''),
                'Task ' || task.seq)
                || CASE WHEN binding.remote_pr_number IS NULL
                    THEN ' — completed with no pull request opened.'
                    ELSE ' (PR #' || binding.remote_pr_number
                        || ') — merged.' END
        END
    FROM task_outcome outcome
    JOIN tasks task ON task.id = outcome.task_id
    LEFT JOIN remote_pr_binding binding
      ON binding.id = outcome.remote_pr_binding_id
    WHERE outcome.id = inbox.task_outcome_id),
    returned_trunk_version = CASE WHEN status = 'DELIVERED' THEN (
        SELECT aggregate_version FROM threads WHERE id = inbox.trunk_id)
        ELSE NULL END;

CREATE TRIGGER trunk_outcome_inbox_insert
BEFORE INSERT ON trunk_outcome_inbox
WHEN NEW.id <> 'TRUNK_OUTCOME:' || NEW.task_outcome_id
  OR NEW.delivery_key <> NEW.id
  OR NEW.status <> 'PENDING'
  OR NEW.delivered_at_ms IS NOT NULL OR NEW.delivery_evidence IS NOT NULL
  OR NEW.returned_trunk_version IS NOT NULL
  OR NEW.fallback_summary_text IS NULL
  OR NOT EXISTS (
      SELECT 1
      FROM task_outcome outcome
      JOIN tasks task ON task.id = outcome.task_id
      LEFT JOIN remote_pr_binding binding
        ON binding.id = outcome.remote_pr_binding_id
      WHERE outcome.id = NEW.task_outcome_id
        AND outcome.task_id = NEW.task_id
        AND outcome.trunk_id = NEW.trunk_id
        AND NEW.fallback_summary_marker =
            'FALLBACK:' || outcome.cleanup_summary_digest
        AND NEW.fallback_summary_text = CASE outcome.terminal_reason
          WHEN 'CANCELED' THEN CASE
            WHEN binding.remote_pr_number IS NULL
              THEN 'Canceled ' || COALESCE(
                NULLIF(trim(task.name), ''),
                NULLIF(trim(task.branch_name), ''),
                'Task ' || task.seq)
                || ' before opening a pull request.'
            ELSE 'Canceled ' || COALESCE(
                NULLIF(trim(task.name), ''),
                NULLIF(trim(task.branch_name), ''),
                'Task ' || task.seq)
                || ' (PR #' || binding.remote_pr_number
                || ' remains open).'
            END
          WHEN 'REMOTE_CLOSED' THEN
            'Shipped ' || COALESCE(
              NULLIF(trim(task.name), ''),
              NULLIF(trim(task.branch_name), ''),
              'Task ' || task.seq)
              || CASE WHEN binding.remote_pr_number IS NULL
                THEN ' — closed without merging.'
                ELSE ' (PR #' || binding.remote_pr_number
                  || ') — closed without merging.' END
          ELSE 'Shipped ' || COALESCE(
              NULLIF(trim(task.name), ''),
              NULLIF(trim(task.branch_name), ''),
              'Task ' || task.seq)
              || CASE WHEN binding.remote_pr_number IS NULL
                THEN ' — completed with no pull request opened.'
                ELSE ' (PR #' || binding.remote_pr_number
                  || ') — merged.' END
          END)
BEGIN SELECT RAISE(ABORT, 'Trunk outcome inbox marker is not exact'); END;

CREATE TRIGGER task_outcome_create_trunk_inbox
AFTER INSERT ON task_outcome
BEGIN
    INSERT INTO trunk_outcome_inbox(
        id, trunk_id, task_id, task_outcome_id, delivery_key,
        fallback_summary_marker, fallback_summary_text, status,
        created_at_ms, delivered_at_ms, delivery_evidence,
        returned_trunk_version)
    SELECT 'TRUNK_OUTCOME:' || NEW.id, NEW.trunk_id, NEW.task_id, NEW.id,
        'TRUNK_OUTCOME:' || NEW.id,
        'FALLBACK:' || NEW.cleanup_summary_digest,
        CASE NEW.terminal_reason
          WHEN 'CANCELED' THEN CASE
            WHEN binding.remote_pr_number IS NULL
              THEN 'Canceled ' || COALESCE(
                NULLIF(trim(task.name), ''),
                NULLIF(trim(task.branch_name), ''),
                'Task ' || task.seq)
                || ' before opening a pull request.'
            ELSE 'Canceled ' || COALESCE(
                NULLIF(trim(task.name), ''),
                NULLIF(trim(task.branch_name), ''),
                'Task ' || task.seq)
                || ' (PR #' || binding.remote_pr_number
                || ' remains open).'
            END
          WHEN 'REMOTE_CLOSED' THEN
            'Shipped ' || COALESCE(
              NULLIF(trim(task.name), ''),
              NULLIF(trim(task.branch_name), ''),
              'Task ' || task.seq)
              || CASE WHEN binding.remote_pr_number IS NULL
                THEN ' — closed without merging.'
                ELSE ' (PR #' || binding.remote_pr_number
                  || ') — closed without merging.' END
          ELSE 'Shipped ' || COALESCE(
              NULLIF(trim(task.name), ''),
              NULLIF(trim(task.branch_name), ''),
              'Task ' || task.seq)
              || CASE WHEN binding.remote_pr_number IS NULL
                THEN ' — completed with no pull request opened.'
                ELSE ' (PR #' || binding.remote_pr_number
                  || ') — merged.' END
          END,
        'PENDING', NEW.recorded_at_ms, NULL, NULL, NULL
    FROM tasks task
    LEFT JOIN remote_pr_binding binding
      ON binding.id = NEW.remote_pr_binding_id
    WHERE task.id = NEW.task_id;
END;

-- Delivery is a Trunk-owned synchronous command.  The inbox row itself is
-- the idempotent receipt and binds the exact same-state Trunk transition.
CREATE TRIGGER trunk_outcome_inbox_transition
BEFORE UPDATE ON trunk_outcome_inbox
WHEN NOT (OLD.status = 'PENDING' AND NEW.status = 'DELIVERED'
      AND NEW.id = OLD.id AND NEW.trunk_id = OLD.trunk_id
      AND NEW.task_id = OLD.task_id
      AND NEW.task_outcome_id = OLD.task_outcome_id
      AND NEW.delivery_key = OLD.delivery_key
      AND NEW.fallback_summary_marker = OLD.fallback_summary_marker
      AND NEW.fallback_summary_text = OLD.fallback_summary_text
      AND NEW.created_at_ms = OLD.created_at_ms
      AND NEW.delivered_at_ms >= OLD.created_at_ms
      AND NEW.delivery_evidence IS NOT NULL
      AND NEW.returned_trunk_version > 0
      AND EXISTS (
          SELECT 1
          FROM task_outcome outcome
          JOIN tasks task ON task.id = outcome.task_id
          JOIN threads trunk ON trunk.id = outcome.trunk_id
          JOIN trunk_transition transition
            ON transition.trunk_id = outcome.trunk_id
          WHERE outcome.id = OLD.task_outcome_id
            AND outcome.task_id = OLD.task_id
            AND outcome.trunk_id = OLD.trunk_id
            AND task.lifecycle_state IN (
              'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            AND transition.command_id = OLD.delivery_key
            AND transition.cause = 'ACCEPT_TASK_OUTCOME'
            AND transition.from_state = transition.to_state
            AND transition.to_state = trunk.lifecycle_state
            AND transition.aggregate_version = NEW.returned_trunk_version
            AND trunk.aggregate_version = NEW.returned_trunk_version))
BEGIN SELECT RAISE(ABORT, 'Trunk outcome inbox transition is invalid'); END;

-- A terminal V2 Task cannot commit without its durable friendly marker.
CREATE TRIGGER task_terminal_completion_marker
BEFORE UPDATE OF lifecycle_state ON tasks
WHEN OLD.workflow_version = 'V2'
  AND OLD.lifecycle_state = 'CLEANING'
  AND NEW.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
  AND NOT EXISTS (
      SELECT 1
      FROM task_outcome outcome
      JOIN trunk_outcome_inbox inbox ON inbox.task_outcome_id = outcome.id
      WHERE outcome.task_id = OLD.id
        AND outcome.task_epoch = OLD.epoch
        AND outcome.terminal_reason = NEW.lifecycle_state
        AND inbox.task_id = OLD.id
        AND inbox.trunk_id = outcome.trunk_id
        AND inbox.fallback_summary_text IS NOT NULL
        AND length(trim(inbox.fallback_summary_text)) > 0)
BEGIN SELECT RAISE(ABORT, 'Terminal Task lacks its completion marker'); END;

-- Bind optional Brain enrichment to one ordinary typed ThreadTurn.  Success
-- later replaces only the marker text; failure/cancel leaves fallback intact.
CREATE TRIGGER task_outcome_summary_update
BEFORE UPDATE ON task_outcome
WHEN NOT (
    (OLD.summary_state = 'FALLBACK'
      AND NEW.summary_state = OLD.summary_state
      AND NEW.summary_text = OLD.summary_text
      AND NEW.summary_digest = OLD.summary_digest
      AND NEW.summary_operation_id IS OLD.summary_operation_id
      AND NEW.follow_up_proposals_json = OLD.follow_up_proposals_json
      AND NEW.backlog_items_json = OLD.backlog_items_json
      AND NEW.summary_updated_at_ms IS OLD.summary_updated_at_ms
      AND OLD.summary_thread_turn_id IS NULL
      AND NEW.summary_thread_turn_id IS NOT NULL
      AND EXISTS (
          SELECT 1
          FROM trunk_outcome_inbox inbox
          JOIN thread_turn turn ON turn.id = NEW.summary_thread_turn_id
          JOIN trunk_thread_turn_request_receipt request
            ON request.turn_id = turn.id
          JOIN dispatch_ticket ticket
            ON ticket.id = request.dispatch_ticket_id
          WHERE inbox.task_outcome_id = OLD.id
            AND inbox.status = 'DELIVERED'
            AND turn.trunk_id = OLD.trunk_id
            AND turn.purpose = 'TASK_COMPLETION_SUMMARY'
            AND turn.planning_operation_id IS NULL
            AND turn.expected_base_sha IS NULL
            AND request.trunk_id = OLD.trunk_id
            AND ticket.operation_id = turn.operation_id
            AND ticket.operation_kind = 'EXECUTE_THREAD_TURN'
            AND ticket.async_family = 'AGENT_TURN'
            AND ticket.owner_kind = 'THREAD_TURN'
            AND ticket.owner_id = turn.id
            AND ticket.callback_route = 'THREAD_TURN_RESULT'
            AND ticket.trunk_control = 1
            AND ticket.exclusive_task = 0
            AND ticket.writer_required = 0
            AND ticket.task_id IS NULL AND ticket.stage_id IS NULL))
  OR
    (OLD.summary_state = 'FALLBACK'
      AND NEW.summary_state = 'BRAIN_GENERATED'
      AND OLD.summary_thread_turn_id IS NOT NULL
      AND NEW.summary_thread_turn_id = OLD.summary_thread_turn_id
      AND NEW.summary_operation_id IS NOT NULL
      AND NEW.summary_updated_at_ms IS NOT NULL
      AND NEW.follow_up_proposals_json = '[]'
      AND NEW.backlog_items_json = '[]'
      AND EXISTS (
          SELECT 1
          FROM thread_turn turn
          JOIN thread_message message
            ON message.turn_id = turn.id AND message.seq = 2
          JOIN trunk_thread_turn_result_receipt result
            ON result.turn_id = turn.id
          JOIN trunk_thread_turn_request_receipt request
            ON request.turn_id = turn.id
          JOIN dispatch_ticket ticket
            ON ticket.id = request.dispatch_ticket_id
          WHERE turn.id = OLD.summary_thread_turn_id
            AND turn.trunk_id = OLD.trunk_id
            AND turn.purpose = 'TASK_COMPLETION_SUMMARY'
            AND turn.status = 'SUCCEEDED'
            AND turn.finished_at_ms IS NOT NULL
            AND turn.operation_id = NEW.summary_operation_id
            AND message.role = 'assistant'
            AND message.body = NEW.summary_text
            AND result.operation_id = turn.operation_id
            AND result.acceptance = 'ACCEPTED'
            AND result.terminal_status = 'SUCCEEDED'
            AND result.assistant_message_id = message.id
            AND result.raw_result_digest = NEW.summary_digest
            AND ticket.operation_id = turn.operation_id
            AND ticket.status = 'SUCCEEDED'
            AND ticket.delivery_acceptance = 'ACCEPTED')))
BEGIN SELECT RAISE(ABORT, 'TaskOutcome summary update lacks its exact typed Turn'); END;

CREATE TRIGGER retired_task_outcome_task_turn
BEFORE INSERT ON task_turn
WHEN NEW.purpose = 'TASK_COMPLETION_SUMMARY'
  AND EXISTS (
      SELECT 1 FROM tasks task
      WHERE task.id = NEW.task_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'V2 completion summary requires a typed ThreadTurn'); END;

CREATE TRIGGER retired_task_outcome_summary_operation
BEFORE INSERT ON task_outcome_summary_operation
BEGIN SELECT RAISE(ABORT, 'TaskTurn outcome-summary protocol is retired'); END;

CREATE TRIGGER retired_task_outcome_summary_dispatch
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'GENERATE_TASK_OUTCOME_SUMMARY'
  OR NEW.callback_route = 'TASK_OUTCOME_SUMMARY_RESULT'
BEGIN SELECT RAISE(ABORT, 'TaskTurn outcome-summary dispatch is retired'); END;
