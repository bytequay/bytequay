-- Rebuild the canonical receipt table to admit the exact publish-failure
-- result cause without weakening any existing receipt constraint.
PRAGMA legacy_alter_table = ON;
ALTER TABLE stage_command_receipt RENAME TO stage_command_receipt_v312;
CREATE TABLE stage_command_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    stage_id                          TEXT    NOT NULL,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause IN (
        'OPEN_INITIAL_PLAN', 'OPEN_REPLAN_PLAN', 'ACCEPT_DRAFTED',
        'ACCEPT_PLAN_BRAIN_FINDINGS', 'ACCEPT_PLAN_BRAIN_APPROVAL',
        'REVISE_BEFORE_APPROVAL', 'APPROVE_PLAN', 'OPEN_LOCAL_DEVELOPMENT',
        'ACCEPT_IMPLEMENTATION', 'ACCEPT_VALIDATION', 'ACCEPT_BRAIN_APPROVAL',
        'ACCEPT_BRAIN_FINDINGS', 'ACCEPT_BRAIN_FIXES', 'SUBMIT_LOCAL_FEEDBACK',
        'AUTHORIZE_PUBLISH', 'ACCEPT_LOCAL_FEEDBACK_FIXES', 'ACCEPT_PUBLISHED', 'ACCEPT_PUBLISH_FAILURE',
        'OPEN_REMOTE_DEVELOPMENT', 'ACCEPT_CI', 'ACCEPT_READY',
        'BEGIN_REMOTE_FEEDBACK', 'ACCEPT_REMOTE_FEEDBACK_PUSH',
        'ACCEPT_READINESS', 'AUTHORIZE_MERGE', 'ACCEPT_REMOTE_MERGED',
        'ACCEPT_REMOTE_CLOSED', 'SEAL_FOR_REPLAN',
        'SEAL_FOR_TASK_CANCELLATION', 'OPEN_CANCELED_CLEANUP',
        'OPEN_TERMINAL_CLEANUP', 'ACCEPT_CLEANUP_QUIESCENCE',
        'ACCEPT_CLEANUP_COMPLETE')),
    actor                             TEXT    NOT NULL,
    disposition                       TEXT    NOT NULL CHECK (disposition IN (
        'APPLIED', 'SUPERSEDED')),

    -- Structural commands carry these; result commands and Stage creation do
    -- not invent a current version/checkpoint fence.
    expected_task_epoch               INTEGER CHECK (expected_task_epoch > 0),
    expected_stage_generation         INTEGER CHECK (expected_stage_generation > 0),
    expected_stage_version            INTEGER CHECK (expected_stage_version >= 0),
    source_checkpoint                 TEXT,

    -- Optional command ResultFence, including operation attempt.
    subject_task_epoch                INTEGER CHECK (subject_task_epoch > 0),
    subject_stage_id                  TEXT,
    subject_stage_generation          INTEGER,
    subject_operation_id              TEXT,
    subject_attempt                   INTEGER,
    subject_expected_code_fingerprint TEXT,
    subject_expected_head_sha         TEXT,
    subject_expected_base_sha         TEXT,
    proof_id                          TEXT,

    -- Exact returned StageManager.State.
    returned_kind                     TEXT    NOT NULL CHECK (returned_kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT', 'CLEANUP')),
    returned_generation               INTEGER NOT NULL CHECK (returned_generation > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version >= 0),
    returned_checkpoint               TEXT    NOT NULL,
    returned_end_reason               TEXT CHECK (returned_end_reason IN (
        'NORMAL', 'SUPERSEDED_BY_REPLAN', 'TASK_CANCELED',
        'REMOTE_MERGED', 'REMOTE_CLOSED')),
    returned_pending_task_epoch INTEGER CHECK (returned_pending_task_epoch > 0),
    returned_pending_stage_id   TEXT,
    returned_pending_stage_generation INTEGER,
    returned_pending_operation_id TEXT,
    returned_pending_attempt     INTEGER,
    returned_pending_code_fingerprint TEXT,
    returned_pending_head_sha    TEXT,
    returned_pending_base_sha    TEXT,
    recorded_at_ms               INTEGER NOT NULL,
    UNIQUE (stage_id, command_id),
    CHECK ((expected_task_epoch IS NULL
            AND expected_stage_generation IS NULL
            AND expected_stage_version IS NULL)
        OR (expected_task_epoch IS NOT NULL
            AND expected_stage_generation IS NOT NULL
            AND expected_stage_version IS NOT NULL)),
    CHECK (source_checkpoint IS NULL OR expected_task_epoch IS NOT NULL),
    CHECK (expected_stage_version IS NULL
        OR returned_version = expected_stage_version + 1),
    CHECK ((subject_operation_id IS NULL
            AND subject_task_epoch IS NULL AND subject_stage_id IS NULL
            AND subject_stage_generation IS NULL AND subject_attempt IS NULL
            AND subject_expected_code_fingerprint IS NULL
            AND subject_expected_head_sha IS NULL
            AND subject_expected_base_sha IS NULL)
        OR (subject_operation_id IS NOT NULL AND length(subject_operation_id) > 0
            AND subject_task_epoch IS NOT NULL AND subject_attempt > 0
            AND ((subject_stage_id IS NULL AND subject_stage_generation = 0)
                OR (subject_stage_id IS NOT NULL
                    AND subject_stage_generation > 0)))),
    CHECK ((returned_pending_operation_id IS NULL
            AND returned_pending_task_epoch IS NULL
            AND returned_pending_stage_id IS NULL
            AND returned_pending_stage_generation IS NULL
            AND returned_pending_attempt IS NULL
            AND returned_pending_code_fingerprint IS NULL
            AND returned_pending_head_sha IS NULL
            AND returned_pending_base_sha IS NULL)
        OR (returned_pending_operation_id IS NOT NULL
            AND length(returned_pending_operation_id) > 0
            AND returned_pending_task_epoch IS NOT NULL
            AND returned_pending_attempt > 0
            AND returned_pending_stage_id = stage_id
            AND returned_pending_stage_generation = returned_generation)),
    CHECK (returned_checkpoint <> 'COMPLETED'
        OR returned_end_reason IS NOT NULL),
    CHECK (returned_checkpoint = 'COMPLETED'
        OR returned_end_reason IS NULL
        OR returned_end_reason IN ('SUPERSEDED_BY_REPLAN', 'TASK_CANCELED')),
    CHECK (returned_end_reason NOT IN ('NORMAL', 'REMOTE_MERGED', 'REMOTE_CLOSED')
        OR returned_checkpoint = 'COMPLETED'),
    CHECK (returned_end_reason NOT IN ('REMOTE_MERGED', 'REMOTE_CLOSED')
        OR returned_kind = 'REMOTE_DEVELOPMENT'),
    CHECK (returned_end_reason IS NULL
        OR returned_pending_operation_id IS NULL),
    CHECK (returned_kind <> 'PLAN' OR returned_checkpoint IN (
        'DRAFTING', 'SELF_REVIEW', 'AWAITING_APPROVAL', 'COMPLETED')),
    CHECK (returned_kind <> 'LOCAL_DEVELOPMENT' OR returned_checkpoint IN (
        'IMPLEMENTING', 'VALIDATING', 'BRAIN_REVIEW', 'LOCAL_REVIEW',
        'PUBLISHING', 'ADDRESSING_BRAIN_FINDINGS',
        'ADDRESSING_LOCAL_FEEDBACK', 'COMPLETED')),
    CHECK (returned_kind <> 'REMOTE_DEVELOPMENT' OR returned_checkpoint IN (
        'WAITING_CI', 'AWAITING_READY', 'WAITING_REMOTE_REVIEW',
        'ADDRESSING_REMOTE_FEEDBACK', 'READY_TO_MERGE', 'MERGING', 'COMPLETED')),
    CHECK (returned_kind <> 'CLEANUP' OR returned_checkpoint IN (
        'WAITING_QUIESCENCE', 'CLEANING', 'COMPLETED')),
    CHECK ((proof_id IS NOT NULL) = (cause IN (
        'OPEN_INITIAL_PLAN', 'OPEN_REPLAN_PLAN', 'REVISE_BEFORE_APPROVAL',
        'APPROVE_PLAN', 'OPEN_LOCAL_DEVELOPMENT', 'SUBMIT_LOCAL_FEEDBACK',
        'AUTHORIZE_PUBLISH', 'BEGIN_REMOTE_FEEDBACK', 'AUTHORIZE_MERGE',
        'ACCEPT_REMOTE_MERGED', 'ACCEPT_REMOTE_CLOSED', 'SEAL_FOR_REPLAN',
        'SEAL_FOR_TASK_CANCELLATION', 'OPEN_CANCELED_CLEANUP',
        'OPEN_TERMINAL_CLEANUP', 'ACCEPT_CLEANUP_QUIESCENCE'))),
    CHECK ((subject_operation_id IS NOT NULL) = (cause IN (
        'OPEN_INITIAL_PLAN', 'ACCEPT_DRAFTED',
        'ACCEPT_PLAN_BRAIN_FINDINGS', 'ACCEPT_PLAN_BRAIN_APPROVAL',
        'ACCEPT_IMPLEMENTATION', 'ACCEPT_VALIDATION', 'ACCEPT_BRAIN_APPROVAL',
        'ACCEPT_BRAIN_FINDINGS', 'ACCEPT_BRAIN_FIXES', 'AUTHORIZE_PUBLISH',
        'ACCEPT_LOCAL_FEEDBACK_FIXES', 'ACCEPT_PUBLISHED', 'ACCEPT_PUBLISH_FAILURE',
        'OPEN_REMOTE_DEVELOPMENT', 'ACCEPT_CI', 'ACCEPT_READY',
        'ACCEPT_REMOTE_FEEDBACK_PUSH', 'ACCEPT_READINESS', 'AUTHORIZE_MERGE',
        'ACCEPT_CLEANUP_COMPLETE'))),
    CHECK (cause NOT IN (
        'OPEN_INITIAL_PLAN', 'OPEN_REPLAN_PLAN', 'OPEN_LOCAL_DEVELOPMENT',
        'OPEN_REMOTE_DEVELOPMENT', 'OPEN_CANCELED_CLEANUP',
        'OPEN_TERMINAL_CLEANUP', 'ACCEPT_DRAFTED',
        'ACCEPT_PLAN_BRAIN_FINDINGS', 'ACCEPT_PLAN_BRAIN_APPROVAL',
        'ACCEPT_IMPLEMENTATION', 'ACCEPT_VALIDATION', 'ACCEPT_BRAIN_APPROVAL',
        'ACCEPT_BRAIN_FINDINGS', 'ACCEPT_BRAIN_FIXES',
        'ACCEPT_LOCAL_FEEDBACK_FIXES', 'ACCEPT_PUBLISHED', 'ACCEPT_PUBLISH_FAILURE', 'ACCEPT_CI',
        'ACCEPT_READY', 'ACCEPT_REMOTE_FEEDBACK_PUSH', 'ACCEPT_READINESS',
        'ACCEPT_CLEANUP_COMPLETE')
        OR expected_task_epoch IS NULL),
    CHECK (cause IN (
        'OPEN_INITIAL_PLAN', 'OPEN_REPLAN_PLAN', 'OPEN_LOCAL_DEVELOPMENT',
        'OPEN_REMOTE_DEVELOPMENT', 'OPEN_CANCELED_CLEANUP',
        'OPEN_TERMINAL_CLEANUP', 'ACCEPT_DRAFTED',
        'ACCEPT_PLAN_BRAIN_FINDINGS', 'ACCEPT_PLAN_BRAIN_APPROVAL',
        'ACCEPT_IMPLEMENTATION', 'ACCEPT_VALIDATION', 'ACCEPT_BRAIN_APPROVAL',
        'ACCEPT_BRAIN_FINDINGS', 'ACCEPT_BRAIN_FIXES',
        'ACCEPT_LOCAL_FEEDBACK_FIXES', 'ACCEPT_PUBLISHED', 'ACCEPT_PUBLISH_FAILURE', 'ACCEPT_CI',
        'ACCEPT_READY', 'ACCEPT_REMOTE_FEEDBACK_PUSH', 'ACCEPT_READINESS',
        'ACCEPT_CLEANUP_COMPLETE')
        OR expected_task_epoch IS NOT NULL),
    CHECK ((source_checkpoint IS NOT NULL) = (cause IN (
        'REVISE_BEFORE_APPROVAL', 'APPROVE_PLAN', 'SUBMIT_LOCAL_FEEDBACK',
        'AUTHORIZE_PUBLISH', 'BEGIN_REMOTE_FEEDBACK', 'AUTHORIZE_MERGE',
        'ACCEPT_CLEANUP_QUIESCENCE'))),
    CHECK (source_checkpoint IS NULL
        OR (cause IN ('REVISE_BEFORE_APPROVAL', 'APPROVE_PLAN')
            AND source_checkpoint = 'AWAITING_APPROVAL')
        OR (cause IN ('SUBMIT_LOCAL_FEEDBACK', 'AUTHORIZE_PUBLISH')
            AND source_checkpoint = 'LOCAL_REVIEW')
        OR (cause = 'BEGIN_REMOTE_FEEDBACK'
            AND source_checkpoint = 'WAITING_REMOTE_REVIEW')
        OR (cause = 'AUTHORIZE_MERGE'
            AND source_checkpoint = 'READY_TO_MERGE')
        OR (cause = 'ACCEPT_CLEANUP_QUIESCENCE'
            AND source_checkpoint = 'WAITING_QUIESCENCE')),
    CHECK (disposition <> 'SUPERSEDED' OR subject_operation_id IS NOT NULL),
    FOREIGN KEY (stage_id, task_id, returned_kind, returned_generation)
        REFERENCES stage(id, task_id, kind, generation)
        DEFERRABLE INITIALLY DEFERRED
);

