-- Executable Remote Development feedback and mark-ready protocols.
-- V233 freezes the domain facts.  This migration adds only the typed work
-- requests and payloads needed to execute those facts through DispatchTicket.

CREATE TABLE remote_feedback_stage_turn_request (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id    TEXT    NOT NULL REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    stage_turn_id               TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    remote_development_stage_id TEXT    NOT NULL REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    predecessor_turn_id         TEXT REFERENCES stage_turn(id),
    prompt_digest               TEXT    NOT NULL CHECK (length(prompt_digest) = 64),
    requested_by                TEXT    NOT NULL CHECK (length(requested_by) > 0),
    requested_at_ms             INTEGER NOT NULL,
    UNIQUE (remote_feedback_batch_id, semantic_attempt)
);

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
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
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
            JOIN stage_turn previous_turn ON previous_turn.id = previous.stage_turn_id
            JOIN remote_feedback_repair_result previous_repair
              ON previous_repair.repair_stage_turn_id = previous.stage_turn_id
            WHERE previous.remote_feedback_batch_id = batch.id
              AND previous.semantic_attempt = NEW.semantic_attempt - 1
              AND previous.stage_turn_id = NEW.predecessor_turn_id
              AND turn.expected_head_sha = previous_repair.proposed_head_sha
              AND previous_turn.status IN ('SUCCEEDED', 'FAILED', 'CANCELED'))))
)
BEGIN SELECT RAISE(ABORT, 'Remote feedback StageTurn lacks its exact batch and owner'); END;

CREATE TRIGGER remote_feedback_stage_turn_request_immutable
BEFORE UPDATE ON remote_feedback_stage_turn_request
BEGIN SELECT RAISE(ABORT, 'Remote feedback StageTurn request is immutable'); END;

CREATE TRIGGER dispatch_ticket_remote_feedback_turn_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'EXECUTE_STAGE_TURN'
  AND EXISTS (
      SELECT 1 FROM stage owner
      WHERE owner.id = NEW.stage_id AND owner.kind = 'REMOTE_DEVELOPMENT')
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM remote_feedback_stage_turn_request request
        JOIN stage_turn turn ON turn.id = request.stage_turn_id
        JOIN remote_feedback_batch batch ON batch.id = request.remote_feedback_batch_id
        WHERE request.stage_turn_id = NEW.owner_id
          AND turn.operation_id = NEW.operation_id
          AND turn.attempt = NEW.attempt
          AND turn.task_epoch = NEW.task_epoch
          AND turn.stage_id = NEW.stage_id
          AND turn.stage_generation = NEW.stage_generation
          AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
          AND turn.expected_head_sha = NEW.expected_head_sha
          AND turn.expected_base_sha = NEW.expected_base_sha
          AND batch.status IN ('FROZEN', 'ADDRESSING')
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'STAGE_TURN'
          AND NEW.callback_route = 'REMOTE_FEEDBACK_TURN_RESULT'
          AND NEW.lane_mask IN (1, 2)
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 1
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Remote feedback StageTurn ticket is not exact') END;
END;

CREATE TABLE remote_feedback_repair_result (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id    TEXT    NOT NULL REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    repair_stage_turn_id        TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id TEXT    NOT NULL REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    subject_head_sha            TEXT    NOT NULL,
    proposed_head_sha           TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    code_fingerprint            TEXT    NOT NULL,
    summary                     TEXT    NOT NULL,
    result_digest               TEXT    NOT NULL CHECK (length(result_digest) = 64),
    completed_at_ms             INTEGER NOT NULL,
    CHECK (length(summary) > 0),
    UNIQUE (remote_feedback_batch_id, repair_stage_turn_id)
);

CREATE TRIGGER remote_feedback_repair_result_insert
BEFORE INSERT ON remote_feedback_repair_result
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_batch batch
    JOIN remote_feedback_stage_turn_request request
      ON request.remote_feedback_batch_id = batch.id
    JOIN stage_turn turn ON turn.id = request.stage_turn_id
    JOIN remote_development_stage remote
      ON remote.stage_id = batch.remote_development_stage_id
    JOIN tasks task ON task.id = batch.task_id
    WHERE batch.id = NEW.remote_feedback_batch_id
      AND batch.status = 'ADDRESSING'
      AND request.stage_turn_id = NEW.repair_stage_turn_id
      AND request.semantic_attempt = (
          SELECT MAX(latest.semantic_attempt)
          FROM remote_feedback_stage_turn_request latest
          WHERE latest.remote_feedback_batch_id = batch.id)
      AND turn.status = 'SUCCEEDED'
      AND batch.task_id = NEW.task_id
      AND batch.task_epoch = NEW.task_epoch
      AND batch.remote_development_stage_id = NEW.remote_development_stage_id
      AND batch.stage_generation = NEW.stage_generation
      AND batch.head_sha = NEW.subject_head_sha
      AND batch.base_sha = NEW.base_sha
      AND remote.current_head_sha = NEW.subject_head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch
      AND turn.expected_base_sha = NEW.base_sha)
BEGIN SELECT RAISE(ABORT, 'Remote feedback repair result is stale or unowned'); END;

CREATE TRIGGER remote_feedback_repair_result_immutable
BEFORE UPDATE ON remote_feedback_repair_result
BEGIN SELECT RAISE(ABORT, 'Remote feedback repair result is immutable'); END;

