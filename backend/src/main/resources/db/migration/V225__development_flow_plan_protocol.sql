-- Durable V2 Task provisioning and Plan protocol. These records are additive
-- and inert until V2 Task creation is enabled by the routing boundary.

CREATE TABLE task_creation_context (
    task_id             TEXT    NOT NULL PRIMARY KEY
        REFERENCES tasks(id) ON DELETE CASCADE,
    assignment_id       TEXT    NOT NULL REFERENCES task_assignment(id),
    policy_revision_id  TEXT    NOT NULL REFERENCES task_policy_revision(id),
    provenance          TEXT    NOT NULL CHECK (provenance IN (
        'AGENT_HANDOFF', 'DIRECT_USER', 'ISSUE_MONITOR', 'AUTOMATION',
        'QUALITY_SCAN', 'REVIEW_SESSION')),
    repository_id       TEXT    NOT NULL,
    upstream_repository_id TEXT,
    publish_repository_id TEXT NOT NULL,
    planning_base_sha   TEXT,
    assignment_head_sha TEXT,
    engine_snapshot     TEXT    NOT NULL,
    work_model_snapshot TEXT    NOT NULL,
    created_at_ms       INTEGER NOT NULL,
    CHECK ((planning_base_sha IS NOT NULL) <> (assignment_head_sha IS NOT NULL))
);

CREATE TRIGGER task_creation_context_owner_insert
BEFORE INSERT ON task_creation_context
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM tasks t
            JOIN task_assignment a ON a.id = t.assignment_id
            JOIN task_policy_revision p ON p.id = t.policy_revision_id
            WHERE t.id = NEW.task_id
              AND t.workflow_version = 'V2'
              AND a.id = NEW.assignment_id
              AND a.trunk_id = t.thread_id
              AND p.id = NEW.policy_revision_id
              AND p.trunk_id = t.thread_id)
            THEN RAISE(ABORT, 'Task creation context must match its exact V2 creation identity')
        WHEN EXISTS (
            SELECT 1 FROM task_assignment a
            WHERE a.id = NEW.assignment_id
              AND a.kind = 'EXISTING_OWN_PR'
              AND (a.repository_id <> NEW.repository_id
                OR a.repository_id <> NEW.publish_repository_id
                OR a.remote_head_sha IS NOT NEW.assignment_head_sha))
            THEN RAISE(ABORT, 'existing-PR context must freeze its exact repository and head')
        WHEN EXISTS (
            SELECT 1 FROM task_assignment a
            WHERE a.id = NEW.assignment_id
              AND a.kind <> 'EXISTING_OWN_PR'
              AND NEW.assignment_head_sha IS NOT NULL)
            THEN RAISE(ABORT, 'only an existing-PR assignment may freeze an assignment head')
        WHEN EXISTS (
            SELECT 1 FROM task_assignment a
            WHERE a.id = NEW.assignment_id
              AND a.kind = 'NEW_FROM_TRUNK'
              AND a.planning_base_sha IS NOT NEW.planning_base_sha)
            THEN RAISE(ABORT, 'new-from-Trunk context must freeze its planning base')
    END;
END;

CREATE TRIGGER task_creation_context_immutable
BEFORE UPDATE ON task_creation_context
BEGIN SELECT RAISE(ABORT, 'Task creation context is immutable'); END;

CREATE TABLE task_brain (
    id                  TEXT    NOT NULL PRIMARY KEY,
    task_id             TEXT    NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
    provider            TEXT    NOT NULL,
    model               TEXT    NOT NULL,
    role_skill          TEXT,
    engine_snapshot     TEXT    NOT NULL,
    created_at_ms       INTEGER NOT NULL
);

CREATE TRIGGER task_brain_owner_insert
BEFORE INSERT ON task_brain
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks t
    JOIN task_creation_context c ON c.task_id = t.id
    WHERE t.id = NEW.task_id
      AND t.workflow_version = 'V2'
      AND c.engine_snapshot = NEW.engine_snapshot)
BEGIN SELECT RAISE(ABORT, 'Task Brain requires a V2 Task'); END;

CREATE TRIGGER task_brain_immutable
BEFORE UPDATE ON task_brain
BEGIN SELECT RAISE(ABORT, 'Task Brain identity is immutable'); END;

