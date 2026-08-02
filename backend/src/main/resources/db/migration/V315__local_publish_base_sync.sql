-- Durable first-publish base synchronization. Standing authority is admitted
-- while the typed BASE_MOVED result is pending; later manual authority uses
-- its frozen blocker plus the accepted publish-failure delivery receipt.
-- Flyway executes this script outside its transaction so foreign-key actions
-- can be disabled before this explicit atomic schema-rebuild transaction.
PRAGMA foreign_keys = OFF;
SAVEPOINT local_publish_base_sync_v315;

CREATE TABLE local_publish_base_sync_episode (
    id                          TEXT    NOT NULL PRIMARY KEY,
    source_publish_operation_id TEXT   NOT NULL UNIQUE
        REFERENCES publish_operation(id) ON DELETE CASCADE,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    source_code_fingerprint     TEXT    NOT NULL,
    source_head_sha             TEXT    NOT NULL,
    source_base_sha             TEXT    NOT NULL,
    target_base_sha             TEXT    NOT NULL,
    authority_kind              TEXT    NOT NULL CHECK (authority_kind IN (
        'STANDING_TASK_POLICY', 'MANUAL')),
    standing_policy_revision_id TEXT REFERENCES task_policy_revision(id),
    blocker_id                  TEXT REFERENCES task_blocker(id),
    actor                       TEXT,
    branch_sync_policy_revision_id TEXT NOT NULL
        REFERENCES task_branch_sync_policy_revision(id),
    command_id                  TEXT    NOT NULL UNIQUE,
    attempt_no                  INTEGER NOT NULL CHECK (attempt_no > 0),
    attempt_limit               INTEGER NOT NULL CHECK (attempt_limit BETWEEN 1 AND 10),
    status                      TEXT    NOT NULL CHECK (status IN (
        'FETCHING', 'REBASING', 'RECONCILING', 'HANDED_OFF',
        'FAILED', 'CANCELED', 'SUPERSEDED')),
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
    CHECK ((status IN ('HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED'))
            = (completed_at_ms IS NOT NULL)),
    CHECK ((status IN ('FAILED', 'CANCELED', 'SUPERSEDED'))
            = (error_message IS NOT NULL))
);
CREATE UNIQUE INDEX idx_local_publish_base_sync_one_live
    ON local_publish_base_sync_episode(local_development_stage_id)
    WHERE status NOT IN ('HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED');
CREATE UNIQUE INDEX idx_local_publish_base_sync_one_open_blocker
    ON task_blocker(subject_revision)
    WHERE blocker_type = 'LOCAL_PUBLISH_BASE_SYNC_REQUIRED'
      AND status = 'OPEN';

CREATE TABLE local_publish_base_sync_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    episode_id                  TEXT    NOT NULL
        REFERENCES local_publish_base_sync_episode(id) ON DELETE CASCADE,
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'FETCH_COMPARE', 'MECHANICAL_REBASE')),
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
    UNIQUE (episode_id, kind),
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
        'ACCEPTED', 'SUPERSEDED')),
    recorded_at_ms    INTEGER NOT NULL
);

CREATE TRIGGER local_publish_base_sync_episode_insert
BEFORE INSERT ON local_publish_base_sync_episode
WHEN NEW.status <> 'FETCHING' OR NOT EXISTS (
    SELECT 1
    FROM publish_operation publish
    JOIN publish_authorization authorization
      ON authorization.id = publish.publish_authorization_id
    JOIN dispatch_ticket ticket ON ticket.operation_id = publish.operation_id
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
      AND branch_policy.attempt_limit = NEW.attempt_limit
      AND branch_policy.revision = (
          SELECT MAX(latest.revision)
          FROM task_branch_sync_policy_revision latest
          WHERE latest.task_id = NEW.task_id)
      AND NEW.attempt_no = 1 + (
          SELECT COUNT(*) FROM local_publish_base_sync_episode prior
          WHERE prior.local_development_stage_id = NEW.local_development_stage_id)
      AND NEW.attempt_no <= branch_policy.attempt_limit
      AND ((NEW.authority_kind = 'STANDING_TASK_POLICY'
            AND ticket.status = 'RESULT_PENDING'
            AND ticket.pending_result_outcome = 'FAILED'
            AND ticket.pending_result_task_epoch = NEW.task_epoch
            AND ticket.pending_result_stage_id = NEW.local_development_stage_id
            AND ticket.pending_result_stage_generation = NEW.stage_generation
            AND ticket.pending_result_operation_id = publish.operation_id
            AND ticket.pending_result_attempt = publish.semantic_attempt
            AND ticket.pending_result_expected_code_fingerprint =
                NEW.source_code_fingerprint
            AND ticket.pending_result_expected_head_sha = NEW.source_head_sha
            AND ticket.pending_result_expected_base_sha = NEW.source_base_sha
            AND json_valid(ticket.pending_result_payload)
            AND json_extract(ticket.pending_result_payload, '$.version') = 1
            AND json_extract(ticket.pending_result_payload,
                '$.publishOperationId') = publish.id
            AND json_extract(ticket.pending_result_payload,
                '$.operationId') = publish.operation_id
            AND json_extract(ticket.pending_result_payload,
                '$.taskId') = NEW.task_id
            AND json_extract(ticket.pending_result_payload,
                '$.stageId') = NEW.local_development_stage_id
            AND json_extract(ticket.pending_result_payload,
                '$.disposition') = 'BASE_MOVED'
            AND json_extract(ticket.pending_result_payload,
                '$.observedBaseSha') = NEW.target_base_sha
            AND length(trim(json_extract(
                ticket.pending_result_payload, '$.error'))) > 0
            AND json_extract(ticket.pending_result_payload, '$.error') =
                ticket.pending_result_error
            AND EXISTS (
                SELECT 1 FROM task_policy_revision policy
                WHERE policy.id = NEW.standing_policy_revision_id
                  AND policy.id = authorization.policy_revision_id
                  AND policy.trunk_id = task.thread_id
                  AND policy.auto_approve = 1))
        OR (NEW.authority_kind = 'MANUAL' AND EXISTS (
              SELECT 1 FROM task_blocker blocker
              WHERE blocker.id = NEW.blocker_id
                AND blocker.task_id = NEW.task_id
                AND blocker.stage_id = NEW.local_development_stage_id
                AND blocker.owner_kind = 'STAGE'
                AND blocker.owner_id = NEW.local_development_stage_id
                AND blocker.subject_revision = publish.id
                AND blocker.blocker_type = 'LOCAL_PUBLISH_BASE_SYNC_REQUIRED'
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
                  AND receipt.remote_stage_id IS NULL)))
)
BEGIN SELECT RAISE(ABORT,
    'Local publish base sync requires its exact accepted base-move subject and authority'); END;

