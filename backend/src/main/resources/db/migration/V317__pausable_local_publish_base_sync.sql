-- Pausable, retryable local publish-base synchronization.
-- V315 remains immutable; this forward rebuild adds explicit parked cursors,
-- operation generations, determinate attempt settlement, and exact receipts.
PRAGMA foreign_keys = OFF;
SAVEPOINT local_publish_base_sync_v317;

DROP VIEW task_current_code_subject_v230;

DROP TRIGGER IF EXISTS local_publish_base_sync_episode_insert;
DROP TRIGGER IF EXISTS local_publish_base_sync_episode_identity_immutable;
DROP TRIGGER IF EXISTS local_publish_base_sync_episode_terminal_immutable;
DROP TRIGGER IF EXISTS local_publish_base_sync_episode_transition;
DROP TRIGGER IF EXISTS local_publish_base_sync_episode_progress;
DROP TRIGGER IF EXISTS local_publish_base_sync_operation_insert;
DROP TRIGGER IF EXISTS local_publish_base_sync_operation_identity_immutable;
DROP TRIGGER IF EXISTS local_publish_base_sync_operation_dispatch;
DROP TRIGGER IF EXISTS local_publish_base_sync_operation_transition;
DROP TRIGGER IF EXISTS local_publish_base_sync_operation_result;
DROP TRIGGER IF EXISTS local_publish_base_sync_operation_terminal_immutable;
DROP TRIGGER IF EXISTS local_publish_base_sync_delivery_receipt_immutable;
DROP TRIGGER IF EXISTS local_publish_base_sync_delivery_receipt_insert;

CREATE TABLE local_publish_base_sync_episode_v317 AS
SELECT id, source_publish_operation_id, local_development_stage_id, task_id,
       task_epoch, stage_generation, source_code_fingerprint, source_head_sha,
       source_base_sha, target_base_sha, authority_kind,
       standing_policy_revision_id, blocker_id, actor,
       branch_sync_policy_revision_id, command_id, attempt_no, attempt_limit,
       status, opened_at_ms, completed_at_ms, error_message
FROM local_publish_base_sync_episode;

CREATE TABLE local_publish_base_sync_operation_v317 AS
SELECT id, episode_id, kind, operation_id, semantic_attempt,
       expected_code_fingerprint, expected_head_sha, expected_base_sha,
       target_base_sha, status, result_disposition, result_code_fingerprint,
       result_head_sha, result_base_sha, result_evidence_json, requested_at_ms,
       completed_at_ms, error_message
FROM local_publish_base_sync_operation;

CREATE TABLE local_publish_base_sync_delivery_receipt_v317 AS
SELECT operation_row_id, operation_id, raw_outcome, raw_result_digest,
       acceptance, recorded_at_ms
FROM local_publish_base_sync_delivery_receipt;

DROP TABLE local_publish_base_sync_delivery_receipt;
DROP TABLE local_publish_base_sync_operation;
DROP TABLE local_publish_base_sync_episode;

CREATE TABLE local_publish_base_sync_episode (
    id                          TEXT    NOT NULL PRIMARY KEY,
    source_publish_operation_id TEXT   NOT NULL
        REFERENCES publish_operation(id) ON DELETE CASCADE,
    local_development_stage_id  TEXT   NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT   NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    source_code_fingerprint     TEXT   NOT NULL,
    source_head_sha             TEXT   NOT NULL,
    source_base_sha             TEXT   NOT NULL,
    target_base_sha             TEXT   NOT NULL,
    authority_kind              TEXT   NOT NULL CHECK (authority_kind IN (
        'STANDING_TASK_POLICY', 'MANUAL')),
    standing_policy_revision_id TEXT REFERENCES task_policy_revision(id),
    blocker_id                  TEXT REFERENCES task_blocker(id),
    actor                       TEXT,
    branch_sync_policy_revision_id TEXT NOT NULL
        REFERENCES task_branch_sync_policy_revision(id),
    command_id                  TEXT   NOT NULL UNIQUE,
    retry_of_episode_id         TEXT UNIQUE
        REFERENCES local_publish_base_sync_episode(id),
    attempt_no                  INTEGER NOT NULL CHECK (attempt_no > 0),
    attempt_limit               INTEGER NOT NULL CHECK (attempt_limit BETWEEN 1 AND 10),
    status                      TEXT   NOT NULL CHECK (status IN (
        'FETCHING', 'REBASING', 'RECONCILING', 'PAUSED', 'HANDED_OFF',
        'FAILED', 'EXHAUSTED', 'CANCELED', 'SUPERSEDED')),
    resume_cursor               TEXT CHECK (resume_cursor IN (
        'FETCH', 'REBASE', 'HANDOFF')),
    opened_at_ms                INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    UNIQUE (local_development_stage_id, attempt_no),
    CHECK (source_base_sha <> target_base_sha),
    CHECK (attempt_no <= attempt_limit),
    CHECK ((authority_kind = 'STANDING_TASK_POLICY'
            AND standing_policy_revision_id IS NOT NULL
            AND blocker_id IS NULL AND actor IS NULL)
        OR (authority_kind = 'MANUAL'
            AND standing_policy_revision_id IS NULL
            AND blocker_id IS NOT NULL AND actor IS NOT NULL
            AND length(trim(actor)) > 0)),
    CHECK ((status = 'PAUSED') = (resume_cursor IS NOT NULL)),
    CHECK ((status IN (
                'HANDED_OFF', 'FAILED', 'EXHAUSTED', 'CANCELED', 'SUPERSEDED'))
            = (completed_at_ms IS NOT NULL)),
    CHECK ((status IN ('FAILED', 'EXHAUSTED', 'CANCELED', 'SUPERSEDED'))
            = (error_message IS NOT NULL))
);

CREATE UNIQUE INDEX idx_local_publish_base_sync_one_live
    ON local_publish_base_sync_episode(local_development_stage_id)
    WHERE status NOT IN (
        'HANDED_OFF', 'FAILED', 'EXHAUSTED', 'CANCELED', 'SUPERSEDED');
CREATE UNIQUE INDEX idx_local_publish_base_sync_one_source_attempt_v317
    ON local_publish_base_sync_episode(source_publish_operation_id, attempt_no);
