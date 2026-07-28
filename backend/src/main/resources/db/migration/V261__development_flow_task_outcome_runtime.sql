-- A TaskOutcome creates its user-visible Trunk completion marker in the same
-- transaction. Optional Brain enrichment keeps the exact TaskTurn protocol
-- introduced in V234: TaskOutcome id + Task id/epoch + TaskTurn + ticket.

DROP TRIGGER task_outcome_create_trunk_inbox;
DROP TRIGGER trunk_outcome_inbox_insert;
DROP TRIGGER trunk_outcome_inbox_transition;

ALTER TABLE trunk_outcome_inbox ADD COLUMN fallback_summary_text TEXT;
ALTER TABLE trunk_outcome_inbox ADD COLUMN returned_trunk_version INTEGER;
ALTER TABLE trunk_outcome_inbox ADD COLUMN legacy_delivery_evidence TEXT;
ALTER TABLE trunk_outcome_inbox ADD COLUMN legacy_delivered_at_ms INTEGER;

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
    legacy_delivery_evidence = CASE WHEN status = 'DELIVERED'
        THEN delivery_evidence ELSE NULL END,
    legacy_delivered_at_ms = CASE WHEN status = 'DELIVERED'
        THEN delivered_at_ms ELSE NULL END,
    returned_trunk_version = CASE WHEN status = 'DELIVERED' THEN (
        SELECT transition.aggregate_version
        FROM trunk_transition transition
        WHERE transition.trunk_id = inbox.trunk_id
          AND transition.command_id = inbox.delivery_key
          AND transition.cause = 'ACCEPT_TASK_OUTCOME'
        ORDER BY transition.aggregate_version DESC
        LIMIT 1)
        ELSE NULL END;

-- Old DELIVERED rows without their exact historical transition are not
-- assigned the Trunk's current version. Preserve their legacy evidence, then
-- return them to PENDING so the normal idempotent Trunk command can deliver.
UPDATE trunk_outcome_inbox
SET status = 'PENDING', delivered_at_ms = NULL, delivery_evidence = NULL
WHERE status = 'DELIVERED' AND returned_trunk_version IS NULL;

CREATE TRIGGER trunk_outcome_inbox_insert
BEFORE INSERT ON trunk_outcome_inbox
WHEN NEW.id <> 'TRUNK_OUTCOME:' || NEW.task_outcome_id
  OR NEW.delivery_key <> NEW.id
  OR NEW.status <> 'PENDING'
  OR NEW.delivered_at_ms IS NOT NULL OR NEW.delivery_evidence IS NOT NULL
  OR NEW.returned_trunk_version IS NOT NULL
  OR NEW.legacy_delivery_evidence IS NOT NULL
  OR NEW.legacy_delivered_at_ms IS NOT NULL
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
      AND NEW.legacy_delivery_evidence IS OLD.legacy_delivery_evidence
      AND NEW.legacy_delivered_at_ms IS OLD.legacy_delivered_at_ms
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

-- V234 admitted only API summary Turns. Keep the same exact TaskTurn/ticket
-- protocol while allowing the frozen Task Brain transport to select CLI or
-- API. Both are read-only and non-exclusive, and the REVIEW lane makes that
-- capacity contract explicit. Normalize any in-flight V234 ticket and lease
-- before restoring their identity guards.
DROP TRIGGER dispatch_ticket_identity_immutable;
DROP TRIGGER dispatch_ticket_version_monotonic;
DROP TRIGGER capacity_lease_identity_immutable;

UPDATE capacity_lease
SET lane_mask = lane_mask | 8
WHERE lane_mask IN (1, 2)
  AND ticket_id IN (
      SELECT summary.dispatch_ticket_id
      FROM task_outcome_summary_operation summary
      WHERE summary.status = 'REQUESTED');

