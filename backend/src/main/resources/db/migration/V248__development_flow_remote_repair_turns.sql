-- Typed CI and branch-repair Turns.  The worktree subject ledger is the
-- single exact local-code fence while Remote PR truth remains independently
-- owned by remote_development_stage.

CREATE TABLE remote_worktree_subject (
    id                          TEXT    NOT NULL PRIMARY KEY,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    revision                    INTEGER NOT NULL CHECK (revision > 0),
    source_kind                 TEXT    NOT NULL CHECK (source_kind IN (
        'CI_STAGE_TURN', 'BRANCH_EFFECT', 'BRANCH_STAGE_TURN')),
    source_operation_id         TEXT    NOT NULL UNIQUE,
    code_fingerprint            TEXT    NOT NULL,
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    recorded_at_ms              INTEGER NOT NULL,
    UNIQUE (task_id, task_epoch, revision)
);

CREATE TRIGGER remote_worktree_subject_insert
BEFORE INSERT ON remote_worktree_subject
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_repair_operation operation
    JOIN ci_repair_episode episode
      ON episode.id = operation.ci_repair_episode_id
    WHERE NEW.source_kind = 'CI_STAGE_TURN'
      AND operation.operation_id = NEW.source_operation_id
      AND operation.kind = 'FIX_STAGE_TURN'
      AND operation.status = 'SUCCEEDED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND operation.expected_base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM branch_sync_dispatch_operation operation
    JOIN branch_sync_effect_step step
      ON step.id = operation.branch_sync_effect_step_id
    WHERE NEW.source_kind = 'BRANCH_EFFECT'
      AND operation.operation_id = NEW.source_operation_id
      AND operation.kind IN (
          'MECHANICAL_REBASE', 'VALIDATE', 'FORCE_WITH_LEASE_PUSH')
      AND operation.status = 'SUCCEEDED'
      AND step.status = 'SUCCEEDED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND operation.target_base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM branch_sync_dispatch_operation operation
    JOIN branch_sync_effect_step step
      ON step.id = operation.branch_sync_effect_step_id
    WHERE NEW.source_kind = 'BRANCH_STAGE_TURN'
      AND operation.operation_id = NEW.source_operation_id
      AND operation.kind = 'CONFLICT_REPAIR'
      AND operation.status = 'SUCCEEDED'
      AND step.status = 'SUCCEEDED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND operation.target_base_sha = NEW.base_sha)
BEGIN SELECT RAISE(ABORT, 'Worktree subject lacks exact successful repair evidence'); END;

CREATE TRIGGER remote_worktree_subject_revision_insert
BEFORE INSERT ON remote_worktree_subject
WHEN NEW.revision <> COALESCE((
    SELECT MAX(previous.revision) + 1
    FROM remote_worktree_subject previous
    WHERE previous.task_id = NEW.task_id
      AND previous.task_epoch = NEW.task_epoch), 1)
BEGIN SELECT RAISE(ABORT, 'Worktree subject revision is not the next exact revision'); END;

CREATE TRIGGER remote_worktree_subject_immutable
BEFORE UPDATE ON remote_worktree_subject
BEGIN SELECT RAISE(ABORT, 'Worktree subject is immutable'); END;

DROP VIEW task_current_code_subject_v230;
CREATE VIEW task_current_code_subject_v230 AS
SELECT task.id AS task_id,
       COALESCE(worktree.code_fingerprint, remote.code_fingerprint,
                report.code_fingerprint, code.code_fingerprint) AS code_fingerprint,
       COALESCE(worktree.head_sha, remote.head_sha,
                report.head_sha, code.local_head_sha) AS head_sha,
       COALESCE(worktree.base_sha, remote.base_sha,
                report.base_sha, code.base_sha) AS base_sha
FROM tasks task
JOIN task_code_identity code ON code.task_id = task.id
LEFT JOIN dev_report report ON report.id = (
    SELECT candidate.id
    FROM dev_report candidate
    WHERE candidate.workflow_version = 'V2'
      AND candidate.task_id = task.id
    ORDER BY candidate.created_at_ms DESC, candidate.id DESC
    LIMIT 1)