DROP INDEX IF EXISTS idx_local_publish_base_sync_one_open_blocker;
CREATE UNIQUE INDEX idx_local_publish_base_sync_one_open_blocker
    ON task_blocker(subject_revision)
    WHERE blocker_type IN (
        'LOCAL_PUBLISH_BASE_SYNC_REQUIRED',
        'LOCAL_PUBLISH_BASE_SYNC_RETRY_REQUIRED',
        'LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED')
      AND status = 'OPEN';

CREATE TABLE local_publish_base_sync_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    episode_id                  TEXT    NOT NULL
        REFERENCES local_publish_base_sync_episode(id) ON DELETE CASCADE,
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'FETCH_COMPARE', 'MECHANICAL_REBASE')),
    generation                  INTEGER NOT NULL CHECK (generation > 0),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    expected_code_fingerprint   TEXT    NOT NULL,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    target_base_sha             TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'INDETERMINATE', 'CANCELED', 'SUPERSEDED')),
    result_disposition          TEXT CHECK (result_disposition IN (
        'FETCHED', 'REBASED', 'CONFLICT', 'FAILED')),
    result_code_fingerprint     TEXT,
    result_head_sha             TEXT,
    result_base_sha             TEXT,
    result_evidence_json        TEXT CHECK (
        result_evidence_json IS NULL OR json_valid(result_evidence_json)),
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    UNIQUE (episode_id, kind, generation),
    CHECK (
        (status IN ('REQUESTED', 'DISPATCHED')
            AND result_disposition IS NULL
            AND result_code_fingerprint IS NULL
            AND result_head_sha IS NULL AND result_base_sha IS NULL
            AND result_evidence_json IS NULL
            AND completed_at_ms IS NULL AND error_message IS NULL)
        OR (status = 'SUCCEEDED'
            AND result_disposition IN ('FETCHED', 'REBASED', 'CONFLICT')
            AND result_code_fingerprint IS NOT NULL
            AND result_head_sha IS NOT NULL AND result_base_sha IS NOT NULL
            AND result_evidence_json IS NOT NULL
            AND completed_at_ms IS NOT NULL AND error_message IS NULL)
        OR (status = 'FAILED' AND result_disposition = 'FAILED'
            AND result_code_fingerprint IS NULL
            AND result_head_sha IS NULL AND result_base_sha IS NULL
            AND result_evidence_json IS NULL
            AND completed_at_ms IS NOT NULL AND error_message IS NOT NULL)
        OR (status IN ('INDETERMINATE', 'CANCELED', 'SUPERSEDED')
            AND result_disposition IS NULL
            AND result_code_fingerprint IS NULL
            AND result_head_sha IS NULL AND result_base_sha IS NULL
            AND result_evidence_json IS NULL
            AND completed_at_ms IS NOT NULL AND error_message IS NOT NULL))
);
CREATE UNIQUE INDEX idx_local_publish_base_sync_one_live_operation
    ON local_publish_base_sync_operation(episode_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TABLE local_publish_base_sync_delivery_receipt (
    operation_row_id  TEXT    NOT NULL PRIMARY KEY
        REFERENCES local_publish_base_sync_operation(id) ON DELETE CASCADE,
    operation_id      TEXT    NOT NULL UNIQUE,
    raw_outcome       TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance        TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'PARKED', 'SUPERSEDED')),
    recorded_at_ms    INTEGER NOT NULL
);

INSERT INTO local_publish_base_sync_episode(
    id, source_publish_operation_id, local_development_stage_id, task_id,
    task_epoch, stage_generation, source_code_fingerprint, source_head_sha,
    source_base_sha, target_base_sha, authority_kind,
    standing_policy_revision_id, blocker_id, actor,
    branch_sync_policy_revision_id, command_id, retry_of_episode_id,
    attempt_no, attempt_limit, status, resume_cursor, opened_at_ms,
    completed_at_ms, error_message)
SELECT id, source_publish_operation_id, local_development_stage_id, task_id,
       task_epoch, stage_generation, source_code_fingerprint, source_head_sha,
       source_base_sha, target_base_sha, authority_kind,
       standing_policy_revision_id, blocker_id, actor,
       branch_sync_policy_revision_id, command_id, NULL,
       attempt_no, attempt_limit, status, NULL, opened_at_ms,
       completed_at_ms, error_message
FROM local_publish_base_sync_episode_v317;

INSERT INTO local_publish_base_sync_operation(
    id, episode_id, kind, generation, operation_id, semantic_attempt,
    expected_code_fingerprint, expected_head_sha, expected_base_sha,
    target_base_sha, status, result_disposition, result_code_fingerprint,
    result_head_sha, result_base_sha, result_evidence_json, requested_at_ms,
    completed_at_ms, error_message)
SELECT id, episode_id, kind, 1, operation_id, semantic_attempt,
       expected_code_fingerprint, expected_head_sha, expected_base_sha,
       target_base_sha, status, result_disposition, result_code_fingerprint,
       result_head_sha, result_base_sha, result_evidence_json, requested_at_ms,
       completed_at_ms, error_message
FROM local_publish_base_sync_operation_v317;

INSERT INTO local_publish_base_sync_delivery_receipt(
    operation_row_id, operation_id, raw_outcome, raw_result_digest,
    acceptance, recorded_at_ms)
SELECT operation_row_id, operation_id, raw_outcome, raw_result_digest,
       acceptance, recorded_at_ms
FROM local_publish_base_sync_delivery_receipt_v317;

DROP TABLE local_publish_base_sync_delivery_receipt_v317;
DROP TABLE local_publish_base_sync_operation_v317;
DROP TABLE local_publish_base_sync_episode_v317;

CREATE TABLE local_publish_base_sync_pause_receipt (
    id                      TEXT    NOT NULL PRIMARY KEY,
    episode_id              TEXT    NOT NULL
        REFERENCES local_publish_base_sync_episode(id) ON DELETE CASCADE,
    operation_row_id        TEXT    NOT NULL UNIQUE
        REFERENCES local_publish_base_sync_operation(id) ON DELETE CASCADE,
    operation_id            TEXT    NOT NULL UNIQUE,
    operation_generation    INTEGER NOT NULL CHECK (operation_generation > 0),
    prior_episode_status    TEXT    NOT NULL CHECK (prior_episode_status IN (
        'FETCHING', 'REBASING')),
    resume_cursor           TEXT    NOT NULL CHECK (resume_cursor IN (
        'FETCH', 'REBASE', 'HANDOFF')),
    settlement_kind         TEXT    NOT NULL CHECK (settlement_kind IN (
        'DELIVERED', 'CANCELED_BEFORE_START')),
    raw_outcome             TEXT CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED')),
    raw_result_digest       TEXT CHECK (
        raw_result_digest IS NULL OR length(raw_result_digest) = 64),
    task_lifecycle          TEXT    NOT NULL CHECK (task_lifecycle IN (
        'PAUSING', 'PAUSED', 'RESUMING')),
    recorded_at_ms          INTEGER NOT NULL,
    CHECK ((settlement_kind = 'DELIVERED')
        = (raw_outcome IS NOT NULL AND raw_result_digest IS NOT NULL))
);

