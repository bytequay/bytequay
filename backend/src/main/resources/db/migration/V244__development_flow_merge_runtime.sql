-- Bind the V233 exact-head merge protocol to the typed dispatcher.  The
-- dispatcher owns execution/recovery only; Remote Development and Task keep
-- their lifecycle transitions.

CREATE TRIGGER dispatch_ticket_remote_merge_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'MERGE_REMOTE_PULL_REQUEST'
  OR NEW.async_family = 'MERGE'
  OR NEW.callback_route = 'REMOTE_MERGE_RESULT'
  OR EXISTS (
      SELECT 1 FROM remote_merge_operation operation
      WHERE operation.operation_id = NEW.operation_id)
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM remote_merge_operation operation
        JOIN remote_merge_authorization authorization
          ON authorization.id = operation.merge_authorization_id
        WHERE operation.operation_id = NEW.operation_id
          AND operation.status = 'REQUESTED'
          AND authorization.status = 'CONSUMED'
          AND NEW.operation_kind = 'MERGE_REMOTE_PULL_REQUEST'
          AND NEW.async_family = 'MERGE'
          AND NEW.owner_kind = 'STAGE'
          AND NEW.owner_id = operation.remote_development_stage_id
          AND NEW.callback_route = 'REMOTE_MERGE_RESULT'
          AND NEW.task_id = operation.task_id
          AND NEW.task_epoch = operation.task_epoch
          AND NEW.stage_id = operation.remote_development_stage_id
          AND NEW.stage_generation = operation.stage_generation
          AND NEW.attempt = operation.semantic_attempt
          AND NEW.expected_code_fingerprint IS NULL
          AND NEW.expected_head_sha = operation.head_sha
          AND NEW.expected_base_sha = operation.base_sha
          AND NEW.lane_mask = 128
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Merge DispatchTicket does not match consumed exact-head authority') END;
END;

-- Re-prove the Task/Stage owner and policy at the irreversible boundary.  The
-- application validates before claiming, but pause/archive/steer and policy
-- commands may commit between that read and this insert.  Recovery PROBE
-- claims remain admissible because they inspect an effect that was already
-- attempted; only a new remote EXECUTE requires live owner authority.
CREATE TRIGGER remote_merge_effect_attempt_execute_owner
BEFORE INSERT ON remote_merge_effect_attempt
WHEN NEW.claim_mode = 'EXECUTE'
  AND NOT EXISTS (
      SELECT 1
      FROM remote_merge_operation operation
      JOIN remote_merge_authorization authorization
        ON authorization.id = operation.merge_authorization_id
      JOIN task_automation_policy policy
        ON policy.id = authorization.automation_policy_id
      JOIN tasks task ON task.id = operation.task_id
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN stage_command_receipt receipt
        ON receipt.stage_id = owner.id
       AND receipt.returned_version = owner.version
       AND receipt.disposition = 'APPLIED'
      WHERE operation.id = NEW.merge_operation_id
        AND policy.task_id = operation.task_id
        AND policy.revision = (
            SELECT MAX(current_policy.revision)
            FROM task_automation_policy current_policy
            WHERE current_policy.task_id = operation.task_id)
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'ACTIVE'
        AND task.epoch = operation.task_epoch
        AND current.stage_id = operation.remote_development_stage_id
        AND current.stage_generation = operation.stage_generation
        AND owner.task_id = operation.task_id
        AND owner.kind = 'REMOTE_DEVELOPMENT'
        AND owner.generation = operation.stage_generation
        AND owner.checkpoint = 'MERGING'
        AND owner.completed_at_ms IS NULL
        AND owner.end_reason IS NULL
        AND receipt.cause = 'AUTHORIZE_MERGE'
        AND receipt.returned_kind = 'REMOTE_DEVELOPMENT'
        AND receipt.returned_generation = operation.stage_generation
        AND receipt.returned_checkpoint = 'MERGING'
        AND receipt.returned_end_reason IS NULL
        AND receipt.returned_pending_task_epoch = operation.task_epoch
        AND receipt.returned_pending_stage_id = operation.remote_development_stage_id
        AND receipt.returned_pending_stage_generation = operation.stage_generation
        AND receipt.returned_pending_operation_id = operation.operation_id
        AND receipt.returned_pending_attempt = operation.semantic_attempt
        AND receipt.returned_pending_code_fingerprint IS NULL
        AND receipt.returned_pending_head_sha = operation.head_sha
        AND receipt.returned_pending_base_sha = operation.base_sha)
