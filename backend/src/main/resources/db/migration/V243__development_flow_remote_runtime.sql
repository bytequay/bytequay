-- Runtime routing for exact-head Remote observation, CI repair, and branch
-- sync. V232 owns domain evidence; this migration only adds the finite
-- Operations, delivery receipts, and dispatch guards needed to execute it.

CREATE TABLE remote_ci_required_check (
    ci_policy_revision_id TEXT NOT NULL
        REFERENCES remote_ci_policy_revision(id) ON DELETE CASCADE,
    check_name            TEXT NOT NULL,
    PRIMARY KEY (ci_policy_revision_id, check_name),
    CHECK (length(trim(check_name)) > 0)
);

CREATE TRIGGER remote_ci_required_check_immutable
BEFORE UPDATE ON remote_ci_required_check
BEGIN SELECT RAISE(ABORT, 'Remote CI required check is immutable'); END;

CREATE TABLE remote_observation_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id        TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    ci_policy_revision_id       TEXT    NOT NULL REFERENCES remote_ci_policy_revision(id),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'ACCEPTED', 'SUPERSEDED',
        'FAILED', 'CANCELED')),
    snapshot_id                 TEXT UNIQUE REFERENCES remote_pr_snapshot(id),
    ci_evaluation_id            TEXT UNIQUE REFERENCES remote_ci_evaluation(id),
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    UNIQUE (remote_development_stage_id, semantic_attempt),
    CHECK ((status IN ('ACCEPTED', 'SUPERSEDED', 'FAILED', 'CANCELED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (status NOT IN ('ACCEPTED', 'SUPERSEDED') OR snapshot_id IS NOT NULL),
    CHECK (ci_evaluation_id IS NULL OR snapshot_id IS NOT NULL)
);

CREATE UNIQUE INDEX idx_remote_observation_one_live_stage
    ON remote_observation_operation(remote_development_stage_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TRIGGER remote_observation_operation_insert
BEFORE INSERT ON remote_observation_operation
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_development_stage remote
    JOIN stage owner ON owner.id = remote.stage_id
    JOIN tasks task ON task.id = remote.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN remote_ci_policy_revision policy
      ON policy.id = NEW.ci_policy_revision_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
      AND remote.current_head_sha = NEW.expected_head_sha
      AND remote.current_base_sha = NEW.expected_base_sha
      AND owner.completed_at_ms IS NULL
      AND current.stage_id = remote.stage_id
      AND current.stage_generation = remote.generation
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND policy.task_id = NEW.task_id
      AND policy.remote_pr_binding_id = NEW.remote_pr_binding_id
      AND NEW.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Remote observation requires the current exact subject'); END;

CREATE TRIGGER remote_observation_operation_identity_immutable
BEFORE UPDATE OF id, remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, ci_policy_revision_id,
        operation_id, semantic_attempt, expected_head_sha, expected_base_sha,
        requested_at_ms ON remote_observation_operation
BEGIN SELECT RAISE(ABORT, 'Remote observation operation identity is immutable'); END;

CREATE TRIGGER remote_observation_operation_status
BEFORE UPDATE OF status ON remote_observation_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'ACCEPTED', 'SUPERSEDED', 'FAILED', 'CANCELED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'ACCEPTED', 'SUPERSEDED', 'FAILED', 'CANCELED')))
BEGIN SELECT RAISE(ABORT, 'Remote observation status transition is invalid'); END;

CREATE TRIGGER remote_observation_operation_dispatched
BEFORE UPDATE OF status ON remote_observation_operation
WHEN NEW.status = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.operation_id = NEW.operation_id
      AND ticket.operation_kind = 'OBSERVE_REMOTE_PR'
      AND ticket.async_family = 'REMOTE_OBSERVATION'
      AND ticket.owner_kind = 'STAGE'
      AND ticket.owner_id = NEW.remote_development_stage_id
      AND ticket.callback_route = 'REMOTE_OBSERVATION_RESULT'
      AND ticket.task_id = NEW.task_id
      AND ticket.task_epoch = NEW.task_epoch
      AND ticket.stage_id = NEW.remote_development_stage_id
      AND ticket.stage_generation = NEW.stage_generation
      AND ticket.attempt = NEW.semantic_attempt
      AND ticket.expected_head_sha = NEW.expected_head_sha
      AND ticket.expected_base_sha = NEW.expected_base_sha
      AND ticket.lane_mask = 64
      AND ticket.trunk_control = 0
      AND ticket.exclusive_task = 0
      AND ticket.writer_required = 0
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Remote observation lacks its exact DispatchTicket'); END;

CREATE TRIGGER dispatch_ticket_remote_observation_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'OBSERVE_REMOTE_PR'
  OR NEW.callback_route = 'REMOTE_OBSERVATION_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM remote_observation_operation operation
        WHERE operation.operation_id = NEW.operation_id
          AND operation.remote_development_stage_id = NEW.owner_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.stage_generation = NEW.stage_generation
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND operation.semantic_attempt = NEW.attempt
          AND NEW.operation_kind = 'OBSERVE_REMOTE_PR'
          AND NEW.async_family = 'REMOTE_OBSERVATION'
          AND NEW.owner_kind = 'STAGE'
          AND NEW.owner_id = NEW.stage_id
          AND NEW.callback_route = 'REMOTE_OBSERVATION_RESULT'
          AND NEW.lane_mask = 64
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 0
          AND NEW.writer_required = 0
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Remote observation DispatchTicket is not exact') END;
END;

CREATE TABLE remote_observation_delivery_receipt (
    remote_observation_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES remote_observation_operation(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    raw_outcome        TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest  TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance         TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    snapshot_id        TEXT REFERENCES remote_pr_snapshot(id),
    ci_evaluation_id   TEXT REFERENCES remote_ci_evaluation(id),
    recorded_at_ms     INTEGER NOT NULL,
    CHECK ((snapshot_id IS NOT NULL) =
        (raw_outcome = 'SUCCEEDED')),
    CHECK (ci_evaluation_id IS NULL OR snapshot_id IS NOT NULL)
);

CREATE TRIGGER remote_observation_delivery_receipt_insert
BEFORE INSERT ON remote_observation_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_observation_operation operation
    JOIN dispatch_ticket ticket ON ticket.operation_id = operation.operation_id
    WHERE operation.id = NEW.remote_observation_operation_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN ('ACCEPTED', 'SUPERSEDED', 'FAILED', 'CANCELED')
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND (NEW.snapshot_id IS NULL OR operation.snapshot_id = NEW.snapshot_id)
      AND (NEW.ci_evaluation_id IS NULL
          OR operation.ci_evaluation_id = NEW.ci_evaluation_id))
BEGIN SELECT RAISE(ABORT, 'Remote observation delivery receipt is not exact'); END;

CREATE TRIGGER remote_observation_delivery_receipt_immutable
BEFORE UPDATE ON remote_observation_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Remote observation delivery receipt is immutable'); END;

-- One immutable output subject for every successful Remote code-writing Turn.
-- It lets the generic typed-turn executor fence the next Remote Turn without
-- rewriting Local DevReport history.
CREATE TABLE remote_code_subject (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    stage_turn_id               TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    source_code_fingerprint     TEXT    NOT NULL,
    source_head_sha             TEXT    NOT NULL,
    source_base_sha             TEXT    NOT NULL,
    code_fingerprint            TEXT    NOT NULL,
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    created_at_ms               INTEGER NOT NULL
);

CREATE TRIGGER remote_code_subject_insert
BEFORE INSERT ON remote_code_subject
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_development_stage remote
    JOIN stage_turn turn ON turn.id = NEW.stage_turn_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND turn.stage_id = remote.stage_id
      AND turn.stage_generation = remote.generation
      AND turn.task_epoch = NEW.task_epoch
      AND turn.expected_code_fingerprint = NEW.source_code_fingerprint
      AND turn.expected_head_sha = NEW.source_head_sha
      AND turn.expected_base_sha = NEW.source_base_sha
      AND turn.status = 'SUCCEEDED')
BEGIN SELECT RAISE(ABORT, 'Remote code subject lacks its exact successful StageTurn'); END;

CREATE TRIGGER remote_code_subject_immutable
BEFORE UPDATE ON remote_code_subject
BEGIN SELECT RAISE(ABORT, 'Remote code subject is immutable'); END;

DROP VIEW task_current_code_subject_v230;
CREATE VIEW task_current_code_subject_v230 AS
SELECT task.id AS task_id,
       COALESCE(remote.code_fingerprint, report.code_fingerprint,
                code.code_fingerprint) AS code_fingerprint,
       COALESCE(remote.head_sha, report.head_sha, code.local_head_sha) AS head_sha,
       COALESCE(remote.base_sha, report.base_sha, code.base_sha) AS base_sha
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
WHERE task.workflow_version = 'V2';

CREATE TABLE ci_repair_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id        TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'RERUN', 'FIX_STAGE_TURN', 'VALIDATE', 'BRAIN_REVIEW', 'PUSH_HEAD')),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    stage_turn_id               TEXT UNIQUE REFERENCES stage_turn(id),
    task_turn_id                TEXT UNIQUE REFERENCES task_turn(id),
    expected_code_fingerprint   TEXT,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'CANCELED', 'SUPERSEDED')),
    result_code_fingerprint     TEXT,
    result_head_sha             TEXT,
    result_evidence             TEXT,
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    UNIQUE (ci_repair_episode_id, kind, semantic_attempt),
    CHECK ((kind = 'FIX_STAGE_TURN') = (stage_turn_id IS NOT NULL)),
    CHECK ((kind = 'BRAIN_REVIEW') = (task_turn_id IS NOT NULL)),
    CHECK (stage_turn_id IS NULL OR task_turn_id IS NULL),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (kind <> 'PUSH_HEAD' OR status <> 'SUCCEEDED'
        OR result_head_sha IS NOT NULL)
);

