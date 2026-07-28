-- Runtime production for the typed Task control proofs installed in V230.
-- Cancellation advances the Task epoch, so quiescence must include live work
-- from every invalidated earlier epoch, not only the Task's current epoch.

PRAGMA foreign_keys = OFF;
SAVEPOINT v256_task_control_runtime;

-- Older REQUEST_CANCEL commits stored an internal cause name and no source
-- identity, so the exact V234 terminal-acceptance trigger could never consume
-- them. Normalize only rows backed by their immutable CANCELING transition.
DROP TRIGGER task_terminal_intent_immutable;
UPDATE task_terminal_intent AS intent
   SET source = 'USER_CANCEL',
       source_id = (
           SELECT transition.command_id
             FROM task_transition transition
            WHERE transition.task_id = intent.task_id
              AND transition.cause = 'REQUEST_CANCEL'
              AND transition.to_state = 'CANCELING'
            ORDER BY transition.aggregate_version DESC
            LIMIT 1)
 WHERE intent.accepted = 1
   AND intent.kind = 'CANCELED'
   AND intent.source = 'REQUEST_CANCEL';

CREATE TEMP TABLE v256_cancel_intent_audit (
    invalid_count INTEGER NOT NULL CHECK (invalid_count = 0)
);
INSERT INTO v256_cancel_intent_audit
SELECT COUNT(*)
  FROM task_terminal_intent intent
 WHERE intent.accepted = 1 AND intent.kind = 'CANCELED'
   AND (intent.source <> 'USER_CANCEL' OR intent.source_id IS NULL
     OR NOT EXISTS (
         SELECT 1 FROM task_transition transition
          WHERE transition.task_id = intent.task_id
            AND transition.command_id = intent.source_id
            AND transition.cause = 'REQUEST_CANCEL'
            AND transition.to_state = 'CANCELING'));
DROP TABLE v256_cancel_intent_audit;

CREATE TRIGGER task_terminal_intent_immutable
BEFORE UPDATE ON task_terminal_intent
BEGIN SELECT RAISE(ABORT, 'task terminal intent is immutable'); END;

-- V234's table trigger correctly derives NO_REMOTE_PR for a canceled Task that
-- never reached Remote Development, but its table CHECK accidentally required
-- PRESERVE_OPEN for every cancellation. Rebuild the table transactionally so
-- cancellation works at Plan and Local Development as well as after a PR.
PRAGMA legacy_alter_table = ON;
ALTER TABLE cleanup_stage RENAME TO cleanup_stage_v234;

CREATE TABLE cleanup_stage (
    stage_id                     TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage(id) ON DELETE CASCADE,
    task_id                      TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                   INTEGER NOT NULL CHECK (task_epoch > 0),
    generation                   INTEGER NOT NULL CHECK (generation > 0),
    terminal_acceptance_id       TEXT    NOT NULL UNIQUE
        REFERENCES task_terminal_acceptance(id),
    task_terminal_intent_id      TEXT    NOT NULL UNIQUE
        REFERENCES task_terminal_intent(id),
    terminal_reason              TEXT    NOT NULL CHECK (terminal_reason IN (
        'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')),
    task_policy_revision_id      TEXT    NOT NULL REFERENCES task_policy_revision(id),
    remote_pr_binding_id         TEXT REFERENCES remote_pr_binding(id),
    remote_pr_disposition        TEXT    NOT NULL CHECK (remote_pr_disposition IN (
        'PRESERVE_OPEN', 'REMOTE_ALREADY_TERMINAL', 'NO_REMOTE_PR')),
    local_branch_requirement     TEXT    NOT NULL CHECK (local_branch_requirement IN (
        'REQUIRED', 'NOT_APPLICABLE')),
    remote_branch_requirement    TEXT    NOT NULL CHECK (remote_branch_requirement IN (
        'REQUIRED', 'OPTIONAL', 'NOT_APPLICABLE')),
    opened_at_ms                 INTEGER NOT NULL,
    UNIQUE (stage_id, task_id, task_epoch, generation),
    CHECK (terminal_reason <> 'CANCELED'
        OR remote_branch_requirement = 'NOT_APPLICABLE'),
    CHECK ((remote_pr_binding_id IS NULL)
        = (remote_pr_disposition = 'NO_REMOTE_PR'))
);

