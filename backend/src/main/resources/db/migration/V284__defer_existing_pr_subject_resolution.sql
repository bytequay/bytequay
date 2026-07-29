-- Existing-PR Task creation freezes only the local repository route, PR number,
-- and (for review builds) the immutable selection. The dispatcher-owned
-- ProvisionTaskOperation discovers the exact remote refs and SHAs while it
-- holds GitHub + LOCAL_GIT capacity, then persists that subject in its proof.

PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;
SAVEPOINT v284_deferred_pr_subject;

DROP TRIGGER trunk_task_creation_authorization_insert;
DROP TRIGGER trunk_task_creation_authorization_immutable;
DROP TRIGGER v2_task_creation_authorization_presentation_insert;

CREATE TABLE trunk_task_creation_authorization_v284 (
    id                         TEXT    NOT NULL PRIMARY KEY,
    trunk_id                   TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    workspace_id               TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    command_id                 TEXT    NOT NULL,
    actor                      TEXT    NOT NULL,
    disposition                TEXT    NOT NULL CHECK (disposition = 'AUTHORIZED'),
    expected_trunk_version     INTEGER NOT NULL CHECK (expected_trunk_version >= 0),
    returned_trunk_version     INTEGER NOT NULL CHECK (returned_trunk_version > 0),
    returned_lifecycle         TEXT    NOT NULL CHECK (returned_lifecycle IN ('ACTIVE', 'IDLE')),
    assignment_id              TEXT    NOT NULL UNIQUE
        REFERENCES task_assignment(id) DEFERRABLE INITIALLY DEFERRED,
    policy_revision_id         TEXT    NOT NULL REFERENCES task_policy_revision(id),
    provenance                 TEXT    NOT NULL CHECK (provenance IN (
        'AGENT_HANDOFF', 'DIRECT_USER', 'ISSUE_MONITOR', 'AUTOMATION',
        'QUALITY_SCAN', 'REVIEW_SESSION')),
    repository_id              TEXT    NOT NULL,
    upstream_repository_id     TEXT,
    publish_repository_id      TEXT    NOT NULL,
    base_source                TEXT    NOT NULL CHECK (base_source IN (
        'PLANNING_SNAPSHOT', 'FRESH_REMOTE_BASE', 'EXISTING_PR_HEAD')),
    base_repository_id         TEXT    NOT NULL,
    base_ref                   TEXT,
    planning_base_sha          TEXT,
    assignment_base_sha        TEXT,
    assignment_head_sha        TEXT,
    engine_snapshot            TEXT    NOT NULL,
    work_model_snapshot        TEXT    NOT NULL,
    recorded_at_ms             INTEGER NOT NULL,
    task_name                  TEXT,
    task_type                  TEXT,
    linked_issue_number        INTEGER,
    opening_prompt             TEXT,
    task_origin                TEXT,
    UNIQUE (trunk_id, command_id),
    CHECK (returned_trunk_version = expected_trunk_version + 1),
    CHECK (length(actor) > 0 AND length(base_repository_id) > 0
        AND length(engine_snapshot) > 0 AND length(work_model_snapshot) > 0),
    CHECK ((base_source = 'PLANNING_SNAPSHOT'
            AND length(base_ref) > 0 AND planning_base_sha IS NOT NULL
            AND assignment_base_sha IS NULL AND assignment_head_sha IS NULL)
        OR (base_source = 'FRESH_REMOTE_BASE'
            AND length(base_ref) > 0 AND planning_base_sha IS NULL
            AND assignment_base_sha IS NULL AND assignment_head_sha IS NULL)
        OR (base_source = 'EXISTING_PR_HEAD'
            AND planning_base_sha IS NULL
            AND ((base_ref IS NULL AND assignment_base_sha IS NULL
                    AND assignment_head_sha IS NULL)
              OR (length(base_ref) > 0 AND assignment_base_sha IS NOT NULL
                    AND assignment_head_sha IS NOT NULL))))
);

INSERT INTO trunk_task_creation_authorization_v284(
    id, trunk_id, workspace_id, command_id, actor, disposition,
    expected_trunk_version, returned_trunk_version, returned_lifecycle,
    assignment_id, policy_revision_id, provenance, repository_id,
    upstream_repository_id, publish_repository_id, base_source,
    base_repository_id, base_ref, planning_base_sha, assignment_base_sha,
    assignment_head_sha, engine_snapshot, work_model_snapshot, recorded_at_ms,
    task_name, task_type, linked_issue_number, opening_prompt, task_origin)
SELECT id, trunk_id, workspace_id, command_id, actor, disposition,
    expected_trunk_version, returned_trunk_version, returned_lifecycle,
    assignment_id, policy_revision_id, provenance, repository_id,
    upstream_repository_id, publish_repository_id, base_source,
    base_repository_id, base_ref, planning_base_sha, assignment_base_sha,
    assignment_head_sha, engine_snapshot, work_model_snapshot, recorded_at_ms,
    task_name, task_type, linked_issue_number, opening_prompt, task_origin
FROM trunk_task_creation_authorization;

ALTER TABLE trunk_task_creation_authorization
    RENAME TO trunk_task_creation_authorization_v283;
ALTER TABLE trunk_task_creation_authorization_v284
    RENAME TO trunk_task_creation_authorization;
DROP TABLE trunk_task_creation_authorization_v283;

DROP TRIGGER task_creation_context_owner_insert;
DROP TRIGGER task_creation_context_immutable;
DROP TRIGGER v2_task_creation_context_presentation_insert;
DROP TRIGGER v2_task_creation_context_presentation_immutable;

