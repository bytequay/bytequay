CREATE TABLE remote_repair_brain_failure_receipt_v309 (
    id                           TEXT    NOT NULL PRIMARY KEY,
    family                       TEXT    NOT NULL CHECK (family IN ('CI', 'BRANCH')),
    source_kind                  TEXT    NOT NULL CHECK (source_kind IN (
        'ORIGINAL', 'REPLACEMENT')),
    source_operation_row_id      TEXT    NOT NULL,
    ci_repair_episode_id         TEXT REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    branch_sync_episode_id       TEXT REFERENCES branch_sync_episode(id) ON DELETE CASCADE,
    branch_sync_effect_step_id   TEXT REFERENCES branch_sync_effect_step(id) ON DELETE CASCADE,
    base_repair_authorization_id TEXT REFERENCES ci_base_repair_authorization_v303(id),
    task_id                      TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                   INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id  TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation             INTEGER NOT NULL CHECK (stage_generation > 0),
    task_turn_id                 TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    operation_id                 TEXT    NOT NULL UNIQUE,
    semantic_attempt             INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt            INTEGER NOT NULL CHECK (execution_attempt > 0),
    expected_code_fingerprint    TEXT    NOT NULL,
    expected_head_sha            TEXT    NOT NULL,
    expected_base_sha            TEXT    NOT NULL,
    blocker_id                   TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    raw_outcome                  TEXT    NOT NULL CHECK (raw_outcome IN (
        'FAILED', 'CANCELED')),
    raw_result_digest            TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    error_message                TEXT    NOT NULL CHECK (length(trim(error_message)) > 0),
    cleared_task_version         INTEGER NOT NULL CHECK (cleared_task_version > 0),
    recorded_at_ms               INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    UNIQUE (family, source_kind, source_operation_row_id),
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_row_id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0),
    CHECK (execution_attempt >= semantic_attempt),
    CHECK ((family = 'CI' AND ci_repair_episode_id IS NOT NULL
            AND branch_sync_episode_id IS NULL
            AND branch_sync_effect_step_id IS NULL)
        OR (family = 'BRANCH' AND ci_repair_episode_id IS NULL
            AND branch_sync_episode_id IS NOT NULL
            AND branch_sync_effect_step_id IS NOT NULL))
);

CREATE TRIGGER remote_repair_brain_failure_receipt_immutable_v309
BEFORE UPDATE ON remote_repair_brain_failure_receipt_v309
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain failure receipt is immutable'); END;

