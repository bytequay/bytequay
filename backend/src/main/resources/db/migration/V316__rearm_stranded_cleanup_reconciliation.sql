-- A terminal Remote observation may win after a repair child was dispatched.
-- Settle only child Operations whose exact DispatchTicket is already terminal;
-- then stop the matching Episode under its accepted Cleanup handoff.

DROP TABLE IF EXISTS temp.v316_terminal_ci_episode;
CREATE TEMP TABLE v316_terminal_ci_episode AS
SELECT episode.id AS episode_id,
       MAX(episode.opened_at_ms, terminal.observed_at_ms,
           COALESCE((
               SELECT MAX(ticket.completed_at_ms)
               FROM ci_repair_operation operation
               JOIN dispatch_ticket ticket
                 ON ticket.operation_id = operation.operation_id
               WHERE operation.ci_repair_episode_id = episode.id
                 AND ticket.status = 'CANCELED'
           ), episode.opened_at_ms)) AS stopped_at_ms,
       'Exact accepted remote ' || terminal.kind
           || ' observation superseded CI repair during Cleanup' AS reason
FROM ci_repair_episode episode
JOIN remote_terminal_observation terminal
  ON terminal.remote_development_stage_id =
         episode.remote_development_stage_id
 AND terminal.task_id = episode.task_id
 AND terminal.task_epoch = episode.task_epoch
 AND terminal.stage_generation = episode.stage_generation
 AND terminal.remote_pr_binding_id = episode.remote_pr_binding_id
JOIN task_terminal_acceptance acceptance
  ON acceptance.remote_terminal_observation_id = terminal.id
 AND acceptance.task_id = episode.task_id
 AND acceptance.task_epoch = episode.task_epoch
 AND acceptance.source_kind = 'REMOTE_OBSERVATION'
 AND acceptance.kind = CASE terminal.kind
       WHEN 'MERGED' THEN 'COMPLETED' ELSE 'REMOTE_CLOSED' END
JOIN cleanup_stage cleanup
  ON cleanup.terminal_acceptance_id = acceptance.id
 AND cleanup.task_id = episode.task_id
 AND cleanup.task_epoch = episode.task_epoch
JOIN tasks task
  ON task.id = episode.task_id
 AND task.workflow_version = 'V2'
 AND task.lifecycle_state = 'CLEANING'
 AND task.epoch = episode.task_epoch
JOIN task_current_stage current
  ON current.task_id = task.id
 AND current.stage_id = cleanup.stage_id
 AND current.stage_generation = cleanup.generation
JOIN stage owner
  ON owner.id = current.stage_id
 AND owner.kind = 'CLEANUP'
 AND owner.generation = current.stage_generation
 AND owner.checkpoint = 'CLEANING'
 AND owner.completed_at_ms IS NULL
WHERE episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED');

UPDATE ci_repair_operation
SET status = CASE (
        SELECT ticket.delivery_acceptance
        FROM dispatch_ticket ticket
        WHERE ticket.operation_id = ci_repair_operation.operation_id)
        WHEN 'SUPERSEDED' THEN 'SUPERSEDED' ELSE 'CANCELED' END,
    completed_at_ms = (
        SELECT ticket.completed_at_ms
        FROM dispatch_ticket ticket
        WHERE ticket.operation_id = ci_repair_operation.operation_id),
    error_message = COALESCE(error_message,
        'Exact accepted remote terminal observation superseded CI repair')
WHERE status IN ('REQUESTED', 'DISPATCHED')
  AND ci_repair_episode_id IN (
      SELECT episode_id FROM v316_terminal_ci_episode)
  AND EXISTS (
      SELECT 1
      FROM dispatch_ticket ticket
      WHERE ticket.operation_id = ci_repair_operation.operation_id
        AND ticket.task_id = ci_repair_operation.task_id
        AND ticket.task_epoch = ci_repair_operation.task_epoch
        AND ticket.stage_id = ci_repair_operation.remote_development_stage_id
        AND ticket.stage_generation = ci_repair_operation.stage_generation
        AND ticket.attempt = ci_repair_operation.semantic_attempt
        AND ticket.expected_code_fingerprint
              IS ci_repair_operation.expected_code_fingerprint
        AND ticket.expected_head_sha = ci_repair_operation.expected_head_sha
        AND ticket.expected_base_sha = ci_repair_operation.expected_base_sha
        AND ticket.status = 'CANCELED'
        AND ticket.completed_at_ms IS NOT NULL
        AND ticket.delivery_acceptance IN ('ACCEPTED', 'SUPERSEDED'));