CREATE TABLE remote_feedback_reply_draft (
    id                       TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id TEXT    NOT NULL REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    repair_result_id         TEXT    NOT NULL REFERENCES remote_feedback_repair_result(id) ON DELETE CASCADE,
    batch_item_ordinal       INTEGER NOT NULL CHECK (batch_item_ordinal > 0),
    kind                     TEXT    NOT NULL CHECK (kind IN (
        'POST_INLINE_REPLY', 'POST_TOP_LEVEL_REPLY', 'RESOLVE_THREAD')),
    body                     TEXT,
    body_digest              TEXT,
    external_target          TEXT,
    ordinal                  INTEGER NOT NULL CHECK (ordinal > 0),
    CHECK ((kind = 'RESOLVE_THREAD' AND body IS NULL AND body_digest IS NULL)
        OR (kind <> 'RESOLVE_THREAD' AND body IS NOT NULL
            AND body_digest IS NOT NULL AND length(body) > 0)),
    UNIQUE (repair_result_id, ordinal),
    UNIQUE (repair_result_id, batch_item_ordinal, kind),
    FOREIGN KEY (remote_feedback_batch_id, batch_item_ordinal)
        REFERENCES remote_feedback_batch_item(remote_feedback_batch_id, ordinal)
);

CREATE TRIGGER remote_feedback_reply_draft_insert
BEFORE INSERT ON remote_feedback_reply_draft
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_repair_result repair
    JOIN remote_feedback_batch_item item
      ON item.remote_feedback_batch_id = repair.remote_feedback_batch_id
    JOIN remote_inbox_item inbox ON inbox.id = item.remote_inbox_item_id
    WHERE repair.id = NEW.repair_result_id
      AND repair.remote_feedback_batch_id = NEW.remote_feedback_batch_id
      AND item.ordinal = NEW.batch_item_ordinal
      AND (NEW.external_target IS NULL OR NEW.external_target = item.external_target)
      AND ((NEW.kind = 'POST_INLINE_REPLY' AND inbox.kind = 'INLINE_COMMENT')
        OR (NEW.kind = 'POST_TOP_LEVEL_REPLY' AND inbox.kind IN (
            'TOP_LEVEL_COMMENT', 'REVIEW_BODY', 'REVIEW_VERDICT'))
        OR (NEW.kind = 'RESOLVE_THREAD' AND inbox.kind IN (
            'INLINE_COMMENT', 'THREAD_REOPENED'))))
BEGIN SELECT RAISE(ABORT, 'Remote feedback reply draft is outside its frozen batch'); END;

CREATE TRIGGER remote_feedback_reply_draft_immutable
BEFORE UPDATE ON remote_feedback_reply_draft
BEGIN SELECT RAISE(ABORT, 'Remote feedback reply draft is immutable'); END;