UPDATE dispatch_ticket
SET lane_mask = lane_mask | 8
WHERE lane_mask IN (1, 2)
  AND id IN (
      SELECT summary.dispatch_ticket_id
      FROM task_outcome_summary_operation summary
      WHERE summary.status = 'REQUESTED');

CREATE TRIGGER dispatch_ticket_version_monotonic
BEFORE UPDATE ON dispatch_ticket
WHEN NEW.version <> OLD.version + 1
BEGIN SELECT RAISE(ABORT, 'DispatchTicket version must advance'); END;

CREATE TRIGGER dispatch_ticket_identity_immutable
BEFORE UPDATE OF operation_id, operation_kind, owner_kind, owner_id,
        callback_route, async_family, lane_mask, trunk_control, exclusive_task,
        writer_required, workspace_id,
        trunk_id, task_id, task_epoch, stage_id, stage_generation, attempt,
        expected_code_fingerprint, expected_head_sha, expected_base_sha,
        created_at_ms ON dispatch_ticket
WHEN NEW.operation_id IS NOT OLD.operation_id
  OR NEW.operation_kind IS NOT OLD.operation_kind
  OR NEW.owner_kind IS NOT OLD.owner_kind
  OR NEW.owner_id IS NOT OLD.owner_id
  OR NEW.callback_route IS NOT OLD.callback_route
  OR NEW.async_family IS NOT OLD.async_family
  OR NEW.lane_mask IS NOT OLD.lane_mask
  OR NEW.trunk_control IS NOT OLD.trunk_control
  OR NEW.exclusive_task IS NOT OLD.exclusive_task
  OR NEW.writer_required IS NOT OLD.writer_required
  OR NEW.workspace_id IS NOT OLD.workspace_id
  OR NEW.trunk_id IS NOT OLD.trunk_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_id IS NOT OLD.stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.attempt IS NOT OLD.attempt
  OR NEW.expected_code_fingerprint IS NOT OLD.expected_code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'DispatchTicket identity is immutable'); END;

CREATE TRIGGER capacity_lease_identity_immutable
BEFORE UPDATE OF ticket_id, operation_id, workflow_source, trunk_control,
        lane_mask, exclusive_task, writer_required, workspace_id, trunk_id, task_id,
        task_epoch, holder, fencing_token, acquired_at_ms ON capacity_lease
WHEN NEW.ticket_id IS NOT OLD.ticket_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.workflow_source IS NOT OLD.workflow_source
  OR NEW.lane_mask IS NOT OLD.lane_mask
  OR NEW.trunk_control IS NOT OLD.trunk_control
  OR NEW.exclusive_task IS NOT OLD.exclusive_task
  OR NEW.writer_required IS NOT OLD.writer_required
  OR NEW.workspace_id IS NOT OLD.workspace_id
  OR NEW.trunk_id IS NOT OLD.trunk_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.holder IS NOT OLD.holder
  OR NEW.fencing_token IS NOT OLD.fencing_token
  OR NEW.acquired_at_ms IS NOT OLD.acquired_at_ms
BEGIN SELECT RAISE(ABORT, 'capacity lease identity is immutable'); END;