LEFT JOIN remote_code_subject remote ON remote.id = (
    SELECT candidate.id
    FROM remote_code_subject candidate
    WHERE candidate.task_id = task.id
      AND candidate.task_epoch = task.epoch
    ORDER BY candidate.created_at_ms DESC, candidate.id DESC
    LIMIT 1)
LEFT JOIN remote_worktree_subject worktree ON worktree.id = (
    SELECT candidate.id
    FROM remote_worktree_subject candidate
    WHERE candidate.task_id = task.id
      AND candidate.task_epoch = task.epoch
    ORDER BY candidate.revision DESC
    LIMIT 1)
WHERE task.workflow_version = 'V2';

-- A CI Episode keeps its immutable Remote subject.  Local repair operations
-- fence against the worktree ledger, while the Remote arm advances only after
-- an independently accepted observation of the last pushed head.
DROP TRIGGER ci_repair_operation_insert;
CREATE TRIGGER ci_repair_operation_insert
BEFORE INSERT ON ci_repair_operation
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_repair_episode episode
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE episode.id = NEW.ci_repair_episode_id
      AND episode.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.stage_generation = NEW.stage_generation
      AND episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
      AND remote.current_head_sha =
          COALESCE(episode.last_pushed_head_sha, episode.subject_head_sha)
      AND remote.current_base_sha = episode.subject_base_sha
      AND task.epoch = NEW.task_epoch
      AND task.lifecycle_state = 'ACTIVE'
      AND NEW.status = 'REQUESTED'
      AND ((NEW.kind = 'RERUN'
            AND NEW.expected_code_fingerprint IS NULL
            AND NEW.expected_head_sha = remote.current_head_sha
            AND NEW.expected_base_sha = remote.current_base_sha)
        OR (NEW.kind <> 'RERUN'
            AND NEW.expected_code_fingerprint = code.code_fingerprint
            AND NEW.expected_head_sha = code.head_sha
            AND NEW.expected_base_sha = code.base_sha))
      AND (NEW.kind <> 'FIX_STAGE_TURN' OR EXISTS (
          SELECT 1 FROM stage_turn turn
          WHERE turn.id = NEW.stage_turn_id
            AND turn.operation_id = NEW.operation_id
            AND turn.stage_id = NEW.remote_development_stage_id
            AND turn.stage_generation = NEW.stage_generation
            AND turn.task_epoch = NEW.task_epoch
            AND turn.attempt = NEW.semantic_attempt
            AND turn.expected_code_fingerprint =
                NEW.expected_code_fingerprint
            AND turn.expected_head_sha = NEW.expected_head_sha
            AND turn.expected_base_sha = NEW.expected_base_sha
            AND turn.purpose = 'REMOTE_CI_REPAIR'
            AND turn.status = 'QUEUED'))
      AND (NEW.kind <> 'BRAIN_REVIEW' OR EXISTS (
          SELECT 1 FROM task_turn turn
          WHERE turn.id = NEW.task_turn_id
            AND turn.operation_id = NEW.operation_id
            AND turn.task_id = NEW.task_id
            AND turn.task_epoch = NEW.task_epoch
            AND turn.trigger_stage_id = NEW.remote_development_stage_id
            AND turn.trigger_stage_generation = NEW.stage_generation
            AND turn.attempt = NEW.semantic_attempt
            AND turn.expected_code_fingerprint =
                NEW.expected_code_fingerprint
            AND turn.expected_head_sha = NEW.expected_head_sha
            AND turn.expected_base_sha = NEW.expected_base_sha
            AND turn.purpose = 'REMOTE_CI_BRAIN_REVIEW'
            AND turn.status = 'REQUESTED')))
BEGIN SELECT RAISE(ABORT, 'CI repair Operation requires its live exact subjects'); END;