CREATE TABLE remote_feedback_validation_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id    TEXT    NOT NULL REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    repair_result_id            TEXT    NOT NULL UNIQUE REFERENCES remote_feedback_repair_result(id),
    remote_development_stage_id TEXT    NOT NULL REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    code_fingerprint            TEXT    NOT NULL,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    CHECK ((status IN ('COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL))
);

CREATE TRIGGER remote_feedback_validation_operation_insert
BEFORE INSERT ON remote_feedback_validation_operation
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1 FROM remote_feedback_repair_result repair
    JOIN remote_feedback_batch batch ON batch.id = repair.remote_feedback_batch_id
    JOIN remote_development_stage remote
      ON remote.stage_id = batch.remote_development_stage_id
    JOIN tasks task ON task.id = batch.task_id
    WHERE repair.id = NEW.repair_result_id
      AND batch.id = NEW.remote_feedback_batch_id
      AND batch.status = 'ADDRESSING'
      AND batch.remote_development_stage_id = NEW.remote_development_stage_id
      AND batch.task_id = NEW.task_id
      AND batch.task_epoch = NEW.task_epoch
      AND batch.stage_generation = NEW.stage_generation
      AND repair.code_fingerprint = NEW.code_fingerprint
      AND repair.proposed_head_sha = NEW.expected_head_sha
      AND repair.base_sha = NEW.expected_base_sha
      AND remote.current_head_sha = repair.subject_head_sha
      AND remote.current_base_sha = repair.base_sha
      AND task.epoch = NEW.task_epoch)
BEGIN SELECT RAISE(ABORT, 'Remote feedback validation operation is stale or unowned'); END;

CREATE TRIGGER remote_feedback_validation_operation_identity_immutable
BEFORE UPDATE OF remote_feedback_batch_id, repair_result_id,
        remote_development_stage_id, task_id, task_epoch, stage_generation,
        operation_id, semantic_attempt, code_fingerprint, expected_head_sha,
        expected_base_sha, requested_at_ms ON remote_feedback_validation_operation
BEGIN SELECT RAISE(ABORT, 'Remote feedback validation identity is immutable'); END;

CREATE TRIGGER remote_feedback_validation_operation_transition
BEFORE UPDATE OF status ON remote_feedback_validation_operation
WHEN NOT ((OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'Remote feedback validation transition is invalid'); END;

CREATE TRIGGER remote_feedback_validation_operation_terminal_immutable
BEFORE UPDATE ON remote_feedback_validation_operation
WHEN OLD.status IN ('COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'Terminal remote feedback validation is immutable'); END;

CREATE TRIGGER dispatch_ticket_remote_feedback_validation_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'VALIDATE_REMOTE_FEEDBACK'
   OR NEW.callback_route = 'REMOTE_FEEDBACK_VALIDATION_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM remote_feedback_validation_operation operation
        WHERE operation.operation_id = NEW.operation_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.remote_development_stage_id = NEW.stage_id
          AND operation.stage_generation = NEW.stage_generation
          AND operation.semantic_attempt = NEW.attempt
          AND operation.code_fingerprint = NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND NEW.async_family = 'VALIDATION'
          AND NEW.owner_kind = 'STAGE'
          AND NEW.owner_id = NEW.stage_id
          AND NEW.lane_mask = 4
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Remote feedback validation ticket is not exact') END;
END;

-- Attempts stay append-only so a Brain changes-requested verdict can run a
-- fresh StageTurn and validation. V233's one-per-batch validation and Brain
-- evidence rows are written only for the final accepted attempt.
CREATE TABLE remote_feedback_validation_attempt_evidence (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id    TEXT    NOT NULL REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    repair_result_id            TEXT    NOT NULL UNIQUE REFERENCES remote_feedback_repair_result(id),
    validation_operation_id     TEXT    NOT NULL UNIQUE REFERENCES remote_feedback_validation_operation(id),
    validation_pass_id          INTEGER NOT NULL UNIQUE REFERENCES validation_pass(id),
    remote_development_stage_id TEXT    NOT NULL REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    repair_stage_turn_id        TEXT    NOT NULL REFERENCES stage_turn(id),
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    subject_head_sha            TEXT    NOT NULL,
    proposed_head_sha           TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    code_fingerprint            TEXT    NOT NULL,
    passed                      INTEGER NOT NULL CHECK (passed IN (0, 1)),
    failures_json               TEXT    NOT NULL,
    evidence                    TEXT    NOT NULL,
    completed_at_ms             INTEGER NOT NULL,
    UNIQUE (remote_feedback_batch_id, semantic_attempt)
);

CREATE TRIGGER remote_feedback_validation_attempt_evidence_insert
BEFORE INSERT ON remote_feedback_validation_attempt_evidence
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_validation_operation operation
    JOIN remote_feedback_repair_result repair ON repair.id = operation.repair_result_id
    JOIN remote_feedback_stage_turn_request request
      ON request.stage_turn_id = repair.repair_stage_turn_id
    JOIN validation_pass validation ON validation.id = NEW.validation_pass_id
    JOIN remote_feedback_batch batch ON batch.id = operation.remote_feedback_batch_id
    WHERE operation.id = NEW.validation_operation_id
      AND operation.status = 'DISPATCHED'
      AND operation.remote_feedback_batch_id = NEW.remote_feedback_batch_id
      AND operation.repair_result_id = NEW.repair_result_id
      AND operation.remote_development_stage_id = NEW.remote_development_stage_id
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.stage_generation = NEW.stage_generation
      AND operation.semantic_attempt = NEW.semantic_attempt
      AND operation.code_fingerprint = NEW.code_fingerprint
      AND operation.expected_head_sha = NEW.proposed_head_sha
      AND operation.expected_base_sha = NEW.base_sha
      AND repair.repair_stage_turn_id = NEW.repair_stage_turn_id
      AND repair.subject_head_sha = NEW.subject_head_sha
      AND request.semantic_attempt = NEW.semantic_attempt
      AND batch.status = 'ADDRESSING'
      AND validation.task_id = NEW.task_id
      AND validation.workflow_version = 'V2'
      AND validation.task_epoch = NEW.task_epoch
      AND validation.stage_id = NEW.remote_development_stage_id
      AND validation.stage_generation = NEW.stage_generation
      AND validation.operation_id = operation.operation_id
      AND validation.semantic_attempt = NEW.semantic_attempt
      AND validation.code_fingerprint = NEW.code_fingerprint
      AND validation.expected_head_sha = NEW.proposed_head_sha
      AND validation.expected_base_sha = NEW.base_sha
      AND validation.passed = NEW.passed
      AND validation.ended_at_ms = NEW.completed_at_ms)
BEGIN SELECT RAISE(ABORT, 'Remote validation attempt evidence is stale or unowned'); END;

CREATE TRIGGER remote_feedback_validation_attempt_evidence_immutable
BEFORE UPDATE ON remote_feedback_validation_attempt_evidence
BEGIN SELECT RAISE(ABORT, 'Remote validation attempt evidence is immutable'); END;

CREATE TABLE remote_feedback_brain_episode (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id    TEXT    NOT NULL REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    validation_attempt_evidence_id TEXT NOT NULL UNIQUE REFERENCES remote_feedback_validation_attempt_evidence(id),
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id TEXT    NOT NULL REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    task_turn_id                TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    code_fingerprint            TEXT    NOT NULL,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    verdict                     TEXT CHECK (verdict IN (
        'APPROVED', 'CHANGES_REQUESTED', 'BLOCKED')),
    unresolved_finding_count    INTEGER CHECK (unresolved_finding_count >= 0),
    evidence                    TEXT,
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    CHECK ((status = 'SUCCEEDED') = (verdict IS NOT NULL
        AND unresolved_finding_count IS NOT NULL AND evidence IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (verdict <> 'APPROVED' OR unresolved_finding_count = 0),
    UNIQUE (remote_feedback_batch_id, semantic_attempt)
);

CREATE TRIGGER remote_feedback_brain_episode_insert
BEFORE INSERT ON remote_feedback_brain_episode
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1 FROM remote_feedback_validation_attempt_evidence validation
    JOIN remote_feedback_batch batch
      ON batch.id = validation.remote_feedback_batch_id
    JOIN task_turn turn ON turn.id = NEW.task_turn_id
    WHERE validation.id = NEW.validation_attempt_evidence_id
      AND validation.passed = 1
      AND validation.remote_feedback_batch_id = NEW.remote_feedback_batch_id
      AND validation.task_id = NEW.task_id
      AND validation.task_epoch = NEW.task_epoch
      AND validation.remote_development_stage_id = NEW.remote_development_stage_id
      AND validation.stage_generation = NEW.stage_generation
      AND validation.semantic_attempt = NEW.semantic_attempt
      AND validation.code_fingerprint = NEW.code_fingerprint
      AND validation.proposed_head_sha = NEW.expected_head_sha
      AND validation.base_sha = NEW.expected_base_sha
      AND batch.status = 'ADDRESSING'
      AND turn.task_id = NEW.task_id
      AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.remote_development_stage_id
      AND turn.trigger_stage_generation = NEW.stage_generation
      AND turn.purpose = 'REMOTE_FEEDBACK_BRAIN_REVIEW'
      AND turn.attempt = NEW.semantic_attempt
      AND turn.expected_code_fingerprint = NEW.code_fingerprint
      AND turn.expected_head_sha = NEW.expected_head_sha
      AND turn.expected_base_sha = NEW.expected_base_sha
      AND turn.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Remote feedback Brain episode lacks green exact-head validation'); END;

CREATE TRIGGER remote_feedback_brain_episode_identity_immutable
BEFORE UPDATE OF remote_feedback_batch_id, validation_attempt_evidence_id, task_id,
        task_epoch, remote_development_stage_id, stage_generation,
        task_turn_id, semantic_attempt, code_fingerprint, expected_head_sha,
        expected_base_sha, requested_at_ms ON remote_feedback_brain_episode
BEGIN SELECT RAISE(ABORT, 'Remote feedback Brain episode identity is immutable'); END;

CREATE TRIGGER remote_feedback_brain_episode_transition
BEFORE UPDATE OF status ON remote_feedback_brain_episode
WHEN OLD.status <> 'REQUESTED'
  OR NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'Remote feedback Brain transition is invalid'); END;

CREATE TRIGGER remote_feedback_brain_episode_terminal_immutable
BEFORE UPDATE ON remote_feedback_brain_episode
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'Terminal remote feedback Brain episode is immutable'); END;

CREATE TRIGGER dispatch_ticket_remote_feedback_brain_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'EXECUTE_TASK_TURN'
  AND EXISTS (
      SELECT 1 FROM task_turn turn
      WHERE turn.id = NEW.owner_id
        AND turn.purpose = 'REMOTE_FEEDBACK_BRAIN_REVIEW')
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM remote_feedback_brain_episode episode
        JOIN task_turn turn ON turn.id = episode.task_turn_id
        WHERE episode.task_turn_id = NEW.owner_id
          AND turn.operation_id = NEW.operation_id
          AND turn.attempt = NEW.attempt
          AND turn.task_id = NEW.task_id
          AND turn.task_epoch = NEW.task_epoch
          AND turn.trigger_stage_id = NEW.stage_id
          AND turn.trigger_stage_generation = NEW.stage_generation
          AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
          AND turn.expected_head_sha = NEW.expected_head_sha
          AND turn.expected_base_sha = NEW.expected_base_sha
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'TASK_TURN'
          AND NEW.callback_route = 'REMOTE_FEEDBACK_BRAIN_RESULT'
          AND NEW.lane_mask IN (1, 2)
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Remote feedback Brain ticket is not exact') END;
END;

-- Payload text is deliberately separate from the immutable authorization.
-- The authorization freezes only its digest; this row proves the handler
-- receives the exact bytes the user approved.
CREATE TABLE remote_feedback_effect_payload (
    remote_feedback_effect_step_id TEXT NOT NULL PRIMARY KEY
        REFERENCES remote_feedback_effect_step(id) ON DELETE CASCADE,
    payload_kind                   TEXT NOT NULL CHECK (payload_kind IN (
        'TEXT', 'REVIEW', 'REVIEWER', 'NUDGE', 'RESOLUTION', 'PUSH')),
    payload                        TEXT NOT NULL,
    payload_digest                 TEXT NOT NULL,
    created_at_ms                  INTEGER NOT NULL,
    CHECK (length(payload) > 0)
);

CREATE TRIGGER remote_feedback_effect_payload_insert
BEFORE INSERT ON remote_feedback_effect_payload
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_effect_step step
    JOIN remote_feedback_batch batch ON batch.id = step.remote_feedback_batch_id
    WHERE step.id = NEW.remote_feedback_effect_step_id
      AND step.status = 'REQUESTED'
      AND step.payload_digest = NEW.payload_digest
      AND batch.status = 'AWAITING_APPROVAL'
      AND NEW.payload_kind = CASE
          WHEN step.kind IN ('POST_INLINE_REPLY', 'POST_TOP_LEVEL_REPLY') THEN 'TEXT'
          WHEN step.kind = 'SUBMIT_REVIEW' THEN 'REVIEW'
          WHEN step.kind = 'REQUEST_REVIEWER' THEN 'REVIEWER'
          WHEN step.kind = 'POST_MAINTAINER_NUDGE' THEN 'NUDGE'
          WHEN step.kind = 'RESOLVE_THREAD' THEN 'RESOLUTION'
          WHEN step.kind = 'PUSH_COMMITS' THEN 'PUSH'
      END)
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect payload is not authorized'); END;

CREATE TRIGGER remote_feedback_effect_payload_immutable
BEFORE UPDATE ON remote_feedback_effect_payload
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect payload is immutable'); END;

CREATE TABLE remote_feedback_effect_dispatch (
    remote_feedback_effect_step_id TEXT NOT NULL PRIMARY KEY
        REFERENCES remote_feedback_effect_step(id) ON DELETE CASCADE,
    dispatch_ticket_id             TEXT NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    operation_id                   TEXT NOT NULL UNIQUE,
    dispatched_at_ms               INTEGER NOT NULL
);

CREATE TRIGGER remote_feedback_effect_dispatch_insert
BEFORE INSERT ON remote_feedback_effect_dispatch
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_effect_step step
    JOIN remote_feedback_authorization authorization
      ON authorization.id = step.remote_feedback_authorization_id
    JOIN remote_feedback_batch batch ON batch.id = step.remote_feedback_batch_id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    JOIN remote_feedback_effect_payload payload
      ON payload.remote_feedback_effect_step_id = step.id
    WHERE step.id = NEW.remote_feedback_effect_step_id
      AND step.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
      AND batch.status IN ('AUTHORIZED', 'APPLYING')
      AND NOT EXISTS (
          SELECT 1 FROM remote_feedback_effect_step previous
          WHERE previous.remote_feedback_batch_id = step.remote_feedback_batch_id
            AND previous.ordinal < step.ordinal
            AND previous.status <> 'SUCCEEDED')
      AND ticket.operation_id = NEW.operation_id
      AND ticket.operation_kind = 'APPLY_REMOTE_FEEDBACK_EFFECT'
      AND ticket.async_family = CASE WHEN step.kind = 'PUSH_COMMITS'
          THEN 'LOCAL_GIT' ELSE 'GITHUB_EFFECT' END
      AND ticket.owner_kind = 'STAGE'
      AND ticket.owner_id = batch.remote_development_stage_id
      AND ticket.callback_route = 'REMOTE_FEEDBACK_EFFECT_RESULT'
      AND ticket.task_id = batch.task_id
      AND ticket.task_epoch = batch.task_epoch
      AND ticket.stage_id = batch.remote_development_stage_id
      AND ticket.stage_generation = batch.stage_generation
      AND ticket.attempt = step.attempt_count + 1
      AND ticket.expected_head_sha = authorization.head_sha
      AND ticket.expected_base_sha = authorization.base_sha
      AND ticket.lane_mask = CASE WHEN step.kind = 'PUSH_COMMITS' THEN 16 ELSE 32 END
      AND ticket.trunk_control = 0
      AND ticket.exclusive_task = 1
      AND ticket.writer_required = CASE WHEN step.kind = 'PUSH_COMMITS' THEN 1 ELSE 0 END
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect dispatch is unordered or stale'); END;

CREATE TRIGGER remote_feedback_effect_dispatch_immutable
BEFORE UPDATE ON remote_feedback_effect_dispatch
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect dispatch is immutable'); END;

CREATE TRIGGER dispatch_ticket_remote_feedback_effect_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'APPLY_REMOTE_FEEDBACK_EFFECT'
   OR NEW.callback_route = 'REMOTE_FEEDBACK_EFFECT_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM remote_feedback_effect_step step
        JOIN remote_feedback_authorization authorization
          ON authorization.id = step.remote_feedback_authorization_id
        JOIN remote_feedback_batch batch ON batch.id = step.remote_feedback_batch_id
        WHERE NEW.operation_id = step.id || ':attempt:' || (step.attempt_count + 1)
          AND step.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
          AND batch.status IN ('AUTHORIZED', 'APPLYING')
          AND NEW.owner_kind = 'STAGE'
          AND NEW.owner_id = batch.remote_development_stage_id
          AND NEW.task_id = batch.task_id
          AND NEW.task_epoch = batch.task_epoch
          AND NEW.stage_id = batch.remote_development_stage_id
          AND NEW.stage_generation = batch.stage_generation
          AND NEW.attempt = step.attempt_count + 1
          AND NEW.expected_head_sha = authorization.head_sha
          AND NEW.expected_base_sha = authorization.base_sha
          AND NEW.async_family = CASE WHEN step.kind = 'PUSH_COMMITS'
              THEN 'LOCAL_GIT' ELSE 'GITHUB_EFFECT' END
          AND NEW.lane_mask = CASE WHEN step.kind = 'PUSH_COMMITS' THEN 16 ELSE 32 END
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = CASE WHEN step.kind = 'PUSH_COMMITS' THEN 1 ELSE 0 END
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Remote feedback effect ticket is not exact') END;
END;

CREATE TABLE remote_mark_ready_dispatch (
    remote_mark_ready_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES remote_mark_ready_operation(id) ON DELETE CASCADE,
    dispatch_ticket_id             TEXT NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    operation_id                   TEXT NOT NULL UNIQUE,
    dispatched_at_ms               INTEGER NOT NULL
);

CREATE TRIGGER remote_mark_ready_dispatch_insert
BEFORE INSERT ON remote_mark_ready_dispatch
WHEN NOT EXISTS (
    SELECT 1 FROM remote_mark_ready_operation operation
    JOIN remote_mark_ready_authorization authorization
      ON authorization.id = operation.mark_ready_authorization_id
    JOIN remote_development_stage remote
      ON remote.stage_id = operation.remote_development_stage_id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    WHERE operation.id = NEW.remote_mark_ready_operation_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
      AND authorization.status = 'CONSUMED'
      AND remote.current_head_sha = operation.head_sha
      AND remote.current_base_sha = operation.base_sha
      AND ticket.operation_id = NEW.operation_id
      AND ticket.operation_kind = 'MARK_REMOTE_PR_READY'
      AND ticket.async_family = 'GITHUB_EFFECT'
      AND ticket.owner_kind = 'STAGE'
      AND ticket.owner_id = operation.remote_development_stage_id
      AND ticket.callback_route = 'REMOTE_MARK_READY_RESULT'
      AND ticket.task_id = operation.task_id
      AND ticket.task_epoch = operation.task_epoch
      AND ticket.stage_id = operation.remote_development_stage_id
      AND ticket.stage_generation = operation.stage_generation
      AND ticket.attempt = operation.semantic_attempt
      AND ticket.expected_head_sha = operation.head_sha
      AND ticket.expected_base_sha = operation.base_sha
      AND ticket.lane_mask = 32
      AND ticket.trunk_control = 0
      AND ticket.exclusive_task = 1
      AND ticket.writer_required = 0
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Mark-ready dispatch lacks consumed exact-head authority'); END;

CREATE TRIGGER remote_mark_ready_dispatch_immutable
BEFORE UPDATE ON remote_mark_ready_dispatch
BEGIN SELECT RAISE(ABORT, 'Mark-ready dispatch is immutable'); END;

CREATE TRIGGER dispatch_ticket_remote_mark_ready_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'MARK_REMOTE_PR_READY'
   OR NEW.callback_route = 'REMOTE_MARK_READY_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM remote_mark_ready_operation operation
        JOIN remote_mark_ready_authorization authorization
          ON authorization.id = operation.mark_ready_authorization_id
        WHERE operation.operation_id = NEW.operation_id
          AND operation.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
          AND authorization.status = 'CONSUMED'
          AND NEW.async_family = 'GITHUB_EFFECT'
          AND NEW.owner_kind = 'STAGE'
          AND NEW.owner_id = operation.remote_development_stage_id
          AND NEW.task_id = operation.task_id
          AND NEW.task_epoch = operation.task_epoch
          AND NEW.stage_id = operation.remote_development_stage_id
          AND NEW.stage_generation = operation.stage_generation
          AND NEW.attempt = operation.semantic_attempt
          AND NEW.expected_head_sha = operation.head_sha
          AND NEW.expected_base_sha = operation.base_sha
          AND NEW.lane_mask = 32
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Mark-ready ticket is not exact') END;
END;

-- Task owns the Brain fence even when Remote Development requested the review.
-- Keep these receipts separate from V236's Local-only proof trigger.
CREATE TABLE remote_task_brain_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause IN (
        'REQUEST_BRAIN_REVIEW', 'ACCEPT_BRAIN_VERDICT')),
    actor                             TEXT    NOT NULL CHECK (length(actor) > 0),
    disposition                       TEXT    NOT NULL CHECK (disposition IN (
        'APPLIED', 'SUPERSEDED')),
    expected_task_epoch               INTEGER,
    expected_task_version             INTEGER,
    subject_task_epoch                INTEGER NOT NULL CHECK (subject_task_epoch > 0),
    subject_stage_id                  TEXT    NOT NULL,
    subject_stage_generation          INTEGER NOT NULL CHECK (subject_stage_generation > 0),
    subject_operation_id              TEXT    NOT NULL CHECK (length(subject_operation_id) > 0),
    subject_attempt                   INTEGER NOT NULL CHECK (subject_attempt > 0),
    subject_expected_code_fingerprint TEXT,
    subject_expected_head_sha         TEXT,
    subject_expected_base_sha         TEXT,
    brain_verdict                     TEXT CHECK (brain_verdict IN (
        'APPROVED', 'CHANGES_REQUESTED')),
    proof_id                          TEXT,
    next_stage_id                     TEXT,
    next_stage_kind                   TEXT,
    next_stage_generation             INTEGER,
    returned_trunk_id                 TEXT    NOT NULL REFERENCES threads(id),
    returned_lifecycle                TEXT    NOT NULL CHECK (returned_lifecycle = 'ACTIVE'),
    returned_epoch                    INTEGER NOT NULL CHECK (returned_epoch > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version >= 0),
    returned_current_stage_id         TEXT    NOT NULL,
    returned_pending_task_epoch       INTEGER,
    returned_pending_stage_id         TEXT,
    returned_pending_stage_generation INTEGER,
    returned_pending_operation_id     TEXT,
    returned_pending_attempt          INTEGER,
    returned_pending_code_fingerprint TEXT,
    returned_pending_head_sha         TEXT,
    returned_pending_base_sha         TEXT,
    returned_last_brain_verdict       TEXT CHECK (returned_last_brain_verdict IN (
        'APPROVED', 'CHANGES_REQUESTED')),
    returned_last_brain_task_epoch    INTEGER,
    returned_last_brain_stage_id      TEXT,
    returned_last_brain_stage_generation INTEGER,
    returned_last_brain_operation_id  TEXT,
    returned_last_brain_attempt       INTEGER,
    returned_last_brain_code_fingerprint TEXT,
    returned_last_brain_head_sha      TEXT,
    returned_last_brain_base_sha      TEXT,
    returned_terminal_intent          TEXT,
    recorded_at_ms                    INTEGER NOT NULL,
    UNIQUE (task_id, command_id),
    CHECK (next_stage_id IS NULL AND next_stage_kind IS NULL
        AND next_stage_generation IS NULL),
    CHECK (returned_terminal_intent IS NULL),
    CHECK ((cause = 'REQUEST_BRAIN_REVIEW'
            AND disposition = 'APPLIED'
            AND expected_task_epoch IS NOT NULL
            AND expected_task_version IS NOT NULL
            AND proof_id IS NOT NULL AND brain_verdict IS NULL
            AND returned_pending_operation_id = subject_operation_id)
        OR (cause = 'ACCEPT_BRAIN_VERDICT'
            AND expected_task_epoch IS NULL
            AND expected_task_version IS NULL
            AND proof_id IS NULL AND brain_verdict IS NOT NULL)),
    FOREIGN KEY (subject_stage_id, task_id)
        REFERENCES stage(id, task_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (returned_current_stage_id, task_id)
        REFERENCES stage(id, task_id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER remote_task_brain_receipt_insert
BEFORE INSERT ON remote_task_brain_receipt
BEGIN
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM task_command_receipt shared
                     WHERE shared.task_id = NEW.task_id
                       AND shared.command_id = NEW.command_id)
          OR EXISTS (SELECT 1 FROM task_brain_request_receipt local
                     WHERE local.task_id = NEW.task_id
                       AND local.command_id = NEW.command_id)
            THEN RAISE(ABORT, 'Remote Task Brain command id is already used')
        WHEN NEW.cause = 'REQUEST_BRAIN_REVIEW' AND NOT EXISTS (
            SELECT 1 FROM remote_feedback_brain_episode episode
            JOIN task_turn turn ON turn.id = episode.task_turn_id
            JOIN task_transition transition
              ON transition.task_id = episode.task_id
             AND transition.command_id = NEW.command_id
            WHERE episode.id = NEW.proof_id
              AND episode.status = 'REQUESTED'
              AND episode.task_id = NEW.task_id
              AND episode.task_epoch = NEW.subject_task_epoch
              AND episode.remote_development_stage_id = NEW.subject_stage_id
              AND episode.stage_generation = NEW.subject_stage_generation
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND turn.expected_code_fingerprint IS NEW.subject_expected_code_fingerprint
              AND turn.expected_head_sha IS NEW.subject_expected_head_sha
              AND turn.expected_base_sha IS NEW.subject_expected_base_sha
              AND NEW.returned_epoch = NEW.expected_task_epoch
              AND NEW.returned_version = NEW.expected_task_version + 1
              AND NEW.returned_current_stage_id = NEW.subject_stage_id
              AND NEW.returned_pending_task_epoch = NEW.subject_task_epoch
              AND NEW.returned_pending_stage_id = NEW.subject_stage_id
              AND NEW.returned_pending_stage_generation = NEW.subject_stage_generation
              AND NEW.returned_pending_operation_id = NEW.subject_operation_id
              AND NEW.returned_pending_attempt = NEW.subject_attempt
              AND NEW.returned_pending_code_fingerprint IS NEW.subject_expected_code_fingerprint
              AND NEW.returned_pending_head_sha IS NEW.subject_expected_head_sha
              AND NEW.returned_pending_base_sha IS NEW.subject_expected_base_sha
              AND transition.aggregate_version = NEW.returned_version
              AND transition.cause = NEW.cause
              AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'Remote Task Brain request receipt is not exact')
        WHEN NEW.cause = 'ACCEPT_BRAIN_VERDICT' AND NEW.disposition = 'APPLIED'
          AND NOT EXISTS (
            SELECT 1 FROM remote_feedback_brain_episode episode
            JOIN task_turn turn ON turn.id = episode.task_turn_id
            JOIN task_transition transition
              ON transition.task_id = episode.task_id
             AND transition.command_id = NEW.command_id
            WHERE episode.task_id = NEW.task_id
              AND episode.status = 'SUCCEEDED'
              AND episode.verdict = NEW.brain_verdict
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND turn.task_epoch = NEW.subject_task_epoch
              AND turn.trigger_stage_id = NEW.subject_stage_id
              AND turn.trigger_stage_generation = NEW.subject_stage_generation
              AND turn.expected_code_fingerprint IS NEW.subject_expected_code_fingerprint
              AND turn.expected_head_sha IS NEW.subject_expected_head_sha
              AND turn.expected_base_sha IS NEW.subject_expected_base_sha
              AND NEW.returned_pending_operation_id IS NULL
              AND NEW.returned_last_brain_verdict = NEW.brain_verdict
              AND NEW.returned_last_brain_operation_id = NEW.subject_operation_id
              AND transition.aggregate_version = NEW.returned_version
              AND transition.cause = NEW.cause
              AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'Remote Task Brain verdict receipt is not exact')
    END;
END;

CREATE TRIGGER remote_task_brain_receipt_immutable
BEFORE UPDATE ON remote_task_brain_receipt
BEGIN SELECT RAISE(ABORT, 'Remote Task Brain receipt is immutable'); END;

CREATE TRIGGER task_command_receipt_remote_brain_id_collision
BEFORE INSERT ON task_command_receipt
WHEN EXISTS (SELECT 1 FROM remote_task_brain_receipt remote
             WHERE remote.task_id = NEW.task_id
               AND remote.command_id = NEW.command_id)
BEGIN SELECT RAISE(ABORT, 'Task command id is already used by Remote Brain'); END;

CREATE TABLE remote_runtime_delivery_receipt (
    id                  TEXT    NOT NULL PRIMARY KEY,
    operation_id        TEXT    NOT NULL UNIQUE,
    callback_route      TEXT    NOT NULL CHECK (callback_route IN (
        'REMOTE_FEEDBACK_TURN_RESULT',
        'REMOTE_FEEDBACK_VALIDATION_RESULT',
        'REMOTE_FEEDBACK_BRAIN_RESULT',
        'REMOTE_FEEDBACK_EFFECT_RESULT',
        'REMOTE_MARK_READY_RESULT')),
    raw_result_digest   TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance          TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED', 'REJECTED')),
    evidence            TEXT    NOT NULL,
    recorded_at_ms      INTEGER NOT NULL
);

CREATE TRIGGER remote_runtime_delivery_receipt_immutable
BEFORE UPDATE ON remote_runtime_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Remote runtime delivery receipt is immutable'); END;

-- Remote feedback completion is a structural owner transition, not a
-- DispatchTicket side effect.  Keep its receipt separate so V242 does not
-- rebuild the shared Stage receipt while other migrations are landing.
CREATE TABLE remote_stage_runtime_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    stage_id                          TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause IN (
        'COMPLETE_REMOTE_FEEDBACK_PUSH',
        'COMPLETE_REMOTE_FEEDBACK_NO_PUSH',
        'COMPLETE_REMOTE_MARK_READY',
        'ACCEPT_REMOTE_READINESS')),
    actor                             TEXT    NOT NULL CHECK (length(actor) > 0),
    disposition                       TEXT    NOT NULL CHECK (disposition IN (
        'APPLIED', 'SUPERSEDED')),
    expected_task_epoch               INTEGER NOT NULL CHECK (expected_task_epoch > 0),
    expected_stage_generation         INTEGER NOT NULL CHECK (expected_stage_generation > 0),
    expected_stage_version            INTEGER NOT NULL CHECK (expected_stage_version >= 0),
    source_checkpoint                 TEXT    NOT NULL CHECK (source_checkpoint IN (
        'ADDRESSING_REMOTE_FEEDBACK', 'AWAITING_READY',
        'WAITING_REMOTE_REVIEW')),
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
        'WAITING_CI', 'WAITING_REMOTE_REVIEW', 'READY_TO_MERGE')),
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
    CHECK ((cause = 'COMPLETE_REMOTE_FEEDBACK_PUSH'
            AND source_checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
            AND returned_checkpoint = 'WAITING_CI')
        OR (cause = 'COMPLETE_REMOTE_FEEDBACK_NO_PUSH'
            AND source_checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
            AND returned_checkpoint = 'WAITING_REMOTE_REVIEW')
        OR (cause = 'COMPLETE_REMOTE_MARK_READY'
            AND source_checkpoint = 'AWAITING_READY'
            AND returned_checkpoint = 'WAITING_REMOTE_REVIEW')
        OR (cause = 'ACCEPT_REMOTE_READINESS'
            AND source_checkpoint = 'WAITING_REMOTE_REVIEW'
            AND returned_checkpoint = 'READY_TO_MERGE'))
);

