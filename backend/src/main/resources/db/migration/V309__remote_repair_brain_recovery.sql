CREATE TABLE remote_repair_brain_replacement_operation_v309 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    family                      TEXT    NOT NULL CHECK (family IN ('CI', 'BRANCH')),
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
    dispatch_ticket_id          TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
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
    CHECK ((family = 'CI' AND ci_repair_episode_id IS NOT NULL
            AND branch_sync_episode_id IS NULL
            AND branch_sync_effect_step_id IS NULL)
        OR (family = 'BRANCH' AND ci_repair_episode_id IS NULL
            AND branch_sync_episode_id IS NOT NULL
            AND branch_sync_effect_step_id IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (verdict IS NOT NULL)),
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
    recorded_at_ms     INTEGER NOT NULL
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
    UNIQUE (task_id, command_id)
);

CREATE TRIGGER remote_repair_brain_retry_command_immutable_v309
BEFORE UPDATE ON remote_repair_brain_retry_command_v309
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain retry command is immutable'); END;

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
