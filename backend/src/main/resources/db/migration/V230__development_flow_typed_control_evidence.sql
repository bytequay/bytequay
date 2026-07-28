-- Typed, inert evidence for Task pause/resume/archive completion and Local
-- feedback handoff. Historical opaque barrier evidence and feedback batches
-- remain readable but are never promoted into these V2 proof paths.

-- Expired leases are retained for audit and crash recovery. They are not live
-- by themselves; a still-running or ambiguous effect remains visible through
-- its typed Turn, Operation, DispatchTicket, or AgentExecution.
DROP TRIGGER task_quiescence_satisfied_fence;
CREATE TRIGGER task_quiescence_satisfied_fence
BEFORE UPDATE OF status ON task_quiescence_barrier
WHEN NEW.status = 'SATISFIED'
  AND (NEW.completed_at_ms IS NULL
    OR NEW.completed_at_ms > CAST(
        (julianday('now') - 2440587.5) * 86400000 AS INTEGER) + 1000
    OR EXISTS (
      SELECT 1 FROM task_turn turn
      WHERE turn.task_id = NEW.task_id AND turn.task_epoch = NEW.task_epoch
        AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
    OR EXISTS (
      SELECT 1 FROM stage_turn turn
      JOIN stage owner ON owner.id = turn.stage_id
      WHERE owner.task_id = NEW.task_id AND turn.task_epoch = NEW.task_epoch
        AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
    OR EXISTS (
      SELECT 1 FROM plan_self_review review
      JOIN plan_revision revision ON revision.id = review.plan_revision_id
      JOIN plan_stage owner ON owner.stage_id = revision.plan_stage_id
      WHERE owner.task_id = NEW.task_id
        AND review.task_epoch = NEW.task_epoch
        AND review.status = 'REQUESTED')
    OR EXISTS (
      SELECT 1 FROM validation_operation operation
      WHERE operation.task_id = NEW.task_id
        AND operation.task_epoch = NEW.task_epoch
        AND operation.status IN ('REQUESTED', 'DISPATCHED'))
    OR EXISTS (
      SELECT 1 FROM brain_review_episode episode
      WHERE episode.task_id = NEW.task_id
        AND episode.task_epoch = NEW.task_epoch
        AND episode.status IN ('REQUESTED', 'REVIEWING'))
    OR EXISTS (
      SELECT 1 FROM provision_task_operation operation
      WHERE operation.task_id = NEW.task_id
        AND operation.task_epoch = NEW.task_epoch
        AND operation.status IN ('REQUESTED', 'DISPATCHED'))
    OR EXISTS (
      SELECT 1 FROM publish_operation operation
      WHERE operation.task_id = NEW.task_id
        AND operation.task_epoch = NEW.task_epoch
        AND operation.status IN ('REQUESTED', 'DISPATCHED', 'INDETERMINATE'))
    OR EXISTS (
      SELECT 1 FROM dispatch_ticket ticket
      WHERE ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND ticket.status IN (
            'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
            'CLAIMED', 'RUNNING', 'DELIVERING'))
    OR EXISTS (
      SELECT 1 FROM agent_execution execution
      JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
      WHERE ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND execution.status IN ('STARTING', 'RUNNING', 'UNKNOWN'))
    OR EXISTS (
      SELECT 1 FROM capacity_lease lease
      WHERE lease.workflow_source = 'V2'
        AND lease.task_id = NEW.task_id AND lease.task_epoch = NEW.task_epoch
        AND lease.released_at_ms IS NULL
        AND lease.expires_at_ms > NEW.completed_at_ms)
    OR EXISTS (
      SELECT 1 FROM worktree_leases lease
      WHERE lease.workflow_version = 'V2'
        AND lease.task_id = NEW.task_id AND lease.task_epoch = NEW.task_epoch
        AND lease.expires_at_ms > NEW.completed_at_ms))
BEGIN SELECT RAISE(ABORT, 'quiescence requires all exact-epoch work to stop or reconcile'); END;

-- The currently persisted code subject. Local DevReport identity supersedes
-- the immutable provisioning identity once local development has produced it.
CREATE VIEW task_current_code_subject_v230 AS
SELECT task.id AS task_id,
       COALESCE(report.code_fingerprint, code.code_fingerprint)
           AS code_fingerprint,
       COALESCE(report.head_sha, code.local_head_sha) AS head_sha,
       COALESCE(report.base_sha, code.base_sha) AS base_sha
FROM tasks task
JOIN task_code_identity code ON code.task_id = task.id
LEFT JOIN dev_report report ON report.id = (
    SELECT candidate.id
    FROM dev_report candidate
    WHERE candidate.workflow_version = 'V2'
      AND candidate.task_id = task.id
    ORDER BY candidate.created_at_ms DESC, candidate.id DESC
    LIMIT 1)
WHERE task.workflow_version = 'V2';

-- Every currently persisted V2 Task-owned Turn, Episode, operation,
-- permission, and recovery family has its own count. Future Remote/Cleanup
-- migrations must extend this projection before enabling those routes.
CREATE VIEW task_live_work_counts_v230 AS
SELECT task.id AS task_id,
       task.epoch AS task_epoch,
       (SELECT COUNT(*) FROM task_turn turn
        WHERE turn.task_id = task.id AND turn.task_epoch = task.epoch
          AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
           AS active_task_turn_count,
       (SELECT COUNT(*) FROM stage_turn turn
        JOIN stage owner ON owner.id = turn.stage_id
        WHERE owner.task_id = task.id AND turn.task_epoch = task.epoch
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
       (SELECT COUNT(*) FROM plan_self_review review
        JOIN plan_revision revision ON revision.id = review.plan_revision_id
        JOIN plan_stage owner ON owner.stage_id = revision.plan_stage_id
        WHERE owner.task_id = task.id AND review.task_epoch = task.epoch
          AND review.status = 'REQUESTED') AS active_plan_review_count,
       (SELECT COUNT(*) FROM validation_operation operation
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND operation.status IN ('REQUESTED', 'DISPATCHED'))
           AS active_validation_count,
       (SELECT COUNT(*) FROM brain_review_episode episode
        WHERE episode.task_id = task.id AND episode.task_epoch = task.epoch
          AND episode.status IN ('REQUESTED', 'REVIEWING'))
           AS active_brain_episode_count,
       (SELECT COUNT(*) FROM provision_task_operation operation
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND operation.status IN ('REQUESTED', 'DISPATCHED'))
           AS active_provision_operation_count,
       (SELECT COUNT(*) FROM dispatch_ticket ticket
        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
          AND ticket.status IN (
              'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
              'CLAIMED', 'RUNNING', 'DELIVERING'))
           AS active_dispatch_count,
       (SELECT COUNT(*) FROM dispatch_ticket ticket
        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
          AND ticket.writer_required = 1
          AND ticket.status IN (
              'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
              'CLAIMED', 'RUNNING', 'DELIVERING'))
           AS active_writer_dispatch_count,
       (SELECT COUNT(*) FROM agent_execution execution
        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
          AND execution.status IN ('STARTING', 'RUNNING'))
           AS active_agent_execution_count,
       (SELECT COUNT(*) FROM agent_execution execution
        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
          AND execution.status = 'UNKNOWN') AS unreconciled_execution_count,
       (SELECT COUNT(*) FROM task_quiescence_barrier barrier
        WHERE barrier.task_id = task.id AND barrier.task_epoch = task.epoch
          AND barrier.status = 'REQUESTED') AS active_quiescence_count,
       (SELECT COUNT(*) FROM task_replan_request request
        WHERE request.task_id = task.id
          AND request.source_task_epoch = task.epoch
          AND request.status IN ('REQUESTED', 'QUIESCING'))
           AS active_replan_count,
       (SELECT COUNT(*) FROM local_feedback_batch batch
        WHERE batch.task_id = task.id AND batch.task_epoch = task.epoch
          AND batch.status IN ('BUILDING', 'FROZEN', 'QUEUED', 'DISPATCHED'))
           AS active_feedback_batch_count,
       (SELECT COUNT(*) FROM publish_operation operation
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND operation.status IN ('REQUESTED', 'DISPATCHED'))
           AS active_publish_operation_count,
       (SELECT COUNT(*) FROM publish_operation operation
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND operation.status = 'INDETERMINATE')
           AS unreconciled_publish_operation_count,
       (SELECT COUNT(*) FROM publish_effect_step step
        JOIN publish_operation operation ON operation.id = step.publish_operation_id
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND step.status IN ('CLAIMED', 'INDETERMINATE'))
           AS active_publish_effect_count,
       (SELECT COUNT(*) FROM publish_authorization authorization
        WHERE authorization.task_id = task.id
          AND authorization.task_epoch = task.epoch
          AND authorization.revoked_at_ms IS NULL
          AND authorization.consumed_at_ms IS NULL)
           AS active_publish_authorization_count,
       (SELECT COUNT(*) FROM permission_request permission
        WHERE permission.state = 'OPEN'
          AND ((permission.turn_kind = 'TASK' AND EXISTS (
                    SELECT 1 FROM task_turn turn
                    WHERE turn.id = permission.turn_id
                      AND turn.task_id = task.id
                      AND turn.task_epoch = task.epoch))
            OR (permission.turn_kind = 'STAGE' AND EXISTS (
                    SELECT 1 FROM stage_turn turn
                    JOIN stage owner ON owner.id = turn.stage_id
                    WHERE turn.id = permission.turn_id
                      AND owner.task_id = task.id
                      AND turn.task_epoch = task.epoch))))
           AS open_permission_count,
       (SELECT COUNT(*) FROM task_terminal_intent intent
        WHERE intent.task_id = task.id AND intent.accepted = 1)
           AS accepted_terminal_intent_count,
       (SELECT COUNT(*) FROM stage cleanup
        WHERE cleanup.task_id = task.id AND cleanup.kind = 'CLEANUP'
          AND cleanup.completed_at_ms IS NULL) AS open_cleanup_stage_count
FROM tasks task
WHERE task.workflow_version = 'V2';

-- Pause barrier proof. The canonical digest is an exact encoding of the
-- typed owner, checkpoint, code subject, barrier completion, and observation
-- time; an arbitrary opaque string cannot enter the proof path.
CREATE TABLE task_pause_evidence (
    barrier_id             TEXT    NOT NULL PRIMARY KEY
        REFERENCES task_quiescence_barrier(id),
    task_id                TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch             INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_id               TEXT    NOT NULL REFERENCES stage(id),
    stage_generation       INTEGER NOT NULL CHECK (stage_generation > 0),
    restore_checkpoint     TEXT    NOT NULL,
    code_fingerprint       TEXT    NOT NULL,
    head_sha               TEXT    NOT NULL,
    base_sha               TEXT    NOT NULL,
    barrier_completed_at_ms INTEGER NOT NULL,
    stop_evidence_digest   TEXT    NOT NULL,
    status                 TEXT    NOT NULL CHECK (status = 'SATISFIED'),
    recorded_at_ms         INTEGER NOT NULL CHECK (recorded_at_ms > 0),
    CHECK (length(trim(code_fingerprint)) > 0
        AND length(trim(head_sha)) > 0
        AND length(trim(base_sha)) > 0
        AND length(trim(stop_evidence_digest)) > 0
        AND barrier_completed_at_ms <= recorded_at_ms),
    UNIQUE (barrier_id, task_id, task_epoch)
);

CREATE VIEW task_pause_evidence_digest_v230 AS
SELECT evidence.barrier_id,
       'pause:v1:' || lower(hex(evidence.task_id)) || ':'
           || evidence.task_epoch || ':' || lower(hex(evidence.barrier_id)) || ':'
           || lower(hex(evidence.stage_id)) || ':' || evidence.stage_generation || ':'
           || lower(hex(evidence.restore_checkpoint)) || ':'
           || lower(hex(evidence.code_fingerprint)) || ':'
           || lower(hex(evidence.head_sha)) || ':' || lower(hex(evidence.base_sha)) || ':'
           || evidence.barrier_completed_at_ms || ':' || evidence.recorded_at_ms
           AS content_digest
FROM task_pause_evidence evidence;

CREATE TRIGGER task_pause_evidence_insert
BEFORE INSERT ON task_pause_evidence
WHEN NEW.recorded_at_ms > CAST(
        (julianday('now') - 2440587.5) * 86400000 AS INTEGER) + 1000
  OR NEW.stop_evidence_digest <> (
       'pause:v1:' || lower(hex(NEW.task_id)) || ':' || NEW.task_epoch || ':'
       || lower(hex(NEW.barrier_id)) || ':' || lower(hex(NEW.stage_id)) || ':'
       || NEW.stage_generation || ':' || lower(hex(NEW.restore_checkpoint)) || ':'
       || lower(hex(NEW.code_fingerprint)) || ':' || lower(hex(NEW.head_sha)) || ':'
       || lower(hex(NEW.base_sha)) || ':' || NEW.barrier_completed_at_ms || ':'
       || NEW.recorded_at_ms)
  OR NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_quiescence_barrier barrier ON barrier.task_id = task.id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    JOIN task_live_work_counts_v230 live ON live.task_id = task.id
    WHERE task.id = NEW.task_id AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'PAUSING' AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.task_id = NEW.task_id AND owner.generation = NEW.stage_generation
      AND owner.checkpoint = NEW.restore_checkpoint AND owner.completed_at_ms IS NULL
      AND code.code_fingerprint = NEW.code_fingerprint
      AND code.head_sha = NEW.head_sha AND code.base_sha = NEW.base_sha
      AND barrier.id = NEW.barrier_id AND barrier.task_epoch = NEW.task_epoch
      AND barrier.reason = 'PAUSE' AND barrier.status = NEW.status
      AND barrier.completed_at_ms = NEW.barrier_completed_at_ms
      AND live.task_epoch = NEW.task_epoch
      AND live.active_task_turn_count = 0
      AND live.active_stage_turn_count = 0
      AND live.active_review_turn_count = 0
      AND live.active_plan_review_count = 0
      AND live.active_validation_count = 0
      AND live.active_brain_episode_count = 0
      AND live.active_provision_operation_count = 0
      AND live.active_dispatch_count = 0
      AND live.active_agent_execution_count = 0
      AND live.unreconciled_execution_count = 0
      AND live.active_quiescence_count = 0
      AND live.active_replan_count = 0
      AND live.active_publish_operation_count = 0
      AND live.unreconciled_publish_operation_count = 0
      AND live.active_publish_effect_count = 0
      AND NOT EXISTS (
          SELECT 1 FROM capacity_lease lease
          WHERE lease.workflow_source = 'V2'
            AND lease.task_id = NEW.task_id AND lease.task_epoch = NEW.task_epoch
            AND lease.released_at_ms IS NULL
            AND lease.expires_at_ms > NEW.recorded_at_ms)
      AND NOT EXISTS (
          SELECT 1 FROM worktree_leases lease
          WHERE lease.workflow_version = 'V2'
            AND lease.task_id = NEW.task_id AND lease.task_epoch = NEW.task_epoch
            AND lease.expires_at_ms > NEW.recorded_at_ms))
BEGIN SELECT RAISE(ABORT, 'PauseEvidence requires its exact satisfied stopped owner'); END;

CREATE TRIGGER task_pause_evidence_immutable
BEFORE UPDATE ON task_pause_evidence
BEGIN SELECT RAISE(ABORT, 'PauseEvidence is immutable'); END;

-- Resume reconciliation proof. Its canonical digest includes the exact pause
-- proof and a typed zero vector showing no pre-resume work remains acceptable.
CREATE TABLE task_resume_reconciliation (
    id                                  TEXT    NOT NULL PRIMARY KEY,
    task_id                             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                          INTEGER NOT NULL CHECK (task_epoch > 0),
    pause_barrier_id                    TEXT    NOT NULL UNIQUE
        REFERENCES task_pause_evidence(barrier_id),
    stage_id                            TEXT    NOT NULL REFERENCES stage(id),
    stage_generation                    INTEGER NOT NULL CHECK (stage_generation > 0),
    restore_checkpoint                  TEXT    NOT NULL,
    paused_code_fingerprint             TEXT    NOT NULL,
    paused_head_sha                     TEXT    NOT NULL,
    paused_base_sha                     TEXT    NOT NULL,
    active_task_turn_count              INTEGER NOT NULL CHECK (active_task_turn_count = 0),
    active_stage_turn_count             INTEGER NOT NULL CHECK (active_stage_turn_count = 0),
    active_plan_review_count            INTEGER NOT NULL CHECK (active_plan_review_count = 0),
    active_validation_count             INTEGER NOT NULL CHECK (active_validation_count = 0),
    active_brain_episode_count           INTEGER NOT NULL CHECK (active_brain_episode_count = 0),
    active_provision_operation_count     INTEGER NOT NULL CHECK (active_provision_operation_count = 0),
    active_writer_dispatch_count         INTEGER NOT NULL CHECK (active_writer_dispatch_count = 0),
    active_agent_execution_count         INTEGER NOT NULL CHECK (active_agent_execution_count = 0),
    unreconciled_execution_count         INTEGER NOT NULL CHECK (unreconciled_execution_count = 0),
    live_writer_capacity_count           INTEGER NOT NULL CHECK (live_writer_capacity_count = 0),
    live_worktree_lease_count            INTEGER NOT NULL CHECK (live_worktree_lease_count = 0),
    active_publish_operation_count       INTEGER NOT NULL CHECK (active_publish_operation_count = 0),
    unreconciled_publish_operation_count INTEGER NOT NULL CHECK (unreconciled_publish_operation_count = 0),
    active_publish_effect_count          INTEGER NOT NULL CHECK (active_publish_effect_count = 0),
    reconciliation_digest               TEXT    NOT NULL,
    status                              TEXT    NOT NULL CHECK (status = 'SATISFIED'),
    recorded_at_ms                      INTEGER NOT NULL CHECK (recorded_at_ms > 0),
    CHECK (length(trim(paused_code_fingerprint)) > 0
        AND length(trim(paused_head_sha)) > 0
        AND length(trim(paused_base_sha)) > 0
        AND length(trim(reconciliation_digest)) > 0),
    UNIQUE (id, task_id, task_epoch)
);

CREATE VIEW task_resume_evidence_digest_v230 AS
SELECT evidence.id,
       'resume:v1:' || lower(hex(evidence.task_id)) || ':' || evidence.task_epoch || ':'
           || lower(hex(evidence.id)) || ':' || lower(hex(evidence.pause_barrier_id)) || ':'
           || lower(hex(evidence.stage_id)) || ':' || evidence.stage_generation || ':'
           || lower(hex(evidence.restore_checkpoint)) || ':'
           || lower(hex(evidence.paused_code_fingerprint)) || ':'
           || lower(hex(evidence.paused_head_sha)) || ':'
           || lower(hex(evidence.paused_base_sha)) || ':'
           || evidence.active_task_turn_count || ':' || evidence.active_stage_turn_count || ':'
           || evidence.active_plan_review_count || ':' || evidence.active_validation_count || ':'
           || evidence.active_brain_episode_count || ':'
           || evidence.active_provision_operation_count || ':'
           || evidence.active_writer_dispatch_count || ':'
           || evidence.active_agent_execution_count || ':'
           || evidence.unreconciled_execution_count || ':'
           || evidence.live_writer_capacity_count || ':'
           || evidence.live_worktree_lease_count || ':'
           || evidence.active_publish_operation_count || ':'
           || evidence.unreconciled_publish_operation_count || ':'
           || evidence.active_publish_effect_count || ':'
           || evidence.recorded_at_ms AS content_digest
FROM task_resume_reconciliation evidence;

CREATE TRIGGER task_resume_reconciliation_insert
BEFORE INSERT ON task_resume_reconciliation
WHEN NEW.recorded_at_ms > CAST(
        (julianday('now') - 2440587.5) * 86400000 AS INTEGER) + 1000
  OR NEW.reconciliation_digest <> (
       'resume:v1:' || lower(hex(NEW.task_id)) || ':' || NEW.task_epoch || ':'
       || lower(hex(NEW.id)) || ':' || lower(hex(NEW.pause_barrier_id)) || ':'
       || lower(hex(NEW.stage_id)) || ':' || NEW.stage_generation || ':'
       || lower(hex(NEW.restore_checkpoint)) || ':'
       || lower(hex(NEW.paused_code_fingerprint)) || ':'
       || lower(hex(NEW.paused_head_sha)) || ':' || lower(hex(NEW.paused_base_sha)) || ':'
       || NEW.active_task_turn_count || ':' || NEW.active_stage_turn_count || ':'
       || NEW.active_plan_review_count || ':' || NEW.active_validation_count || ':'
       || NEW.active_brain_episode_count || ':' || NEW.active_provision_operation_count || ':'
       || NEW.active_writer_dispatch_count || ':' || NEW.active_agent_execution_count || ':'
       || NEW.unreconciled_execution_count || ':' || NEW.live_writer_capacity_count || ':'
       || NEW.live_worktree_lease_count || ':' || NEW.active_publish_operation_count || ':'
       || NEW.unreconciled_publish_operation_count || ':'
       || NEW.active_publish_effect_count || ':' || NEW.recorded_at_ms)
  OR NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_pause_evidence paused ON paused.barrier_id = NEW.pause_barrier_id
    JOIN task_pause_evidence_digest_v230 pause_digest
      ON pause_digest.barrier_id = paused.barrier_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    JOIN task_live_work_counts_v230 live ON live.task_id = task.id
    WHERE task.id = NEW.task_id AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'RESUMING' AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.task_id = NEW.task_id AND owner.generation = NEW.stage_generation
      AND owner.checkpoint = NEW.restore_checkpoint AND owner.completed_at_ms IS NULL
      AND paused.task_id = NEW.task_id AND paused.task_epoch = NEW.task_epoch
      AND paused.stage_id = NEW.stage_id
      AND paused.stage_generation = NEW.stage_generation
      AND paused.restore_checkpoint = NEW.restore_checkpoint
      AND paused.code_fingerprint = NEW.paused_code_fingerprint
      AND paused.head_sha = NEW.paused_head_sha AND paused.base_sha = NEW.paused_base_sha
      AND paused.stop_evidence_digest = pause_digest.content_digest
      AND code.code_fingerprint = NEW.paused_code_fingerprint
      AND code.head_sha = NEW.paused_head_sha AND code.base_sha = NEW.paused_base_sha
      AND paused.status = 'SATISFIED' AND paused.recorded_at_ms <= NEW.recorded_at_ms
      AND live.task_epoch = NEW.task_epoch
      AND live.active_task_turn_count = NEW.active_task_turn_count
      AND live.active_stage_turn_count = NEW.active_stage_turn_count
      AND live.active_plan_review_count = NEW.active_plan_review_count
      AND live.active_validation_count = NEW.active_validation_count
      AND live.active_brain_episode_count = NEW.active_brain_episode_count
      AND live.active_provision_operation_count = NEW.active_provision_operation_count
      AND live.active_writer_dispatch_count = NEW.active_writer_dispatch_count
      AND live.active_agent_execution_count = NEW.active_agent_execution_count
      AND live.unreconciled_execution_count = NEW.unreconciled_execution_count
      AND live.active_publish_operation_count = NEW.active_publish_operation_count
      AND live.unreconciled_publish_operation_count
            = NEW.unreconciled_publish_operation_count
      AND live.active_publish_effect_count = NEW.active_publish_effect_count
      AND (SELECT COUNT(*) FROM capacity_lease lease
           WHERE lease.workflow_source = 'V2' AND lease.task_id = NEW.task_id
             AND lease.task_epoch = NEW.task_epoch AND lease.writer_required = 1
             AND lease.released_at_ms IS NULL
             AND lease.expires_at_ms > NEW.recorded_at_ms)
            = NEW.live_writer_capacity_count
      AND (SELECT COUNT(*) FROM worktree_leases lease
           WHERE lease.workflow_version = 'V2' AND lease.task_id = NEW.task_id
             AND lease.task_epoch = NEW.task_epoch
             AND lease.expires_at_ms > NEW.recorded_at_ms)
            = NEW.live_worktree_lease_count)
BEGIN SELECT RAISE(ABORT, 'ResumeEvidence lacks exact paused reconciliation'); END;

CREATE TRIGGER task_resume_reconciliation_immutable
BEFORE UPDATE ON task_resume_reconciliation
BEGIN SELECT RAISE(ABORT, 'ResumeEvidence is immutable'); END;

-- Archive liveness freezes the complete currently persisted zero vector. The
-- cleanup facts are real accepted terminal intent and open Cleanup ownership;
-- no constant or inferred cleanup sentinel is accepted.
CREATE TABLE task_archive_liveness (
    id                                   TEXT    NOT NULL PRIMARY KEY,
    task_id                              TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                           INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_id                             TEXT    NOT NULL REFERENCES stage(id),
    stage_generation                     INTEGER NOT NULL CHECK (stage_generation > 0),
    active_task_turn_count               INTEGER NOT NULL CHECK (active_task_turn_count = 0),
    active_stage_turn_count              INTEGER NOT NULL CHECK (active_stage_turn_count = 0),
    active_review_turn_count             INTEGER NOT NULL CHECK (active_review_turn_count = 0),
    active_plan_review_count             INTEGER NOT NULL CHECK (active_plan_review_count = 0),
    active_validation_count              INTEGER NOT NULL CHECK (active_validation_count = 0),
    active_brain_episode_count            INTEGER NOT NULL CHECK (active_brain_episode_count = 0),
    active_provision_operation_count      INTEGER NOT NULL CHECK (active_provision_operation_count = 0),
    active_dispatch_count                INTEGER NOT NULL CHECK (active_dispatch_count = 0),
    active_agent_execution_count         INTEGER NOT NULL CHECK (active_agent_execution_count = 0),
    unreconciled_execution_count          INTEGER NOT NULL CHECK (unreconciled_execution_count = 0),
    live_capacity_lease_count             INTEGER NOT NULL CHECK (live_capacity_lease_count = 0),
    live_worktree_lease_count             INTEGER NOT NULL CHECK (live_worktree_lease_count = 0),
    active_quiescence_count               INTEGER NOT NULL CHECK (active_quiescence_count = 0),
    active_replan_count                   INTEGER NOT NULL CHECK (active_replan_count = 0),
    active_feedback_batch_count           INTEGER NOT NULL CHECK (active_feedback_batch_count = 0),
    active_publish_operation_count        INTEGER NOT NULL CHECK (active_publish_operation_count = 0),
    unreconciled_publish_operation_count  INTEGER NOT NULL CHECK (unreconciled_publish_operation_count = 0),
    active_publish_effect_count           INTEGER NOT NULL CHECK (active_publish_effect_count = 0),
    active_publish_authorization_count    INTEGER NOT NULL CHECK (active_publish_authorization_count = 0),
    open_permission_count                 INTEGER NOT NULL CHECK (open_permission_count = 0),
    accepted_terminal_intent_count        INTEGER NOT NULL CHECK (accepted_terminal_intent_count = 0),
    open_cleanup_stage_count              INTEGER NOT NULL CHECK (open_cleanup_stage_count = 0),
    liveness_digest                       TEXT    NOT NULL,
    status                                TEXT    NOT NULL CHECK (status = 'SATISFIED'),
    recorded_at_ms                        INTEGER NOT NULL CHECK (recorded_at_ms > 0),
    CHECK (length(trim(liveness_digest)) > 0),
    UNIQUE (id, task_id, task_epoch)
);

CREATE VIEW task_archive_evidence_digest_v230 AS
SELECT evidence.id,
       'archive:v1:' || lower(hex(evidence.task_id)) || ':' || evidence.task_epoch || ':'
           || lower(hex(evidence.id)) || ':' || lower(hex(evidence.stage_id)) || ':'
           || evidence.stage_generation || ':' || evidence.active_task_turn_count || ':'
           || evidence.active_stage_turn_count || ':' || evidence.active_review_turn_count || ':'
           || evidence.active_plan_review_count || ':' || evidence.active_validation_count || ':'
           || evidence.active_brain_episode_count || ':'
           || evidence.active_provision_operation_count || ':'
           || evidence.active_dispatch_count || ':' || evidence.active_agent_execution_count || ':'
           || evidence.unreconciled_execution_count || ':'
           || evidence.live_capacity_lease_count || ':'
           || evidence.live_worktree_lease_count || ':' || evidence.active_quiescence_count || ':'
           || evidence.active_replan_count || ':' || evidence.active_feedback_batch_count || ':'
           || evidence.active_publish_operation_count || ':'
           || evidence.unreconciled_publish_operation_count || ':'
           || evidence.active_publish_effect_count || ':'
           || evidence.active_publish_authorization_count || ':'
           || evidence.open_permission_count || ':'
           || evidence.accepted_terminal_intent_count || ':'
           || evidence.open_cleanup_stage_count || ':' || evidence.recorded_at_ms
           AS content_digest
FROM task_archive_liveness evidence;

CREATE TRIGGER task_archive_liveness_insert
BEFORE INSERT ON task_archive_liveness
WHEN NEW.recorded_at_ms > CAST(
        (julianday('now') - 2440587.5) * 86400000 AS INTEGER) + 1000
  OR NEW.liveness_digest <> (
       'archive:v1:' || lower(hex(NEW.task_id)) || ':' || NEW.task_epoch || ':'
       || lower(hex(NEW.id)) || ':' || lower(hex(NEW.stage_id)) || ':'
       || NEW.stage_generation || ':' || NEW.active_task_turn_count || ':'
       || NEW.active_stage_turn_count || ':' || NEW.active_review_turn_count || ':'
       || NEW.active_plan_review_count || ':' || NEW.active_validation_count || ':'
       || NEW.active_brain_episode_count || ':' || NEW.active_provision_operation_count || ':'
       || NEW.active_dispatch_count || ':' || NEW.active_agent_execution_count || ':'
       || NEW.unreconciled_execution_count || ':' || NEW.live_capacity_lease_count || ':'
       || NEW.live_worktree_lease_count || ':' || NEW.active_quiescence_count || ':'
       || NEW.active_replan_count || ':' || NEW.active_feedback_batch_count || ':'
       || NEW.active_publish_operation_count || ':'
       || NEW.unreconciled_publish_operation_count || ':'
       || NEW.active_publish_effect_count || ':'
       || NEW.active_publish_authorization_count || ':' || NEW.open_permission_count || ':'
       || NEW.accepted_terminal_intent_count || ':' || NEW.open_cleanup_stage_count || ':'
       || NEW.recorded_at_ms)
  OR NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_live_work_counts_v230 live ON live.task_id = task.id
    WHERE task.id = NEW.task_id AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ARCHIVING' AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.task_id = NEW.task_id AND owner.generation = NEW.stage_generation
      AND owner.completed_at_ms IS NULL AND live.task_epoch = NEW.task_epoch
      AND live.active_task_turn_count = NEW.active_task_turn_count
      AND live.active_stage_turn_count = NEW.active_stage_turn_count
      AND live.active_review_turn_count = NEW.active_review_turn_count
      AND live.active_plan_review_count = NEW.active_plan_review_count
      AND live.active_validation_count = NEW.active_validation_count
      AND live.active_brain_episode_count = NEW.active_brain_episode_count
      AND live.active_provision_operation_count = NEW.active_provision_operation_count
      AND live.active_dispatch_count = NEW.active_dispatch_count
      AND live.active_agent_execution_count = NEW.active_agent_execution_count
      AND live.unreconciled_execution_count = NEW.unreconciled_execution_count
      AND live.active_quiescence_count = NEW.active_quiescence_count
      AND live.active_replan_count = NEW.active_replan_count
      AND live.active_feedback_batch_count = NEW.active_feedback_batch_count
      AND live.active_publish_operation_count = NEW.active_publish_operation_count
      AND live.unreconciled_publish_operation_count
            = NEW.unreconciled_publish_operation_count
      AND live.active_publish_effect_count = NEW.active_publish_effect_count
      AND live.active_publish_authorization_count
            = NEW.active_publish_authorization_count
      AND live.open_permission_count = NEW.open_permission_count
      AND live.accepted_terminal_intent_count = NEW.accepted_terminal_intent_count
      AND live.open_cleanup_stage_count = NEW.open_cleanup_stage_count
      AND (SELECT COUNT(*) FROM capacity_lease lease
           WHERE lease.workflow_source = 'V2' AND lease.task_id = NEW.task_id
             AND lease.task_epoch = NEW.task_epoch AND lease.released_at_ms IS NULL
             AND lease.expires_at_ms > NEW.recorded_at_ms)
            = NEW.live_capacity_lease_count
      AND (SELECT COUNT(*) FROM worktree_leases lease
           WHERE lease.workflow_version = 'V2' AND lease.task_id = NEW.task_id
             AND lease.task_epoch = NEW.task_epoch
             AND lease.expires_at_ms > NEW.recorded_at_ms)
            = NEW.live_worktree_lease_count)
BEGIN SELECT RAISE(ABORT, 'ArchiveEvidence requires its exact zero-live-work facts'); END;

CREATE TRIGGER task_archive_liveness_immutable
BEFORE UPDATE ON task_archive_liveness
BEGIN SELECT RAISE(ABORT, 'ArchiveEvidence is immutable'); END;

-- Canonical LocalFeedbackBatch digest. Historical rows retain NULL and are
-- therefore never accepted as typed feedback evidence.
ALTER TABLE local_feedback_batch ADD COLUMN content_digest TEXT;

CREATE VIEW local_feedback_batch_digest_v230 AS
SELECT batch.id AS batch_id,
       'lfb:v1:' || lower(hex(batch.task_id)) || ':' || batch.task_epoch || ':'
           || lower(hex(batch.local_development_stage_id)) || ':'
           || batch.stage_generation || ':' || lower(hex(batch.source_submission_id)) || ':'
           || lower(hex(batch.code_fingerprint)) || ':' || lower(hex(batch.head_sha)) || ':'
           || lower(hex(batch.base_sha)) || ':'
           || (SELECT group_concat(
                    item.position || ':' || lower(hex(item.thread_id)) || ':'
                    || lower(hex(item.comment_revision_id)) || ':'
                    || lower(hex(item.body_digest)) || ':'
                    || lower(hex(item.frozen_body)) || ':'
                    || lower(hex(item.frozen_thread_content)),
                    '|' ORDER BY item.position)
               FROM local_feedback_batch_item item
               WHERE item.batch_id = batch.id) AS content_digest,
       (SELECT COUNT(*) FROM local_feedback_batch_item item
        WHERE item.batch_id = batch.id) AS item_count,
       (SELECT MIN(item.position) FROM local_feedback_batch_item item
        WHERE item.batch_id = batch.id) AS first_position,
       (SELECT MAX(item.position) FROM local_feedback_batch_item item
        WHERE item.batch_id = batch.id) AS last_position
FROM local_feedback_batch batch;

CREATE TRIGGER local_feedback_batch_v230_insert_digest
BEFORE INSERT ON local_feedback_batch
WHEN NEW.content_digest IS NOT NULL
BEGIN SELECT RAISE(ABORT, 'LocalFeedbackBatch digest is assigned only at freeze'); END;

CREATE TRIGGER local_feedback_batch_v230_digest_update
BEFORE UPDATE OF content_digest ON local_feedback_batch
WHEN NOT (
    OLD.status = 'BUILDING' AND NEW.status = 'FROZEN'
    AND OLD.content_digest IS NULL AND NEW.content_digest IS NOT NULL
    AND length(trim(NEW.content_digest)) > 0
    AND EXISTS (
        SELECT 1 FROM local_feedback_batch_digest_v230 digest
        WHERE digest.batch_id = NEW.id AND digest.item_count > 0
          AND digest.first_position = 1
          AND digest.last_position = digest.item_count
          AND digest.content_digest = NEW.content_digest))
BEGIN SELECT RAISE(ABORT, 'LocalFeedbackBatch digest must freeze exact ordered items'); END;

CREATE TRIGGER local_feedback_batch_v230_freeze
BEFORE UPDATE OF status ON local_feedback_batch
WHEN NEW.status = 'FROZEN'
  AND NOT EXISTS (
      SELECT 1 FROM local_feedback_batch_digest_v230 digest
      WHERE digest.batch_id = NEW.id AND digest.item_count > 0
        AND digest.first_position = 1
        AND digest.last_position = digest.item_count
        AND digest.content_digest = NEW.content_digest)
BEGIN SELECT RAISE(ABORT, 'frozen LocalFeedbackBatch lacks canonical content digest'); END;

-- Typed receipt consumption. These supplemental triggers deliberately leave
-- the existing V228 receipts and historical rows readable while preventing a
-- new V2 transition from consuming opaque legacy evidence.
CREATE TRIGGER task_command_receipt_v230_control_proof
BEFORE INSERT ON task_command_receipt
WHEN NEW.cause IN ('COMPLETE_PAUSE', 'COMPLETE_RESUME', 'COMPLETE_ARCHIVE')
BEGIN
    SELECT CASE
        WHEN NEW.cause = 'COMPLETE_PAUSE' AND NOT EXISTS (
            SELECT 1 FROM task_pause_evidence evidence
            JOIN task_pause_evidence_digest_v230 digest
              ON digest.barrier_id = evidence.barrier_id
            JOIN task_current_stage current ON current.task_id = evidence.task_id
            JOIN stage owner ON owner.id = evidence.stage_id
            JOIN task_current_code_subject_v230 code ON code.task_id = evidence.task_id
            JOIN task_live_work_counts_v230 live ON live.task_id = evidence.task_id
            WHERE evidence.barrier_id = NEW.proof_id
              AND evidence.task_id = NEW.task_id
              AND evidence.task_epoch = NEW.expected_task_epoch
              AND evidence.task_epoch = NEW.returned_epoch
              AND evidence.stage_id = NEW.returned_current_stage_id
              AND current.stage_id = evidence.stage_id
              AND current.stage_generation = evidence.stage_generation
              AND owner.generation = evidence.stage_generation
              AND owner.checkpoint = evidence.restore_checkpoint
              AND code.code_fingerprint = evidence.code_fingerprint
              AND code.head_sha = evidence.head_sha AND code.base_sha = evidence.base_sha
              AND evidence.stop_evidence_digest = digest.content_digest
              AND evidence.status = 'SATISFIED'
              AND live.active_task_turn_count = 0
              AND live.active_stage_turn_count = 0
              AND live.active_plan_review_count = 0
              AND live.active_validation_count = 0
              AND live.active_brain_episode_count = 0
              AND live.active_provision_operation_count = 0
              AND live.active_dispatch_count = 0
              AND live.active_agent_execution_count = 0
              AND live.unreconciled_execution_count = 0
              AND live.active_publish_operation_count = 0
              AND live.unreconciled_publish_operation_count = 0
              AND live.active_publish_effect_count = 0
              AND NOT EXISTS (SELECT 1 FROM capacity_lease lease
                  WHERE lease.workflow_source = 'V2'
                    AND lease.task_id = evidence.task_id
                    AND lease.task_epoch = evidence.task_epoch
                    AND lease.released_at_ms IS NULL
                    AND lease.expires_at_ms > evidence.recorded_at_ms)
              AND NOT EXISTS (SELECT 1 FROM worktree_leases lease
                  WHERE lease.workflow_version = 'V2'
                    AND lease.task_id = evidence.task_id
                    AND lease.task_epoch = evidence.task_epoch
                    AND lease.expires_at_ms > evidence.recorded_at_ms)
              AND NEW.returned_lifecycle = 'PAUSED')
            THEN RAISE(ABORT, 'Task receipt PauseEvidence is not exact')
        WHEN NEW.cause = 'COMPLETE_RESUME' AND NOT EXISTS (
            SELECT 1 FROM task_resume_reconciliation evidence
            JOIN task_resume_evidence_digest_v230 digest ON digest.id = evidence.id
            JOIN task_current_stage current ON current.task_id = evidence.task_id
            JOIN stage owner ON owner.id = evidence.stage_id
            JOIN task_current_code_subject_v230 code ON code.task_id = evidence.task_id
            JOIN task_live_work_counts_v230 live ON live.task_id = evidence.task_id
            WHERE evidence.id = NEW.proof_id AND evidence.task_id = NEW.task_id
              AND evidence.task_epoch = NEW.expected_task_epoch
              AND evidence.task_epoch = NEW.returned_epoch
              AND evidence.stage_id = NEW.returned_current_stage_id
              AND current.stage_id = evidence.stage_id
              AND current.stage_generation = evidence.stage_generation
              AND owner.generation = evidence.stage_generation
              AND owner.checkpoint = evidence.restore_checkpoint
              AND code.code_fingerprint = evidence.paused_code_fingerprint
              AND code.head_sha = evidence.paused_head_sha
              AND code.base_sha = evidence.paused_base_sha
              AND evidence.reconciliation_digest = digest.content_digest
              AND evidence.status = 'SATISFIED'
              AND live.active_task_turn_count = 0
              AND live.active_stage_turn_count = 0
              AND live.active_plan_review_count = 0
              AND live.active_validation_count = 0
              AND live.active_brain_episode_count = 0
              AND live.active_provision_operation_count = 0
              AND live.active_writer_dispatch_count = 0
              AND live.active_agent_execution_count = 0
              AND live.unreconciled_execution_count = 0
              AND live.active_publish_operation_count = 0
              AND live.unreconciled_publish_operation_count = 0
              AND live.active_publish_effect_count = 0
              AND NOT EXISTS (SELECT 1 FROM capacity_lease lease
                  WHERE lease.workflow_source = 'V2'
                    AND lease.task_id = evidence.task_id
                    AND lease.task_epoch = evidence.task_epoch
                    AND lease.writer_required = 1 AND lease.released_at_ms IS NULL
                    AND lease.expires_at_ms > evidence.recorded_at_ms)
              AND NOT EXISTS (SELECT 1 FROM worktree_leases lease
                  WHERE lease.workflow_version = 'V2'
                    AND lease.task_id = evidence.task_id
                    AND lease.task_epoch = evidence.task_epoch
                    AND lease.expires_at_ms > evidence.recorded_at_ms)
              AND NEW.returned_lifecycle = 'ACTIVE')
            THEN RAISE(ABORT, 'Task receipt ResumeEvidence is not exact')
        WHEN NEW.cause = 'COMPLETE_ARCHIVE' AND NOT EXISTS (
            SELECT 1 FROM task_archive_liveness evidence
            JOIN task_archive_evidence_digest_v230 digest ON digest.id = evidence.id
            JOIN task_current_stage current ON current.task_id = evidence.task_id
            JOIN task_live_work_counts_v230 live ON live.task_id = evidence.task_id
            WHERE evidence.id = NEW.proof_id AND evidence.task_id = NEW.task_id
              AND evidence.task_epoch = NEW.expected_task_epoch
              AND evidence.task_epoch = NEW.returned_epoch
              AND evidence.stage_id = NEW.returned_current_stage_id
              AND current.stage_id = evidence.stage_id
              AND current.stage_generation = evidence.stage_generation
              AND evidence.liveness_digest = digest.content_digest
              AND evidence.status = 'SATISFIED'
              AND live.active_task_turn_count = evidence.active_task_turn_count
              AND live.active_stage_turn_count = evidence.active_stage_turn_count
              AND live.active_review_turn_count = evidence.active_review_turn_count
              AND live.active_plan_review_count = evidence.active_plan_review_count
              AND live.active_validation_count = evidence.active_validation_count
              AND live.active_brain_episode_count = evidence.active_brain_episode_count
              AND live.active_provision_operation_count
                    = evidence.active_provision_operation_count
              AND live.active_dispatch_count = evidence.active_dispatch_count
              AND live.active_agent_execution_count = evidence.active_agent_execution_count
              AND live.unreconciled_execution_count = evidence.unreconciled_execution_count
              AND live.active_quiescence_count = evidence.active_quiescence_count
              AND live.active_replan_count = evidence.active_replan_count
              AND live.active_feedback_batch_count = evidence.active_feedback_batch_count
              AND live.active_publish_operation_count
                    = evidence.active_publish_operation_count
              AND live.unreconciled_publish_operation_count
                    = evidence.unreconciled_publish_operation_count
              AND live.active_publish_effect_count
                    = evidence.active_publish_effect_count
              AND live.active_publish_authorization_count
                    = evidence.active_publish_authorization_count
              AND live.open_permission_count = evidence.open_permission_count
              AND live.accepted_terminal_intent_count
                    = evidence.accepted_terminal_intent_count
              AND live.open_cleanup_stage_count = evidence.open_cleanup_stage_count
              AND (SELECT COUNT(*) FROM capacity_lease lease
                  WHERE lease.workflow_source = 'V2'
                    AND lease.task_id = evidence.task_id
                    AND lease.task_epoch = evidence.task_epoch
                    AND lease.released_at_ms IS NULL
                    AND lease.expires_at_ms > evidence.recorded_at_ms)
                    = evidence.live_capacity_lease_count
              AND (SELECT COUNT(*) FROM worktree_leases lease
                  WHERE lease.workflow_version = 'V2'
                    AND lease.task_id = evidence.task_id
                    AND lease.task_epoch = evidence.task_epoch
                    AND lease.expires_at_ms > evidence.recorded_at_ms)
                    = evidence.live_worktree_lease_count
              AND NEW.returned_lifecycle = 'ARCHIVED')
            THEN RAISE(ABORT, 'Task receipt ArchiveEvidence is not exact')
    END;
END;

CREATE TRIGGER stage_command_receipt_v230_feedback_proof
BEFORE INSERT ON stage_command_receipt
WHEN NEW.cause = 'SUBMIT_LOCAL_FEEDBACK'
  AND NOT EXISTS (
      SELECT 1 FROM local_feedback_batch batch
      JOIN local_feedback_batch_digest_v230 digest ON digest.batch_id = batch.id
      WHERE batch.id = NEW.proof_id AND batch.task_id = NEW.task_id
        AND batch.task_epoch = NEW.expected_task_epoch
        AND batch.local_development_stage_id = NEW.stage_id
        AND batch.stage_generation = NEW.expected_stage_generation
        AND batch.stage_generation = NEW.returned_generation
        AND batch.source_submission_id IS NOT NULL
        AND batch.content_digest = digest.content_digest
        AND batch.status IN ('FROZEN', 'QUEUED', 'DISPATCHED')
        AND NEW.source_checkpoint = 'LOCAL_REVIEW'
        AND NEW.returned_kind = 'LOCAL_DEVELOPMENT'
        AND NEW.returned_checkpoint = 'ADDRESSING_LOCAL_FEEDBACK')
BEGIN SELECT RAISE(ABORT, 'Stage receipt LocalFeedbackBatch digest is not exact'); END;