CREATE TABLE local_publish_base_sync_resume_receipt (
    id                      TEXT    NOT NULL PRIMARY KEY,
    episode_id              TEXT    NOT NULL
        REFERENCES local_publish_base_sync_episode(id) ON DELETE CASCADE,
    pause_receipt_id        TEXT    NOT NULL UNIQUE
        REFERENCES local_publish_base_sync_pause_receipt(id),
    handoff_id              TEXT    NOT NULL UNIQUE
        REFERENCES stage_resume_rearm_intent_v257(handoff_id),
    resume_cursor           TEXT    NOT NULL CHECK (resume_cursor IN (
        'FETCH', 'REBASE', 'HANDOFF')),
    disposition             TEXT    NOT NULL CHECK (disposition IN (
        'OPERATION', 'HANDOFF', 'FAILURE')),
    successor_operation_id  TEXT UNIQUE,
    recorded_at_ms          INTEGER NOT NULL,
    CHECK ((disposition = 'OPERATION') = (successor_operation_id IS NOT NULL))
);

CREATE TABLE local_publish_base_sync_cancel_receipt (
    id                      TEXT    NOT NULL PRIMARY KEY,
    episode_id              TEXT    NOT NULL UNIQUE
        REFERENCES local_publish_base_sync_episode(id) ON DELETE CASCADE,
    operation_id            TEXT,
    prior_episode_status    TEXT    NOT NULL CHECK (prior_episode_status IN (
        'FETCHING', 'REBASING', 'PAUSED', 'EXHAUSTED')),
    task_lifecycle          TEXT    NOT NULL CHECK (task_lifecycle IN (
        'CANCELING', 'CLEANING', 'CANCELED')),
    recorded_at_ms          INTEGER NOT NULL
);

CREATE TABLE local_publish_base_sync_budget_extension (
    id                         TEXT    NOT NULL PRIMARY KEY,
    exhausted_episode_id       TEXT    NOT NULL UNIQUE
        REFERENCES local_publish_base_sync_episode(id),
    blocker_id                 TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    command_id                 TEXT    NOT NULL UNIQUE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    local_development_stage_id TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    previous_limit             INTEGER NOT NULL CHECK (previous_limit > 0),
    new_limit                  INTEGER NOT NULL CHECK (new_limit = previous_limit + 1),
    actor                      TEXT    NOT NULL CHECK (length(trim(actor)) > 0),
    retry_episode_id           TEXT    NOT NULL UNIQUE,
    recorded_at_ms             INTEGER NOT NULL
);