CREATE TABLE provision_task_operation (
    id                       TEXT    NOT NULL PRIMARY KEY,
    task_id                  TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch               INTEGER NOT NULL CHECK (task_epoch > 0),
    assignment_id            TEXT    NOT NULL REFERENCES task_assignment(id),
    operation_id             TEXT    NOT NULL UNIQUE,
    semantic_attempt         INTEGER NOT NULL CHECK (semantic_attempt > 0),
    repository_id            TEXT    NOT NULL,
    expected_base_sha        TEXT,
    expected_remote_head_sha TEXT,
    requested_branch_name    TEXT    NOT NULL,
    requested_worktree_path  TEXT    NOT NULL,
    status                   TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'ACCEPTED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    result_base_sha          TEXT,
    result_head_sha          TEXT,
    result_code_fingerprint  TEXT,
    created_at_ms            INTEGER NOT NULL,
    completed_at_ms          INTEGER,
    error_message            TEXT,
    CHECK ((status = 'ACCEPTED') = (
        result_base_sha IS NOT NULL AND result_head_sha IS NOT NULL
        AND result_code_fingerprint IS NOT NULL)),
    CHECK ((status IN ('ACCEPTED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL))
);
CREATE UNIQUE INDEX idx_provision_task_semantic_attempt
    ON provision_task_operation(task_id, task_epoch, semantic_attempt);

CREATE TRIGGER provision_task_operation_owner_insert
BEFORE INSERT ON provision_task_operation
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'REQUESTED'
            THEN RAISE(ABORT, 'provision operation must start REQUESTED')
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks t
            WHERE t.id = NEW.task_id
              AND t.workflow_version = 'V2'
              AND t.epoch = NEW.task_epoch
              AND t.lifecycle_state = 'PROVISIONING'
              AND t.assignment_id = NEW.assignment_id)
            THEN RAISE(ABORT, 'provision operation does not match its V2 Task fence')
        WHEN NOT EXISTS (
            SELECT 1 FROM task_creation_context c
            WHERE c.task_id = NEW.task_id
              AND c.assignment_id = NEW.assignment_id
              AND c.repository_id = NEW.repository_id
              AND c.planning_base_sha IS NEW.expected_base_sha
              AND c.assignment_head_sha IS NEW.expected_remote_head_sha)
            THEN RAISE(ABORT, 'provision operation does not match frozen creation context')
    END;
END;

CREATE TRIGGER provision_task_operation_dispatch_fence
BEFORE UPDATE OF status ON provision_task_operation
WHEN NEW.status = 'DISPATCHED'
  AND NOT EXISTS (
      SELECT 1
      FROM dispatch_ticket d
      WHERE d.operation_id = NEW.operation_id
        AND d.operation_kind = 'PROVISION_TASK'
        AND d.async_family = 'LOCAL_GIT'
        AND d.owner_kind = 'TASK'
        AND d.owner_id = NEW.task_id
        AND d.task_id = NEW.task_id
        AND d.task_epoch = NEW.task_epoch
        AND d.attempt = NEW.semantic_attempt
        AND d.expected_base_sha IS NEW.expected_base_sha
        AND d.expected_head_sha IS NEW.expected_remote_head_sha
        AND d.exclusive_task = 1
        AND d.writer_required = 1
        AND (d.lane_mask & 16) = 16
        AND d.status = 'REQUESTED'
        AND EXISTS (
            SELECT 1 FROM tasks t
            WHERE t.id = NEW.task_id
              AND t.workflow_version = 'V2'
              AND t.lifecycle_state = 'PROVISIONING'
              AND t.epoch = NEW.task_epoch
              AND t.assignment_id = NEW.assignment_id))
BEGIN SELECT RAISE(ABORT, 'dispatched provisioning requires its exact DispatchTicket'); END;

CREATE TRIGGER provision_task_operation_identity_immutable
BEFORE UPDATE OF task_id, task_epoch, assignment_id, operation_id,
        semantic_attempt, repository_id, expected_base_sha,
        expected_remote_head_sha, requested_branch_name,
        requested_worktree_path, created_at_ms ON provision_task_operation
WHEN NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.assignment_id IS NOT OLD.assignment_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.repository_id IS NOT OLD.repository_id
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.expected_remote_head_sha IS NOT OLD.expected_remote_head_sha
  OR NEW.requested_branch_name IS NOT OLD.requested_branch_name
  OR NEW.requested_worktree_path IS NOT OLD.requested_worktree_path
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'provision operation fence is immutable'); END;

CREATE TRIGGER provision_task_operation_result_update_fence
BEFORE UPDATE ON provision_task_operation
WHEN NEW.status = 'ACCEPTED'
  AND (((NEW.expected_base_sha IS NOT NULL
          AND (NEW.result_base_sha IS NOT NEW.expected_base_sha
            OR NEW.result_head_sha IS NOT NEW.expected_base_sha))
      OR (NEW.expected_remote_head_sha IS NOT NULL
          AND NEW.result_head_sha IS NOT NEW.expected_remote_head_sha))
    OR NOT EXISTS (
        SELECT 1 FROM tasks t
        WHERE t.id = NEW.task_id
          AND t.workflow_version = 'V2'
          AND t.epoch = NEW.task_epoch
          AND t.lifecycle_state = 'PROVISIONING'
          AND t.assignment_id = NEW.assignment_id)
    OR NOT EXISTS (
        SELECT 1
        FROM dispatch_ticket d
        WHERE d.operation_id = NEW.operation_id
          AND d.operation_kind = 'PROVISION_TASK'
          AND d.async_family = 'LOCAL_GIT'
          AND d.owner_kind = 'TASK'
          AND d.owner_id = NEW.task_id
          AND d.task_id = NEW.task_id
          AND d.task_epoch = NEW.task_epoch
          AND d.attempt = NEW.semantic_attempt
          AND d.expected_base_sha IS NEW.expected_base_sha
          AND d.expected_head_sha IS NEW.expected_remote_head_sha
          AND d.status = 'RESULT_PENDING'
          AND d.pending_result_outcome = 'SUCCEEDED'
          AND d.pending_result_evidence IS NOT NULL))
BEGIN SELECT RAISE(ABORT, 'accepted provisioning result does not match its frozen source'); END;

CREATE TRIGGER provision_task_operation_terminal_immutable
BEFORE UPDATE ON provision_task_operation
WHEN OLD.status IN ('ACCEPTED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'terminal provisioning operation is immutable'); END;

CREATE TRIGGER provision_task_operation_transition
BEFORE UPDATE OF status ON provision_task_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'ACCEPTED', 'FAILED', 'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'illegal provisioning operation transition'); END;

CREATE TABLE task_code_identity (
    task_id                  TEXT    NOT NULL PRIMARY KEY
        REFERENCES tasks(id) ON DELETE CASCADE,
    provision_operation_id  TEXT    NOT NULL UNIQUE
        REFERENCES provision_task_operation(id),
    repository_id           TEXT    NOT NULL,
    upstream_repository_id  TEXT,
    publish_repository_id   TEXT    NOT NULL,
    branch_name             TEXT    NOT NULL,
    worktree_path           TEXT    NOT NULL,
    base_sha                TEXT    NOT NULL,
    local_head_sha          TEXT    NOT NULL,
    code_fingerprint        TEXT    NOT NULL,
    version                 INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at_ms           INTEGER NOT NULL,
    updated_at_ms           INTEGER NOT NULL,
    UNIQUE (repository_id, worktree_path),
    UNIQUE (publish_repository_id, branch_name)
);

CREATE TRIGGER task_code_identity_insert_fence
BEFORE INSERT ON task_code_identity
WHEN NOT EXISTS (
    SELECT 1
    FROM provision_task_operation o
    JOIN task_creation_context c ON c.task_id = o.task_id
    WHERE o.id = NEW.provision_operation_id
      AND o.task_id = NEW.task_id
      AND o.status = 'ACCEPTED'
      AND o.repository_id = NEW.repository_id
      AND c.repository_id = NEW.repository_id
      AND c.upstream_repository_id IS NEW.upstream_repository_id
      AND c.publish_repository_id = NEW.publish_repository_id
      AND o.requested_branch_name = NEW.branch_name
      AND o.requested_worktree_path = NEW.worktree_path
      AND o.result_base_sha = NEW.base_sha
      AND o.result_head_sha = NEW.local_head_sha
      AND o.result_code_fingerprint = NEW.code_fingerprint)
BEGIN SELECT RAISE(ABORT, 'Task code identity requires exact accepted provisioning evidence'); END;

CREATE TRIGGER task_code_identity_immutable
BEFORE UPDATE ON task_code_identity
BEGIN SELECT RAISE(ABORT, 'initial Task code identity is immutable'); END;

CREATE TABLE plan_stage (
    stage_id             TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage(id) ON DELETE CASCADE,
    task_id              TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    generation           INTEGER NOT NULL CHECK (generation > 0),
    opened_for_epoch     INTEGER NOT NULL CHECK (opened_for_epoch > 0),
    UNIQUE (stage_id, task_id, generation)
);

CREATE TRIGGER plan_stage_owner_insert
BEFORE INSERT ON plan_stage
WHEN NOT EXISTS (
    SELECT 1
    FROM stage s
    JOIN tasks t ON t.id = s.task_id
    WHERE s.id = NEW.stage_id
      AND s.task_id = NEW.task_id
      AND s.kind = 'PLAN'
      AND s.generation = NEW.generation
      AND t.workflow_version = 'V2'
      AND t.epoch = NEW.opened_for_epoch
      AND t.lifecycle_state = 'ACTIVE'
      AND s.completed_at_ms IS NULL
      AND EXISTS (
          SELECT 1 FROM task_current_stage c
          WHERE c.task_id = t.id
            AND c.stage_id = s.id
            AND c.stage_generation = s.generation))
BEGIN SELECT RAISE(ABORT, 'Plan subtype must match its exact V2 Stage and epoch'); END;

CREATE TRIGGER plan_stage_identity_immutable
BEFORE UPDATE ON plan_stage
BEGIN SELECT RAISE(ABORT, 'Plan Stage identity is immutable'); END;

CREATE TABLE plan_revision (
    id                  TEXT    NOT NULL PRIMARY KEY,
    plan_stage_id       TEXT    NOT NULL REFERENCES plan_stage(stage_id) ON DELETE CASCADE,
    revision            INTEGER NOT NULL CHECK (revision > 0),
    content             TEXT    NOT NULL,
    content_digest      TEXT    NOT NULL,
    source              TEXT    NOT NULL CHECK (source IN (
        'AGENT', 'USER_EDIT', 'BRAIN_REVISION', 'REPLAN_SEED')),
    created_by          TEXT    NOT NULL,
    created_at_ms       INTEGER NOT NULL,
    UNIQUE (plan_stage_id, revision),
    UNIQUE (plan_stage_id, content_digest)
);

CREATE TRIGGER plan_revision_sequence
BEFORE INSERT ON plan_revision
BEGIN
    SELECT CASE
        WHEN NEW.revision <> COALESCE((
            SELECT MAX(r.revision) + 1 FROM plan_revision r
            WHERE r.plan_stage_id = NEW.plan_stage_id), 1)
            THEN RAISE(ABORT, 'Plan revision must be the next exact revision')
        WHEN NOT EXISTS (
            SELECT 1
            FROM plan_stage p
            JOIN stage s ON s.id = p.stage_id
            JOIN task_current_stage c ON c.stage_id = s.id
            WHERE p.stage_id = NEW.plan_stage_id
              AND c.task_id = p.task_id
              AND c.stage_generation = p.generation
              AND s.completed_at_ms IS NULL)
            THEN RAISE(ABORT, 'Plan revision requires the current open Plan Stage')
    END;
END;

CREATE TRIGGER plan_revision_immutable
BEFORE UPDATE ON plan_revision
BEGIN SELECT RAISE(ABORT, 'Plan revision is immutable'); END;

CREATE TABLE plan_self_review (
    id                    TEXT    NOT NULL PRIMARY KEY,
    plan_revision_id      TEXT    NOT NULL UNIQUE
        REFERENCES plan_revision(id) ON DELETE CASCADE,
    task_turn_id          TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    task_epoch            INTEGER NOT NULL CHECK (task_epoch > 0),
    reviewed_digest       TEXT    NOT NULL,
    status                TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    verdict               TEXT CHECK (verdict IN (
        'APPROVED', 'CHANGES_REQUESTED', 'BLOCKED')),
    concern_summary       TEXT,
    requested_at_ms       INTEGER NOT NULL,
    completed_at_ms       INTEGER,
    error_message         TEXT,
    CHECK ((status = 'SUCCEEDED') = (verdict IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL))
);

CREATE TRIGGER plan_self_review_owner_insert
BEFORE INSERT ON plan_self_review
WHEN NEW.status <> 'REQUESTED'
  OR NOT EXISTS (
    SELECT 1
    FROM plan_revision r
    JOIN plan_stage p ON p.stage_id = r.plan_stage_id
    JOIN stage s ON s.id = p.stage_id
    JOIN tasks t ON t.id = p.task_id
    JOIN task_current_stage c ON c.stage_id = p.stage_id
    JOIN task_turn tt ON tt.id = NEW.task_turn_id
    WHERE r.id = NEW.plan_revision_id
      AND r.content_digest = NEW.reviewed_digest
      AND t.workflow_version = 'V2'
      AND t.lifecycle_state = 'ACTIVE'
      AND t.epoch = NEW.task_epoch
      AND c.task_id = p.task_id
      AND c.stage_generation = p.generation
      AND s.completed_at_ms IS NULL
      AND p.task_id = tt.task_id
      AND p.opened_for_epoch = NEW.task_epoch
      AND tt.task_epoch = NEW.task_epoch
      AND tt.purpose = 'PLAN_SELF_REVIEW'
      AND tt.status IN ('REQUESTED', 'QUEUED')
      AND tt.trigger_stage_id = p.stage_id
      AND tt.trigger_stage_generation = p.generation
      AND NOT EXISTS (
          SELECT 1 FROM plan_revision newer
          WHERE newer.plan_stage_id = r.plan_stage_id
            AND newer.revision > r.revision))
BEGIN SELECT RAISE(ABORT, 'Plan self-review must use its exact TaskTurn and revision fence'); END;

CREATE TRIGGER plan_self_review_identity_immutable
BEFORE UPDATE OF plan_revision_id, task_turn_id, task_epoch,
        reviewed_digest, requested_at_ms ON plan_self_review
WHEN NEW.plan_revision_id IS NOT OLD.plan_revision_id
  OR NEW.task_turn_id IS NOT OLD.task_turn_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.reviewed_digest IS NOT OLD.reviewed_digest
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'Plan self-review fence is immutable'); END;

CREATE TRIGGER plan_self_review_terminal_immutable
BEFORE UPDATE ON plan_self_review
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'terminal Plan self-review is immutable'); END;

