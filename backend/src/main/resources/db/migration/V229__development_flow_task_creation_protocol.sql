-- Durable, inert V2 Task-creation protocol. TrunkManager first authorizes an
-- immutable TaskAssignment with an optimistic Trunk CAS. TaskManager then
-- consumes that exact authorization while atomically creating the epoch-one
-- PROVISIONING Task, its frozen context, first ProvisionTaskOperation, and one
-- DispatchTicket. No production route is enabled by this migration.

-- task_assignment and task_creation_context were introduced with checks that
-- required every NEW_FROM_TRUNK Task to already know a base SHA. That would
-- turn asynchronous provisioning into synchronous Git work. Rebuild both
-- tables transactionally so fresh-base Tasks can freeze repository/ref intent
-- and let ProvisionTaskOperation persist the resolved SHA.
PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;
SAVEPOINT v229_task_creation;

CREATE TABLE task_assignment_v229 (
    id                      TEXT    NOT NULL PRIMARY KEY,
    trunk_id                TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    kind                    TEXT    NOT NULL CHECK (kind IN (
        'NEW_FROM_TRUNK', 'EXISTING_OWN_PR', 'REVIEW_FINDINGS',
        'ISSUE', 'AUTOMATION', 'QUALITY_SCAN')),
    source_id               TEXT,
    repository_id           TEXT,
    pr_number               INTEGER,
    remote_head_sha         TEXT,
    planning_base_sha       TEXT,
    plan_seed               TEXT,
    prompt                  TEXT,
    producer                TEXT,
    reason                  TEXT,
    selected_findings_json  TEXT,
    created_by              TEXT    NOT NULL,
    created_at_ms           INTEGER NOT NULL,
    base_repository_id      TEXT,
    head_repository_id      TEXT,
    base_ref                TEXT,
    head_ref                TEXT,
    remote_base_sha         TEXT,
    repository_route        TEXT CHECK (repository_route IN ('DIRECT', 'FORK')),
    creation_authorization_id TEXT UNIQUE
        REFERENCES trunk_task_creation_authorization(id)
        DEFERRABLE INITIALLY DEFERRED
);

INSERT INTO task_assignment_v229(
    id, trunk_id, kind, source_id, repository_id, pr_number,
    remote_head_sha, planning_base_sha, plan_seed, prompt, producer, reason,
    selected_findings_json, created_by, created_at_ms)
SELECT id, trunk_id, kind, source_id, repository_id, pr_number,
    remote_head_sha, planning_base_sha, plan_seed, prompt, producer, reason,
    selected_findings_json, created_by, created_at_ms
FROM task_assignment;

ALTER TABLE task_assignment RENAME TO task_assignment_v228;
ALTER TABLE task_assignment_v229 RENAME TO task_assignment;
DROP TABLE task_assignment_v228;

CREATE INDEX idx_task_assignment_trunk_created
    ON task_assignment(trunk_id, created_at_ms);