CREATE TRIGGER local_publish_base_sync_episode_insert
BEFORE INSERT ON local_publish_base_sync_episode
WHEN NEW.status <> 'FETCHING'
 OR NEW.resume_cursor IS NOT NULL
 OR NOT EXISTS (
    SELECT 1
    FROM publish_operation publish
    JOIN publish_authorization authorization
      ON authorization.id = publish.publish_authorization_id
    JOIN dispatch_ticket publish_ticket
      ON publish_ticket.operation_id = publish.operation_id
    JOIN local_development_stage local
      ON local.stage_id = publish.local_development_stage_id
    JOIN stage owner ON owner.id = local.stage_id
    JOIN tasks task ON task.id = publish.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    JOIN task_branch_sync_policy_revision branch_policy
      ON branch_policy.id = NEW.branch_sync_policy_revision_id
    WHERE publish.id = NEW.source_publish_operation_id
      AND publish.status = 'FAILED'
      AND publish.local_development_stage_id = NEW.local_development_stage_id
      AND publish.task_id = NEW.task_id
      AND publish.task_epoch = NEW.task_epoch
      AND publish.stage_generation = NEW.stage_generation
      AND publish.code_fingerprint = NEW.source_code_fingerprint
      AND publish.expected_head_sha = NEW.source_head_sha
      AND publish.expected_base_sha = NEW.source_base_sha
      AND authorization.revoked_at_ms IS NOT NULL
      AND authorization.consumed_at_ms IS NULL
      AND local.task_id = NEW.task_id
      AND local.generation = NEW.stage_generation
      AND local.opened_for_epoch = NEW.task_epoch
      AND owner.kind = 'LOCAL_DEVELOPMENT'
      AND owner.checkpoint = 'LOCAL_REVIEW'
      AND owner.completed_at_ms IS NULL
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.local_development_stage_id
      AND current.stage_generation = NEW.stage_generation
      AND code.code_fingerprint = NEW.source_code_fingerprint
      AND code.head_sha = NEW.source_head_sha
      AND code.base_sha = NEW.source_base_sha
      AND NOT EXISTS (
          SELECT 1 FROM remote_pr_binding binding
          WHERE binding.publish_operation_id = publish.id)
      AND branch_policy.task_id = NEW.task_id
      AND NEW.attempt_no = 1 + (
          SELECT COUNT(*) FROM local_publish_base_sync_episode prior_count
          WHERE prior_count.local_development_stage_id =
              NEW.local_development_stage_id)
      AND (
        (NEW.retry_of_episode_id IS NULL
          AND NEW.attempt_limit = branch_policy.attempt_limit
          AND branch_policy.revision = (
              SELECT MAX(latest.revision)
              FROM task_branch_sync_policy_revision latest
              WHERE latest.task_id = NEW.task_id)
          AND (
            (NEW.authority_kind = 'STANDING_TASK_POLICY'
              AND publish_ticket.status = 'RESULT_PENDING'
              AND publish_ticket.pending_result_outcome = 'FAILED'
              AND publish_ticket.pending_result_task_epoch = NEW.task_epoch
              AND publish_ticket.pending_result_stage_id =
                  NEW.local_development_stage_id
              AND publish_ticket.pending_result_stage_generation =
                  NEW.stage_generation
              AND publish_ticket.pending_result_operation_id =
                  publish.operation_id
              AND publish_ticket.pending_result_attempt =
                  publish.semantic_attempt
              AND publish_ticket.pending_result_expected_code_fingerprint =
                  NEW.source_code_fingerprint
              AND publish_ticket.pending_result_expected_head_sha =
                  NEW.source_head_sha
              AND publish_ticket.pending_result_expected_base_sha =
                  NEW.source_base_sha
              AND json_valid(publish_ticket.pending_result_payload)
              AND json_extract(publish_ticket.pending_result_payload,
                  '$.version') = 1
              AND json_extract(publish_ticket.pending_result_payload,
                  '$.publishOperationId') = publish.id
              AND json_extract(publish_ticket.pending_result_payload,
                  '$.operationId') = publish.operation_id
              AND json_extract(publish_ticket.pending_result_payload,
                  '$.taskId') = NEW.task_id
              AND json_extract(publish_ticket.pending_result_payload,
                  '$.stageId') = NEW.local_development_stage_id
              AND json_extract(publish_ticket.pending_result_payload,
                  '$.disposition') = 'BASE_MOVED'
              AND json_extract(publish_ticket.pending_result_payload,
                  '$.observedBaseSha') = NEW.target_base_sha
              AND length(trim(json_extract(
                  publish_ticket.pending_result_payload, '$.error'))) > 0
              AND json_extract(publish_ticket.pending_result_payload,
                  '$.error') = publish_ticket.pending_result_error
              AND EXISTS (
                  SELECT 1 FROM task_policy_revision policy
                  WHERE policy.id = NEW.standing_policy_revision_id
                    AND policy.id = authorization.policy_revision_id
                    AND policy.trunk_id = task.thread_id
                    AND policy.auto_approve = 1))
            OR
            (NEW.authority_kind = 'MANUAL'
              AND EXISTS (
                  SELECT 1 FROM task_blocker blocker
                  WHERE blocker.id = NEW.blocker_id
                    AND blocker.task_id = NEW.task_id
                    AND blocker.stage_id = NEW.local_development_stage_id
                    AND blocker.owner_kind = 'STAGE'
                    AND blocker.owner_id =
                        NEW.local_development_stage_id
                    AND blocker.blocker_type =
                        'LOCAL_PUBLISH_BASE_SYNC_REQUIRED'
                    AND blocker.subject_revision = publish.id
                    AND blocker.status = 'OPEN'
                    AND json_valid(blocker.payload_json)
                    AND json_extract(blocker.payload_json,
                        '$.sourcePublishOperationId') = publish.id
                    AND json_extract(blocker.payload_json,
                        '$.sourceBaseSha') = NEW.source_base_sha
                    AND json_extract(blocker.payload_json,
                        '$.targetBaseSha') = NEW.target_base_sha)
              AND EXISTS (
                  SELECT 1 FROM publish_delivery_receipt receipt
                  WHERE receipt.operation_id = publish.operation_id
                    AND receipt.outcome = 'FAILED'
                    AND receipt.acceptance = 'ACCEPTED'
                    AND receipt.remote_stage_id IS NULL))))
        OR
        (NEW.retry_of_episode_id IS NOT NULL
          AND EXISTS (
            SELECT 1
            FROM local_publish_base_sync_episode prior
            WHERE prior.id = NEW.retry_of_episode_id
              AND prior.source_publish_operation_id =
                  NEW.source_publish_operation_id
              AND prior.local_development_stage_id =
                  NEW.local_development_stage_id
              AND prior.task_id = NEW.task_id
              AND prior.task_epoch = NEW.task_epoch
              AND prior.stage_generation = NEW.stage_generation
              AND prior.source_code_fingerprint =
                  NEW.source_code_fingerprint
              AND prior.source_head_sha = NEW.source_head_sha
              AND prior.source_base_sha = NEW.source_base_sha
              AND prior.target_base_sha = NEW.target_base_sha
              AND prior.branch_sync_policy_revision_id =
                  NEW.branch_sync_policy_revision_id
              AND NEW.attempt_no = prior.attempt_no + 1
              AND (
                (prior.status = 'FAILED'
                  AND prior.attempt_no < prior.attempt_limit
                  AND NEW.attempt_limit = prior.attempt_limit
                  AND NEW.authority_kind = 'STANDING_TASK_POLICY'
                  AND prior.authority_kind = 'STANDING_TASK_POLICY'
                  AND NEW.standing_policy_revision_id =
                      prior.standing_policy_revision_id)
                OR
                (prior.status = 'FAILED'
                  AND prior.attempt_no < prior.attempt_limit
                  AND NEW.attempt_limit = prior.attempt_limit
                  AND NEW.authority_kind = 'MANUAL'
                  AND EXISTS (
                    SELECT 1 FROM task_blocker blocker
                    WHERE blocker.id = NEW.blocker_id
                      AND blocker.status = 'OPEN'
                      AND blocker.blocker_type =
                          'LOCAL_PUBLISH_BASE_SYNC_RETRY_REQUIRED'
                      AND blocker.subject_revision = prior.id))
                OR
                (prior.status = 'EXHAUSTED'
                  AND NEW.authority_kind = 'MANUAL'
                  AND NEW.attempt_limit = prior.attempt_limit + 1
                  AND EXISTS (
                    SELECT 1
                    FROM local_publish_base_sync_budget_extension extension
                    JOIN task_blocker blocker
                      ON blocker.id = extension.blocker_id
                    WHERE extension.exhausted_episode_id = prior.id
                      AND extension.retry_episode_id = NEW.id
                      AND extension.new_limit = NEW.attempt_limit
                      AND blocker.id = NEW.blocker_id
                      AND blocker.status = 'OPEN'
                      AND blocker.blocker_type =
                          'LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED'
                      AND blocker.subject_revision = prior.id)))))
      )
)
BEGIN SELECT RAISE(ABORT,
    'Local publish base sync requires its exact current subject and authority');
END;

CREATE TRIGGER local_publish_base_sync_episode_identity_immutable
BEFORE UPDATE OF source_publish_operation_id, local_development_stage_id,
        task_id, task_epoch, stage_generation, source_code_fingerprint,
        source_head_sha, source_base_sha, target_base_sha, authority_kind,
        standing_policy_revision_id, blocker_id, actor,
        branch_sync_policy_revision_id, command_id, retry_of_episode_id,
        attempt_no, attempt_limit, opened_at_ms