DELETE FROM v316_terminal_ci_episode
WHERE EXISTS (
    SELECT 1 FROM ci_repair_operation operation
    WHERE operation.ci_repair_episode_id = v316_terminal_ci_episode.episode_id
      AND operation.status IN ('REQUESTED', 'DISPATCHED'));

UPDATE task_blocker
SET status = 'RESOLVED',
    resolved_at_ms = (
        SELECT candidate.stopped_at_ms
        FROM v316_terminal_ci_episode candidate
        WHERE candidate.episode_id = task_blocker.owner_id),
    resolution_evidence = (
        SELECT candidate.reason
        FROM v316_terminal_ci_episode candidate
        WHERE candidate.episode_id = task_blocker.owner_id)
WHERE owner_kind = 'EPISODE'
  AND status = 'OPEN'
  AND owner_id IN (SELECT episode_id FROM v316_terminal_ci_episode);

UPDATE ci_repair_episode
SET status = 'STOPPED',
    completed_at_ms = (
        SELECT candidate.stopped_at_ms
        FROM v316_terminal_ci_episode candidate
        WHERE candidate.episode_id = ci_repair_episode.id),
    stop_reason = (
        SELECT candidate.reason
        FROM v316_terminal_ci_episode candidate
        WHERE candidate.episode_id = ci_repair_episode.id)
WHERE id IN (SELECT episode_id FROM v316_terminal_ci_episode);

DROP TABLE v316_terminal_ci_episode;

DROP TABLE IF EXISTS temp.v316_terminal_branch_episode;
CREATE TEMP TABLE v316_terminal_branch_episode AS
SELECT episode.id AS episode_id,
       MAX(episode.opened_at_ms, terminal.observed_at_ms,
           COALESCE((
               SELECT MAX(ticket.completed_at_ms)
               FROM branch_sync_dispatch_operation operation
               JOIN dispatch_ticket ticket
                 ON ticket.operation_id = operation.operation_id
               WHERE operation.branch_sync_episode_id = episode.id
                 AND ticket.status = 'CANCELED'
           ), episode.opened_at_ms)) AS stopped_at_ms,
       'Exact accepted remote ' || terminal.kind
           || ' observation superseded branch sync during Cleanup' AS reason
FROM branch_sync_episode episode
JOIN remote_terminal_observation terminal
  ON terminal.remote_development_stage_id =
         episode.remote_development_stage_id
 AND terminal.task_id = episode.task_id
 AND terminal.task_epoch = episode.task_epoch
 AND terminal.stage_generation = episode.stage_generation
 AND terminal.remote_pr_binding_id = episode.remote_pr_binding_id
JOIN task_terminal_acceptance acceptance
  ON acceptance.remote_terminal_observation_id = terminal.id
 AND acceptance.task_id = episode.task_id
 AND acceptance.task_epoch = episode.task_epoch
 AND acceptance.source_kind = 'REMOTE_OBSERVATION'
 AND acceptance.kind = CASE terminal.kind
       WHEN 'MERGED' THEN 'COMPLETED' ELSE 'REMOTE_CLOSED' END
JOIN cleanup_stage cleanup
  ON cleanup.terminal_acceptance_id = acceptance.id
 AND cleanup.task_id = episode.task_id
 AND cleanup.task_epoch = episode.task_epoch
JOIN tasks task
  ON task.id = episode.task_id
 AND task.workflow_version = 'V2'
 AND task.lifecycle_state = 'CLEANING'
 AND task.epoch = episode.task_epoch
JOIN task_current_stage current
  ON current.task_id = task.id
 AND current.stage_id = cleanup.stage_id
 AND current.stage_generation = cleanup.generation
JOIN stage owner
  ON owner.id = current.stage_id
 AND owner.kind = 'CLEANUP'
 AND owner.generation = current.stage_generation
 AND owner.checkpoint = 'CLEANING'
 AND owner.completed_at_ms IS NULL
WHERE episode.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED');

UPDATE branch_sync_effect_step
SET status = 'FAILED', claim_mode = NULL, claim_owner = NULL,
    claimed_at_ms = NULL, lease_until_ms = NULL,
    last_error = 'CANCELED',
    completed_at_ms = (
        SELECT ticket.completed_at_ms
        FROM branch_sync_dispatch_operation operation
        JOIN dispatch_ticket ticket
          ON ticket.operation_id = operation.operation_id
        WHERE operation.branch_sync_effect_step_id = branch_sync_effect_step.id)