INSERT INTO cleanup_stage
SELECT * FROM cleanup_stage_v234;
DROP TABLE cleanup_stage_v234;

CREATE TRIGGER cleanup_stage_insert
BEFORE INSERT ON cleanup_stage
WHEN NOT EXISTS (
    SELECT 1 FROM stage owner
    JOIN tasks task ON task.id = owner.task_id
    JOIN task_current_stage current ON current.stage_id = owner.id
    JOIN task_terminal_acceptance acceptance
      ON acceptance.id = NEW.terminal_acceptance_id
    JOIN task_terminal_intent intent ON intent.id = acceptance.task_terminal_intent_id
    JOIN task_policy_revision policy ON policy.id = NEW.task_policy_revision_id
    WHERE owner.id = NEW.stage_id
      AND owner.task_id = NEW.task_id
      AND owner.kind = 'CLEANUP'
      AND owner.generation = NEW.generation
      AND owner.checkpoint = 'WAITING_QUIESCENCE'
      AND owner.completed_at_ms IS NULL
      AND current.task_id = NEW.task_id
      AND current.stage_generation = NEW.generation
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'CLEANING'
      AND task.epoch = NEW.task_epoch
      AND task.policy_revision_id = policy.id
      AND acceptance.task_id = NEW.task_id
      AND acceptance.task_epoch = NEW.task_epoch
      AND acceptance.kind = NEW.terminal_reason
      AND intent.id = NEW.task_terminal_intent_id
      AND policy.trunk_id = task.thread_id
      AND (NEW.remote_pr_binding_id IS NULL OR EXISTS (
          SELECT 1 FROM remote_pr_binding binding
          WHERE binding.id = NEW.remote_pr_binding_id
            AND binding.task_id = NEW.task_id))
      AND NEW.remote_pr_disposition = CASE
          WHEN NEW.remote_pr_binding_id IS NULL THEN 'NO_REMOTE_PR'
          WHEN NEW.terminal_reason = 'CANCELED' THEN 'PRESERVE_OPEN'
          ELSE 'REMOTE_ALREADY_TERMINAL' END
      AND NEW.local_branch_requirement = CASE
          WHEN policy.delete_local_branch_on_cleanup = 1 THEN 'REQUIRED'
          ELSE 'NOT_APPLICABLE' END
      AND NEW.remote_branch_requirement = CASE
          WHEN NEW.remote_pr_binding_id IS NULL OR NEW.terminal_reason = 'CANCELED'
              THEN 'NOT_APPLICABLE'
          WHEN policy.require_remote_branch_cleanup = 1 THEN 'REQUIRED'
          ELSE 'OPTIONAL' END)
BEGIN SELECT RAISE(ABORT, 'Cleanup Stage lacks current accepted terminal ownership'); END;

CREATE TRIGGER cleanup_stage_immutable
BEFORE UPDATE ON cleanup_stage
BEGIN SELECT RAISE(ABORT, 'Cleanup Stage identity is immutable'); END;

PRAGMA legacy_alter_table = OFF;

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
          AND execution.status IN ('STARTING', 'RUNNING', 'UNKNOWN'))
           AS active_agent_execution_count
FROM tasks task
WHERE task.workflow_version = 'V2';

DROP TRIGGER task_quiescence_satisfied_fence;
CREATE TRIGGER task_quiescence_satisfied_fence
BEFORE UPDATE OF status ON task_quiescence_barrier
WHEN NEW.status = 'SATISFIED'
  AND (NEW.completed_at_ms IS NULL
    OR NEW.completed_at_ms > CAST(
        (julianday('now') - 2440587.5) * 86400000 AS INTEGER) + 1000
    OR EXISTS (
        SELECT 1 FROM task_control_live_work_v256 live
        WHERE live.task_id = NEW.task_id
          AND (live.active_task_turn_count <> 0
            OR live.active_stage_turn_count <> 0
            OR live.active_review_turn_count <> 0
            OR live.active_dispatch_count <> 0
            OR live.active_agent_execution_count <> 0))
    OR EXISTS (
        SELECT 1 FROM capacity_lease lease
        WHERE lease.workflow_source = 'V2'
          AND lease.task_id = NEW.task_id
          AND lease.released_at_ms IS NULL
          AND lease.expires_at_ms > NEW.completed_at_ms)
    OR EXISTS (
        SELECT 1 FROM worktree_leases lease
        WHERE lease.workflow_version = 'V2'
          AND lease.task_id = NEW.task_id
          AND lease.expires_at_ms > NEW.completed_at_ms))