CREATE TRIGGER local_publish_base_sync_episode_identity_immutable
BEFORE UPDATE OF source_publish_operation_id, local_development_stage_id,
        task_id, task_epoch, stage_generation, source_code_fingerprint,
        source_head_sha, source_base_sha, target_base_sha, authority_kind,
        standing_policy_revision_id, blocker_id, actor,
        branch_sync_policy_revision_id, command_id, attempt_no,
        attempt_limit, opened_at_ms
ON local_publish_base_sync_episode
BEGIN SELECT RAISE(ABORT, 'Local publish base-sync identity is immutable'); END;

CREATE TRIGGER local_publish_base_sync_episode_terminal_immutable
BEFORE UPDATE ON local_publish_base_sync_episode
WHEN OLD.status IN ('HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'Terminal local publish base-sync episode is immutable'); END;

CREATE TRIGGER local_publish_base_sync_episode_transition
BEFORE UPDATE OF status ON local_publish_base_sync_episode
WHEN NOT (
    (OLD.status = 'FETCHING' AND NEW.status IN (
        'REBASING', 'FAILED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'REBASING' AND NEW.status IN (
        'RECONCILING', 'FAILED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'RECONCILING' AND NEW.status IN (
        'HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'Illegal local publish base-sync transition'); END;

CREATE TRIGGER local_publish_base_sync_operation_insert
BEFORE INSERT ON local_publish_base_sync_operation
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1
    FROM local_publish_base_sync_episode episode
    JOIN task_current_code_subject_v230 code ON code.task_id = episode.task_id
    WHERE episode.id = NEW.episode_id
      AND NEW.semantic_attempt = episode.attempt_no
      AND NEW.expected_code_fingerprint = episode.source_code_fingerprint
      AND NEW.expected_head_sha = episode.source_head_sha
      AND NEW.expected_base_sha = episode.source_base_sha
      AND NEW.target_base_sha = episode.target_base_sha
      AND code.code_fingerprint = NEW.expected_code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND ((NEW.kind = 'FETCH_COMPARE' AND episode.status = 'FETCHING'
              AND NOT EXISTS (
                  SELECT 1 FROM local_publish_base_sync_operation prior
                  WHERE prior.episode_id = episode.id))
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
                    AND receipt.acceptance = 'ACCEPTED')))
)
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync Operation requires its exact current subject'); END;

CREATE TRIGGER local_publish_base_sync_operation_identity_immutable
BEFORE UPDATE OF episode_id, kind, operation_id, semantic_attempt,
        expected_code_fingerprint, expected_head_sha, expected_base_sha,
        target_base_sha, requested_at_ms
ON local_publish_base_sync_operation
BEGIN SELECT RAISE(ABORT, 'Local publish base-sync Operation identity is immutable'); END;

CREATE TRIGGER local_publish_base_sync_operation_dispatch
BEFORE UPDATE OF status ON local_publish_base_sync_operation
WHEN NEW.status = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.operation_id = NEW.operation_id
      AND ticket.operation_kind = CASE NEW.kind
          WHEN 'FETCH_COMPARE' THEN 'FETCH_LOCAL_PUBLISH_BASE'
          WHEN 'MECHANICAL_REBASE' THEN 'REBASE_LOCAL_PUBLISH_BASE' END
      AND ticket.async_family = 'LOCAL_GIT'
      AND ticket.owner_kind = 'STAGE'
      AND ticket.owner_id = (
          SELECT episode.local_development_stage_id
          FROM local_publish_base_sync_episode episode
          WHERE episode.id = NEW.episode_id)
      AND ticket.callback_route = CASE NEW.kind
          WHEN 'FETCH_COMPARE' THEN 'LOCAL_PUBLISH_BASE_FETCH_RESULT'
          WHEN 'MECHANICAL_REBASE' THEN 'LOCAL_PUBLISH_BASE_REBASE_RESULT' END
      AND ticket.task_epoch = (
          SELECT episode.task_epoch FROM local_publish_base_sync_episode episode
          WHERE episode.id = NEW.episode_id)
      AND ticket.stage_id = (
          SELECT episode.local_development_stage_id
          FROM local_publish_base_sync_episode episode
          WHERE episode.id = NEW.episode_id)
      AND ticket.stage_generation = (
          SELECT episode.stage_generation
          FROM local_publish_base_sync_episode episode
          WHERE episode.id = NEW.episode_id)
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
BEGIN SELECT RAISE(ABORT, 'Illegal local publish base-sync Operation transition'); END;

CREATE TRIGGER local_publish_base_sync_operation_result
BEFORE UPDATE OF status ON local_publish_base_sync_operation
WHEN NEW.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'SUPERSEDED')
 AND (NOT EXISTS (
        SELECT 1 FROM dispatch_ticket ticket
        WHERE ticket.operation_id = OLD.operation_id
          AND ticket.status = 'RESULT_PENDING'
          AND ticket.pending_result_task_epoch = (
              SELECT episode.task_epoch
              FROM local_publish_base_sync_episode episode
              WHERE episode.id = OLD.episode_id)
          AND ticket.pending_result_stage_id = (
              SELECT episode.local_development_stage_id
              FROM local_publish_base_sync_episode episode
              WHERE episode.id = OLD.episode_id)
          AND ticket.pending_result_stage_generation = (
              SELECT episode.stage_generation
              FROM local_publish_base_sync_episode episode
              WHERE episode.id = OLD.episode_id)
          AND ticket.pending_result_operation_id = OLD.operation_id
          AND ticket.pending_result_attempt = OLD.semantic_attempt
          AND ticket.pending_result_expected_code_fingerprint = OLD.expected_code_fingerprint
          AND ticket.pending_result_expected_head_sha = OLD.expected_head_sha
          AND ticket.pending_result_expected_base_sha = OLD.expected_base_sha
          AND (NEW.status = 'SUPERSEDED'
            OR ticket.pending_result_outcome = NEW.status))
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
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'Terminal local publish base-sync Operation is immutable'); END;