CREATE TABLE task_creation_context_v284 (
    task_id                   TEXT    NOT NULL PRIMARY KEY
        REFERENCES tasks(id) ON DELETE CASCADE,
    assignment_id             TEXT    NOT NULL REFERENCES task_assignment(id),
    policy_revision_id        TEXT    NOT NULL REFERENCES task_policy_revision(id),
    authorization_id          TEXT UNIQUE
        REFERENCES trunk_task_creation_authorization(id),
    provenance                TEXT    NOT NULL CHECK (provenance IN (
        'AGENT_HANDOFF', 'DIRECT_USER', 'ISSUE_MONITOR', 'AUTOMATION',
        'QUALITY_SCAN', 'REVIEW_SESSION')),
    repository_id             TEXT    NOT NULL,
    upstream_repository_id    TEXT,
    publish_repository_id     TEXT    NOT NULL,
    base_source               TEXT CHECK (base_source IN (
        'PLANNING_SNAPSHOT', 'FRESH_REMOTE_BASE', 'EXISTING_PR_HEAD')),
    base_repository_id        TEXT,
    base_ref                  TEXT,
    planning_base_sha         TEXT,
    assignment_base_sha       TEXT,
    assignment_head_sha       TEXT,
    engine_snapshot           TEXT    NOT NULL,
    work_model_snapshot       TEXT    NOT NULL,
    created_at_ms             INTEGER NOT NULL,
    task_name                 TEXT,
    task_type                 TEXT,
    linked_issue_number       INTEGER,
    opening_prompt            TEXT,
    task_origin               TEXT,
    CHECK (base_source IS NULL OR (length(base_repository_id) > 0
        AND ((base_source = 'PLANNING_SNAPSHOT'
                AND length(base_ref) > 0 AND planning_base_sha IS NOT NULL
                AND assignment_base_sha IS NULL
                AND assignment_head_sha IS NULL)
            OR (base_source = 'FRESH_REMOTE_BASE'
                AND length(base_ref) > 0 AND planning_base_sha IS NULL
                AND assignment_base_sha IS NULL
                AND assignment_head_sha IS NULL)
            OR (base_source = 'EXISTING_PR_HEAD'
                AND planning_base_sha IS NULL
                AND ((base_ref IS NULL AND assignment_base_sha IS NULL
                        AND assignment_head_sha IS NULL)
                  OR (length(base_ref) > 0 AND assignment_base_sha IS NOT NULL
                        AND assignment_head_sha IS NOT NULL))))))
);

INSERT INTO task_creation_context_v284(
    task_id, assignment_id, policy_revision_id, authorization_id, provenance,
    repository_id, upstream_repository_id, publish_repository_id, base_source,
    base_repository_id, base_ref, planning_base_sha, assignment_base_sha,
    assignment_head_sha, engine_snapshot, work_model_snapshot, created_at_ms,
    task_name, task_type, linked_issue_number, opening_prompt, task_origin)
SELECT task_id, assignment_id, policy_revision_id, authorization_id, provenance,
    repository_id, upstream_repository_id, publish_repository_id, base_source,
    base_repository_id, base_ref, planning_base_sha, assignment_base_sha,
    assignment_head_sha, engine_snapshot, work_model_snapshot, created_at_ms,
    task_name, task_type, linked_issue_number, opening_prompt, task_origin
FROM task_creation_context;

ALTER TABLE task_creation_context RENAME TO task_creation_context_v283;
ALTER TABLE task_creation_context_v284 RENAME TO task_creation_context;
DROP TABLE task_creation_context_v283;