CREATE UNIQUE INDEX idx_ci_repair_one_live_operation
    ON ci_repair_operation(ci_repair_episode_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TRIGGER ci_repair_operation_insert
BEFORE INSERT ON ci_repair_operation
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_repair_episode episode
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    JOIN tasks task ON task.id = episode.task_id
    WHERE episode.id = NEW.ci_repair_episode_id
      AND episode.remote_development_stage_id = NEW.remote_development_stage_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.stage_generation = NEW.stage_generation
      AND episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
      AND remote.current_head_sha = NEW.expected_head_sha
      AND remote.current_base_sha = NEW.expected_base_sha
      AND task.epoch = NEW.task_epoch
      AND task.lifecycle_state = 'ACTIVE'
      AND NEW.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'CI repair Operation requires its live exact-head Episode'); END;

CREATE TRIGGER ci_repair_operation_identity_immutable
BEFORE UPDATE OF id, ci_repair_episode_id, remote_development_stage_id,
        task_id, task_epoch, stage_generation, kind, operation_id,
        semantic_attempt, stage_turn_id, task_turn_id,
        expected_code_fingerprint, expected_head_sha, expected_base_sha,
        requested_at_ms ON ci_repair_operation
BEGIN SELECT RAISE(ABORT, 'CI repair Operation identity is immutable'); END;