CREATE TRIGGER dispatch_ticket_local_publish_base_sync_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind IN (
        'FETCH_LOCAL_PUBLISH_BASE', 'REBASE_LOCAL_PUBLISH_BASE')
  OR NEW.callback_route IN (
        'LOCAL_PUBLISH_BASE_FETCH_RESULT', 'LOCAL_PUBLISH_BASE_REBASE_RESULT')
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM local_publish_base_sync_operation operation
        JOIN local_publish_base_sync_episode episode
          ON episode.id = operation.episode_id
        JOIN tasks task ON task.id = episode.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE operation.operation_id = NEW.operation_id
          AND operation.semantic_attempt = NEW.attempt
          AND operation.expected_code_fingerprint = NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND NEW.operation_kind = CASE operation.kind
              WHEN 'FETCH_COMPARE' THEN 'FETCH_LOCAL_PUBLISH_BASE'
              WHEN 'MECHANICAL_REBASE' THEN 'REBASE_LOCAL_PUBLISH_BASE' END
          AND NEW.callback_route = CASE operation.kind
              WHEN 'FETCH_COMPARE' THEN 'LOCAL_PUBLISH_BASE_FETCH_RESULT'
              WHEN 'MECHANICAL_REBASE' THEN 'LOCAL_PUBLISH_BASE_REBASE_RESULT' END
          AND NEW.async_family = 'LOCAL_GIT'
          AND NEW.owner_kind = 'STAGE'
          AND NEW.owner_id = episode.local_development_stage_id
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.task_id = episode.task_id
          AND NEW.task_epoch = episode.task_epoch
          AND NEW.stage_id = episode.local_development_stage_id
          AND NEW.stage_generation = episode.stage_generation
          AND NEW.lane_mask = 16 AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1 AND NEW.writer_required = 1
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'Local publish base-sync DispatchTicket is not exact') END;
END;