DROP TRIGGER task_assignment_v2_exact_insert;
CREATE TRIGGER task_assignment_v2_exact_insert
BEFORE INSERT ON task_assignment
BEGIN
    SELECT CASE
        WHEN length(trim(NEW.id)) = 0 OR length(trim(NEW.trunk_id)) = 0
          OR length(trim(NEW.created_by)) = 0
          OR NEW.creation_authorization_id IS NULL
          OR length(trim(NEW.creation_authorization_id)) = 0
            THEN RAISE(ABORT, 'TaskAssignment identity must be exact')
        WHEN NOT EXISTS (
            SELECT 1 FROM threads trunk
            JOIN workspace_repos repository
              ON repository.workspace_id = trunk.workspace_id
            WHERE trunk.id = NEW.trunk_id AND trunk.turn_version = 'V2'
              AND trunk.lifecycle_state IN ('ACTIVE', 'IDLE'))
            THEN RAISE(ABORT, 'TaskAssignment requires a live V2 Trunk Workspace')
        WHEN NEW.kind = 'NEW_FROM_TRUNK' AND NOT (
            (NEW.planning_base_sha IS NULL OR length(trim(NEW.planning_base_sha)) > 0)
            AND NEW.plan_seed IS NOT NULL AND length(trim(NEW.plan_seed)) > 0
            AND NEW.prompt IS NOT NULL AND length(trim(NEW.prompt)) > 0
            AND NEW.source_id IS NULL AND NEW.repository_id IS NULL
            AND NEW.pr_number IS NULL AND NEW.remote_head_sha IS NULL
            AND NEW.producer IS NULL AND NEW.reason IS NULL
            AND NEW.selected_findings_json IS NULL
            AND NEW.base_repository_id IS NULL AND NEW.head_repository_id IS NULL
            AND NEW.base_ref IS NULL AND NEW.head_ref IS NULL
            AND NEW.remote_base_sha IS NULL AND NEW.repository_route IS NULL)
            THEN RAISE(ABORT, 'NewFromTrunk assignment shape is not exact')
        WHEN NEW.kind IN ('EXISTING_OWN_PR', 'REVIEW_FINDINGS') AND NOT (
            NEW.repository_id IS NOT NULL
            AND NEW.repository_id = NEW.head_repository_id
            AND NEW.pr_number > 0
            AND length(trim(NEW.base_repository_id)) > 0
            AND length(trim(NEW.head_repository_id)) > 0
            AND ((NEW.repository_route = 'DIRECT'
                    AND NEW.base_repository_id = NEW.head_repository_id)
                OR (NEW.repository_route = 'FORK'
                    AND NEW.base_repository_id <> NEW.head_repository_id))
            AND ((NEW.base_ref IS NULL AND NEW.head_ref IS NULL
                    AND NEW.remote_base_sha IS NULL AND NEW.remote_head_sha IS NULL)
                OR (length(trim(NEW.base_ref)) > 0
                    AND length(trim(NEW.head_ref)) > 0
                    AND length(trim(NEW.remote_base_sha)) > 0
                    AND length(trim(NEW.remote_head_sha)) > 0))
            AND NEW.planning_base_sha IS NULL AND NEW.plan_seed IS NULL
            AND NEW.prompt IS NULL AND NEW.producer IS NULL AND NEW.reason IS NULL
            AND ((NEW.kind = 'EXISTING_OWN_PR' AND NEW.source_id IS NULL
                    AND NEW.selected_findings_json IS NULL)
                OR (NEW.kind = 'REVIEW_FINDINGS'
                    AND length(trim(NEW.source_id)) > 0
                    AND NEW.selected_findings_json = '[]')))
            THEN RAISE(ABORT, 'Existing PR assignment shape is not exact')
        WHEN NEW.kind = 'ISSUE' AND NOT (
            length(trim(NEW.source_id)) > 0 AND NEW.repository_id IS NULL
            AND NEW.pr_number IS NULL AND NEW.remote_head_sha IS NULL
            AND NEW.planning_base_sha IS NULL AND NEW.plan_seed IS NULL
            AND NEW.prompt IS NULL AND NEW.producer IS NULL AND NEW.reason IS NULL
            AND NEW.selected_findings_json IS NULL
            AND NEW.base_repository_id IS NULL AND NEW.head_repository_id IS NULL
            AND NEW.base_ref IS NULL AND NEW.head_ref IS NULL
            AND NEW.remote_base_sha IS NULL AND NEW.repository_route IS NULL)
            THEN RAISE(ABORT, 'Issue assignment shape is not exact')
        WHEN NEW.kind = 'AUTOMATION' AND NOT (
            length(trim(NEW.producer)) > 0 AND length(trim(NEW.reason)) > 0
            AND NEW.source_id IS NULL AND NEW.repository_id IS NULL
            AND NEW.pr_number IS NULL AND NEW.remote_head_sha IS NULL
            AND NEW.planning_base_sha IS NULL AND NEW.plan_seed IS NULL
            AND NEW.prompt IS NULL AND NEW.selected_findings_json IS NULL
            AND NEW.base_repository_id IS NULL AND NEW.head_repository_id IS NULL
            AND NEW.base_ref IS NULL AND NEW.head_ref IS NULL
            AND NEW.remote_base_sha IS NULL AND NEW.repository_route IS NULL)
            THEN RAISE(ABORT, 'Automation assignment shape is not exact')
        WHEN NEW.kind = 'QUALITY_SCAN' AND NOT (
            length(trim(NEW.source_id)) > 0 AND NEW.repository_id IS NULL
            AND NEW.pr_number IS NULL AND NEW.remote_head_sha IS NULL
            AND NEW.planning_base_sha IS NULL AND NEW.plan_seed IS NULL
            AND NEW.prompt IS NULL AND NEW.producer IS NULL AND NEW.reason IS NULL
            AND NEW.selected_findings_json IS NULL
            AND NEW.base_repository_id IS NULL AND NEW.head_repository_id IS NULL
            AND NEW.base_ref IS NULL AND NEW.head_ref IS NULL
            AND NEW.remote_base_sha IS NULL AND NEW.repository_route IS NULL)
            THEN RAISE(ABORT, 'QualityScan assignment shape is not exact')
    END;
END;

