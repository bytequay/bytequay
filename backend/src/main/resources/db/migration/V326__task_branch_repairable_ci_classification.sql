-- Extend the immutable CI classification without rewriting historical values.
-- Flyway runs this outside its transaction so foreign keys can be disabled
-- while SQLite rebuilds the parent table. The savepoint keeps the rebuild,
-- trigger restoration, row-count proof, and FK proof atomic.
PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;
SAVEPOINT task_branch_repairable_ci_classification_v326;

CREATE TABLE ci_repair_episode_count_v326(
    row_count INTEGER NOT NULL);
INSERT INTO ci_repair_episode_count_v326(row_count)
SELECT COUNT(*) FROM ci_repair_episode;

CREATE TABLE ci_repair_episode_v326_new (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id        TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    failed_ci_evaluation_id     TEXT    NOT NULL REFERENCES remote_ci_evaluation(id),
    subject_head_sha            TEXT    NOT NULL,
    subject_base_sha            TEXT    NOT NULL,
    classification              TEXT    NOT NULL CHECK (classification IN (
        'FLAKY', 'INFRASTRUCTURE', 'TASK_DETERMINISTIC',
        'TASK_BRANCH_REPAIRABLE', 'BASE_DETERMINISTIC', 'UNKNOWN')),
    status                      TEXT    NOT NULL CHECK (status IN (
        'OPEN', 'AWAITING_RERUN', 'FIXING', 'VALIDATING', 'AWAITING_PUSH_CI',
        'SUCCEEDED', 'EXHAUSTED', 'STOPPED')),
    rerun_count                 INTEGER NOT NULL DEFAULT 0 CHECK (rerun_count >= 0),
    rerun_limit                 INTEGER NOT NULL CHECK (rerun_limit >= 0),
    fix_attempt_count           INTEGER NOT NULL DEFAULT 0 CHECK (fix_attempt_count >= 0),
    fix_attempt_limit           INTEGER NOT NULL CHECK (fix_attempt_limit >= 0),
    delivery_retry_count        INTEGER NOT NULL DEFAULT 0 CHECK (delivery_retry_count >= 0),
    delivery_retry_limit        INTEGER NOT NULL CHECK (delivery_retry_limit >= 0),
    push_count                  INTEGER NOT NULL DEFAULT 0 CHECK (push_count >= 0),
    push_limit                  INTEGER NOT NULL CHECK (push_limit >= 0),
    last_pushed_head_sha        TEXT,
    last_push_result_evaluation_id TEXT REFERENCES remote_ci_evaluation(id),
    terminal_ci_evaluation_id   TEXT REFERENCES remote_ci_evaluation(id),
    opened_at_ms                INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    stop_reason                 TEXT,
    CHECK (rerun_count <= rerun_limit
        AND fix_attempt_count <= fix_attempt_limit
        AND delivery_retry_count <= delivery_retry_limit
        AND push_count <= push_limit),
    CHECK ((push_count = 0 AND last_pushed_head_sha IS NULL
            AND last_push_result_evaluation_id IS NULL)
        OR (push_count > 0 AND last_pushed_head_sha IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED'))
            = (completed_at_ms IS NOT NULL)),
    CHECK (classification NOT IN ('FLAKY', 'INFRASTRUCTURE')
        OR (fix_attempt_count = 0 AND fix_attempt_limit = 0
            AND push_count = 0 AND push_limit = 0))
);

INSERT INTO ci_repair_episode_v326_new(
    id, remote_development_stage_id, task_id, task_epoch, stage_generation,
    remote_pr_binding_id, failed_ci_evaluation_id, subject_head_sha,
    subject_base_sha, classification, status, rerun_count, rerun_limit,
    fix_attempt_count, fix_attempt_limit, delivery_retry_count,
    delivery_retry_limit, push_count, push_limit, last_pushed_head_sha,
    last_push_result_evaluation_id, terminal_ci_evaluation_id, opened_at_ms,
    completed_at_ms, stop_reason)
SELECT id, remote_development_stage_id, task_id, task_epoch, stage_generation,
       remote_pr_binding_id, failed_ci_evaluation_id, subject_head_sha,
       subject_base_sha, classification, status, rerun_count, rerun_limit,
       fix_attempt_count, fix_attempt_limit, delivery_retry_count,
       delivery_retry_limit, push_count, push_limit, last_pushed_head_sha,
       last_push_result_evaluation_id, terminal_ci_evaluation_id, opened_at_ms,
       completed_at_ms, stop_reason
FROM ci_repair_episode;

DROP TABLE ci_repair_episode;
ALTER TABLE ci_repair_episode_v326_new RENAME TO ci_repair_episode;
PRAGMA legacy_alter_table = OFF;

CREATE UNIQUE INDEX idx_ci_repair_one_live_subject
    ON ci_repair_episode(remote_development_stage_id, subject_head_sha)
    WHERE status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED');

CREATE TRIGGER ci_repair_episode_budget_update
BEFORE UPDATE OF rerun_limit, fix_attempt_limit, push_limit
        ON ci_repair_episode
WHEN NEW.rerun_limit <> OLD.rerun_limit
  OR NEW.fix_attempt_limit <> OLD.fix_attempt_limit
  OR NEW.push_limit <> OLD.push_limit
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM ci_repair_budget_extension extension
        WHERE extension.ci_repair_episode_id = NEW.id
          AND extension.consumed_at_ms IS NULL
          AND NEW.rerun_limit = OLD.rerun_limit + extension.rerun_delta
          AND NEW.fix_attempt_limit = OLD.fix_attempt_limit + extension.fix_delta
          AND NEW.push_limit = OLD.push_limit + extension.push_delta)
    THEN RAISE(ABORT, 'CI repair budget change lacks exact authorization') END;