CREATE TRIGGER local_publish_base_sync_delivery_receipt_immutable
BEFORE UPDATE ON local_publish_base_sync_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Local publish base-sync delivery receipt is immutable'); END;

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
        OR (NEW.acceptance = 'ACCEPTED'
              AND operation.status = NEW.raw_outcome
              AND task.workflow_version = 'V2'
              AND task.lifecycle_state = 'ACTIVE'
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

-- Rebuild the Local StageTurn subtype with supported DDL. Foreign-key actions
-- are disabled around the atomic replacement so CASCADE, SET NULL, and
-- SET DEFAULT inbound children retain their rows and values unchanged.
DROP TRIGGER IF EXISTS local_stage_turn_request_cancel;
DROP TRIGGER IF EXISTS local_stage_turn_request_identity_immutable;
DROP TRIGGER IF EXISTS local_stage_turn_request_insert;
DROP TRIGGER IF EXISTS local_stage_turn_request_failure_gate_v299;

CREATE TABLE local_stage_turn_request_v315 AS
SELECT id, command_id, stage_turn_id, task_id, local_development_stage_id,
       task_epoch, stage_generation, kind, queue_mode, predecessor_turn_id,
       brain_review_episode_id, local_feedback_batch_id, prompt_digest,
       requested_by, requested_at_ms, cancellation_requested_at_ms
FROM local_stage_turn_request;
DROP TABLE local_stage_turn_request;
CREATE TABLE local_stage_turn_request (
    id                         TEXT    NOT NULL PRIMARY KEY,
    command_id                 TEXT    NOT NULL UNIQUE,
    stage_turn_id              TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    kind                       TEXT    NOT NULL CHECK (kind IN (
        'IMPLEMENTATION', 'BRAIN_FINDINGS', 'LOCAL_FEEDBACK', 'STEERING',
        'BASE_SYNC')),
    queue_mode                 TEXT    NOT NULL CHECK (queue_mode IN (
        'IMMEDIATE', 'APPEND', 'CANCEL_AND_REPLACE')),
    predecessor_turn_id        TEXT REFERENCES stage_turn(id),
    brain_review_episode_id    TEXT REFERENCES brain_review_episode(id),
    local_feedback_batch_id    TEXT REFERENCES local_feedback_batch(id),
    base_sync_episode_id       TEXT REFERENCES local_publish_base_sync_episode(id),
    target_base_sha            TEXT,
    prompt_digest              TEXT    NOT NULL,
    requested_by               TEXT    NOT NULL,
    requested_at_ms            INTEGER NOT NULL,
    cancellation_requested_at_ms INTEGER,
    CHECK (length(prompt_digest) = 64 AND length(requested_by) > 0),
    CHECK ((queue_mode = 'IMMEDIATE') = (predecessor_turn_id IS NULL)),
    CHECK ((kind = 'BRAIN_FINDINGS') = (brain_review_episode_id IS NOT NULL)),
    CHECK ((kind = 'LOCAL_FEEDBACK') = (local_feedback_batch_id IS NOT NULL)),
    CHECK ((kind = 'BASE_SYNC') = (base_sync_episode_id IS NOT NULL
        AND target_base_sha IS NOT NULL))
);
INSERT INTO local_stage_turn_request(
    id, command_id, stage_turn_id, task_id, local_development_stage_id,
    task_epoch, stage_generation, kind, queue_mode, predecessor_turn_id,
    brain_review_episode_id, local_feedback_batch_id, prompt_digest,
    requested_by, requested_at_ms, cancellation_requested_at_ms)
SELECT id, command_id, stage_turn_id, task_id, local_development_stage_id,
       task_epoch, stage_generation, kind, queue_mode, predecessor_turn_id,
       brain_review_episode_id, local_feedback_batch_id, prompt_digest,
       requested_by, requested_at_ms, cancellation_requested_at_ms
FROM local_stage_turn_request_v315;
DROP TABLE local_stage_turn_request_v315;

CREATE TABLE local_publish_base_sync_fk_assert_v315 (
    value INTEGER NOT NULL CHECK (value = 1)
);
INSERT INTO local_publish_base_sync_fk_assert_v315(value)
VALUES ((SELECT CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END
         FROM pragma_foreign_key_check));
DROP TABLE local_publish_base_sync_fk_assert_v315;

CREATE TABLE local_publish_base_sync_integrity_assert_v315 (
    value TEXT NOT NULL CHECK (value = 'ok')
);
INSERT INTO local_publish_base_sync_integrity_assert_v315(value)
SELECT integrity_check FROM pragma_integrity_check;
DROP TABLE local_publish_base_sync_integrity_assert_v315;

CREATE TRIGGER local_stage_turn_request_cancel
BEFORE UPDATE OF cancellation_requested_at_ms ON local_stage_turn_request
WHEN OLD.cancellation_requested_at_ms IS NOT NULL
  OR NEW.cancellation_requested_at_ms IS NULL
  OR NEW.cancellation_requested_at_ms < OLD.requested_at_ms
  OR NOT EXISTS (
      SELECT 1 FROM local_stage_turn_request replacement
      WHERE replacement.predecessor_turn_id = OLD.stage_turn_id
        AND replacement.queue_mode = 'CANCEL_AND_REPLACE')
BEGIN SELECT RAISE(ABORT, 'Local StageTurn cancellation lacks its exact replacement'); END;

CREATE TRIGGER local_stage_turn_request_identity_immutable
BEFORE UPDATE OF id, command_id, stage_turn_id, task_id,
        local_development_stage_id, task_epoch, stage_generation, kind,
        queue_mode, predecessor_turn_id, brain_review_episode_id,
        local_feedback_batch_id, base_sync_episode_id, target_base_sha,
        prompt_digest, requested_by, requested_at_ms
ON local_stage_turn_request
BEGIN SELECT RAISE(ABORT, 'Local StageTurn request identity is immutable'); END;

CREATE TRIGGER local_stage_turn_request_insert
BEFORE INSERT ON local_stage_turn_request
BEGIN
    SELECT CASE WHEN (NEW.kind = 'BASE_SYNC'
                AND (NEW.base_sync_episode_id IS NULL
                    OR NEW.target_base_sha IS NULL))
            OR (NEW.kind <> 'BASE_SYNC'
                AND (NEW.base_sync_episode_id IS NOT NULL
                    OR NEW.target_base_sha IS NOT NULL))
        THEN RAISE(ABORT, 'Local StageTurn base-sync subtype is incomplete') END;
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM stage_turn turn
            JOIN stage owner ON owner.id = turn.stage_id
            JOIN tasks task ON task.id = owner.task_id
            JOIN task_current_stage current ON current.task_id = task.id
            JOIN task_current_code_subject_v230 code ON code.task_id = task.id
            WHERE turn.id = NEW.stage_turn_id
              AND turn.stage_id = NEW.local_development_stage_id
              AND turn.stage_generation = NEW.stage_generation
              AND turn.task_epoch = NEW.task_epoch AND turn.status = 'QUEUED'
              AND turn.expected_code_fingerprint = code.code_fingerprint
              AND turn.expected_head_sha = code.head_sha
              AND turn.expected_base_sha = code.base_sha
              AND task.id = NEW.task_id AND task.workflow_version = 'V2'
              AND task.lifecycle_state = 'ACTIVE' AND task.epoch = NEW.task_epoch
              AND current.stage_id = owner.id
              AND current.stage_generation = owner.generation
              AND owner.kind = 'LOCAL_DEVELOPMENT'
              AND owner.generation = NEW.stage_generation
              AND owner.completed_at_ms IS NULL
              AND ((NEW.kind = 'IMPLEMENTATION'
                    AND turn.purpose = 'IMPLEMENT_LOCAL_PLAN')
                OR (NEW.kind = 'BRAIN_FINDINGS'
                    AND turn.purpose = 'ADDRESS_BRAIN_FINDINGS')
                OR (NEW.kind = 'LOCAL_FEEDBACK'
                    AND turn.purpose = 'ADDRESS_LOCAL_FEEDBACK')
                OR (NEW.kind = 'STEERING' AND turn.purpose = 'USER_STEERING')
                OR (NEW.kind = 'BASE_SYNC'
                    AND turn.purpose = 'BASE_SYNC')))
            THEN RAISE(ABORT, 'Local StageTurn request owner or subject is stale')
        WHEN NEW.predecessor_turn_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM local_stage_turn_request previous
            JOIN stage_turn turn ON turn.id = previous.stage_turn_id
            WHERE previous.stage_turn_id = NEW.predecessor_turn_id
              AND previous.task_id = NEW.task_id
              AND previous.local_development_stage_id = NEW.local_development_stage_id
              AND previous.task_epoch = NEW.task_epoch
              AND previous.stage_generation = NEW.stage_generation
              AND (turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                OR (turn.status = 'SUCCEEDED' AND EXISTS (
                    SELECT 1 FROM typed_user_wait_result result
                    WHERE result.operation_id = turn.operation_id
                      AND result.owner_kind = 'STAGE_TURN'
                      AND result.turn_id = turn.id))))
            THEN RAISE(ABORT, 'Local StageTurn predecessor is not exact')
        WHEN NEW.brain_review_episode_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM brain_review_episode episode
            WHERE episode.id = NEW.brain_review_episode_id
              AND episode.task_id = NEW.task_id
              AND episode.local_development_stage_id = NEW.local_development_stage_id
              AND episode.task_epoch = NEW.task_epoch
              AND episode.stage_generation = NEW.stage_generation
              AND episode.status = 'SUCCEEDED'
              AND episode.verdict = 'CHANGES_REQUESTED')
            THEN RAISE(ABORT, 'Brain-finding Turn lacks its exact verdict')
        WHEN NEW.local_feedback_batch_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM local_feedback_batch batch
            WHERE batch.id = NEW.local_feedback_batch_id
              AND batch.task_id = NEW.task_id
              AND batch.local_development_stage_id = NEW.local_development_stage_id
              AND batch.task_epoch = NEW.task_epoch
              AND batch.stage_generation = NEW.stage_generation
              AND batch.status IN ('FROZEN', 'QUEUED', 'DISPATCHED'))
            THEN RAISE(ABORT, 'Local-feedback Turn lacks its exact batch')
        WHEN NEW.kind = 'BASE_SYNC' AND NOT EXISTS (
            SELECT 1
            FROM local_publish_base_sync_episode episode
            JOIN local_publish_base_sync_operation rebase
              ON rebase.episode_id = episode.id
            JOIN local_publish_base_sync_delivery_receipt receipt
              ON receipt.operation_row_id = rebase.id
            JOIN stage_turn turn ON turn.id = NEW.stage_turn_id
            WHERE episode.id = NEW.base_sync_episode_id
              AND episode.task_id = NEW.task_id
              AND episode.local_development_stage_id = NEW.local_development_stage_id
              AND episode.task_epoch = NEW.task_epoch
              AND episode.stage_generation = NEW.stage_generation
              AND episode.target_base_sha = NEW.target_base_sha
              AND episode.status IN ('RECONCILING', 'HANDED_OFF')
              AND rebase.kind = 'MECHANICAL_REBASE'
              AND rebase.status = 'SUCCEEDED'
              AND rebase.result_disposition IN ('REBASED', 'CONFLICT')
              AND receipt.acceptance = 'ACCEPTED'
              AND ((rebase.result_disposition = 'REBASED'
                    AND turn.expected_code_fingerprint = rebase.result_code_fingerprint
                    AND turn.expected_head_sha = rebase.result_head_sha
                    AND turn.expected_base_sha = episode.target_base_sha)
                OR (rebase.result_disposition = 'CONFLICT'
                    AND turn.expected_code_fingerprint = episode.source_code_fingerprint
                    AND turn.expected_head_sha = episode.source_head_sha
                    AND turn.expected_base_sha = episode.source_base_sha)))
            THEN RAISE(ABORT, 'Base-sync Turn lacks its exact rebase result')
    END;
