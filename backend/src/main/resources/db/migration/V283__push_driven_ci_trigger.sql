-- Preserve the push-driven CI fallback as its own exact V2 operation.  Unlike
-- GitHub's rerun-failed endpoint, this command creates one empty commit in the
-- Task worktree and pushes it, so it needs both lane capacity, a writer fence,
-- and a durable successor code subject.

ALTER TABLE v2_user_remote_action_v270
    ADD COLUMN worktree_path TEXT;
ALTER TABLE v2_user_remote_action_v270
    ADD COLUMN expected_code_fingerprint TEXT;

DROP TRIGGER v2_user_remote_action_semantic_insert_v282;
CREATE TRIGGER v2_user_remote_action_semantic_insert_v282
BEFORE INSERT ON v2_user_remote_action_v270
WHEN NEW.semantic_action IS NULL
  OR NEW.semantic_action NOT IN (
      'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
      'SUBMIT_REVIEW', 'RERUN_FAILED_CHECKS', 'SET_DRAFT_STATE',
      'UPDATE_TITLE', 'UPDATE_BODY', 'CLOSE_PULL_REQUEST',
      'COMMENT_AND_CLOSE',
      'REPLY_REVIEW_THREAD', 'EDIT_ISSUE_COMMENT', 'EDIT_REVIEW_COMMENT',
      'DELETE_ISSUE_COMMENT', 'DELETE_REVIEW_COMMENT', 'ADD_REVIEWER',
      'REMOVE_REVIEWER', 'SET_ASSIGNEE', 'SET_LABEL',
      'CREATE_INLINE_COMMENT', 'REACT_PULL_REQUEST',
      'REACT_REVIEW_COMMENT', 'REACT_ISSUE_COMMENT',
      'SET_THREAD_RESOLUTION', 'TRIGGER_CI_EMPTY_COMMIT')
  OR (NEW.semantic_action IN (
          'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
          'SUBMIT_REVIEW')
      AND NEW.kind <> NEW.semantic_action)
  OR (NEW.semantic_action NOT IN (
          'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
          'SUBMIT_REVIEW')
      AND NEW.kind <> 'DEQUEUE')
BEGIN SELECT RAISE(ABORT,
    'V2 user remote action semantic identity is invalid'); END;

CREATE TRIGGER v2_user_remote_action_ci_trigger_insert_v283
BEFORE INSERT ON v2_user_remote_action_v270
WHEN (NEW.semantic_action = 'TRIGGER_CI_EMPTY_COMMIT'
      AND NOT EXISTS (
          SELECT 1
          FROM tasks task
          JOIN task_code_identity identity ON identity.task_id = task.id
          JOIN task_current_code_subject_v230 code ON code.task_id = task.id
          JOIN remote_pr_binding binding
            ON binding.id = NEW.remote_pr_binding_id
          JOIN remote_development_stage remote
            ON remote.stage_id = NEW.remote_stage_id
          WHERE task.id = NEW.task_id
            AND task.workflow_version = 'V2'
            AND task.lifecycle_state = 'ACTIVE'
            AND task.epoch = NEW.task_epoch
            AND identity.worktree_path = NEW.worktree_path
            AND identity.publish_repository_id = NEW.head_repository_id
            AND identity.branch_name = NEW.branch_name
            AND code.code_fingerprint = NEW.expected_code_fingerprint
            AND code.head_sha = NEW.expected_head_sha
            AND code.base_sha = NEW.expected_base_sha
            AND remote.task_id = task.id
            AND remote.generation = NEW.stage_generation
            AND remote.current_head_sha = NEW.expected_head_sha
            AND remote.current_base_sha = NEW.expected_base_sha
            AND binding.task_id = task.id
            AND binding.head_repository_id = identity.publish_repository_id))
   OR (NEW.semantic_action <> 'TRIGGER_CI_EMPTY_COMMIT'
       AND (NEW.worktree_path IS NOT NULL
            OR NEW.expected_code_fingerprint IS NOT NULL))
BEGIN SELECT RAISE(ABORT,
    'empty-commit CI trigger lacks its exact Task worktree subject'); END;

CREATE TRIGGER v2_user_remote_action_ci_trigger_identity_v283
BEFORE UPDATE OF worktree_path, expected_code_fingerprint
ON v2_user_remote_action_v270
BEGIN SELECT RAISE(ABORT,
    'V2 user remote action worktree identity is immutable'); END;

