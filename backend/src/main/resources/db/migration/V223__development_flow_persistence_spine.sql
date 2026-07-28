-- Add the inert persistence spine for the V2 development workflow. Nothing in
-- this migration routes production work or writes a second lifecycle for a
-- LEGACY Task. The new columns therefore remain nullable on historical rows,
-- while triggers fail closed for newly-created V2 owners and protocol records.

-- ── Trunk and Task aggregate identity ──────────────────────────────────────
ALTER TABLE threads ADD COLUMN lifecycle_state TEXT;
ALTER TABLE threads ADD COLUMN aggregate_version INTEGER NOT NULL DEFAULT 0
    CHECK (aggregate_version >= 0);

CREATE TABLE task_assignment (
    id                    TEXT    NOT NULL PRIMARY KEY,
    trunk_id              TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    kind                  TEXT    NOT NULL CHECK (kind IN (
        'NEW_FROM_TRUNK', 'EXISTING_OWN_PR', 'REVIEW_FINDINGS',
        'ISSUE', 'AUTOMATION', 'QUALITY_SCAN')),
    source_id             TEXT,
    repository_id         TEXT,
    pr_number             INTEGER,
    remote_head_sha       TEXT,
    planning_base_sha     TEXT,
    plan_seed             TEXT,
    prompt                TEXT,
    producer              TEXT,
    reason                TEXT,
    selected_findings_json TEXT,
    created_by            TEXT    NOT NULL,
    created_at_ms         INTEGER NOT NULL,
    CONSTRAINT task_assignment_existing_pr_shape CHECK (
        kind <> 'EXISTING_OWN_PR'
        OR (repository_id IS NOT NULL AND pr_number IS NOT NULL AND remote_head_sha IS NOT NULL)),
    CONSTRAINT task_assignment_new_shape CHECK (
        kind <> 'NEW_FROM_TRUNK'
        OR (planning_base_sha IS NOT NULL AND plan_seed IS NOT NULL AND prompt IS NOT NULL)),
    CONSTRAINT task_assignment_review_shape CHECK (
        kind <> 'REVIEW_FINDINGS'
        OR (source_id IS NOT NULL AND selected_findings_json IS NOT NULL)),
    CONSTRAINT task_assignment_issue_shape CHECK (
        kind <> 'ISSUE' OR source_id IS NOT NULL),
    CONSTRAINT task_assignment_automation_shape CHECK (
        kind <> 'AUTOMATION' OR (producer IS NOT NULL AND reason IS NOT NULL)),
    CONSTRAINT task_assignment_quality_shape CHECK (
        kind <> 'QUALITY_SCAN' OR source_id IS NOT NULL)
);
CREATE INDEX idx_task_assignment_trunk_created
    ON task_assignment(trunk_id, created_at_ms);

CREATE TABLE task_policy_revision (
    id                             TEXT    NOT NULL PRIMARY KEY,
    trunk_id                       TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    revision                       INTEGER NOT NULL CHECK (revision > 0),
    source                         TEXT    NOT NULL,
    auto_approve                   INTEGER NOT NULL DEFAULT 0 CHECK (auto_approve IN (0, 1)),
    auto_merge                     INTEGER NOT NULL DEFAULT 0 CHECK (auto_merge IN (0, 1)),
    min_approvals                  INTEGER NOT NULL DEFAULT 0 CHECK (min_approvals >= 0),
    max_brain_rounds               INTEGER NOT NULL DEFAULT 3 CHECK (max_brain_rounds >= 0),
    max_ci_fix_pushes              INTEGER NOT NULL DEFAULT 3 CHECK (max_ci_fix_pushes >= 0),
    require_remote_branch_cleanup  INTEGER NOT NULL DEFAULT 0
        CHECK (require_remote_branch_cleanup IN (0, 1)),
    permission_policy_ref          TEXT,
    created_by                     TEXT    NOT NULL,
    created_at_ms                  INTEGER NOT NULL,
    UNIQUE (trunk_id, revision),
    CHECK (auto_merge = 0 OR auto_approve = 1)
);

ALTER TABLE tasks ADD COLUMN epoch INTEGER NOT NULL DEFAULT 1 CHECK (epoch >= 1);
ALTER TABLE tasks ADD COLUMN aggregate_version INTEGER NOT NULL DEFAULT 0
    CHECK (aggregate_version >= 0);
ALTER TABLE tasks ADD COLUMN lifecycle_state TEXT;
ALTER TABLE tasks ADD COLUMN assignment_id TEXT REFERENCES task_assignment(id);
ALTER TABLE tasks ADD COLUMN policy_revision_id TEXT REFERENCES task_policy_revision(id);

CREATE UNIQUE INDEX idx_tasks_assignment_v2
    ON tasks(assignment_id) WHERE assignment_id IS NOT NULL;