END;

CREATE TRIGGER ci_repair_episode_exhaustion
BEFORE UPDATE OF status ON ci_repair_episode
WHEN NEW.status = 'EXHAUSTED'
  AND (
    (NEW.classification IN ('FLAKY', 'INFRASTRUCTURE')
        AND NEW.rerun_count < NEW.rerun_limit)
    OR (NEW.classification NOT IN ('FLAKY', 'INFRASTRUCTURE')
        AND NEW.fix_attempt_count < NEW.fix_attempt_limit
        AND NEW.push_count < NEW.push_limit)
    OR (NEW.last_pushed_head_sha IS NOT NULL
        AND (NEW.last_push_result_evaluation_id IS NULL
            OR NOT EXISTS (
                SELECT 1 FROM remote_ci_evaluation evaluation
                WHERE evaluation.id = NEW.last_push_result_evaluation_id
                  AND evaluation.head_sha = NEW.last_pushed_head_sha
                  AND evaluation.policy_outcome = 'FAILED'))))
BEGIN SELECT RAISE(ABORT, 'CI repair cannot exhaust before its final result and budget'); END;

CREATE TRIGGER ci_repair_episode_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, failed_ci_evaluation_id,
        subject_head_sha, subject_base_sha, classification, opened_at_ms
        ON ci_repair_episode
BEGIN SELECT RAISE(ABORT, 'CI repair subject identity is immutable'); END;

CREATE TRIGGER ci_repair_episode_insert
BEFORE INSERT ON ci_repair_episode
WHEN NOT EXISTS (
    SELECT 1 FROM remote_development_stage remote
    JOIN tasks task ON task.id = remote.task_id
    JOIN remote_ci_evaluation evaluation
      ON evaluation.remote_development_stage_id = remote.stage_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
      AND remote.current_head_sha = NEW.subject_head_sha
      AND remote.current_base_sha = NEW.subject_base_sha
      AND task.epoch = NEW.task_epoch
      AND evaluation.id = NEW.failed_ci_evaluation_id
      AND evaluation.head_sha = NEW.subject_head_sha
      AND evaluation.base_sha = NEW.subject_base_sha
      AND evaluation.policy_outcome = 'FAILED')
BEGIN SELECT RAISE(ABORT, 'CI repair requires the current failed exact-head evidence'); END;

CREATE TRIGGER ci_repair_episode_push_result
BEFORE UPDATE OF last_pushed_head_sha, last_push_result_evaluation_id
        ON ci_repair_episode