CREATE TRIGGER trunk_task_creation_authorization_insert
BEFORE INSERT ON trunk_task_creation_authorization
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM threads trunk
            JOIN task_assignment assignment ON assignment.trunk_id = trunk.id
            JOIN task_policy_revision policy ON policy.trunk_id = trunk.id
            JOIN workspace_repos local_repo
              ON local_repo.workspace_id = trunk.workspace_id
            WHERE trunk.id = NEW.trunk_id
              AND trunk.workspace_id = NEW.workspace_id
              AND trunk.turn_version = 'V2'
              AND trunk.lifecycle_state = NEW.returned_lifecycle
              AND trunk.aggregate_version = NEW.returned_trunk_version
              AND assignment.id = NEW.assignment_id
              AND assignment.creation_authorization_id = NEW.id
              AND policy.id = NEW.policy_revision_id
              AND lower(local_repo.repo_full_name) = lower(NEW.repository_id)
              AND NEW.publish_repository_id = NEW.repository_id
              AND NOT EXISTS (
                  SELECT 1 FROM task_policy_revision newer
                  WHERE newer.trunk_id = policy.trunk_id
                    AND newer.revision > policy.revision))
            THEN RAISE(ABORT, 'Task creation authorization owner graph is not exact')
        WHEN NOT EXISTS (
            SELECT 1 FROM trunk_transition transition
            WHERE transition.trunk_id = NEW.trunk_id
              AND transition.command_id = NEW.command_id
              AND transition.from_state = NEW.returned_lifecycle
              AND transition.to_state = NEW.returned_lifecycle
              AND transition.aggregate_version = NEW.returned_trunk_version
              AND transition.cause = 'AUTHORIZE_TASK_CREATION'
              AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'Task creation authorization lacks exact Trunk CAS audit')
        WHEN NEW.upstream_repository_id IS NULL AND NOT (
            NEW.base_repository_id = NEW.repository_id
            AND NOT EXISTS (
                SELECT 1 FROM workspace_relation relation
                WHERE relation.workspace_id = NEW.workspace_id))
            THEN RAISE(ABORT, 'direct Task authorization conflicts with Workspace provenance')
        WHEN NEW.upstream_repository_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM workspace_relation relation
            JOIN workspace_repos upstream
              ON upstream.workspace_id = relation.upstream_workspace_id
            WHERE relation.workspace_id = NEW.workspace_id
              AND lower(upstream.repo_full_name) = lower(NEW.upstream_repository_id)
              AND lower(upstream.repo_full_name) = lower(NEW.base_repository_id))
            THEN RAISE(ABORT, 'fork Task authorization lacks exact upstream Workspace')
        WHEN NEW.base_source <> 'EXISTING_PR_HEAD' AND NOT EXISTS (
            SELECT 1 FROM workspace_repos base_repo
            WHERE lower(base_repo.repo_full_name) = lower(NEW.base_repository_id)
              AND base_repo.default_base_branch = NEW.base_ref
              AND base_repo.workspace_id = CASE
                  WHEN NEW.upstream_repository_id IS NULL THEN NEW.workspace_id
                  ELSE (SELECT relation.upstream_workspace_id
                        FROM workspace_relation relation
                        WHERE relation.workspace_id = NEW.workspace_id)
              END)
            THEN RAISE(ABORT, 'fresh/planning Task authorization lacks exact base ref')
        WHEN NOT (
            EXISTS (
                SELECT 1 FROM task_assignment assignment
                WHERE assignment.id = NEW.assignment_id
                  AND assignment.kind = 'NEW_FROM_TRUNK'
                  AND NEW.provenance = 'AGENT_HANDOFF'
                  AND NEW.base_source = 'PLANNING_SNAPSHOT'
                  AND assignment.planning_base_sha IS NEW.planning_base_sha)
            OR EXISTS (
                SELECT 1 FROM task_assignment assignment
                WHERE assignment.id = NEW.assignment_id
                  AND assignment.kind = 'NEW_FROM_TRUNK'
                  AND NEW.provenance = 'DIRECT_USER'
                  AND NEW.base_source = 'FRESH_REMOTE_BASE'
                  AND assignment.planning_base_sha IS NULL)
            OR EXISTS (
                SELECT 1 FROM task_assignment assignment
                WHERE assignment.id = NEW.assignment_id
                  AND assignment.kind = 'REVIEW_FINDINGS'
                  AND NEW.provenance = 'REVIEW_SESSION'
                  AND NEW.base_source = 'EXISTING_PR_HEAD'
                  AND assignment.repository_id = NEW.repository_id
                  AND assignment.head_repository_id = NEW.publish_repository_id
                  AND assignment.base_repository_id = NEW.base_repository_id
                  AND assignment.base_ref IS NEW.base_ref
                  AND assignment.remote_base_sha IS NEW.assignment_base_sha
                  AND assignment.remote_head_sha IS NEW.assignment_head_sha
                  AND ((assignment.repository_route = 'DIRECT'
                        AND NEW.upstream_repository_id IS NULL)
                    OR (assignment.repository_route = 'FORK'
                        AND assignment.base_repository_id = NEW.upstream_repository_id))
                  AND EXISTS (
                      SELECT 1 FROM task_assignment_review_finding finding
                      WHERE finding.assignment_id = assignment.id))
            OR EXISTS (
                SELECT 1 FROM task_assignment assignment
                WHERE assignment.id = NEW.assignment_id
                  AND assignment.kind = 'ISSUE'
                  AND NEW.provenance = 'ISSUE_MONITOR'
                  AND NEW.base_source = 'FRESH_REMOTE_BASE')
            OR EXISTS (
                SELECT 1 FROM task_assignment assignment
                WHERE assignment.id = NEW.assignment_id
                  AND assignment.kind = 'AUTOMATION'
                  AND NEW.provenance = 'AUTOMATION'
                  AND NEW.base_source = 'FRESH_REMOTE_BASE')
            OR EXISTS (
                SELECT 1 FROM task_assignment assignment
                WHERE assignment.id = NEW.assignment_id
                  AND assignment.kind = 'QUALITY_SCAN'
                  AND NEW.provenance = 'QUALITY_SCAN'
                  AND NEW.base_source = 'FRESH_REMOTE_BASE')
            OR EXISTS (
                SELECT 1 FROM task_assignment assignment
                WHERE assignment.id = NEW.assignment_id
                  AND assignment.kind = 'EXISTING_OWN_PR'
                  AND NEW.provenance = 'DIRECT_USER'
                  AND NEW.base_source = 'EXISTING_PR_HEAD'
                  AND assignment.repository_id = NEW.repository_id
                  AND assignment.head_repository_id = NEW.publish_repository_id
                  AND assignment.base_repository_id = NEW.base_repository_id
                  AND assignment.base_ref IS NEW.base_ref
                  AND assignment.remote_base_sha IS NEW.assignment_base_sha
                  AND assignment.remote_head_sha IS NEW.assignment_head_sha
                  AND ((assignment.repository_route = 'DIRECT'
                        AND NEW.upstream_repository_id IS NULL)
                    OR (assignment.repository_route = 'FORK'
                        AND assignment.base_repository_id = NEW.upstream_repository_id))))
            THEN RAISE(ABORT, 'Task creation authorization source is not exact')
    END;
END;

CREATE TRIGGER trunk_task_creation_authorization_immutable
BEFORE UPDATE ON trunk_task_creation_authorization
BEGIN SELECT RAISE(ABORT, 'Task creation authorization is immutable'); END;

CREATE TRIGGER v2_task_creation_authorization_presentation_insert
BEFORE INSERT ON trunk_task_creation_authorization
WHEN NEW.task_name IS NULL OR length(trim(NEW.task_name)) = 0
  OR NEW.task_type IS NULL OR length(trim(NEW.task_type)) = 0
  OR NEW.task_origin IS NULL OR length(trim(NEW.task_origin)) = 0
  OR (NEW.linked_issue_number IS NOT NULL AND NEW.linked_issue_number < 1)
  OR (NEW.opening_prompt IS NOT NULL AND length(trim(NEW.opening_prompt)) = 0)
BEGIN SELECT RAISE(ABORT, 'V2 Task presentation must be exact'); END;