-- The V2 Stage aggregate deliberately uses a singular table name so it cannot
-- be confused with historical task_stage pseudo-Stages.
CREATE TABLE stage (
    id               TEXT    NOT NULL PRIMARY KEY,
    task_id          TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind             TEXT    NOT NULL CHECK (kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT', 'CLEANUP')),
    generation       INTEGER NOT NULL CHECK (generation > 0),
    version          INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    checkpoint       TEXT    NOT NULL,
    opened_at_ms     INTEGER NOT NULL,
    completed_at_ms  INTEGER,
    end_reason       TEXT CHECK (end_reason IN (
        'NORMAL', 'SUPERSEDED_BY_REPLAN', 'TASK_CANCELED',
        'REMOTE_MERGED', 'REMOTE_CLOSED')),
    open_marker      INTEGER GENERATED ALWAYS AS (
        CASE WHEN completed_at_ms IS NULL THEN 1 ELSE 0 END) STORED,
    CHECK ((completed_at_ms IS NULL AND end_reason IS NULL)
        OR (completed_at_ms IS NOT NULL AND end_reason IS NOT NULL)),
    CHECK (kind <> 'PLAN' OR checkpoint IN (
        'DRAFTING', 'SELF_REVIEW', 'AWAITING_APPROVAL', 'COMPLETED')),
    CHECK (kind <> 'LOCAL_DEVELOPMENT' OR checkpoint IN (
        'IMPLEMENTING', 'VALIDATING', 'BRAIN_REVIEW', 'LOCAL_REVIEW',
        'PUBLISHING', 'ADDRESSING_BRAIN_FINDINGS',
        'ADDRESSING_LOCAL_FEEDBACK', 'COMPLETED')),
    CHECK (kind <> 'REMOTE_DEVELOPMENT' OR checkpoint IN (
        'WAITING_CI', 'AWAITING_READY', 'WAITING_REMOTE_REVIEW',
        'ADDRESSING_REMOTE_FEEDBACK', 'READY_TO_MERGE', 'MERGING', 'COMPLETED')),
    CHECK (kind <> 'CLEANUP' OR checkpoint IN (
        'WAITING_QUIESCENCE', 'CLEANING', 'COMPLETED')),
    CHECK (end_reason NOT IN ('NORMAL', 'REMOTE_MERGED', 'REMOTE_CLOSED')
        OR checkpoint = 'COMPLETED'),
    UNIQUE (task_id, kind, generation),
    UNIQUE (id, task_id, generation, open_marker)
);
CREATE UNIQUE INDEX idx_stage_one_unsealed_per_task
    ON stage(task_id) WHERE completed_at_ms IS NULL;
CREATE INDEX idx_stage_task_kind_generation
    ON stage(task_id, kind, generation DESC);

-- The deferred exact-open FK lets a manager seal the old Stage, insert the
-- next one, and repoint the Task in one transaction. Committing between those
-- steps fails instead of leaving an ACTIVE Task aimed at a sealed Stage.
CREATE TABLE task_current_stage (
    task_id          TEXT    NOT NULL PRIMARY KEY
        REFERENCES tasks(id) ON DELETE CASCADE,
    stage_id         TEXT    NOT NULL,
    stage_generation INTEGER NOT NULL CHECK (stage_generation > 0),
    open_marker      INTEGER NOT NULL DEFAULT 1 CHECK (open_marker = 1),
    FOREIGN KEY (stage_id, task_id, stage_generation, open_marker)
        REFERENCES stage(id, task_id, generation, open_marker)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER v2_task_insert_shape
BEFORE INSERT ON tasks
WHEN NEW.workflow_version = 'V2'
BEGIN
    SELECT CASE
        WHEN NEW.lifecycle_state IS NULL
            THEN RAISE(ABORT, 'V2 task lifecycle state is required')
        WHEN NEW.lifecycle_state NOT IN (
            'PROVISIONING', 'ACTIVE', 'PAUSING', 'PAUSED', 'RESUMING',
            'ARCHIVING', 'ARCHIVED', 'CANCELING', 'CLEANING',
            'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            THEN RAISE(ABORT, 'invalid V2 task lifecycle state')
        WHEN NEW.assignment_id IS NULL OR NEW.policy_revision_id IS NULL
            THEN RAISE(ABORT, 'V2 task assignment and policy are required')
        WHEN NOT EXISTS (
            SELECT 1 FROM task_assignment a
            WHERE a.id = NEW.assignment_id AND a.trunk_id = NEW.thread_id)
            THEN RAISE(ABORT, 'V2 task assignment must belong to its Trunk')
        WHEN NOT EXISTS (
            SELECT 1 FROM task_policy_revision p
            WHERE p.id = NEW.policy_revision_id AND p.trunk_id = NEW.thread_id)
            THEN RAISE(ABORT, 'V2 task policy must belong to its Trunk')
        WHEN NEW.lifecycle_state = 'ACTIVE' AND NOT EXISTS (
            SELECT 1 FROM task_current_stage c WHERE c.task_id = NEW.id)
            THEN RAISE(ABORT, 'active V2 task requires an exact current Stage')
    END;
END;

CREATE TRIGGER v2_task_update_shape
BEFORE UPDATE OF lifecycle_state, assignment_id, policy_revision_id ON tasks
WHEN NEW.workflow_version = 'V2'
BEGIN
    SELECT CASE
        WHEN NEW.lifecycle_state IS NULL
            THEN RAISE(ABORT, 'V2 task lifecycle state is required')
        WHEN NEW.lifecycle_state NOT IN (
            'PROVISIONING', 'ACTIVE', 'PAUSING', 'PAUSED', 'RESUMING',
            'ARCHIVING', 'ARCHIVED', 'CANCELING', 'CLEANING',
            'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            THEN RAISE(ABORT, 'invalid V2 task lifecycle state')
        WHEN NEW.assignment_id IS NULL OR NEW.policy_revision_id IS NULL
            THEN RAISE(ABORT, 'V2 task assignment and policy are required')
        WHEN NOT EXISTS (
            SELECT 1 FROM task_assignment a
            WHERE a.id = NEW.assignment_id AND a.trunk_id = NEW.thread_id)
            THEN RAISE(ABORT, 'V2 task assignment must belong to its Trunk')
        WHEN NOT EXISTS (
            SELECT 1 FROM task_policy_revision p
            WHERE p.id = NEW.policy_revision_id AND p.trunk_id = NEW.thread_id)
            THEN RAISE(ABORT, 'V2 task policy must belong to its Trunk')
        WHEN NEW.lifecycle_state = 'ACTIVE' AND NOT EXISTS (
            SELECT 1 FROM task_current_stage c WHERE c.task_id = NEW.id)
            THEN RAISE(ABORT, 'active V2 task requires an exact current Stage')
    END;
END;

CREATE TRIGGER v2_task_current_stage_delete
BEFORE DELETE ON task_current_stage
WHEN EXISTS (
    SELECT 1 FROM tasks t
    WHERE t.id = OLD.task_id AND t.lifecycle_state = 'ACTIVE')
BEGIN SELECT RAISE(ABORT, 'active V2 task requires a current Stage'); END;

CREATE TRIGGER v2_task_current_stage_owner_immutable
BEFORE UPDATE OF task_id, open_marker ON task_current_stage
WHEN NEW.task_id IS NOT OLD.task_id OR NEW.open_marker IS NOT OLD.open_marker
BEGIN SELECT RAISE(ABORT, 'current Stage link owner is immutable'); END;

CREATE TRIGGER v2_task_epoch_monotonic
BEFORE UPDATE OF epoch ON tasks
WHEN OLD.workflow_version = 'V2'
  AND (NEW.epoch < OLD.epoch OR NEW.epoch > OLD.epoch + 1)
BEGIN
    SELECT RAISE(ABORT, 'V2 task epoch must stay or advance once');
END;

CREATE TRIGGER v2_task_version_monotonic
BEFORE UPDATE ON tasks
WHEN OLD.workflow_version = 'V2'
  AND NEW.aggregate_version <> OLD.aggregate_version + 1
BEGIN
    SELECT RAISE(ABORT, 'V2 task aggregate version must advance once');
END;

CREATE TRIGGER v2_task_identity_immutable
BEFORE UPDATE OF thread_id, seq, assignment_id, policy_revision_id ON tasks
WHEN OLD.workflow_version = 'V2'
  AND (NEW.thread_id IS NOT OLD.thread_id
    OR NEW.seq IS NOT OLD.seq
    OR NEW.assignment_id IS NOT OLD.assignment_id
    OR NEW.policy_revision_id IS NOT OLD.policy_revision_id)
BEGIN
    SELECT RAISE(ABORT, 'V2 task creation identity is immutable');
END;

CREATE TRIGGER v2_trunk_insert_shape
BEFORE INSERT ON threads
WHEN NEW.turn_version = 'V2'
BEGIN
    SELECT CASE WHEN NEW.lifecycle_state IS NULL
            OR NEW.lifecycle_state NOT IN ('ACTIVE', 'IDLE', 'ARCHIVED')
        THEN RAISE(ABORT, 'invalid V2 trunk lifecycle state') END;
END;

CREATE TRIGGER v2_trunk_update_shape
BEFORE UPDATE OF turn_version, lifecycle_state ON threads
WHEN NEW.turn_version = 'V2'
BEGIN
    SELECT CASE WHEN NEW.lifecycle_state IS NULL
            OR NEW.lifecycle_state NOT IN ('ACTIVE', 'IDLE', 'ARCHIVED')
        THEN RAISE(ABORT, 'invalid V2 trunk lifecycle state') END;
END;

CREATE TRIGGER v2_trunk_version_monotonic
BEFORE UPDATE ON threads
WHEN OLD.turn_version = 'V2'
  AND NEW.aggregate_version <> OLD.aggregate_version + 1
BEGIN
    SELECT RAISE(ABORT, 'V2 trunk aggregate version must advance once');
END;

CREATE TRIGGER v2_stage_task_route_insert
BEFORE INSERT ON stage
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM tasks t
        WHERE t.id = NEW.task_id AND t.workflow_version = 'V2')
    THEN RAISE(ABORT, 'V2 stage requires a V2 task') END;
    SELECT CASE WHEN NEW.generation <> COALESCE((
        SELECT MAX(s.generation) + 1
        FROM stage s
        WHERE s.task_id = NEW.task_id AND s.kind = NEW.kind), 1)
    THEN RAISE(ABORT, 'Stage generation must be the next exact generation') END;
END;

CREATE TRIGGER v2_stage_identity_immutable
BEFORE UPDATE OF task_id, kind, generation, opened_at_ms ON stage
WHEN NEW.task_id IS NOT OLD.task_id
  OR NEW.kind IS NOT OLD.kind
  OR NEW.generation IS NOT OLD.generation
  OR NEW.opened_at_ms IS NOT OLD.opened_at_ms
BEGIN
    SELECT RAISE(ABORT, 'stage identity is immutable');
END;

CREATE TRIGGER v2_stage_version_monotonic
BEFORE UPDATE ON stage
WHEN NEW.version <> OLD.version + 1
BEGIN
    SELECT RAISE(ABORT, 'Stage version must advance once');
END;

CREATE TRIGGER v2_stage_completed_immutable
BEFORE UPDATE ON stage
WHEN OLD.completed_at_ms IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'completed stage is immutable');
END;

-- ── Immutable aggregate audit and command receipts ────────────────────────
CREATE TABLE trunk_transition (
    id               TEXT    NOT NULL PRIMARY KEY,
    trunk_id         TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    command_id       TEXT    NOT NULL,
    from_state       TEXT,
    to_state         TEXT    NOT NULL,
    aggregate_version INTEGER NOT NULL,
    cause            TEXT    NOT NULL,
    actor            TEXT    NOT NULL,
    occurred_at_ms   INTEGER NOT NULL,
    UNIQUE (trunk_id, command_id),
    UNIQUE (trunk_id, aggregate_version)
);

CREATE TABLE task_transition (
    id               TEXT    NOT NULL PRIMARY KEY,
    task_id          TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id       TEXT    NOT NULL,
    epoch            INTEGER NOT NULL,
    from_state       TEXT,
    to_state         TEXT    NOT NULL,
    aggregate_version INTEGER NOT NULL,
    cause            TEXT    NOT NULL,
    actor            TEXT    NOT NULL,
    occurred_at_ms   INTEGER NOT NULL,
    UNIQUE (task_id, command_id),
    UNIQUE (task_id, aggregate_version)
);

CREATE TABLE stage_transition (
    id               TEXT    NOT NULL PRIMARY KEY,
    stage_id         TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    command_id       TEXT    NOT NULL,
    generation       INTEGER NOT NULL,
    from_checkpoint  TEXT,
    to_checkpoint    TEXT    NOT NULL,
    stage_version    INTEGER NOT NULL,
    cause            TEXT    NOT NULL,
    actor            TEXT    NOT NULL,
    occurred_at_ms   INTEGER NOT NULL,
    UNIQUE (stage_id, command_id),
    UNIQUE (stage_id, stage_version)
);

CREATE TRIGGER trunk_transition_immutable
BEFORE UPDATE ON trunk_transition
BEGIN SELECT RAISE(ABORT, 'trunk transition is immutable'); END;
CREATE TRIGGER task_transition_immutable
BEFORE UPDATE ON task_transition
BEGIN SELECT RAISE(ABORT, 'task transition is immutable'); END;
CREATE TRIGGER stage_transition_immutable
BEFORE UPDATE ON stage_transition
BEGIN SELECT RAISE(ABORT, 'stage transition is immutable'); END;

CREATE TABLE task_terminal_intent (
    id               TEXT    NOT NULL PRIMARY KEY,
    task_id          TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind             TEXT    NOT NULL CHECK (kind IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')),
    source           TEXT    NOT NULL,
    source_id        TEXT,
    observed_head_sha TEXT,
    evidence_json    TEXT,
    accepted         INTEGER NOT NULL CHECK (accepted IN (0, 1)),
    recorded_at_ms   INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_task_terminal_intent_accepted
    ON task_terminal_intent(task_id) WHERE accepted = 1;
CREATE TRIGGER task_terminal_intent_owner_insert
BEFORE INSERT ON task_terminal_intent
WHEN NOT EXISTS (
    SELECT 1 FROM tasks t
    WHERE t.id = NEW.task_id AND t.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'terminal intent requires V2 Task'); END;
CREATE TRIGGER task_terminal_intent_immutable
BEFORE UPDATE ON task_terminal_intent
BEGIN SELECT RAISE(ABORT, 'task terminal intent is immutable'); END;

CREATE TABLE task_blocker (
    id                  TEXT    NOT NULL PRIMARY KEY,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    stage_id            TEXT    REFERENCES stage(id) ON DELETE CASCADE,
    owner_kind          TEXT    NOT NULL CHECK (owner_kind IN ('TASK', 'STAGE', 'EPISODE', 'OPERATION')),
    owner_id            TEXT    NOT NULL,
    subject_revision    TEXT,
    blocker_type        TEXT    NOT NULL,
    status              TEXT    NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'WAIVED')),
    payload_json        TEXT,
    opened_at_ms        INTEGER NOT NULL,
    resolved_at_ms      INTEGER,
    resolution_evidence TEXT,
    CHECK ((status = 'OPEN' AND resolved_at_ms IS NULL)
        OR (status <> 'OPEN' AND resolved_at_ms IS NOT NULL))
);
CREATE INDEX idx_task_blocker_open
    ON task_blocker(task_id, stage_id) WHERE status = 'OPEN';
CREATE TRIGGER task_blocker_owner_insert
BEFORE INSERT ON task_blocker
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks t
            WHERE t.id = NEW.task_id AND t.workflow_version = 'V2')
            THEN RAISE(ABORT, 'Task blocker requires a V2 Task')
        WHEN NEW.owner_kind = 'TASK'
                AND (NEW.owner_id <> NEW.task_id OR NEW.stage_id IS NOT NULL)
            THEN RAISE(ABORT, 'Task blocker Task owner is invalid')
        WHEN NEW.owner_kind = 'STAGE'
                AND (NEW.stage_id IS NULL OR NEW.owner_id <> NEW.stage_id
                    OR NOT EXISTS (
                        SELECT 1 FROM stage s
                        WHERE s.id = NEW.stage_id AND s.task_id = NEW.task_id))
            THEN RAISE(ABORT, 'Task blocker Stage owner is invalid')
        WHEN NEW.owner_kind IN ('EPISODE', 'OPERATION')
            THEN RAISE(ABORT, 'Task blocker owner table is not installed yet')
    END;
END;

CREATE TRIGGER task_assignment_immutable
BEFORE UPDATE ON task_assignment
BEGIN SELECT RAISE(ABORT, 'task assignment is immutable'); END;
CREATE TRIGGER task_policy_revision_immutable
BEFORE UPDATE ON task_policy_revision
BEGIN SELECT RAISE(ABORT, 'task policy revision is immutable'); END;

-- ── Physically typed Turns and their supporting records ──────────────────
CREATE TABLE thread_turn (
    id                TEXT    NOT NULL PRIMARY KEY,
    trunk_id          TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    purpose           TEXT    NOT NULL,
    status            TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING',
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    operation_id      TEXT    NOT NULL UNIQUE,
    attempt           INTEGER NOT NULL CHECK (attempt > 0),
    delivery_lane     TEXT    NOT NULL,
    launch_input      TEXT    NOT NULL,
    requested_at_ms   INTEGER NOT NULL,
    started_at_ms     INTEGER,
    finished_at_ms    INTEGER,
    error_message     TEXT
);
CREATE INDEX idx_thread_turn_trunk_status
    ON thread_turn(trunk_id, status, requested_at_ms);

CREATE TABLE task_turn (
    id                         TEXT    NOT NULL PRIMARY KEY,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    purpose                    TEXT    NOT NULL,
    status                     TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING',
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    operation_id               TEXT    NOT NULL UNIQUE,
    attempt                    INTEGER NOT NULL CHECK (attempt > 0),
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    trigger_stage_id           TEXT    REFERENCES stage(id),
    trigger_stage_generation   INTEGER,
    expected_code_fingerprint  TEXT,
    expected_head_sha          TEXT,
    expected_base_sha          TEXT,
    delivery_lane              TEXT    NOT NULL,
    launch_input               TEXT    NOT NULL,
    requested_at_ms            INTEGER NOT NULL,
    started_at_ms              INTEGER,
    finished_at_ms             INTEGER,
    error_message              TEXT,
    CHECK ((trigger_stage_id IS NULL AND trigger_stage_generation IS NULL)
        OR (trigger_stage_id IS NOT NULL AND trigger_stage_generation IS NOT NULL))
);
CREATE INDEX idx_task_turn_task_status
    ON task_turn(task_id, status, requested_at_ms);

CREATE TABLE stage_turn (
    id                         TEXT    NOT NULL PRIMARY KEY,
    stage_id                   TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    purpose                    TEXT    NOT NULL,
    status                     TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING',
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    operation_id               TEXT    NOT NULL UNIQUE,
    attempt                    INTEGER NOT NULL CHECK (attempt > 0),
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    expected_code_fingerprint  TEXT,
    expected_head_sha          TEXT,
    expected_base_sha          TEXT,
    delivery_lane              TEXT    NOT NULL,
    launch_input               TEXT    NOT NULL,
    requested_at_ms            INTEGER NOT NULL,
    started_at_ms              INTEGER,
    finished_at_ms             INTEGER,
    error_message              TEXT
);
CREATE INDEX idx_stage_turn_stage_status
    ON stage_turn(stage_id, status, requested_at_ms);

CREATE TABLE review_assignment_turn (
    id                TEXT    NOT NULL PRIMARY KEY,
    assignment_id     TEXT    NOT NULL REFERENCES review_assignment(id) ON DELETE CASCADE,
    purpose           TEXT    NOT NULL,
    status            TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING',
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    operation_id      TEXT    NOT NULL UNIQUE,
    attempt           INTEGER NOT NULL CHECK (attempt > 0),
    start_commit      TEXT    NOT NULL,
    delivery_lane     TEXT    NOT NULL,
    launch_input      TEXT    NOT NULL,
    requested_at_ms   INTEGER NOT NULL,
    started_at_ms     INTEGER,
    finished_at_ms    INTEGER,
    error_message     TEXT
);
CREATE INDEX idx_review_assignment_turn_owner_status
    ON review_assignment_turn(assignment_id, status, requested_at_ms);

CREATE TRIGGER thread_turn_global_identity_insert
BEFORE INSERT ON thread_turn
WHEN EXISTS (SELECT 1 FROM task_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
  OR EXISTS (SELECT 1 FROM stage_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
  OR EXISTS (SELECT 1 FROM review_assignment_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
BEGIN SELECT RAISE(ABORT, 'typed Turn id and operation id must be globally exact'); END;

CREATE TRIGGER task_turn_global_identity_insert
BEFORE INSERT ON task_turn
WHEN EXISTS (SELECT 1 FROM thread_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
  OR EXISTS (SELECT 1 FROM stage_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
  OR EXISTS (SELECT 1 FROM review_assignment_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
BEGIN SELECT RAISE(ABORT, 'typed Turn id and operation id must be globally exact'); END;

CREATE TRIGGER stage_turn_global_identity_insert
BEFORE INSERT ON stage_turn
WHEN EXISTS (SELECT 1 FROM thread_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
  OR EXISTS (SELECT 1 FROM task_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
  OR EXISTS (SELECT 1 FROM review_assignment_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
BEGIN SELECT RAISE(ABORT, 'typed Turn id and operation id must be globally exact'); END;

CREATE TRIGGER review_assignment_turn_global_identity_insert
BEFORE INSERT ON review_assignment_turn
WHEN EXISTS (SELECT 1 FROM thread_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
  OR EXISTS (SELECT 1 FROM task_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
  OR EXISTS (SELECT 1 FROM stage_turn WHERE id = NEW.id OR operation_id = NEW.operation_id)
BEGIN SELECT RAISE(ABORT, 'typed Turn id and operation id must be globally exact'); END;

CREATE TRIGGER task_turn_owner_fence_insert
BEFORE INSERT ON task_turn
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks t
            WHERE t.id = NEW.task_id
              AND t.workflow_version = 'V2'
              AND t.epoch = NEW.task_epoch)
            THEN RAISE(ABORT, 'task Turn fence does not match V2 Task')
        WHEN NEW.trigger_stage_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM stage s
            WHERE s.id = NEW.trigger_stage_id
              AND s.task_id = NEW.task_id
              AND s.generation = NEW.trigger_stage_generation)
            THEN RAISE(ABORT, 'task Turn trigger Stage does not match Task')
    END;
END;

CREATE TRIGGER stage_turn_owner_fence_insert
BEFORE INSERT ON stage_turn
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM stage s
        JOIN tasks t ON t.id = s.task_id
        WHERE s.id = NEW.stage_id
          AND s.generation = NEW.stage_generation
          AND t.workflow_version = 'V2'
          AND t.epoch = NEW.task_epoch)
    THEN RAISE(ABORT, 'stage Turn fence does not match V2 Stage') END;
END;

CREATE TRIGGER review_assignment_turn_start_insert
BEFORE INSERT ON review_assignment_turn
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM review_assignment a
        JOIN review_round r ON r.id = a.round_id
        WHERE a.id = NEW.assignment_id
          AND r.start_commit = NEW.start_commit)
    THEN RAISE(ABORT, 'review assignment Turn must freeze round start commit') END;
END;

CREATE TRIGGER thread_turn_identity_immutable
BEFORE UPDATE OF trunk_id, purpose, operation_id, attempt, delivery_lane, launch_input ON thread_turn
WHEN NEW.trunk_id IS NOT OLD.trunk_id
  OR NEW.purpose IS NOT OLD.purpose
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.attempt IS NOT OLD.attempt
  OR NEW.delivery_lane IS NOT OLD.delivery_lane
  OR NEW.launch_input IS NOT OLD.launch_input
BEGIN SELECT RAISE(ABORT, 'thread Turn launch identity is immutable'); END;

CREATE TRIGGER task_turn_identity_immutable
BEFORE UPDATE OF task_id, purpose, operation_id, attempt, task_epoch,
        trigger_stage_id, trigger_stage_generation, expected_code_fingerprint,
        expected_head_sha, expected_base_sha, delivery_lane, launch_input ON task_turn
WHEN NEW.task_id IS NOT OLD.task_id
  OR NEW.purpose IS NOT OLD.purpose
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.attempt IS NOT OLD.attempt
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.trigger_stage_id IS NOT OLD.trigger_stage_id
  OR NEW.trigger_stage_generation IS NOT OLD.trigger_stage_generation
  OR NEW.expected_code_fingerprint IS NOT OLD.expected_code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.delivery_lane IS NOT OLD.delivery_lane
  OR NEW.launch_input IS NOT OLD.launch_input
BEGIN SELECT RAISE(ABORT, 'task Turn launch identity is immutable'); END;

CREATE TRIGGER stage_turn_identity_immutable
BEFORE UPDATE OF stage_id, stage_generation, purpose, operation_id, attempt,
        task_epoch, expected_code_fingerprint, expected_head_sha,
        expected_base_sha, delivery_lane, launch_input ON stage_turn
WHEN NEW.stage_id IS NOT OLD.stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.purpose IS NOT OLD.purpose
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.attempt IS NOT OLD.attempt
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.expected_code_fingerprint IS NOT OLD.expected_code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.delivery_lane IS NOT OLD.delivery_lane
  OR NEW.launch_input IS NOT OLD.launch_input
BEGIN SELECT RAISE(ABORT, 'stage Turn launch identity is immutable'); END;

CREATE TRIGGER review_assignment_turn_identity_immutable
BEFORE UPDATE OF assignment_id, purpose, operation_id, attempt, start_commit,
        delivery_lane, launch_input ON review_assignment_turn
WHEN NEW.assignment_id IS NOT OLD.assignment_id
  OR NEW.purpose IS NOT OLD.purpose
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.attempt IS NOT OLD.attempt
  OR NEW.start_commit IS NOT OLD.start_commit
  OR NEW.delivery_lane IS NOT OLD.delivery_lane
  OR NEW.launch_input IS NOT OLD.launch_input
BEGIN SELECT RAISE(ABORT, 'review assignment Turn launch identity is immutable'); END;

-- Each support family references exactly one physical Turn table. There is no
-- generic nullable owner tuple to infer at read or write time.
CREATE TABLE thread_message (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES thread_turn(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq > 0),
    role TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    UNIQUE (turn_id, seq)
);
CREATE TABLE thread_question (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES thread_turn(id) ON DELETE CASCADE,
    call_id TEXT NOT NULL,
    prompt TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'ANSWERED', 'CANCELED')),
    answer TEXT,
    answer_revision INTEGER NOT NULL DEFAULT 0 CHECK (answer_revision >= 0),
    created_at_ms INTEGER NOT NULL,
    answered_at_ms INTEGER,
    UNIQUE (turn_id, call_id)
);
CREATE TABLE thread_attachment (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES thread_turn(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    content_ref TEXT NOT NULL,
    media_type TEXT,
    digest TEXT,
    created_at_ms INTEGER NOT NULL
);
CREATE TABLE thread_checkpoint (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES thread_turn(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq > 0),
    payload TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    UNIQUE (turn_id, seq)
);

CREATE TABLE task_message (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES task_turn(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq > 0),
    role TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    UNIQUE (turn_id, seq)
);
CREATE TABLE task_question (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES task_turn(id) ON DELETE CASCADE,
    call_id TEXT NOT NULL,
    prompt TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'ANSWERED', 'CANCELED')),
    answer TEXT,
    answer_revision INTEGER NOT NULL DEFAULT 0 CHECK (answer_revision >= 0),
    created_at_ms INTEGER NOT NULL,
    answered_at_ms INTEGER,
    UNIQUE (turn_id, call_id)
);
CREATE TABLE task_attachment (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES task_turn(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    content_ref TEXT NOT NULL,
    media_type TEXT,
    digest TEXT,
    created_at_ms INTEGER NOT NULL
);
CREATE TABLE task_checkpoint (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES task_turn(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq > 0),
    payload TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    UNIQUE (turn_id, seq)
);

CREATE TABLE stage_message (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES stage_turn(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq > 0),
    role TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    UNIQUE (turn_id, seq)
);
CREATE TABLE stage_question (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES stage_turn(id) ON DELETE CASCADE,
    call_id TEXT NOT NULL,
    prompt TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'ANSWERED', 'CANCELED')),
    answer TEXT,
    answer_revision INTEGER NOT NULL DEFAULT 0 CHECK (answer_revision >= 0),
    created_at_ms INTEGER NOT NULL,
    answered_at_ms INTEGER,
    UNIQUE (turn_id, call_id)
);
CREATE TABLE stage_attachment (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES stage_turn(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    content_ref TEXT NOT NULL,
    media_type TEXT,
    digest TEXT,
    created_at_ms INTEGER NOT NULL
);
CREATE TABLE stage_checkpoint (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES stage_turn(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq > 0),
    payload TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    UNIQUE (turn_id, seq)
);

CREATE TABLE review_assignment_message (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES review_assignment_turn(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq > 0),
    role TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    UNIQUE (turn_id, seq)
);
CREATE TABLE review_assignment_question (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES review_assignment_turn(id) ON DELETE CASCADE,
    call_id TEXT NOT NULL,
    prompt TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'ANSWERED', 'CANCELED')),
    answer TEXT,
    answer_revision INTEGER NOT NULL DEFAULT 0 CHECK (answer_revision >= 0),
    created_at_ms INTEGER NOT NULL,
    answered_at_ms INTEGER,
    UNIQUE (turn_id, call_id)
);
CREATE TABLE review_assignment_attachment (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES review_assignment_turn(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    content_ref TEXT NOT NULL,
    media_type TEXT,
    digest TEXT,
    created_at_ms INTEGER NOT NULL
);
CREATE TABLE review_assignment_checkpoint (
    id TEXT NOT NULL PRIMARY KEY,
    turn_id TEXT NOT NULL REFERENCES review_assignment_turn(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq > 0),
    payload TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    UNIQUE (turn_id, seq)
);

CREATE TRIGGER thread_turn_owner_insert
BEFORE INSERT ON thread_turn
WHEN NOT EXISTS (
    SELECT 1 FROM threads t
    WHERE t.id = NEW.trunk_id AND t.turn_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'thread Turn requires a V2 Trunk'); END;

CREATE TRIGGER trunk_turn_version_quiescent
BEFORE UPDATE OF turn_version ON threads
WHEN NEW.turn_version IS NOT OLD.turn_version
  AND (
      EXISTS (
          SELECT 1 FROM thread_turns legacy
          WHERE legacy.thread_id = OLD.id
            AND legacy.status IN ('QUEUED', 'RUNNING'))
      OR EXISTS (
          SELECT 1 FROM thread_turn typed
          WHERE typed.trunk_id = OLD.id
            AND typed.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')))
BEGIN
    SELECT RAISE(ABORT, 'Trunk Turn version can change only while quiescent');
END;

-- A durable permission call names one physical Turn and the exact Operation
-- that issued it. Trigger validation supplies the polymorphic FK SQLite does
-- not natively support.
CREATE TABLE permission_request (
    id                    TEXT    NOT NULL PRIMARY KEY,
    call_id               TEXT    NOT NULL UNIQUE,
    turn_kind             TEXT    NOT NULL CHECK (turn_kind IN (
        'THREAD', 'TASK', 'STAGE', 'REVIEW_ASSIGNMENT')),
    turn_id               TEXT    NOT NULL,
    operation_id          TEXT    NOT NULL,
    capability            TEXT    NOT NULL,
    tool_name             TEXT,
    parameters_json       TEXT    NOT NULL,
    policy_snapshot       TEXT    NOT NULL,
    state                 TEXT    NOT NULL CHECK (state IN (
        'OPEN', 'ALLOWED_ONCE', 'ALLOWED_NEXT', 'ALLOWED_TASK',
        'ALLOWED_REPOSITORY', 'DENIED', 'CANCELED', 'EXPIRED')),
    answer                TEXT,
    answer_revision       INTEGER NOT NULL DEFAULT 0 CHECK (answer_revision >= 0),
    requested_at_ms       INTEGER NOT NULL,
    answered_at_ms        INTEGER
);
CREATE INDEX idx_permission_request_turn
    ON permission_request(turn_kind, turn_id, requested_at_ms);

CREATE TRIGGER permission_request_owner_insert
BEFORE INSERT ON permission_request
BEGIN
    SELECT CASE WHEN
        (EXISTS (SELECT 1 FROM thread_turn x WHERE x.id = NEW.turn_id)
        + EXISTS (SELECT 1 FROM task_turn x WHERE x.id = NEW.turn_id)
        + EXISTS (SELECT 1 FROM stage_turn x WHERE x.id = NEW.turn_id)
        + EXISTS (SELECT 1 FROM review_assignment_turn x WHERE x.id = NEW.turn_id)) <> 1
    THEN RAISE(ABORT, 'permission Turn id must resolve exactly once') END;
    SELECT CASE WHEN
        (NEW.turn_kind = 'THREAD' AND NOT EXISTS (
            SELECT 1 FROM thread_turn x
            WHERE x.id = NEW.turn_id AND x.operation_id = NEW.operation_id))
        OR (NEW.turn_kind = 'TASK' AND NOT EXISTS (
            SELECT 1 FROM task_turn x
            WHERE x.id = NEW.turn_id AND x.operation_id = NEW.operation_id))
        OR (NEW.turn_kind = 'STAGE' AND NOT EXISTS (
            SELECT 1 FROM stage_turn x
            WHERE x.id = NEW.turn_id AND x.operation_id = NEW.operation_id))
        OR (NEW.turn_kind = 'REVIEW_ASSIGNMENT' AND NOT EXISTS (
            SELECT 1 FROM review_assignment_turn x
            WHERE x.id = NEW.turn_id AND x.operation_id = NEW.operation_id))
    THEN RAISE(ABORT, 'permission Operation does not match typed Turn') END;
END;

CREATE TRIGGER permission_request_identity_immutable
BEFORE UPDATE OF call_id, turn_kind, turn_id, operation_id, capability,
        tool_name, parameters_json, policy_snapshot, requested_at_ms ON permission_request
WHEN NEW.call_id IS NOT OLD.call_id
  OR NEW.turn_kind IS NOT OLD.turn_kind
  OR NEW.turn_id IS NOT OLD.turn_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.capability IS NOT OLD.capability
  OR NEW.tool_name IS NOT OLD.tool_name
  OR NEW.parameters_json IS NOT OLD.parameters_json
  OR NEW.policy_snapshot IS NOT OLD.policy_snapshot
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'permission request identity is immutable'); END;

-- ── Delivery, execution, and durable admission ───────────────────────────
CREATE TABLE dispatch_ticket (
    id                         TEXT    NOT NULL PRIMARY KEY,
    version                    INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    operation_id               TEXT    NOT NULL UNIQUE,
    operation_kind             TEXT    NOT NULL,
    async_family               TEXT    NOT NULL CHECK (async_family IN (
        'AGENT_TURN', 'VALIDATION', 'LOCAL_GIT', 'GITHUB_EFFECT',
        'REMOTE_OBSERVATION', 'MERGE', 'CLEANUP')),
    owner_kind                 TEXT    NOT NULL CHECK (owner_kind IN (
        'TRUNK', 'TASK', 'STAGE', 'THREAD_TURN', 'TASK_TURN',
        'STAGE_TURN', 'REVIEW_ASSIGNMENT_TURN')),
    owner_id                   TEXT    NOT NULL,
    callback_route             TEXT    NOT NULL,
    -- CLI=1, API=2, VALIDATION=4, REVIEW=8, LOCAL_GIT=16, GITHUB=32,
    -- REMOTE_OBSERVATION=64, MERGE=128, CLEANUP=256. A bitmask keeps the
    -- required non-empty lane set atomic with the ticket row.
    lane_mask                  INTEGER NOT NULL CHECK (lane_mask BETWEEN 1 AND 511),
    trunk_control              INTEGER NOT NULL DEFAULT 0 CHECK (trunk_control IN (0, 1)),
    exclusive_task             INTEGER NOT NULL DEFAULT 0 CHECK (exclusive_task IN (0, 1)),
    writer_required            INTEGER NOT NULL DEFAULT 0 CHECK (writer_required IN (0, 1)),
    workspace_id               TEXT    REFERENCES workspaces(id),
    trunk_id                   TEXT    REFERENCES threads(id) ON DELETE CASCADE,
    task_id                    TEXT    REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER,
    stage_id                   TEXT    REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation           INTEGER,
    attempt                    INTEGER NOT NULL CHECK (attempt > 0),
    expected_code_fingerprint  TEXT,
    expected_head_sha          TEXT,
    expected_base_sha          TEXT,
    status                     TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
        'CLAIMED', 'RUNNING', 'DELIVERING', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    claim_purpose              TEXT CHECK (claim_purpose IN ('EXECUTE', 'RECONCILE', 'DELIVER')),
    claim_owner                TEXT,
    capacity_lease_id          TEXT,
    claim_expires_at_ms        INTEGER,
    next_attempt_at_ms         INTEGER,
    cancel_requested_at_ms     INTEGER,
    infrastructure_attempts    INTEGER NOT NULL DEFAULT 0 CHECK (infrastructure_attempts >= 0),
    started_at_ms              INTEGER,
    pending_result_outcome     TEXT CHECK (pending_result_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    pending_result_payload     TEXT,
    pending_result_evidence    TEXT,
    pending_result_error       TEXT,
    delivery_acceptance        TEXT CHECK (delivery_acceptance IN (
        'ACCEPTED', 'SUPERSEDED', 'REJECTED')),
    delivery_evidence          TEXT,
    created_at_ms              INTEGER NOT NULL,
    completed_at_ms            INTEGER,
    last_error                 TEXT,
    CHECK ((stage_id IS NULL AND stage_generation IS NULL)
        OR (stage_id IS NOT NULL AND stage_generation IS NOT NULL)),
    CHECK ((task_id IS NULL AND task_epoch IS NULL)
        OR (task_id IS NOT NULL AND task_epoch IS NOT NULL)),
    CHECK (trunk_control = 0 OR task_id IS NULL),
    CHECK (exclusive_task = 0 OR task_id IS NOT NULL),
    CHECK (writer_required = 0 OR exclusive_task = 1),
    CHECK ((status IN ('CLAIMED', 'RUNNING', 'DELIVERING'))
        = (claim_purpose IS NOT NULL AND claim_owner IS NOT NULL
            AND capacity_lease_id IS NOT NULL AND claim_expires_at_ms IS NOT NULL)),
    CHECK ((status IN ('RESULT_PENDING', 'DELIVERING')
            OR (status = 'CLAIMED' AND claim_purpose = 'DELIVER'))
        = (pending_result_outcome IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
        = (completed_at_ms IS NOT NULL AND delivery_acceptance IS NOT NULL))
);
CREATE INDEX idx_dispatch_ticket_eligible
    ON dispatch_ticket(status, next_attempt_at_ms, created_at_ms);
CREATE INDEX idx_dispatch_ticket_claim
    ON dispatch_ticket(claim_owner, claim_expires_at_ms) WHERE claim_owner IS NOT NULL;

CREATE TRIGGER dispatch_ticket_owner_insert
BEFORE INSERT ON dispatch_ticket
BEGIN
    SELECT CASE
        WHEN NEW.task_id IS NOT NULL AND NOT EXISTS (
            SELECT 1
            FROM tasks t
            JOIN threads h ON h.id = t.thread_id
            WHERE t.id = NEW.task_id
              AND t.workflow_version = 'V2'
              AND t.epoch = NEW.task_epoch
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'DispatchTicket Task scope is invalid')
        WHEN NEW.stage_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM stage s
            WHERE s.id = NEW.stage_id
              AND s.task_id = NEW.task_id
              AND s.generation = NEW.stage_generation)
            THEN RAISE(ABORT, 'DispatchTicket Stage scope is invalid')
        WHEN NEW.owner_kind = 'TRUNK' AND NOT EXISTS (
            SELECT 1 FROM threads x
            WHERE x.id = NEW.owner_id
              AND x.id = NEW.trunk_id
              AND x.workspace_id IS NEW.workspace_id
              AND x.turn_version = 'V2'
              AND NEW.task_id IS NULL AND NEW.stage_id IS NULL)
            THEN RAISE(ABORT, 'DispatchTicket Trunk owner is invalid')
        WHEN NEW.owner_kind = 'TASK' AND NOT EXISTS (
            SELECT 1 FROM tasks x JOIN threads h ON h.id = x.thread_id
            WHERE x.id = NEW.owner_id AND x.workflow_version = 'V2'
              AND x.id = NEW.task_id AND x.epoch = NEW.task_epoch
              AND x.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'DispatchTicket Task owner fence is invalid')
        WHEN NEW.owner_kind = 'STAGE' AND NOT EXISTS (
            SELECT 1
            FROM stage s
            JOIN tasks t ON t.id = s.task_id
            JOIN threads h ON h.id = t.thread_id
            WHERE s.id = NEW.owner_id
              AND s.id = NEW.stage_id
              AND s.generation = NEW.stage_generation
              AND t.id = NEW.task_id
              AND t.epoch = NEW.task_epoch
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'DispatchTicket Stage owner fence is invalid')
        WHEN NEW.owner_kind = 'THREAD_TURN' AND NOT EXISTS (
            SELECT 1 FROM thread_turn x JOIN threads h ON h.id = x.trunk_id
            WHERE x.id = NEW.owner_id AND x.operation_id = NEW.operation_id
              AND x.trunk_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id
              AND NEW.task_id IS NULL AND NEW.stage_id IS NULL)
            THEN RAISE(ABORT, 'DispatchTicket ThreadTurn owner is invalid')
        WHEN NEW.owner_kind = 'TASK_TURN' AND NOT EXISTS (
            SELECT 1
            FROM task_turn x
            JOIN tasks t ON t.id = x.task_id
            JOIN threads h ON h.id = t.thread_id
            WHERE x.id = NEW.owner_id AND x.operation_id = NEW.operation_id
              AND x.task_id = NEW.task_id AND x.task_epoch = NEW.task_epoch
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id
              AND (NEW.stage_id IS NULL OR EXISTS (
                  SELECT 1 FROM stage s
                  WHERE s.id = NEW.stage_id
                    AND s.task_id = NEW.task_id
                    AND s.generation = NEW.stage_generation)))
            THEN RAISE(ABORT, 'DispatchTicket TaskTurn owner fence is invalid')
        WHEN NEW.owner_kind = 'STAGE_TURN' AND NOT EXISTS (
            SELECT 1
            FROM stage_turn x
            JOIN stage s ON s.id = x.stage_id
            JOIN tasks t ON t.id = s.task_id
            JOIN threads h ON h.id = t.thread_id
            WHERE x.id = NEW.owner_id AND x.operation_id = NEW.operation_id
              AND x.task_epoch = NEW.task_epoch
              AND x.stage_id = NEW.stage_id
              AND x.stage_generation = NEW.stage_generation
              AND s.task_id = NEW.task_id
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'DispatchTicket StageTurn owner fence is invalid')
        WHEN NEW.owner_kind = 'REVIEW_ASSIGNMENT_TURN' AND NOT EXISTS (
            SELECT 1 FROM review_assignment_turn x
            WHERE x.id = NEW.owner_id AND x.operation_id = NEW.operation_id
              AND NEW.workspace_id IS NULL AND NEW.trunk_id IS NULL
              AND NEW.task_id IS NULL AND NEW.stage_id IS NULL)
            THEN RAISE(ABORT, 'DispatchTicket review Turn owner is invalid')
    END;
END;

CREATE TRIGGER dispatch_ticket_identity_immutable
BEFORE UPDATE OF operation_id, operation_kind, owner_kind, owner_id,
        callback_route, async_family, lane_mask, trunk_control, exclusive_task,
        writer_required, workspace_id,
        trunk_id, task_id, task_epoch, stage_id, stage_generation, attempt,
        expected_code_fingerprint, expected_head_sha, expected_base_sha,
        created_at_ms ON dispatch_ticket
WHEN NEW.operation_id IS NOT OLD.operation_id
  OR NEW.operation_kind IS NOT OLD.operation_kind
  OR NEW.owner_kind IS NOT OLD.owner_kind
  OR NEW.owner_id IS NOT OLD.owner_id
  OR NEW.callback_route IS NOT OLD.callback_route
  OR NEW.async_family IS NOT OLD.async_family
  OR NEW.lane_mask IS NOT OLD.lane_mask
  OR NEW.trunk_control IS NOT OLD.trunk_control
  OR NEW.exclusive_task IS NOT OLD.exclusive_task
  OR NEW.writer_required IS NOT OLD.writer_required
  OR NEW.workspace_id IS NOT OLD.workspace_id
  OR NEW.trunk_id IS NOT OLD.trunk_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_id IS NOT OLD.stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.attempt IS NOT OLD.attempt
  OR NEW.expected_code_fingerprint IS NOT OLD.expected_code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'DispatchTicket identity is immutable'); END;

CREATE TRIGGER dispatch_ticket_state_shape_insert
BEFORE INSERT ON dispatch_ticket
BEGIN
    SELECT CASE WHEN
        (NEW.status IN ('CLAIMED', 'RUNNING', 'DELIVERING') AND (
            NEW.claim_purpose IS NULL OR NEW.claim_owner IS NULL
            OR NEW.capacity_lease_id IS NULL OR NEW.claim_expires_at_ms IS NULL))
        OR (NEW.status NOT IN ('CLAIMED', 'RUNNING', 'DELIVERING') AND (
            NEW.claim_purpose IS NOT NULL OR NEW.claim_owner IS NOT NULL
            OR NEW.capacity_lease_id IS NOT NULL OR NEW.claim_expires_at_ms IS NOT NULL))
        OR ((NEW.status IN ('RESULT_PENDING', 'DELIVERING')
                OR (NEW.status = 'CLAIMED' AND NEW.claim_purpose = 'DELIVER'))
            AND NEW.pending_result_outcome IS NULL)
        OR (NEW.status NOT IN ('RESULT_PENDING', 'DELIVERING')
            AND NOT (NEW.status = 'CLAIMED' AND NEW.claim_purpose = 'DELIVER')
            AND NEW.pending_result_outcome IS NOT NULL)
        OR (NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELED') AND (
            NEW.completed_at_ms IS NULL OR NEW.delivery_acceptance IS NULL))
        OR (NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED') AND (
            NEW.completed_at_ms IS NOT NULL OR NEW.delivery_acceptance IS NOT NULL))
    THEN RAISE(ABORT, 'DispatchTicket state evidence is incomplete') END;
END;

CREATE TRIGGER dispatch_ticket_state_shape_update
BEFORE UPDATE ON dispatch_ticket
BEGIN
    SELECT CASE WHEN
        (NEW.status IN ('CLAIMED', 'RUNNING', 'DELIVERING') AND (
            NEW.claim_purpose IS NULL OR NEW.claim_owner IS NULL
            OR NEW.capacity_lease_id IS NULL OR NEW.claim_expires_at_ms IS NULL))
        OR (NEW.status NOT IN ('CLAIMED', 'RUNNING', 'DELIVERING') AND (
            NEW.claim_purpose IS NOT NULL OR NEW.claim_owner IS NOT NULL
            OR NEW.capacity_lease_id IS NOT NULL OR NEW.claim_expires_at_ms IS NOT NULL))
        OR ((NEW.status IN ('RESULT_PENDING', 'DELIVERING')
                OR (NEW.status = 'CLAIMED' AND NEW.claim_purpose = 'DELIVER'))
            AND NEW.pending_result_outcome IS NULL)
        OR (NEW.status NOT IN ('RESULT_PENDING', 'DELIVERING')
            AND NOT (NEW.status = 'CLAIMED' AND NEW.claim_purpose = 'DELIVER')
            AND NEW.pending_result_outcome IS NOT NULL)
        OR (NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELED') AND (
            NEW.completed_at_ms IS NULL OR NEW.delivery_acceptance IS NULL))
        OR (NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED') AND (
            NEW.completed_at_ms IS NOT NULL OR NEW.delivery_acceptance IS NOT NULL))
    THEN RAISE(ABORT, 'DispatchTicket state evidence is incomplete') END;
END;

CREATE TRIGGER dispatch_ticket_version_monotonic
BEFORE UPDATE ON dispatch_ticket
WHEN NEW.version <> OLD.version + 1
BEGIN SELECT RAISE(ABORT, 'DispatchTicket version must advance'); END;

CREATE TABLE outbox (
    id                TEXT    NOT NULL PRIMARY KEY,
    dedup_key         TEXT    NOT NULL UNIQUE,
    aggregate_kind    TEXT    NOT NULL,
    aggregate_id      TEXT    NOT NULL,
    topic             TEXT    NOT NULL,
    payload           TEXT    NOT NULL,
    status            TEXT    NOT NULL CHECK (status IN ('PENDING', 'CLAIMED', 'DELIVERED', 'FAILED')),
    attempts          INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at_ms   INTEGER NOT NULL,
    claim_owner       TEXT,
    lease_until_ms    INTEGER,
    created_at_ms     INTEGER NOT NULL,
    delivered_at_ms   INTEGER,
    last_error        TEXT
);
CREATE INDEX idx_outbox_delivery
    ON outbox(status, available_at_ms, created_at_ms);
CREATE TRIGGER outbox_identity_immutable
BEFORE UPDATE OF dedup_key, aggregate_kind, aggregate_id, topic, payload, created_at_ms ON outbox
WHEN NEW.dedup_key IS NOT OLD.dedup_key
  OR NEW.aggregate_kind IS NOT OLD.aggregate_kind
  OR NEW.aggregate_id IS NOT OLD.aggregate_id
  OR NEW.topic IS NOT OLD.topic
  OR NEW.payload IS NOT OLD.payload
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'outbox identity is immutable'); END;