WHEN NEW.last_push_result_evaluation_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM remote_ci_evaluation evaluation
      WHERE evaluation.id = NEW.last_push_result_evaluation_id
        AND evaluation.remote_development_stage_id =
            NEW.remote_development_stage_id
        AND evaluation.task_id = NEW.task_id
        AND evaluation.task_epoch = NEW.task_epoch
        AND evaluation.head_sha = NEW.last_pushed_head_sha)
BEGIN SELECT RAISE(ABORT, 'CI repair push result does not match the pushed head'); END;

CREATE TRIGGER ci_repair_episode_push_subject
BEFORE UPDATE OF push_count, last_pushed_head_sha ON ci_repair_episode
WHEN (NEW.push_count = OLD.push_count + 1
        AND (NEW.last_pushed_head_sha IS NULL
            OR NEW.last_pushed_head_sha IS OLD.last_pushed_head_sha
            OR NEW.last_pushed_head_sha IS NEW.subject_head_sha
            OR NEW.last_push_result_evaluation_id IS NOT NULL))
  OR (NEW.push_count = OLD.push_count
        AND NEW.last_pushed_head_sha IS NOT OLD.last_pushed_head_sha)
BEGIN SELECT RAISE(ABORT, 'Each CI repair push must record exactly one new unresolved head'); END;

CREATE TRIGGER ci_repair_episode_terminal_immutable
BEFORE UPDATE ON ci_repair_episode
WHEN OLD.status IN ('SUCCEEDED', 'STOPPED')
  OR (OLD.status = 'EXHAUSTED' AND NOT (
      (NEW.status = 'OPEN'
        AND NEW.completed_at_ms IS NULL
        AND NEW.terminal_ci_evaluation_id IS NULL
        AND EXISTS (
            SELECT 1 FROM ci_repair_budget_extension extension
            WHERE extension.ci_repair_episode_id = OLD.id
              AND extension.consumed_at_ms IS NULL))
      OR (NEW.status = 'STOPPED'
        AND EXISTS (
            SELECT 1 FROM ci_repair_control_command command
            WHERE command.ci_repair_episode_id = OLD.id
              AND command.consumed_at_ms IS NULL))))
BEGIN SELECT RAISE(ABORT, 'Terminal CI repair episode is immutable'); END;

CREATE TRIGGER ci_repair_episode_terminal_result
BEFORE UPDATE OF status ON ci_repair_episode
WHEN NEW.status IN ('SUCCEEDED', 'EXHAUSTED')
  AND NOT EXISTS (
      SELECT 1 FROM remote_ci_evaluation evaluation
      JOIN remote_development_stage remote
        ON remote.stage_id = evaluation.remote_development_stage_id
      WHERE evaluation.id = NEW.terminal_ci_evaluation_id
        AND evaluation.remote_development_stage_id =
            NEW.remote_development_stage_id
        AND evaluation.task_id = NEW.task_id
        AND evaluation.task_epoch = NEW.task_epoch
        AND evaluation.stage_generation = NEW.stage_generation
        AND evaluation.head_sha = COALESCE(
            NEW.last_pushed_head_sha, NEW.subject_head_sha)
        AND evaluation.base_sha = NEW.subject_base_sha
        AND evaluation.policy_outcome = CASE NEW.status
            WHEN 'SUCCEEDED' THEN 'ACCEPTED' ELSE 'FAILED' END
        AND remote.accepted_snapshot_id = evaluation.remote_pr_snapshot_id
        AND remote.current_head_sha = evaluation.head_sha
        AND remote.current_base_sha = evaluation.base_sha
        AND (NEW.last_pushed_head_sha IS NULL
            OR NEW.last_push_result_evaluation_id = evaluation.id))
BEGIN SELECT RAISE(ABORT, 'CI repair terminal state lacks accepted exact-head CI result'); END;

CREATE TRIGGER ci_repair_episode_counter_update
BEFORE UPDATE OF rerun_count, fix_attempt_count, delivery_retry_count, push_count
        ON ci_repair_episode