BEGIN SELECT RAISE(ABORT, 'quiescence requires all Task work across epochs to stop'); END;

CREATE TRIGGER task_pause_evidence_v256_all_epoch_fence
BEFORE INSERT ON task_pause_evidence
WHEN EXISTS (
    SELECT 1 FROM task_control_live_work_v256 live
    WHERE live.task_id = NEW.task_id
      AND (live.active_task_turn_count <> 0
        OR live.active_stage_turn_count <> 0
        OR live.active_review_turn_count <> 0
        OR live.active_dispatch_count <> 0
        OR live.active_agent_execution_count <> 0))
  OR EXISTS (
      SELECT 1 FROM capacity_lease lease
      WHERE lease.workflow_source = 'V2' AND lease.task_id = NEW.task_id
        AND lease.released_at_ms IS NULL
        AND lease.expires_at_ms > NEW.recorded_at_ms)
  OR EXISTS (
      SELECT 1 FROM worktree_leases lease
      WHERE lease.workflow_version = 'V2' AND lease.task_id = NEW.task_id
        AND lease.expires_at_ms > NEW.recorded_at_ms)
BEGIN SELECT RAISE(ABORT, 'PauseEvidence requires all Task work across epochs to stop'); END;

CREATE TRIGGER task_archive_liveness_v256_all_epoch_fence
BEFORE INSERT ON task_archive_liveness
WHEN EXISTS (
    SELECT 1 FROM task_control_live_work_v256 live
    WHERE live.task_id = NEW.task_id
      AND (live.active_task_turn_count <> 0
        OR live.active_stage_turn_count <> 0
        OR live.active_review_turn_count <> 0
        OR live.active_dispatch_count <> 0
        OR live.active_agent_execution_count <> 0))
  OR EXISTS (
      SELECT 1 FROM capacity_lease lease
      WHERE lease.workflow_source = 'V2' AND lease.task_id = NEW.task_id
        AND lease.released_at_ms IS NULL
        AND lease.expires_at_ms > NEW.recorded_at_ms)
  OR EXISTS (
      SELECT 1 FROM worktree_leases lease
      WHERE lease.workflow_version = 'V2' AND lease.task_id = NEW.task_id
        AND lease.expires_at_ms > NEW.recorded_at_ms)
BEGIN SELECT RAISE(ABORT, 'ArchiveEvidence requires all Task work across epochs to stop'); END;

-- Resume from PAUSED and ARCHIVED uses one exact predecessor proof. V230's
-- table accepted only PauseEvidence, so a separate typed row is required for
-- the already-supported ARCHIVED -> RESUMING transition.
CREATE TABLE task_resume_reconciliation_v256 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    source_kind                 TEXT    NOT NULL CHECK (source_kind IN ('PAUSE', 'ARCHIVE')),
    source_id                   TEXT    NOT NULL,
    stage_id                    TEXT    NOT NULL REFERENCES stage(id),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    restore_checkpoint          TEXT    NOT NULL,
    code_fingerprint            TEXT    NOT NULL,
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    reconciliation_digest       TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status = 'SATISFIED'),
    recorded_at_ms              INTEGER NOT NULL CHECK (recorded_at_ms > 0),
    UNIQUE (source_kind, source_id),
    CHECK (length(trim(reconciliation_digest)) > 0)
);

CREATE VIEW task_resume_evidence_digest_v256 AS
SELECT evidence.id,
       'resume:v2:' || lower(hex(evidence.task_id)) || ':' || evidence.task_epoch || ':'
           || lower(hex(evidence.id)) || ':' || lower(hex(evidence.source_kind)) || ':'
           || lower(hex(evidence.source_id)) || ':' || lower(hex(evidence.stage_id)) || ':'
           || evidence.stage_generation || ':' || lower(hex(evidence.restore_checkpoint)) || ':'
           || lower(hex(evidence.code_fingerprint)) || ':' || lower(hex(evidence.head_sha)) || ':'
           || lower(hex(evidence.base_sha)) || ':' || evidence.recorded_at_ms
           AS content_digest