CREATE TRIGGER plan_self_review_transition
BEFORE UPDATE OF status ON plan_self_review
WHEN OLD.status <> 'REQUESTED'
  OR NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'illegal Plan self-review transition'); END;

CREATE TRIGGER plan_self_review_result_fence
BEFORE UPDATE ON plan_self_review
WHEN NEW.status = 'SUCCEEDED'
  AND NOT EXISTS (
      SELECT 1
      FROM plan_revision r
      JOIN plan_stage p ON p.stage_id = r.plan_stage_id
      JOIN stage s ON s.id = p.stage_id
      JOIN task_current_stage c ON c.stage_id = s.id
      JOIN tasks t ON t.id = p.task_id
      JOIN task_turn tt ON tt.id = NEW.task_turn_id
      WHERE r.id = NEW.plan_revision_id
        AND r.content_digest = NEW.reviewed_digest
        AND c.task_id = p.task_id
        AND c.stage_generation = p.generation
        AND t.workflow_version = 'V2'
        AND t.lifecycle_state = 'ACTIVE'
        AND t.epoch = NEW.task_epoch
        AND p.opened_for_epoch = NEW.task_epoch
        AND tt.status = 'SUCCEEDED'
        AND tt.purpose = 'PLAN_SELF_REVIEW'
        AND tt.task_epoch = NEW.task_epoch
        AND tt.trigger_stage_id = p.stage_id
        AND tt.trigger_stage_generation = p.generation
        AND s.completed_at_ms IS NULL
        AND NOT EXISTS (
            SELECT 1 FROM plan_revision newer
            WHERE newer.plan_stage_id = r.plan_stage_id
              AND newer.revision > r.revision))