CREATE TABLE ci_repair_delivery_receipt (
    ci_repair_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES ci_repair_operation(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    raw_outcome        TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest  TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance         TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    recorded_at_ms     INTEGER NOT NULL
);

CREATE TRIGGER ci_repair_delivery_receipt_immutable
BEFORE UPDATE ON ci_repair_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'CI repair delivery receipt is immutable'); END;

CREATE TABLE ci_repair_budget_extension (
    id                   TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id TEXT    NOT NULL REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    command_id           TEXT    NOT NULL UNIQUE,
    kind                 TEXT    NOT NULL CHECK (kind IN (
        'EXTEND', 'PER_PUSH_APPROVAL')),
    rerun_delta          INTEGER NOT NULL DEFAULT 0 CHECK (rerun_delta >= 0),
    fix_delta            INTEGER NOT NULL DEFAULT 0 CHECK (fix_delta >= 0),
    push_delta           INTEGER NOT NULL DEFAULT 0 CHECK (push_delta >= 0),
    approved_by          TEXT    NOT NULL,
    reason               TEXT    NOT NULL,
    created_at_ms        INTEGER NOT NULL,
    consumed_at_ms       INTEGER,
    CHECK (rerun_delta + fix_delta + push_delta > 0),
    CHECK (kind <> 'PER_PUSH_APPROVAL'
        OR (rerun_delta = 0 AND fix_delta = 1 AND push_delta = 1))
);