ON local_publish_base_sync_episode
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync identity is immutable'); END;

CREATE TRIGGER local_publish_base_sync_episode_terminal_immutable
BEFORE UPDATE ON local_publish_base_sync_episode
WHEN OLD.status IN ('HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED')
   OR (OLD.status = 'EXHAUSTED' AND NEW.status <> 'CANCELED')
BEGIN SELECT RAISE(ABORT,
    'Terminal local publish base-sync episode is immutable'); END;

CREATE TRIGGER local_publish_base_sync_episode_transition
BEFORE UPDATE OF status ON local_publish_base_sync_episode
WHEN NOT (
    (OLD.status = 'FETCHING' AND NEW.status IN (
        'REBASING', 'PAUSED', 'FAILED', 'EXHAUSTED',
        'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'REBASING' AND NEW.status IN (
        'RECONCILING', 'PAUSED', 'FAILED', 'EXHAUSTED',
        'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'RECONCILING' AND NEW.status IN (
        'HANDED_OFF', 'PAUSED', 'FAILED', 'EXHAUSTED',
        'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'PAUSED' AND NEW.status IN (
        'FETCHING', 'REBASING', 'RECONCILING',
        'FAILED', 'EXHAUSTED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'EXHAUSTED' AND NEW.status = 'CANCELED'))
BEGIN SELECT RAISE(ABORT,
    'Illegal local publish base-sync transition'); END;

CREATE TRIGGER local_publish_base_sync_operation_insert
BEFORE INSERT ON local_publish_base_sync_operation
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1
    FROM local_publish_base_sync_episode episode
    JOIN task_current_code_subject_v230 code ON code.task_id = episode.task_id
    WHERE episode.id = NEW.episode_id
      AND NEW.semantic_attempt = episode.attempt_no
      AND NEW.generation = 1 + COALESCE((
          SELECT MAX(prior.generation)
          FROM local_publish_base_sync_operation prior
          WHERE prior.episode_id = episode.id AND prior.kind = NEW.kind), 0)
      AND NEW.expected_code_fingerprint = episode.source_code_fingerprint
      AND NEW.expected_head_sha = episode.source_head_sha
      AND NEW.expected_base_sha = episode.source_base_sha
      AND NEW.target_base_sha = episode.target_base_sha
      AND code.code_fingerprint = NEW.expected_code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND NOT EXISTS (
          SELECT 1 FROM local_publish_base_sync_operation live
          WHERE live.episode_id = episode.id
            AND live.status IN ('REQUESTED', 'DISPATCHED'))
      AND ((NEW.kind = 'FETCH_COMPARE' AND episode.status = 'FETCHING')
        OR (NEW.kind = 'MECHANICAL_REBASE' AND episode.status = 'REBASING'
            AND EXISTS (
                SELECT 1
                FROM local_publish_base_sync_operation fetch
                JOIN local_publish_base_sync_delivery_receipt receipt
                  ON receipt.operation_row_id = fetch.id
                WHERE fetch.episode_id = episode.id
                  AND fetch.kind = 'FETCH_COMPARE'
                  AND fetch.status = 'SUCCEEDED'
                  AND fetch.result_disposition = 'FETCHED'
                  AND receipt.acceptance IN ('ACCEPTED', 'PARKED'))))
)
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync Operation requires its exact current subject'); END;

CREATE TRIGGER local_publish_base_sync_operation_identity_immutable
BEFORE UPDATE OF episode_id, kind, generation, operation_id, semantic_attempt,
        expected_code_fingerprint, expected_head_sha, expected_base_sha,
        target_base_sha, requested_at_ms
ON local_publish_base_sync_operation
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync Operation identity is immutable'); END;

CREATE TRIGGER local_publish_base_sync_operation_dispatch
BEFORE UPDATE OF status ON local_publish_base_sync_operation
WHEN NEW.status = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    JOIN local_publish_base_sync_episode episode
      ON episode.id = NEW.episode_id
    WHERE ticket.operation_id = NEW.operation_id
      AND ticket.operation_kind = CASE NEW.kind
          WHEN 'FETCH_COMPARE' THEN 'FETCH_LOCAL_PUBLISH_BASE'
          WHEN 'MECHANICAL_REBASE' THEN 'REBASE_LOCAL_PUBLISH_BASE' END
      AND ticket.async_family = 'LOCAL_GIT'
      AND ticket.owner_kind = 'STAGE'
      AND ticket.owner_id = episode.local_development_stage_id
      AND ticket.callback_route = CASE NEW.kind
          WHEN 'FETCH_COMPARE' THEN 'LOCAL_PUBLISH_BASE_FETCH_RESULT'
          WHEN 'MECHANICAL_REBASE' THEN 'LOCAL_PUBLISH_BASE_REBASE_RESULT' END
      AND ticket.task_epoch = episode.task_epoch
      AND ticket.stage_id = episode.local_development_stage_id
      AND ticket.stage_generation = episode.stage_generation
      AND ticket.attempt = NEW.semantic_attempt
      AND ticket.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND ticket.expected_head_sha = NEW.expected_head_sha
      AND ticket.expected_base_sha = NEW.expected_base_sha
      AND ticket.lane_mask = 16 AND ticket.trunk_control = 0
      AND ticket.exclusive_task = 1 AND ticket.writer_required = 1
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT,
    'Dispatched local publish base-sync Operation lacks its exact ticket'); END;

CREATE TRIGGER local_publish_base_sync_operation_transition
BEFORE UPDATE OF status ON local_publish_base_sync_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status = 'DISPATCHED')
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT,
    'Illegal local publish base-sync Operation transition'); END;

