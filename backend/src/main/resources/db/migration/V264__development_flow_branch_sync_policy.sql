-- V2 branch guard configuration is Task-owned and revisioned. A missing row
-- is the durable semantic default before first push: disabled and nightly.
-- The first accepted publish appends the default armed revision unless a user
-- has already made an explicit choice.

CREATE TABLE task_branch_sync_policy_revision (
    id                TEXT    NOT NULL PRIMARY KEY,
    task_id           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    revision          INTEGER NOT NULL CHECK (revision > 0),
    enabled           INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    schedule          TEXT    NOT NULL
        CHECK (length(trim(schedule)) BETWEEN 1 AND 64),
    source            TEXT    NOT NULL CHECK (source IN (
        'FIRST_PUSH_DEFAULT', 'USER_CONFIGURED')),
    attempt_limit     INTEGER NOT NULL CHECK (attempt_limit BETWEEN 1 AND 10),
    command_id        TEXT    NOT NULL,
    actor             TEXT    NOT NULL,
    created_at_ms     INTEGER NOT NULL,
    UNIQUE (task_id, revision),
    UNIQUE (task_id, command_id)
);

CREATE TRIGGER task_branch_sync_policy_revision_insert
BEFORE INSERT ON task_branch_sync_policy_revision
WHEN NOT EXISTS (
    SELECT 1 FROM tasks task
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state NOT IN (
          'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
      AND NEW.revision = COALESCE((
          SELECT MAX(policy.revision) + 1
          FROM task_branch_sync_policy_revision policy
          WHERE policy.task_id = task.id), 1))
BEGIN SELECT RAISE(ABORT, 'Branch sync policy does not match the current V2 Task'); END;

CREATE TRIGGER task_branch_sync_policy_revision_immutable
BEFORE UPDATE ON task_branch_sync_policy_revision
BEGIN SELECT RAISE(ABORT, 'Branch sync policy revision is immutable'); END;

ALTER TABLE branch_sync_episode
    ADD COLUMN branch_sync_policy_revision_id TEXT
        REFERENCES task_branch_sync_policy_revision(id);

CREATE TRIGGER branch_sync_episode_policy_v264
BEFORE INSERT ON branch_sync_episode
WHEN NEW.branch_sync_policy_revision_id IS NULL
  OR NOT EXISTS (
      SELECT 1 FROM task_branch_sync_policy_revision policy
      WHERE policy.id = NEW.branch_sync_policy_revision_id
        AND policy.task_id = NEW.task_id
        AND policy.enabled = 1
        AND policy.source = NEW.policy_source
        AND policy.attempt_limit = NEW.attempt_limit
        AND policy.revision = (
            SELECT MAX(current.revision)
            FROM task_branch_sync_policy_revision current
            WHERE current.task_id = NEW.task_id))
BEGIN SELECT RAISE(ABORT, 'Branch sync requires the exact enabled Task policy'); END;

DROP TRIGGER branch_sync_episode_identity_immutable;
CREATE TRIGGER branch_sync_episode_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, source_snapshot_id,
        old_head_sha, observed_base_sha, target_base_sha, policy_source,
        attempt_limit, opened_at_ms, branch_sync_policy_revision_id
        ON branch_sync_episode
WHEN NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.remote_pr_binding_id IS NOT OLD.remote_pr_binding_id
  OR NEW.source_snapshot_id IS NOT OLD.source_snapshot_id
  OR NEW.old_head_sha IS NOT OLD.old_head_sha
  OR NEW.observed_base_sha IS NOT OLD.observed_base_sha
  OR NEW.target_base_sha IS NOT OLD.target_base_sha
  OR NEW.policy_source IS NOT OLD.policy_source
  OR NEW.attempt_limit IS NOT OLD.attempt_limit
  OR NEW.opened_at_ms IS NOT OLD.opened_at_ms
  OR NEW.branch_sync_policy_revision_id IS NOT OLD.branch_sync_policy_revision_id
BEGIN SELECT RAISE(ABORT, 'Branch sync subject is immutable'); END;