FROM task_resume_reconciliation_v256 evidence;

CREATE TRIGGER task_resume_reconciliation_v256_insert
BEFORE INSERT ON task_resume_reconciliation_v256
WHEN NEW.recorded_at_ms > CAST(
        (julianday('now') - 2440587.5) * 86400000 AS INTEGER) + 1000
  OR NEW.reconciliation_digest <> (
       'resume:v2:' || lower(hex(NEW.task_id)) || ':' || NEW.task_epoch || ':'
       || lower(hex(NEW.id)) || ':' || lower(hex(NEW.source_kind)) || ':'
       || lower(hex(NEW.source_id)) || ':' || lower(hex(NEW.stage_id)) || ':'
       || NEW.stage_generation || ':' || lower(hex(NEW.restore_checkpoint)) || ':'
       || lower(hex(NEW.code_fingerprint)) || ':' || lower(hex(NEW.head_sha)) || ':'
       || lower(hex(NEW.base_sha)) || ':' || NEW.recorded_at_ms)
  OR NOT EXISTS (
      SELECT 1
      FROM tasks task
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN task_current_code_subject_v230 code ON code.task_id = task.id
      JOIN task_transition transition ON transition.task_id = task.id
      JOIN task_control_live_work_v256 live ON live.task_id = task.id
      WHERE task.id = NEW.task_id AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'RESUMING' AND task.epoch = NEW.task_epoch
        AND current.stage_id = NEW.stage_id
        AND current.stage_generation = NEW.stage_generation
        AND owner.generation = NEW.stage_generation
        AND owner.checkpoint = NEW.restore_checkpoint
        AND owner.completed_at_ms IS NULL
        AND code.code_fingerprint = NEW.code_fingerprint
        AND code.head_sha = NEW.head_sha AND code.base_sha = NEW.base_sha
        AND transition.aggregate_version = task.aggregate_version
        AND transition.to_state = 'RESUMING'
        AND transition.from_state = CASE NEW.source_kind
            WHEN 'PAUSE' THEN 'PAUSED' ELSE 'ARCHIVED' END
        AND live.active_task_turn_count = 0
        AND live.active_stage_turn_count = 0
        AND live.active_review_turn_count = 0
        AND live.active_dispatch_count = 0
        AND live.active_agent_execution_count = 0
        AND ((NEW.source_kind = 'PAUSE' AND EXISTS (
              SELECT 1 FROM task_pause_evidence evidence
              WHERE evidence.barrier_id = NEW.source_id
                AND evidence.task_id = NEW.task_id
                AND evidence.task_epoch = NEW.task_epoch
                AND evidence.stage_id = NEW.stage_id
                AND evidence.stage_generation = NEW.stage_generation
                AND evidence.restore_checkpoint = NEW.restore_checkpoint
                AND evidence.status = 'SATISFIED'))
          OR (NEW.source_kind = 'ARCHIVE' AND EXISTS (
              SELECT 1 FROM task_archive_liveness evidence
              WHERE evidence.id = NEW.source_id
                AND evidence.task_id = NEW.task_id
                AND evidence.task_epoch = NEW.task_epoch
                AND evidence.stage_id = NEW.stage_id
                AND evidence.stage_generation = NEW.stage_generation
                AND evidence.status = 'SATISFIED')))
        AND NOT EXISTS (
            SELECT 1 FROM capacity_lease lease
            WHERE lease.workflow_source = 'V2' AND lease.task_id = NEW.task_id
              AND lease.writer_required = 1 AND lease.released_at_ms IS NULL
              AND lease.expires_at_ms > NEW.recorded_at_ms)
        AND NOT EXISTS (
            SELECT 1 FROM worktree_leases lease
            WHERE lease.workflow_version = 'V2' AND lease.task_id = NEW.task_id
              AND lease.expires_at_ms > NEW.recorded_at_ms))
BEGIN SELECT RAISE(ABORT, 'ResumeEvidence lacks exact stopped predecessor proof'); END;

CREATE TRIGGER task_resume_reconciliation_v256_immutable
BEFORE UPDATE ON task_resume_reconciliation_v256
BEGIN SELECT RAISE(ABORT, 'ResumeEvidence is immutable'); END;