END;

CREATE TRIGGER local_stage_turn_request_failure_gate_v315
BEFORE INSERT ON local_stage_turn_request
WHEN EXISTS (
    SELECT 1 FROM task_blocker blocker
    WHERE blocker.task_id = NEW.task_id
      AND blocker.stage_id = NEW.local_development_stage_id
      AND blocker.owner_kind = 'STAGE'
      AND blocker.owner_id = NEW.local_development_stage_id
      AND blocker.blocker_type = 'OPERATION_FAILED'
      AND blocker.status = 'OPEN')
  AND NOT EXISTS (
    SELECT 1
    FROM local_stage_turn_failure_v298 failure
    JOIN task_blocker blocker ON blocker.id = failure.blocker_id
    JOIN stage_turn previous ON previous.id = failure.stage_turn_id
    JOIN local_stage_turn_request previous_request
      ON previous_request.stage_turn_id = previous.id
    JOIN stage_turn replacement ON replacement.id = NEW.stage_turn_id
    WHERE failure.task_id = NEW.task_id
      AND failure.stage_id = NEW.local_development_stage_id
      AND failure.stage_generation = NEW.stage_generation
      AND blocker.task_id = NEW.task_id
      AND blocker.stage_id = NEW.local_development_stage_id
      AND blocker.owner_kind = 'STAGE'
      AND blocker.owner_id = NEW.local_development_stage_id
      AND blocker.subject_revision = previous.id
      AND blocker.blocker_type = 'OPERATION_FAILED'
      AND blocker.status = 'OPEN'
      AND previous.status = 'FAILED'
      AND replacement.stage_id = previous.stage_id
      AND replacement.stage_generation = previous.stage_generation
      AND replacement.task_epoch = previous.task_epoch
      AND replacement.purpose = previous.purpose
      AND replacement.status = 'QUEUED'
      AND replacement.attempt = previous.attempt + 1
      AND replacement.expected_code_fingerprint = previous.expected_code_fingerprint
      AND replacement.expected_head_sha = previous.expected_head_sha
      AND replacement.expected_base_sha = previous.expected_base_sha
      AND NEW.kind = previous_request.kind
      AND NEW.queue_mode = 'IMMEDIATE'
      AND NEW.predecessor_turn_id IS NULL
      AND NEW.brain_review_episode_id IS previous_request.brain_review_episode_id
      AND NEW.local_feedback_batch_id IS previous_request.local_feedback_batch_id
      AND NEW.base_sync_episode_id IS previous_request.base_sync_episode_id
      AND NEW.target_base_sha IS previous_request.target_base_sha
      AND NOT EXISTS (
          SELECT 1 FROM local_stage_turn_retry_v298 retry
          WHERE retry.failure_id = failure.id
             OR retry.blocker_id = blocker.id
             OR retry.predecessor_turn_id = previous.id))
BEGIN SELECT RAISE(ABORT,
    'Local StageTurn admission requires exact failure recovery'); END;

CREATE TABLE local_publish_base_sync_start_receipt (
    id                          TEXT    NOT NULL PRIMARY KEY,
    cause                       TEXT    NOT NULL CHECK (cause = 'START_LOCAL_BASE_SYNC'),
    episode_id                  TEXT    NOT NULL UNIQUE
        REFERENCES local_publish_base_sync_episode(id) ON DELETE CASCADE,
    stage_turn_request_id       TEXT    NOT NULL UNIQUE
        REFERENCES local_stage_turn_request(id),
    command_id                  TEXT    NOT NULL UNIQUE,
    actor                       TEXT    NOT NULL CHECK (length(trim(actor)) > 0),
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    expected_stage_version      INTEGER NOT NULL CHECK (expected_stage_version >= 0),
    returned_stage_version      INTEGER NOT NULL,
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    expected_code_fingerprint   TEXT    NOT NULL,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    target_base_sha             TEXT    NOT NULL,
    recorded_at_ms              INTEGER NOT NULL,
    CHECK (returned_stage_version = expected_stage_version + 1)
);