DROP TRIGGER branch_sync_dispatch_operation_insert;
CREATE TRIGGER branch_sync_dispatch_operation_insert
BEFORE INSERT ON branch_sync_dispatch_operation
WHEN NOT EXISTS (
    SELECT 1
    FROM branch_sync_episode episode
    JOIN branch_sync_effect_step step
      ON step.branch_sync_episode_id = episode.id
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE episode.id = NEW.branch_sync_episode_id
      AND step.id = NEW.branch_sync_effect_step_id
      AND step.kind = NEW.kind
      AND step.status = 'REQUESTED'
      AND episode.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.stage_generation = NEW.stage_generation
      AND episode.target_base_sha = NEW.target_base_sha
      AND episode.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED')
      AND remote.current_head_sha = episode.old_head_sha
      AND remote.current_base_sha = episode.observed_base_sha
      AND NEW.expected_head_sha = code.head_sha
      AND NEW.expected_base_sha = code.base_sha
      AND (NEW.expected_code_fingerprint IS NULL
          OR NEW.expected_code_fingerprint = code.code_fingerprint)
      AND task.epoch = NEW.task_epoch
      AND task.lifecycle_state = 'ACTIVE'
      AND NEW.status = 'REQUESTED'
      AND (NEW.kind <> 'CONFLICT_REPAIR' OR EXISTS (
          SELECT 1 FROM stage_turn turn
          WHERE turn.id = NEW.stage_turn_id
            AND turn.operation_id = NEW.operation_id
            AND turn.stage_id = NEW.remote_development_stage_id
            AND turn.stage_generation = NEW.stage_generation
            AND turn.task_epoch = NEW.task_epoch
            AND turn.attempt = NEW.semantic_attempt
            AND turn.expected_code_fingerprint =
                NEW.expected_code_fingerprint
            AND turn.expected_head_sha = NEW.expected_head_sha
            AND turn.expected_base_sha = NEW.expected_base_sha
            AND turn.purpose = 'BRANCH_CONFLICT_REPAIR'
            AND turn.status = 'QUEUED'))
      AND (NEW.kind <> 'BRAIN_REVIEW' OR EXISTS (
          SELECT 1 FROM task_turn turn
          WHERE turn.id = NEW.task_turn_id
            AND turn.operation_id = NEW.operation_id
            AND turn.task_id = NEW.task_id
            AND turn.task_epoch = NEW.task_epoch
            AND turn.trigger_stage_id = NEW.remote_development_stage_id
            AND turn.trigger_stage_generation = NEW.stage_generation
            AND turn.attempt = NEW.semantic_attempt
            AND turn.expected_code_fingerprint =
                NEW.expected_code_fingerprint
            AND turn.expected_head_sha = NEW.expected_head_sha
            AND turn.expected_base_sha = NEW.expected_base_sha
            AND turn.purpose = 'BRANCH_SYNC_BRAIN_REVIEW'
            AND turn.status = 'REQUESTED')))
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch requires its exact worktree subject'); END;

-- V242 originally claimed every Remote Development StageTurn.  Restrict it
-- to its own callback so the CI and branch protocols can own their Turns.
DROP TRIGGER dispatch_ticket_remote_feedback_turn_insert;
CREATE TRIGGER dispatch_ticket_remote_feedback_turn_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.callback_route = 'REMOTE_FEEDBACK_TURN_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM remote_feedback_stage_turn_request request
        JOIN stage_turn turn ON turn.id = request.stage_turn_id
        JOIN remote_feedback_batch batch
          ON batch.id = request.remote_feedback_batch_id
        WHERE request.stage_turn_id = NEW.owner_id
          AND turn.operation_id = NEW.operation_id
          AND turn.attempt = NEW.attempt
          AND turn.task_epoch = NEW.task_epoch
          AND turn.stage_id = NEW.stage_id
          AND turn.stage_generation = NEW.stage_generation
          AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
          AND turn.expected_head_sha = NEW.expected_head_sha
          AND turn.expected_base_sha = NEW.expected_base_sha
          AND batch.status IN ('FROZEN', 'ADDRESSING')
          AND NEW.operation_kind = 'EXECUTE_STAGE_TURN'
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'STAGE_TURN'
          AND NEW.lane_mask IN (1, 2)
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 1
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Remote feedback StageTurn ticket is not exact') END;
END;