CREATE TABLE agent_execution (
    id                   TEXT    NOT NULL PRIMARY KEY,
    ticket_id            TEXT    NOT NULL REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    infrastructure_attempt INTEGER NOT NULL CHECK (infrastructure_attempt > 0),
    provider             TEXT,
    provider_session_id  TEXT,
    process_pid          INTEGER,
    log_ref              TEXT,
    status               TEXT    NOT NULL CHECK (status IN (
        'STARTING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED', 'UNKNOWN')),
    started_at_ms        INTEGER NOT NULL,
    heartbeat_at_ms      INTEGER,
    finished_at_ms       INTEGER,
    raw_result           TEXT,
    error_class          TEXT,
    error_message        TEXT,
    cost_usd_milli       INTEGER NOT NULL DEFAULT 0,
    tokens_in            INTEGER NOT NULL DEFAULT 0,
    tokens_out           INTEGER NOT NULL DEFAULT 0,
    UNIQUE (ticket_id, infrastructure_attempt)
);
CREATE TABLE agent_execution_log (
    execution_id TEXT NOT NULL REFERENCES agent_execution(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq >= 0),
    payload TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    PRIMARY KEY (execution_id, seq)
);

CREATE TABLE capacity_lease (
    id               TEXT    NOT NULL PRIMARY KEY,
    ticket_id        TEXT    REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    operation_id     TEXT    NOT NULL,
    workflow_source  TEXT    NOT NULL CHECK (workflow_source IN ('LEGACY', 'V2')),
    lane_mask        INTEGER NOT NULL CHECK (lane_mask BETWEEN 1 AND 511),
    trunk_control    INTEGER NOT NULL CHECK (trunk_control IN (0, 1)),
    exclusive_task   INTEGER NOT NULL CHECK (exclusive_task IN (0, 1)),
    writer_required  INTEGER NOT NULL CHECK (writer_required IN (0, 1)),
    workspace_id     TEXT    REFERENCES workspaces(id),
    trunk_id         TEXT    REFERENCES threads(id) ON DELETE CASCADE,
    task_id          TEXT    REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch       INTEGER,
    holder           TEXT    NOT NULL,
    fencing_token    INTEGER CHECK (fencing_token > 0),
    acquired_at_ms   INTEGER NOT NULL,
    heartbeat_at_ms  INTEGER NOT NULL,
    expires_at_ms    INTEGER NOT NULL,
    released_at_ms   INTEGER,
    release_reason   TEXT,
    CHECK (expires_at_ms > acquired_at_ms),
    CHECK (heartbeat_at_ms >= acquired_at_ms),
    CHECK ((released_at_ms IS NULL AND release_reason IS NULL)
        OR (released_at_ms IS NOT NULL AND release_reason IS NOT NULL)),
    CHECK (trunk_control = 0 OR task_id IS NULL),
    CHECK (exclusive_task = 0 OR task_id IS NOT NULL),
    CHECK (writer_required = 0 OR (exclusive_task = 1 AND fencing_token IS NOT NULL)),
    CHECK ((task_id IS NULL AND task_epoch IS NULL)
        OR (task_id IS NOT NULL AND task_epoch IS NOT NULL))
);
CREATE UNIQUE INDEX idx_capacity_lease_live_operation
    ON capacity_lease(operation_id) WHERE released_at_ms IS NULL;
