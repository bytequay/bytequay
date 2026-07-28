-- A Task policy revision can make an accepted exact-head readiness proof
-- stricter. The Remote owner records that regression explicitly instead of
-- leaving READY_TO_MERGE projected from an older policy.

CREATE TABLE remote_policy_stage_receipt_v268 (
    id                                TEXT    NOT NULL PRIMARY KEY,
    stage_id                          TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (
        cause = 'RECONSIDER_REMOTE_READINESS_POLICY'),
    actor                             TEXT    NOT NULL CHECK (length(actor) > 0),
    disposition                       TEXT    NOT NULL CHECK (disposition = 'APPLIED'),
    expected_task_epoch               INTEGER NOT NULL CHECK (expected_task_epoch > 0),
    expected_stage_generation         INTEGER NOT NULL CHECK (expected_stage_generation > 0),
    expected_stage_version            INTEGER NOT NULL CHECK (expected_stage_version >= 0),
    source_checkpoint                 TEXT    NOT NULL CHECK (
        source_checkpoint = 'READY_TO_MERGE'),
    subject_task_epoch                INTEGER,
    subject_stage_id                  TEXT,
    subject_stage_generation          INTEGER,
    subject_operation_id              TEXT,
    subject_attempt                   INTEGER,
    subject_expected_code_fingerprint TEXT,
    subject_expected_head_sha         TEXT,
    subject_expected_base_sha         TEXT,
    proof_id                          TEXT    NOT NULL
        REFERENCES remote_readiness_evidence(id),
    returned_kind                     TEXT    NOT NULL CHECK (
        returned_kind = 'REMOTE_DEVELOPMENT'),
    returned_generation               INTEGER NOT NULL CHECK (returned_generation > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version > 0),
    returned_checkpoint               TEXT    NOT NULL CHECK (
        returned_checkpoint = 'WAITING_REMOTE_REVIEW'),
    returned_end_reason               TEXT,
    returned_pending_task_epoch       INTEGER,
    returned_pending_stage_id         TEXT,
    returned_pending_stage_generation INTEGER,
    returned_pending_operation_id     TEXT,
    returned_pending_attempt          INTEGER,
    returned_pending_code_fingerprint TEXT,
    returned_pending_head_sha         TEXT,
    returned_pending_base_sha         TEXT,
    recorded_at_ms                    INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    UNIQUE (stage_id, command_id),
    CHECK (returned_version = expected_stage_version + 1),
    CHECK (subject_task_epoch IS NULL
        AND subject_stage_id IS NULL
        AND subject_stage_generation IS NULL
        AND subject_operation_id IS NULL
        AND subject_attempt IS NULL
        AND subject_expected_code_fingerprint IS NULL
        AND subject_expected_head_sha IS NULL
        AND subject_expected_base_sha IS NULL),
    CHECK (returned_end_reason IS NULL
        AND returned_pending_task_epoch IS NULL
        AND returned_pending_stage_id IS NULL
        AND returned_pending_stage_generation IS NULL
        AND returned_pending_operation_id IS NULL
        AND returned_pending_attempt IS NULL
        AND returned_pending_code_fingerprint IS NULL
        AND returned_pending_head_sha IS NULL
        AND returned_pending_base_sha IS NULL)
);

CREATE TRIGGER remote_policy_stage_receipt_v268_insert
BEFORE INSERT ON remote_policy_stage_receipt_v268
WHEN NOT EXISTS (
    SELECT 1
    FROM stage owner
    JOIN tasks task ON task.id = owner.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN remote_development_stage remote ON remote.stage_id = owner.id
    JOIN remote_readiness_evidence readiness ON readiness.id = NEW.proof_id
    JOIN task_automation_policy policy
      ON policy.id = readiness.automation_policy_id
    JOIN stage_transition transition
      ON transition.stage_id = owner.id
     AND transition.command_id = NEW.command_id
    WHERE owner.id = NEW.stage_id
      AND owner.task_id = NEW.task_id
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = NEW.returned_generation
      AND owner.version = NEW.returned_version
      AND owner.checkpoint = 'WAITING_REMOTE_REVIEW'
      AND owner.completed_at_ms IS NULL
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.expected_task_epoch
      AND current.stage_id = owner.id
      AND current.stage_generation = owner.generation
      AND NEW.expected_stage_generation = owner.generation
      AND remote.generation = owner.generation
      AND remote.accepted_snapshot_id = readiness.remote_pr_snapshot_id
      AND remote.current_head_sha = readiness.head_sha
      AND remote.current_base_sha = readiness.base_sha
      AND readiness.task_id = task.id
      AND readiness.task_epoch = task.epoch
      AND readiness.stage_generation = owner.generation
      AND readiness.ready = 0
      AND policy.task_id = task.id
      AND policy.revision = (
          SELECT MAX(current_policy.revision)
          FROM task_automation_policy current_policy
          WHERE current_policy.task_id = task.id)
      AND transition.generation = owner.generation
      AND transition.from_checkpoint = 'READY_TO_MERGE'
      AND transition.to_checkpoint = 'WAITING_REMOTE_REVIEW'
      AND transition.stage_version = owner.version
      AND transition.cause = NEW.cause
      AND transition.actor = NEW.actor)
BEGIN SELECT RAISE(ABORT,
    'Remote policy readiness receipt lacks exact current-policy evidence'); END;

CREATE TRIGGER remote_policy_stage_receipt_v268_immutable
BEFORE UPDATE ON remote_policy_stage_receipt_v268
BEGIN SELECT RAISE(ABORT, 'Remote policy readiness receipt is immutable'); END;