CREATE TRIGGER local_publish_base_sync_start_receipt_immutable
BEFORE UPDATE ON local_publish_base_sync_start_receipt
BEGIN SELECT RAISE(ABORT, 'Local publish base-sync start receipt is immutable'); END;

CREATE TRIGGER local_publish_base_sync_start_receipt_insert
BEFORE INSERT ON local_publish_base_sync_start_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM local_publish_base_sync_episode episode
    JOIN local_stage_turn_request request
      ON request.id = NEW.stage_turn_request_id
    JOIN stage_turn turn ON turn.id = request.stage_turn_id
    JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
    JOIN stage owner ON owner.id = episode.local_development_stage_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage_transition transition
      ON transition.stage_id = owner.id AND transition.command_id = NEW.command_id
    WHERE episode.id = NEW.episode_id
      AND episode.status = 'RECONCILING'
      AND episode.task_id = NEW.task_id
      AND episode.local_development_stage_id = NEW.local_development_stage_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.stage_generation = NEW.stage_generation
      AND episode.target_base_sha = NEW.target_base_sha
      AND request.command_id = NEW.command_id
      AND request.kind = 'BASE_SYNC'
      AND request.base_sync_episode_id = episode.id
      AND request.target_base_sha = NEW.target_base_sha
      AND turn.operation_id = NEW.operation_id
      AND turn.attempt = NEW.semantic_attempt
      AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND turn.expected_head_sha = NEW.expected_head_sha
      AND turn.expected_base_sha = NEW.expected_base_sha
      AND turn.status = 'QUEUED'
      AND ticket.status = 'REQUESTED'
      AND ticket.task_id = NEW.task_id
      AND ticket.task_epoch = NEW.task_epoch
      AND ticket.stage_id = NEW.local_development_stage_id
      AND ticket.stage_generation = NEW.stage_generation
      AND ticket.attempt = NEW.semantic_attempt
      AND ticket.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND ticket.expected_head_sha = NEW.expected_head_sha
      AND ticket.expected_base_sha = NEW.expected_base_sha
      AND task.lifecycle_state = 'ACTIVE' AND task.epoch = NEW.task_epoch
      AND current.stage_id = owner.id
      AND current.stage_generation = owner.generation
      AND owner.version = NEW.returned_stage_version
      AND owner.checkpoint = 'IMPLEMENTING'
      AND transition.from_checkpoint = 'LOCAL_REVIEW'
      AND transition.to_checkpoint = 'IMPLEMENTING'
      AND transition.stage_version = NEW.returned_stage_version
      AND transition.cause = 'START_LOCAL_BASE_SYNC'
      AND transition.actor = NEW.actor
)
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync start receipt lacks its exact Turn and transition'); END;

CREATE TRIGGER local_publish_base_sync_dev_report_output
BEFORE INSERT ON dev_report
WHEN EXISTS (
    SELECT 1
    FROM local_stage_turn_request request
    WHERE request.stage_turn_id = NEW.stage_turn_id
      AND request.kind = 'BASE_SYNC'
      AND NEW.base_sha <> request.target_base_sha)
BEGIN SELECT RAISE(ABORT,
    'Base-sync DevelopmentReport must use the frozen target base'); END;

-- A clean rebase becomes the current worktree subject until its BASE_SYNC
-- StageTurn produces a DevReport. Conflict leaves the old source current.
DROP VIEW task_current_code_subject_v230;
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
      AND receipt.acceptance = 'ACCEPTED'
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
          AND receipt.acceptance = 'ACCEPTED'
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
        SELECT 1 FROM local_publish_base_sync_operation operation
        JOIN local_publish_base_sync_delivery_receipt receipt
          ON receipt.operation_row_id = operation.id
        WHERE operation.episode_id = NEW.id
          AND operation.kind = 'FETCH_COMPARE'
          AND operation.status = 'SUCCEEDED'
          AND operation.result_disposition = 'FETCHED'
          AND receipt.acceptance = 'ACCEPTED'))
  OR (NEW.status = 'RECONCILING' AND NOT EXISTS (
        SELECT 1 FROM local_publish_base_sync_operation operation
        JOIN local_publish_base_sync_delivery_receipt receipt
          ON receipt.operation_row_id = operation.id
        WHERE operation.episode_id = NEW.id
          AND operation.kind = 'MECHANICAL_REBASE'
          AND operation.status = 'SUCCEEDED'
          AND operation.result_disposition IN ('REBASED', 'CONFLICT')
          AND receipt.acceptance = 'ACCEPTED'))
  OR (NEW.status = 'HANDED_OFF' AND NOT EXISTS (
        SELECT 1 FROM local_publish_base_sync_start_receipt receipt
        WHERE receipt.episode_id = NEW.id))
BEGIN SELECT RAISE(ABORT,
    'Local publish base-sync progress lacks exact durable proof'); END;

-- Cleanup step 2 cannot prove asynchronous work quiescent while this
-- pre-publish episode still owns a live cursor.
CREATE TRIGGER cleanup_step_attempt_result_local_publish_base_sync_v315
BEFORE INSERT ON cleanup_step_attempt_result
WHEN NEW.outcome = 'SUCCEEDED'
  AND NEW.ordinal = 2
  AND EXISTS (
      SELECT 1
      FROM local_publish_base_sync_episode episode
      WHERE episode.task_id = NEW.task_id
        AND episode.task_epoch = NEW.task_epoch
        AND episode.status NOT IN (
            'HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED'))
BEGIN SELECT RAISE(ABORT,
    'Cleanup cannot settle a live local publish base-sync episode'); END;