CREATE UNIQUE INDEX idx_capacity_lease_live_ticket
    ON capacity_lease(ticket_id) WHERE released_at_ms IS NULL AND ticket_id IS NOT NULL;
CREATE UNIQUE INDEX idx_capacity_lease_mutating_task
    ON capacity_lease(task_id)
    WHERE released_at_ms IS NULL AND exclusive_task = 1;
CREATE INDEX idx_capacity_lease_expiry
    ON capacity_lease(released_at_ms, expires_at_ms);
CREATE UNIQUE INDEX idx_capacity_lease_writer_fence
    ON capacity_lease(task_id, fencing_token) WHERE fencing_token IS NOT NULL;

CREATE TRIGGER capacity_lease_route_insert
BEFORE INSERT ON capacity_lease
BEGIN
    SELECT CASE
        WHEN NEW.workflow_source = 'V2' AND NEW.ticket_id IS NULL
            THEN RAISE(ABORT, 'V2 capacity lease requires DispatchTicket')
        WHEN NEW.workflow_source = 'V2' AND NOT EXISTS (
            SELECT 1 FROM dispatch_ticket d
            WHERE d.id = NEW.ticket_id
              AND d.operation_id = NEW.operation_id
              AND d.lane_mask = NEW.lane_mask
              AND d.workspace_id IS NEW.workspace_id
              AND d.trunk_id IS NEW.trunk_id
              AND d.task_id IS NEW.task_id
              AND d.task_epoch IS NEW.task_epoch
              AND d.trunk_control = NEW.trunk_control
              AND d.exclusive_task = NEW.exclusive_task
              AND d.writer_required = NEW.writer_required)
            THEN RAISE(ABORT, 'V2 capacity lease does not match DispatchTicket')
        WHEN NEW.task_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM tasks t JOIN threads h ON h.id = t.thread_id
            WHERE t.id = NEW.task_id
              AND t.workflow_version = NEW.workflow_source
              AND t.epoch = NEW.task_epoch
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'capacity lease does not match Task route and epoch')
        WHEN NEW.task_id IS NULL AND NEW.trunk_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM threads h
            WHERE h.id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'capacity lease does not match Trunk route')
    END;