CREATE TRIGGER task_creation_context_owner_insert
BEFORE INSERT ON task_creation_context
WHEN NEW.authorization_id IS NULL
  OR NOT EXISTS (
      SELECT 1 FROM tasks task
      JOIN trunk_task_creation_authorization authorization
        ON authorization.id = NEW.authorization_id
      WHERE task.id = NEW.task_id AND task.workflow_version = 'V2'
        AND task.thread_id = authorization.trunk_id
        AND task.assignment_id = NEW.assignment_id
        AND task.policy_revision_id = NEW.policy_revision_id
        AND authorization.assignment_id = NEW.assignment_id
        AND authorization.policy_revision_id = NEW.policy_revision_id
        AND authorization.provenance = NEW.provenance
        AND authorization.repository_id = NEW.repository_id
        AND authorization.upstream_repository_id IS NEW.upstream_repository_id
        AND authorization.publish_repository_id = NEW.publish_repository_id
        AND authorization.base_source = NEW.base_source
        AND authorization.base_repository_id = NEW.base_repository_id
        AND authorization.base_ref IS NEW.base_ref
        AND authorization.planning_base_sha IS NEW.planning_base_sha
        AND authorization.assignment_base_sha IS NEW.assignment_base_sha
        AND authorization.assignment_head_sha IS NEW.assignment_head_sha
        AND authorization.engine_snapshot = NEW.engine_snapshot
        AND authorization.work_model_snapshot = NEW.work_model_snapshot)
BEGIN SELECT RAISE(ABORT, 'Task creation context does not consume exact Trunk authorization'); END;

CREATE TRIGGER task_creation_context_immutable
BEFORE UPDATE ON task_creation_context
BEGIN SELECT RAISE(ABORT, 'Task creation context is immutable'); END;

CREATE TRIGGER v2_task_creation_context_presentation_insert
BEFORE INSERT ON task_creation_context
WHEN NOT EXISTS (
    SELECT 1 FROM tasks task
    JOIN trunk_task_creation_authorization authorization
      ON authorization.id = NEW.authorization_id
    WHERE task.id = NEW.task_id AND task.workflow_version = 'V2'
      AND task.assignment_id = NEW.assignment_id
      AND task.policy_revision_id = NEW.policy_revision_id
      AND task.name = NEW.task_name AND task.task_type = NEW.task_type
      AND task.linked_issue_number IS NEW.linked_issue_number
      AND task.opening_prompt IS NEW.opening_prompt
      AND task.origin = NEW.task_origin
      AND authorization.assignment_id = NEW.assignment_id
      AND authorization.policy_revision_id = NEW.policy_revision_id
      AND authorization.task_name = NEW.task_name
      AND authorization.task_type = NEW.task_type
      AND authorization.linked_issue_number IS NEW.linked_issue_number
      AND authorization.opening_prompt IS NEW.opening_prompt
      AND authorization.task_origin = NEW.task_origin)
BEGIN SELECT RAISE(ABORT, 'V2 Task presentation differs from Trunk authority'); END;

CREATE TRIGGER v2_task_creation_context_presentation_immutable
BEFORE UPDATE OF task_name, task_type, linked_issue_number, opening_prompt, task_origin
ON task_creation_context
WHEN NEW.task_name IS NOT OLD.task_name OR NEW.task_type IS NOT OLD.task_type
  OR NEW.linked_issue_number IS NOT OLD.linked_issue_number
  OR NEW.opening_prompt IS NOT OLD.opening_prompt
  OR NEW.task_origin IS NOT OLD.task_origin
BEGIN SELECT RAISE(ABORT, 'V2 Task presentation is immutable'); END;

DROP TRIGGER review_build_task_materialization;
CREATE TRIGGER review_build_task_materialization
BEFORE INSERT ON tasks
WHEN NEW.workflow_version = 'V2'
  AND EXISTS (
      SELECT 1 FROM task_assignment assignment
      JOIN threads trunk ON trunk.id = NEW.thread_id
      WHERE assignment.id = NEW.assignment_id
        AND assignment.trunk_id = trunk.id
        AND assignment.kind = 'REVIEW_FINDINGS')
  AND NOT EXISTS (
      SELECT 1 FROM task_assignment assignment
      JOIN threads trunk ON trunk.id = NEW.thread_id
      JOIN review_build_selection selection
        ON selection.thread_id = trunk.id
       AND selection.review_pass_id = assignment.source_id
      WHERE assignment.id = NEW.assignment_id
        AND assignment.trunk_id = trunk.id
        AND assignment.kind = 'REVIEW_FINDINGS'
        AND trunk.parent_review_pass_id = selection.review_pass_id
        AND trunk.workspace_id = selection.workspace_id
        AND selection.spawn_mode = 'author_is_reviewer'
        AND assignment.pr_number = selection.pr_number
        AND lower(assignment.base_repository_id)
              = lower(selection.base_repository_id)
        AND lower(assignment.head_repository_id)
              = lower(selection.head_repository_id)
        AND lower(assignment.repository_id)
              = lower(selection.head_repository_id)
        AND lower(selection.repo_full_name)
              = lower(selection.base_repository_id)
        AND ((lower(selection.base_repository_id)
                  = lower(selection.head_repository_id)
                AND assignment.repository_route = 'DIRECT')
          OR (lower(selection.base_repository_id)
                  <> lower(selection.head_repository_id)
                AND assignment.repository_route = 'FORK'))
        AND (SELECT COUNT(*)
             FROM task_assignment_review_finding assigned
             WHERE assigned.assignment_id = assignment.id)
            = (SELECT COUNT(*)
               FROM review_build_selection_item frozen
               WHERE frozen.thread_id = selection.thread_id)
        AND NOT EXISTS (
            SELECT 1 FROM review_build_selection_item frozen
            LEFT JOIN task_assignment_review_finding assigned
              ON assigned.assignment_id = assignment.id
             AND assigned.source_review_id = frozen.review_pass_id
             AND assigned.finding_id = frozen.finding_id
             AND assigned.finding_revision = frozen.finding_revision
             AND assigned.content_digest = frozen.content_digest
            JOIN review_findings current ON current.id = frozen.finding_id
            WHERE frozen.thread_id = selection.thread_id
              AND (assigned.finding_id IS NULL
                OR current.review_pass_id <> frozen.review_pass_id
                OR current.revision <> frozen.finding_revision
                OR current.status <> 'agreed')))