BEGIN SELECT RAISE(ABORT, 'Plan self-review result is stale'); END;

CREATE TABLE plan_approval (
    id                  TEXT    NOT NULL PRIMARY KEY,
    plan_revision_id    TEXT    NOT NULL UNIQUE
        REFERENCES plan_revision(id) ON DELETE CASCADE,
    self_review_id      TEXT    NOT NULL UNIQUE REFERENCES plan_self_review(id),
    approval_kind       TEXT    NOT NULL CHECK (approval_kind IN ('HUMAN', 'POLICY')),
    policy_revision_id  TEXT    NOT NULL REFERENCES task_policy_revision(id),
    actor               TEXT    NOT NULL,
    approved_at_ms      INTEGER NOT NULL
);

CREATE TRIGGER plan_approval_fence_insert
BEFORE INSERT ON plan_approval
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review sr
    JOIN plan_revision r ON r.id = sr.plan_revision_id
    JOIN plan_stage p ON p.stage_id = r.plan_stage_id
    JOIN stage s ON s.id = p.stage_id
    JOIN tasks t ON t.id = p.task_id
    JOIN task_current_stage c ON c.stage_id = s.id
    JOIN task_policy_revision policy ON policy.id = NEW.policy_revision_id
    WHERE sr.id = NEW.self_review_id
      AND sr.plan_revision_id = NEW.plan_revision_id
      AND sr.status = 'SUCCEEDED'
      AND sr.verdict = 'APPROVED'
      AND t.policy_revision_id = NEW.policy_revision_id
      AND (NEW.approval_kind <> 'POLICY' OR (
          policy.auto_approve = 1
          AND NOT EXISTS (
              SELECT 1
              FROM plan_followup f
              JOIN plan_revision fr ON fr.id = f.plan_revision_id
              JOIN plan_stage fp ON fp.stage_id = fr.plan_stage_id
              WHERE fp.task_id = t.id
                AND f.kind = 'STEWARDSHIP'
                AND f.status <> 'RESOLVED')))
      AND t.epoch = p.opened_for_epoch
      AND c.task_id = t.id
      AND c.stage_generation = p.generation
      AND s.completed_at_ms IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM plan_revision newer
          WHERE newer.plan_stage_id = r.plan_stage_id
            AND newer.revision > r.revision))