CREATE TRIGGER task_assignment_immutable
BEFORE UPDATE ON task_assignment
BEGIN SELECT RAISE(ABORT, 'TaskAssignment is immutable'); END;

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
            SELECT 1
            FROM threads trunk
            JOIN workspace_repos repository
              ON repository.workspace_id = trunk.workspace_id
            WHERE trunk.id = NEW.trunk_id
              AND trunk.turn_version = 'V2'
              AND trunk.lifecycle_state IN ('ACTIVE', 'IDLE'))
            THEN RAISE(ABORT, 'TaskAssignment requires a live V2 Trunk Workspace')
        WHEN NEW.kind = 'NEW_FROM_TRUNK' AND NOT (
            (NEW.planning_base_sha IS NULL
                OR length(trim(NEW.planning_base_sha)) > 0)
            AND NEW.plan_seed IS NOT NULL AND length(trim(NEW.plan_seed)) > 0
            AND NEW.prompt IS NOT NULL AND length(trim(NEW.prompt)) > 0
            AND NEW.source_id IS NULL AND NEW.repository_id IS NULL
            AND NEW.pr_number IS NULL AND NEW.remote_head_sha IS NULL
            AND NEW.producer IS NULL AND NEW.reason IS NULL
            AND NEW.selected_findings_json IS NULL
            AND NEW.base_repository_id IS NULL
            AND NEW.head_repository_id IS NULL AND NEW.base_ref IS NULL
            AND NEW.head_ref IS NULL AND NEW.remote_base_sha IS NULL
            AND NEW.repository_route IS NULL)
            THEN RAISE(ABORT, 'NewFromTrunk assignment shape is not exact')
        WHEN NEW.kind = 'EXISTING_OWN_PR' AND NOT (
            NEW.source_id IS NULL
            AND NEW.repository_id IS NOT NULL
            AND NEW.repository_id = NEW.head_repository_id
            AND NEW.pr_number > 0
            AND NEW.remote_head_sha IS NOT NULL
            AND length(trim(NEW.remote_head_sha)) > 0
            AND NEW.remote_base_sha IS NOT NULL
            AND length(trim(NEW.remote_base_sha)) > 0
            AND NEW.base_repository_id IS NOT NULL
            AND length(trim(NEW.base_repository_id)) > 0
            AND NEW.head_repository_id IS NOT NULL
            AND length(trim(NEW.head_repository_id)) > 0
            AND NEW.base_ref IS NOT NULL AND length(trim(NEW.base_ref)) > 0
            AND NEW.head_ref IS NOT NULL AND length(trim(NEW.head_ref)) > 0
            AND ((NEW.repository_route = 'DIRECT'
                    AND NEW.base_repository_id = NEW.head_repository_id)
                OR (NEW.repository_route = 'FORK'
                    AND NEW.base_repository_id <> NEW.head_repository_id))
            AND NEW.planning_base_sha IS NULL AND NEW.plan_seed IS NULL
            AND NEW.prompt IS NULL AND NEW.producer IS NULL
            AND NEW.reason IS NULL AND NEW.selected_findings_json IS NULL)
            THEN RAISE(ABORT, 'ExistingOwnPr assignment shape is not exact')
        WHEN NEW.kind = 'REVIEW_FINDINGS' AND NOT (
            NEW.source_id IS NOT NULL AND length(trim(NEW.source_id)) > 0
            AND NEW.selected_findings_json = '[]'
            AND NEW.repository_id IS NOT NULL
            AND NEW.repository_id = NEW.head_repository_id
            AND NEW.pr_number > 0
            AND NEW.remote_head_sha IS NOT NULL
            AND length(trim(NEW.remote_head_sha)) > 0
            AND NEW.remote_base_sha IS NOT NULL
            AND length(trim(NEW.remote_base_sha)) > 0
            AND NEW.base_repository_id IS NOT NULL
            AND length(trim(NEW.base_repository_id)) > 0
            AND NEW.head_repository_id IS NOT NULL
            AND length(trim(NEW.head_repository_id)) > 0
            AND NEW.base_ref IS NOT NULL AND length(trim(NEW.base_ref)) > 0
            AND NEW.head_ref IS NOT NULL AND length(trim(NEW.head_ref)) > 0
            AND ((NEW.repository_route = 'DIRECT'
                    AND NEW.base_repository_id = NEW.head_repository_id)
                OR (NEW.repository_route = 'FORK'
                    AND NEW.base_repository_id <> NEW.head_repository_id))
            AND NEW.planning_base_sha IS NULL AND NEW.plan_seed IS NULL
            AND NEW.prompt IS NULL AND NEW.producer IS NULL
            AND NEW.reason IS NULL)
            THEN RAISE(ABORT, 'ReviewFindings assignment shape is not exact')
        WHEN NEW.kind = 'ISSUE' AND NOT (
            NEW.source_id IS NOT NULL AND length(trim(NEW.source_id)) > 0
            AND NEW.repository_id IS NULL AND NEW.pr_number IS NULL
            AND NEW.remote_head_sha IS NULL AND NEW.planning_base_sha IS NULL
            AND NEW.plan_seed IS NULL AND NEW.prompt IS NULL
            AND NEW.producer IS NULL AND NEW.reason IS NULL
            AND NEW.selected_findings_json IS NULL
            AND NEW.base_repository_id IS NULL
            AND NEW.head_repository_id IS NULL AND NEW.base_ref IS NULL
            AND NEW.head_ref IS NULL AND NEW.remote_base_sha IS NULL
            AND NEW.repository_route IS NULL)
            THEN RAISE(ABORT, 'Issue assignment shape is not exact')
        WHEN NEW.kind = 'AUTOMATION' AND NOT (
            NEW.producer IS NOT NULL AND length(trim(NEW.producer)) > 0
            AND NEW.reason IS NOT NULL AND length(trim(NEW.reason)) > 0
            AND NEW.source_id IS NULL AND NEW.repository_id IS NULL
            AND NEW.pr_number IS NULL AND NEW.remote_head_sha IS NULL
            AND NEW.planning_base_sha IS NULL AND NEW.plan_seed IS NULL
            AND NEW.prompt IS NULL AND NEW.selected_findings_json IS NULL
            AND NEW.base_repository_id IS NULL
            AND NEW.head_repository_id IS NULL AND NEW.base_ref IS NULL
            AND NEW.head_ref IS NULL AND NEW.remote_base_sha IS NULL
            AND NEW.repository_route IS NULL)
            THEN RAISE(ABORT, 'Automation assignment shape is not exact')
        WHEN NEW.kind = 'QUALITY_SCAN' AND NOT (
            NEW.source_id IS NOT NULL AND length(trim(NEW.source_id)) > 0
            AND NEW.repository_id IS NULL AND NEW.pr_number IS NULL
            AND NEW.remote_head_sha IS NULL AND NEW.planning_base_sha IS NULL
            AND NEW.plan_seed IS NULL AND NEW.prompt IS NULL
            AND NEW.producer IS NULL AND NEW.reason IS NULL
            AND NEW.selected_findings_json IS NULL
            AND NEW.base_repository_id IS NULL
            AND NEW.head_repository_id IS NULL AND NEW.base_ref IS NULL
            AND NEW.head_ref IS NULL AND NEW.remote_base_sha IS NULL
            AND NEW.repository_route IS NULL)
            THEN RAISE(ABORT, 'QualityScan assignment shape is not exact')
    END;