DROP TRIGGER task_outcome_summary_operation_insert;
CREATE TRIGGER task_outcome_summary_operation_insert
BEFORE INSERT ON task_outcome_summary_operation
WHEN NEW.status <> 'REQUESTED'
  OR NEW.semantic_attempt <> COALESCE((
      SELECT MAX(previous.semantic_attempt) + 1
      FROM task_outcome_summary_operation previous
      WHERE previous.task_outcome_id = NEW.task_outcome_id), 1)
  OR NOT EXISTS (
      SELECT 1 FROM task_outcome outcome
      JOIN tasks task ON task.id = outcome.task_id
      JOIN task_turn turn ON turn.id = NEW.task_turn_id
      JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
      JOIN outbox wake ON wake.aggregate_id = ticket.id
      WHERE outcome.id = NEW.task_outcome_id
        AND outcome.task_id = NEW.task_id
        AND outcome.task_epoch = NEW.task_epoch
        AND outcome.summary_state = 'FALLBACK'
        AND task.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
        AND task.epoch = NEW.task_epoch
        AND turn.task_id = NEW.task_id
        AND turn.task_epoch = NEW.task_epoch
        AND turn.purpose = 'TASK_COMPLETION_SUMMARY'
        AND turn.status = 'REQUESTED'
        AND turn.operation_id = NEW.operation_id
        AND turn.attempt = NEW.semantic_attempt
        AND turn.trigger_stage_id IS NULL
        AND ticket.operation_id = NEW.operation_id
        AND ticket.operation_kind = 'GENERATE_TASK_OUTCOME_SUMMARY'
        AND ticket.async_family = 'AGENT_TURN'
        AND ticket.owner_kind = 'TASK_TURN'
        AND ticket.owner_id = turn.id
        AND ticket.callback_route = 'TASK_OUTCOME_SUMMARY_RESULT'
        AND ticket.lane_mask IN (9, 10)
        AND ticket.trunk_control = 0
        AND ticket.exclusive_task = 0
        AND ticket.writer_required = 0
        AND ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND ticket.stage_id IS NULL
        AND ticket.stage_generation IS NULL
        AND ticket.attempt = NEW.semantic_attempt
        AND ticket.status = 'REQUESTED'
        AND wake.id = 'V2_DISPATCH_TICKET_REQUESTED:' || ticket.id
        AND wake.dedup_key = wake.id
        AND wake.aggregate_kind = 'DISPATCH_TICKET'
        AND wake.topic = 'V2_DISPATCH_TICKET_REQUESTED'
        AND wake.payload = ticket.id
        AND wake.status = 'PENDING'
        AND wake.attempts = 0
        AND wake.available_at_ms = ticket.created_at_ms
        AND wake.created_at_ms = ticket.created_at_ms
        AND wake.claim_owner IS NULL AND wake.lease_until_ms IS NULL
        AND wake.delivered_at_ms IS NULL AND wake.last_error IS NULL)
BEGIN SELECT RAISE(ABORT, 'Task outcome summary lacks exact async dispatch'); END;

-- The result consumer stores a SHA-256 digest of the frozen typed payload.
-- Keep the exact V234 owner/ticket/fence checks while allowing that digest to
-- differ from the payload JSON persisted on the RESULT_PENDING ticket.
DROP TRIGGER task_outcome_summary_operation_success;
CREATE TRIGGER task_outcome_summary_operation_success
BEFORE UPDATE OF status ON task_outcome_summary_operation
WHEN NEW.status = 'SUCCEEDED'
  AND (NEW.completed_at_ms IS NULL OR NEW.summary_text IS NULL
    OR NEW.summary_digest IS NULL OR length(NEW.summary_digest) <> 64
    OR NEW.error_message IS NOT NULL
    OR NOT EXISTS (
        SELECT 1 FROM task_turn turn
        JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
        WHERE turn.id = NEW.task_turn_id
          AND turn.operation_id = NEW.operation_id
          AND turn.status = 'SUCCEEDED'
          AND turn.finished_at_ms IS NOT NULL
          AND ticket.operation_id = NEW.operation_id
          AND ticket.status = 'RESULT_PENDING'
          AND ticket.pending_result_outcome = 'SUCCEEDED'
          AND ticket.pending_result_payload IS NOT NULL
          AND ticket.pending_result_evidence IS NOT NULL
          AND ticket.pending_result_task_epoch = NEW.task_epoch
          AND ticket.pending_result_stage_id IS NULL
          AND ticket.pending_result_stage_generation IS NULL
          AND ticket.pending_result_operation_id = NEW.operation_id
          AND ticket.pending_result_attempt = NEW.semantic_attempt))
BEGIN SELECT RAISE(ABORT, 'Task outcome summary success lacks exact result'); END;