CREATE TABLE ci_repair_brain_verdict (
    ci_repair_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES ci_repair_operation(id) ON DELETE CASCADE,
    task_turn_id           TEXT NOT NULL UNIQUE REFERENCES task_turn(id),
    verdict                TEXT NOT NULL CHECK (verdict IN (
        'APPROVED', 'CHANGES_REQUESTED')),
    finding_count          INTEGER NOT NULL CHECK (finding_count >= 0),
    summary                TEXT NOT NULL CHECK (length(trim(summary)) > 0),
    recorded_at_ms         INTEGER NOT NULL,
    CHECK ((verdict = 'APPROVED') = (finding_count = 0))
);

CREATE TRIGGER ci_repair_brain_verdict_insert
BEFORE INSERT ON ci_repair_brain_verdict
WHEN NOT EXISTS (
    SELECT 1 FROM ci_repair_operation operation
    WHERE operation.id = NEW.ci_repair_operation_id
      AND operation.kind = 'BRAIN_REVIEW'
      AND operation.task_turn_id = NEW.task_turn_id
      AND operation.status = 'SUCCEEDED')
BEGIN SELECT RAISE(ABORT, 'CI Brain verdict lacks its successful exact TaskTurn'); END;

CREATE TRIGGER ci_repair_brain_verdict_immutable
BEFORE UPDATE ON ci_repair_brain_verdict
BEGIN SELECT RAISE(ABORT, 'CI Brain verdict is immutable'); END;

CREATE TABLE branch_sync_brain_verdict (
    branch_sync_dispatch_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES branch_sync_dispatch_operation(id) ON DELETE CASCADE,
    task_turn_id           TEXT NOT NULL UNIQUE REFERENCES task_turn(id),
    verdict                TEXT NOT NULL CHECK (verdict IN (
        'APPROVED', 'CHANGES_REQUESTED')),
    finding_count          INTEGER NOT NULL CHECK (finding_count >= 0),
    summary                TEXT NOT NULL CHECK (length(trim(summary)) > 0),
    recorded_at_ms         INTEGER NOT NULL,
    CHECK ((verdict = 'APPROVED') = (finding_count = 0))
);

CREATE TRIGGER branch_sync_brain_verdict_insert
BEFORE INSERT ON branch_sync_brain_verdict
WHEN NOT EXISTS (
    SELECT 1 FROM branch_sync_dispatch_operation operation
    WHERE operation.id = NEW.branch_sync_dispatch_operation_id
      AND operation.kind = 'BRAIN_REVIEW'
      AND operation.task_turn_id = NEW.task_turn_id
      AND operation.status = 'SUCCEEDED')
BEGIN SELECT RAISE(ABORT, 'Branch Brain verdict lacks its successful exact TaskTurn'); END;

CREATE TRIGGER branch_sync_brain_verdict_immutable
BEFORE UPDATE ON branch_sync_brain_verdict
BEGIN SELECT RAISE(ABORT, 'Branch Brain verdict is immutable'); END;

CREATE VIEW remote_brain_operation_v248 AS
SELECT episode.id AS proof_id, episode.task_turn_id,
       episode.task_id, episode.task_epoch,
       episode.remote_development_stage_id AS stage_id,
       episode.stage_generation, turn.operation_id, turn.attempt,
       turn.expected_code_fingerprint, turn.expected_head_sha,
       turn.expected_base_sha, episode.status, episode.verdict
FROM remote_feedback_brain_episode episode
JOIN task_turn turn ON turn.id = episode.task_turn_id
UNION ALL
SELECT operation.id, operation.task_turn_id, operation.task_id,
       operation.task_epoch, operation.remote_development_stage_id,
       operation.stage_generation, operation.operation_id,
       operation.semantic_attempt, operation.expected_code_fingerprint,
       operation.expected_head_sha, operation.expected_base_sha,
       operation.status, verdict.verdict
FROM ci_repair_operation operation
LEFT JOIN ci_repair_brain_verdict verdict
  ON verdict.ci_repair_operation_id = operation.id
WHERE operation.kind = 'BRAIN_REVIEW'
UNION ALL
SELECT operation.id, operation.task_turn_id, operation.task_id,
       operation.task_epoch, operation.remote_development_stage_id,
       operation.stage_generation, operation.operation_id,
       operation.semantic_attempt, operation.expected_code_fingerprint,
       operation.expected_head_sha, operation.expected_base_sha,
       operation.status, verdict.verdict