BEGIN SELECT RAISE(ABORT, 'Plan approval requires exact approved self-review evidence'); END;

CREATE TRIGGER plan_approval_immutable
BEFORE UPDATE ON plan_approval
BEGIN SELECT RAISE(ABORT, 'Plan approval is immutable'); END;

CREATE TABLE plan_followup (
    id                  TEXT    NOT NULL PRIMARY KEY,
    plan_revision_id    TEXT    NOT NULL REFERENCES plan_revision(id) ON DELETE CASCADE,
    kind                TEXT    NOT NULL CHECK (kind IN (
        'CONCERN', 'FOLLOW_UP', 'STEWARDSHIP', 'FAILURE_BLOCKER')),
    description         TEXT    NOT NULL,
    status              TEXT    NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'DEFERRED')),
    self_review_id      TEXT    REFERENCES plan_self_review(id),
    task_blocker_id     TEXT    UNIQUE REFERENCES task_blocker(id),
    created_by          TEXT    NOT NULL,
    created_at_ms       INTEGER NOT NULL,
    resolved_at_ms      INTEGER,
    resolution          TEXT,
    UNIQUE (self_review_id),
    CHECK ((kind = 'FAILURE_BLOCKER'
            AND self_review_id IS NOT NULL AND task_blocker_id IS NOT NULL)
        OR (kind <> 'FAILURE_BLOCKER'
            AND self_review_id IS NULL AND task_blocker_id IS NULL)),
    CHECK ((status = 'OPEN' AND resolved_at_ms IS NULL AND resolution IS NULL)
        OR (status <> 'OPEN' AND resolved_at_ms IS NOT NULL AND resolution IS NOT NULL))
);