CREATE TRIGGER ci_repair_budget_extension_immutable
BEFORE UPDATE OF id, ci_repair_episode_id, command_id, kind, rerun_delta,
        fix_delta, push_delta, approved_by, reason, created_at_ms
        ON ci_repair_budget_extension
BEGIN SELECT RAISE(ABORT, 'CI repair budget authorization is immutable'); END;

CREATE TABLE ci_repair_control_command (
    id                   TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id TEXT    NOT NULL REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    command_id           TEXT    NOT NULL UNIQUE,
    kind                 TEXT    NOT NULL CHECK (kind IN (
        'MANUAL_TAKEOVER', 'STOP_AUTOMATION')),
    actor                TEXT    NOT NULL,
    reason               TEXT    NOT NULL,
    created_at_ms        INTEGER NOT NULL,
    consumed_at_ms       INTEGER
);

CREATE TRIGGER ci_repair_control_command_immutable
BEFORE UPDATE OF id, ci_repair_episode_id, command_id, kind, actor, reason,
        created_at_ms ON ci_repair_control_command
BEGIN SELECT RAISE(ABORT, 'CI repair control command is immutable'); END;

DROP TRIGGER ci_repair_episode_identity_immutable;
CREATE TRIGGER ci_repair_episode_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, failed_ci_evaluation_id,
        subject_head_sha, subject_base_sha, classification, opened_at_ms
        ON ci_repair_episode
BEGIN SELECT RAISE(ABORT, 'CI repair subject identity is immutable'); END;

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

-- Exact-head invalidation is immutable evidence. V242/V233-owned records are
-- invalidated by their owner when integrated; this ledger supplies the
-- cross-slice proof without coupling V243 to those tables.
CREATE TABLE remote_head_evidence_invalidation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    accepted_snapshot_id        TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    old_head_sha                TEXT    NOT NULL,
    new_head_sha                TEXT    NOT NULL,
    old_base_sha                TEXT    NOT NULL,
    new_base_sha                TEXT    NOT NULL,
    evidence_kind               TEXT    NOT NULL CHECK (evidence_kind IN (
        'CI', 'BRAIN', 'REMOTE_REVIEW', 'READINESS', 'MERGE')),
    invalidated_at_ms           INTEGER NOT NULL,
    UNIQUE (accepted_snapshot_id, evidence_kind),
    CHECK (old_head_sha <> new_head_sha OR old_base_sha <> new_base_sha)
);

CREATE TRIGGER remote_head_evidence_invalidation_immutable
BEFORE UPDATE ON remote_head_evidence_invalidation
BEGIN SELECT RAISE(ABORT, 'Remote head invalidation evidence is immutable'); END;

CREATE TABLE branch_sync_dispatch_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    branch_sync_episode_id      TEXT    NOT NULL REFERENCES branch_sync_episode(id) ON DELETE CASCADE,
    branch_sync_effect_step_id  TEXT    NOT NULL UNIQUE
        REFERENCES branch_sync_effect_step(id) ON DELETE CASCADE,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'FETCH_COMPARE', 'MECHANICAL_REBASE', 'CONFLICT_REPAIR',
        'VALIDATE', 'BRAIN_REVIEW', 'FORCE_WITH_LEASE_PUSH')),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    stage_turn_id               TEXT UNIQUE REFERENCES stage_turn(id),
    task_turn_id                TEXT UNIQUE REFERENCES task_turn(id),
    expected_code_fingerprint   TEXT,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    target_base_sha             TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'INDETERMINATE', 'CANCELED', 'SUPERSEDED')),
    result_code_fingerprint     TEXT,
    result_head_sha             TEXT,
    result_evidence             TEXT,
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    CHECK ((kind = 'CONFLICT_REPAIR') = (stage_turn_id IS NOT NULL)),
    CHECK ((kind = 'BRAIN_REVIEW') = (task_turn_id IS NOT NULL)),
    CHECK (stage_turn_id IS NULL OR task_turn_id IS NULL),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE',
            'CANCELED', 'SUPERSEDED')) = (completed_at_ms IS NOT NULL))
);