CREATE TRIGGER local_publish_base_sync_operation_result
BEFORE UPDATE OF status ON local_publish_base_sync_operation
WHEN NEW.status IN (
        'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'SUPERSEDED')
 AND (
   NOT (
      EXISTS (
        SELECT 1
        FROM dispatch_ticket ticket
        JOIN local_publish_base_sync_episode episode
          ON episode.id = OLD.episode_id
        WHERE ticket.operation_id = OLD.operation_id
          AND ticket.status = 'RESULT_PENDING'
          AND ticket.pending_result_task_epoch = episode.task_epoch
          AND ticket.pending_result_stage_id =
              episode.local_development_stage_id
          AND ticket.pending_result_stage_generation =
              episode.stage_generation
          AND ticket.pending_result_operation_id = OLD.operation_id
          AND ticket.pending_result_attempt = OLD.semantic_attempt
          AND ticket.pending_result_expected_code_fingerprint =
              OLD.expected_code_fingerprint
          AND ticket.pending_result_expected_head_sha = OLD.expected_head_sha
          AND ticket.pending_result_expected_base_sha = OLD.expected_base_sha
          AND (NEW.status = 'SUPERSEDED'
            OR ticket.pending_result_outcome = NEW.status))
      OR (NEW.status = 'CANCELED' AND EXISTS (
        SELECT 1
        FROM dispatch_ticket ticket
        JOIN local_publish_base_sync_episode episode
          ON episode.id = OLD.episode_id
        JOIN tasks task ON task.id = episode.task_id
        WHERE ticket.operation_id = OLD.operation_id
          AND ticket.status = 'CANCELED'
          AND ticket.delivery_acceptance = 'SUPERSEDED'
          AND task.lifecycle_state IN (
              'PAUSING', 'PAUSED', 'RESUMING',
              'CANCELING', 'CLEANING', 'CANCELED')))
   )
   OR (NEW.status = 'SUCCEEDED' AND NOT (
        (NEW.kind = 'FETCH_COMPARE'
          AND NEW.result_disposition = 'FETCHED'
          AND NEW.result_code_fingerprint = OLD.expected_code_fingerprint
          AND NEW.result_head_sha = OLD.expected_head_sha
          AND NEW.result_base_sha = OLD.expected_base_sha)
        OR (NEW.kind = 'MECHANICAL_REBASE'
          AND NEW.result_disposition = 'REBASED'
          AND NEW.result_base_sha = OLD.target_base_sha)
        OR (NEW.kind = 'MECHANICAL_REBASE'
          AND NEW.result_disposition = 'CONFLICT'
          AND NEW.result_code_fingerprint = OLD.expected_code_fingerprint
          AND NEW.result_head_sha = OLD.expected_head_sha
          AND NEW.result_base_sha = OLD.expected_base_sha)))
 )
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync result lacks its exact pending fence or subject'); END;

CREATE TRIGGER local_publish_base_sync_operation_terminal_immutable
BEFORE UPDATE ON local_publish_base_sync_operation
WHEN OLD.status IN (
    'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT,
    'Terminal local publish base-sync Operation is immutable'); END;

CREATE TRIGGER local_publish_base_sync_delivery_receipt_immutable
BEFORE UPDATE ON local_publish_base_sync_delivery_receipt
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync delivery receipt is immutable'); END;

CREATE TRIGGER local_publish_base_sync_delivery_receipt_insert
BEFORE INSERT ON local_publish_base_sync_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM local_publish_base_sync_operation operation
    JOIN local_publish_base_sync_episode episode
      ON episode.id = operation.episode_id
    JOIN dispatch_ticket ticket ON ticket.operation_id = operation.operation_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN stage owner ON owner.id = episode.local_development_stage_id
    LEFT JOIN task_current_stage current ON current.task_id = task.id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE operation.id = NEW.operation_row_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN (
          'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'SUPERSEDED')
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND ((NEW.acceptance = 'SUPERSEDED'
              AND operation.status = 'SUPERSEDED')
        OR (NEW.acceptance IN ('ACCEPTED', 'PARKED')
              AND operation.status = NEW.raw_outcome
              AND task.workflow_version = 'V2'
              AND task.lifecycle_state = CASE NEW.acceptance
                  WHEN 'ACCEPTED' THEN 'ACTIVE'
                  ELSE task.lifecycle_state END
              AND (NEW.acceptance <> 'PARKED'
                  OR task.lifecycle_state IN ('PAUSING', 'PAUSED', 'RESUMING'))
              AND task.epoch = episode.task_epoch
              AND current.stage_id = episode.local_development_stage_id
              AND current.stage_generation = episode.stage_generation
              AND owner.generation = episode.stage_generation
              AND owner.checkpoint = 'LOCAL_REVIEW'
              AND owner.completed_at_ms IS NULL
              AND code.code_fingerprint = operation.expected_code_fingerprint
              AND code.head_sha = operation.expected_head_sha
              AND code.base_sha = operation.expected_base_sha
              AND ((operation.kind = 'FETCH_COMPARE'
                      AND episode.status = 'FETCHING')
                OR (operation.kind = 'MECHANICAL_REBASE'
                      AND episode.status = 'REBASING'))))
)
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync delivery receipt is not exact'); END;

CREATE TRIGGER local_publish_base_sync_pause_receipt_immutable
BEFORE UPDATE ON local_publish_base_sync_pause_receipt
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync pause receipt is immutable'); END;

CREATE TRIGGER local_publish_base_sync_pause_receipt_insert
BEFORE INSERT ON local_publish_base_sync_pause_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM local_publish_base_sync_episode episode
    JOIN local_publish_base_sync_operation operation
      ON operation.episode_id = episode.id
    JOIN tasks task ON task.id = episode.task_id
    WHERE episode.id = NEW.episode_id
      AND operation.id = NEW.operation_row_id
      AND operation.operation_id = NEW.operation_id
      AND operation.generation = NEW.operation_generation
      AND episode.status = NEW.prior_episode_status
      AND task.lifecycle_state = NEW.task_lifecycle
      AND task.lifecycle_state IN ('PAUSING', 'PAUSED', 'RESUMING')
      AND task.epoch = episode.task_epoch
      AND ((NEW.settlement_kind = 'DELIVERED'
            AND EXISTS (
                SELECT 1
                FROM local_publish_base_sync_delivery_receipt receipt
                WHERE receipt.operation_row_id = operation.id
                  AND receipt.acceptance = 'PARKED'
                  AND receipt.raw_outcome = NEW.raw_outcome
                  AND receipt.raw_result_digest = NEW.raw_result_digest))
        OR (NEW.settlement_kind = 'CANCELED_BEFORE_START'
            AND operation.status = 'CANCELED'
            AND EXISTS (
                SELECT 1 FROM dispatch_ticket ticket
                WHERE ticket.operation_id = operation.operation_id
                  AND ticket.status = 'CANCELED'
                  AND ticket.delivery_acceptance = 'SUPERSEDED')))
)
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync pause receipt lacks exact settlement proof'); END;