CREATE TRIGGER plan_followup_insert_fence
BEFORE INSERT ON plan_followup
WHEN NEW.status <> 'OPEN'
  OR NOT EXISTS (
      SELECT 1
      FROM plan_revision r
      JOIN plan_stage p ON p.stage_id = r.plan_stage_id
      JOIN stage s ON s.id = p.stage_id
      JOIN task_current_stage c ON c.stage_id = p.stage_id
      WHERE r.id = NEW.plan_revision_id
        AND c.task_id = p.task_id
        AND c.stage_generation = p.generation
        AND s.completed_at_ms IS NULL
        AND NOT EXISTS (
            SELECT 1 FROM plan_revision newer
            WHERE newer.plan_stage_id = r.plan_stage_id
              AND newer.revision > r.revision)
        AND (NEW.kind IN ('FOLLOW_UP', 'FAILURE_BLOCKER') OR NOT EXISTS (
            SELECT 1 FROM plan_self_review frozen
            WHERE frozen.plan_revision_id = r.id
              AND frozen.status = 'SUCCEEDED'))
        AND (NEW.kind <> 'FAILURE_BLOCKER' OR EXISTS (
            SELECT 1
            FROM plan_self_review sr
            JOIN task_blocker b ON b.id = NEW.task_blocker_id
            WHERE sr.id = NEW.self_review_id
              AND sr.plan_revision_id = r.id
              AND (sr.status = 'FAILED'
                OR (sr.status = 'SUCCEEDED' AND sr.verdict = 'BLOCKED'))
              AND b.task_id = p.task_id
              AND b.stage_id = p.stage_id
              AND b.owner_kind = 'STAGE'
              AND b.owner_id = p.stage_id
              AND b.subject_revision = r.content_digest
              AND b.blocker_type = 'PLAN_REVIEW_FAILURE'
              AND b.status = 'OPEN')))
BEGIN SELECT RAISE(ABORT, 'Plan follow-up must match current reviewed evidence'); END;

CREATE TRIGGER plan_followup_identity_immutable
BEFORE UPDATE OF id, plan_revision_id, kind, description, self_review_id,
        task_blocker_id, created_by, created_at_ms
ON plan_followup
WHEN NEW.id IS NOT OLD.id
  OR NEW.plan_revision_id IS NOT OLD.plan_revision_id
  OR NEW.kind IS NOT OLD.kind
  OR NEW.description IS NOT OLD.description
  OR NEW.self_review_id IS NOT OLD.self_review_id
  OR NEW.task_blocker_id IS NOT OLD.task_blocker_id
  OR NEW.created_by IS NOT OLD.created_by
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'Plan follow-up identity is immutable'); END;

CREATE TRIGGER plan_followup_transition
BEFORE UPDATE OF status ON plan_followup
WHEN NOT (
    (OLD.status = 'OPEN' AND NEW.status IN ('RESOLVED', 'DEFERRED'))
    OR (OLD.status = 'DEFERRED' AND NEW.status = 'RESOLVED'))
BEGIN SELECT RAISE(ABORT, 'illegal Plan follow-up transition'); END;

