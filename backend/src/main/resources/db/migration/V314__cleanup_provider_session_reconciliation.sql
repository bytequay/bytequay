-- Cleanup attempt failures are durable evidence, not successful reconciliation
-- proofs.  Also, the Cleanup executor itself is not a provider session.

DROP TRIGGER IF EXISTS cleanup_step_attempt_result_insert;

CREATE TRIGGER cleanup_step_attempt_result_insert
BEFORE INSERT ON cleanup_step_attempt_result
WHEN NOT EXISTS (
    SELECT 1 FROM cleanup_step step
    JOIN cleanup_operation operation ON operation.id = step.cleanup_operation_id
    JOIN cleanup_stage cleanup ON cleanup.stage_id = step.cleanup_stage_id
    JOIN tasks task ON task.id = step.task_id
    WHERE step.id = NEW.cleanup_step_id
      AND step.cleanup_operation_id = NEW.cleanup_operation_id
      AND step.task_id = NEW.task_id
      AND step.task_epoch = NEW.task_epoch
      AND step.ordinal = NEW.ordinal
      AND step.status = 'CLAIMED'
      AND step.attempt_count = NEW.attempt
      AND step.claim_mode = NEW.claim_mode
      AND operation.status = 'ACTIVE'
      AND task.lifecycle_state = 'CLEANING'
      AND task.epoch = NEW.task_epoch
      AND (NEW.outcome <> 'SUCCEEDED' OR step.ordinal <> 1 OR (
          task.lifecycle_state = 'CLEANING'
          AND cleanup.stage_id = step.cleanup_stage_id))
      AND (NEW.outcome <> 'SUCCEEDED' OR step.ordinal <> 2 OR (
          NOT EXISTS (
              SELECT 1 FROM task_turn turn
              WHERE turn.task_id = NEW.task_id
                AND turn.task_epoch = NEW.task_epoch
                AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
          AND NOT EXISTS (
              SELECT 1 FROM stage_turn turn
              JOIN stage owner ON owner.id = turn.stage_id
              WHERE owner.task_id = NEW.task_id
                AND turn.task_epoch = NEW.task_epoch
                AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
          AND NOT EXISTS (
              SELECT 1 FROM dispatch_ticket ticket
              WHERE ticket.task_id = NEW.task_id
                AND ticket.task_epoch = NEW.task_epoch
                AND ticket.async_family <> 'CLEANUP'
                AND ticket.status IN ('REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                    'RESULT_PENDING', 'CLAIMED', 'RUNNING', 'DELIVERING'))
          AND NOT EXISTS (
              SELECT 1 FROM ci_repair_episode episode
              WHERE episode.task_id = NEW.task_id
                AND episode.task_epoch = NEW.task_epoch
                AND episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED'))
          AND NOT EXISTS (
              SELECT 1 FROM branch_sync_episode episode
              WHERE episode.task_id = NEW.task_id
                AND episode.task_epoch = NEW.task_epoch
                AND episode.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED'))
          AND NOT EXISTS (
              SELECT 1 FROM remote_mark_ready_operation remote_operation
              WHERE remote_operation.task_id = NEW.task_id
                AND remote_operation.task_epoch = NEW.task_epoch
                AND remote_operation.status NOT IN ('SUCCEEDED', 'CANCELED'))
          AND NOT EXISTS (
              SELECT 1 FROM remote_merge_operation merge_operation
              WHERE merge_operation.task_id = NEW.task_id
                AND merge_operation.task_epoch = NEW.task_epoch
                AND merge_operation.status NOT IN (
                    'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED'))))
      AND (NEW.outcome <> 'SUCCEEDED' OR step.ordinal <> 3 OR NOT EXISTS (
          SELECT 1 FROM agent_execution execution
          JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
          WHERE ticket.task_id = NEW.task_id
            AND ticket.async_family = 'AGENT_TURN'
            AND execution.finished_at_ms IS NULL
            AND execution.status IN ('STARTING', 'RUNNING', 'UNKNOWN')))
      AND (NEW.outcome <> 'SUCCEEDED' OR step.ordinal <> 4 OR (
          NOT EXISTS (
              SELECT 1 FROM validation_operation validation
              WHERE validation.task_id = NEW.task_id
                AND validation.status IN ('REQUESTED', 'DISPATCHED'))
          AND NOT EXISTS (
              SELECT 1 FROM validation_pass validation
              WHERE validation.task_id = NEW.task_id
                AND validation.ended_at_ms IS NULL)))
      AND (NEW.outcome <> 'SUCCEEDED' OR step.ordinal <> 5 OR (
          NOT EXISTS (
              SELECT 1 FROM local_feedback_batch batch
              WHERE batch.task_id = NEW.task_id
                AND batch.status IN ('BUILDING', 'FROZEN', 'QUEUED', 'DISPATCHED'))
          AND NOT EXISTS (
              SELECT 1 FROM remote_feedback_batch batch
              WHERE batch.task_id = NEW.task_id
                AND batch.status NOT IN ('COMPLETED', 'SUPERSEDED'))
          AND NOT EXISTS (
              SELECT 1 FROM remote_mark_ready_authorization authorization
              WHERE authorization.task_id = NEW.task_id
                AND authorization.status = 'ACTIVE')
          AND NOT EXISTS (
              SELECT 1 FROM remote_merge_authorization authorization
              WHERE authorization.task_id = NEW.task_id
                AND authorization.status = 'ACTIVE')))
      AND (NEW.outcome <> 'SUCCEEDED' OR step.ordinal <> 6 OR NOT EXISTS (
          SELECT 1 FROM permission_request permission
          WHERE permission.state = 'OPEN'
            AND ((permission.turn_kind = 'TASK' AND EXISTS (
                    SELECT 1 FROM task_turn turn
                    WHERE turn.id = permission.turn_id
                      AND turn.task_id = NEW.task_id))
              OR (permission.turn_kind = 'STAGE' AND EXISTS (
                    SELECT 1 FROM stage_turn turn
                    JOIN stage owner ON owner.id = turn.stage_id
                    WHERE turn.id = permission.turn_id
                      AND owner.task_id = NEW.task_id)))))
      AND (NEW.outcome <> 'SUCCEEDED' OR step.ordinal <> 6 OR EXISTS (
          SELECT 1 FROM cleanup_interaction_dismissal_evidence interaction
          WHERE interaction.cleanup_step_id = step.id
            AND interaction.cleanup_operation_id = operation.id
            AND interaction.task_id = NEW.task_id
            AND interaction.task_epoch = NEW.task_epoch
            AND interaction.recorded_at_ms <= NEW.recorded_at_ms))
      AND (NEW.outcome <> 'SUCCEEDED' OR step.ordinal <> 7 OR (
          NOT EXISTS (
              SELECT 1 FROM capacity_lease lease
              JOIN dispatch_ticket ticket ON ticket.id = lease.ticket_id
              WHERE lease.workflow_source = 'V2'
                AND lease.task_id = NEW.task_id
                AND lease.task_epoch = NEW.task_epoch
                AND ticket.async_family <> 'CLEANUP'
                AND lease.released_at_ms IS NULL
                AND lease.expires_at_ms > NEW.recorded_at_ms)
          AND NOT EXISTS (
              SELECT 1 FROM worktree_leases lease
              WHERE lease.workflow_version = 'V2'
                AND lease.task_id = NEW.task_id
                AND lease.task_epoch = NEW.task_epoch
                AND lease.expires_at_ms > NEW.recorded_at_ms))))
BEGIN SELECT RAISE(ABORT, 'Cleanup result lacks exact claimed or reconciled evidence'); END;
