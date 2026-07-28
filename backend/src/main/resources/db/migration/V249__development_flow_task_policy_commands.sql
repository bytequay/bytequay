-- V2 Task policy changes are aggregate commands. Each command appends an
-- immutable Trunk-scoped policy revision, advances the Task version, and
-- atomically selects the new revision. Existing publish/remote authorization
-- rows keep the policy revision they already froze.

DROP TRIGGER v2_task_identity_immutable;
CREATE TRIGGER v2_task_identity_immutable
BEFORE UPDATE OF thread_id, seq, assignment_id ON tasks
WHEN OLD.workflow_version = 'V2'
  AND (NEW.thread_id IS NOT OLD.thread_id
    OR NEW.seq IS NOT OLD.seq
    OR NEW.assignment_id IS NOT OLD.assignment_id)
BEGIN
    SELECT RAISE(ABORT, 'V2 task creation identity is immutable');
END;

CREATE TABLE task_policy_command_intent (
    id                           TEXT    NOT NULL PRIMARY KEY,
    task_id                      TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                   TEXT    NOT NULL,
    actor                        TEXT    NOT NULL,
    expected_task_epoch          INTEGER NOT NULL CHECK (expected_task_epoch > 0),
    expected_task_version        INTEGER NOT NULL CHECK (expected_task_version >= 0),
    previous_policy_revision_id  TEXT    NOT NULL REFERENCES task_policy_revision(id),
    selected_policy_revision_id  TEXT    NOT NULL UNIQUE REFERENCES task_policy_revision(id),
    recorded_at_ms               INTEGER NOT NULL,
    UNIQUE (task_id, command_id),
    CHECK (previous_policy_revision_id <> selected_policy_revision_id)
);

CREATE TRIGGER task_policy_command_intent_insert
BEFORE INSERT ON task_policy_command_intent
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN task_policy_revision previous
      ON previous.id = NEW.previous_policy_revision_id
    JOIN task_policy_revision selected
      ON selected.id = NEW.selected_policy_revision_id
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state NOT IN (
          'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
      AND task.epoch = NEW.expected_task_epoch
      AND task.aggregate_version = NEW.expected_task_version
      AND task.policy_revision_id = previous.id
      AND previous.trunk_id = task.thread_id
      AND selected.trunk_id = task.thread_id
      AND selected.revision > previous.revision
      AND selected.revision = (
          SELECT MAX(policy.revision)
          FROM task_policy_revision policy
          WHERE policy.trunk_id = task.thread_id)
      AND selected.source = 'TASK_POLICY_COMMAND'
      AND selected.created_by = NEW.actor)
BEGIN
    SELECT RAISE(ABORT, 'Task policy intent does not match the exact aggregate');
END;

CREATE TRIGGER task_policy_command_intent_immutable
BEFORE UPDATE ON task_policy_command_intent
BEGIN SELECT RAISE(ABORT, 'Task policy command intent is immutable'); END;

CREATE TRIGGER v2_task_policy_selection_exact
BEFORE UPDATE OF policy_revision_id ON tasks
WHEN OLD.workflow_version = 'V2'
  AND NEW.policy_revision_id IS NOT OLD.policy_revision_id
  AND NOT EXISTS (
      SELECT 1
      FROM task_policy_command_intent intent
      WHERE intent.task_id = OLD.id
        AND intent.expected_task_epoch = OLD.epoch
        AND intent.expected_task_version = OLD.aggregate_version
        AND intent.previous_policy_revision_id = OLD.policy_revision_id
        AND intent.selected_policy_revision_id = NEW.policy_revision_id
        AND NEW.thread_id = OLD.thread_id
        AND NEW.lifecycle_state = OLD.lifecycle_state
        AND NEW.epoch = OLD.epoch
        AND NEW.aggregate_version = OLD.aggregate_version + 1)
BEGIN
    SELECT RAISE(ABORT, 'V2 Task policy selection lacks its exact command intent');
END;

CREATE TABLE task_policy_command_receipt (
    id                           TEXT    NOT NULL PRIMARY KEY,
    intent_id                    TEXT    NOT NULL UNIQUE
        REFERENCES task_policy_command_intent(id),
    task_id                      TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                   TEXT    NOT NULL,
    actor                        TEXT    NOT NULL,
    expected_task_epoch          INTEGER NOT NULL CHECK (expected_task_epoch > 0),
    expected_task_version        INTEGER NOT NULL CHECK (expected_task_version >= 0),
    previous_policy_revision_id  TEXT    NOT NULL REFERENCES task_policy_revision(id),
    selected_policy_revision_id  TEXT    NOT NULL UNIQUE REFERENCES task_policy_revision(id),
    returned_task_version        INTEGER NOT NULL CHECK (returned_task_version > 0),
    recorded_at_ms               INTEGER NOT NULL,
    UNIQUE (task_id, command_id),
    CHECK (previous_policy_revision_id <> selected_policy_revision_id),
    CHECK (returned_task_version = expected_task_version + 1)
);