-- A stopped Task does not become ACTIVE until the exact current Stage owner
-- has durably accepted responsibility for rearming its parked checkpoint.
-- The owner-specific row is intentionally outside this table: this handoff is
-- the Task/Stage boundary, not a generic recreation of arbitrary Stage work.
CREATE TABLE task_resume_handoff_v256 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    task_version                INTEGER NOT NULL CHECK (task_version >= 0),
    reconciliation_id           TEXT    NOT NULL UNIQUE,
    stage_id                    TEXT    NOT NULL REFERENCES stage(id),
    stage_kind                  TEXT    NOT NULL CHECK (stage_kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT')),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    stage_version               INTEGER NOT NULL CHECK (stage_version >= 0),
    restore_checkpoint          TEXT    NOT NULL,
    code_fingerprint            TEXT    NOT NULL,
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    request_digest              TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED')),
    owner_proof_id              TEXT,
    accepted_by                 TEXT,
    accepted_at_ms              INTEGER,
    created_at_ms               INTEGER NOT NULL CHECK (created_at_ms > 0),
    UNIQUE (task_id, task_version),
    CHECK (length(trim(reconciliation_id)) > 0
        AND length(trim(restore_checkpoint)) > 0
        AND length(trim(code_fingerprint)) > 0
        AND length(trim(head_sha)) > 0
        AND length(trim(base_sha)) > 0
        AND length(trim(request_digest)) > 0),
    CHECK ((status = 'PENDING'
            AND owner_proof_id IS NULL AND accepted_by IS NULL
            AND accepted_at_ms IS NULL)
        OR (status = 'ACCEPTED'
            AND owner_proof_id IS NOT NULL AND length(trim(owner_proof_id)) > 0
            AND accepted_by IS NOT NULL AND length(trim(accepted_by)) > 0
            AND accepted_at_ms IS NOT NULL
            AND accepted_at_ms >= created_at_ms))
);

CREATE VIEW task_resume_handoff_digest_v256 AS
SELECT handoff.id,
       'resume-handoff:v1:' || lower(hex(handoff.id)) || ':'
           || lower(hex(handoff.task_id)) || ':' || handoff.task_epoch || ':'
           || handoff.task_version || ':' || lower(hex(handoff.reconciliation_id)) || ':'
           || lower(hex(handoff.stage_id)) || ':' || lower(hex(handoff.stage_kind)) || ':'
           || handoff.stage_generation || ':' || handoff.stage_version || ':'
           || lower(hex(handoff.restore_checkpoint)) || ':'
           || lower(hex(handoff.code_fingerprint)) || ':'
           || lower(hex(handoff.head_sha)) || ':' || lower(hex(handoff.base_sha)) || ':'
           || handoff.created_at_ms AS content_digest
FROM task_resume_handoff_v256 handoff;

