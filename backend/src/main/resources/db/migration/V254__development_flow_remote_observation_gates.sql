-- Recurring RemoteObserver facts are exact owner proofs, not synthetic
-- one-shot Stage results. Keep their receipts separate from the shared V228
-- result table and the V242 feedback/effect receipt table.

CREATE TABLE remote_observation_stage_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    stage_id                          TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause IN (
        'ACCEPT_REMOTE_CI',
        'ACCEPT_OBSERVED_READY',
        'ACCEPT_REMOTE_HEAD_CHANGE')),
    actor                             TEXT    NOT NULL CHECK (length(actor) > 0),
    disposition                       TEXT    NOT NULL CHECK (disposition IN (
        'APPLIED', 'SUPERSEDED')),
    expected_task_epoch               INTEGER NOT NULL CHECK (expected_task_epoch > 0),
    expected_stage_generation         INTEGER NOT NULL CHECK (expected_stage_generation > 0),
    expected_stage_version            INTEGER NOT NULL CHECK (expected_stage_version >= 0),
    source_checkpoint                 TEXT    NOT NULL CHECK (source_checkpoint IN (
        'WAITING_CI', 'AWAITING_READY', 'WAITING_REMOTE_REVIEW',
        'ADDRESSING_REMOTE_FEEDBACK', 'READY_TO_MERGE', 'MERGING')),
    subject_task_epoch                INTEGER,
    subject_stage_id                  TEXT,
    subject_stage_generation          INTEGER,
    subject_operation_id              TEXT,
    subject_attempt                   INTEGER,
    subject_expected_code_fingerprint TEXT,
    subject_expected_head_sha         TEXT,
    subject_expected_base_sha         TEXT,
    proof_id                          TEXT    NOT NULL CHECK (length(proof_id) > 0),
    returned_kind                     TEXT    NOT NULL CHECK (
        returned_kind = 'REMOTE_DEVELOPMENT'),
    returned_generation               INTEGER NOT NULL CHECK (returned_generation > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version >= 0),
    returned_checkpoint               TEXT    NOT NULL CHECK (returned_checkpoint IN (
        'WAITING_CI', 'AWAITING_READY', 'WAITING_REMOTE_REVIEW')),
    returned_end_reason               TEXT,
    returned_pending_task_epoch       INTEGER,
    returned_pending_stage_id         TEXT,
    returned_pending_stage_generation INTEGER,
    returned_pending_operation_id     TEXT,
    returned_pending_attempt          INTEGER,
    returned_pending_code_fingerprint TEXT,
    returned_pending_head_sha         TEXT,
    returned_pending_base_sha         TEXT,
    recorded_at_ms                    INTEGER NOT NULL,
    UNIQUE (stage_id, command_id),
    CHECK (returned_version = expected_stage_version + 1),
    CHECK (subject_operation_id IS NULL
        AND subject_task_epoch IS NULL AND subject_stage_id IS NULL
        AND subject_stage_generation IS NULL AND subject_attempt IS NULL
        AND subject_expected_code_fingerprint IS NULL
        AND subject_expected_head_sha IS NULL
        AND subject_expected_base_sha IS NULL),
    CHECK (returned_pending_operation_id IS NULL
        AND returned_pending_task_epoch IS NULL
        AND returned_pending_stage_id IS NULL
        AND returned_pending_stage_generation IS NULL
        AND returned_pending_attempt IS NULL
        AND returned_pending_code_fingerprint IS NULL
        AND returned_pending_head_sha IS NULL
        AND returned_pending_base_sha IS NULL),
    CHECK (returned_end_reason IS NULL),
    CHECK ((cause = 'ACCEPT_REMOTE_CI'
            AND source_checkpoint = 'WAITING_CI'
            AND returned_checkpoint = 'AWAITING_READY')
        OR (cause = 'ACCEPT_OBSERVED_READY'
            AND source_checkpoint = 'AWAITING_READY'
            AND returned_checkpoint = 'WAITING_REMOTE_REVIEW')
        OR (cause = 'ACCEPT_REMOTE_HEAD_CHANGE'
            AND source_checkpoint IN (
                'AWAITING_READY', 'WAITING_REMOTE_REVIEW',
                'ADDRESSING_REMOTE_FEEDBACK', 'READY_TO_MERGE', 'MERGING')
            AND returned_checkpoint = 'WAITING_CI'))
);