END;

CREATE TABLE task_creation_context_v229 (
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
    CHECK (base_source IS NULL OR (
        length(base_repository_id) > 0 AND length(base_ref) > 0
        AND ((base_source = 'PLANNING_SNAPSHOT'
                AND planning_base_sha IS NOT NULL
                AND assignment_base_sha IS NULL
                AND assignment_head_sha IS NULL)
            OR (base_source = 'FRESH_REMOTE_BASE'
                AND planning_base_sha IS NULL
                AND assignment_base_sha IS NULL
                AND assignment_head_sha IS NULL)
            OR (base_source = 'EXISTING_PR_HEAD'
                AND planning_base_sha IS NULL
                AND assignment_base_sha IS NOT NULL
                AND assignment_head_sha IS NOT NULL))))
);

INSERT INTO task_creation_context_v229(
    task_id, assignment_id, policy_revision_id, provenance,
    repository_id, upstream_repository_id, publish_repository_id,
    planning_base_sha, assignment_head_sha, engine_snapshot,
    work_model_snapshot, created_at_ms)
SELECT task_id, assignment_id, policy_revision_id, provenance,
    repository_id, upstream_repository_id, publish_repository_id,
    planning_base_sha, assignment_head_sha, engine_snapshot,
    work_model_snapshot, created_at_ms
FROM task_creation_context;

ALTER TABLE task_creation_context RENAME TO task_creation_context_v228;
ALTER TABLE task_creation_context_v229 RENAME TO task_creation_context;
DROP TABLE task_creation_context_v228;
PRAGMA legacy_alter_table = OFF;

-- ── Typed findings and immutable policy sequence ────────────────────────
CREATE TABLE task_assignment_review_finding (
    assignment_id       TEXT    NOT NULL
        REFERENCES task_assignment(id) ON DELETE CASCADE,
    position            INTEGER NOT NULL CHECK (position > 0),
    source_review_id    TEXT    NOT NULL,
    finding_id          TEXT    NOT NULL,
    finding_revision    INTEGER NOT NULL CHECK (finding_revision > 0),
    content_digest      TEXT    NOT NULL,
    PRIMARY KEY (assignment_id, position),
    UNIQUE (assignment_id, finding_id),
    CHECK (length(source_review_id) > 0 AND length(finding_id) > 0
        AND length(content_digest) > 0)
);