END;

CREATE TRIGGER capacity_lease_identity_immutable
BEFORE UPDATE OF ticket_id, operation_id, workflow_source, trunk_control,
        lane_mask, exclusive_task, writer_required, workspace_id, trunk_id, task_id,
        task_epoch, holder, fencing_token, acquired_at_ms ON capacity_lease
WHEN NEW.ticket_id IS NOT OLD.ticket_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.workflow_source IS NOT OLD.workflow_source
  OR NEW.lane_mask IS NOT OLD.lane_mask
  OR NEW.trunk_control IS NOT OLD.trunk_control
  OR NEW.exclusive_task IS NOT OLD.exclusive_task
  OR NEW.writer_required IS NOT OLD.writer_required
  OR NEW.workspace_id IS NOT OLD.workspace_id
  OR NEW.trunk_id IS NOT OLD.trunk_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.holder IS NOT OLD.holder
  OR NEW.fencing_token IS NOT OLD.fencing_token
  OR NEW.acquired_at_ms IS NOT OLD.acquired_at_ms
BEGIN SELECT RAISE(ABORT, 'capacity lease identity is immutable'); END;

CREATE TRIGGER dispatch_ticket_capacity_claim_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.status IN ('CLAIMED', 'RUNNING', 'DELIVERING')
  AND NOT EXISTS (
      SELECT 1 FROM capacity_lease c
      WHERE c.id = NEW.capacity_lease_id
        AND c.ticket_id = NEW.id
        AND c.operation_id = NEW.operation_id
        AND c.workflow_source = 'V2'
        AND c.lane_mask = NEW.lane_mask
        AND c.holder = NEW.claim_owner
        AND c.released_at_ms IS NULL
        AND NEW.claim_expires_at_ms <= c.expires_at_ms)