INSERT INTO stage_command_receipt
SELECT * FROM stage_command_receipt_v312;
DROP TABLE stage_command_receipt_v312;
PRAGMA legacy_alter_table = OFF;

CREATE TRIGGER stage_command_receipt_immutable
BEFORE UPDATE ON stage_command_receipt
BEGIN SELECT RAISE(ABORT, 'Stage command receipt is immutable'); END;
CREATE TRIGGER stage_command_receipt_insert
BEFORE INSERT ON stage_command_receipt
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM stage stage
            JOIN tasks task ON task.id = stage.task_id
            WHERE stage.id = NEW.stage_id
              AND stage.task_id = NEW.task_id
              AND stage.kind = NEW.returned_kind
              AND stage.generation = NEW.returned_generation
              AND task.workflow_version = 'V2')
            THEN RAISE(ABORT, 'Stage command receipt requires its exact V2 owner')
        WHEN NEW.disposition = 'APPLIED' AND NOT EXISTS (
            SELECT 1 FROM stage_transition transition
            WHERE transition.stage_id = NEW.stage_id
              AND transition.command_id = NEW.command_id
              AND transition.generation = NEW.returned_generation
              AND transition.to_checkpoint = NEW.returned_checkpoint
              AND transition.stage_version = NEW.returned_version
              AND transition.cause = NEW.cause AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'applied Stage receipt lacks its exact transition')
        WHEN NEW.cause = 'SEAL_FOR_TASK_CANCELLATION' AND NOT EXISTS (
            SELECT 1 FROM task_quiescence_barrier barrier
            WHERE barrier.id = NEW.proof_id
              AND barrier.task_id = NEW.task_id
              AND barrier.task_epoch = NEW.expected_task_epoch
              AND barrier.reason = 'CANCEL'
              AND barrier.status = 'SATISFIED'
              AND NEW.expected_stage_generation = NEW.returned_generation
              AND NEW.returned_end_reason = 'TASK_CANCELED'
              AND NEW.source_checkpoint IS NULL)
            THEN RAISE(ABORT, 'Stage cancellation seal proof is invalid')
        WHEN NEW.cause = 'OPEN_CANCELED_CLEANUP' AND NOT EXISTS (
            SELECT 1
            FROM task_quiescence_barrier barrier
            JOIN task_command_receipt task_receipt
              ON task_receipt.task_id = barrier.task_id
            WHERE barrier.id = NEW.proof_id
              AND barrier.task_id = NEW.task_id
              AND barrier.reason = 'CANCEL'
              AND barrier.status = 'SATISFIED'
              AND task_receipt.command_id = NEW.command_id
              AND task_receipt.cause = 'OPEN_CANCELED_CLEANUP'
              AND task_receipt.actor = NEW.actor
              AND task_receipt.disposition = 'APPLIED'
              AND task_receipt.expected_task_epoch = barrier.task_epoch
              AND task_receipt.proof_id = barrier.id
              AND task_receipt.next_stage_id = NEW.stage_id
              AND task_receipt.next_stage_kind = 'CLEANUP'
              AND task_receipt.next_stage_generation = NEW.returned_generation
              AND task_receipt.returned_current_stage_id = NEW.stage_id
              AND task_receipt.returned_lifecycle = 'CLEANING'
              AND task_receipt.returned_terminal_intent = 'CANCELED'
              AND NEW.returned_kind = 'CLEANUP'
              AND NEW.returned_version = 0
              AND NEW.returned_checkpoint = 'WAITING_QUIESCENCE')
            THEN RAISE(ABORT, 'canceled Cleanup must open waiting on its exact barrier')
        WHEN NEW.cause = 'ACCEPT_CLEANUP_QUIESCENCE' AND NOT EXISTS (
            SELECT 1 FROM task_quiescence_barrier barrier
            WHERE barrier.id = NEW.proof_id
              AND barrier.task_id = NEW.task_id
              AND barrier.task_epoch = NEW.expected_task_epoch
              AND barrier.reason IN ('CANCEL', 'CLEANUP')
              AND barrier.status = 'SATISFIED'
              AND NEW.expected_stage_generation = NEW.returned_generation
              AND NEW.source_checkpoint = 'WAITING_QUIESCENCE'
              AND NEW.returned_kind = 'CLEANUP'
              AND NEW.returned_version = NEW.expected_stage_version + 1
              AND NEW.returned_checkpoint = 'CLEANING'
              AND (barrier.reason <> 'CANCEL' OR EXISTS (
                  SELECT 1 FROM stage_command_receipt opening
                  WHERE opening.stage_id = NEW.stage_id
                    AND opening.task_id = NEW.task_id
                    AND opening.cause = 'OPEN_CANCELED_CLEANUP'
                    AND opening.proof_id = barrier.id
                    AND opening.returned_generation = NEW.returned_generation
                    AND opening.returned_version = 0
                    AND opening.returned_checkpoint = 'WAITING_QUIESCENCE')))
            THEN RAISE(ABORT, 'Cleanup quiescence receipt lacks its exact barrier')
        WHEN NEW.cause = 'OPEN_INITIAL_PLAN' AND NOT EXISTS (
            SELECT 1 FROM provision_task_operation operation
            WHERE operation.task_id = NEW.task_id
              AND operation.operation_id = NEW.proof_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.result_code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.result_head_sha IS NEW.subject_expected_head_sha
              AND operation.result_base_sha IS NEW.subject_expected_base_sha
              AND operation.status = 'ACCEPTED'
              AND NEW.returned_kind = 'PLAN'
              AND NEW.returned_checkpoint = 'DRAFTING')
            THEN RAISE(ABORT, 'Stage receipt initial Plan proof is invalid')
        WHEN NEW.cause = 'OPEN_REPLAN_PLAN' AND NOT EXISTS (
            SELECT 1 FROM task_replan_request request
            JOIN task_quiescence_barrier barrier
              ON barrier.id = request.quiescence_barrier_id
            WHERE request.id = NEW.proof_id
              AND request.task_id = NEW.task_id
              AND barrier.status = 'SATISFIED'
              AND NEW.returned_kind = 'PLAN'
              AND NEW.returned_checkpoint = 'DRAFTING')
            THEN RAISE(ABORT, 'Stage receipt replan proof is invalid')
        WHEN NEW.cause = 'OPEN_LOCAL_DEVELOPMENT' AND NOT EXISTS (
            SELECT 1 FROM plan_approval approval
            JOIN plan_revision revision ON revision.id = approval.plan_revision_id
            JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
            WHERE approval.id = NEW.proof_id
              AND plan.task_id = NEW.task_id
              AND NEW.returned_kind = 'LOCAL_DEVELOPMENT'
              AND NEW.returned_checkpoint = 'IMPLEMENTING')
            THEN RAISE(ABORT, 'Stage receipt Local opening proof is invalid')
        WHEN NEW.cause = 'REVISE_BEFORE_APPROVAL' AND NOT EXISTS (
            SELECT 1 FROM plan_revision revision
            JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
            WHERE revision.id = NEW.proof_id
              AND plan.task_id = NEW.task_id
              AND plan.stage_id = NEW.stage_id
              AND plan.generation = NEW.returned_generation)
            THEN RAISE(ABORT, 'Stage receipt Plan revision proof is invalid')
        WHEN NEW.cause = 'APPROVE_PLAN' AND NOT EXISTS (
            SELECT 1 FROM plan_approval approval
            JOIN plan_revision revision ON revision.id = approval.plan_revision_id
            JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
            WHERE approval.id = NEW.proof_id
              AND plan.task_id = NEW.task_id
              AND plan.stage_id = NEW.stage_id
              AND plan.generation = NEW.returned_generation)
            THEN RAISE(ABORT, 'Stage receipt Plan approval proof is invalid')
        WHEN NEW.cause = 'ACCEPT_IMPLEMENTATION' AND NOT EXISTS (
            SELECT 1 FROM dev_report report
            JOIN stage_turn turn ON turn.id = report.stage_turn_id
            WHERE report.workflow_version = 'V2'
              AND report.task_id = NEW.task_id
              AND report.local_development_stage_id = NEW.stage_id
              AND report.task_epoch = NEW.subject_task_epoch
              AND report.stage_generation = NEW.subject_stage_generation
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND report.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND report.head_sha IS NEW.subject_expected_head_sha
              AND report.base_sha IS NEW.subject_expected_base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = report.local_development_stage_id
                    AND newer.revision > report.revision))
            THEN RAISE(ABORT, 'Stage receipt implementation proof is invalid')
        WHEN NEW.cause = 'ACCEPT_VALIDATION' AND NOT EXISTS (
            SELECT 1 FROM validation_evidence evidence
            JOIN validation_operation operation
              ON operation.id = evidence.validation_operation_id
            WHERE evidence.task_id = NEW.task_id
              AND evidence.task_epoch = NEW.subject_task_epoch
              AND evidence.stage_id = NEW.stage_id
              AND evidence.stage_generation = NEW.subject_stage_generation
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.semantic_attempt = NEW.subject_attempt
              AND evidence.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND evidence.head_sha IS NEW.subject_expected_head_sha
              AND evidence.base_sha IS NEW.subject_expected_base_sha
              AND evidence.passed = 1
              AND operation.status = 'COMPLETED')
            THEN RAISE(ABORT, 'Stage receipt validation proof is invalid')
        WHEN NEW.cause IN ('ACCEPT_BRAIN_APPROVAL', 'ACCEPT_BRAIN_FINDINGS')
          AND NOT EXISTS (
            SELECT 1 FROM brain_review_episode brain
            JOIN task_turn turn ON turn.id = brain.task_turn_id
            WHERE brain.task_id = NEW.task_id
              AND brain.task_epoch = NEW.subject_task_epoch
              AND brain.local_development_stage_id = NEW.stage_id
              AND brain.stage_generation = NEW.subject_stage_generation
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND brain.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND brain.expected_head_sha IS NEW.subject_expected_head_sha
              AND brain.expected_base_sha IS NEW.subject_expected_base_sha
              AND brain.status = 'SUCCEEDED'
              AND ((NEW.cause = 'ACCEPT_BRAIN_APPROVAL'
                    AND brain.verdict = 'APPROVED')
                OR (NEW.cause = 'ACCEPT_BRAIN_FINDINGS'
                    AND brain.verdict = 'CHANGES_REQUESTED')))
            THEN RAISE(ABORT, 'Stage receipt Brain proof is invalid')
        WHEN NEW.cause IN ('ACCEPT_BRAIN_FIXES', 'ACCEPT_LOCAL_FEEDBACK_FIXES')
          AND NOT EXISTS (
            SELECT 1 FROM stage_turn turn
            WHERE turn.stage_id = NEW.stage_id
              AND turn.stage_generation = NEW.subject_stage_generation
              AND turn.task_epoch = NEW.subject_task_epoch
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND turn.expected_code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND turn.expected_head_sha IS NEW.subject_expected_head_sha
              AND turn.expected_base_sha IS NEW.subject_expected_base_sha
              AND turn.status = 'SUCCEEDED')
            THEN RAISE(ABORT, 'Stage receipt fix result proof is invalid')
        WHEN NEW.cause = 'SUBMIT_LOCAL_FEEDBACK' AND NOT EXISTS (
            SELECT 1 FROM local_feedback_batch batch
            WHERE batch.id = NEW.proof_id
              AND batch.task_id = NEW.task_id
              AND batch.local_development_stage_id = NEW.stage_id
              AND batch.stage_generation = NEW.returned_generation
              AND batch.status IN ('FROZEN', 'DISPATCHED', 'ADDRESSED'))
            THEN RAISE(ABORT, 'Stage receipt feedback proof is invalid')
        WHEN NEW.cause = 'AUTHORIZE_PUBLISH' AND NOT EXISTS (
            SELECT 1 FROM publish_authorization authorization
            WHERE authorization.id = NEW.proof_id
              AND authorization.task_id = NEW.task_id
              AND authorization.task_epoch = NEW.subject_task_epoch
              AND authorization.local_development_stage_id = NEW.stage_id
              AND authorization.stage_generation = NEW.subject_stage_generation
              AND authorization.authorized_operation_id = NEW.subject_operation_id
              AND authorization.authorized_attempt = NEW.subject_attempt
              AND authorization.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND authorization.head_sha IS NEW.subject_expected_head_sha
              AND authorization.base_sha IS NEW.subject_expected_base_sha
              AND authorization.revoked_at_ms IS NULL
              AND authorization.consumed_at_ms IS NULL
              AND NEW.returned_pending_task_epoch IS NEW.subject_task_epoch
              AND NEW.returned_pending_stage_id IS NEW.subject_stage_id
              AND NEW.returned_pending_stage_generation
                    IS NEW.subject_stage_generation
              AND NEW.returned_pending_operation_id IS NEW.subject_operation_id
              AND NEW.returned_pending_attempt IS NEW.subject_attempt
              AND NEW.returned_pending_code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND NEW.returned_pending_head_sha IS NEW.subject_expected_head_sha
              AND NEW.returned_pending_base_sha IS NEW.subject_expected_base_sha)
            THEN RAISE(ABORT, 'Stage receipt publish authorization proof is invalid')
        WHEN NEW.cause = 'OPEN_REMOTE_DEVELOPMENT' AND NOT EXISTS (
            SELECT 1 FROM remote_pr_binding binding
            JOIN publish_operation operation
              ON operation.id = binding.publish_operation_id
            WHERE binding.task_id = NEW.task_id
              AND operation.local_development_stage_id = NEW.subject_stage_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.stage_generation = NEW.subject_stage_generation
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.expected_head_sha IS NEW.subject_expected_head_sha
              AND operation.expected_base_sha IS NEW.subject_expected_base_sha
              AND operation.status = 'SUCCEEDED'
              AND NEW.returned_kind = 'REMOTE_DEVELOPMENT'
              AND NEW.returned_checkpoint = 'WAITING_CI')
            THEN RAISE(ABORT, 'Stage receipt remote opening proof is invalid')
        WHEN NEW.cause = 'ACCEPT_PUBLISH_FAILURE' AND NOT EXISTS (
            SELECT 1
            FROM publish_operation operation
            JOIN publish_authorization authorization
              ON authorization.id = operation.publish_authorization_id
            WHERE operation.local_development_stage_id = NEW.stage_id
              AND operation.task_id = NEW.task_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.stage_generation = NEW.subject_stage_generation
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.expected_head_sha IS NEW.subject_expected_head_sha
              AND operation.expected_base_sha IS NEW.subject_expected_base_sha
              AND ((operation.status = 'FAILED'
                    AND authorization.revoked_at_ms IS NOT NULL
                    AND authorization.consumed_at_ms IS NULL
                    AND authorization.outcome IS NULL)
                OR (operation.status = 'CANCELED'
                    AND authorization.revoked_at_ms IS NULL
                    AND authorization.consumed_at_ms IS NOT NULL
                    AND authorization.outcome = 'CANCELED'))
              AND (NEW.disposition = 'SUPERSEDED'
                OR (NEW.returned_kind = 'LOCAL_DEVELOPMENT'
                    AND NEW.returned_checkpoint = 'LOCAL_REVIEW'
                    AND NEW.returned_end_reason IS NULL
                    AND NEW.returned_pending_operation_id IS NULL)))
            THEN RAISE(ABORT, 'Stage receipt publish failure proof is invalid')
        WHEN NEW.cause = 'ACCEPT_PUBLISHED' AND NOT EXISTS (
            SELECT 1 FROM remote_pr_binding binding
            JOIN publish_operation operation
              ON operation.id = binding.publish_operation_id
            WHERE binding.task_id = NEW.task_id
              AND operation.local_development_stage_id = NEW.stage_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.stage_generation = NEW.subject_stage_generation
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.expected_head_sha IS NEW.subject_expected_head_sha
              AND operation.expected_base_sha IS NEW.subject_expected_base_sha
              AND operation.status = 'SUCCEEDED')
            THEN RAISE(ABORT, 'Stage receipt remote binding proof is invalid')
        WHEN NEW.disposition = 'APPLIED'
              AND NEW.returned_checkpoint = 'PUBLISHING'
              AND NEW.cause <> 'AUTHORIZE_PUBLISH'
            THEN RAISE(ABORT, 'LOCAL_REVIEW to PUBLISHING requires PublishAuthorization')
    END;
END;
CREATE TRIGGER stage_command_receipt_local_id_collision
BEFORE INSERT ON stage_command_receipt
WHEN EXISTS (SELECT 1 FROM local_stage_command_receipt local
             WHERE local.stage_id = NEW.stage_id
               AND local.command_id = NEW.command_id)
BEGIN SELECT RAISE(ABORT, 'Stage command id is already used by Local Development'); END;
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