CREATE TABLE task_quiescence_barrier (
    id                  TEXT    NOT NULL PRIMARY KEY,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch          INTEGER NOT NULL CHECK (task_epoch > 0),
    reason              TEXT    NOT NULL CHECK (reason IN (
        'REPLAN', 'PAUSE', 'CANCEL', 'CLEANUP')),
    status              TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'SATISFIED', 'FAILED', 'CANCELED')),
    requested_at_ms     INTEGER NOT NULL,
    completed_at_ms     INTEGER,
    evidence            TEXT,
    error_message       TEXT,
    CHECK ((status IN ('SATISFIED', 'FAILED', 'CANCELED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK ((status = 'SATISFIED') = (evidence IS NOT NULL))
);
CREATE UNIQUE INDEX idx_task_quiescence_one_active
    ON task_quiescence_barrier(task_id) WHERE status = 'REQUESTED';

CREATE TRIGGER task_quiescence_owner_insert
BEFORE INSERT ON task_quiescence_barrier
WHEN NEW.status <> 'REQUESTED'
  OR NOT EXISTS (
      SELECT 1 FROM tasks t
      WHERE t.id = NEW.task_id
        AND t.workflow_version = 'V2'
        AND t.epoch = NEW.task_epoch
        AND t.lifecycle_state IN ('ACTIVE', 'PAUSING', 'CANCELING', 'CLEANING'))
BEGIN SELECT RAISE(ABORT, 'quiescence barrier must start on its exact current V2 Task'); END;

CREATE TRIGGER task_quiescence_identity_immutable
BEFORE UPDATE OF task_id, task_epoch, reason, requested_at_ms
ON task_quiescence_barrier
WHEN NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.reason IS NOT OLD.reason
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'quiescence barrier identity is immutable'); END;

CREATE TRIGGER task_quiescence_terminal_immutable
BEFORE UPDATE ON task_quiescence_barrier
WHEN OLD.status IN ('SATISFIED', 'FAILED', 'CANCELED')
BEGIN SELECT RAISE(ABORT, 'terminal quiescence barrier is immutable'); END;

CREATE TRIGGER task_quiescence_transition
BEFORE UPDATE OF status ON task_quiescence_barrier
WHEN OLD.status <> 'REQUESTED'
  OR NEW.status NOT IN ('SATISFIED', 'FAILED', 'CANCELED')
BEGIN SELECT RAISE(ABORT, 'illegal quiescence barrier transition'); END;

CREATE TRIGGER task_quiescence_satisfied_fence
BEFORE UPDATE OF status ON task_quiescence_barrier
WHEN NEW.status = 'SATISFIED'
  AND (EXISTS (
      SELECT 1 FROM capacity_lease c
      WHERE c.workflow_source = 'V2'
        AND c.task_id = NEW.task_id
        AND c.task_epoch = NEW.task_epoch
        AND c.released_at_ms IS NULL)
    OR EXISTS (
      SELECT 1 FROM worktree_leases w
      WHERE w.workflow_version = 'V2'
        AND w.task_id = NEW.task_id
        AND w.task_epoch = NEW.task_epoch)
    OR EXISTS (
      SELECT 1 FROM dispatch_ticket d
      WHERE d.task_id = NEW.task_id
        AND d.task_epoch = NEW.task_epoch
        AND d.status IN ('CLAIMED', 'RUNNING', 'RECONCILE_WAIT'))
    OR EXISTS (
      SELECT 1
      FROM agent_execution e
      JOIN dispatch_ticket d ON d.id = e.ticket_id
      WHERE d.task_id = NEW.task_id
        AND d.task_epoch = NEW.task_epoch
        AND e.status = 'UNKNOWN'))
BEGIN SELECT RAISE(ABORT, 'quiescence requires all exact-epoch work to stop or reconcile'); END;

CREATE TABLE task_replan_request (
    id                    TEXT    NOT NULL PRIMARY KEY,
    task_id               TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    source_stage_id       TEXT    NOT NULL REFERENCES stage(id),
    source_generation     INTEGER NOT NULL CHECK (source_generation > 0),
    source_task_epoch     INTEGER NOT NULL CHECK (source_task_epoch > 0),
    target_task_epoch     INTEGER NOT NULL CHECK (target_task_epoch = source_task_epoch + 1),
    quiescence_barrier_id TEXT    NOT NULL UNIQUE
        REFERENCES task_quiescence_barrier(id),
    command_id            TEXT    NOT NULL,
    reason                TEXT    NOT NULL,
    requested_by          TEXT    NOT NULL,
    status                TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'QUIESCING', 'APPLIED', 'FAILED', 'CANCELED')),
    new_plan_stage_id     TEXT    REFERENCES stage(id),
    new_plan_generation   INTEGER,
    requested_at_ms       INTEGER NOT NULL,
    completed_at_ms       INTEGER,
    error_message         TEXT,
    UNIQUE (task_id, command_id),
    CHECK ((new_plan_stage_id IS NULL AND new_plan_generation IS NULL)
        OR (new_plan_stage_id IS NOT NULL AND new_plan_generation IS NOT NULL)),
    CHECK ((status = 'APPLIED') = (new_plan_stage_id IS NOT NULL)),
    CHECK ((status IN ('APPLIED', 'FAILED', 'CANCELED'))
        = (completed_at_ms IS NOT NULL))
);
CREATE UNIQUE INDEX idx_task_replan_one_active
    ON task_replan_request(task_id)
    WHERE status IN ('REQUESTED', 'QUIESCING');