WHERE status = 'CLAIMED'
  AND EXISTS (
      SELECT 1
      FROM branch_sync_dispatch_operation operation
      JOIN dispatch_ticket ticket
        ON ticket.operation_id = operation.operation_id
      WHERE operation.branch_sync_effect_step_id = branch_sync_effect_step.id
        AND operation.branch_sync_episode_id IN (
            SELECT episode_id FROM v316_terminal_branch_episode)
        AND operation.status IN ('REQUESTED', 'DISPATCHED')
        AND ticket.task_id = operation.task_id
        AND ticket.task_epoch = operation.task_epoch
        AND ticket.stage_id = operation.remote_development_stage_id
        AND ticket.stage_generation = operation.stage_generation
        AND ticket.attempt = operation.semantic_attempt
        AND ticket.expected_code_fingerprint
              IS operation.expected_code_fingerprint
        AND ticket.expected_head_sha = operation.expected_head_sha
        AND ticket.expected_base_sha = operation.expected_base_sha
        AND ticket.status = 'CANCELED'
        AND ticket.completed_at_ms IS NOT NULL
        AND ticket.delivery_acceptance IN ('ACCEPTED', 'SUPERSEDED'));

UPDATE branch_sync_dispatch_operation
SET status = CASE (
        SELECT ticket.delivery_acceptance
        FROM dispatch_ticket ticket
        WHERE ticket.operation_id = branch_sync_dispatch_operation.operation_id)
        WHEN 'SUPERSEDED' THEN 'SUPERSEDED' ELSE 'CANCELED' END,
    completed_at_ms = (
        SELECT ticket.completed_at_ms
        FROM dispatch_ticket ticket
        WHERE ticket.operation_id = branch_sync_dispatch_operation.operation_id),
    error_message = COALESCE(error_message,
        'Exact accepted remote terminal observation superseded branch sync')
WHERE status IN ('REQUESTED', 'DISPATCHED')
  AND branch_sync_episode_id IN (
      SELECT episode_id FROM v316_terminal_branch_episode)
  AND EXISTS (
      SELECT 1
      FROM branch_sync_effect_step step
      JOIN dispatch_ticket ticket
        ON ticket.operation_id = branch_sync_dispatch_operation.operation_id
      WHERE step.id = branch_sync_dispatch_operation.branch_sync_effect_step_id
        AND step.status = 'FAILED'
        AND ticket.task_id = branch_sync_dispatch_operation.task_id
        AND ticket.task_epoch = branch_sync_dispatch_operation.task_epoch
        AND ticket.stage_id =
              branch_sync_dispatch_operation.remote_development_stage_id
        AND ticket.stage_generation =
              branch_sync_dispatch_operation.stage_generation
        AND ticket.attempt = branch_sync_dispatch_operation.semantic_attempt
        AND ticket.expected_code_fingerprint
              IS branch_sync_dispatch_operation.expected_code_fingerprint
        AND ticket.expected_head_sha =
              branch_sync_dispatch_operation.expected_head_sha
        AND ticket.expected_base_sha =
              branch_sync_dispatch_operation.expected_base_sha
        AND ticket.status = 'CANCELED'
        AND ticket.completed_at_ms IS NOT NULL
        AND ticket.delivery_acceptance IN ('ACCEPTED', 'SUPERSEDED'));

DELETE FROM v316_terminal_branch_episode
WHERE EXISTS (
    SELECT 1 FROM branch_sync_dispatch_operation operation
    WHERE operation.branch_sync_episode_id =
          v316_terminal_branch_episode.episode_id
      AND operation.status IN ('REQUESTED', 'DISPATCHED'));

UPDATE task_blocker
SET status = 'RESOLVED',
    resolved_at_ms = (
        SELECT candidate.stopped_at_ms
        FROM v316_terminal_branch_episode candidate
        WHERE candidate.episode_id = task_blocker.owner_id),
    resolution_evidence = (
        SELECT candidate.reason
        FROM v316_terminal_branch_episode candidate
        WHERE candidate.episode_id = task_blocker.owner_id)
WHERE owner_kind = 'EPISODE'
  AND status = 'OPEN'
  AND owner_id IN (SELECT episode_id FROM v316_terminal_branch_episode);

UPDATE branch_sync_episode
SET status = 'STOPPED',
    completed_at_ms = (
        SELECT candidate.stopped_at_ms
        FROM v316_terminal_branch_episode candidate
        WHERE candidate.episode_id = branch_sync_episode.id),
    error_message = (
        SELECT candidate.reason
        FROM v316_terminal_branch_episode candidate
        WHERE candidate.episode_id = branch_sync_episode.id)
WHERE id IN (SELECT episode_id FROM v316_terminal_branch_episode);

DROP TABLE v316_terminal_branch_episode;