CREATE TABLE remote_repair_brain_replacement_operation_v309 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    family                      TEXT    NOT NULL CHECK (family IN ('CI', 'BRANCH')),
    predecessor_failure_receipt_id TEXT NOT NULL UNIQUE
        REFERENCES remote_repair_brain_failure_receipt_v309(id),
    predecessor_turn_id         TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    predecessor_operation_id    TEXT    NOT NULL UNIQUE,
    ci_repair_episode_id        TEXT REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    branch_sync_episode_id      TEXT REFERENCES branch_sync_episode(id) ON DELETE CASCADE,
    branch_sync_effect_step_id  TEXT REFERENCES branch_sync_effect_step(id) ON DELETE CASCADE,
    base_repair_authorization_id TEXT REFERENCES ci_base_repair_authorization_v303(id),
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id TEXT    NOT NULL REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    task_turn_id                TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    operation_id                TEXT    NOT NULL UNIQUE,
    dispatch_ticket_id          TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt           INTEGER NOT NULL CHECK (execution_attempt > 1),
    expected_code_fingerprint   TEXT    NOT NULL,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    verdict                     TEXT CHECK (verdict IN ('APPROVED', 'CHANGES_REQUESTED')),
    finding_count               INTEGER CHECK (finding_count >= 0),
    result_summary              TEXT,
    result_code_fingerprint     TEXT,
    result_head_sha             TEXT,
    result_evidence             TEXT,
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    CHECK (length(trim(id)) > 0
        AND length(trim(predecessor_operation_id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0),
    CHECK (execution_attempt > semantic_attempt),
    CHECK ((family = 'CI' AND ci_repair_episode_id IS NOT NULL
            AND branch_sync_episode_id IS NULL
            AND branch_sync_effect_step_id IS NULL)
        OR (family = 'BRANCH' AND ci_repair_episode_id IS NULL
            AND branch_sync_episode_id IS NOT NULL
            AND branch_sync_effect_step_id IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (verdict IS NOT NULL)),
    CHECK (result_code_fingerprint IS NULL AND result_head_sha IS NULL),
    CHECK (verdict IS NOT 'APPROVED' OR finding_count = 0),
    CHECK (verdict IS NOT 'CHANGES_REQUESTED' OR finding_count > 0)
);

CREATE UNIQUE INDEX remote_repair_brain_one_live_replacement_v309
    ON remote_repair_brain_replacement_operation_v309(task_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TABLE remote_repair_brain_replacement_delivery_v309 (
    replacement_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES remote_repair_brain_replacement_operation_v309(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    raw_outcome        TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest  TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance         TEXT    NOT NULL CHECK (acceptance IN ('ACCEPTED', 'SUPERSEDED')),
    recorded_at_ms     INTEGER NOT NULL,
    CHECK (length(trim(operation_id)) > 0)
);

CREATE TRIGGER remote_repair_brain_replacement_delivery_immutable_v309
BEFORE UPDATE ON remote_repair_brain_replacement_delivery_v309
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain replacement delivery is immutable'); END;

CREATE TABLE remote_repair_brain_retry_command_v309 (
    id                       TEXT    NOT NULL PRIMARY KEY,
    family                   TEXT    NOT NULL CHECK (family IN ('CI', 'BRANCH')),
    task_id                  TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    stage_id                 TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    episode_id               TEXT    NOT NULL,
    failed_turn_id           TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    blocker_id               TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    failure_receipt_id       TEXT    NOT NULL UNIQUE
        REFERENCES remote_repair_brain_failure_receipt_v309(id),
    command_id               TEXT    NOT NULL,
    task_request_command_id  TEXT    NOT NULL UNIQUE,
    actor                    TEXT    NOT NULL CHECK (length(trim(actor)) > 0),
    reason                   TEXT    NOT NULL CHECK (length(trim(reason)) > 0),
    replacement_operation_row_id TEXT NOT NULL UNIQUE
        REFERENCES remote_repair_brain_replacement_operation_v309(id),
    replacement_turn_id      TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    replacement_operation_id TEXT    NOT NULL UNIQUE,
    replacement_ticket_id    TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    recorded_at_ms           INTEGER NOT NULL,
    UNIQUE (task_id, command_id),
    CHECK (length(trim(id)) > 0 AND length(trim(episode_id)) > 0
        AND length(trim(command_id)) > 0
        AND length(trim(task_request_command_id)) > 0
        AND length(trim(replacement_operation_id)) > 0)
);

CREATE TRIGGER remote_repair_brain_retry_command_immutable_v309
BEFORE UPDATE ON remote_repair_brain_retry_command_v309
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain retry command is immutable'); END;

CREATE VIEW remote_repair_brain_failure_source_v309 AS
SELECT 'CI' AS family, 'ORIGINAL' AS source_kind,
       operation.id AS source_operation_row_id,
       operation.ci_repair_episode_id, NULL AS branch_sync_episode_id,
       NULL AS branch_sync_effect_step_id,
       operation.base_repair_authorization_id,
       operation.task_id, operation.task_epoch,
       operation.remote_development_stage_id,
       operation.stage_generation, operation.task_turn_id,
       operation.operation_id, operation.semantic_attempt,
       operation.semantic_attempt AS execution_attempt,
       operation.expected_code_fingerprint, operation.expected_head_sha,
       operation.expected_base_sha, operation.status, operation.error_message
FROM ci_repair_operation operation
WHERE operation.kind = 'BRAIN_REVIEW'
UNION ALL
SELECT 'BRANCH', 'ORIGINAL', operation.id, NULL,
       operation.branch_sync_episode_id,
       operation.branch_sync_effect_step_id, NULL,
       operation.task_id, operation.task_epoch,
       operation.remote_development_stage_id,
       operation.stage_generation, operation.task_turn_id,
       operation.operation_id, operation.semantic_attempt,
       operation.semantic_attempt, operation.expected_code_fingerprint,
       operation.expected_head_sha, operation.expected_base_sha,
       operation.status, operation.error_message
FROM branch_sync_dispatch_operation operation
WHERE operation.kind = 'BRAIN_REVIEW'
UNION ALL
SELECT operation.family, 'REPLACEMENT', operation.id,
       operation.ci_repair_episode_id, operation.branch_sync_episode_id,
       operation.branch_sync_effect_step_id,
       operation.base_repair_authorization_id,
       operation.task_id, operation.task_epoch,
       operation.remote_development_stage_id,
       operation.stage_generation, operation.task_turn_id,
       operation.operation_id, operation.semantic_attempt,
       operation.execution_attempt, operation.expected_code_fingerprint,
       operation.expected_head_sha, operation.expected_base_sha,
       operation.status, operation.error_message
FROM remote_repair_brain_replacement_operation_v309 operation;

CREATE VIEW remote_repair_brain_delivery_source_v309 AS
SELECT 'CI' AS family, 'ORIGINAL' AS source_kind,
       delivery.ci_repair_operation_id AS source_operation_row_id,
       delivery.operation_id, delivery.raw_outcome,
       delivery.raw_result_digest, delivery.acceptance,
       delivery.recorded_at_ms
FROM ci_repair_delivery_receipt delivery
UNION ALL
SELECT 'BRANCH', 'ORIGINAL',
       delivery.branch_sync_dispatch_operation_id,
       delivery.operation_id, delivery.raw_outcome,
       delivery.raw_result_digest, delivery.acceptance,
       delivery.recorded_at_ms
FROM branch_sync_delivery_receipt delivery
UNION ALL
SELECT operation.family, 'REPLACEMENT',
       delivery.replacement_operation_id, delivery.operation_id,
       delivery.raw_outcome, delivery.raw_result_digest,
       delivery.acceptance, delivery.recorded_at_ms
FROM remote_repair_brain_replacement_delivery_v309 delivery
JOIN remote_repair_brain_replacement_operation_v309 operation
  ON operation.id = delivery.replacement_operation_id;

-- Every Task aggregate version is represented by exactly one applied typed
-- command receipt. Recovery may survive later Task transitions, but it may not
-- race a newer pending Brain result.
CREATE VIEW task_applied_protocol_snapshot_v309 AS
SELECT task_id, returned_version, returned_pending_operation_id
FROM task_command_receipt WHERE disposition = 'APPLIED'
UNION ALL
SELECT task_id, returned_version, returned_pending_operation_id
FROM task_brain_request_receipt WHERE disposition = 'APPLIED'
UNION ALL
SELECT task_id, returned_version, returned_pending_operation_id
FROM task_brain_budget_receipt WHERE disposition = 'APPLIED'
UNION ALL
SELECT task_id, returned_version, returned_pending_operation_id
FROM task_brain_protocol_failure_receipt_v300 WHERE disposition = 'APPLIED'
UNION ALL
SELECT task_id, returned_version, returned_pending_operation_id
FROM remote_task_brain_receipt WHERE disposition = 'APPLIED';

-- Retrying a failed provider execution reclaims the existing semantic Brain
-- step. Only the replacement TaskTurn execution ordinal advances.
DROP TRIGGER branch_sync_effect_step_claim;
CREATE TRIGGER branch_sync_effect_step_claim
BEFORE UPDATE OF status ON branch_sync_effect_step
WHEN NEW.status = 'CLAIMED'
  AND (NOT (
        (NEW.attempt_count = OLD.attempt_count + 1
          AND ((OLD.status = 'INDETERMINATE' AND NEW.claim_mode = 'PROBE')
            OR (OLD.status <> 'INDETERMINATE'
                AND NEW.claim_mode = 'EXECUTE')))
        OR (OLD.status = 'FAILED'
          AND NEW.attempt_count = OLD.attempt_count
          AND NEW.claim_mode = 'EXECUTE'
          AND NEW.evidence IS NULL AND NEW.last_error IS NULL
          AND NEW.completed_at_ms IS NULL
          AND EXISTS (
              SELECT 1
              FROM remote_repair_brain_replacement_operation_v309 replacement
              WHERE replacement.family = 'BRANCH'
                AND replacement.branch_sync_episode_id =
                    NEW.branch_sync_episode_id
                AND replacement.branch_sync_effect_step_id = NEW.id
                AND replacement.operation_id = NEW.claim_owner
                AND replacement.semantic_attempt = NEW.attempt_count
                AND replacement.execution_attempt >
                    replacement.semantic_attempt
                AND replacement.status = 'DISPATCHED'
                AND NEW.claimed_at_ms >= replacement.requested_at_ms)))
    OR EXISTS (
        SELECT 1 FROM branch_sync_effect_step previous
        WHERE previous.branch_sync_episode_id = NEW.branch_sync_episode_id
          AND previous.ordinal < NEW.ordinal
          AND previous.status NOT IN ('SUCCEEDED', 'SKIPPED')))
BEGIN SELECT RAISE(ABORT, 'Branch sync effect claim is unordered or unsafe'); END;

-- Replacement work follows the same REQUESTED -> ticket -> DISPATCHED order as
-- every other durable operation. The V308 family guards therefore recognize
-- the exact REQUESTED replacement row; the replacement transition below then
-- proves the complete ticket before it can become DISPATCHED.
DROP TRIGGER dispatch_ticket_ci_repair_insert;
CREATE TRIGGER dispatch_ticket_ci_repair_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind IN (
        'RERUN_REMOTE_CI', 'VALIDATE_REMOTE_CI_REPAIR',
        'REWRITE_VALIDATE_REMOTE_CI_BASE_REPAIR',
        'PUSH_REMOTE_CI_REPAIR')
  OR NEW.callback_route IN (
        'REMOTE_CI_RERUN_RESULT', 'REMOTE_CI_STAGE_TURN_RESULT',
        'REMOTE_CI_VALIDATION_RESULT',
        'REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT',
        'REMOTE_CI_BRAIN_RESULT', 'REMOTE_CI_PUSH_RESULT')
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
              WHEN 'VALIDATE' THEN CASE
                  WHEN operation.base_repair_authorization_id IS NULL
                      THEN 'REMOTE_CI_VALIDATION_RESULT'
                  ELSE 'REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT' END
              WHEN 'BRAIN_REVIEW' THEN 'REMOTE_CI_BRAIN_RESULT'
              WHEN 'PUSH_HEAD' THEN 'REMOTE_CI_PUSH_RESULT' END
          AND NEW.operation_kind = CASE operation.kind
              WHEN 'RERUN' THEN 'RERUN_REMOTE_CI'
              WHEN 'FIX_STAGE_TURN' THEN 'EXECUTE_STAGE_TURN'
              WHEN 'VALIDATE' THEN CASE
                  WHEN operation.base_repair_authorization_id IS NULL
                      THEN 'VALIDATE_REMOTE_CI_REPAIR'
                  ELSE 'REWRITE_VALIDATE_REMOTE_CI_BASE_REPAIR' END
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
              WHEN 'PUSH_HEAD' THEN 48 END
          AND (operation.kind NOT IN ('FIX_STAGE_TURN', 'BRAIN_REVIEW')
              OR NEW.lane_mask IN (1, 2))
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = CASE operation.kind
              WHEN 'RERUN' THEN 0 ELSE 1 END
          AND NEW.writer_required = CASE operation.kind
              WHEN 'FIX_STAGE_TURN' THEN 1
              WHEN 'VALIDATE' THEN CASE
                  WHEN operation.base_repair_authorization_id IS NULL
                      THEN 0 ELSE 1 END
              WHEN 'PUSH_HEAD' THEN 1
              ELSE 0 END
          AND NEW.status = 'REQUESTED')
      AND NOT EXISTS (
        SELECT 1
        FROM remote_repair_brain_replacement_operation_v309 replacement
        JOIN task_turn turn ON turn.id = replacement.task_turn_id
        JOIN dispatch_ticket predecessor
          ON predecessor.operation_id = replacement.predecessor_operation_id
         AND predecessor.owner_kind = 'TASK_TURN'
         AND predecessor.owner_id = replacement.predecessor_turn_id
        JOIN tasks task ON task.id = replacement.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE replacement.family = 'CI'
          AND replacement.status = 'REQUESTED'
          AND replacement.dispatch_ticket_id = NEW.id
          AND replacement.operation_id = NEW.operation_id
          AND replacement.task_id = NEW.task_id
          AND replacement.task_epoch = NEW.task_epoch
          AND replacement.remote_development_stage_id = NEW.stage_id
          AND replacement.stage_generation = NEW.stage_generation
          AND replacement.execution_attempt = NEW.attempt
          AND replacement.expected_code_fingerprint
                IS NEW.expected_code_fingerprint
          AND replacement.expected_head_sha = NEW.expected_head_sha
          AND replacement.expected_base_sha = NEW.expected_base_sha
          AND turn.operation_id = replacement.operation_id
          AND turn.status = 'REQUESTED'
          AND NEW.operation_kind = 'EXECUTE_TASK_TURN'
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'TASK_TURN'
          AND NEW.owner_id = replacement.task_turn_id
          AND NEW.callback_route = 'REMOTE_CI_BRAIN_RESULT'
          AND NEW.lane_mask = predecessor.lane_mask
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'CI repair DispatchTicket is not exact') END;
END;

DROP TRIGGER dispatch_ticket_branch_sync_insert;
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
      AND NOT EXISTS (
        SELECT 1
        FROM remote_repair_brain_replacement_operation_v309 replacement
        JOIN task_turn turn ON turn.id = replacement.task_turn_id
        JOIN dispatch_ticket predecessor
          ON predecessor.operation_id = replacement.predecessor_operation_id
         AND predecessor.owner_kind = 'TASK_TURN'
         AND predecessor.owner_id = replacement.predecessor_turn_id
        JOIN tasks task ON task.id = replacement.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE replacement.family = 'BRANCH'
          AND replacement.status = 'REQUESTED'
          AND replacement.dispatch_ticket_id = NEW.id
          AND replacement.operation_id = NEW.operation_id
          AND replacement.task_id = NEW.task_id
          AND replacement.task_epoch = NEW.task_epoch
          AND replacement.remote_development_stage_id = NEW.stage_id
          AND replacement.stage_generation = NEW.stage_generation
          AND replacement.execution_attempt = NEW.attempt
          AND replacement.expected_code_fingerprint
                IS NEW.expected_code_fingerprint
          AND replacement.expected_head_sha = NEW.expected_head_sha
          AND replacement.expected_base_sha = NEW.expected_base_sha
          AND turn.operation_id = replacement.operation_id
          AND turn.status = 'REQUESTED'
          AND NEW.operation_kind = 'EXECUTE_TASK_TURN'
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'TASK_TURN'
          AND NEW.owner_id = replacement.task_turn_id
          AND NEW.callback_route = 'BRANCH_SYNC_BRAIN_RESULT'
          AND NEW.lane_mask = predecessor.lane_mask
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Branch sync DispatchTicket is not exact') END;
END;

DROP VIEW remote_brain_operation_v248;
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
WHERE operation.kind = 'BRAIN_REVIEW'
UNION ALL
SELECT operation.id, operation.task_turn_id, operation.task_id,
       operation.task_epoch, operation.remote_development_stage_id,
       operation.stage_generation, operation.operation_id,
       operation.execution_attempt, operation.expected_code_fingerprint,
       operation.expected_head_sha, operation.expected_base_sha,
       operation.status, operation.verdict
FROM remote_repair_brain_replacement_operation_v309 operation;

DROP TRIGGER task_brain_protocol_failure_receipt_insert_v300;
CREATE TRIGGER task_brain_protocol_failure_receipt_insert_v300
BEFORE INSERT ON task_brain_protocol_failure_receipt_v300
WHEN NOT EXISTS (
    SELECT 1
    FROM task_transition transition
    JOIN tasks task ON task.id = NEW.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_blocker blocker ON blocker.id = NEW.proof_id
    JOIN task_turn delivered ON delivered.id = blocker.subject_revision
    LEFT JOIN task_turn_user_wait_continuation_v266 continuation
      ON continuation.successor_turn_id = delivered.id
    JOIN task_turn logical ON logical.id = COALESCE(
        continuation.logical_turn_id, delivered.id)
    JOIN brain_review_episode episode
      ON episode.task_id = task.id AND episode.task_turn_id = logical.id
    WHERE transition.task_id = task.id
      AND transition.command_id = NEW.command_id
      AND transition.cause = NEW.cause
      AND transition.actor = NEW.actor
      AND transition.aggregate_version = NEW.returned_version
      AND task.workflow_version = 'V2'
      AND task.thread_id = NEW.returned_trunk_id
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.subject_task_epoch
      AND task.aggregate_version = NEW.returned_version
      AND current.stage_id = NEW.subject_stage_id
      AND current.stage_generation = NEW.subject_stage_generation
      AND owner.kind = 'LOCAL_DEVELOPMENT'
      AND owner.checkpoint = 'BRAIN_REVIEW'
      AND owner.completed_at_ms IS NULL
      AND blocker.task_id = task.id
      AND blocker.stage_id IS NULL
      AND blocker.owner_kind = 'TASK'
      AND blocker.owner_id = task.id
      AND blocker.subject_revision = delivered.id
      AND blocker.blocker_type = 'OPERATION_FAILED'
      AND blocker.status = 'OPEN'
      AND episode.status = 'FAILED'
      AND episode.local_development_stage_id = owner.id
      AND episode.stage_generation = owner.generation
      AND logical.operation_id = NEW.subject_operation_id
      AND logical.attempt = NEW.subject_attempt
      AND logical.expected_code_fingerprint = NEW.subject_expected_code_fingerprint
      AND logical.expected_head_sha = NEW.subject_expected_head_sha
      AND logical.expected_base_sha = NEW.subject_expected_base_sha
      AND delivered.task_id = task.id
      AND delivered.task_epoch = task.epoch
      AND delivered.trigger_stage_id = owner.id
      AND delivered.trigger_stage_generation = owner.generation
      AND delivered.purpose = 'DEVELOPMENT_BRAIN_REVIEW'
      AND delivered.status = 'FAILED'
      AND delivered.expected_code_fingerprint = NEW.subject_expected_code_fingerprint
      AND delivered.expected_head_sha = NEW.subject_expected_head_sha
      AND delivered.expected_base_sha = NEW.subject_expected_base_sha
    UNION ALL
    SELECT 1
    FROM task_transition transition
    JOIN tasks task ON task.id = NEW.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_blocker blocker ON blocker.id = NEW.proof_id
    JOIN task_turn delivered ON delivered.id = blocker.subject_revision
    JOIN remote_brain_operation_v248 operation
      ON operation.task_turn_id = delivered.id
    WHERE transition.task_id = task.id
      AND transition.command_id = NEW.command_id
      AND transition.cause = NEW.cause
      AND transition.actor = NEW.actor
      AND transition.aggregate_version = NEW.returned_version
      AND task.workflow_version = 'V2'
      AND task.thread_id = NEW.returned_trunk_id
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.subject_task_epoch
      AND task.aggregate_version = NEW.returned_version
      AND current.stage_id = NEW.subject_stage_id
      AND current.stage_generation = NEW.subject_stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND blocker.task_id = task.id
      AND blocker.stage_id IS NULL
      AND blocker.owner_kind = 'TASK'
      AND blocker.owner_id = task.id
      AND blocker.subject_revision = delivered.id
      AND blocker.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
      AND blocker.status = 'OPEN'
      AND operation.task_id = task.id
      AND operation.task_epoch = NEW.subject_task_epoch
      AND operation.stage_id = NEW.subject_stage_id
      AND operation.stage_generation = NEW.subject_stage_generation
      AND operation.operation_id = NEW.subject_operation_id
      AND operation.attempt = NEW.subject_attempt
      AND operation.expected_code_fingerprint IS NEW.subject_expected_code_fingerprint
      AND operation.expected_head_sha IS NEW.subject_expected_head_sha
      AND operation.expected_base_sha IS NEW.subject_expected_base_sha
      AND operation.status IN ('FAILED', 'CANCELED')
      AND (EXISTS (
              SELECT 1 FROM ci_repair_delivery_receipt delivery
              WHERE delivery.ci_repair_operation_id = operation.proof_id
                AND delivery.operation_id = operation.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED'))
        OR EXISTS (
              SELECT 1 FROM branch_sync_delivery_receipt delivery
              WHERE delivery.branch_sync_dispatch_operation_id = operation.proof_id
                AND delivery.operation_id = operation.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED'))
        OR EXISTS (
              SELECT 1
              FROM remote_repair_brain_replacement_delivery_v309 delivery
              WHERE delivery.replacement_operation_id = operation.proof_id
                AND delivery.operation_id = operation.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED')))
      AND delivered.task_id = task.id
      AND delivered.task_epoch = task.epoch
      AND delivered.trigger_stage_id = owner.id
      AND delivered.trigger_stage_generation = owner.generation
      AND delivered.purpose IN ('REMOTE_CI_BRAIN_REVIEW', 'BRANCH_SYNC_BRAIN_REVIEW')
      AND delivered.status IN ('FAILED', 'CANCELED')
      AND delivered.expected_code_fingerprint IS NEW.subject_expected_code_fingerprint
      AND delivered.expected_head_sha IS NEW.subject_expected_head_sha
      AND delivered.expected_base_sha IS NEW.subject_expected_base_sha)
BEGIN SELECT RAISE(ABORT, 'Brain protocol failure Task receipt is not exact'); END;

CREATE UNIQUE INDEX task_blocker_one_open_remote_repair_brain_failure_v309
    ON task_blocker(task_id)
    WHERE owner_kind = 'TASK'
      AND blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
      AND status = 'OPEN';

CREATE TRIGGER remote_repair_brain_failure_receipt_insert_v309
BEFORE INSERT ON remote_repair_brain_failure_receipt_v309
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_repair_brain_failure_source_v309 source
    JOIN remote_repair_brain_delivery_source_v309 delivery
      ON delivery.family = source.family
     AND delivery.source_kind = source.source_kind
     AND delivery.source_operation_row_id = source.source_operation_row_id
     AND delivery.operation_id = source.operation_id
     AND delivery.acceptance = 'ACCEPTED'
    JOIN task_turn failed ON failed.id = source.task_turn_id
    JOIN dispatch_ticket ticket
      ON ticket.operation_id = source.operation_id
     AND ticket.owner_kind = 'TASK_TURN'
     AND ticket.owner_id = source.task_turn_id
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    JOIN task_brain_protocol_failure_receipt_v300 protocol
      ON protocol.task_id = source.task_id
     AND protocol.proof_id = blocker.id
     AND protocol.subject_task_epoch = source.task_epoch
     AND protocol.subject_stage_id = source.remote_development_stage_id
     AND protocol.subject_stage_generation = source.stage_generation
     AND protocol.subject_operation_id = source.operation_id
     AND protocol.subject_attempt = source.execution_attempt
     AND protocol.subject_expected_code_fingerprint =
         source.expected_code_fingerprint
     AND protocol.subject_expected_head_sha = source.expected_head_sha
     AND protocol.subject_expected_base_sha = source.expected_base_sha
     AND protocol.returned_pending_operation_id IS NULL
    JOIN tasks task ON task.id = source.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN remote_development_stage remote
      ON remote.stage_id = source.remote_development_stage_id
     AND remote.task_id = source.task_id
     AND remote.generation = source.stage_generation
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    LEFT JOIN ci_repair_episode ci
      ON source.family = 'CI' AND ci.id = source.ci_repair_episode_id
    LEFT JOIN branch_sync_episode branch
      ON source.family = 'BRANCH'
     AND branch.id = source.branch_sync_episode_id
    LEFT JOIN branch_sync_effect_step step
      ON source.family = 'BRANCH'
     AND step.id = source.branch_sync_effect_step_id
     AND step.branch_sync_episode_id = branch.id
    WHERE source.family = NEW.family
      AND source.source_kind = NEW.source_kind
      AND source.source_operation_row_id = NEW.source_operation_row_id
      AND source.ci_repair_episode_id IS NEW.ci_repair_episode_id
      AND source.branch_sync_episode_id IS NEW.branch_sync_episode_id
      AND source.branch_sync_effect_step_id IS
          NEW.branch_sync_effect_step_id
      AND source.base_repair_authorization_id IS
          NEW.base_repair_authorization_id
      AND source.task_id = NEW.task_id
      AND source.task_epoch = NEW.task_epoch
      AND source.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND source.stage_generation = NEW.stage_generation
      AND source.task_turn_id = NEW.task_turn_id
      AND source.operation_id = NEW.operation_id
      AND source.semantic_attempt = NEW.semantic_attempt
      AND source.execution_attempt = NEW.execution_attempt
      AND source.expected_code_fingerprint =
          NEW.expected_code_fingerprint
      AND source.expected_head_sha = NEW.expected_head_sha
      AND source.expected_base_sha = NEW.expected_base_sha
      AND source.status = NEW.raw_outcome
      AND source.error_message = NEW.error_message
      AND delivery.raw_outcome = NEW.raw_outcome
      AND delivery.raw_result_digest = NEW.raw_result_digest
      AND failed.task_id = source.task_id
      AND failed.task_epoch = source.task_epoch
      AND failed.trigger_stage_id = source.remote_development_stage_id
      AND failed.trigger_stage_generation = source.stage_generation
      AND failed.operation_id = source.operation_id
      AND failed.attempt = source.execution_attempt
      AND failed.expected_code_fingerprint = source.expected_code_fingerprint
      AND failed.expected_head_sha = source.expected_head_sha
      AND failed.expected_base_sha = source.expected_base_sha
      AND failed.status = NEW.raw_outcome
      AND failed.error_message = NEW.error_message
      AND ticket.task_id = source.task_id
      AND ticket.task_epoch = source.task_epoch
      AND ticket.stage_id = source.remote_development_stage_id
      AND ticket.stage_generation = source.stage_generation
      AND ticket.attempt = source.execution_attempt
      AND ticket.expected_code_fingerprint = source.expected_code_fingerprint
      AND ticket.expected_head_sha = source.expected_head_sha
      AND ticket.expected_base_sha = source.expected_base_sha
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND ticket.pending_result_task_epoch = source.task_epoch
      AND ticket.pending_result_stage_id = source.remote_development_stage_id
      AND ticket.pending_result_stage_generation = source.stage_generation
      AND ticket.pending_result_operation_id = source.operation_id
      AND ticket.pending_result_attempt = source.execution_attempt
      AND ticket.pending_result_expected_code_fingerprint =
          source.expected_code_fingerprint
      AND ticket.pending_result_expected_head_sha = source.expected_head_sha
      AND ticket.pending_result_expected_base_sha = source.expected_base_sha
      AND blocker.task_id = source.task_id
      AND blocker.stage_id IS NULL
      AND blocker.owner_kind = 'TASK'
      AND blocker.owner_id = source.task_id
      AND blocker.subject_revision = source.task_turn_id
      AND blocker.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
      AND blocker.status = 'OPEN'
      AND protocol.returned_trunk_id = task.thread_id
      AND protocol.returned_lifecycle = 'ACTIVE'
      AND protocol.returned_epoch = source.task_epoch
      AND protocol.returned_version = NEW.cleared_task_version
      AND protocol.returned_current_stage_id =
          source.remote_development_stage_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = source.task_epoch
      AND task.aggregate_version = NEW.cleared_task_version
      AND current.stage_id = source.remote_development_stage_id
      AND current.stage_generation = source.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = source.stage_generation
      AND owner.completed_at_ms IS NULL
      AND code.code_fingerprint = source.expected_code_fingerprint
      AND code.head_sha = source.expected_head_sha
      AND code.base_sha = source.expected_base_sha
      AND NEW.recorded_at_ms >= delivery.recorded_at_ms
      AND ((source.family = 'CI'
            AND ci.remote_development_stage_id = source.remote_development_stage_id
            AND ci.task_id = source.task_id
            AND ci.task_epoch = source.task_epoch
            AND ci.stage_generation = source.stage_generation
            AND ci.status = 'AWAITING_PUSH_CI'
            AND remote.current_head_sha = COALESCE(
                ci.last_pushed_head_sha, ci.subject_head_sha)
            AND remote.current_base_sha = ci.subject_base_sha)
        OR (source.family = 'BRANCH'
            AND branch.remote_development_stage_id =
                source.remote_development_stage_id
            AND branch.task_id = source.task_id
            AND branch.task_epoch = source.task_epoch
            AND branch.stage_generation = source.stage_generation
            AND branch.status = 'BRAIN_REVIEW'
            AND step.kind = 'BRAIN_REVIEW'
            AND step.status = 'FAILED'
            AND step.attempt_count = source.semantic_attempt
            AND remote.current_head_sha = branch.old_head_sha
            AND remote.current_base_sha = branch.observed_base_sha))
)
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain failure receipt is not exact'); END;

CREATE TRIGGER remote_repair_brain_replacement_operation_insert_v309
BEFORE INSERT ON remote_repair_brain_replacement_operation_v309
WHEN NEW.status <> 'REQUESTED'
  OR NEW.verdict IS NOT NULL OR NEW.finding_count IS NOT NULL
  OR NEW.result_summary IS NOT NULL
  OR NEW.result_code_fingerprint IS NOT NULL
  OR NEW.result_head_sha IS NOT NULL OR NEW.result_evidence IS NOT NULL
  OR NEW.completed_at_ms IS NOT NULL OR NEW.error_message IS NOT NULL
  OR NOT EXISTS (
      WITH predecessor AS (
          SELECT 'CI' AS family, 'ORIGINAL' AS source_kind,
                 operation.id AS row_id,
                 operation.ci_repair_episode_id AS episode_id,
                 NULL AS step_id, operation.base_repair_authorization_id,
                 operation.task_id, operation.task_epoch,
                 operation.remote_development_stage_id AS stage_id,
                 operation.stage_generation,
                 operation.semantic_attempt,
                 operation.semantic_attempt AS execution_attempt,
                 operation.task_turn_id, operation.operation_id,
                 operation.expected_code_fingerprint,
                 operation.expected_head_sha, operation.expected_base_sha,
                 operation.status
          FROM ci_repair_operation operation
          WHERE operation.kind = 'BRAIN_REVIEW'
          UNION ALL
          SELECT 'BRANCH', 'ORIGINAL', operation.id,
                 operation.branch_sync_episode_id,
                 operation.branch_sync_effect_step_id, NULL,
                 operation.task_id, operation.task_epoch,
                 operation.remote_development_stage_id,
                 operation.stage_generation, operation.semantic_attempt,
                 operation.semantic_attempt AS execution_attempt,
                 operation.task_turn_id, operation.operation_id,
                 operation.expected_code_fingerprint,
                 operation.expected_head_sha, operation.expected_base_sha,
                 operation.status
          FROM branch_sync_dispatch_operation operation
          WHERE operation.kind = 'BRAIN_REVIEW'
          UNION ALL
          SELECT operation.family, 'REPLACEMENT', operation.id,
                 COALESCE(operation.ci_repair_episode_id,
                          operation.branch_sync_episode_id),
                 operation.branch_sync_effect_step_id,
                 operation.base_repair_authorization_id,
                 operation.task_id, operation.task_epoch,
                 operation.remote_development_stage_id,
                 operation.stage_generation, operation.semantic_attempt,
                 operation.execution_attempt,
                 operation.task_turn_id, operation.operation_id,
                 operation.expected_code_fingerprint,
                 operation.expected_head_sha, operation.expected_base_sha,
                 operation.status
          FROM remote_repair_brain_replacement_operation_v309 operation)
      SELECT 1
      FROM predecessor previous
      JOIN task_turn failed ON failed.id = previous.task_turn_id
      JOIN remote_repair_brain_failure_receipt_v309 failure
        ON failure.id = NEW.predecessor_failure_receipt_id
       AND failure.family = previous.family
       AND failure.source_kind = previous.source_kind
       AND failure.source_operation_row_id = previous.row_id
       AND failure.task_turn_id = previous.task_turn_id
       AND failure.operation_id = previous.operation_id
      JOIN dispatch_ticket failed_ticket
        ON failed_ticket.operation_id = previous.operation_id
       AND failed_ticket.owner_kind = 'TASK_TURN'
       AND failed_ticket.owner_id = failed.id
      JOIN task_blocker blocker
        ON blocker.task_id = previous.task_id
       AND blocker.stage_id IS NULL
       AND blocker.owner_kind = 'TASK'
       AND blocker.owner_id = previous.task_id
       AND blocker.subject_revision = failed.id
       AND blocker.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
       AND blocker.status = 'OPEN'
      JOIN task_brain_protocol_failure_receipt_v300 failure_receipt
        ON failure_receipt.task_id = previous.task_id
       AND failure_receipt.proof_id = blocker.id
       AND failure_receipt.subject_operation_id = previous.operation_id
       AND failure_receipt.subject_attempt = previous.execution_attempt
       AND failure_receipt.subject_expected_code_fingerprint =
           previous.expected_code_fingerprint
       AND failure_receipt.subject_expected_head_sha =
           previous.expected_head_sha
       AND failure_receipt.subject_expected_base_sha =
           previous.expected_base_sha
       AND failure_receipt.returned_pending_operation_id IS NULL
      JOIN tasks task ON task.id = previous.task_id
      JOIN task_applied_protocol_snapshot_v309 current_task
        ON current_task.task_id = task.id
       AND current_task.returned_version = (
           SELECT MAX(latest.returned_version)
           FROM task_applied_protocol_snapshot_v309 latest
           WHERE latest.task_id = task.id
             AND latest.returned_version <= task.aggregate_version)
      JOIN threads trunk ON trunk.id = task.thread_id
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN remote_development_stage remote
        ON remote.stage_id = previous.stage_id
       AND remote.task_id = previous.task_id
       AND remote.generation = previous.stage_generation
      JOIN task_current_code_subject_v230 code ON code.task_id = task.id
      JOIN task_turn replacement ON replacement.id = NEW.task_turn_id
      LEFT JOIN ci_repair_episode ci
        ON previous.family = 'CI' AND ci.id = previous.episode_id
      LEFT JOIN branch_sync_episode branch
        ON previous.family = 'BRANCH' AND branch.id = previous.episode_id
      LEFT JOIN branch_sync_effect_step step
        ON previous.family = 'BRANCH' AND step.id = previous.step_id
      WHERE previous.task_turn_id = NEW.predecessor_turn_id
        AND previous.operation_id = NEW.predecessor_operation_id
        AND previous.status IN ('FAILED', 'CANCELED')
        AND failure.ci_repair_episode_id IS
            CASE WHEN previous.family = 'CI' THEN previous.episode_id END
        AND failure.branch_sync_episode_id IS
            CASE WHEN previous.family = 'BRANCH' THEN previous.episode_id END
        AND failure.branch_sync_effect_step_id IS previous.step_id
        AND failure.base_repair_authorization_id IS
            previous.base_repair_authorization_id
        AND failure.task_id = previous.task_id
        AND failure.task_epoch = previous.task_epoch
        AND failure.remote_development_stage_id = previous.stage_id
        AND failure.stage_generation = previous.stage_generation
        AND failure.semantic_attempt = previous.semantic_attempt
        AND failure.execution_attempt = previous.execution_attempt
        AND failure.expected_code_fingerprint =
            previous.expected_code_fingerprint
        AND failure.expected_head_sha = previous.expected_head_sha
        AND failure.expected_base_sha = previous.expected_base_sha
        AND failure.blocker_id = blocker.id
        AND failure.raw_outcome = previous.status
        AND failed.status IN ('FAILED', 'CANCELED')
        AND failed.task_id = previous.task_id
        AND failed.task_epoch = previous.task_epoch
        AND failed.trigger_stage_id = previous.stage_id
        AND failed.trigger_stage_generation = previous.stage_generation
        AND failed.operation_id = previous.operation_id
        AND failed.attempt = previous.execution_attempt
        AND failed.expected_code_fingerprint =
            previous.expected_code_fingerprint
        AND failed.expected_head_sha = previous.expected_head_sha
        AND failed.expected_base_sha = previous.expected_base_sha
        AND failed_ticket.task_id = previous.task_id
        AND failed_ticket.task_epoch = previous.task_epoch
        AND failed_ticket.stage_id = previous.stage_id
        AND failed_ticket.stage_generation = previous.stage_generation
        AND failed_ticket.attempt = previous.execution_attempt
        AND failed_ticket.expected_code_fingerprint =
            previous.expected_code_fingerprint
        AND failed_ticket.expected_head_sha = previous.expected_head_sha
        AND failed_ticket.expected_base_sha = previous.expected_base_sha
        AND failure_receipt.subject_task_epoch = previous.task_epoch
        AND failure_receipt.subject_stage_id = previous.stage_id
        AND failure_receipt.subject_stage_generation = previous.stage_generation
        AND failure_receipt.returned_trunk_id = task.thread_id
        AND failure_receipt.returned_lifecycle = 'ACTIVE'
        AND failure_receipt.returned_epoch = task.epoch
        AND failure_receipt.returned_version = failure.cleared_task_version
        AND failure.cleared_task_version <= task.aggregate_version
        AND failure_receipt.returned_current_stage_id = current.stage_id
        AND current_task.returned_pending_operation_id IS NULL
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'ACTIVE'
        AND task.epoch = previous.task_epoch
        AND current.stage_id = previous.stage_id
        AND current.stage_generation = previous.stage_generation
        AND owner.kind = 'REMOTE_DEVELOPMENT'
        AND owner.generation = previous.stage_generation
        AND owner.completed_at_ms IS NULL
        AND code.code_fingerprint = previous.expected_code_fingerprint
        AND code.head_sha = previous.expected_head_sha
        AND code.base_sha = previous.expected_base_sha
        AND NEW.family = previous.family
        AND NEW.task_id = previous.task_id
        AND NEW.task_epoch = previous.task_epoch
        AND NEW.remote_development_stage_id = previous.stage_id
        AND NEW.stage_generation = previous.stage_generation
        AND NEW.semantic_attempt = previous.semantic_attempt
        AND NEW.execution_attempt = previous.execution_attempt + 1
        AND NEW.expected_code_fingerprint =
            previous.expected_code_fingerprint
        AND NEW.expected_head_sha = previous.expected_head_sha
        AND NEW.expected_base_sha = previous.expected_base_sha
        AND NEW.base_repair_authorization_id IS
            previous.base_repair_authorization_id
        AND replacement.task_id = NEW.task_id
        AND replacement.task_epoch = NEW.task_epoch
        AND replacement.trigger_stage_id = NEW.remote_development_stage_id
        AND replacement.trigger_stage_generation = NEW.stage_generation
        AND replacement.operation_id = NEW.operation_id
        AND replacement.attempt = NEW.execution_attempt
        AND replacement.expected_code_fingerprint =
            NEW.expected_code_fingerprint
        AND replacement.expected_head_sha = NEW.expected_head_sha
        AND replacement.expected_base_sha = NEW.expected_base_sha
        AND replacement.status = 'REQUESTED'
        AND replacement.delivery_lane = failed.delivery_lane
        AND replacement.purpose = CASE NEW.family
            WHEN 'CI' THEN 'REMOTE_CI_BRAIN_REVIEW'
            ELSE 'BRANCH_SYNC_BRAIN_REVIEW' END
        AND json_valid(replacement.launch_input)
        AND json_type(replacement.launch_input, '$.resumeSessionId') IS NULL
        AND json_type(replacement.launch_input, '$.fallbackPrompt') IS NULL
        AND json_type(replacement.launch_input,
            '$.priorCumulativeInputTokens') IS NULL
        AND json_type(replacement.launch_input,
            '$.priorCumulativeOutputTokens') IS NULL
        AND instr(json_extract(replacement.launch_input, '$.prompt'),
            COALESCE(json_extract(failed.launch_input, '$.fallbackPrompt'),
                     json_extract(failed.launch_input, '$.prompt'))) = 1
        AND instr(json_extract(replacement.launch_input, '$.prompt'),
            'Retry instruction:') > 0
        AND json_extract(replacement.launch_input, '$.transport') IS
            json_extract(failed.launch_input, '$.transport')
        AND json_extract(replacement.launch_input, '$.provider') IS
            json_extract(failed.launch_input, '$.provider')
        AND json_extract(replacement.launch_input, '$.credentialAccount') IS
            json_extract(failed.launch_input, '$.credentialAccount')
        AND json_extract(replacement.launch_input, '$.model') IS
            json_extract(failed.launch_input, '$.model')
        AND json_extract(replacement.launch_input, '$.reasoningEffort') IS
            json_extract(failed.launch_input, '$.reasoningEffort')
        AND json_extract(replacement.launch_input, '$.workingDirectory') =
            json_extract(failed.launch_input, '$.workingDirectory')
        AND json_extract(replacement.launch_input, '$.systemPrompt') =
            json_extract(failed.launch_input, '$.systemPrompt')
        AND json_extract(replacement.launch_input,
            '$.toolEndpoint.ownerKind') = 'TASK_TURN'
        AND json_extract(replacement.launch_input,
            '$.toolEndpoint.ownerId') = NEW.task_turn_id
        AND json_extract(replacement.launch_input,
            '$.toolEndpoint.operationId') = NEW.operation_id
        AND json_extract(replacement.launch_input,
            '$.toolEndpoint.profile') = 'TASK_BRAIN_READ_ONLY'
        AND ((NEW.family = 'CI'
              AND NEW.ci_repair_episode_id = previous.episode_id
              AND ci.status = 'AWAITING_PUSH_CI'
              AND remote.current_head_sha = COALESCE(
                  ci.last_pushed_head_sha, ci.subject_head_sha)
              AND remote.current_base_sha = ci.subject_base_sha
              AND (NEW.base_repair_authorization_id IS NULL OR EXISTS (
                  SELECT 1 FROM ci_base_repair_authorization_v303 authorization
                  WHERE authorization.id = NEW.base_repair_authorization_id
                    AND authorization.ci_repair_episode_id = ci.id
                    AND authorization.status = 'CLAIMED')))
          OR (NEW.family = 'BRANCH'
              AND NEW.branch_sync_episode_id = previous.episode_id
              AND NEW.branch_sync_effect_step_id = previous.step_id
              AND NEW.base_repair_authorization_id IS NULL
              AND branch.status = 'BRAIN_REVIEW'
              AND step.branch_sync_episode_id = branch.id
              AND step.kind = 'BRAIN_REVIEW'
              AND step.status = 'FAILED'
              AND step.attempt_count = previous.semantic_attempt
              AND remote.current_head_sha = branch.old_head_sha
              AND remote.current_base_sha = branch.observed_base_sha))
        AND ((previous.family = 'CI' AND EXISTS (
              SELECT 1 FROM ci_repair_delivery_receipt delivery
              WHERE delivery.ci_repair_operation_id = previous.row_id
                AND delivery.operation_id = previous.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED')))
          OR (previous.family = 'BRANCH' AND EXISTS (
              SELECT 1 FROM branch_sync_delivery_receipt delivery
              WHERE delivery.branch_sync_dispatch_operation_id = previous.row_id
                AND delivery.operation_id = previous.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED')))
          OR EXISTS (
              SELECT 1 FROM remote_repair_brain_replacement_delivery_v309 delivery
              WHERE delivery.replacement_operation_id = previous.row_id
                AND delivery.operation_id = previous.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED'))))
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain replacement is not exact'); END;

CREATE TRIGGER remote_repair_brain_replacement_operation_transition_v309
BEFORE UPDATE ON remote_repair_brain_replacement_operation_v309
WHEN NEW.id IS NOT OLD.id OR NEW.family IS NOT OLD.family
  OR NEW.predecessor_failure_receipt_id IS NOT
      OLD.predecessor_failure_receipt_id
  OR NEW.predecessor_turn_id IS NOT OLD.predecessor_turn_id
  OR NEW.predecessor_operation_id IS NOT OLD.predecessor_operation_id
  OR NEW.ci_repair_episode_id IS NOT OLD.ci_repair_episode_id
  OR NEW.branch_sync_episode_id IS NOT OLD.branch_sync_episode_id
  OR NEW.branch_sync_effect_step_id IS NOT OLD.branch_sync_effect_step_id
  OR NEW.base_repair_authorization_id IS NOT OLD.base_repair_authorization_id
  OR NEW.task_id IS NOT OLD.task_id OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.task_turn_id IS NOT OLD.task_turn_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.dispatch_ticket_id IS NOT OLD.dispatch_ticket_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.execution_attempt IS NOT OLD.execution_attempt
  OR NEW.expected_code_fingerprint IS NOT OLD.expected_code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
  OR NOT (
      (OLD.status = 'REQUESTED' AND NEW.status = 'DISPATCHED'
        AND NEW.verdict IS NULL AND NEW.finding_count IS NULL
        AND NEW.result_summary IS NULL
        AND NEW.result_code_fingerprint IS NULL
        AND NEW.result_head_sha IS NULL AND NEW.result_evidence IS NULL
        AND NEW.completed_at_ms IS NULL AND NEW.error_message IS NULL
        AND EXISTS (
            SELECT 1
            FROM dispatch_ticket ticket
            JOIN task_turn replacement ON replacement.id = OLD.task_turn_id
            JOIN dispatch_ticket predecessor
              ON predecessor.operation_id = OLD.predecessor_operation_id
             AND predecessor.owner_kind = 'TASK_TURN'
             AND predecessor.owner_id = OLD.predecessor_turn_id
            JOIN tasks task ON task.id = OLD.task_id
            JOIN threads trunk ON trunk.id = task.thread_id
            WHERE ticket.id = OLD.dispatch_ticket_id
              AND ticket.operation_id = OLD.operation_id
              AND ticket.operation_kind = 'EXECUTE_TASK_TURN'
              AND ticket.async_family = 'AGENT_TURN'
              AND ticket.owner_kind = 'TASK_TURN'
              AND ticket.owner_id = OLD.task_turn_id
              AND ticket.callback_route = CASE OLD.family
                  WHEN 'CI' THEN 'REMOTE_CI_BRAIN_RESULT'
                  ELSE 'BRANCH_SYNC_BRAIN_RESULT' END
              AND ticket.lane_mask = predecessor.lane_mask
              AND ticket.trunk_control = 0
              AND ticket.exclusive_task = 1
              AND ticket.writer_required = 0
              AND ticket.workspace_id = trunk.workspace_id
              AND ticket.trunk_id = task.thread_id
              AND ticket.task_id = OLD.task_id
              AND ticket.task_epoch = OLD.task_epoch
              AND ticket.stage_id = OLD.remote_development_stage_id
              AND ticket.stage_generation = OLD.stage_generation
              AND ticket.attempt = OLD.execution_attempt
              AND ticket.expected_code_fingerprint
                    = OLD.expected_code_fingerprint
              AND ticket.expected_head_sha = OLD.expected_head_sha
              AND ticket.expected_base_sha = OLD.expected_base_sha
              AND ticket.status = 'REQUESTED'
              AND replacement.operation_id = OLD.operation_id
              AND replacement.attempt = OLD.execution_attempt
              AND replacement.status = 'REQUESTED'))
      OR (OLD.status = 'DISPATCHED'
        AND NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
        AND NEW.completed_at_ms IS NOT NULL
        AND NEW.result_code_fingerprint IS NULL
        AND NEW.result_head_sha IS NULL
        AND ((NEW.status = 'SUCCEEDED'
              AND NEW.verdict IS NOT NULL AND NEW.finding_count IS NOT NULL
              AND NEW.result_summary IS NOT NULL
              AND NEW.result_evidence IS NOT NULL
              AND NEW.result_summary IS NEW.result_evidence
              AND NEW.error_message IS NULL
              AND ((NEW.verdict = 'APPROVED' AND NEW.finding_count = 0)
                OR (NEW.verdict = 'CHANGES_REQUESTED'
                    AND NEW.finding_count > 0)))
          OR (NEW.status <> 'SUCCEEDED'
              AND NEW.verdict IS NULL AND NEW.finding_count IS NULL
              AND NEW.result_summary IS NULL AND NEW.result_evidence IS NULL
              AND NEW.error_message IS NOT NULL))))
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain replacement transition is invalid'); END;

CREATE TRIGGER remote_repair_brain_replacement_delivery_insert_v309
BEFORE INSERT ON remote_repair_brain_replacement_delivery_v309
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_repair_brain_replacement_operation_v309 operation
    JOIN task_turn turn ON turn.id = operation.task_turn_id
    JOIN dispatch_ticket ticket ON ticket.id = operation.dispatch_ticket_id
    WHERE operation.id = NEW.replacement_operation_id
      AND operation.operation_id = NEW.operation_id
      AND turn.operation_id = operation.operation_id
      AND turn.status = operation.status
      AND ticket.operation_id = operation.operation_id
      AND ticket.owner_kind = 'TASK_TURN'
      AND ticket.owner_id = turn.id
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND ((NEW.acceptance = 'ACCEPTED'
            AND NEW.raw_outcome IN ('SUCCEEDED', 'FAILED', 'CANCELED')
            AND operation.status = NEW.raw_outcome)
        OR (NEW.acceptance = 'SUPERSEDED'
            AND operation.status = 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain replacement delivery is not exact'); END;

CREATE TRIGGER remote_repair_brain_retry_command_insert_v309
BEFORE INSERT ON remote_repair_brain_retry_command_v309
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_repair_brain_replacement_operation_v309 replacement
    JOIN task_turn successor ON successor.id = replacement.task_turn_id
    JOIN dispatch_ticket ticket ON ticket.id = replacement.dispatch_ticket_id
    JOIN task_turn failed ON failed.id = replacement.predecessor_turn_id
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    JOIN remote_repair_brain_failure_receipt_v309 failure
      ON failure.id = NEW.failure_receipt_id
     AND failure.id = replacement.predecessor_failure_receipt_id
     AND failure.blocker_id = blocker.id
     AND failure.task_turn_id = failed.id
     AND failure.operation_id = replacement.predecessor_operation_id
    JOIN task_brain_protocol_failure_receipt_v300 failure_receipt
      ON failure_receipt.task_id = replacement.task_id
     AND failure_receipt.proof_id = blocker.id
     AND failure_receipt.subject_operation_id =
         replacement.predecessor_operation_id
     AND failure_receipt.subject_attempt = failed.attempt
     AND failure_receipt.subject_expected_code_fingerprint =
         replacement.expected_code_fingerprint
     AND failure_receipt.subject_expected_head_sha =
         replacement.expected_head_sha
     AND failure_receipt.subject_expected_base_sha =
         replacement.expected_base_sha
     AND failure_receipt.returned_pending_operation_id IS NULL
    JOIN remote_task_brain_receipt request
      ON request.task_id = replacement.task_id
     AND request.command_id = NEW.task_request_command_id
     AND request.cause = 'REQUEST_BRAIN_REVIEW'
     AND request.disposition = 'APPLIED'
     AND request.proof_id = replacement.id
     AND request.subject_task_epoch = replacement.task_epoch
     AND request.subject_stage_id = replacement.remote_development_stage_id
     AND request.subject_stage_generation = replacement.stage_generation
     AND request.subject_operation_id = replacement.operation_id
     AND request.subject_attempt = replacement.execution_attempt
     AND request.subject_expected_code_fingerprint =
         replacement.expected_code_fingerprint
     AND request.subject_expected_head_sha = replacement.expected_head_sha
     AND request.subject_expected_base_sha = replacement.expected_base_sha
     AND request.returned_pending_operation_id = replacement.operation_id
     AND request.returned_pending_attempt = replacement.execution_attempt
    JOIN tasks task ON task.id = replacement.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN remote_development_stage remote
      ON remote.stage_id = replacement.remote_development_stage_id
     AND remote.task_id = replacement.task_id
     AND remote.generation = replacement.stage_generation
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    LEFT JOIN ci_repair_episode ci
      ON replacement.family = 'CI'
     AND ci.id = replacement.ci_repair_episode_id
    LEFT JOIN branch_sync_episode branch
      ON replacement.family = 'BRANCH'
     AND branch.id = replacement.branch_sync_episode_id
    LEFT JOIN branch_sync_effect_step step
      ON replacement.family = 'BRANCH'
     AND step.id = replacement.branch_sync_effect_step_id
    WHERE replacement.id = NEW.replacement_operation_row_id
      AND replacement.family = NEW.family
      AND replacement.task_id = NEW.task_id
      AND replacement.remote_development_stage_id = NEW.stage_id
      AND COALESCE(replacement.ci_repair_episode_id,
                   replacement.branch_sync_episode_id) = NEW.episode_id
      AND replacement.predecessor_turn_id = NEW.failed_turn_id
      AND replacement.predecessor_failure_receipt_id = NEW.failure_receipt_id
      AND replacement.task_turn_id = NEW.replacement_turn_id
      AND replacement.operation_id = NEW.replacement_operation_id
      AND replacement.dispatch_ticket_id = NEW.replacement_ticket_id
      AND replacement.status = 'DISPATCHED'
      AND successor.status = 'REQUESTED'
      AND successor.operation_id = replacement.operation_id
      AND successor.attempt = replacement.execution_attempt
      AND ticket.status = 'REQUESTED'
      AND ticket.operation_id = replacement.operation_id
      AND ticket.owner_kind = 'TASK_TURN'
      AND ticket.owner_id = replacement.task_turn_id
      AND ticket.attempt = replacement.execution_attempt
      AND failed.operation_id = replacement.predecessor_operation_id
      AND failed.status IN ('FAILED', 'CANCELED')
      AND failure.family = replacement.family
      AND failure.task_id = replacement.task_id
      AND failure.task_epoch = replacement.task_epoch
      AND failure.remote_development_stage_id =
          replacement.remote_development_stage_id
      AND failure.stage_generation = replacement.stage_generation
      AND failure.execution_attempt = failed.attempt
      AND failure.expected_code_fingerprint =
          replacement.expected_code_fingerprint
      AND failure.expected_head_sha = replacement.expected_head_sha
      AND failure.expected_base_sha = replacement.expected_base_sha
      AND failure.raw_outcome = failed.status
      AND blocker.task_id = replacement.task_id
      AND blocker.stage_id IS NULL
      AND blocker.owner_kind = 'TASK'
      AND blocker.owner_id = replacement.task_id
      AND blocker.subject_revision = failed.id
      AND blocker.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
      AND blocker.status = 'OPEN'
      AND failure_receipt.returned_trunk_id = task.thread_id
      AND failure_receipt.returned_lifecycle = 'ACTIVE'
      AND failure_receipt.returned_epoch = replacement.task_epoch
      AND failure_receipt.returned_version = failure.cleared_task_version
      AND failure.cleared_task_version <= request.expected_task_version
      AND failure_receipt.returned_current_stage_id =
          replacement.remote_development_stage_id
      AND request.actor = NEW.actor
      AND request.expected_task_epoch = replacement.task_epoch
      AND request.returned_epoch = replacement.task_epoch
      AND request.returned_version = request.expected_task_version + 1
      AND request.returned_version = task.aggregate_version
      AND request.returned_current_stage_id =
          replacement.remote_development_stage_id
      AND request.returned_pending_task_epoch = replacement.task_epoch
      AND request.returned_pending_stage_id =
          replacement.remote_development_stage_id
      AND request.returned_pending_stage_generation =
          replacement.stage_generation
      AND request.returned_pending_code_fingerprint =
          replacement.expected_code_fingerprint
      AND request.returned_pending_head_sha = replacement.expected_head_sha
      AND request.returned_pending_base_sha = replacement.expected_base_sha
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = replacement.task_epoch
      AND current.stage_id = replacement.remote_development_stage_id
      AND current.stage_generation = replacement.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = replacement.stage_generation
      AND owner.completed_at_ms IS NULL
      AND code.code_fingerprint = replacement.expected_code_fingerprint
      AND code.head_sha = replacement.expected_head_sha
      AND code.base_sha = replacement.expected_base_sha
      AND NEW.recorded_at_ms >= replacement.requested_at_ms
      AND ((replacement.family = 'CI'
            AND ci.status = 'AWAITING_PUSH_CI'
            AND remote.current_head_sha = COALESCE(
                ci.last_pushed_head_sha, ci.subject_head_sha)
            AND remote.current_base_sha = ci.subject_base_sha)
        OR (replacement.family = 'BRANCH'
            AND branch.status = 'BRAIN_REVIEW'
            AND step.branch_sync_episode_id = branch.id
            AND step.kind = 'BRAIN_REVIEW'
            AND step.status = 'CLAIMED'
            AND step.claim_owner = replacement.operation_id
            AND step.attempt_count = replacement.semantic_attempt
            AND remote.current_head_sha = branch.old_head_sha
            AND remote.current_base_sha = branch.observed_base_sha)))
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain retry command is not exact'); END;

CREATE TRIGGER remote_repair_brain_failure_blocker_resolution_v309
BEFORE UPDATE OF status ON task_blocker
WHEN OLD.owner_kind = 'TASK'
  AND OLD.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
  AND OLD.status = 'OPEN' AND NEW.status = 'RESOLVED'
  AND NOT EXISTS (
      SELECT 1 FROM remote_repair_brain_retry_command_v309 retry
      WHERE retry.blocker_id = OLD.id
        AND retry.task_id = OLD.task_id
        AND retry.failed_turn_id = OLD.subject_revision)
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain blocker lacks its replacement'); END;

-- TaskOutcome summaries use the same typed CLI/API Brain lanes as every other
-- TaskTurn.  The pre-squash Cleanup admission guard still named the retired
-- generic agent lane, which made the current summary runtime impossible to
-- dispatch after terminal cleanup.
DROP TRIGGER cleanup_dispatch_admission;
CREATE TRIGGER cleanup_dispatch_admission
BEFORE INSERT ON dispatch_ticket
WHEN NEW.task_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM tasks task
      WHERE task.id = NEW.task_id
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state IN (
            'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
  AND NOT (
      EXISTS (
          SELECT 1 FROM tasks task
          JOIN task_current_stage current ON current.task_id = task.id
          JOIN stage owner ON owner.id = current.stage_id
          JOIN cleanup_stage cleanup ON cleanup.stage_id = owner.id
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state = 'CLEANING'
            AND task.epoch = NEW.task_epoch
            AND owner.kind = 'CLEANUP'
            AND owner.checkpoint = 'WAITING_QUIESCENCE'
            AND owner.id = NEW.stage_id
            AND owner.generation = NEW.stage_generation
            AND NEW.operation_kind = 'RUN_CLEANUP_OPERATION'
            AND NEW.async_family = 'CLEANUP'
            AND NEW.owner_kind = 'STAGE'
            AND NEW.owner_id = owner.id
            AND NEW.callback_route = 'CLEANUP_OPERATION_RESULT'
            AND NEW.lane_mask = 256
            AND NEW.exclusive_task = 1
            AND NEW.writer_required = 1
            AND NOT EXISTS (
                SELECT 1 FROM dispatch_ticket existing
                WHERE existing.task_id = NEW.task_id
                  AND existing.task_epoch = NEW.task_epoch
                  AND existing.async_family = 'CLEANUP'))
      OR EXISTS (
          SELECT 1 FROM tasks task
          JOIN task_outcome outcome ON outcome.task_id = task.id
          JOIN task_turn turn ON turn.task_id = task.id
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            AND task.epoch = NEW.task_epoch
            AND outcome.summary_state = 'FALLBACK'
            AND turn.id = NEW.owner_id
            AND turn.purpose = 'TASK_COMPLETION_SUMMARY'
            AND turn.operation_id = NEW.operation_id
            AND turn.attempt = NEW.attempt
            AND NEW.operation_kind = 'GENERATE_TASK_OUTCOME_SUMMARY'
            AND NEW.async_family = 'AGENT_TURN'
            AND NEW.owner_kind = 'TASK_TURN'
            AND NEW.callback_route = 'TASK_OUTCOME_SUMMARY_RESULT'
            AND NEW.lane_mask IN (9, 10)
            AND NEW.trunk_control = 0
            AND NEW.exclusive_task = 0
            AND NEW.writer_required = 0
            AND NEW.stage_id IS NULL
            AND NEW.stage_generation IS NULL)
      OR EXISTS (
          SELECT 1 FROM tasks task
          JOIN task_outcome outcome ON outcome.task_id = task.id
          JOIN task_turn turn ON turn.id = NEW.owner_id
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            AND task.epoch = NEW.task_epoch
            AND outcome.task_epoch = NEW.task_epoch
            AND turn.task_id = task.id
            AND turn.purpose = 'TASK_BRAIN_CONVERSATION'
            AND turn.operation_id = NEW.operation_id
            AND turn.attempt = NEW.attempt
            AND NEW.operation_kind = 'EXECUTE_TASK_TURN'
            AND NEW.async_family = 'AGENT_TURN'
            AND NEW.owner_kind = 'TASK_TURN'
            AND NEW.callback_route = 'TASK_TURN_RESULT'
            AND NEW.lane_mask IN (9, 10)
            AND NEW.trunk_control = 0
            AND NEW.exclusive_task = 0
            AND NEW.writer_required = 0
            AND NEW.stage_id IS NULL
            AND NEW.stage_generation IS NULL))
BEGIN SELECT RAISE(ABORT,
    'Task admits no unrelated dispatch during or after Cleanup'); END;