CREATE TRIGGER task_resume_handoff_v256_insert
BEFORE INSERT ON task_resume_handoff_v256
WHEN NEW.status <> 'PENDING'
  OR NEW.created_at_ms > CAST(
        (julianday('now') - 2440587.5) * 86400000 AS INTEGER) + 1000
  OR NEW.request_digest <> (
       'resume-handoff:v1:' || lower(hex(NEW.id)) || ':'
       || lower(hex(NEW.task_id)) || ':' || NEW.task_epoch || ':'
       || NEW.task_version || ':' || lower(hex(NEW.reconciliation_id)) || ':'
       || lower(hex(NEW.stage_id)) || ':' || lower(hex(NEW.stage_kind)) || ':'
       || NEW.stage_generation || ':' || NEW.stage_version || ':'
       || lower(hex(NEW.restore_checkpoint)) || ':'
       || lower(hex(NEW.code_fingerprint)) || ':'
       || lower(hex(NEW.head_sha)) || ':' || lower(hex(NEW.base_sha)) || ':'
       || NEW.created_at_ms)
  OR NOT EXISTS (
      SELECT 1
      FROM tasks task
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN task_current_code_subject_v230 code ON code.task_id = task.id
      JOIN task_control_live_work_v256 live ON live.task_id = task.id
      WHERE task.id = NEW.task_id AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'RESUMING'
        AND task.epoch = NEW.task_epoch
        AND task.aggregate_version = NEW.task_version
        AND current.stage_id = NEW.stage_id
        AND current.stage_generation = NEW.stage_generation
        AND owner.kind = NEW.stage_kind
        AND owner.generation = NEW.stage_generation
        AND owner.version = NEW.stage_version
        AND owner.checkpoint = NEW.restore_checkpoint
        AND owner.completed_at_ms IS NULL
        AND code.code_fingerprint = NEW.code_fingerprint
        AND code.head_sha = NEW.head_sha AND code.base_sha = NEW.base_sha
        AND live.active_task_turn_count = 0
        AND live.active_stage_turn_count = 0
        AND live.active_review_turn_count = 0
        AND live.active_dispatch_count = 0
        AND live.active_agent_execution_count = 0
        AND NOT EXISTS (
            SELECT 1 FROM capacity_lease lease
            WHERE lease.workflow_source = 'V2' AND lease.task_id = NEW.task_id
              AND lease.released_at_ms IS NULL
              AND lease.expires_at_ms > NEW.created_at_ms)
        AND NOT EXISTS (
            SELECT 1 FROM worktree_leases lease
            WHERE lease.workflow_version = 'V2' AND lease.task_id = NEW.task_id
              AND lease.expires_at_ms > NEW.created_at_ms)
        AND (EXISTS (
              SELECT 1 FROM task_resume_reconciliation_v256 evidence
              JOIN task_resume_evidence_digest_v256 digest ON digest.id = evidence.id
              WHERE evidence.id = NEW.reconciliation_id
                AND evidence.task_id = NEW.task_id
                AND evidence.task_epoch = NEW.task_epoch
                AND evidence.stage_id = NEW.stage_id
                AND evidence.stage_generation = NEW.stage_generation
                AND evidence.restore_checkpoint = NEW.restore_checkpoint
                AND evidence.code_fingerprint = NEW.code_fingerprint
                AND evidence.head_sha = NEW.head_sha
                AND evidence.base_sha = NEW.base_sha
                AND evidence.reconciliation_digest = digest.content_digest
                AND evidence.status = 'SATISFIED')
          OR EXISTS (
              SELECT 1 FROM task_resume_reconciliation evidence
              JOIN task_resume_evidence_digest_v230 digest ON digest.id = evidence.id
              WHERE evidence.id = NEW.reconciliation_id
                AND evidence.task_id = NEW.task_id
                AND evidence.task_epoch = NEW.task_epoch
                AND evidence.stage_id = NEW.stage_id
                AND evidence.stage_generation = NEW.stage_generation
                AND evidence.restore_checkpoint = NEW.restore_checkpoint
                AND evidence.paused_code_fingerprint = NEW.code_fingerprint
                AND evidence.paused_head_sha = NEW.head_sha
                AND evidence.paused_base_sha = NEW.base_sha
                AND evidence.reconciliation_digest = digest.content_digest
                AND evidence.status = 'SATISFIED')))
BEGIN SELECT RAISE(ABORT, 'Resume handoff lacks exact current Stage ownership'); END;