CREATE TRIGGER local_publish_base_sync_resume_receipt_immutable
BEFORE UPDATE ON local_publish_base_sync_resume_receipt
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync resume receipt is immutable'); END;

CREATE TRIGGER local_publish_base_sync_resume_receipt_insert
BEFORE INSERT ON local_publish_base_sync_resume_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM local_publish_base_sync_episode episode
    JOIN local_publish_base_sync_pause_receipt pause
      ON pause.episode_id = episode.id
    JOIN stage_resume_rearm_intent_v257 intent
      ON intent.handoff_id = NEW.handoff_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    WHERE episode.id = NEW.episode_id
      AND pause.id = NEW.pause_receipt_id
      AND pause.resume_cursor = NEW.resume_cursor
      AND intent.status = 'MATERIALIZED'
      AND intent.task_id = episode.task_id
      AND intent.stage_id = episode.local_development_stage_id
      AND intent.task_epoch = episode.task_epoch
      AND intent.stage_generation = episode.stage_generation
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = episode.task_epoch
      AND current.stage_id = episode.local_development_stage_id
      AND current.stage_generation = episode.stage_generation
      AND ((NEW.disposition = 'OPERATION'
            AND EXISTS (
                SELECT 1
                FROM local_publish_base_sync_operation operation
                WHERE operation.operation_id = NEW.successor_operation_id
                  AND operation.episode_id = episode.id
                  AND operation.status = 'DISPATCHED'))
        OR (NEW.disposition = 'HANDOFF'
            AND episode.status = 'RECONCILING')
        OR (NEW.disposition = 'FAILURE'
            AND episode.status IN ('FAILED', 'EXHAUSTED')))
)
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync resume receipt lacks exact active handoff'); END;

CREATE TRIGGER local_publish_base_sync_cancel_receipt_immutable
BEFORE UPDATE ON local_publish_base_sync_cancel_receipt
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync cancel receipt is immutable'); END;

CREATE TRIGGER local_publish_base_sync_cancel_receipt_insert
BEFORE INSERT ON local_publish_base_sync_cancel_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM local_publish_base_sync_episode episode
    JOIN tasks task ON task.id = episode.task_id
    WHERE episode.id = NEW.episode_id
      AND episode.status = NEW.prior_episode_status
      AND task.lifecycle_state = NEW.task_lifecycle
      AND task.lifecycle_state IN ('CANCELING', 'CLEANING', 'CANCELED')
      AND task.epoch = episode.task_epoch + 1)
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync cancel receipt lacks exact terminal intent'); END;

CREATE TRIGGER local_publish_base_sync_budget_extension_immutable
BEFORE UPDATE ON local_publish_base_sync_budget_extension
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync budget extension is immutable'); END;

CREATE TRIGGER local_publish_base_sync_budget_extension_insert
BEFORE INSERT ON local_publish_base_sync_budget_extension
WHEN NOT EXISTS (
    SELECT 1
    FROM local_publish_base_sync_episode episode
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    WHERE episode.id = NEW.exhausted_episode_id
      AND episode.status = 'EXHAUSTED'
      AND episode.task_id = NEW.task_id
      AND episode.local_development_stage_id =
          NEW.local_development_stage_id
      AND episode.attempt_limit = NEW.previous_limit
      AND blocker.task_id = episode.task_id
      AND blocker.stage_id = episode.local_development_stage_id
      AND blocker.subject_revision = episode.id
      AND blocker.blocker_type = 'LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED'
      AND blocker.status = 'OPEN'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = episode.task_epoch
      AND current.stage_id = episode.local_development_stage_id
      AND current.stage_generation = episode.stage_generation)
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync extension lacks exact exhausted authority'); END;

-- A clean rebase becomes the current worktree subject until its BASE_SYNC
-- StageTurn produces a DevReport. Conflict leaves the old source current.
CREATE VIEW task_current_code_subject_v230 AS
SELECT task.id AS task_id,
       COALESCE(current_local.code_fingerprint, remote.code_fingerprint,
                report.code_fingerprint, code.code_fingerprint) AS code_fingerprint,
       COALESCE(current_local.head_sha, remote.head_sha,
                report.head_sha, code.local_head_sha) AS head_sha,
       COALESCE(current_local.base_sha, remote.base_sha,
                report.base_sha, code.base_sha) AS base_sha
FROM tasks task
JOIN task_code_identity code ON code.task_id = task.id
LEFT JOIN dev_report report ON report.id = (
    SELECT candidate.id FROM dev_report candidate
    WHERE candidate.workflow_version = 'V2' AND candidate.task_id = task.id
    ORDER BY candidate.created_at_ms DESC, candidate.id DESC LIMIT 1)
LEFT JOIN remote_code_subject remote ON remote.id = (
    SELECT candidate.id FROM remote_code_subject candidate
    WHERE candidate.task_id = task.id AND candidate.task_epoch = task.epoch
    ORDER BY candidate.created_at_ms DESC, candidate.id DESC LIMIT 1)
