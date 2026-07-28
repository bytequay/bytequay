-- Durable Stage-owned steering. A request exists before its successor Turn;
-- waiting requests therefore consume neither a DispatchTicket nor capacity.

CREATE TABLE stage_steering_request_v257 (
    id                         TEXT    NOT NULL PRIMARY KEY,
    command_id                 TEXT    NOT NULL UNIQUE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_id                   TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_kind                 TEXT    NOT NULL CHECK (stage_kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT')),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    accepted_stage_version     INTEGER NOT NULL CHECK (accepted_stage_version >= 0),
    accepted_checkpoint        TEXT    NOT NULL,
    mode                       TEXT    NOT NULL CHECK (mode IN (
        'APPEND', 'CANCEL_AND_REPLACE')),
    body                       TEXT    NOT NULL,
    content_digest             TEXT    NOT NULL CHECK (length(content_digest) = 64),
    predecessor_owner_kind     TEXT,
    predecessor_owner_id       TEXT,
    predecessor_purpose        TEXT,
    predecessor_operation_id   TEXT,
    predecessor_ticket_id      TEXT REFERENCES dispatch_ticket(id),
    predecessor_attempt        INTEGER CHECK (predecessor_attempt > 0),
    predecessor_code_fingerprint TEXT,
    predecessor_head_sha       TEXT,
    predecessor_base_sha       TEXT,
    cancel_intent_at_ms        INTEGER,
    status                     TEXT    NOT NULL CHECK (status IN (
        'PENDING', 'ADMITTED', 'SUPERSEDED')),
    successor_owner_kind       TEXT,
    successor_owner_id         TEXT,
    successor_operation_id     TEXT,
    terminal_reason            TEXT,
    requested_by               TEXT    NOT NULL CHECK (length(requested_by) > 0),
    requested_at_ms            INTEGER NOT NULL CHECK (requested_at_ms >= 0),
    admitted_at_ms             INTEGER,
    CHECK ((predecessor_operation_id IS NULL
            AND predecessor_owner_kind IS NULL
            AND predecessor_owner_id IS NULL
            AND predecessor_purpose IS NULL
            AND predecessor_ticket_id IS NULL
            AND predecessor_attempt IS NULL
            AND predecessor_code_fingerprint IS NULL
            AND predecessor_head_sha IS NULL
            AND predecessor_base_sha IS NULL)
        OR (predecessor_operation_id IS NOT NULL
            AND predecessor_owner_kind IS NOT NULL
            AND predecessor_owner_id IS NOT NULL
            AND predecessor_purpose IS NOT NULL
            AND predecessor_ticket_id IS NOT NULL
            AND predecessor_attempt IS NOT NULL)),
    CHECK ((mode = 'CANCEL_AND_REPLACE') = (cancel_intent_at_ms IS NOT NULL)),
    CHECK (mode <> 'CANCEL_AND_REPLACE' OR predecessor_operation_id IS NOT NULL),
    CHECK ((status = 'PENDING'
            AND successor_owner_kind IS NULL
            AND successor_owner_id IS NULL
            AND successor_operation_id IS NULL
            AND admitted_at_ms IS NULL
            AND terminal_reason IS NULL)
        OR (status = 'ADMITTED'
            AND successor_owner_kind IS NOT NULL
            AND successor_owner_id IS NOT NULL
            AND successor_operation_id IS NOT NULL
            AND admitted_at_ms IS NOT NULL
            AND terminal_reason IS NULL)
        OR (status = 'SUPERSEDED'
            AND successor_owner_kind IS NULL
            AND successor_owner_id IS NULL
            AND successor_operation_id IS NULL
            AND admitted_at_ms IS NULL
            AND terminal_reason IS NOT NULL)),
    FOREIGN KEY (stage_id, task_id, stage_kind, stage_generation)
        REFERENCES stage(id, task_id, kind, generation) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_stage_steering_pending_v257
    ON stage_steering_request_v257(status, requested_at_ms, id);
CREATE UNIQUE INDEX idx_stage_steering_cancel_predecessor_v257
    ON stage_steering_request_v257(predecessor_operation_id)
    WHERE status = 'PENDING' AND mode = 'CANCEL_AND_REPLACE';

CREATE TRIGGER stage_steering_request_insert_v257
BEFORE INSERT ON stage_steering_request_v257
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'PENDING'
            THEN RAISE(ABORT, 'Stage steering must start pending')
        WHEN NOT EXISTS (
            SELECT 1
            FROM stage owner
            JOIN tasks task ON task.id = owner.task_id
            JOIN task_current_stage current ON current.task_id = task.id
            WHERE owner.id = NEW.stage_id
              AND owner.task_id = NEW.task_id
              AND owner.kind = NEW.stage_kind
              AND owner.generation = NEW.stage_generation
              AND owner.version = NEW.accepted_stage_version
              AND owner.checkpoint = NEW.accepted_checkpoint
              AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
              AND task.workflow_version = 'V2'
              AND task.lifecycle_state = 'ACTIVE'
              AND task.epoch = NEW.task_epoch
              AND current.stage_id = owner.id
              AND current.stage_generation = owner.generation)
            THEN RAISE(ABORT, 'Stage steering owner fence is stale')
        WHEN NEW.predecessor_operation_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM dispatch_ticket ticket
            WHERE ticket.id = NEW.predecessor_ticket_id
              AND ticket.operation_id = NEW.predecessor_operation_id
              AND ticket.owner_kind = NEW.predecessor_owner_kind
              AND ticket.owner_id = NEW.predecessor_owner_id
              AND ticket.task_id = NEW.task_id
              AND ticket.task_epoch = NEW.task_epoch
              AND ticket.stage_id = NEW.stage_id
              AND ticket.stage_generation = NEW.stage_generation
              AND ticket.attempt = NEW.predecessor_attempt
              AND ticket.expected_code_fingerprint
                    IS NEW.predecessor_code_fingerprint
              AND ticket.expected_head_sha IS NEW.predecessor_head_sha
              AND ticket.expected_base_sha IS NEW.predecessor_base_sha
              AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')
              AND ((ticket.owner_kind = 'STAGE_TURN' AND EXISTS (
                      SELECT 1 FROM stage_turn turn
                      WHERE turn.id = ticket.owner_id
                        AND turn.operation_id = ticket.operation_id
                        AND turn.purpose = NEW.predecessor_purpose))
                OR (ticket.owner_kind = 'TASK_TURN' AND EXISTS (
                      SELECT 1 FROM task_turn turn
                      WHERE turn.id = ticket.owner_id
                        AND turn.operation_id = ticket.operation_id
                        AND turn.purpose = NEW.predecessor_purpose))
                OR (ticket.owner_kind NOT IN ('STAGE_TURN', 'TASK_TURN')
                    AND ticket.operation_kind = NEW.predecessor_purpose)))
            THEN RAISE(ABORT, 'Stage steering predecessor fence is stale')
    END;
END;

CREATE TRIGGER stage_steering_identity_immutable_v257
BEFORE UPDATE OF id, command_id, task_id, task_epoch, stage_id, stage_kind,
        stage_generation, accepted_stage_version, accepted_checkpoint, mode,
        body, content_digest, predecessor_owner_kind, predecessor_owner_id,
        predecessor_operation_id, predecessor_ticket_id, predecessor_attempt,
        predecessor_purpose,
        predecessor_code_fingerprint, predecessor_head_sha,
        predecessor_base_sha, cancel_intent_at_ms, requested_by,
        requested_at_ms
ON stage_steering_request_v257
BEGIN SELECT RAISE(ABORT, 'Stage steering identity is immutable'); END;

CREATE TRIGGER stage_steering_transition_v257
BEFORE UPDATE OF status ON stage_steering_request_v257
WHEN OLD.status <> 'PENDING' OR NEW.status NOT IN ('ADMITTED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'illegal Stage steering transition'); END;

CREATE TRIGGER stage_steering_terminal_immutable_v257
BEFORE UPDATE ON stage_steering_request_v257
WHEN OLD.status IN ('ADMITTED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'terminal Stage steering is immutable'); END;

CREATE TABLE stage_steering_attachment_v257 (
    request_id     TEXT    NOT NULL REFERENCES stage_steering_request_v257(id)
        ON DELETE CASCADE,
    position       INTEGER NOT NULL CHECK (position > 0),
    media_type     TEXT    NOT NULL CHECK (length(media_type) > 0),
    content_ref    TEXT    NOT NULL CHECK (length(content_ref) > 0),
    content_digest TEXT    NOT NULL CHECK (length(content_digest) = 64),
    PRIMARY KEY (request_id, position),
    UNIQUE (request_id, content_ref)
);

CREATE TRIGGER stage_steering_attachment_immutable_v257
BEFORE UPDATE ON stage_steering_attachment_v257
BEGIN SELECT RAISE(ABORT, 'Stage steering attachment is immutable'); END;

-- Remote steering never guesses which loop owns the next writer Turn. It is
-- parked against the exact predecessor purpose for that owner to consume.
CREATE TABLE remote_stage_steering_handoff_v257 (
    request_id              TEXT NOT NULL PRIMARY KEY
        REFERENCES stage_steering_request_v257(id) ON DELETE CASCADE,
    owner_family            TEXT NOT NULL CHECK (owner_family IN (
        'CI_REPAIR', 'BRANCH_REPAIR', 'REMOTE_FEEDBACK')),
    owner_purpose           TEXT NOT NULL,
    predecessor_turn_id     TEXT NOT NULL REFERENCES stage_turn(id),
    predecessor_operation_id TEXT NOT NULL UNIQUE,
    status                  TEXT NOT NULL CHECK (status IN ('PARKED', 'CONSUMED')),
    successor_turn_id       TEXT REFERENCES stage_turn(id),
    consumed_at_ms          INTEGER,
    CHECK ((status = 'PARKED' AND successor_turn_id IS NULL
            AND consumed_at_ms IS NULL)
        OR (status = 'CONSUMED' AND successor_turn_id IS NOT NULL
            AND consumed_at_ms IS NOT NULL))
);

CREATE TRIGGER remote_stage_steering_handoff_insert_v257
BEFORE INSERT ON remote_stage_steering_handoff_v257
WHEN NEW.status <> 'PARKED' OR NOT EXISTS (
    SELECT 1
    FROM stage_steering_request_v257 request
    JOIN stage_turn turn ON turn.id = request.predecessor_owner_id
    WHERE request.id = NEW.request_id AND request.status = 'PENDING'
      AND request.stage_kind = 'REMOTE_DEVELOPMENT'
      AND request.predecessor_owner_kind = 'STAGE_TURN'
      AND request.predecessor_owner_id = NEW.predecessor_turn_id
      AND request.predecessor_operation_id = NEW.predecessor_operation_id
      AND request.predecessor_purpose = NEW.owner_purpose
      AND turn.operation_id = NEW.predecessor_operation_id
      AND NEW.owner_family = CASE NEW.owner_purpose
          WHEN 'REMOTE_CI_REPAIR' THEN 'CI_REPAIR'
          WHEN 'BRANCH_CONFLICT_REPAIR' THEN 'BRANCH_REPAIR'
          WHEN 'ADDRESS_REMOTE_FEEDBACK' THEN 'REMOTE_FEEDBACK'
          ELSE NULL END)
BEGIN SELECT RAISE(ABORT, 'Remote steering handoff owner is not exact'); END;

CREATE TRIGGER remote_stage_steering_handoff_identity_v257
BEFORE UPDATE OF request_id, owner_family, owner_purpose,
        predecessor_turn_id, predecessor_operation_id
ON remote_stage_steering_handoff_v257
BEGIN SELECT RAISE(ABORT, 'Remote steering handoff identity is immutable'); END;

CREATE TRIGGER remote_stage_steering_handoff_transition_v257
BEFORE UPDATE OF status ON remote_stage_steering_handoff_v257
WHEN OLD.status <> 'PARKED' OR NEW.status <> 'CONSUMED'
BEGIN SELECT RAISE(ABORT, 'illegal Remote steering handoff transition'); END;

CREATE TRIGGER remote_stage_steering_handoff_terminal_v257
BEFORE UPDATE ON remote_stage_steering_handoff_v257
WHEN OLD.status = 'CONSUMED'
BEGIN SELECT RAISE(ABORT, 'consumed Remote steering handoff is immutable'); END;

-- CI and branch repair own their steering successor through the same typed
-- repair runtime.  This row is durable work, not a coordinator wake-up: the
-- StageTurn and DispatchTicket are inserted in the same transaction.
CREATE TABLE remote_repair_steering_turn_v257 (
    request_id              TEXT NOT NULL PRIMARY KEY
        REFERENCES stage_steering_request_v257(id) ON DELETE CASCADE,
    owner_family            TEXT NOT NULL CHECK (owner_family IN (
        'CI_REPAIR', 'BRANCH_REPAIR')),
    ci_repair_episode_id    TEXT REFERENCES ci_repair_episode(id),
    branch_sync_episode_id  TEXT REFERENCES branch_sync_episode(id),
    branch_sync_step_id     TEXT REFERENCES branch_sync_effect_step(id),
    stage_turn_id           TEXT NOT NULL UNIQUE REFERENCES stage_turn(id),
    operation_id            TEXT NOT NULL UNIQUE,
    dispatch_ticket_id      TEXT NOT NULL UNIQUE REFERENCES dispatch_ticket(id)
        DEFERRABLE INITIALLY DEFERRED,
    semantic_attempt        INTEGER NOT NULL CHECK (semantic_attempt > 0),
    status                  TEXT NOT NULL CHECK (status IN (
        'REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    result_code_fingerprint TEXT,
    result_head_sha         TEXT,
    result_summary          TEXT,
    requested_at_ms         INTEGER NOT NULL,
    completed_at_ms         INTEGER,
    error_message           TEXT,
    CHECK ((owner_family = 'CI_REPAIR'
            AND ci_repair_episode_id IS NOT NULL
            AND branch_sync_episode_id IS NULL
            AND branch_sync_step_id IS NULL)
        OR (owner_family = 'BRANCH_REPAIR'
            AND ci_repair_episode_id IS NULL
            AND branch_sync_episode_id IS NOT NULL
            AND branch_sync_step_id IS NOT NULL)),
    CHECK ((status = 'REQUESTED') = (completed_at_ms IS NULL)),
    CHECK (status <> 'SUCCEEDED' OR (
        result_code_fingerprint IS NOT NULL AND result_head_sha IS NOT NULL
        AND result_summary IS NOT NULL))
);

CREATE TRIGGER remote_repair_steering_turn_insert_v257
BEFORE INSERT ON remote_repair_steering_turn_v257
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1
    FROM remote_stage_steering_handoff_v257 handoff
    JOIN stage_steering_request_v257 request
      ON request.id = handoff.request_id
    JOIN stage_turn turn ON turn.id = NEW.stage_turn_id
    JOIN dispatch_ticket predecessor
      ON predecessor.id = request.predecessor_ticket_id
    JOIN tasks task ON task.id = request.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE handoff.request_id = NEW.request_id
      AND handoff.status = 'PARKED'
      AND handoff.owner_family = NEW.owner_family
      AND request.status = 'PENDING'
      AND request.stage_kind = 'REMOTE_DEVELOPMENT'
      AND request.predecessor_owner_kind = 'STAGE_TURN'
      AND predecessor.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = request.task_epoch
      AND current.stage_id = request.stage_id
      AND current.stage_generation = request.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = request.stage_generation
      AND owner.completed_at_ms IS NULL
      AND turn.stage_id = request.stage_id
      AND turn.stage_generation = request.stage_generation
      AND turn.task_epoch = request.task_epoch
      AND turn.operation_id = NEW.operation_id
      AND turn.attempt = NEW.semantic_attempt
      AND turn.purpose = request.predecessor_purpose
      AND turn.status = 'QUEUED'
      AND turn.expected_code_fingerprint = code.code_fingerprint
      AND turn.expected_head_sha = code.head_sha
      AND turn.expected_base_sha = code.base_sha
      AND ((NEW.owner_family = 'CI_REPAIR' AND EXISTS (
          SELECT 1 FROM ci_repair_operation operation
          JOIN ci_repair_episode episode
            ON episode.id = operation.ci_repair_episode_id
          WHERE operation.operation_id = request.predecessor_operation_id
            AND operation.stage_turn_id = request.predecessor_owner_id
            AND operation.status IN (
                'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
            AND episode.id = NEW.ci_repair_episode_id
            AND episode.status = 'FIXING'))
        OR (NEW.owner_family = 'BRANCH_REPAIR' AND EXISTS (
          SELECT 1 FROM branch_sync_dispatch_operation operation
          JOIN branch_sync_episode episode
            ON episode.id = operation.branch_sync_episode_id
          JOIN branch_sync_effect_step step
            ON step.id = operation.branch_sync_effect_step_id
          WHERE operation.operation_id = request.predecessor_operation_id
            AND operation.stage_turn_id = request.predecessor_owner_id
            AND operation.status IN (
                'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
            AND episode.id = NEW.branch_sync_episode_id
            AND step.id = NEW.branch_sync_step_id
            AND step.status = 'CLAIMED'
            AND step.claim_owner = request.predecessor_operation_id))))
BEGIN SELECT RAISE(ABORT, 'Remote repair steering successor is not exact'); END;

CREATE TRIGGER remote_repair_steering_turn_identity_v257
BEFORE UPDATE OF request_id, owner_family, ci_repair_episode_id,
        branch_sync_episode_id, branch_sync_step_id, stage_turn_id,
        operation_id, dispatch_ticket_id, semantic_attempt, requested_at_ms
ON remote_repair_steering_turn_v257
BEGIN SELECT RAISE(ABORT, 'Remote repair steering identity is immutable'); END;

CREATE TRIGGER remote_repair_steering_turn_transition_v257
BEFORE UPDATE OF status ON remote_repair_steering_turn_v257
WHEN OLD.status <> 'REQUESTED' OR NEW.status NOT IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'illegal Remote repair steering transition'); END;

CREATE TRIGGER remote_repair_steering_turn_terminal_v257
BEFORE UPDATE ON remote_repair_steering_turn_v257
WHEN OLD.status <> 'REQUESTED'
BEGIN SELECT RAISE(ABORT, 'terminal Remote repair steering is immutable'); END;

CREATE TRIGGER dispatch_ticket_remote_repair_steering_v257
BEFORE INSERT ON dispatch_ticket
WHEN NEW.callback_route = 'REMOTE_REPAIR_STEERING_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM remote_repair_steering_turn_v257 operation
        JOIN stage_turn turn ON turn.id = operation.stage_turn_id
        JOIN stage_steering_request_v257 request
          ON request.id = operation.request_id
        WHERE operation.operation_id = NEW.operation_id
          AND operation.dispatch_ticket_id = NEW.id
          AND operation.stage_turn_id = NEW.owner_id
          AND operation.semantic_attempt = NEW.attempt
          AND request.task_id = NEW.task_id
          AND request.task_epoch = NEW.task_epoch
          AND request.stage_id = NEW.stage_id
          AND request.stage_generation = NEW.stage_generation
          AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
          AND turn.expected_head_sha = NEW.expected_head_sha
          AND turn.expected_base_sha = NEW.expected_base_sha
          AND NEW.operation_kind = 'EXECUTE_STAGE_TURN'
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'STAGE_TURN'
          AND NEW.lane_mask IN (1, 2)
          AND NEW.trunk_control = 0 AND NEW.exclusive_task = 1
          AND NEW.writer_required = 1 AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Remote repair steering ticket is not exact') END;
END;

CREATE TABLE remote_repair_steering_delivery_v257 (
    request_id       TEXT NOT NULL PRIMARY KEY
        REFERENCES remote_repair_steering_turn_v257(request_id) ON DELETE CASCADE,
    operation_id     TEXT NOT NULL UNIQUE,
    raw_outcome      TEXT NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest TEXT NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance       TEXT NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    recorded_at_ms   INTEGER NOT NULL
);

CREATE TRIGGER remote_repair_steering_delivery_immutable_v257
BEFORE UPDATE ON remote_repair_steering_delivery_v257
BEGIN SELECT RAISE(ABORT, 'Remote repair steering delivery is immutable'); END;

CREATE TABLE remote_steering_code_subject_v257 (
    request_id              TEXT NOT NULL PRIMARY KEY
        REFERENCES remote_repair_steering_turn_v257(request_id) ON DELETE CASCADE,
    task_id                 TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch              INTEGER NOT NULL,
    remote_stage_id         TEXT NOT NULL REFERENCES remote_development_stage(stage_id),
    stage_generation        INTEGER NOT NULL,
    stage_turn_id           TEXT NOT NULL UNIQUE REFERENCES stage_turn(id),
    source_code_fingerprint TEXT NOT NULL,
    source_head_sha         TEXT NOT NULL,
    source_base_sha         TEXT NOT NULL,
    code_fingerprint        TEXT NOT NULL,
    head_sha                TEXT NOT NULL,
    base_sha                TEXT NOT NULL,
    recorded_at_ms          INTEGER NOT NULL
);

CREATE TRIGGER remote_steering_code_subject_insert_v257
BEFORE INSERT ON remote_steering_code_subject_v257
WHEN NOT EXISTS (
    SELECT 1 FROM remote_repair_steering_turn_v257 operation
    JOIN stage_steering_request_v257 request ON request.id = operation.request_id
    JOIN stage_turn turn ON turn.id = operation.stage_turn_id
    WHERE operation.request_id = NEW.request_id
      AND operation.status = 'SUCCEEDED'
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND request.task_id = NEW.task_id AND request.task_epoch = NEW.task_epoch
      AND request.stage_id = NEW.remote_stage_id
      AND request.stage_generation = NEW.stage_generation
      AND turn.id = NEW.stage_turn_id AND turn.status = 'SUCCEEDED'
      AND turn.expected_code_fingerprint = NEW.source_code_fingerprint
      AND turn.expected_head_sha = NEW.source_head_sha
      AND turn.expected_base_sha = NEW.source_base_sha
      AND NEW.base_sha = NEW.source_base_sha)
BEGIN SELECT RAISE(ABORT, 'Remote steering code subject lacks exact result'); END;

CREATE TRIGGER remote_steering_code_subject_immutable_v257
BEFORE UPDATE ON remote_steering_code_subject_v257
BEGIN SELECT RAISE(ABORT, 'Remote steering code subject is immutable'); END;

DROP VIEW task_current_code_subject_v230;
CREATE VIEW task_current_code_subject_v230 AS
SELECT task.id AS task_id,
       COALESCE(CASE WHEN steering.recorded_at_ms >=
                         COALESCE(worktree.recorded_at_ms, -1)
                     THEN steering.code_fingerprint END,
                worktree.code_fingerprint,
                remote.code_fingerprint, report.code_fingerprint,
                code.code_fingerprint) AS code_fingerprint,
       COALESCE(CASE WHEN steering.recorded_at_ms >=
                         COALESCE(worktree.recorded_at_ms, -1)
                     THEN steering.head_sha END,
                worktree.head_sha, remote.head_sha,
                report.head_sha, code.local_head_sha) AS head_sha,
       COALESCE(CASE WHEN steering.recorded_at_ms >=
                         COALESCE(worktree.recorded_at_ms, -1)
                     THEN steering.base_sha END,
                worktree.base_sha, remote.base_sha,
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
LEFT JOIN remote_worktree_subject worktree ON worktree.id = (
    SELECT candidate.id FROM remote_worktree_subject candidate
    WHERE candidate.task_id = task.id AND candidate.task_epoch = task.epoch
    ORDER BY candidate.revision DESC LIMIT 1)
LEFT JOIN remote_steering_code_subject_v257 steering
  ON steering.request_id = (
    SELECT candidate.request_id FROM remote_steering_code_subject_v257 candidate
    WHERE candidate.task_id = task.id AND candidate.task_epoch = task.epoch
    ORDER BY candidate.recorded_at_ms DESC, candidate.request_id DESC LIMIT 1)
WHERE task.workflow_version = 'V2';

-- A canceled feedback Turn has no repair result.  Its exact durable steering
-- handoff is the only additional evidence that may admit the next attempt.
DROP TRIGGER remote_feedback_stage_turn_request_insert;
CREATE TRIGGER remote_feedback_stage_turn_request_insert
BEFORE INSERT ON remote_feedback_stage_turn_request
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_feedback_batch batch
    JOIN remote_development_stage remote
      ON remote.stage_id = batch.remote_development_stage_id
    JOIN tasks task ON task.id = batch.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = remote.stage_id
    JOIN stage_turn turn ON turn.id = NEW.stage_turn_id
    WHERE batch.id = NEW.remote_feedback_batch_id
      AND batch.status IN ('FROZEN', 'ADDRESSING')
      AND batch.task_id = NEW.task_id
      AND batch.task_epoch = NEW.task_epoch
      AND batch.stage_generation = NEW.stage_generation
      AND batch.remote_development_stage_id = NEW.remote_development_stage_id
      AND remote.current_head_sha = batch.head_sha
      AND remote.current_base_sha = batch.base_sha
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE' AND task.epoch = NEW.task_epoch
      AND current.stage_id = remote.stage_id
      AND current.stage_generation = remote.generation
      AND owner.checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
      AND owner.completed_at_ms IS NULL
      AND turn.stage_id = remote.stage_id
      AND turn.stage_generation = remote.generation
      AND turn.task_epoch = NEW.task_epoch
      AND turn.purpose = 'ADDRESS_REMOTE_FEEDBACK'
      AND turn.attempt = NEW.semantic_attempt
      AND turn.expected_base_sha = batch.base_sha
      AND turn.status = 'QUEUED'
      AND ((NEW.semantic_attempt = 1 AND NEW.predecessor_turn_id IS NULL
            AND turn.expected_head_sha = batch.head_sha)
        OR (NEW.semantic_attempt > 1 AND EXISTS (
            SELECT 1 FROM remote_feedback_stage_turn_request previous
            JOIN stage_turn previous_turn
              ON previous_turn.id = previous.stage_turn_id
            JOIN remote_feedback_repair_result previous_repair
              ON previous_repair.repair_stage_turn_id = previous.stage_turn_id
            WHERE previous.remote_feedback_batch_id = batch.id
              AND previous.semantic_attempt = NEW.semantic_attempt - 1
              AND previous.stage_turn_id = NEW.predecessor_turn_id
              AND turn.expected_head_sha = previous_repair.proposed_head_sha
              AND turn.expected_code_fingerprint =
                  previous_repair.code_fingerprint
              AND previous_turn.status IN (
                  'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')))
        OR (NEW.semantic_attempt > 1 AND EXISTS (
            SELECT 1 FROM remote_feedback_stage_turn_request previous
            JOIN stage_turn previous_turn
              ON previous_turn.id = previous.stage_turn_id
            JOIN stage_steering_request_v257 steering
              ON steering.predecessor_owner_id = previous.stage_turn_id
             AND steering.predecessor_operation_id = previous_turn.operation_id
            JOIN remote_stage_steering_handoff_v257 handoff
              ON handoff.request_id = steering.id
            WHERE previous.remote_feedback_batch_id = batch.id
              AND previous.semantic_attempt = NEW.semantic_attempt - 1
              AND previous.stage_turn_id = NEW.predecessor_turn_id
              AND steering.status = 'PENDING'
              AND steering.stage_id = NEW.remote_development_stage_id
              AND steering.stage_generation = NEW.stage_generation
              AND steering.predecessor_purpose = 'ADDRESS_REMOTE_FEEDBACK'
              AND handoff.owner_family = 'REMOTE_FEEDBACK'
              AND handoff.status = 'PARKED'
              AND previous_turn.status IN (
                  'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
              AND turn.expected_head_sha = previous_turn.expected_head_sha
              AND turn.expected_code_fingerprint =
                  previous_turn.expected_code_fingerprint))))
BEGIN SELECT RAISE(ABORT, 'Remote feedback StageTurn lacks its exact batch and owner'); END;

-- One owner-specific projection is needed when Local Review admits steering
-- by moving to ADDRESSING_LOCAL_FEEDBACK and arming its StageTurn atomically.
CREATE TABLE stage_steering_transition_receipt_v257 (
    id                                TEXT    NOT NULL PRIMARY KEY,
    stage_id                          TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause = 'ADMIT_LOCAL_STEERING'),
    actor                             TEXT    NOT NULL CHECK (length(actor) > 0),
    disposition                       TEXT    NOT NULL CHECK (disposition IN ('APPLIED', 'SUPERSEDED')),
    expected_task_epoch               INTEGER CHECK (expected_task_epoch > 0),
    expected_stage_generation         INTEGER CHECK (expected_stage_generation > 0),
    expected_stage_version            INTEGER CHECK (expected_stage_version >= 0),
    source_checkpoint                 TEXT,
    subject_task_epoch                INTEGER CHECK (subject_task_epoch > 0),
    subject_stage_id                  TEXT,
    subject_stage_generation          INTEGER,
    subject_operation_id              TEXT,
    subject_attempt                   INTEGER,
    subject_expected_code_fingerprint TEXT,
    subject_expected_head_sha         TEXT,
    subject_expected_base_sha         TEXT,
    proof_id                          TEXT    NOT NULL CHECK (length(proof_id) > 0),
    returned_kind                     TEXT    NOT NULL CHECK (returned_kind = 'LOCAL_DEVELOPMENT'),
    returned_generation               INTEGER NOT NULL CHECK (returned_generation > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version >= 0),
    returned_checkpoint               TEXT    NOT NULL CHECK (returned_checkpoint = 'ADDRESSING_LOCAL_FEEDBACK'),
    returned_end_reason               TEXT,
    returned_pending_task_epoch       INTEGER CHECK (returned_pending_task_epoch > 0),
    returned_pending_stage_id         TEXT,
    returned_pending_stage_generation INTEGER,
    returned_pending_operation_id     TEXT,
    returned_pending_attempt          INTEGER,
    returned_pending_code_fingerprint TEXT,
    returned_pending_head_sha         TEXT,
    returned_pending_base_sha         TEXT,
    recorded_at_ms                    INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    UNIQUE (stage_id, command_id),
    CHECK (expected_task_epoch IS NOT NULL
        AND expected_stage_generation IS NOT NULL
        AND expected_stage_version IS NOT NULL
        AND source_checkpoint = 'LOCAL_REVIEW'),
    CHECK (subject_operation_id IS NOT NULL
        AND subject_task_epoch = expected_task_epoch
        AND subject_stage_id = stage_id
        AND subject_stage_generation = returned_generation
        AND subject_attempt > 0),
    CHECK (returned_pending_operation_id = subject_operation_id
        AND returned_pending_task_epoch = subject_task_epoch
        AND returned_pending_stage_id = subject_stage_id
        AND returned_pending_stage_generation = subject_stage_generation
        AND returned_pending_attempt = subject_attempt),
    CHECK (returned_end_reason IS NULL),
    FOREIGN KEY (stage_id, task_id, returned_kind, returned_generation)
        REFERENCES stage(id, task_id, kind, generation) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER stage_steering_transition_receipt_insert_v257
BEFORE INSERT ON stage_steering_transition_receipt_v257
WHEN NEW.disposition = 'APPLIED' AND NOT EXISTS (
    SELECT 1
    FROM stage_transition transition
    JOIN local_stage_turn_request request ON request.id = NEW.proof_id
    JOIN stage_turn turn ON turn.id = request.stage_turn_id
    JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
    WHERE transition.stage_id = NEW.stage_id
      AND transition.command_id = NEW.command_id
      AND transition.generation = NEW.returned_generation
      AND transition.from_checkpoint = 'LOCAL_REVIEW'
      AND transition.to_checkpoint = 'ADDRESSING_LOCAL_FEEDBACK'
      AND transition.stage_version = NEW.returned_version
      AND transition.cause = NEW.cause
      AND transition.actor = NEW.actor
      AND request.task_id = NEW.task_id
      AND request.local_development_stage_id = NEW.stage_id
      AND request.task_epoch = NEW.expected_task_epoch
      AND request.stage_generation = NEW.returned_generation
      AND request.kind = 'STEERING'
      AND request.queue_mode = 'IMMEDIATE'
      AND turn.operation_id = NEW.subject_operation_id
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Local steering transition lacks exact durable work'); END;

CREATE TRIGGER stage_steering_transition_receipt_immutable_v257
BEFORE UPDATE ON stage_steering_transition_receipt_v257
BEGIN SELECT RAISE(ABORT, 'Stage steering transition receipt is immutable'); END;

-- Resume acceptance belongs to the exact Stage owner, but it deliberately
-- does not mutate Stage state or create work while Task is RESUMING.
CREATE TABLE stage_resume_rearm_intent_v257 (
    handoff_id          TEXT    NOT NULL PRIMARY KEY
        REFERENCES task_resume_handoff_v256(id) ON DELETE CASCADE,
    owner_proof_id      TEXT    NOT NULL UNIQUE,
    accepted_by         TEXT    NOT NULL CHECK (length(accepted_by) > 0),
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch          INTEGER NOT NULL CHECK (task_epoch > 0),
    task_version        INTEGER NOT NULL CHECK (task_version > 0),
    stage_id            TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_kind          TEXT    NOT NULL CHECK (stage_kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT')),
    stage_generation    INTEGER NOT NULL CHECK (stage_generation > 0),
    stage_version       INTEGER NOT NULL CHECK (stage_version >= 0),
    restore_checkpoint  TEXT    NOT NULL,
    reconciliation_id   TEXT    NOT NULL,
    code_fingerprint    TEXT    NOT NULL CHECK (length(code_fingerprint) > 0),
    head_sha            TEXT    NOT NULL CHECK (length(head_sha) > 0),
    base_sha            TEXT    NOT NULL CHECK (length(base_sha) > 0),
    status              TEXT    NOT NULL CHECK (status IN ('PENDING', 'MATERIALIZED')),
    accepted_at_ms      INTEGER NOT NULL CHECK (accepted_at_ms >= 0),
    materialized_at_ms  INTEGER,
    CHECK ((status = 'PENDING') = (materialized_at_ms IS NULL)),
    FOREIGN KEY (stage_id, task_id, stage_kind, stage_generation)
        REFERENCES stage(id, task_id, kind, generation) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER stage_resume_rearm_intent_insert_v257
BEFORE INSERT ON stage_resume_rearm_intent_v257
WHEN NEW.status <> 'PENDING' OR NOT EXISTS (
    SELECT 1
    FROM task_resume_handoff_v256 handoff
    JOIN tasks task ON task.id = handoff.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE handoff.id = NEW.handoff_id AND handoff.status = 'PENDING'
      AND handoff.task_id = NEW.task_id
      AND handoff.task_epoch = NEW.task_epoch
      AND handoff.task_version = NEW.task_version
      AND handoff.reconciliation_id = NEW.reconciliation_id
      AND handoff.stage_id = NEW.stage_id
      AND handoff.stage_kind = NEW.stage_kind
      AND handoff.stage_generation = NEW.stage_generation
      AND handoff.stage_version = NEW.stage_version
      AND handoff.restore_checkpoint = NEW.restore_checkpoint
      AND handoff.code_fingerprint = NEW.code_fingerprint
      AND handoff.head_sha = NEW.head_sha AND handoff.base_sha = NEW.base_sha
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'RESUMING'
      AND task.epoch = NEW.task_epoch
      AND task.aggregate_version = NEW.task_version
      AND current.stage_id = NEW.stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.kind = NEW.stage_kind
      AND owner.generation = NEW.stage_generation
      AND owner.version = NEW.stage_version
      AND owner.checkpoint = NEW.restore_checkpoint
      AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
      AND code.code_fingerprint = NEW.code_fingerprint
      AND code.head_sha = NEW.head_sha AND code.base_sha = NEW.base_sha)
BEGIN SELECT RAISE(ABORT, 'Resume rearm intent fence is stale'); END;

CREATE TRIGGER stage_resume_rearm_identity_immutable_v257
BEFORE UPDATE OF handoff_id, owner_proof_id, accepted_by, task_id, task_epoch,
        task_version, stage_id, stage_kind, stage_generation, stage_version,
        restore_checkpoint, reconciliation_id, code_fingerprint, head_sha,
        base_sha, accepted_at_ms
ON stage_resume_rearm_intent_v257
BEGIN SELECT RAISE(ABORT, 'Resume rearm intent identity is immutable'); END;

CREATE TRIGGER stage_resume_rearm_transition_v257
BEFORE UPDATE OF status ON stage_resume_rearm_intent_v257
WHEN OLD.status <> 'PENDING' OR NEW.status <> 'MATERIALIZED'
  OR NEW.materialized_at_ms IS NULL
BEGIN SELECT RAISE(ABORT, 'illegal resume rearm transition'); END;

CREATE TRIGGER stage_resume_rearm_terminal_immutable_v257
BEFORE UPDATE ON stage_resume_rearm_intent_v257
WHEN OLD.status = 'MATERIALIZED'
BEGIN SELECT RAISE(ABORT, 'materialized resume rearm intent is immutable'); END;
