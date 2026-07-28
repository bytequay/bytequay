-- Physical deletion is deliberately outside the normal append-only V2
-- lifecycle.  One short transaction may remove one archived Trunk only after
-- its Tasks have terminal Cleanup proof and every typed asynchronous owner is
-- quiescent.  The authorization row is removed before that transaction may
-- commit; immutable-history guards otherwise retain their original behavior.

CREATE VIEW v2_trunk_purge_state_v269 AS
SELECT trunk.id AS trunk_id,
       trunk.lifecycle_state,
       trunk.aggregate_version,
       (SELECT COUNT(*)
        FROM tasks task
        WHERE task.thread_id = trunk.id
          AND task.workflow_version = 'V2'
          AND task.lifecycle_state NOT IN (
              'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
           AS nonterminal_task_count,
       (SELECT COUNT(*)
        FROM tasks task
        WHERE task.thread_id = trunk.id
          AND task.workflow_version = 'V2'
          AND NOT EXISTS (
              SELECT 1
              FROM task_outcome outcome
              JOIN cleanup_operation operation
                ON operation.id = outcome.cleanup_operation_id
              JOIN cleanup_stage cleanup
                ON cleanup.stage_id = outcome.cleanup_stage_id
              JOIN stage owner ON owner.id = cleanup.stage_id
              JOIN dispatch_ticket ticket
                ON ticket.id = operation.dispatch_ticket_id
              WHERE outcome.task_id = task.id
                AND outcome.trunk_id = trunk.id
                AND outcome.task_epoch = task.epoch
                AND outcome.terminal_reason = task.lifecycle_state
                AND operation.task_id = task.id
                AND operation.task_epoch = task.epoch
                AND operation.cleanup_stage_id = cleanup.stage_id
                AND operation.status = 'COMPLETED'
                AND cleanup.task_id = task.id
                AND cleanup.task_epoch = task.epoch
                AND cleanup.terminal_reason = outcome.terminal_reason
                AND owner.task_id = task.id
                AND owner.kind = 'CLEANUP'
                AND owner.generation = cleanup.generation
                AND owner.checkpoint = 'COMPLETED'
                AND owner.completed_at_ms IS NOT NULL
                AND ticket.trunk_id = trunk.id
                AND ticket.task_id = task.id
                AND ticket.task_epoch = task.epoch
                AND ticket.status = 'SUCCEEDED'
                AND ticket.delivery_acceptance = 'ACCEPTED'))
           AS incomplete_cleanup_count,
       ((SELECT COUNT(*)
         FROM thread_question question
         JOIN thread_turn turn ON turn.id = question.turn_id
         WHERE turn.trunk_id = trunk.id
           AND (question.state = 'OPEN'
             OR question.continuation_state = 'READY'))
        + (SELECT COUNT(*)
           FROM task_question question
           JOIN task_turn turn ON turn.id = question.turn_id
           JOIN tasks task ON task.id = turn.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND (question.state = 'OPEN'
               OR question.continuation_state = 'READY'))
        + (SELECT COUNT(*)
           FROM stage_question question
           JOIN stage_turn turn ON turn.id = question.turn_id
           JOIN stage owner ON owner.id = turn.stage_id
           JOIN tasks task ON task.id = owner.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND (question.state = 'OPEN'
               OR question.continuation_state = 'READY'))
        + (SELECT COUNT(*)
           FROM review_assignment_question question
           JOIN review_assignment_turn turn ON turn.id = question.turn_id
           JOIN review_assignment assignment
             ON assignment.id = turn.assignment_id
           JOIN review_round round ON round.id = assignment.round_id
           JOIN review_session session ON session.id = round.session_id
           WHERE (session.owner_thread_id = trunk.id
               OR EXISTS (
                   SELECT 1 FROM tasks task
                   WHERE task.id = session.owner_task_id
                     AND task.thread_id = trunk.id
                     AND task.workflow_version = 'V2'))
             AND (question.state = 'OPEN'
               OR question.continuation_state = 'READY'))
        + (SELECT COUNT(*)
           FROM permission_request permission
           WHERE (permission.state = 'OPEN'
               OR permission.continuation_state = 'READY')
             AND ((permission.turn_kind = 'THREAD' AND EXISTS (
                      SELECT 1 FROM thread_turn turn
                      WHERE turn.id = permission.turn_id
                        AND turn.trunk_id = trunk.id))
               OR (permission.turn_kind = 'TASK' AND EXISTS (
                      SELECT 1
                      FROM task_turn turn
                      JOIN tasks task ON task.id = turn.task_id
                      WHERE turn.id = permission.turn_id
                        AND task.thread_id = trunk.id
                        AND task.workflow_version = 'V2'))
               OR (permission.turn_kind = 'STAGE' AND EXISTS (
                      SELECT 1
                      FROM stage_turn turn
                      JOIN stage owner ON owner.id = turn.stage_id
                      JOIN tasks task ON task.id = owner.task_id
                      WHERE turn.id = permission.turn_id
                        AND task.thread_id = trunk.id
                        AND task.workflow_version = 'V2'))
               OR (permission.turn_kind = 'REVIEW_ASSIGNMENT' AND EXISTS (
                      SELECT 1
                      FROM review_assignment_turn turn
                      JOIN review_assignment assignment
                        ON assignment.id = turn.assignment_id
                      JOIN review_round round
                        ON round.id = assignment.round_id
                      JOIN review_session session
                        ON session.id = round.session_id
                      WHERE turn.id = permission.turn_id
                        AND (session.owner_thread_id = trunk.id
                          OR EXISTS (
                              SELECT 1 FROM tasks task
                              WHERE task.id = session.owner_task_id
                                AND task.thread_id = trunk.id
                                AND task.workflow_version = 'V2')))))))
           AS open_wait_count,
       ((SELECT COUNT(*) FROM thread_turn turn
         WHERE turn.trunk_id = trunk.id
           AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
        + (SELECT COUNT(*)
           FROM task_turn turn
           JOIN tasks task ON task.id = turn.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
        + (SELECT COUNT(*)
           FROM stage_turn turn
           JOIN stage owner ON owner.id = turn.stage_id
           JOIN tasks task ON task.id = owner.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
        + (SELECT COUNT(*)
           FROM review_assignment_turn turn
           JOIN review_assignment assignment
             ON assignment.id = turn.assignment_id
           JOIN review_round round ON round.id = assignment.round_id
           JOIN review_session session ON session.id = round.session_id
           WHERE turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
             AND (session.owner_thread_id = trunk.id
               OR EXISTS (
                   SELECT 1 FROM tasks task
                   WHERE task.id = session.owner_task_id
                     AND task.thread_id = trunk.id
                     AND task.workflow_version = 'V2'))))
           AS live_turn_count,
       (SELECT COUNT(*) FROM dispatch_ticket ticket
        WHERE ticket.trunk_id = trunk.id
          AND ticket.status IN (
              'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
              'CLAIMED', 'RUNNING', 'DELIVERING'))
           AS live_ticket_count,
       (SELECT COUNT(*)
        FROM agent_execution execution
        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
        WHERE ticket.trunk_id = trunk.id
          AND execution.status IN ('STARTING', 'RUNNING', 'UNKNOWN'))
           AS live_execution_count,
       ((SELECT COUNT(*)
         FROM planning_base_refresh_operation operation
         WHERE operation.trunk_id = trunk.id
           AND (operation.status = 'REQUESTED'
             OR (operation.status = 'SUCCEEDED'
               AND operation.launch_disposition = 'PENDING')))
        + (SELECT COALESCE(SUM(
                live.active_plan_review_count
              + live.active_validation_count
              + live.active_brain_episode_count
              + live.active_provision_operation_count
              + live.active_quiescence_count
              + live.active_replan_count
              + live.active_feedback_batch_count
              + live.active_publish_operation_count
              + live.unreconciled_publish_operation_count
              + live.active_publish_effect_count
              + live.active_publish_authorization_count), 0)
           FROM task_live_work_counts_v230 live
           JOIN tasks task ON task.id = live.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2')
        + (SELECT COUNT(*) FROM stage_steering_request_v257 request
           JOIN tasks task ON task.id = request.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND request.status = 'PENDING')
        + (SELECT COUNT(*) FROM local_review_agent_request request
           JOIN tasks task ON task.id = request.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND request.status = 'REQUESTED')
        + (SELECT COUNT(*) FROM remote_observation_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN ('REQUESTED', 'DISPATCHED'))
        + (SELECT COUNT(*) FROM ci_repair_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN ('REQUESTED', 'DISPATCHED'))
        + (SELECT COUNT(*) FROM branch_sync_dispatch_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN (
                 'REQUESTED', 'DISPATCHED', 'INDETERMINATE'))
        + (SELECT COUNT(*)
           FROM remote_feedback_validation_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN ('REQUESTED', 'DISPATCHED'))
        + (SELECT COUNT(*) FROM remote_feedback_brain_episode episode
           JOIN tasks task ON task.id = episode.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND episode.status = 'REQUESTED')
        + (SELECT COUNT(*) FROM remote_feedback_batch batch
           JOIN tasks task ON task.id = batch.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND batch.status NOT IN ('COMPLETED', 'SUPERSEDED'))
        + (SELECT COUNT(*) FROM ci_repair_episode episode
           JOIN tasks task ON task.id = episode.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED'))
        + (SELECT COUNT(*) FROM branch_sync_episode episode
           JOIN tasks task ON task.id = episode.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND episode.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED'))
        + (SELECT COUNT(*) FROM remote_mark_ready_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN (
                 'REQUESTED', 'CLAIMED', 'AWAITING_OBSERVATION',
                 'INDETERMINATE'))
        + (SELECT COUNT(*) FROM remote_merge_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status NOT IN (
                 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED'))
        + (SELECT COUNT(*) FROM remote_mark_ready_authorization authorization
           JOIN tasks task ON task.id = authorization.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND authorization.status = 'ACTIVE')
        + (SELECT COUNT(*) FROM remote_merge_authorization authorization
           JOIN tasks task ON task.id = authorization.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND authorization.status = 'ACTIVE')
        + (SELECT COUNT(*) FROM cleanup_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status <> 'COMPLETED')
        + (SELECT COUNT(*) FROM cleanup_step_retry_request request
           JOIN tasks task ON task.id = request.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND request.status = 'PENDING')
        + (SELECT COUNT(*) FROM task_outcome_summary_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status = 'REQUESTED')
        + (SELECT COUNT(*) FROM trunk_outcome_inbox inbox
           WHERE inbox.trunk_id = trunk.id AND inbox.status = 'PENDING')
        + (SELECT COUNT(*)
           FROM outbox event
           JOIN dispatch_ticket ticket ON ticket.id = event.aggregate_id
           WHERE event.aggregate_kind = 'DISPATCH_TICKET'
             AND event.status IN ('PENDING', 'CLAIMED')
             AND ticket.trunk_id = trunk.id))
           AS live_operation_count,
       (SELECT COUNT(*)
        FROM stage owner
        JOIN tasks task ON task.id = owner.task_id
        WHERE task.thread_id = trunk.id
          AND task.workflow_version = 'V2'
          AND owner.completed_at_ms IS NULL)
           AS incomplete_stage_count,
       ((SELECT COUNT(*) FROM capacity_lease lease
         WHERE lease.workflow_source = 'V2'
           AND lease.trunk_id = trunk.id
           AND lease.released_at_ms IS NULL)
        + (SELECT COUNT(*)
           FROM worktree_leases lease
           JOIN tasks task ON task.id = lease.task_id
           WHERE lease.workflow_version = 'V2'
             AND task.thread_id = trunk.id
             AND task.workflow_version = 'V2'))
           AS live_lease_count
FROM threads trunk
WHERE trunk.turn_version = 'V2';

CREATE TABLE v2_trunk_purge_authorization_v269 (
    trunk_id          TEXT    NOT NULL PRIMARY KEY
        REFERENCES threads(id) DEFERRABLE INITIALLY DEFERRED,
    archived_version  INTEGER NOT NULL CHECK (archived_version >= 0),
    authorized_at_ms  INTEGER NOT NULL CHECK (authorized_at_ms > 0)
);

CREATE TRIGGER v2_trunk_purge_authorization_insert_v269
BEFORE INSERT ON v2_trunk_purge_authorization_v269
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_state_v269 state
    WHERE state.trunk_id = NEW.trunk_id
      AND state.lifecycle_state = 'ARCHIVED'
      AND state.aggregate_version = NEW.archived_version
      AND state.nonterminal_task_count = 0
      AND state.incomplete_cleanup_count = 0
      AND state.open_wait_count = 0
      AND state.live_turn_count = 0
      AND state.live_ticket_count = 0
      AND state.live_execution_count = 0
      AND state.live_operation_count = 0
      AND state.incomplete_stage_count = 0
      AND state.live_lease_count = 0)
BEGIN
    SELECT RAISE(ABORT,
        'V2 Trunk purge requires exact archived quiescent Cleanup proof');
END;

CREATE TRIGGER v2_trunk_purge_authorization_update_v269
BEFORE UPDATE ON v2_trunk_purge_authorization_v269
BEGIN
    SELECT RAISE(ABORT, 'V2 Trunk purge authorization is immutable');
END;

CREATE TRIGGER v2_trunk_physical_delete_guard_v269
BEFORE DELETE ON threads
WHEN OLD.turn_version = 'V2'
  AND NOT EXISTS (
      SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
      WHERE authorization.trunk_id = OLD.id
        AND authorization.archived_version = OLD.aggregate_version)
BEGIN
    SELECT RAISE(ABORT,
        'V2 Trunk physical delete requires exact purge authorization');
END;

-- Existing evidence remains immutable unless the exact owning Trunk has the
-- validated V269 authorization inside the physical-delete transaction.

DROP TRIGGER task_assignment_review_finding_delete_guard;
CREATE TRIGGER task_assignment_review_finding_delete_guard
BEFORE DELETE ON task_assignment_review_finding
WHEN NOT EXISTS (
    SELECT 1
    FROM task_assignment assignment
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = assignment.trunk_id
    WHERE assignment.id = OLD.assignment_id)
BEGIN SELECT RAISE(ABORT, 'TaskAssignment review finding cannot be deleted'); END;

DROP TRIGGER local_feedback_batch_item_delete_guard;
CREATE TRIGGER local_feedback_batch_item_delete_guard
BEFORE DELETE ON local_feedback_batch_item
WHEN EXISTS (
    SELECT 1 FROM local_feedback_batch batch
    WHERE batch.id = OLD.batch_id AND batch.status <> 'BUILDING')
  AND NOT EXISTS (
    SELECT 1
    FROM local_feedback_batch batch
    JOIN tasks task ON task.id = batch.task_id
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE batch.id = OLD.batch_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'frozen LocalFeedbackBatch items cannot be deleted'); END;

DROP TRIGGER publish_override_item_delete_guard;
CREATE TRIGGER publish_override_item_delete_guard
BEFORE DELETE ON publish_override_item
WHEN EXISTS (
    SELECT 1 FROM publish_authorization authorization
    WHERE authorization.publish_override_id = OLD.override_id)
  AND NOT EXISTS (
    SELECT 1
    FROM publish_override override
    JOIN tasks task ON task.id = override.task_id
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE override.id = OLD.override_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'used PublishOverride items cannot be deleted'); END;

DROP TRIGGER task_outcome_delete_immutable;
CREATE TRIGGER task_outcome_delete_immutable
BEFORE DELETE ON task_outcome
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.trunk_id)
BEGIN SELECT RAISE(ABORT, 'TaskOutcome cannot be deleted'); END;

DROP TRIGGER trunk_outcome_inbox_delete_immutable;
CREATE TRIGGER trunk_outcome_inbox_delete_immutable
BEFORE DELETE ON trunk_outcome_inbox
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.trunk_id)
BEGIN SELECT RAISE(ABORT, 'Trunk outcome inbox evidence cannot be deleted'); END;

DROP TRIGGER task_outcome_summary_operation_delete_immutable;
CREATE TRIGGER task_outcome_summary_operation_delete_immutable
BEFORE DELETE ON task_outcome_summary_operation
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE task.id = OLD.task_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'Task outcome summary operation cannot be deleted'); END;

DROP TRIGGER stage_initial_result_request_delete_guard;
CREATE TRIGGER stage_initial_result_request_delete_guard
BEFORE DELETE ON stage_initial_result_request
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE task.id = OLD.task_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'initial Stage result request cannot be deleted'); END;

DROP TRIGGER cleanup_step_retry_request_delete;
CREATE TRIGGER cleanup_step_retry_request_delete
BEFORE DELETE ON cleanup_step_retry_request
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE task.id = OLD.task_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'Cleanup retry evidence cannot be deleted'); END;

DROP TRIGGER remote_observation_stage_receipt_delete;
CREATE TRIGGER remote_observation_stage_receipt_delete
BEFORE DELETE ON remote_observation_stage_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE task.id = OLD.task_id AND task.workflow_version = 'V2')
BEGIN
    SELECT RAISE(ABORT, 'Remote observation Stage receipt cannot be deleted');
END;

DROP TRIGGER review_build_selection_delete_guard;
CREATE TRIGGER review_build_selection_delete_guard
BEFORE DELETE ON review_build_selection
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.thread_id)
BEGIN SELECT RAISE(ABORT, 'Review build selection cannot be deleted'); END;

DROP TRIGGER review_build_selection_item_delete_guard;
CREATE TRIGGER review_build_selection_item_delete_guard
BEFORE DELETE ON review_build_selection_item
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.thread_id)
BEGIN SELECT RAISE(ABORT, 'Review build selection item cannot be deleted'); END;

DROP TRIGGER review_build_outcome_receipt_delete_guard;
CREATE TRIGGER review_build_outcome_receipt_delete_guard
BEFORE DELETE ON review_build_outcome_receipt
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.thread_id)
BEGIN SELECT RAISE(ABORT, 'Review build outcome receipt cannot be deleted'); END;

DROP TRIGGER local_review_imported_finding_delete_guard;
CREATE TRIGGER local_review_imported_finding_delete_guard
BEFORE DELETE ON local_review_imported_finding
WHEN EXISTS (
    SELECT 1 FROM local_review_agent_request request
    WHERE request.id = OLD.request_id)
  AND NOT EXISTS (
    SELECT 1
    FROM local_review_agent_request request
    JOIN tasks task ON task.id = request.task_id
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE request.id = OLD.request_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'imported Local review finding cannot be deleted'); END;

DROP TRIGGER planning_base_refresh_delete_immutable;
CREATE TRIGGER planning_base_refresh_delete_immutable
BEFORE DELETE ON planning_base_refresh_operation
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.trunk_id)
BEGIN SELECT RAISE(ABORT, 'Planning-base Operation cannot be deleted'); END;