BEGIN SELECT RAISE(ABORT, 'Merge execution claim lacks current Task and Stage authority'); END;

-- Cancellation is an owner decision, not an adapter result.  Terminalize the
-- merge aggregate in the same transaction that records the ticket request so
-- queued and reconcile-wait tickets cannot strand Cleanup after restart.
CREATE TRIGGER dispatch_ticket_remote_merge_cancel
AFTER UPDATE OF cancel_requested_at_ms, pending_result_outcome
        ON dispatch_ticket
WHEN NEW.operation_kind = 'MERGE_REMOTE_PULL_REQUEST'
  AND NEW.async_family = 'MERGE'
  AND NEW.callback_route = 'REMOTE_MERGE_RESULT'
  AND ((OLD.cancel_requested_at_ms IS NULL
            AND NEW.cancel_requested_at_ms IS NOT NULL)
        OR (OLD.pending_result_outcome IS NOT 'CANCELED'
            AND NEW.pending_result_outcome = 'CANCELED'))
BEGIN
    UPDATE remote_merge_effect_attempt
    SET status = 'FAILED',
        evidence = 'merge dispatch cancellation requested',
        last_error = 'merge dispatch cancellation requested',
        completed_at_ms = COALESCE(
            NEW.cancel_requested_at_ms, NEW.completed_at_ms, NEW.created_at_ms)
    WHERE merge_operation_id = (
            SELECT operation.id FROM remote_merge_operation operation
            WHERE operation.operation_id = NEW.operation_id)
      AND status IN ('CLAIMED', 'AWAITING_OBSERVATION');

    UPDATE remote_merge_operation
    SET status = 'CANCELED',
        completed_at_ms = COALESCE(
            NEW.cancel_requested_at_ms, NEW.completed_at_ms, NEW.created_at_ms),
        last_error = 'merge dispatch cancellation requested'
    WHERE operation_id = NEW.operation_id
      AND status NOT IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED');
END;

-- The Stage validates the accepted snapshot before the handoff.  Task then
-- owns terminal intent.  Materialize the immutable V233 remote terminal fact
-- immediately after that intent is accepted, in the same command transaction.
CREATE TRIGGER task_terminal_intent_remote_observation_insert
AFTER INSERT ON task_terminal_intent
WHEN NEW.source = 'REMOTE_OBSERVATION'
BEGIN
    INSERT INTO remote_terminal_observation(
        id, remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, remote_pr_snapshot_id,
        task_terminal_intent_id, kind, head_sha, base_sha,
        observed_at_ms, evidence)
    SELECT 'remote-terminal-observation:' || NEW.id,
        snapshot.remote_development_stage_id, snapshot.task_id,
        snapshot.task_epoch, snapshot.stage_generation,
        snapshot.remote_pr_binding_id, snapshot.id, NEW.id,
        CASE NEW.kind WHEN 'COMPLETED' THEN 'MERGED' ELSE 'CLOSED' END,
        snapshot.head_sha, snapshot.base_sha, snapshot.observed_at_ms,
        COALESCE(NEW.evidence_json, snapshot.raw_evidence,
            'accepted remote terminal observation')
    FROM remote_pr_snapshot snapshot
    JOIN remote_development_stage remote
      ON remote.stage_id = snapshot.remote_development_stage_id
    WHERE snapshot.id = NEW.source_id
      AND snapshot.task_id = NEW.task_id
      AND NEW.accepted = 1
      AND NEW.kind IN ('COMPLETED', 'REMOTE_CLOSED')
      AND NEW.observed_head_sha = snapshot.head_sha
      AND snapshot.pr_state = CASE NEW.kind
          WHEN 'COMPLETED' THEN 'MERGED' ELSE 'CLOSED' END
      AND remote.accepted_snapshot_id = snapshot.id
      AND remote.current_head_sha = snapshot.head_sha
      AND remote.current_base_sha = snapshot.base_sha;

    SELECT CASE WHEN changes() <> 1
        THEN RAISE(ABORT, 'Remote Task terminal intent lacks accepted exact-head truth') END;
END;