WHEN NEW.rerun_count NOT BETWEEN OLD.rerun_count AND OLD.rerun_count + 1
  OR NEW.fix_attempt_count NOT BETWEEN OLD.fix_attempt_count AND OLD.fix_attempt_count + 1
  OR NEW.delivery_retry_count NOT BETWEEN OLD.delivery_retry_count AND OLD.delivery_retry_count + 1
  OR NEW.push_count NOT BETWEEN OLD.push_count AND OLD.push_count + 1
  OR (NEW.fix_attempt_count = OLD.fix_attempt_count + 1 AND NOT (
      EXISTS (
          SELECT 1 FROM ci_repair_fix_tree_result_v318 result
          WHERE result.ci_repair_episode_id = NEW.id
            AND result.semantic_attempt = NEW.fix_attempt_count
            AND result.disposition = 'CHANGED')
      OR EXISTS (
          SELECT 1
          FROM remote_repair_commit_adoption_operation_v322 adoption
          JOIN remote_repair_result_normalization_due_v322 due
            ON due.id = adoption.normalization_due_id
          JOIN remote_repair_result_normalization_operation_v322 normalization
            ON normalization.id = adoption.normalization_operation_row_id
          JOIN remote_repair_commit_adoption_result_v322 result
            ON result.id = adoption.result_id
          JOIN remote_repair_commit_adoption_delivery_v322 delivery
            ON delivery.adoption_operation_row_id = adoption.id
          JOIN task_blocker blocker ON blocker.id = adoption.blocker_id
          JOIN remote_code_subject code
            ON code.stage_turn_id = adoption.source_stage_turn_id
          JOIN remote_worktree_subject worktree
            ON worktree.source_operation_id = adoption.operation_id
          WHERE adoption.ci_repair_episode_id = NEW.id
            AND due.semantic_attempt = NEW.fix_attempt_count
            AND due.status = 'DISPATCHED'
            AND normalization.status = 'SUCCEEDED'
            AND adoption.status = 'SUCCEEDED'
            AND delivery.operation_id = adoption.operation_id
            AND delivery.result_id = result.id
            AND delivery.raw_outcome = 'SUCCEEDED'
            AND delivery.acceptance = 'ACCEPTED'
            AND blocker.owner_kind = 'EPISODE'
            AND blocker.owner_id = NEW.id
            AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
            AND blocker.status = 'OPEN'
            AND code.task_id = adoption.task_id
            AND code.task_epoch = adoption.task_epoch
            AND code.remote_development_stage_id =
                adoption.remote_development_stage_id
            AND code.stage_generation = adoption.stage_generation
            AND code.source_code_fingerprint =
                adoption.expected_code_fingerprint
            AND code.source_head_sha = adoption.expected_head_sha
            AND code.source_base_sha = adoption.expected_base_sha
            AND code.code_fingerprint = result.candidate_code_fingerprint
            AND code.head_sha = result.candidate_head_sha
            AND code.base_sha = adoption.expected_base_sha
            AND worktree.task_id = adoption.task_id
            AND worktree.task_epoch = adoption.task_epoch
            AND worktree.remote_development_stage_id =
                adoption.remote_development_stage_id
            AND worktree.stage_generation = adoption.stage_generation
            AND worktree.source_kind = 'CI_STAGE_TURN'
            AND worktree.code_fingerprint =
                result.candidate_code_fingerprint
            AND worktree.head_sha = result.candidate_head_sha
            AND worktree.base_sha = adoption.expected_base_sha)))
BEGIN SELECT RAISE(ABORT,
    'CI repair counters require one exact accepted result'); END;

CREATE TABLE ci_repair_episode_assert_v326(
    value INTEGER NOT NULL CHECK (value = 1));
INSERT INTO ci_repair_episode_assert_v326(value)
VALUES ((SELECT CASE WHEN
    (SELECT COUNT(*) FROM ci_repair_episode) =
        (SELECT row_count FROM ci_repair_episode_count_v326)
    AND (SELECT COUNT(*) FROM pragma_foreign_key_check) = 0
    THEN 1 ELSE 0 END));
DROP TABLE ci_repair_episode_assert_v326;
DROP TABLE ci_repair_episode_count_v326;

RELEASE task_branch_repairable_ci_classification_v326;
PRAGMA foreign_keys = ON;