CREATE TRIGGER remote_observation_stage_receipt_insert
BEFORE INSERT ON remote_observation_stage_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM stage owner
    JOIN tasks task ON task.id = owner.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN remote_development_stage remote ON remote.stage_id = owner.id
    WHERE owner.id = NEW.stage_id
      AND owner.task_id = NEW.task_id
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = NEW.returned_generation
      AND owner.version = NEW.returned_version
      AND owner.checkpoint = NEW.returned_checkpoint
      AND owner.completed_at_ms IS NULL
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.expected_task_epoch
      AND current.stage_id = owner.id
      AND current.stage_generation = owner.generation
      AND NEW.expected_stage_generation = owner.generation
      AND NEW.returned_generation = owner.generation
      AND (
        (NEW.cause = 'ACCEPT_REMOTE_CI' AND EXISTS (
            SELECT 1 FROM remote_ci_evaluation ci
            WHERE ci.id = NEW.proof_id
              AND ci.task_id = NEW.task_id
              AND ci.task_epoch = NEW.expected_task_epoch
              AND ci.remote_development_stage_id = owner.id
              AND ci.stage_generation = owner.generation
              AND ci.policy_outcome = 'ACCEPTED'
              AND ci.remote_pr_snapshot_id = remote.accepted_snapshot_id
              AND ci.head_sha = remote.current_head_sha
              AND ci.base_sha = remote.current_base_sha))
        OR (NEW.cause = 'ACCEPT_OBSERVED_READY' AND EXISTS (
            SELECT 1 FROM remote_pr_snapshot snapshot
            JOIN remote_ci_evaluation ci
              ON ci.remote_pr_snapshot_id = snapshot.id
             AND ci.policy_outcome = 'ACCEPTED'
            WHERE snapshot.id = NEW.proof_id
              AND snapshot.task_id = NEW.task_id
              AND snapshot.task_epoch = NEW.expected_task_epoch
              AND snapshot.remote_development_stage_id = owner.id
              AND snapshot.stage_generation = owner.generation
              AND snapshot.pr_state = 'OPEN'
              AND remote.accepted_snapshot_id = snapshot.id
              AND remote.current_head_sha = snapshot.head_sha
              AND remote.current_base_sha = snapshot.base_sha))
        OR (NEW.cause = 'ACCEPT_REMOTE_HEAD_CHANGE' AND EXISTS (
            SELECT 1 FROM remote_pr_snapshot snapshot
            JOIN remote_head_evidence_invalidation invalidation
              ON invalidation.remote_development_stage_id = owner.id
             AND invalidation.accepted_snapshot_id = snapshot.id
            WHERE snapshot.id = NEW.proof_id
              AND snapshot.task_id = NEW.task_id
              AND snapshot.task_epoch = NEW.expected_task_epoch
              AND snapshot.remote_development_stage_id = owner.id
              AND snapshot.stage_generation = owner.generation
              AND remote.accepted_snapshot_id = snapshot.id
              AND remote.current_head_sha = snapshot.head_sha
              AND remote.current_base_sha = snapshot.base_sha)))
      AND (NEW.disposition = 'SUPERSEDED' OR EXISTS (
          SELECT 1 FROM stage_transition transition
          WHERE transition.stage_id = NEW.stage_id
            AND transition.command_id = NEW.command_id
            AND transition.generation = NEW.returned_generation
            AND transition.stage_version = NEW.returned_version
            AND transition.cause = NEW.cause
            AND transition.actor = NEW.actor
            AND transition.from_checkpoint = NEW.source_checkpoint
            AND transition.to_checkpoint = NEW.returned_checkpoint))
)
BEGIN
    SELECT RAISE(ABORT,
        'Remote observation Stage receipt lacks exact current owner proof');
END;

CREATE TRIGGER remote_observation_stage_receipt_immutable
BEFORE UPDATE ON remote_observation_stage_receipt
BEGIN
    SELECT RAISE(ABORT, 'Remote observation Stage receipt is immutable');
END;

CREATE TRIGGER remote_observation_stage_receipt_delete
BEFORE DELETE ON remote_observation_stage_receipt
BEGIN
    SELECT RAISE(ABORT, 'Remote observation Stage receipt cannot be deleted');
END;