CREATE TRIGGER task_resume_handoff_v256_accept
BEFORE UPDATE ON task_resume_handoff_v256
WHEN NOT (
    OLD.status = 'PENDING' AND NEW.status = 'ACCEPTED'
    AND NEW.id IS OLD.id AND NEW.task_id IS OLD.task_id
    AND NEW.task_epoch IS OLD.task_epoch AND NEW.task_version IS OLD.task_version
    AND NEW.reconciliation_id IS OLD.reconciliation_id
    AND NEW.stage_id IS OLD.stage_id AND NEW.stage_kind IS OLD.stage_kind
    AND NEW.stage_generation IS OLD.stage_generation
    AND NEW.stage_version IS OLD.stage_version
    AND NEW.restore_checkpoint IS OLD.restore_checkpoint
    AND NEW.code_fingerprint IS OLD.code_fingerprint
    AND NEW.head_sha IS OLD.head_sha AND NEW.base_sha IS OLD.base_sha
    AND NEW.request_digest IS OLD.request_digest
    AND NEW.created_at_ms IS OLD.created_at_ms
    AND NEW.owner_proof_id IS NOT NULL AND length(trim(NEW.owner_proof_id)) > 0
    AND NEW.accepted_by IS NOT NULL AND length(trim(NEW.accepted_by)) > 0
    AND NEW.accepted_at_ms IS NOT NULL
    AND NEW.accepted_at_ms >= OLD.created_at_ms
    AND NEW.accepted_at_ms <= CAST(
        (julianday('now') - 2440587.5) * 86400000 AS INTEGER) + 1000
    AND EXISTS (
        SELECT 1 FROM tasks task
        JOIN task_current_stage current ON current.task_id = task.id
        JOIN stage owner ON owner.id = current.stage_id
        JOIN task_current_code_subject_v230 code ON code.task_id = task.id
        JOIN task_resume_handoff_digest_v256 digest ON digest.id = OLD.id
        WHERE task.id = OLD.task_id AND task.workflow_version = 'V2'
          AND task.lifecycle_state = 'RESUMING'
          AND task.epoch = OLD.task_epoch
          AND task.aggregate_version = OLD.task_version
          AND current.stage_id = OLD.stage_id
          AND current.stage_generation = OLD.stage_generation
          AND owner.kind = OLD.stage_kind
          AND owner.generation = OLD.stage_generation
          AND owner.version = OLD.stage_version
          AND owner.checkpoint = OLD.restore_checkpoint
          AND owner.completed_at_ms IS NULL
          AND code.code_fingerprint = OLD.code_fingerprint
          AND code.head_sha = OLD.head_sha AND code.base_sha = OLD.base_sha
          AND OLD.request_digest = digest.content_digest))
BEGIN SELECT RAISE(ABORT, 'Resume handoff acceptance is not exact'); END;