BEGIN SELECT RAISE(ABORT,
    'V2 Task materialization has a stale or mismatched review build selection'); END;

DROP TRIGGER provision_task_operation_owner_insert;
CREATE TRIGGER provision_task_operation_owner_insert
BEFORE INSERT ON provision_task_operation
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'REQUESTED'
            THEN RAISE(ABORT, 'ProvisionTaskOperation must start REQUESTED')
        WHEN NEW.base_source IS NULL OR NEW.base_repository_id IS NULL
          OR (NEW.base_source <> 'EXISTING_PR_HEAD' AND NEW.base_ref IS NULL)
            THEN RAISE(ABORT, 'ProvisionTaskOperation source must be typed')
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks task
            JOIN task_creation_context context ON context.task_id = task.id
            WHERE task.id = NEW.task_id
              AND task.workflow_version = 'V2'
              AND task.epoch = NEW.task_epoch
              AND task.lifecycle_state = 'PROVISIONING'
              AND task.assignment_id = NEW.assignment_id
              AND context.assignment_id = NEW.assignment_id
              AND context.repository_id = NEW.repository_id
              AND context.base_source = NEW.base_source
              AND context.base_repository_id = NEW.base_repository_id
              AND context.base_ref IS NEW.base_ref
              AND NEW.expected_base_sha IS CASE context.base_source
                    WHEN 'PLANNING_SNAPSHOT' THEN context.planning_base_sha
                    WHEN 'EXISTING_PR_HEAD' THEN context.assignment_base_sha
                    ELSE NULL END
              AND NEW.expected_remote_head_sha IS CASE context.base_source
                    WHEN 'EXISTING_PR_HEAD' THEN context.assignment_head_sha
                    ELSE NULL END)
            THEN RAISE(ABORT, 'ProvisionTaskOperation does not match frozen creation source')
    END;
END;

DROP TRIGGER provision_task_operation_dispatch_fence;
CREATE TRIGGER provision_task_operation_dispatch_fence
BEFORE UPDATE OF status ON provision_task_operation
WHEN NEW.status = 'DISPATCHED'
  AND NOT EXISTS (
      SELECT 1 FROM dispatch_ticket ticket
      WHERE ticket.operation_id = NEW.operation_id
        AND ticket.operation_kind = 'PROVISION_TASK'
        AND ticket.async_family = 'LOCAL_GIT'
        AND ticket.owner_kind = 'TASK' AND ticket.owner_id = NEW.task_id
        AND ticket.task_id = NEW.task_id AND ticket.task_epoch = NEW.task_epoch
        AND ticket.stage_id IS NULL AND ticket.stage_generation IS NULL
        AND ticket.attempt = NEW.semantic_attempt
        AND ticket.expected_code_fingerprint IS NULL
        AND ticket.expected_base_sha IS NEW.expected_base_sha
        AND ticket.expected_head_sha IS NEW.expected_remote_head_sha
        AND ticket.exclusive_task = 1 AND ticket.writer_required = 1
        AND ticket.lane_mask = CASE
              WHEN NEW.base_source = 'EXISTING_PR_HEAD'
                AND NEW.expected_base_sha IS NULL
                AND NEW.expected_remote_head_sha IS NULL THEN 48
              ELSE 16 END
        AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT,
    'dispatched provisioning requires exact dispatcher capacity'); END;