CREATE TRIGGER task_replan_request_owner_insert
BEFORE INSERT ON task_replan_request
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'REQUESTED'
            THEN RAISE(ABORT, 'replan request must start REQUESTED')
        WHEN NOT EXISTS (
            SELECT 1
            FROM tasks t
            JOIN stage s ON s.task_id = t.id
            JOIN task_quiescence_barrier q ON q.task_id = t.id
            WHERE t.id = NEW.task_id
              AND t.workflow_version = 'V2'
              AND t.epoch = NEW.source_task_epoch
              AND s.id = NEW.source_stage_id
              AND s.generation = NEW.source_generation
              AND s.completed_at_ms IS NULL
              AND q.id = NEW.quiescence_barrier_id
              AND q.task_epoch = NEW.source_task_epoch
              AND q.reason = 'REPLAN'
              AND q.status = 'REQUESTED'
              AND EXISTS (
                  SELECT 1 FROM task_current_stage c
                  WHERE c.task_id = t.id
                    AND c.stage_id = s.id
                    AND c.stage_generation = s.generation))
            THEN RAISE(ABORT, 'replan request must fence the current V2 Stage, epoch and barrier')
    END;
END;

CREATE TRIGGER task_replan_request_identity_immutable
BEFORE UPDATE OF task_id, source_stage_id, source_generation,
        source_task_epoch, target_task_epoch, quiescence_barrier_id, command_id, reason,
        requested_by, requested_at_ms ON task_replan_request
WHEN NEW.task_id IS NOT OLD.task_id
  OR NEW.source_stage_id IS NOT OLD.source_stage_id
  OR NEW.source_generation IS NOT OLD.source_generation
  OR NEW.source_task_epoch IS NOT OLD.source_task_epoch
  OR NEW.target_task_epoch IS NOT OLD.target_task_epoch
  OR NEW.quiescence_barrier_id IS NOT OLD.quiescence_barrier_id
  OR NEW.command_id IS NOT OLD.command_id
  OR NEW.reason IS NOT OLD.reason
  OR NEW.requested_by IS NOT OLD.requested_by
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'replan request fence is immutable'); END;

CREATE TRIGGER task_replan_terminal_immutable
BEFORE UPDATE ON task_replan_request
WHEN OLD.status IN ('APPLIED', 'FAILED', 'CANCELED')
BEGIN SELECT RAISE(ABORT, 'terminal replan request is immutable'); END;

CREATE TRIGGER task_replan_transition
BEFORE UPDATE OF status ON task_replan_request
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN ('QUIESCING', 'CANCELED'))
    OR (OLD.status = 'QUIESCING' AND NEW.status IN ('APPLIED', 'FAILED', 'CANCELED')))
BEGIN SELECT RAISE(ABORT, 'illegal replan request transition'); END;

CREATE TRIGGER task_replan_applied_fence
BEFORE UPDATE ON task_replan_request
WHEN NEW.status = 'APPLIED'
  AND (NOT EXISTS (
      SELECT 1
      FROM tasks t
      JOIN plan_stage p ON p.task_id = t.id
      JOIN stage target ON target.id = p.stage_id
      JOIN stage source ON source.id = NEW.source_stage_id
      WHERE t.id = NEW.task_id
        AND t.workflow_version = 'V2'
        AND t.lifecycle_state = 'ACTIVE'
        AND t.epoch = NEW.target_task_epoch
        AND p.stage_id = NEW.new_plan_stage_id
        AND p.generation = NEW.new_plan_generation
        AND p.opened_for_epoch = NEW.target_task_epoch
        AND target.task_id = t.id
        AND target.kind = 'PLAN'
        AND target.generation = NEW.new_plan_generation
        AND target.checkpoint = 'DRAFTING'
        AND target.completed_at_ms IS NULL
        AND source.task_id = t.id
        AND source.id = NEW.source_stage_id
        AND source.generation = NEW.source_generation
        AND source.completed_at_ms IS NOT NULL
        AND source.end_reason = 'SUPERSEDED_BY_REPLAN'
        AND EXISTS (
            SELECT 1 FROM task_current_stage c
            WHERE c.task_id = t.id
              AND c.stage_id = p.stage_id
              AND c.stage_generation = p.generation))
    OR NOT EXISTS (
        SELECT 1 FROM task_quiescence_barrier q
        WHERE q.id = NEW.quiescence_barrier_id
          AND q.task_id = NEW.task_id
          AND q.task_epoch = NEW.source_task_epoch
          AND q.reason = 'REPLAN'
          AND q.status = 'SATISFIED'))
BEGIN SELECT RAISE(ABORT, 'applied replan requires its exact new Plan Stage and epoch'); END;

CREATE TRIGGER capacity_lease_replan_admission_fence
BEFORE INSERT ON capacity_lease
WHEN NEW.workflow_source = 'V2'
  AND EXISTS (
      SELECT 1 FROM task_replan_request r
      WHERE r.task_id = NEW.task_id
        AND r.source_task_epoch = NEW.task_epoch
        AND r.status IN ('REQUESTED', 'QUIESCING'))
BEGIN SELECT RAISE(ABORT, 'replan quiescence blocks new exact-epoch admission'); END;