CREATE TRIGGER remote_stage_runtime_receipt_insert
BEFORE INSERT ON remote_stage_runtime_receipt
WHEN NOT EXISTS (
    SELECT 1 FROM stage owner
    JOIN remote_development_stage remote ON remote.stage_id = owner.id
    WHERE owner.id = NEW.stage_id AND owner.task_id = NEW.task_id
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = NEW.returned_generation
      AND remote.stage_id = owner.id
      AND (
        (NEW.cause IN ('COMPLETE_REMOTE_FEEDBACK_PUSH',
                'COMPLETE_REMOTE_FEEDBACK_NO_PUSH') AND EXISTS (
            SELECT 1 FROM remote_feedback_batch batch
            WHERE batch.id = NEW.proof_id
              AND batch.remote_development_stage_id = owner.id
              AND batch.task_id = NEW.task_id
              AND batch.task_epoch = NEW.expected_task_epoch
              AND batch.stage_generation = NEW.expected_stage_generation
              AND batch.status = 'COMPLETED'
              AND ((NEW.cause = 'COMPLETE_REMOTE_FEEDBACK_PUSH'
                    AND batch.result_head_sha IS NOT NULL
                    AND remote.current_head_sha = batch.result_head_sha)
                OR (NEW.cause = 'COMPLETE_REMOTE_FEEDBACK_NO_PUSH'
                    AND batch.result_head_sha IS NULL
                    AND remote.current_head_sha = batch.head_sha))))
        OR (NEW.cause = 'COMPLETE_REMOTE_MARK_READY' AND EXISTS (
            SELECT 1 FROM remote_mark_ready_operation operation
            WHERE operation.id = NEW.proof_id
              AND operation.remote_development_stage_id = owner.id
              AND operation.task_id = NEW.task_id
              AND operation.task_epoch = NEW.expected_task_epoch
              AND operation.stage_generation = NEW.expected_stage_generation
              AND operation.status = 'SUCCEEDED'
              AND operation.result_snapshot_id = remote.accepted_snapshot_id
              AND operation.head_sha = remote.current_head_sha
              AND operation.base_sha = remote.current_base_sha))
        OR (NEW.cause = 'ACCEPT_REMOTE_READINESS' AND EXISTS (
            SELECT 1 FROM remote_readiness_evidence readiness
            WHERE readiness.id = NEW.proof_id
              AND readiness.remote_development_stage_id = owner.id
              AND readiness.task_id = NEW.task_id
              AND readiness.task_epoch = NEW.expected_task_epoch
              AND readiness.stage_generation = NEW.expected_stage_generation
              AND readiness.ready = 1
              AND readiness.remote_pr_snapshot_id = remote.accepted_snapshot_id
              AND readiness.head_sha = remote.current_head_sha
              AND readiness.base_sha = remote.current_base_sha)))
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
BEGIN SELECT RAISE(ABORT, 'Remote feedback completion receipt lacks exact owner proof'); END;

CREATE TRIGGER remote_stage_runtime_receipt_immutable
BEFORE UPDATE ON remote_stage_runtime_receipt
BEGIN SELECT RAISE(ABORT, 'Remote Stage runtime receipt is immutable'); END;

-- V233 could only prove no-op repairs because it compared the StageTurn input
-- fence with the output code subject. Link the final gate to V242's immutable
-- repair result so a real code-changing feedback turn remains exact.
DROP TRIGGER remote_feedback_validation_evidence_insert;
CREATE TRIGGER remote_feedback_validation_evidence_insert
BEFORE INSERT ON remote_feedback_validation_evidence
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_batch batch
    JOIN remote_development_stage remote
      ON remote.stage_id = batch.remote_development_stage_id
    JOIN tasks task ON task.id = batch.task_id
    JOIN remote_feedback_repair_result repair
      ON repair.remote_feedback_batch_id = batch.id
    JOIN stage_turn turn ON turn.id = repair.repair_stage_turn_id
    JOIN validation_pass validation ON validation.id = NEW.validation_pass_id
    WHERE batch.id = NEW.remote_feedback_batch_id
      AND batch.status IN ('ADDRESSING', 'AWAITING_APPROVAL')
      AND batch.remote_development_stage_id = NEW.remote_development_stage_id
      AND batch.task_id = NEW.task_id
      AND batch.task_epoch = NEW.task_epoch
      AND batch.stage_generation = NEW.stage_generation
      AND batch.head_sha = NEW.subject_head_sha
      AND batch.base_sha = NEW.base_sha
      AND remote.current_head_sha = NEW.subject_head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch
      AND repair.repair_stage_turn_id = NEW.repair_stage_turn_id
      AND repair.subject_head_sha = NEW.subject_head_sha
      AND repair.proposed_head_sha = NEW.proposed_head_sha
      AND repair.base_sha = NEW.base_sha
      AND repair.code_fingerprint = NEW.code_fingerprint
      AND turn.stage_id = NEW.remote_development_stage_id
      AND turn.stage_generation = NEW.stage_generation
      AND turn.task_epoch = NEW.task_epoch
      AND turn.purpose = 'ADDRESS_REMOTE_FEEDBACK'
      AND turn.status = 'SUCCEEDED'
      AND turn.expected_base_sha = NEW.base_sha
      AND validation.task_id = NEW.task_id
      AND validation.workflow_version = 'V2'
      AND validation.task_epoch = NEW.task_epoch
      AND validation.stage_id = NEW.remote_development_stage_id
      AND validation.stage_generation = NEW.stage_generation
      AND validation.operation_id = NEW.validation_operation_id
      AND validation.semantic_attempt = NEW.validation_attempt
      AND validation.code_fingerprint = NEW.code_fingerprint
      AND validation.expected_head_sha = NEW.proposed_head_sha
      AND validation.expected_base_sha = NEW.base_sha
      AND validation.ended_at_ms = NEW.completed_at_ms
      AND validation.passed = NEW.passed)
BEGIN SELECT RAISE(ABORT, 'Remote feedback validation lacks exact repair evidence'); END;