DROP TRIGGER provision_task_operation_result_update_fence;
CREATE TRIGGER provision_task_operation_result_update_fence
BEFORE UPDATE ON provision_task_operation
WHEN NEW.status = 'ACCEPTED'
  AND (NEW.result_evidence IS NULL OR length(trim(NEW.result_evidence)) = 0
    OR NEW.result_code_fingerprint IS NULL
    OR length(trim(NEW.result_code_fingerprint)) = 0
    OR (NEW.base_source = 'PLANNING_SNAPSHOT'
        AND (NEW.expected_base_sha IS NULL
          OR NEW.expected_remote_head_sha IS NOT NULL
          OR NEW.result_base_sha IS NOT NEW.expected_base_sha
          OR NEW.result_head_sha IS NOT NEW.expected_base_sha))
    OR (NEW.base_source = 'FRESH_REMOTE_BASE'
        AND (NEW.expected_base_sha IS NOT NULL
          OR NEW.expected_remote_head_sha IS NOT NULL
          OR length(trim(NEW.result_base_sha)) = 0
          OR NEW.result_head_sha IS NOT NEW.result_base_sha))
    OR (NEW.base_source = 'EXISTING_PR_HEAD' AND NOT (
        (NEW.expected_base_sha IS NOT NULL
          AND NEW.expected_remote_head_sha IS NOT NULL
          AND NEW.result_base_sha IS NEW.expected_base_sha
          AND NEW.result_head_sha IS NEW.expected_remote_head_sha)
        OR (NEW.expected_base_sha IS NULL
          AND NEW.expected_remote_head_sha IS NULL
          AND length(trim(NEW.result_base_sha)) > 0
          AND length(trim(NEW.result_head_sha)) > 0
          AND json_valid(NEW.result_evidence) = 1
          AND json_extract(NEW.result_evidence, '$.schema') = 'PROVISION_TASK_V2'
          AND json_extract(NEW.result_evidence, '$.baseSource') = 'EXISTING_PR_HEAD'
          AND json_extract(NEW.result_evidence, '$.baseSha') = NEW.result_base_sha
          AND json_extract(NEW.result_evidence, '$.headSha') = NEW.result_head_sha
          AND json_extract(NEW.result_evidence, '$.pullRequest.baseSha')
                = NEW.result_base_sha
          AND json_extract(NEW.result_evidence, '$.pullRequest.headSha')
                = NEW.result_head_sha
          AND EXISTS (
              SELECT 1 FROM task_assignment assignment
              JOIN task_creation_context context
                ON context.assignment_id = assignment.id
              WHERE assignment.id = NEW.assignment_id
                AND context.task_id = NEW.task_id
                AND assignment.kind IN ('EXISTING_OWN_PR', 'REVIEW_FINDINGS')
                AND assignment.pr_number
                    = json_extract(NEW.result_evidence, '$.pullRequest.number')
                AND lower(assignment.base_repository_id) = lower(json_extract(
                    NEW.result_evidence, '$.pullRequest.baseRepositoryId'))
                AND lower(assignment.head_repository_id) = lower(json_extract(
                    NEW.result_evidence, '$.pullRequest.headRepositoryId'))
                AND lower(context.base_repository_id) = lower(json_extract(
                    NEW.result_evidence, '$.pullRequest.baseRepositoryId'))
                AND lower(context.publish_repository_id) = lower(json_extract(
                    NEW.result_evidence, '$.pullRequest.headRepositoryId'))
                AND (assignment.kind = 'EXISTING_OWN_PR' OR EXISTS (
                    SELECT 1 FROM review_build_selection selection
                    WHERE selection.thread_id = (
                            SELECT thread_id FROM tasks WHERE id = NEW.task_id)
                      AND selection.review_pass_id = assignment.source_id
                      AND selection.pr_number = assignment.pr_number
                      AND selection.reviewed_head_sha = NEW.result_head_sha
                      AND lower(selection.base_repository_id) = lower(json_extract(
                          NEW.result_evidence, '$.pullRequest.baseRepositoryId'))
                      AND lower(selection.head_repository_id) = lower(json_extract(
                          NEW.result_evidence, '$.pullRequest.headRepositoryId'))
                      AND selection.base_ref = json_extract(
                          NEW.result_evidence, '$.pullRequest.baseRef')
                      AND selection.head_ref = json_extract(
                          NEW.result_evidence, '$.pullRequest.headRef')
                      AND (SELECT COUNT(*)
                           FROM task_assignment_review_finding assigned
                           WHERE assigned.assignment_id = assignment.id)
                          = (SELECT COUNT(*)
                             FROM review_build_selection_item frozen
                             WHERE frozen.thread_id = selection.thread_id)
                      AND NOT EXISTS (
                          SELECT 1 FROM review_build_selection_item frozen
                          LEFT JOIN task_assignment_review_finding assigned
                            ON assigned.assignment_id = assignment.id
                           AND assigned.source_review_id = frozen.review_pass_id
                           AND assigned.finding_id = frozen.finding_id
                           AND assigned.finding_revision = frozen.finding_revision
                           AND assigned.content_digest = frozen.content_digest
                          JOIN review_findings current
                            ON current.id = frozen.finding_id
                          WHERE frozen.thread_id = selection.thread_id
                            AND (assigned.finding_id IS NULL
                              OR current.review_pass_id <> frozen.review_pass_id
                              OR current.revision <> frozen.finding_revision
                              OR current.status <> 'agreed'))))))))
    OR NOT EXISTS (
        SELECT 1 FROM tasks task
        WHERE task.id = NEW.task_id AND task.workflow_version = 'V2'
          AND task.epoch = NEW.task_epoch
          AND task.lifecycle_state = 'PROVISIONING'
          AND task.assignment_id = NEW.assignment_id)
    OR NOT EXISTS (
        SELECT 1 FROM dispatch_ticket ticket
        WHERE ticket.operation_id = NEW.operation_id
          AND ticket.operation_kind = 'PROVISION_TASK'
          AND ticket.async_family = 'LOCAL_GIT'
          AND ticket.owner_kind = 'TASK' AND ticket.owner_id = NEW.task_id
          AND ticket.task_id = NEW.task_id AND ticket.task_epoch = NEW.task_epoch
          AND ticket.stage_id IS NULL AND ticket.stage_generation IS NULL
          AND ticket.attempt = NEW.semantic_attempt
          AND ticket.expected_code_fingerprint IS NULL
          AND ticket.expected_base_sha IS NEW.expected_base_sha
          AND ticket.expected_head_sha IS NEW.expected_remote_head_sha
          AND ticket.lane_mask = CASE
              WHEN NEW.base_source = 'EXISTING_PR_HEAD'
                AND NEW.expected_base_sha IS NULL
                AND NEW.expected_remote_head_sha IS NULL THEN 48
              ELSE 16 END
          AND ticket.status = 'RESULT_PENDING'
          AND ticket.pending_result_outcome = 'SUCCEEDED'
          AND ticket.pending_result_task_epoch = NEW.task_epoch
          AND ticket.pending_result_stage_id IS NULL
          AND ticket.pending_result_stage_generation IS NULL
          AND ticket.pending_result_operation_id = NEW.operation_id
          AND ticket.pending_result_attempt = NEW.semantic_attempt
          AND ticket.pending_result_expected_code_fingerprint IS NULL
          AND ticket.pending_result_expected_base_sha IS NEW.expected_base_sha
          AND ticket.pending_result_expected_head_sha IS NEW.expected_remote_head_sha
          AND ticket.pending_result_evidence = NEW.result_evidence))
BEGIN SELECT RAISE(ABORT,
    'accepted provisioning result lacks exact source/evidence'); END;