BEGIN SELECT RAISE(ABORT, 'DispatchTicket claim requires its exact live CapacityLease'); END;

CREATE TRIGGER dispatch_ticket_capacity_claim_update
BEFORE UPDATE ON dispatch_ticket
WHEN NEW.status IN ('CLAIMED', 'RUNNING', 'DELIVERING')
  AND NOT EXISTS (
      SELECT 1 FROM capacity_lease c
      WHERE c.id = NEW.capacity_lease_id
        AND c.ticket_id = NEW.id
        AND c.operation_id = NEW.operation_id
        AND c.workflow_source = 'V2'
        AND c.lane_mask = NEW.lane_mask
        AND c.workspace_id IS NEW.workspace_id
        AND c.trunk_id IS NEW.trunk_id
        AND c.task_id IS NEW.task_id
        AND c.task_epoch IS NEW.task_epoch
        AND c.holder = NEW.claim_owner
        AND c.released_at_ms IS NULL
        AND NEW.claim_expires_at_ms <= c.expires_at_ms)
BEGIN SELECT RAISE(ABORT, 'DispatchTicket claim requires its exact live CapacityLease'); END;

-- ── Exact V2 fences on durable stores whose legacy semantics already fit ─
ALTER TABLE validation_pass ADD COLUMN workflow_version TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (workflow_version IN ('LEGACY', 'V2'));
ALTER TABLE validation_pass ADD COLUMN task_epoch INTEGER;
ALTER TABLE validation_pass ADD COLUMN stage_id TEXT REFERENCES stage(id);
ALTER TABLE validation_pass ADD COLUMN stage_generation INTEGER;
ALTER TABLE validation_pass ADD COLUMN operation_id TEXT;
ALTER TABLE validation_pass ADD COLUMN semantic_attempt INTEGER;
ALTER TABLE validation_pass ADD COLUMN expected_head_sha TEXT;
ALTER TABLE validation_pass ADD COLUMN expected_base_sha TEXT;
CREATE UNIQUE INDEX idx_validation_pass_v2_operation
    ON validation_pass(operation_id) WHERE operation_id IS NOT NULL;