CREATE TRIGGER task_policy_command_receipt_insert
BEFORE INSERT ON task_policy_command_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN task_policy_revision previous
      ON previous.id = NEW.previous_policy_revision_id
    JOIN task_policy_revision selected
      ON selected.id = NEW.selected_policy_revision_id
    JOIN task_policy_command_intent intent
      ON intent.id = NEW.intent_id
    JOIN task_transition transition
      ON transition.task_id = task.id
     AND transition.command_id = NEW.command_id
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.epoch = NEW.expected_task_epoch
      AND task.aggregate_version = NEW.returned_task_version
      AND task.policy_revision_id = selected.id
      AND intent.task_id = NEW.task_id
      AND intent.command_id = NEW.command_id
      AND intent.actor = NEW.actor
      AND intent.expected_task_epoch = NEW.expected_task_epoch
      AND intent.expected_task_version = NEW.expected_task_version
      AND intent.previous_policy_revision_id = previous.id
      AND intent.selected_policy_revision_id = selected.id
      AND previous.trunk_id = task.thread_id
      AND selected.trunk_id = task.thread_id
      AND selected.revision > previous.revision
      AND transition.epoch = task.epoch
      AND transition.from_state = task.lifecycle_state
      AND transition.to_state = task.lifecycle_state
      AND transition.aggregate_version = NEW.returned_task_version
      AND transition.cause = 'REVISE_POLICY'
      AND transition.actor = NEW.actor)
BEGIN
    SELECT RAISE(ABORT, 'Task policy receipt lacks its exact aggregate revision');
END;

CREATE TRIGGER task_policy_command_receipt_immutable
BEFORE UPDATE ON task_policy_command_receipt
BEGIN SELECT RAISE(ABORT, 'Task policy command receipt is immutable'); END;

-- A new policy may be selected after Task creation. Promotion still requires
-- the current Task policy, but no longer requires it to equal the immutable
-- creation-context policy snapshot.
DROP TRIGGER promotion_manifest_insert;
CREATE TRIGGER promotion_manifest_insert
BEFORE INSERT ON promotion_manifest
BEGIN
    SELECT CASE
        WHEN NEW.revision <> COALESCE((
            SELECT MAX(manifest.revision) + 1 FROM promotion_manifest manifest
            WHERE manifest.local_development_stage_id = NEW.local_development_stage_id), 1)
            THEN RAISE(ABORT, 'PromotionManifest revision must be exact')
        WHEN NOT EXISTS (
            SELECT 1
            FROM local_development_stage l
            JOIN stage s ON s.id = l.stage_id
            JOIN tasks t ON t.id = l.task_id
            JOIN task_current_stage current ON current.stage_id = l.stage_id
            JOIN dev_report report ON report.id = NEW.dev_report_id
            JOIN pr p ON p.id = NEW.pr_id
            JOIN task_policy_revision policy ON policy.id = NEW.policy_revision_id
            JOIN task_creation_context context ON context.task_id = t.id
            JOIN task_code_identity code ON code.task_id = t.id
            WHERE l.stage_id = NEW.local_development_stage_id
              AND l.task_id = NEW.task_id
              AND l.opened_for_epoch = NEW.task_epoch
              AND l.generation = NEW.stage_generation
              AND s.completed_at_ms IS NULL
              AND t.workflow_version = 'V2'
              AND t.lifecycle_state = 'ACTIVE' AND t.epoch = NEW.task_epoch
              AND current.task_id = NEW.task_id
              AND current.stage_generation = NEW.stage_generation
              AND report.workflow_version = 'V2'
              AND report.local_development_stage_id = l.stage_id
              AND report.task_id = NEW.task_id
              AND report.task_epoch = NEW.task_epoch
              AND report.stage_generation = NEW.stage_generation
              AND report.code_fingerprint = NEW.code_fingerprint
              AND report.head_sha = NEW.head_sha
              AND report.base_sha = NEW.base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = report.local_development_stage_id
                    AND newer.revision > report.revision)
              AND p.task_id = NEW.task_id AND p.origin = 'task'
              AND p.branch_name = NEW.branch_name
              AND p.base_branch = NEW.base_branch
              AND p.title = NEW.pr_title AND p.description = NEW.pr_body
              AND policy.id = t.policy_revision_id
              AND policy.trunk_id = t.thread_id
              AND code.branch_name = NEW.branch_name
              AND NEW.publish_repository_id = context.publish_repository_id
              AND NEW.head_repository_id = context.publish_repository_id
              AND ((context.upstream_repository_id IS NULL
                    AND NEW.route = 'DIRECT'
                    AND NEW.base_repository_id = context.repository_id
                    AND context.repository_id = context.publish_repository_id)
                OR (context.upstream_repository_id IS NOT NULL
                    AND NEW.route = 'FORK'
                    AND NEW.base_repository_id = context.upstream_repository_id)))
            THEN RAISE(ABORT, 'PromotionManifest does not prove the exact local publish subject')
    END;
END;