CREATE TRIGGER task_assignment_review_finding_insert
BEFORE INSERT ON task_assignment_review_finding
WHEN NEW.position <> COALESCE((
        SELECT MAX(f.position) + 1
        FROM task_assignment_review_finding f
        WHERE f.assignment_id = NEW.assignment_id), 1)
  OR NOT EXISTS (
      SELECT 1 FROM task_assignment assignment
      WHERE assignment.id = NEW.assignment_id
        AND assignment.kind = 'REVIEW_FINDINGS'
        AND assignment.source_id = NEW.source_review_id
        AND assignment.selected_findings_json = '[]')
BEGIN SELECT RAISE(ABORT, 'typed review finding does not match its assignment'); END;

CREATE TRIGGER task_assignment_review_finding_immutable
BEFORE UPDATE ON task_assignment_review_finding
BEGIN SELECT RAISE(ABORT, 'TaskAssignment review finding is immutable'); END;

CREATE TRIGGER task_assignment_review_finding_delete_guard
BEFORE DELETE ON task_assignment_review_finding
BEGIN SELECT RAISE(ABORT, 'TaskAssignment review finding cannot be deleted'); END;

CREATE TRIGGER task_policy_revision_sequence_insert
BEFORE INSERT ON task_policy_revision
WHEN length(trim(NEW.id)) = 0 OR length(trim(NEW.source)) = 0
  OR length(trim(NEW.created_by)) = 0
  OR NEW.revision <> COALESCE((
      SELECT MAX(policy.revision) + 1
      FROM task_policy_revision policy
      WHERE policy.trunk_id = NEW.trunk_id), 1)
  OR NOT EXISTS (
      SELECT 1 FROM threads trunk
      WHERE trunk.id = NEW.trunk_id AND trunk.turn_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'Task policy revision is not the next exact V2 revision'); END;

-- ── TrunkManager Task-creation authorization ────────────────────────────
CREATE TABLE trunk_task_creation_authorization (
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
    base_ref                   TEXT    NOT NULL,
    planning_base_sha          TEXT,
    assignment_base_sha        TEXT,
    assignment_head_sha        TEXT,
    engine_snapshot            TEXT    NOT NULL,
    work_model_snapshot        TEXT    NOT NULL,
    recorded_at_ms             INTEGER NOT NULL,
    UNIQUE (trunk_id, command_id),
    CHECK (returned_trunk_version = expected_trunk_version + 1),
    CHECK (length(actor) > 0 AND length(base_repository_id) > 0
        AND length(base_ref) > 0 AND length(engine_snapshot) > 0
        AND length(work_model_snapshot) > 0),
    CHECK ((base_source = 'PLANNING_SNAPSHOT'
            AND planning_base_sha IS NOT NULL
            AND assignment_base_sha IS NULL AND assignment_head_sha IS NULL)
        OR (base_source = 'FRESH_REMOTE_BASE'
            AND planning_base_sha IS NULL
            AND assignment_base_sha IS NULL AND assignment_head_sha IS NULL)
        OR (base_source = 'EXISTING_PR_HEAD'
            AND planning_base_sha IS NULL
            AND assignment_base_sha IS NOT NULL AND assignment_head_sha IS NOT NULL))
);