CREATE TRIGGER validation_pass_route_insert
BEFORE INSERT ON validation_pass
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM tasks t
        WHERE t.id = NEW.task_id AND t.workflow_version = NEW.workflow_version)
    THEN RAISE(ABORT, 'validation workflow version does not match Task') END;
    SELECT CASE WHEN NEW.workflow_version = 'V2' AND (
        NEW.task_epoch IS NULL OR NEW.stage_id IS NULL
        OR NEW.stage_generation IS NULL OR NEW.operation_id IS NULL
        OR NEW.semantic_attempt IS NULL OR NEW.semantic_attempt <= 0
        OR NEW.code_fingerprint IS NULL
        OR NOT EXISTS (
            SELECT 1 FROM stage s JOIN tasks t ON t.id = s.task_id
            WHERE s.id = NEW.stage_id
              AND s.generation = NEW.stage_generation
              AND t.id = NEW.task_id
              AND t.epoch = NEW.task_epoch))
    THEN RAISE(ABORT, 'V2 validation requires its exact Task, Stage and operation fence') END;
END;

CREATE TRIGGER validation_pass_fence_immutable
BEFORE UPDATE OF workflow_version, task_epoch, stage_id, stage_generation,
        operation_id, semantic_attempt, code_fingerprint,
        expected_head_sha, expected_base_sha ON validation_pass
WHEN NEW.workflow_version IS NOT OLD.workflow_version
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_id IS NOT OLD.stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
BEGIN SELECT RAISE(ABORT, 'validation fence is immutable'); END;

ALTER TABLE task_push_authorization ADD COLUMN workflow_version TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (workflow_version IN ('LEGACY', 'V2'));
ALTER TABLE task_push_authorization ADD COLUMN task_epoch INTEGER;
ALTER TABLE task_push_authorization ADD COLUMN stage_id TEXT REFERENCES stage(id);
ALTER TABLE task_push_authorization ADD COLUMN stage_generation INTEGER;
ALTER TABLE task_push_authorization ADD COLUMN operation_id TEXT;
ALTER TABLE task_push_authorization ADD COLUMN semantic_attempt INTEGER;
ALTER TABLE task_push_authorization ADD COLUMN expected_base_sha TEXT;
CREATE UNIQUE INDEX idx_task_push_authorization_v2_operation
    ON task_push_authorization(operation_id) WHERE operation_id IS NOT NULL;

CREATE TRIGGER task_push_authorization_route_insert
BEFORE INSERT ON task_push_authorization
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM tasks t
        WHERE t.id = NEW.task_id AND t.workflow_version = NEW.workflow_version)
    THEN RAISE(ABORT, 'push authorization workflow version does not match Task') END;
    SELECT CASE WHEN NEW.workflow_version = 'V2' AND (
        NEW.task_epoch IS NULL OR NEW.stage_id IS NULL
        OR NEW.stage_generation IS NULL OR NEW.operation_id IS NULL
        OR NEW.semantic_attempt IS NULL OR NEW.semantic_attempt <= 0
        OR NEW.expected_base_sha IS NULL
        OR NEW.head_sha IS NULL OR NEW.code_fingerprint IS NULL
        OR NOT EXISTS (
            SELECT 1 FROM stage s JOIN tasks t ON t.id = s.task_id
            WHERE s.id = NEW.stage_id
              AND s.generation = NEW.stage_generation
              AND t.id = NEW.task_id
              AND t.epoch = NEW.task_epoch))
    THEN RAISE(ABORT, 'V2 push authorization requires its complete fence') END;