-- Recreate the purge projection through ordinary DDL so this migration's
-- physical connection immediately compiles the new liveness term.
DROP VIEW v2_trunk_purge_state_v269;
CREATE VIEW v2_trunk_purge_state_v269 AS
SELECT trunk.id AS trunk_id,
       trunk.lifecycle_state,
       trunk.aggregate_version,
       (SELECT COUNT(*)
        FROM tasks task
        WHERE task.thread_id = trunk.id
          AND task.workflow_version = 'V2'
          AND task.lifecycle_state NOT IN (
              'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
           AS nonterminal_task_count,
       (SELECT COUNT(*)
        FROM tasks task
        WHERE task.thread_id = trunk.id
          AND task.workflow_version = 'V2'
          AND NOT EXISTS (
              SELECT 1
              FROM task_outcome outcome
              JOIN cleanup_operation operation
                ON operation.id = outcome.cleanup_operation_id
              JOIN cleanup_stage cleanup
                ON cleanup.stage_id = outcome.cleanup_stage_id
              JOIN stage owner ON owner.id = cleanup.stage_id
              JOIN dispatch_ticket ticket
                ON ticket.id = operation.dispatch_ticket_id
              WHERE outcome.task_id = task.id
                AND outcome.trunk_id = trunk.id
                AND outcome.task_epoch = task.epoch
                AND outcome.terminal_reason = task.lifecycle_state
                AND operation.task_id = task.id
                AND operation.task_epoch = task.epoch
                AND operation.cleanup_stage_id = cleanup.stage_id
                AND operation.status = 'COMPLETED'
                AND cleanup.task_id = task.id
                AND cleanup.task_epoch = task.epoch
                AND cleanup.terminal_reason = outcome.terminal_reason
                AND owner.task_id = task.id
                AND owner.kind = 'CLEANUP'
                AND owner.generation = cleanup.generation
                AND owner.checkpoint = 'COMPLETED'
                AND owner.completed_at_ms IS NOT NULL
                AND ticket.trunk_id = trunk.id
                AND ticket.task_id = task.id
                AND ticket.task_epoch = task.epoch
                AND ticket.status = 'SUCCEEDED'
                AND ticket.delivery_acceptance = 'ACCEPTED'))
           AS incomplete_cleanup_count,
       ((SELECT COUNT(*)
         FROM thread_question question
         JOIN thread_turn turn ON turn.id = question.turn_id
         WHERE turn.trunk_id = trunk.id
           AND (question.state = 'OPEN'
             OR question.continuation_state = 'READY'))
        + (SELECT COUNT(*)
           FROM task_question question
           JOIN task_turn turn ON turn.id = question.turn_id
           JOIN tasks task ON task.id = turn.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND (question.state = 'OPEN'
               OR question.continuation_state = 'READY'))
        + (SELECT COUNT(*)
           FROM stage_question question
           JOIN stage_turn turn ON turn.id = question.turn_id
           JOIN stage owner ON owner.id = turn.stage_id
           JOIN tasks task ON task.id = owner.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND (question.state = 'OPEN'
               OR question.continuation_state = 'READY'))
        + (SELECT COUNT(*)
           FROM review_assignment_question question
           JOIN review_assignment_turn turn ON turn.id = question.turn_id
           JOIN review_assignment assignment
             ON assignment.id = turn.assignment_id
           JOIN review_round round ON round.id = assignment.round_id
           JOIN review_session session ON session.id = round.session_id
           WHERE (session.owner_thread_id = trunk.id
               OR EXISTS (
                   SELECT 1 FROM tasks task
                   WHERE task.id = session.owner_task_id
                     AND task.thread_id = trunk.id
                     AND task.workflow_version = 'V2'))
             AND (question.state = 'OPEN'
               OR question.continuation_state = 'READY'))
        + (SELECT COUNT(*)
           FROM permission_request permission
           WHERE (permission.state = 'OPEN'
               OR permission.continuation_state = 'READY')
             AND ((permission.turn_kind = 'THREAD' AND EXISTS (
                      SELECT 1 FROM thread_turn turn
                      WHERE turn.id = permission.turn_id
                        AND turn.trunk_id = trunk.id))
               OR (permission.turn_kind = 'TASK' AND EXISTS (
                      SELECT 1
                      FROM task_turn turn
                      JOIN tasks task ON task.id = turn.task_id
                      WHERE turn.id = permission.turn_id
                        AND task.thread_id = trunk.id
                        AND task.workflow_version = 'V2'))
               OR (permission.turn_kind = 'STAGE' AND EXISTS (
                      SELECT 1
                      FROM stage_turn turn
                      JOIN stage owner ON owner.id = turn.stage_id
                      JOIN tasks task ON task.id = owner.task_id
                      WHERE turn.id = permission.turn_id
                        AND task.thread_id = trunk.id
                        AND task.workflow_version = 'V2'))
               OR (permission.turn_kind = 'REVIEW_ASSIGNMENT' AND EXISTS (
                      SELECT 1
                      FROM review_assignment_turn turn
                      JOIN review_assignment assignment
                        ON assignment.id = turn.assignment_id
                      JOIN review_round round
                        ON round.id = assignment.round_id
                      JOIN review_session session
                        ON session.id = round.session_id
                      WHERE turn.id = permission.turn_id
                        AND (session.owner_thread_id = trunk.id
                          OR EXISTS (
                              SELECT 1 FROM tasks task
                              WHERE task.id = session.owner_task_id
                                AND task.thread_id = trunk.id
                                AND task.workflow_version = 'V2')))))))
           AS open_wait_count,
       ((SELECT COUNT(*) FROM thread_turn turn
         WHERE turn.trunk_id = trunk.id
           AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
        + (SELECT COUNT(*)
           FROM task_turn turn
           JOIN tasks task ON task.id = turn.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
        + (SELECT COUNT(*)
           FROM stage_turn turn
           JOIN stage owner ON owner.id = turn.stage_id
           JOIN tasks task ON task.id = owner.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
        + (SELECT COUNT(*)
           FROM review_assignment_turn turn
           JOIN review_assignment assignment
             ON assignment.id = turn.assignment_id
           JOIN review_round round ON round.id = assignment.round_id
           JOIN review_session session ON session.id = round.session_id
           WHERE turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
             AND (session.owner_thread_id = trunk.id
               OR EXISTS (
                   SELECT 1 FROM tasks task
                   WHERE task.id = session.owner_task_id
                     AND task.thread_id = trunk.id
                     AND task.workflow_version = 'V2'))))
           AS live_turn_count,
       (SELECT COUNT(*) FROM dispatch_ticket ticket
        WHERE ticket.trunk_id = trunk.id
          AND ticket.status IN (
              'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
              'CLAIMED', 'RUNNING', 'DELIVERING'))
           AS live_ticket_count,
       (SELECT COUNT(*)
        FROM agent_execution execution
        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
        WHERE ticket.trunk_id = trunk.id
          AND execution.status IN ('STARTING', 'RUNNING', 'UNKNOWN'))
           AS live_execution_count,
       ((SELECT COUNT(*)
         FROM planning_base_refresh_operation operation
         WHERE operation.trunk_id = trunk.id
           AND (operation.status = 'REQUESTED'
             OR (operation.status = 'SUCCEEDED'
               AND operation.launch_disposition = 'PENDING')))
        + (SELECT COALESCE(SUM(
                live.active_plan_review_count
              + live.active_validation_count
              + live.active_brain_episode_count
              + live.active_provision_operation_count
              + live.active_quiescence_count
              + live.active_replan_count
              + live.active_feedback_batch_count
              + live.active_publish_operation_count
              + live.unreconciled_publish_operation_count
              + live.active_publish_effect_count
              + live.active_publish_authorization_count), 0)
           FROM task_live_work_counts_v230 live
           JOIN tasks task ON task.id = live.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2')
        + (SELECT COUNT(*) FROM stage_steering_request_v257 request
           JOIN tasks task ON task.id = request.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND request.status = 'PENDING')
        + (SELECT COUNT(*) FROM local_review_agent_request request
           JOIN tasks task ON task.id = request.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND request.status = 'REQUESTED')
        + (SELECT COUNT(*) FROM remote_observation_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN ('REQUESTED', 'DISPATCHED'))
        + (SELECT COUNT(*) FROM ci_repair_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN ('REQUESTED', 'DISPATCHED'))
        + (SELECT COUNT(*) FROM branch_sync_dispatch_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN (
                 'REQUESTED', 'DISPATCHED', 'INDETERMINATE'))
        + (SELECT COUNT(*)
           FROM remote_feedback_validation_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN ('REQUESTED', 'DISPATCHED'))
        + (SELECT COUNT(*) FROM remote_feedback_brain_episode episode
           JOIN tasks task ON task.id = episode.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND episode.status = 'REQUESTED')
        + (SELECT COUNT(*) FROM remote_feedback_batch batch
           JOIN tasks task ON task.id = batch.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND batch.status NOT IN ('COMPLETED', 'SUPERSEDED'))
        + (SELECT COUNT(*) FROM ci_repair_episode episode
           JOIN tasks task ON task.id = episode.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED'))
        + (SELECT COUNT(*) FROM branch_sync_episode episode
           JOIN tasks task ON task.id = episode.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND episode.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED'))
        + (SELECT COUNT(*) FROM remote_mark_ready_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status IN (
                 'REQUESTED', 'CLAIMED', 'AWAITING_OBSERVATION',
                 'INDETERMINATE'))
        + (SELECT COUNT(*) FROM remote_merge_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status NOT IN (
                 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED'))
        + (SELECT COUNT(*) FROM remote_mark_ready_authorization authorization
           JOIN tasks task ON task.id = authorization.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND authorization.status = 'ACTIVE')
        + (SELECT COUNT(*) FROM remote_merge_authorization authorization
           JOIN tasks task ON task.id = authorization.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND authorization.status = 'ACTIVE')
        + (SELECT COUNT(*) FROM cleanup_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status <> 'COMPLETED')
        + (SELECT COUNT(*) FROM cleanup_step_retry_request request
           JOIN tasks task ON task.id = request.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND request.status = 'PENDING')
        + (SELECT COUNT(*)
           FROM local_publish_base_sync_episode episode
           JOIN tasks task ON task.id = episode.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND episode.status NOT IN (
                 'HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        + (SELECT COUNT(*) FROM task_outcome_summary_operation operation
           JOIN tasks task ON task.id = operation.task_id
           WHERE task.thread_id = trunk.id
             AND task.workflow_version = 'V2'
             AND operation.status = 'REQUESTED')
        + (SELECT COUNT(*) FROM trunk_outcome_inbox inbox
           WHERE inbox.trunk_id = trunk.id AND inbox.status = 'PENDING')
        + (SELECT COUNT(*)
           FROM outbox event
           JOIN dispatch_ticket ticket ON ticket.id = event.aggregate_id
           WHERE event.aggregate_kind = 'DISPATCH_TICKET'
             AND event.status IN ('PENDING', 'CLAIMED')
             AND ticket.trunk_id = trunk.id))
           AS live_operation_count,
       (SELECT COUNT(*)
        FROM stage owner
        JOIN tasks task ON task.id = owner.task_id
        WHERE task.thread_id = trunk.id
          AND task.workflow_version = 'V2'
          AND owner.completed_at_ms IS NULL)
           AS incomplete_stage_count,
       ((SELECT COUNT(*) FROM capacity_lease lease
         WHERE lease.workflow_source = 'V2'
           AND lease.trunk_id = trunk.id
           AND lease.released_at_ms IS NULL)
        + (SELECT COUNT(*)
           FROM worktree_leases lease
           JOIN tasks task ON task.id = lease.task_id
           WHERE lease.workflow_version = 'V2'
             AND task.thread_id = trunk.id
             AND task.workflow_version = 'V2'))
           AS live_lease_count
FROM threads trunk
WHERE trunk.turn_version = 'V2';

RELEASE SAVEPOINT local_publish_base_sync_v315;
PRAGMA foreign_keys = ON;