CREATE TRIGGER trunk_task_creation_authorization_insert
BEFORE INSERT ON trunk_task_creation_authorization
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM threads trunk
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
                SELECT 1
                FROM workspace_relation relation
                WHERE relation.workspace_id = NEW.workspace_id))
            THEN RAISE(ABORT, 'direct Task authorization conflicts with Workspace provenance')
        WHEN NEW.upstream_repository_id IS NOT NULL AND NOT EXISTS (
            SELECT 1
            FROM workspace_relation relation
            JOIN workspace_repos upstream
              ON upstream.workspace_id = relation.upstream_workspace_id
            WHERE relation.workspace_id = NEW.workspace_id
              AND lower(upstream.repo_full_name) = lower(NEW.upstream_repository_id)
              AND lower(upstream.repo_full_name) = lower(NEW.base_repository_id))
            THEN RAISE(ABORT, 'fork Task authorization lacks exact upstream Workspace')
        WHEN NEW.base_source <> 'EXISTING_PR_HEAD' AND NOT EXISTS (
            SELECT 1
            FROM workspace_repos base_repo
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
                  AND assignment.base_ref = NEW.base_ref
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
                  AND assignment.base_ref = NEW.base_ref
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

CREATE TRIGGER task_creation_context_owner_insert
BEFORE INSERT ON task_creation_context
WHEN NEW.authorization_id IS NULL
  OR NOT EXISTS (
      SELECT 1
      FROM tasks task
      JOIN trunk_task_creation_authorization authorization
        ON authorization.id = NEW.authorization_id
      WHERE task.id = NEW.task_id
        AND task.workflow_version = 'V2'
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
        AND authorization.base_ref = NEW.base_ref
        AND authorization.planning_base_sha IS NEW.planning_base_sha
        AND authorization.assignment_base_sha IS NEW.assignment_base_sha
        AND authorization.assignment_head_sha IS NEW.assignment_head_sha
        AND authorization.engine_snapshot = NEW.engine_snapshot
        AND authorization.work_model_snapshot = NEW.work_model_snapshot)
BEGIN SELECT RAISE(ABORT, 'Task creation context does not consume exact Trunk authorization'); END;

CREATE TRIGGER task_creation_context_immutable
BEFORE UPDATE ON task_creation_context
BEGIN SELECT RAISE(ABORT, 'Task creation context is immutable'); END;

-- ── ProvisionTaskOperation source and discovered-result protocol ────────
ALTER TABLE provision_task_operation ADD COLUMN base_source TEXT
    CHECK (base_source IN ('PLANNING_SNAPSHOT', 'FRESH_REMOTE_BASE', 'EXISTING_PR_HEAD'));
ALTER TABLE provision_task_operation ADD COLUMN base_repository_id TEXT;
ALTER TABLE provision_task_operation ADD COLUMN base_ref TEXT;
ALTER TABLE provision_task_operation ADD COLUMN result_evidence TEXT;

DROP TRIGGER provision_task_operation_owner_insert;
DROP TRIGGER provision_task_operation_dispatch_fence;
DROP TRIGGER provision_task_operation_result_update_fence;

CREATE TRIGGER provision_task_operation_owner_insert
BEFORE INSERT ON provision_task_operation
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'REQUESTED'
            THEN RAISE(ABORT, 'ProvisionTaskOperation must start REQUESTED')
        WHEN NEW.base_source IS NULL OR NEW.base_repository_id IS NULL
          OR NEW.base_ref IS NULL
            THEN RAISE(ABORT, 'ProvisionTaskOperation source must be typed')
        WHEN NOT EXISTS (
            SELECT 1
            FROM tasks task
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
              AND context.base_ref = NEW.base_ref
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

CREATE TRIGGER provision_task_operation_dispatch_fence
BEFORE UPDATE OF status ON provision_task_operation
WHEN NEW.status = 'DISPATCHED'
  AND NOT EXISTS (
      SELECT 1
      FROM dispatch_ticket ticket
      WHERE ticket.operation_id = NEW.operation_id
        AND ticket.operation_kind = 'PROVISION_TASK'
        AND ticket.async_family = 'LOCAL_GIT'
        AND ticket.owner_kind = 'TASK'
        AND ticket.owner_id = NEW.task_id
        AND ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND ticket.stage_id IS NULL AND ticket.stage_generation IS NULL
        AND ticket.attempt = NEW.semantic_attempt
        AND ticket.expected_code_fingerprint IS NULL
        AND ticket.expected_base_sha IS NEW.expected_base_sha
        AND ticket.expected_head_sha IS NEW.expected_remote_head_sha
        AND ticket.exclusive_task = 1 AND ticket.writer_required = 1
        AND ticket.lane_mask = 16
        AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'dispatched provisioning requires exact LOCAL_GIT ticket'); END;

CREATE TRIGGER provision_task_operation_v229_identity_immutable
BEFORE UPDATE OF base_source, base_repository_id, base_ref
ON provision_task_operation
WHEN NEW.base_source IS NOT OLD.base_source
  OR NEW.base_repository_id IS NOT OLD.base_repository_id
  OR NEW.base_ref IS NOT OLD.base_ref
BEGIN SELECT RAISE(ABORT, 'ProvisionTaskOperation source is immutable'); END;

CREATE TRIGGER provision_task_operation_result_fields_guard
BEFORE UPDATE OF result_base_sha, result_head_sha, result_code_fingerprint,
        result_evidence ON provision_task_operation
WHEN OLD.status <> 'DISPATCHED' OR NEW.status <> 'ACCEPTED'
BEGIN SELECT RAISE(ABORT, 'ProvisionTaskOperation result requires acceptance'); END;

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
    OR (NEW.base_source = 'EXISTING_PR_HEAD'
        AND (NEW.expected_base_sha IS NULL
          OR NEW.expected_remote_head_sha IS NULL
          OR NEW.result_base_sha IS NOT NEW.expected_base_sha
          OR NEW.result_head_sha IS NOT NEW.expected_remote_head_sha))
    OR NOT EXISTS (
        SELECT 1 FROM tasks task
        WHERE task.id = NEW.task_id
          AND task.workflow_version = 'V2'
          AND task.epoch = NEW.task_epoch
          AND task.lifecycle_state = 'PROVISIONING'
          AND task.assignment_id = NEW.assignment_id)
    OR NOT EXISTS (
        SELECT 1
        FROM dispatch_ticket ticket
        WHERE ticket.operation_id = NEW.operation_id
          AND ticket.operation_kind = 'PROVISION_TASK'
          AND ticket.async_family = 'LOCAL_GIT'
          AND ticket.owner_kind = 'TASK'
          AND ticket.owner_id = NEW.task_id
          AND ticket.task_id = NEW.task_id
          AND ticket.task_epoch = NEW.task_epoch
          AND ticket.stage_id IS NULL
          AND ticket.stage_generation IS NULL
          AND ticket.attempt = NEW.semantic_attempt
          AND ticket.expected_code_fingerprint IS NULL
          AND ticket.expected_base_sha IS NEW.expected_base_sha
          AND ticket.expected_head_sha IS NEW.expected_remote_head_sha
          AND ticket.lane_mask = 16
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
BEGIN SELECT RAISE(ABORT, 'accepted provisioning result lacks exact source/evidence'); END;

CREATE TRIGGER task_code_identity_v229_insert_fence
BEFORE INSERT ON task_code_identity
WHEN NOT EXISTS (
    SELECT 1
    FROM provision_task_operation operation
    JOIN dispatch_ticket ticket ON ticket.operation_id = operation.operation_id
    WHERE operation.id = NEW.provision_operation_id
      AND operation.task_id = NEW.task_id
      AND operation.status = 'ACCEPTED'
      AND operation.result_evidence IS NOT NULL
      AND operation.result_base_sha = NEW.base_sha
      AND operation.result_head_sha = NEW.local_head_sha
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = 'SUCCEEDED'
      AND ticket.pending_result_evidence = operation.result_evidence)
BEGIN SELECT RAISE(ABORT, 'TaskCodeIdentity lacks exact discovered provisioning evidence'); END;

-- ── One immutable Task provisioning target ──────────────────────────────
CREATE TABLE task_provision_target (
    task_id                TEXT    NOT NULL PRIMARY KEY
        REFERENCES tasks(id) ON DELETE CASCADE,
    repository_id          TEXT    NOT NULL,
    publish_repository_id  TEXT    NOT NULL,
    branch_name            TEXT    NOT NULL,
    worktree_path          TEXT    NOT NULL UNIQUE,
    created_at_ms          INTEGER NOT NULL,
    UNIQUE (publish_repository_id, branch_name),
    CHECK (length(repository_id) > 0 AND length(publish_repository_id) > 0
        AND length(branch_name) > 0 AND length(worktree_path) > 0)
);

CREATE TRIGGER task_provision_target_insert
BEFORE INSERT ON task_provision_target
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN task_creation_context context ON context.task_id = task.id
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'PROVISIONING'
      AND task.epoch = 1
      AND context.repository_id = NEW.repository_id
      AND context.publish_repository_id = NEW.publish_repository_id)
BEGIN SELECT RAISE(ABORT, 'Task provision target lacks exact creation context'); END;

CREATE TRIGGER task_provision_target_immutable
BEFORE UPDATE ON task_provision_target
BEGIN SELECT RAISE(ABORT, 'Task provision target is immutable'); END;

-- ── Atomic TaskManager creation receipt ─────────────────────────────────
CREATE TABLE task_creation_receipt (
    id                       TEXT    NOT NULL PRIMARY KEY,
    trunk_id                 TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    workspace_id             TEXT    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    command_id               TEXT    NOT NULL,
    actor                    TEXT    NOT NULL,
    authorization_id         TEXT    NOT NULL UNIQUE
        REFERENCES trunk_task_creation_authorization(id),
    task_id                  TEXT    NOT NULL UNIQUE
        REFERENCES tasks(id) DEFERRABLE INITIALLY DEFERRED,
    task_seq                 INTEGER NOT NULL CHECK (task_seq > 0),
    task_epoch               INTEGER NOT NULL CHECK (task_epoch = 1),
    task_version             INTEGER NOT NULL CHECK (task_version = 0),
    returned_lifecycle       TEXT    NOT NULL CHECK (returned_lifecycle = 'PROVISIONING'),
    assignment_id            TEXT    NOT NULL UNIQUE REFERENCES task_assignment(id),
    policy_revision_id       TEXT    NOT NULL REFERENCES task_policy_revision(id),
    task_brain_id            TEXT    NOT NULL UNIQUE REFERENCES task_brain(id),
    provision_operation_id   TEXT    NOT NULL UNIQUE REFERENCES provision_task_operation(id),
    dispatch_ticket_id       TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    operation_id             TEXT    NOT NULL UNIQUE,
    semantic_attempt         INTEGER NOT NULL CHECK (semantic_attempt = 1),
    requested_branch_name    TEXT    NOT NULL,
    requested_worktree_path  TEXT    NOT NULL,
    recorded_at_ms           INTEGER NOT NULL,
    UNIQUE (trunk_id, command_id),
    CHECK (length(actor) > 0 AND length(operation_id) > 0
        AND length(requested_branch_name) > 0
        AND length(requested_worktree_path) > 0)
);

ALTER TABLE tasks ADD COLUMN creation_receipt_id TEXT
    REFERENCES task_creation_receipt(id) DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX idx_tasks_creation_receipt
    ON tasks(creation_receipt_id) WHERE creation_receipt_id IS NOT NULL;

CREATE TRIGGER v2_task_creation_authority_insert
BEFORE INSERT ON tasks
WHEN NEW.workflow_version = 'V2'
  AND (NEW.creation_receipt_id IS NULL
    OR NEW.epoch <> 1 OR NEW.aggregate_version <> 0
    OR NEW.lifecycle_state <> 'PROVISIONING'
    OR NEW.branch_name IS NOT NULL OR NEW.worktree_path IS NOT NULL
    OR NEW.seq <> COALESCE((
        SELECT MAX(sibling.seq) + 1 FROM tasks sibling
        WHERE sibling.thread_id = NEW.thread_id), 1)
    OR NOT EXISTS (
        SELECT 1
        FROM threads trunk
        JOIN task_assignment assignment ON assignment.trunk_id = trunk.id
        JOIN trunk_task_creation_authorization authorization
          ON authorization.id = assignment.creation_authorization_id
        WHERE trunk.id = NEW.thread_id
          AND trunk.turn_version = 'V2'
          AND trunk.lifecycle_state IN ('ACTIVE', 'IDLE')
          AND assignment.id = NEW.assignment_id
          AND authorization.assignment_id = assignment.id
          AND authorization.policy_revision_id = NEW.policy_revision_id))
BEGIN SELECT RAISE(ABORT, 'V2 Task creation must consume authorization at next sequence'); END;

CREATE TRIGGER v2_task_creation_route_immutable
BEFORE UPDATE OF creation_receipt_id ON tasks
WHEN OLD.workflow_version = 'V2'
  AND NEW.creation_receipt_id IS NOT OLD.creation_receipt_id
BEGIN SELECT RAISE(ABORT, 'V2 Task creation receipt is immutable'); END;

CREATE TRIGGER task_creation_receipt_insert
BEFORE INSERT ON task_creation_receipt
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM tasks task
            JOIN threads trunk ON trunk.id = task.thread_id
            JOIN trunk_task_creation_authorization authorization
              ON authorization.id = NEW.authorization_id
            JOIN task_assignment assignment ON assignment.id = task.assignment_id
            JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
            JOIN task_creation_context context ON context.task_id = task.id
            JOIN task_brain brain ON brain.task_id = task.id
            JOIN task_provision_target target ON target.task_id = task.id
            WHERE task.id = NEW.task_id
              AND task.thread_id = NEW.trunk_id
              AND trunk.workspace_id = NEW.workspace_id
              AND trunk.turn_version = 'V2'
              AND task.workflow_version = 'V2'
              AND task.creation_receipt_id = NEW.id
              AND task.seq = NEW.task_seq
              AND task.epoch = NEW.task_epoch
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
            SELECT 1
            FROM provision_task_operation operation
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
              AND operation.base_ref = context.base_ref
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
            SELECT 1
            FROM dispatch_ticket ticket
            JOIN task_creation_context context ON context.task_id = ticket.task_id
            WHERE ticket.id = NEW.dispatch_ticket_id
              AND ticket.operation_id = NEW.operation_id
              AND ticket.operation_kind = 'PROVISION_TASK'
              AND ticket.async_family = 'LOCAL_GIT'
              AND ticket.owner_kind = 'TASK'
              AND ticket.owner_id = NEW.task_id
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
              AND ticket.lane_mask = 16
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

CREATE TRIGGER task_creation_receipt_immutable
BEFORE UPDATE ON task_creation_receipt
BEGIN SELECT RAISE(ABORT, 'Task creation receipt is immutable'); END;

-- ── Exact operation lane contracts ──────────────────────────────────────
DROP TRIGGER validation_operation_dispatch_fence;
CREATE TRIGGER validation_operation_dispatch_fence
BEFORE UPDATE OF status ON validation_operation
WHEN NEW.status = 'DISPATCHED'
  AND NOT EXISTS (
      SELECT 1 FROM dispatch_ticket ticket
      WHERE ticket.operation_id = NEW.operation_id
        AND ticket.operation_kind = 'VALIDATE_LOCAL_DEVELOPMENT'
        AND ticket.async_family = 'VALIDATION'
        AND ticket.owner_kind = 'STAGE'
        AND ticket.owner_id = NEW.local_development_stage_id
        AND ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND ticket.stage_id = NEW.local_development_stage_id
        AND ticket.stage_generation = NEW.stage_generation
        AND ticket.attempt = NEW.semantic_attempt
        AND ticket.expected_code_fingerprint = NEW.code_fingerprint
        AND ticket.expected_head_sha = NEW.expected_head_sha
        AND ticket.expected_base_sha = NEW.expected_base_sha
        AND ticket.exclusive_task = 1 AND ticket.writer_required = 0
        AND ticket.lane_mask = 4
        AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'dispatched validation requires exact VALIDATION ticket'); END;

DROP TRIGGER publish_operation_dispatch;
CREATE TRIGGER publish_operation_dispatch
BEFORE UPDATE OF status ON publish_operation
WHEN NEW.status = 'DISPATCHED'
  AND (NOT EXISTS (
      SELECT 1 FROM dispatch_ticket ticket
      WHERE ticket.operation_id = NEW.operation_id
        AND ticket.operation_kind = 'PUBLISH_LOCAL_DEVELOPMENT'
        AND ticket.async_family = 'GITHUB_EFFECT'
        AND ticket.owner_kind = 'STAGE'
        AND ticket.owner_id = NEW.local_development_stage_id
        AND ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND ticket.stage_id = NEW.local_development_stage_id
        AND ticket.stage_generation = NEW.stage_generation
        AND ticket.attempt = NEW.semantic_attempt
        AND ticket.expected_code_fingerprint = NEW.code_fingerprint
        AND ticket.expected_head_sha = NEW.expected_head_sha
        AND ticket.expected_base_sha = NEW.expected_base_sha
        AND ticket.exclusive_task = 1 AND ticket.writer_required = 1
        AND ticket.lane_mask = 48
        AND ticket.status = 'REQUESTED')
    OR (SELECT COUNT(*) FROM publish_effect_step step
        WHERE step.publish_operation_id = NEW.id) <> 6)
BEGIN SELECT RAISE(ABORT, 'dispatched PublishOperation requires exact lanes and six steps'); END;

RELEASE v229_task_creation;
PRAGMA foreign_keys = ON;