CREATE UNIQUE INDEX idx_branch_sync_one_live_dispatch
    ON branch_sync_dispatch_operation(branch_sync_episode_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

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
    WHERE episode.id = NEW.branch_sync_episode_id
      AND step.id = NEW.branch_sync_effect_step_id
      AND step.kind = NEW.kind
      AND step.status = 'REQUESTED'
      AND episode.remote_development_stage_id = NEW.remote_development_stage_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.stage_generation = NEW.stage_generation
      AND episode.target_base_sha = NEW.target_base_sha
      AND episode.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED')
      AND remote.current_head_sha = episode.old_head_sha
      AND remote.current_base_sha = episode.observed_base_sha
      AND (
          (NEW.kind IN ('FETCH_COMPARE', 'MECHANICAL_REBASE')
              AND NEW.expected_head_sha = episode.old_head_sha
              AND NEW.expected_base_sha = episode.observed_base_sha)
          OR (NEW.kind NOT IN ('FETCH_COMPARE', 'MECHANICAL_REBASE')
              AND NEW.expected_base_sha = episode.target_base_sha
              AND NEW.expected_head_sha = COALESCE((
                  SELECT previous.result_head_sha
                  FROM branch_sync_dispatch_operation previous
                  JOIN branch_sync_effect_step previous_step
                    ON previous_step.id = previous.branch_sync_effect_step_id
                  WHERE previous.branch_sync_episode_id = episode.id
                    AND previous.status = 'SUCCEEDED'
                    AND previous.result_head_sha IS NOT NULL
                    AND previous_step.ordinal < step.ordinal
                  ORDER BY previous_step.ordinal DESC
                  LIMIT 1), episode.old_head_sha)))
      AND task.epoch = NEW.task_epoch
      AND task.lifecycle_state = 'ACTIVE'
      AND NEW.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch requires its exact requested step'); END;

CREATE TRIGGER branch_sync_dispatch_operation_identity_immutable
BEFORE UPDATE OF id, branch_sync_episode_id, branch_sync_effect_step_id,
        remote_development_stage_id, task_id, task_epoch, stage_generation,
        kind, operation_id, semantic_attempt, stage_turn_id, task_turn_id,
        expected_code_fingerprint, expected_head_sha, expected_base_sha,
        target_base_sha, requested_at_ms ON branch_sync_dispatch_operation
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch identity is immutable'); END;

CREATE TABLE branch_sync_delivery_receipt (
    branch_sync_dispatch_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES branch_sync_dispatch_operation(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    raw_outcome        TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest  TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance         TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    recorded_at_ms     INTEGER NOT NULL
);

CREATE TRIGGER branch_sync_delivery_receipt_immutable
BEFORE UPDATE ON branch_sync_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Branch sync delivery receipt is immutable'); END;

-- Explicit dispatch guards for every V243 callback. Agent Turns keep the
-- shared EXECUTE_* handlers; the typed operation row supplies their Remote
-- meaning without a prompt/source switch.
CREATE TRIGGER dispatch_ticket_ci_repair_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind IN (
        'RERUN_REMOTE_CI', 'VALIDATE_REMOTE_CI_REPAIR',
        'PUSH_REMOTE_CI_REPAIR')
  OR NEW.callback_route IN (
        'REMOTE_CI_RERUN_RESULT', 'REMOTE_CI_STAGE_TURN_RESULT',
        'REMOTE_CI_VALIDATION_RESULT', 'REMOTE_CI_BRAIN_RESULT',
        'REMOTE_CI_PUSH_RESULT')
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM ci_repair_operation operation
        WHERE operation.operation_id = NEW.operation_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.remote_development_stage_id = NEW.stage_id
          AND operation.stage_generation = NEW.stage_generation
          AND operation.semantic_attempt = NEW.attempt
          AND operation.expected_code_fingerprint IS NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND NEW.callback_route = CASE operation.kind
              WHEN 'RERUN' THEN 'REMOTE_CI_RERUN_RESULT'
              WHEN 'FIX_STAGE_TURN' THEN 'REMOTE_CI_STAGE_TURN_RESULT'
              WHEN 'VALIDATE' THEN 'REMOTE_CI_VALIDATION_RESULT'
              WHEN 'BRAIN_REVIEW' THEN 'REMOTE_CI_BRAIN_RESULT'
              WHEN 'PUSH_HEAD' THEN 'REMOTE_CI_PUSH_RESULT' END
          AND NEW.operation_kind = CASE operation.kind
              WHEN 'RERUN' THEN 'RERUN_REMOTE_CI'
              WHEN 'FIX_STAGE_TURN' THEN 'EXECUTE_STAGE_TURN'
              WHEN 'VALIDATE' THEN 'VALIDATE_REMOTE_CI_REPAIR'
              WHEN 'BRAIN_REVIEW' THEN 'EXECUTE_TASK_TURN'
              WHEN 'PUSH_HEAD' THEN 'PUSH_REMOTE_CI_REPAIR' END
          AND NEW.async_family = CASE operation.kind
              WHEN 'RERUN' THEN 'GITHUB_EFFECT'
              WHEN 'FIX_STAGE_TURN' THEN 'AGENT_TURN'
              WHEN 'VALIDATE' THEN 'VALIDATION'
              WHEN 'BRAIN_REVIEW' THEN 'AGENT_TURN'
              WHEN 'PUSH_HEAD' THEN 'GITHUB_EFFECT' END
          AND NEW.owner_kind = CASE operation.kind
              WHEN 'FIX_STAGE_TURN' THEN 'STAGE_TURN'
              WHEN 'BRAIN_REVIEW' THEN 'TASK_TURN'
              ELSE 'STAGE' END
          AND NEW.owner_id = COALESCE(
              operation.stage_turn_id, operation.task_turn_id,
              operation.remote_development_stage_id)
          AND NEW.lane_mask = CASE operation.kind
              WHEN 'RERUN' THEN 32
              WHEN 'FIX_STAGE_TURN' THEN NEW.lane_mask
              WHEN 'VALIDATE' THEN 4
              WHEN 'BRAIN_REVIEW' THEN NEW.lane_mask
              WHEN 'PUSH_HEAD' THEN 48
              END
          AND (operation.kind NOT IN ('FIX_STAGE_TURN', 'BRAIN_REVIEW')
              OR NEW.lane_mask IN (1, 2))
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = CASE operation.kind
              WHEN 'RERUN' THEN 0 ELSE 1 END
          AND NEW.writer_required = CASE operation.kind
              WHEN 'FIX_STAGE_TURN' THEN 1
              WHEN 'PUSH_HEAD' THEN 1 ELSE 0 END
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'CI repair DispatchTicket is not exact') END;
END;

CREATE TRIGGER dispatch_ticket_branch_sync_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind IN (
        'FETCH_COMPARE_REMOTE_BRANCH', 'REBASE_REMOTE_BRANCH',
        'VALIDATE_REMOTE_BRANCH_SYNC', 'FORCE_WITH_LEASE_REMOTE_BRANCH')
  OR NEW.callback_route IN (
        'BRANCH_SYNC_FETCH_RESULT', 'BRANCH_SYNC_REBASE_RESULT',
        'BRANCH_SYNC_CONFLICT_RESULT', 'BRANCH_SYNC_VALIDATION_RESULT',
        'BRANCH_SYNC_BRAIN_RESULT', 'BRANCH_SYNC_PUSH_RESULT')
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM branch_sync_dispatch_operation operation
        WHERE operation.operation_id = NEW.operation_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.remote_development_stage_id = NEW.stage_id
          AND operation.stage_generation = NEW.stage_generation
          AND operation.semantic_attempt = NEW.attempt
          AND operation.expected_code_fingerprint IS NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND NEW.callback_route = CASE operation.kind
              WHEN 'FETCH_COMPARE' THEN 'BRANCH_SYNC_FETCH_RESULT'
              WHEN 'MECHANICAL_REBASE' THEN 'BRANCH_SYNC_REBASE_RESULT'
              WHEN 'CONFLICT_REPAIR' THEN 'BRANCH_SYNC_CONFLICT_RESULT'
              WHEN 'VALIDATE' THEN 'BRANCH_SYNC_VALIDATION_RESULT'
              WHEN 'BRAIN_REVIEW' THEN 'BRANCH_SYNC_BRAIN_RESULT'
              WHEN 'FORCE_WITH_LEASE_PUSH' THEN 'BRANCH_SYNC_PUSH_RESULT' END
          AND NEW.operation_kind = CASE operation.kind
              WHEN 'FETCH_COMPARE' THEN 'FETCH_COMPARE_REMOTE_BRANCH'
              WHEN 'MECHANICAL_REBASE' THEN 'REBASE_REMOTE_BRANCH'
              WHEN 'CONFLICT_REPAIR' THEN 'EXECUTE_STAGE_TURN'
              WHEN 'VALIDATE' THEN 'VALIDATE_REMOTE_BRANCH_SYNC'
              WHEN 'BRAIN_REVIEW' THEN 'EXECUTE_TASK_TURN'
              WHEN 'FORCE_WITH_LEASE_PUSH' THEN 'FORCE_WITH_LEASE_REMOTE_BRANCH' END
          AND NEW.async_family = CASE operation.kind
              WHEN 'CONFLICT_REPAIR' THEN 'AGENT_TURN'
              WHEN 'BRAIN_REVIEW' THEN 'AGENT_TURN'
              WHEN 'VALIDATE' THEN 'VALIDATION'
              ELSE 'LOCAL_GIT' END
          AND NEW.owner_kind = CASE operation.kind
              WHEN 'CONFLICT_REPAIR' THEN 'STAGE_TURN'
              WHEN 'BRAIN_REVIEW' THEN 'TASK_TURN'
              ELSE 'STAGE' END
          AND NEW.owner_id = COALESCE(
              operation.stage_turn_id, operation.task_turn_id,
              operation.remote_development_stage_id)
          AND NEW.lane_mask = CASE operation.kind
              WHEN 'FETCH_COMPARE' THEN 16
              WHEN 'MECHANICAL_REBASE' THEN 16
              WHEN 'CONFLICT_REPAIR' THEN NEW.lane_mask
              WHEN 'VALIDATE' THEN 4
              WHEN 'BRAIN_REVIEW' THEN NEW.lane_mask
              WHEN 'FORCE_WITH_LEASE_PUSH' THEN 16 END
          AND (operation.kind NOT IN ('CONFLICT_REPAIR', 'BRAIN_REVIEW')
              OR NEW.lane_mask IN (1, 2))
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = CASE operation.kind
              WHEN 'VALIDATE' THEN 0
              WHEN 'BRAIN_REVIEW' THEN 0 ELSE 1 END
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Branch sync DispatchTicket is not exact') END;
END;

CREATE TRIGGER ci_repair_operation_status
BEFORE UPDATE OF status ON ci_repair_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'CI repair Operation status transition is invalid'); END;