DROP TRIGGER task_creation_receipt_insert;
CREATE TRIGGER task_creation_receipt_insert
BEFORE INSERT ON task_creation_receipt
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks task
            JOIN threads trunk ON trunk.id = task.thread_id
            JOIN trunk_task_creation_authorization authorization
              ON authorization.id = NEW.authorization_id
            JOIN task_assignment assignment ON assignment.id = task.assignment_id
            JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
            JOIN task_creation_context context ON context.task_id = task.id
            JOIN task_brain brain ON brain.task_id = task.id
            JOIN task_provision_target target ON target.task_id = task.id
            WHERE task.id = NEW.task_id AND task.thread_id = NEW.trunk_id
              AND trunk.workspace_id = NEW.workspace_id
              AND trunk.turn_version = 'V2' AND task.workflow_version = 'V2'
              AND task.creation_receipt_id = NEW.id
              AND task.seq = NEW.task_seq AND task.epoch = NEW.task_epoch
              AND task.aggregate_version = NEW.task_version
              AND task.lifecycle_state = NEW.returned_lifecycle
              AND task.assignment_id = NEW.assignment_id
              AND task.policy_revision_id = NEW.policy_revision_id
              AND authorization.trunk_id = NEW.trunk_id
              AND authorization.workspace_id = NEW.workspace_id
              AND authorization.command_id = NEW.command_id
              AND authorization.actor = NEW.actor
              AND authorization.assignment_id = NEW.assignment_id
              AND authorization.policy_revision_id = NEW.policy_revision_id
              AND assignment.creation_authorization_id = authorization.id
              AND context.authorization_id = authorization.id
              AND brain.id = NEW.task_brain_id
              AND target.repository_id = context.repository_id
              AND target.publish_repository_id = context.publish_repository_id
              AND target.branch_name = NEW.requested_branch_name
              AND target.worktree_path = NEW.requested_worktree_path)
            THEN RAISE(ABORT, 'Task creation receipt owner/authorization graph is not exact')
        WHEN NOT EXISTS (
            SELECT 1 FROM provision_task_operation operation
            JOIN task_creation_context context ON context.task_id = operation.task_id
            JOIN task_provision_target target ON target.task_id = operation.task_id
            WHERE operation.id = NEW.provision_operation_id
              AND operation.task_id = NEW.task_id
              AND operation.task_epoch = NEW.task_epoch
              AND operation.assignment_id = NEW.assignment_id
              AND operation.operation_id = NEW.operation_id
              AND operation.semantic_attempt = NEW.semantic_attempt
              AND operation.repository_id = target.repository_id
              AND operation.base_source = context.base_source
              AND operation.base_repository_id = context.base_repository_id
              AND operation.base_ref IS context.base_ref
              AND operation.expected_base_sha IS CASE context.base_source
                    WHEN 'PLANNING_SNAPSHOT' THEN context.planning_base_sha
                    WHEN 'EXISTING_PR_HEAD' THEN context.assignment_base_sha
                    ELSE NULL END
              AND operation.expected_remote_head_sha IS CASE context.base_source
                    WHEN 'EXISTING_PR_HEAD' THEN context.assignment_head_sha
                    ELSE NULL END
              AND operation.requested_branch_name = target.branch_name
              AND operation.requested_worktree_path = target.worktree_path
              AND operation.status = 'REQUESTED'
              AND (SELECT COUNT(*) FROM provision_task_operation sibling
                   WHERE sibling.task_id = NEW.task_id) = 1)
            THEN RAISE(ABORT, 'Task creation receipt provisioning operation is not exact')
        WHEN NOT EXISTS (
            SELECT 1 FROM dispatch_ticket ticket
            JOIN task_creation_context context ON context.task_id = ticket.task_id
            WHERE ticket.id = NEW.dispatch_ticket_id
              AND ticket.operation_id = NEW.operation_id
              AND ticket.operation_kind = 'PROVISION_TASK'
              AND ticket.async_family = 'LOCAL_GIT'
              AND ticket.owner_kind = 'TASK' AND ticket.owner_id = NEW.task_id
              AND ticket.callback_route = 'TASK_PROVISION_RESULT'
              AND ticket.workspace_id = NEW.workspace_id
              AND ticket.trunk_id = NEW.trunk_id
              AND ticket.task_id = NEW.task_id
              AND ticket.task_epoch = NEW.task_epoch
              AND ticket.stage_id IS NULL AND ticket.stage_generation IS NULL
              AND ticket.attempt = NEW.semantic_attempt
              AND ticket.expected_code_fingerprint IS NULL
              AND ticket.expected_base_sha IS CASE context.base_source
                    WHEN 'PLANNING_SNAPSHOT' THEN context.planning_base_sha
                    WHEN 'EXISTING_PR_HEAD' THEN context.assignment_base_sha
                    ELSE NULL END
              AND ticket.expected_head_sha IS CASE context.base_source
                    WHEN 'EXISTING_PR_HEAD' THEN context.assignment_head_sha
                    ELSE NULL END
              AND ticket.exclusive_task = 1 AND ticket.writer_required = 1
              AND ticket.lane_mask = CASE
                    WHEN context.base_source = 'EXISTING_PR_HEAD'
                      AND context.assignment_base_sha IS NULL
                      AND context.assignment_head_sha IS NULL THEN 48
                    ELSE 16 END
              AND ticket.status = 'REQUESTED'
              AND (SELECT COUNT(*) FROM dispatch_ticket sibling
                   WHERE sibling.operation_id = NEW.operation_id) = 1)
            THEN RAISE(ABORT, 'Task creation receipt DispatchTicket is not exact')
        WHEN NOT EXISTS (
            SELECT 1 FROM task_transition transition
            WHERE transition.task_id = NEW.task_id
              AND transition.command_id = NEW.command_id
              AND transition.epoch = NEW.task_epoch
              AND transition.from_state IS NULL
              AND transition.to_state = 'PROVISIONING'
              AND transition.aggregate_version = NEW.task_version
              AND transition.cause = 'CREATE_TASK'
              AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'Task creation receipt lacks exact Task transition')
        WHEN EXISTS (
            SELECT 1 FROM task_current_stage current
            WHERE current.task_id = NEW.task_id)
          OR EXISTS (
            SELECT 1 FROM task_code_identity code
            WHERE code.task_id = NEW.task_id)
            THEN RAISE(ABORT, 'new PROVISIONING Task cannot already own code or Stage')
    END;
END;

RELEASE v284_deferred_pr_subject;
PRAGMA legacy_alter_table = OFF;
PRAGMA foreign_keys = ON;