-- V230's receipt fence knows only its pause-backed ResumeEvidence table.
-- Replace it so a completion can consume either an already-persisted V230
-- proof or the exact pause/archive predecessor proof above. Pause and archive
-- retain their typed digest checks and gain the all-epoch live-work fence.
DROP TRIGGER task_command_receipt_v230_control_proof;
CREATE TRIGGER task_command_receipt_v256_control_proof
BEFORE INSERT ON task_command_receipt
WHEN NEW.cause IN ('COMPLETE_PAUSE', 'COMPLETE_RESUME', 'COMPLETE_ARCHIVE')
BEGIN
    SELECT CASE
        WHEN NEW.cause = 'COMPLETE_PAUSE' AND NOT EXISTS (
            SELECT 1 FROM task_pause_evidence evidence
            JOIN task_pause_evidence_digest_v230 digest
              ON digest.barrier_id = evidence.barrier_id
            JOIN task_current_stage current ON current.task_id = evidence.task_id
            JOIN task_control_live_work_v256 live ON live.task_id = evidence.task_id
            WHERE evidence.barrier_id = NEW.proof_id
              AND evidence.task_id = NEW.task_id
              AND evidence.task_epoch = NEW.expected_task_epoch
              AND evidence.task_epoch = NEW.returned_epoch
              AND evidence.stage_id = NEW.returned_current_stage_id
              AND current.stage_id = evidence.stage_id
              AND current.stage_generation = evidence.stage_generation
              AND evidence.stop_evidence_digest = digest.content_digest
              AND evidence.status = 'SATISFIED'
              AND live.active_task_turn_count = 0
              AND live.active_stage_turn_count = 0
              AND live.active_review_turn_count = 0
              AND live.active_dispatch_count = 0
              AND live.active_agent_execution_count = 0
              AND NEW.returned_lifecycle = 'PAUSED')
            THEN RAISE(ABORT, 'Task receipt PauseEvidence is not exact')
        WHEN NEW.cause = 'COMPLETE_RESUME' AND NOT (
            (EXISTS (
                SELECT 1 FROM task_resume_reconciliation_v256 evidence
                JOIN task_resume_evidence_digest_v256 digest ON digest.id = evidence.id
                JOIN task_current_stage current ON current.task_id = evidence.task_id
                JOIN task_control_live_work_v256 live ON live.task_id = evidence.task_id
                WHERE evidence.id = NEW.proof_id
                  AND evidence.task_id = NEW.task_id
                  AND evidence.task_epoch = NEW.expected_task_epoch
                  AND evidence.task_epoch = NEW.returned_epoch
                  AND evidence.stage_id = NEW.returned_current_stage_id
                  AND current.stage_id = evidence.stage_id
                  AND current.stage_generation = evidence.stage_generation
                  AND evidence.reconciliation_digest = digest.content_digest
                  AND evidence.status = 'SATISFIED'
                  AND live.active_task_turn_count = 0
                  AND live.active_stage_turn_count = 0
                  AND live.active_review_turn_count = 0
                  AND live.active_dispatch_count = 0
                  AND live.active_agent_execution_count = 0
                  AND NEW.returned_lifecycle = 'ACTIVE')
            OR EXISTS (
                SELECT 1 FROM task_resume_reconciliation evidence
                JOIN task_resume_evidence_digest_v230 digest ON digest.id = evidence.id
                JOIN task_current_stage current ON current.task_id = evidence.task_id
                JOIN task_control_live_work_v256 live ON live.task_id = evidence.task_id
                WHERE evidence.id = NEW.proof_id
                  AND evidence.task_id = NEW.task_id
                  AND evidence.task_epoch = NEW.expected_task_epoch
                  AND evidence.task_epoch = NEW.returned_epoch
                  AND evidence.stage_id = NEW.returned_current_stage_id
                  AND current.stage_id = evidence.stage_id
                  AND current.stage_generation = evidence.stage_generation
                  AND evidence.reconciliation_digest = digest.content_digest
                  AND evidence.status = 'SATISFIED'
                  AND live.active_task_turn_count = 0
                  AND live.active_stage_turn_count = 0
                  AND live.active_review_turn_count = 0
                  AND live.active_dispatch_count = 0
                  AND live.active_agent_execution_count = 0
                  AND NEW.returned_lifecycle = 'ACTIVE'))
            AND EXISTS (
                SELECT 1 FROM task_resume_handoff_v256 handoff
                JOIN task_resume_handoff_digest_v256 digest ON digest.id = handoff.id
                JOIN task_current_stage current ON current.task_id = handoff.task_id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = handoff.task_id
                WHERE handoff.reconciliation_id = NEW.proof_id
                  AND handoff.task_id = NEW.task_id
                  AND handoff.task_epoch = NEW.expected_task_epoch
                  AND handoff.task_epoch = NEW.returned_epoch
                  AND handoff.task_version = NEW.expected_task_version
                  AND handoff.stage_id = NEW.returned_current_stage_id
                  AND current.stage_id = handoff.stage_id
                  AND current.stage_generation = handoff.stage_generation
                  AND owner.kind = handoff.stage_kind
                  AND owner.generation = handoff.stage_generation
                  AND owner.version = handoff.stage_version
                  AND owner.checkpoint = handoff.restore_checkpoint
                  AND owner.completed_at_ms IS NULL
                  AND code.code_fingerprint = handoff.code_fingerprint
                  AND code.head_sha = handoff.head_sha
                  AND code.base_sha = handoff.base_sha
                  AND handoff.request_digest = digest.content_digest
                  AND handoff.status = 'ACCEPTED'))
            THEN RAISE(ABORT, 'Task receipt ResumeEvidence is not exact')
        WHEN NEW.cause = 'COMPLETE_ARCHIVE' AND NOT EXISTS (
            SELECT 1 FROM task_archive_liveness evidence
            JOIN task_archive_evidence_digest_v230 digest ON digest.id = evidence.id
            JOIN task_current_stage current ON current.task_id = evidence.task_id
            JOIN task_control_live_work_v256 live ON live.task_id = evidence.task_id
            WHERE evidence.id = NEW.proof_id
              AND evidence.task_id = NEW.task_id
              AND evidence.task_epoch = NEW.expected_task_epoch
              AND evidence.task_epoch = NEW.returned_epoch
              AND evidence.stage_id = NEW.returned_current_stage_id
              AND current.stage_id = evidence.stage_id
              AND current.stage_generation = evidence.stage_generation
              AND evidence.liveness_digest = digest.content_digest
              AND evidence.status = 'SATISFIED'
              AND live.active_task_turn_count = 0
              AND live.active_stage_turn_count = 0
              AND live.active_review_turn_count = 0
              AND live.active_dispatch_count = 0
              AND live.active_agent_execution_count = 0
              AND NEW.returned_lifecycle = 'ARCHIVED')
            THEN RAISE(ABORT, 'Task receipt ArchiveEvidence is not exact')
    END;
END;

RELEASE SAVEPOINT v256_task_control_runtime;
PRAGMA foreign_keys = ON;