CREATE TRIGGER ci_repair_operation_dispatched
BEFORE UPDATE OF status ON ci_repair_operation
WHEN NEW.status = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.operation_id = NEW.operation_id
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'CI repair Operation lacks its DispatchTicket'); END;

CREATE TRIGGER ci_repair_delivery_receipt_insert
BEFORE INSERT ON ci_repair_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_repair_operation operation
    JOIN dispatch_ticket ticket ON ticket.operation_id = operation.operation_id
    WHERE operation.id = NEW.ci_repair_operation_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome)
BEGIN SELECT RAISE(ABORT, 'CI repair delivery receipt is not exact'); END;

CREATE TRIGGER branch_sync_dispatch_operation_status
BEFORE UPDATE OF status ON branch_sync_dispatch_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'SUCCEEDED', 'FAILED', 'INDETERMINATE',
        'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'INDETERMINATE',
        'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch status transition is invalid'); END;

CREATE TRIGGER branch_sync_dispatch_operation_dispatched
BEFORE UPDATE OF status ON branch_sync_dispatch_operation
WHEN NEW.status = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.operation_id = NEW.operation_id
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch lacks its DispatchTicket'); END;

CREATE TRIGGER branch_sync_delivery_receipt_insert
BEFORE INSERT ON branch_sync_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM branch_sync_dispatch_operation operation
    JOIN dispatch_ticket ticket ON ticket.operation_id = operation.operation_id
    WHERE operation.id = NEW.branch_sync_dispatch_operation_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN (
          'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'SUPERSEDED')
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome)
BEGIN SELECT RAISE(ABORT, 'Branch sync delivery receipt is not exact'); END;

-- Exhaustion is a durable user gate. It may reopen only while consuming one
-- exact extension/per-push authorization in the same transaction.
DROP TRIGGER ci_repair_episode_terminal_immutable;
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