FROM branch_sync_dispatch_operation operation
LEFT JOIN branch_sync_brain_verdict verdict
  ON verdict.branch_sync_dispatch_operation_id = operation.id
WHERE operation.kind = 'BRAIN_REVIEW';

DROP TRIGGER remote_task_brain_receipt_insert;
CREATE TRIGGER remote_task_brain_receipt_insert
BEFORE INSERT ON remote_task_brain_receipt
BEGIN
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM task_command_receipt shared
                     WHERE shared.task_id = NEW.task_id
                       AND shared.command_id = NEW.command_id)
          OR EXISTS (SELECT 1 FROM task_brain_request_receipt local
                     WHERE local.task_id = NEW.task_id
                       AND local.command_id = NEW.command_id)
            THEN RAISE(ABORT, 'Remote Task Brain command id is already used')
        WHEN NEW.cause = 'REQUEST_BRAIN_REVIEW' AND NOT EXISTS (
            SELECT 1 FROM remote_brain_operation_v248 operation
            JOIN task_transition transition
              ON transition.task_id = operation.task_id
             AND transition.command_id = NEW.command_id
            WHERE operation.proof_id = NEW.proof_id
              AND operation.status IN ('REQUESTED', 'DISPATCHED')
              AND operation.task_id = NEW.task_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.stage_id = NEW.subject_stage_id
              AND operation.stage_generation =
                  NEW.subject_stage_generation
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.attempt = NEW.subject_attempt
              AND operation.expected_code_fingerprint IS
                  NEW.subject_expected_code_fingerprint
              AND operation.expected_head_sha IS
                  NEW.subject_expected_head_sha
              AND operation.expected_base_sha IS
                  NEW.subject_expected_base_sha
              AND NEW.returned_epoch = NEW.expected_task_epoch
              AND NEW.returned_version = NEW.expected_task_version + 1
              AND NEW.returned_current_stage_id = NEW.subject_stage_id
              AND NEW.returned_pending_task_epoch = NEW.subject_task_epoch
              AND NEW.returned_pending_stage_id = NEW.subject_stage_id
              AND NEW.returned_pending_stage_generation =
                  NEW.subject_stage_generation
              AND NEW.returned_pending_operation_id =
                  NEW.subject_operation_id
              AND NEW.returned_pending_attempt = NEW.subject_attempt
              AND NEW.returned_pending_code_fingerprint IS
                  NEW.subject_expected_code_fingerprint
              AND NEW.returned_pending_head_sha IS
                  NEW.subject_expected_head_sha
              AND NEW.returned_pending_base_sha IS
                  NEW.subject_expected_base_sha
              AND transition.aggregate_version = NEW.returned_version
              AND transition.cause = NEW.cause
              AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'Remote Task Brain request receipt is not exact')
        WHEN NEW.cause = 'ACCEPT_BRAIN_VERDICT'
          AND NEW.disposition = 'APPLIED' AND NOT EXISTS (
            SELECT 1 FROM remote_brain_operation_v248 operation
            JOIN task_transition transition
              ON transition.task_id = operation.task_id
             AND transition.command_id = NEW.command_id
            WHERE operation.task_id = NEW.task_id
              AND operation.status = 'SUCCEEDED'
              AND operation.verdict = NEW.brain_verdict
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.attempt = NEW.subject_attempt
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.stage_id = NEW.subject_stage_id
              AND operation.stage_generation =
                  NEW.subject_stage_generation
              AND operation.expected_code_fingerprint IS
                  NEW.subject_expected_code_fingerprint
              AND operation.expected_head_sha IS
                  NEW.subject_expected_head_sha
              AND operation.expected_base_sha IS
                  NEW.subject_expected_base_sha
              AND NEW.returned_pending_operation_id IS NULL
              AND NEW.returned_last_brain_verdict = NEW.brain_verdict
              AND NEW.returned_last_brain_operation_id =
                  NEW.subject_operation_id
              AND transition.aggregate_version = NEW.returned_version
              AND transition.cause = NEW.cause
              AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'Remote Task Brain verdict receipt is not exact')
    END;
END;