LEFT JOIN (
    SELECT 'WORKTREE:' || id AS selector, task_id, task_epoch,
           code_fingerprint, head_sha, base_sha, recorded_at_ms
    FROM remote_worktree_subject
    UNION ALL
    SELECT 'STEERING:' || request_id, task_id, task_epoch,
           code_fingerprint, head_sha, base_sha, recorded_at_ms
    FROM remote_steering_code_subject_v257
    UNION ALL
    SELECT 'BASE_REPAIR:' || id, task_id, task_epoch,
           code_fingerprint, head_sha, base_sha, recorded_at_ms
    FROM ci_base_repair_subject_v303
    UNION ALL
    SELECT 'LOCAL_BASE_SYNC:' || operation.id,
           episode.task_id, episode.task_epoch,
           operation.result_code_fingerprint,
           operation.result_head_sha, operation.result_base_sha,
           operation.completed_at_ms
    FROM local_publish_base_sync_operation operation
    JOIN local_publish_base_sync_episode episode ON episode.id = operation.episode_id
    JOIN local_publish_base_sync_delivery_receipt receipt
      ON receipt.operation_row_id = operation.id
    WHERE operation.kind = 'MECHANICAL_REBASE'
      AND operation.status = 'SUCCEEDED'
      AND operation.result_disposition = 'REBASED'
      AND receipt.acceptance IN ('ACCEPTED', 'PARKED')
      AND NOT EXISTS (
          SELECT 1
          FROM local_stage_turn_request request
          JOIN dev_report completed ON completed.stage_turn_id = request.stage_turn_id
          WHERE request.base_sync_episode_id = episode.id)
) current_local ON current_local.selector = (
    SELECT candidate.selector FROM (
        SELECT 'WORKTREE:' || id AS selector, task_id, task_epoch,
               recorded_at_ms FROM remote_worktree_subject
        UNION ALL
        SELECT 'STEERING:' || request_id, task_id, task_epoch,
               recorded_at_ms FROM remote_steering_code_subject_v257
        UNION ALL
        SELECT 'BASE_REPAIR:' || id, task_id, task_epoch,
               recorded_at_ms FROM ci_base_repair_subject_v303
        UNION ALL
        SELECT 'LOCAL_BASE_SYNC:' || operation.id,
               episode.task_id, episode.task_epoch, operation.completed_at_ms
        FROM local_publish_base_sync_operation operation
        JOIN local_publish_base_sync_episode episode ON episode.id = operation.episode_id
        JOIN local_publish_base_sync_delivery_receipt receipt
          ON receipt.operation_row_id = operation.id
        WHERE operation.kind = 'MECHANICAL_REBASE'
          AND operation.status = 'SUCCEEDED'
          AND operation.result_disposition = 'REBASED'
          AND receipt.acceptance IN ('ACCEPTED', 'PARKED')
          AND NOT EXISTS (
              SELECT 1
              FROM local_stage_turn_request request
              JOIN dev_report completed ON completed.stage_turn_id = request.stage_turn_id
              WHERE request.base_sync_episode_id = episode.id)
    ) candidate
    WHERE candidate.task_id = task.id AND candidate.task_epoch = task.epoch
    ORDER BY candidate.recorded_at_ms DESC, candidate.selector DESC LIMIT 1)
WHERE task.workflow_version = 'V2';



CREATE TRIGGER local_publish_base_sync_episode_progress
BEFORE UPDATE OF status ON local_publish_base_sync_episode
WHEN (NEW.status = 'REBASING' AND NOT EXISTS (
        SELECT 1
        FROM local_publish_base_sync_operation operation
        JOIN local_publish_base_sync_delivery_receipt receipt
          ON receipt.operation_row_id = operation.id
        WHERE operation.episode_id = NEW.id
          AND operation.kind = 'FETCH_COMPARE'
          AND operation.status = 'SUCCEEDED'
          AND operation.result_disposition = 'FETCHED'
          AND receipt.acceptance IN ('ACCEPTED', 'PARKED')))
  OR (NEW.status = 'RECONCILING' AND NOT EXISTS (
        SELECT 1
        FROM local_publish_base_sync_operation operation
        JOIN local_publish_base_sync_delivery_receipt receipt
          ON receipt.operation_row_id = operation.id
        WHERE operation.episode_id = NEW.id
          AND operation.kind = 'MECHANICAL_REBASE'
          AND operation.status = 'SUCCEEDED'
          AND operation.result_disposition IN ('REBASED', 'CONFLICT')
          AND receipt.acceptance IN ('ACCEPTED', 'PARKED')))
  OR (NEW.status = 'PAUSED' AND NOT EXISTS (
        SELECT 1 FROM local_publish_base_sync_pause_receipt pause
        WHERE pause.episode_id = NEW.id
          AND pause.resume_cursor = NEW.resume_cursor))
  OR (NEW.status = 'HANDED_OFF' AND NOT EXISTS (
        SELECT 1 FROM local_publish_base_sync_start_receipt receipt
        WHERE receipt.episode_id = NEW.id))
  OR (NEW.status = 'CANCELED'
      AND EXISTS (
          SELECT 1 FROM tasks task
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state IN ('CANCELING', 'CLEANING', 'CANCELED'))
      AND NOT EXISTS (
          SELECT 1 FROM local_publish_base_sync_cancel_receipt cancel
          WHERE cancel.episode_id = NEW.id))
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync progress lacks exact durable proof'); END;

-- A canceled Task advances its epoch before an abandoned local-Git claim can
-- be reconciled. Permit only this exact proof-only route to reacquire capacity.
DROP TRIGGER capacity_lease_route_insert;
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
        WHEN NEW.task_id IS NOT NULL AND NOT (
            EXISTS (
                SELECT 1 FROM tasks t JOIN threads h ON h.id = t.thread_id
                WHERE t.id = NEW.task_id
                  AND t.workflow_version = NEW.workflow_source
                  AND t.epoch = NEW.task_epoch
                  AND t.thread_id = NEW.trunk_id
                  AND h.workspace_id IS NEW.workspace_id)
            OR EXISTS (
                SELECT 1
                FROM tasks t
                JOIN threads h ON h.id = t.thread_id
                JOIN dispatch_ticket ticket ON ticket.id = NEW.ticket_id
                WHERE t.id = NEW.task_id
                  AND t.workflow_version = 'V2'
                  AND t.lifecycle_state IN (
                      'CANCELING', 'CLEANING', 'CANCELED')
                  AND t.epoch = NEW.task_epoch + 1
                  AND t.thread_id = NEW.trunk_id
                  AND h.workspace_id IS NEW.workspace_id
                  AND ticket.operation_kind IN (
                      'FETCH_LOCAL_PUBLISH_BASE',
                      'REBASE_LOCAL_PUBLISH_BASE')
                  AND ticket.status = 'RECONCILE_WAIT'))
            THEN RAISE(ABORT, 'capacity lease does not match Task route and epoch')
        WHEN NEW.task_id IS NULL AND NEW.trunk_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM threads h
            WHERE h.id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'capacity lease does not match Trunk route')
    END;
END;

CREATE TABLE local_publish_base_sync_fk_assert_v317 (
    value INTEGER NOT NULL CHECK (value = 1)
);
INSERT INTO local_publish_base_sync_fk_assert_v317(value)
VALUES ((SELECT CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END
         FROM pragma_foreign_key_check));
DROP TABLE local_publish_base_sync_fk_assert_v317;

RELEASE local_publish_base_sync_v317;
PRAGMA foreign_keys = ON;