DROP TRIGGER dispatch_ticket_v2_user_remote_action_v270;
CREATE TRIGGER dispatch_ticket_v2_user_remote_action_v270
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'APPLY_V2_USER_REMOTE_ACTION'
   OR NEW.callback_route = 'V2_USER_REMOTE_ACTION_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM v2_user_remote_action_v270 action
        JOIN tasks task ON task.id = action.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE action.operation_id = NEW.operation_id
          AND action.status = 'REQUESTED'
          AND NEW.operation_kind = 'APPLY_V2_USER_REMOTE_ACTION'
          AND NEW.async_family = 'GITHUB_EFFECT'
          AND NEW.owner_kind = 'TASK'
          AND NEW.owner_id = action.task_id
          AND NEW.callback_route = 'V2_USER_REMOTE_ACTION_RESULT'
          AND NEW.lane_mask = CASE action.semantic_action
              WHEN 'TRIGGER_CI_EMPTY_COMMIT' THEN 48 ELSE 32 END
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = CASE action.semantic_action
              WHEN 'TRIGGER_CI_EMPTY_COMMIT' THEN 1 ELSE 0 END
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.task_id = action.task_id
          AND NEW.task_epoch = action.task_epoch
          AND NEW.stage_id = action.remote_stage_id
          AND NEW.stage_generation = action.stage_generation
          AND NEW.attempt = action.semantic_attempt
          AND NEW.expected_code_fingerprint
              IS action.expected_code_fingerprint
          AND NEW.expected_head_sha = action.expected_head_sha
          AND NEW.expected_base_sha = action.expected_base_sha
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'V2 user remote action ticket is stale or unowned') END;
END;

-- remote_worktree_subject is already the revisioned current-local-subject
-- ledger for Remote Development.  Rebuild its closed source-kind constraint
-- to admit this exact successful operation; existing rows and revision order
-- are retained, and later repair/steering subjects supersede this row normally.
-- Keep the current-code view present because owner triggers depend on it.
-- Legacy rename mode leaves that view pointed at the replacement table name
-- while the closed CHECK constraint is rebuilt.
DROP TRIGGER remote_worktree_subject_insert;
DROP TRIGGER remote_worktree_subject_revision_insert;
DROP TRIGGER remote_worktree_subject_immutable;

PRAGMA legacy_alter_table = ON;
ALTER TABLE remote_worktree_subject
    RENAME TO remote_worktree_subject_v248_pre_v283;

CREATE TABLE remote_worktree_subject (
    id                          TEXT    NOT NULL PRIMARY KEY,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    revision                    INTEGER NOT NULL CHECK (revision > 0),
    source_kind                 TEXT    NOT NULL CHECK (source_kind IN (
        'CI_STAGE_TURN', 'BRANCH_EFFECT', 'BRANCH_STAGE_TURN',
        'USER_CI_TRIGGER')),
    source_operation_id         TEXT    NOT NULL UNIQUE,
    code_fingerprint            TEXT    NOT NULL,
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    recorded_at_ms              INTEGER NOT NULL,
    UNIQUE (task_id, task_epoch, revision)
);

INSERT INTO remote_worktree_subject(
    id, task_id, task_epoch, remote_development_stage_id, stage_generation,
    revision, source_kind, source_operation_id, code_fingerprint, head_sha,
    base_sha, recorded_at_ms)
SELECT id, task_id, task_epoch, remote_development_stage_id, stage_generation,
       revision, source_kind, source_operation_id, code_fingerprint, head_sha,
       base_sha, recorded_at_ms
FROM remote_worktree_subject_v248_pre_v283;

DROP TABLE remote_worktree_subject_v248_pre_v283;
PRAGMA legacy_alter_table = OFF;

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
      AND operation.target_base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM v2_user_remote_action_v270 action
    WHERE NEW.source_kind = 'USER_CI_TRIGGER'
      AND action.operation_id = NEW.source_operation_id
      AND action.semantic_action = 'TRIGGER_CI_EMPTY_COMMIT'
      AND action.status = 'SUCCEEDED'
      AND action.task_id = NEW.task_id
      AND action.task_epoch = NEW.task_epoch
      AND action.remote_stage_id = NEW.remote_development_stage_id
      AND action.stage_generation = NEW.stage_generation
      AND action.expected_code_fingerprint = NEW.code_fingerprint
      AND action.expected_base_sha = NEW.base_sha
      AND action.expected_head_sha <> NEW.head_sha
      AND action.external_effect_id =
          'ci-trigger-empty-commit:' || NEW.head_sha)
BEGIN SELECT RAISE(ABORT,
    'Worktree subject lacks exact successful repair or CI-trigger evidence'); END;

CREATE TRIGGER remote_worktree_subject_revision_insert
BEFORE INSERT ON remote_worktree_subject
WHEN NEW.revision <> COALESCE((
    SELECT MAX(previous.revision) + 1
    FROM remote_worktree_subject previous
    WHERE previous.task_id = NEW.task_id
      AND previous.task_epoch = NEW.task_epoch), 1)
BEGIN SELECT RAISE(ABORT,
    'Worktree subject revision is not the next exact revision'); END;

CREATE TRIGGER remote_worktree_subject_immutable
BEFORE UPDATE ON remote_worktree_subject
BEGIN SELECT RAISE(ABORT, 'Worktree subject is immutable'); END;