-- V314 made these exact failed Cleanup probes safe to retry. They were
-- explicitly parked only because the old trigger rejected the probe result.
UPDATE dispatch_ticket
SET version = version + 1,
    next_attempt_at_ms = (
        SELECT step.claimed_at_ms
        FROM cleanup_operation operation
        JOIN cleanup_step step
          ON step.cleanup_operation_id = operation.id
        WHERE operation.dispatch_ticket_id = dispatch_ticket.id
          AND step.status = 'CLAIMED')
WHERE status = 'RECONCILE_WAIT'
  AND next_attempt_at_ms IS NULL
  AND claim_purpose IS NULL
  AND claim_owner IS NULL
  AND capacity_lease_id IS NULL
  AND claim_expires_at_ms IS NULL
  AND cancel_requested_at_ms IS NULL
  AND pending_result_outcome IS NULL
  AND delivery_acceptance IS NULL
  AND completed_at_ms IS NULL
  AND operation_kind = 'RUN_CLEANUP_OPERATION'
  AND async_family = 'CLEANUP'
  AND owner_kind = 'STAGE'
  AND callback_route = 'CLEANUP_OPERATION_RESULT'
  AND lane_mask = 256
  AND exclusive_task = 1
  AND writer_required = 1
  AND last_error LIKE
      '%Cleanup result lacks exact claimed or reconciled evidence%'
  AND EXISTS (
      SELECT 1
      FROM cleanup_operation operation
      JOIN cleanup_step step
        ON step.cleanup_operation_id = operation.id
      JOIN cleanup_stage cleanup
        ON cleanup.stage_id = operation.cleanup_stage_id
      JOIN tasks task ON task.id = operation.task_id
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN agent_execution execution
        ON execution.id = step.claim_owner
       AND execution.ticket_id = dispatch_ticket.id
      WHERE operation.dispatch_ticket_id = dispatch_ticket.id
        AND operation.operation_id = dispatch_ticket.operation_id
        AND operation.status = 'ACTIVE'
        AND operation.task_id = dispatch_ticket.task_id
        AND operation.task_epoch = dispatch_ticket.task_epoch
        AND operation.cleanup_stage_id = dispatch_ticket.stage_id
        AND operation.stage_generation = dispatch_ticket.stage_generation
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'CLEANING'
        AND task.epoch = operation.task_epoch
        AND current.stage_id = operation.cleanup_stage_id
        AND current.stage_generation = operation.stage_generation
        AND owner.kind = 'CLEANUP'
        AND owner.checkpoint = 'CLEANING'
        AND owner.completed_at_ms IS NULL
        AND cleanup.task_id = operation.task_id
        AND cleanup.task_epoch = operation.task_epoch
        AND cleanup.generation = operation.stage_generation
        AND step.task_id = operation.task_id
        AND step.task_epoch = operation.task_epoch
        AND step.cleanup_stage_id = operation.cleanup_stage_id
        AND step.stage_generation = operation.stage_generation
        AND step.ordinal IN (2, 3)
        AND step.status = 'CLAIMED'
        AND step.attempt_count = 2
        AND step.execute_attempt_count = 1
        AND step.claim_mode = 'PROBE'
        AND execution.status = 'UNKNOWN'
        AND execution.finished_at_ms IS NOT NULL
        AND NOT EXISTS (
            SELECT 1 FROM cleanup_step_attempt_result result
            WHERE result.cleanup_step_id = step.id
              AND result.attempt = step.attempt_count)
        AND NOT EXISTS (
            SELECT 1 FROM cleanup_step previous
            WHERE previous.cleanup_operation_id = operation.id
              AND previous.ordinal < step.ordinal
              AND NOT ((previous.requirement = 'REQUIRED'
                        AND previous.status = 'SUCCEEDED')
                OR (previous.requirement = 'OPTIONAL'
                        AND previous.status IN ('SUCCEEDED', 'WAIVED'))
                OR (previous.requirement = 'NOT_APPLICABLE'
                        AND previous.status = 'SKIPPED')))
        AND NOT EXISTS (
            SELECT 1 FROM cleanup_step later
            WHERE later.cleanup_operation_id = operation.id
              AND later.ordinal > step.ordinal
              AND later.status <> 'REQUESTED'))
  AND NOT EXISTS (
      SELECT 1 FROM agent_execution execution
      WHERE execution.ticket_id = dispatch_ticket.id
        AND execution.finished_at_ms IS NULL)
  AND NOT EXISTS (
      SELECT 1 FROM capacity_lease lease
      WHERE lease.ticket_id = dispatch_ticket.id
        AND lease.released_at_ms IS NULL);