END;

CREATE TRIGGER task_push_authorization_fence_immutable
BEFORE UPDATE OF workflow_version, task_epoch, stage_id, stage_generation,
        operation_id, semantic_attempt, expected_base_sha,
        head_sha, code_fingerprint ON task_push_authorization
WHEN NEW.workflow_version IS NOT OLD.workflow_version
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_id IS NOT OLD.stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
BEGIN SELECT RAISE(ABORT, 'push authorization fence is immutable'); END;

ALTER TABLE response_round ADD COLUMN workflow_version TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (workflow_version IN ('LEGACY', 'V2'));
ALTER TABLE response_round ADD COLUMN task_epoch INTEGER;
ALTER TABLE response_round ADD COLUMN stage_id TEXT REFERENCES stage(id);
ALTER TABLE response_round ADD COLUMN stage_generation INTEGER;
ALTER TABLE response_round ADD COLUMN operation_id TEXT;
ALTER TABLE response_round ADD COLUMN semantic_attempt INTEGER;
ALTER TABLE response_round ADD COLUMN expected_head_sha TEXT;
ALTER TABLE response_round ADD COLUMN expected_base_sha TEXT;
CREATE UNIQUE INDEX idx_response_round_v2_operation
    ON response_round(operation_id) WHERE operation_id IS NOT NULL;

CREATE TRIGGER response_round_route_insert
BEFORE INSERT ON response_round
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM tasks t
        WHERE t.id = NEW.task_id AND t.workflow_version = NEW.workflow_version)
    THEN RAISE(ABORT, 'response round workflow version does not match Task') END;
    SELECT CASE WHEN NEW.workflow_version = 'V2' AND (
        NEW.task_epoch IS NULL OR NEW.stage_id IS NULL
        OR NEW.stage_generation IS NULL OR NEW.operation_id IS NULL
        OR NEW.semantic_attempt IS NULL OR NEW.semantic_attempt <= 0
        OR NEW.code_fingerprint IS NULL
        OR NEW.expected_head_sha IS NULL OR NEW.expected_base_sha IS NULL
        OR NOT EXISTS (
            SELECT 1 FROM stage s JOIN tasks t ON t.id = s.task_id
            WHERE s.id = NEW.stage_id
              AND s.generation = NEW.stage_generation
              AND t.id = NEW.task_id
              AND t.epoch = NEW.task_epoch))
    THEN RAISE(ABORT, 'V2 response round requires its complete fence') END;
END;

CREATE TRIGGER response_round_fence_immutable
BEFORE UPDATE OF workflow_version, task_epoch, stage_id, stage_generation,
        operation_id, semantic_attempt, code_fingerprint,
        expected_head_sha, expected_base_sha ON response_round
WHEN NEW.workflow_version IS NOT OLD.workflow_version
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_id IS NOT OLD.stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
BEGIN SELECT RAISE(ABORT, 'response round fence is immutable'); END;

ALTER TABLE round_gate_authorization ADD COLUMN workflow_version TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (workflow_version IN ('LEGACY', 'V2'));
ALTER TABLE round_gate_authorization ADD COLUMN task_epoch INTEGER;
ALTER TABLE round_gate_authorization ADD COLUMN stage_id TEXT REFERENCES stage(id);
ALTER TABLE round_gate_authorization ADD COLUMN stage_generation INTEGER;
ALTER TABLE round_gate_authorization ADD COLUMN operation_id TEXT;
ALTER TABLE round_gate_authorization ADD COLUMN semantic_attempt INTEGER;
ALTER TABLE round_gate_authorization ADD COLUMN expected_head_sha TEXT;
ALTER TABLE round_gate_authorization ADD COLUMN expected_base_sha TEXT;
CREATE UNIQUE INDEX idx_round_gate_authorization_v2_operation
    ON round_gate_authorization(operation_id) WHERE operation_id IS NOT NULL;

CREATE TRIGGER round_gate_authorization_route_insert
BEFORE INSERT ON round_gate_authorization
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM tasks t
        WHERE t.id = NEW.task_id AND t.workflow_version = NEW.workflow_version)
    THEN RAISE(ABORT, 'round gate workflow version does not match Task') END;
    SELECT CASE WHEN NEW.workflow_version = 'V2' AND (
        NEW.task_epoch IS NULL OR NEW.stage_id IS NULL
        OR NEW.stage_generation IS NULL OR NEW.operation_id IS NULL
        OR NEW.semantic_attempt IS NULL OR NEW.semantic_attempt <= 0
        OR NEW.code_fingerprint IS NULL
        OR NEW.expected_head_sha IS NULL OR NEW.expected_base_sha IS NULL
        OR NOT EXISTS (
            SELECT 1
            FROM response_round r
            JOIN stage s ON s.id = r.stage_id
            JOIN tasks t ON t.id = r.task_id
            WHERE r.id = NEW.round_id
              AND r.workflow_version = NEW.workflow_version
              AND r.task_id = NEW.task_id
              AND r.task_epoch = NEW.task_epoch
              AND r.stage_id = NEW.stage_id
              AND r.stage_generation = NEW.stage_generation
              AND r.operation_id = NEW.operation_id
              AND r.semantic_attempt = NEW.semantic_attempt
              AND r.code_fingerprint IS NEW.code_fingerprint
              AND r.expected_head_sha IS NEW.expected_head_sha
              AND r.expected_base_sha IS NEW.expected_base_sha
              AND s.generation = NEW.stage_generation
              AND t.epoch = NEW.task_epoch))
    THEN RAISE(ABORT, 'V2 round gate authorization requires its complete fence') END;
END;

CREATE TRIGGER round_gate_authorization_fence_immutable
BEFORE UPDATE OF workflow_version, task_epoch, stage_id, stage_generation,
        operation_id, semantic_attempt, code_fingerprint,
        expected_head_sha, expected_base_sha ON round_gate_authorization
WHEN NEW.workflow_version IS NOT OLD.workflow_version
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_id IS NOT OLD.stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
BEGIN SELECT RAISE(ABORT, 'round gate authorization fence is immutable'); END;

ALTER TABLE worktree_leases ADD COLUMN workflow_version TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (workflow_version IN ('LEGACY', 'V2'));
ALTER TABLE worktree_leases ADD COLUMN operation_id TEXT;
ALTER TABLE worktree_leases ADD COLUMN task_epoch INTEGER;
ALTER TABLE worktree_leases ADD COLUMN fencing_token INTEGER;
ALTER TABLE worktree_leases ADD COLUMN lease_owner TEXT;
CREATE UNIQUE INDEX idx_worktree_leases_v2_task
    ON worktree_leases(task_id) WHERE workflow_version = 'V2';
CREATE UNIQUE INDEX idx_worktree_leases_v2_fence
    ON worktree_leases(task_id, fencing_token) WHERE fencing_token IS NOT NULL;

CREATE TRIGGER worktree_lease_route_insert
BEFORE INSERT ON worktree_leases
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM tasks t
        WHERE t.id = NEW.task_id AND t.workflow_version = NEW.workflow_version)
    THEN RAISE(ABORT, 'worktree lease workflow version does not match Task') END;
    SELECT CASE WHEN NEW.workflow_version = 'V2' AND (
        NEW.operation_id IS NULL OR NEW.task_epoch IS NULL
        OR NEW.fencing_token IS NULL OR NEW.fencing_token <= 0
        OR NEW.lease_owner IS NULL OR NEW.expires_at_ms IS NULL
        OR NOT EXISTS (
            SELECT 1
            FROM capacity_lease c
            JOIN tasks t ON t.id = c.task_id
            WHERE c.operation_id = NEW.operation_id
              AND c.workflow_source = 'V2'
              AND c.task_id = NEW.task_id
              AND c.task_epoch = NEW.task_epoch
              AND c.writer_required = 1
              AND c.fencing_token = NEW.fencing_token
              AND c.holder = NEW.lease_owner
              AND c.released_at_ms IS NULL
              AND NEW.expires_at_ms <= c.expires_at_ms
              AND t.epoch = NEW.task_epoch))
    THEN RAISE(ABORT, 'V2 worktree lease requires its exact fence') END;
END;

CREATE TRIGGER worktree_lease_fence_immutable
BEFORE UPDATE OF worktree_path, task_id, agent_kind, acquired_at_ms,
        workflow_version, operation_id, task_epoch,
        fencing_token, lease_owner ON worktree_leases
WHEN NEW.worktree_path IS NOT OLD.worktree_path
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.agent_kind IS NOT OLD.agent_kind
  OR NEW.acquired_at_ms IS NOT OLD.acquired_at_ms
  OR NEW.workflow_version IS NOT OLD.workflow_version
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.fencing_token IS NOT OLD.fencing_token
  OR NEW.lease_owner IS NOT OLD.lease_owner
BEGIN SELECT RAISE(ABORT, 'worktree lease fence is immutable'); END;

CREATE TRIGGER worktree_lease_route_update
BEFORE UPDATE ON worktree_leases
WHEN OLD.workflow_version = 'V2'
  AND NOT EXISTS (
      SELECT 1 FROM capacity_lease c
      WHERE c.operation_id = NEW.operation_id
        AND c.workflow_source = 'V2'
        AND c.task_id = NEW.task_id
        AND c.task_epoch = NEW.task_epoch
        AND c.writer_required = 1
        AND c.fencing_token = NEW.fencing_token
        AND c.holder = NEW.lease_owner
        AND c.released_at_ms IS NULL
        AND NEW.expires_at_ms IS NOT NULL
        AND NEW.expires_at_ms <= c.expires_at_ms)
BEGIN SELECT RAISE(ABORT, 'V2 worktree lease update lost its CapacityLease'); END;
