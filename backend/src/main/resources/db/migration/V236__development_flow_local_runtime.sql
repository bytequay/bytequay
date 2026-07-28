-- Exact Local Development runtime records. Production routing remains off;
-- these constraints let owner commands persist and recover without reusing
-- the legacy scheduler or inferring an active Turn.

-- A code-producing StageTurn is fenced by the source subject. Its immutable
-- DevReport records the resulting subject. V228 accidentally required those
-- two subjects to be identical, which made a real code change impossible.
ALTER TABLE dev_report ADD COLUMN source_code_fingerprint TEXT;
ALTER TABLE dev_report ADD COLUMN source_head_sha TEXT;
ALTER TABLE dev_report ADD COLUMN source_base_sha TEXT;

DROP TRIGGER dev_report_owner_insert;
DROP TRIGGER dev_report_immutable;
DROP TRIGGER dev_report_route_immutable;

UPDATE dev_report
SET source_code_fingerprint = (
        SELECT turn.expected_code_fingerprint FROM stage_turn turn
        WHERE turn.id = dev_report.stage_turn_id),
    source_head_sha = (
        SELECT turn.expected_head_sha FROM stage_turn turn
        WHERE turn.id = dev_report.stage_turn_id),
    source_base_sha = (
        SELECT turn.expected_base_sha FROM stage_turn turn
        WHERE turn.id = dev_report.stage_turn_id)
WHERE workflow_version = 'V2';

CREATE TRIGGER dev_report_owner_insert
BEFORE INSERT ON dev_report
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks task
            WHERE task.id = NEW.task_id
              AND task.workflow_version = NEW.workflow_version)
            THEN RAISE(ABORT, 'DevReport workflow version must match its Task')
        WHEN NEW.workflow_version = 'LEGACY' AND (
            NEW.local_development_stage_id IS NOT NULL
            OR NEW.task_epoch IS NOT NULL OR NEW.stage_generation IS NOT NULL
            OR NEW.stage_turn_id IS NOT NULL OR NEW.revision IS NOT NULL
            OR NEW.code_fingerprint IS NOT NULL OR NEW.head_sha IS NOT NULL
            OR NEW.base_sha IS NOT NULL OR NEW.implemented_intent IS NOT NULL
            OR NEW.commit_summary IS NOT NULL OR NEW.file_summary IS NOT NULL
            OR NEW.validation_summary IS NOT NULL OR NEW.known_risks IS NOT NULL
            OR NEW.unresolved_concerns IS NOT NULL OR NEW.context_refs IS NOT NULL
            OR NEW.source_code_fingerprint IS NOT NULL
            OR NEW.source_head_sha IS NOT NULL OR NEW.source_base_sha IS NOT NULL)
            THEN RAISE(ABORT, 'LEGACY DevReport cannot carry a V2 fence')
        WHEN NEW.workflow_version = 'V2' AND (
            NEW.local_development_stage_id IS NULL OR NEW.task_epoch IS NULL
            OR NEW.stage_generation IS NULL OR NEW.stage_turn_id IS NULL
            OR NEW.revision IS NULL OR NEW.code_fingerprint IS NULL
            OR NEW.head_sha IS NULL OR NEW.base_sha IS NULL
            OR NEW.source_code_fingerprint IS NULL
            OR NEW.source_head_sha IS NULL OR NEW.source_base_sha IS NULL
            OR NEW.implemented_intent IS NULL OR NEW.commit_summary IS NULL
            OR NEW.file_summary IS NULL OR NEW.validation_summary IS NULL
            OR NEW.known_risks IS NULL OR NEW.unresolved_concerns IS NULL
            OR NEW.context_refs IS NULL)
            THEN RAISE(ABORT, 'V2 DevReport requires its complete typed fence')
        WHEN NEW.workflow_version = 'V2' AND (
            length(NEW.code_fingerprint) = 0 OR length(NEW.head_sha) = 0
            OR length(NEW.base_sha) = 0
            OR length(NEW.source_code_fingerprint) = 0
            OR length(NEW.source_head_sha) = 0
            OR length(NEW.source_base_sha) = 0
            OR length(NEW.implemented_intent) = 0)
            THEN RAISE(ABORT, 'V2 DevReport subject must not be blank')
        WHEN NEW.workflow_version = 'V2' AND NEW.revision <> COALESCE((
            SELECT MAX(report.revision) + 1 FROM dev_report report
            WHERE report.workflow_version = 'V2'
              AND report.local_development_stage_id
                    = NEW.local_development_stage_id), 1)
            THEN RAISE(ABORT, 'DevReport revision must be the next exact revision')
        WHEN NEW.workflow_version = 'V2' AND NOT EXISTS (
            SELECT 1
            FROM local_development_stage local
            JOIN stage owner ON owner.id = local.stage_id
            JOIN tasks task ON task.id = local.task_id
            JOIN task_current_stage current ON current.stage_id = local.stage_id
            JOIN stage_turn turn ON turn.id = NEW.stage_turn_id
            WHERE local.stage_id = NEW.local_development_stage_id
              AND local.task_id = NEW.task_id
              AND local.generation = NEW.stage_generation
              AND local.opened_for_epoch = NEW.task_epoch
              AND owner.completed_at_ms IS NULL
              AND task.lifecycle_state = 'ACTIVE'
              AND task.epoch = NEW.task_epoch
              AND current.task_id = NEW.task_id
              AND current.stage_generation = NEW.stage_generation
              AND turn.stage_id = local.stage_id
              AND turn.stage_generation = local.generation
              AND turn.task_epoch = NEW.task_epoch
              AND turn.expected_code_fingerprint
                    = NEW.source_code_fingerprint
              AND turn.expected_head_sha = NEW.source_head_sha
              AND turn.expected_base_sha = NEW.source_base_sha
              AND turn.status = 'SUCCEEDED')
            THEN RAISE(ABORT, 'DevReport requires its exact successful StageTurn')
    END;
END;

CREATE TRIGGER dev_report_immutable
BEFORE UPDATE ON dev_report
WHEN OLD.workflow_version = 'V2'
BEGIN SELECT RAISE(ABORT, 'V2 DevReport is immutable'); END;

CREATE TRIGGER dev_report_route_immutable
BEFORE UPDATE OF workflow_version, task_id, local_development_stage_id,
        task_epoch, stage_generation, stage_turn_id, revision,
        code_fingerprint, head_sha, base_sha, source_code_fingerprint,
        source_head_sha, source_base_sha ON dev_report
WHEN NEW.workflow_version IS NOT OLD.workflow_version
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.local_development_stage_id IS NOT OLD.local_development_stage_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.stage_turn_id IS NOT OLD.stage_turn_id
  OR NEW.revision IS NOT OLD.revision
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.source_code_fingerprint IS NOT OLD.source_code_fingerprint
  OR NEW.source_head_sha IS NOT OLD.source_head_sha
  OR NEW.source_base_sha IS NOT OLD.source_base_sha
BEGIN SELECT RAISE(ABORT, 'DevReport route and subject are immutable'); END;

-- Meaning and queue relation for a Local code-writing StageTurn. StageTurn is
-- still the delivery state; this table does not duplicate queued/running state.
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
        'IMPLEMENTATION', 'BRAIN_FINDINGS', 'LOCAL_FEEDBACK', 'STEERING')),
    queue_mode                 TEXT    NOT NULL CHECK (queue_mode IN (
        'IMMEDIATE', 'APPEND', 'CANCEL_AND_REPLACE')),
    predecessor_turn_id        TEXT REFERENCES stage_turn(id),
    brain_review_episode_id    TEXT REFERENCES brain_review_episode(id),
    local_feedback_batch_id    TEXT REFERENCES local_feedback_batch(id),
    prompt_digest              TEXT    NOT NULL,
    requested_by               TEXT    NOT NULL,
    requested_at_ms            INTEGER NOT NULL,
    cancellation_requested_at_ms INTEGER,
    CHECK (length(prompt_digest) = 64 AND length(requested_by) > 0),
    CHECK ((queue_mode = 'IMMEDIATE') = (predecessor_turn_id IS NULL)),
    CHECK ((kind = 'BRAIN_FINDINGS') = (brain_review_episode_id IS NOT NULL)),
    CHECK ((kind = 'LOCAL_FEEDBACK') = (local_feedback_batch_id IS NOT NULL))
);

CREATE TRIGGER local_stage_turn_request_insert
BEFORE INSERT ON local_stage_turn_request
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM stage_turn turn
            JOIN stage owner ON owner.id = turn.stage_id
            JOIN tasks task ON task.id = owner.task_id
            JOIN task_current_stage current ON current.task_id = task.id
            JOIN task_current_code_subject_v230 code ON code.task_id = task.id
            WHERE turn.id = NEW.stage_turn_id
              AND turn.stage_id = NEW.local_development_stage_id
              AND turn.stage_generation = NEW.stage_generation
              AND turn.task_epoch = NEW.task_epoch
              AND turn.status = 'QUEUED'
              AND turn.expected_code_fingerprint = code.code_fingerprint
              AND turn.expected_head_sha = code.head_sha
              AND turn.expected_base_sha = code.base_sha
              AND task.id = NEW.task_id AND task.workflow_version = 'V2'
              AND task.lifecycle_state = 'ACTIVE'
              AND task.epoch = NEW.task_epoch
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
                OR (NEW.kind = 'STEERING'
                    AND turn.purpose = 'USER_STEERING')))
            THEN RAISE(ABORT, 'Local StageTurn request owner or subject is stale')
        WHEN NEW.predecessor_turn_id IS NOT NULL AND NOT EXISTS (
            SELECT 1
            FROM local_stage_turn_request previous
            JOIN stage_turn turn ON turn.id = previous.stage_turn_id
            WHERE previous.stage_turn_id = NEW.predecessor_turn_id
              AND previous.task_id = NEW.task_id
              AND previous.local_development_stage_id
                    = NEW.local_development_stage_id
              AND previous.task_epoch = NEW.task_epoch
              AND previous.stage_generation = NEW.stage_generation
              AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
            THEN RAISE(ABORT, 'Local StageTurn predecessor is not exact and live')
        WHEN NEW.brain_review_episode_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM brain_review_episode episode
            WHERE episode.id = NEW.brain_review_episode_id
              AND episode.task_id = NEW.task_id
              AND episode.local_development_stage_id
                    = NEW.local_development_stage_id
              AND episode.task_epoch = NEW.task_epoch
              AND episode.stage_generation = NEW.stage_generation
              AND episode.status = 'SUCCEEDED'
              AND episode.verdict = 'CHANGES_REQUESTED')
            THEN RAISE(ABORT, 'Brain-finding Turn lacks its exact verdict')
        WHEN NEW.local_feedback_batch_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM local_feedback_batch batch
            WHERE batch.id = NEW.local_feedback_batch_id
              AND batch.task_id = NEW.task_id
              AND batch.local_development_stage_id
                    = NEW.local_development_stage_id
              AND batch.task_epoch = NEW.task_epoch
              AND batch.stage_generation = NEW.stage_generation
              AND batch.status IN ('FROZEN', 'QUEUED', 'DISPATCHED'))
            THEN RAISE(ABORT, 'Local-feedback Turn lacks its exact batch')
    END;
END;

CREATE TRIGGER local_stage_turn_request_identity_immutable
BEFORE UPDATE OF id, command_id, stage_turn_id, task_id,
        local_development_stage_id, task_epoch, stage_generation, kind,
        queue_mode, predecessor_turn_id, brain_review_episode_id,
        local_feedback_batch_id, prompt_digest, requested_by, requested_at_ms
ON local_stage_turn_request
BEGIN SELECT RAISE(ABORT, 'Local StageTurn request identity is immutable'); END;

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

CREATE TRIGGER dispatch_ticket_local_stage_turn_insert
BEFORE INSERT ON dispatch_ticket
WHEN (NEW.callback_route = 'STAGE_TURN_RESULT'
      OR NEW.operation_kind = 'EXECUTE_STAGE_TURN')
  AND (EXISTS (
          SELECT 1
          FROM stage_turn turn
          JOIN stage owner ON owner.id = turn.stage_id
          WHERE turn.operation_id = NEW.operation_id
            AND owner.kind = 'LOCAL_DEVELOPMENT')
    OR EXISTS (
          SELECT 1 FROM stage owner
          WHERE owner.id = NEW.stage_id
            AND owner.kind = 'LOCAL_DEVELOPMENT'))
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM local_stage_turn_request request
        JOIN stage_turn turn ON turn.id = request.stage_turn_id
        WHERE request.stage_turn_id = NEW.owner_id
          AND turn.operation_id = NEW.operation_id
          AND turn.attempt = NEW.attempt
          AND turn.task_epoch = NEW.task_epoch
          AND turn.stage_id = NEW.stage_id
          AND turn.stage_generation = NEW.stage_generation
          AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
          AND turn.expected_head_sha = NEW.expected_head_sha
          AND turn.expected_base_sha = NEW.expected_base_sha
          AND NEW.operation_kind = 'EXECUTE_STAGE_TURN'
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'STAGE_TURN'
          AND NEW.callback_route = 'STAGE_TURN_RESULT'
          AND NEW.lane_mask IN (1, 2)
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1 AND NEW.writer_required = 1
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Local StageTurn ticket is not exact') END;
END;

CREATE TRIGGER dispatch_ticket_local_validation_insert
BEFORE INSERT ON dispatch_ticket
WHEN (NEW.callback_route = 'STAGE_VALIDATION_RESULT'
      OR NEW.operation_kind = 'VALIDATE_LOCAL_DEVELOPMENT')
  AND (EXISTS (
          SELECT 1
          FROM validation_operation operation
          JOIN stage owner
            ON owner.id = operation.local_development_stage_id
          WHERE operation.operation_id = NEW.operation_id
            AND owner.kind = 'LOCAL_DEVELOPMENT')
    OR EXISTS (
          SELECT 1 FROM stage owner
          WHERE owner.id = NEW.stage_id
            AND owner.kind = 'LOCAL_DEVELOPMENT'))
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM validation_operation operation
        WHERE operation.operation_id = NEW.operation_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.local_development_stage_id = NEW.stage_id
          AND operation.stage_generation = NEW.stage_generation
          AND operation.semantic_attempt = NEW.attempt
          AND operation.code_fingerprint = NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND NEW.operation_kind = 'VALIDATE_LOCAL_DEVELOPMENT'
          AND NEW.async_family = 'VALIDATION'
          AND NEW.owner_kind = 'STAGE'
          AND NEW.owner_id = NEW.stage_id
          AND NEW.callback_route = 'STAGE_VALIDATION_RESULT'
          AND NEW.lane_mask = 4
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'Local Validation ticket is not exact') END;
END;

-- Local-only Stage receipts avoid rebuilding the shared receipt table while
-- Plan and Remote migrations are landing in parallel. The owner store reads
-- this table only for the six explicit Local causes below.
CREATE TABLE local_stage_command_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    stage_id                          TEXT    NOT NULL
        REFERENCES stage(id) ON DELETE CASCADE,
    task_id                           TEXT    NOT NULL
        REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause IN (
        'REQUEST_LOCAL_RESULT', 'REPLACE_LOCAL_RESULT',
        'CLEAR_LOCAL_RESULT', 'ACCEPT_LOCAL_CODE_RESULT',
        'BEGIN_LOCAL_VALIDATION', 'ACCEPT_BRAIN_BUDGET_EXHAUSTION')),
    actor                             TEXT    NOT NULL CHECK (length(actor) > 0),
    disposition                       TEXT    NOT NULL CHECK (disposition IN (
        'APPLIED', 'SUPERSEDED')),
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
    returned_kind                     TEXT    NOT NULL
        CHECK (returned_kind = 'LOCAL_DEVELOPMENT'),
    returned_generation               INTEGER NOT NULL CHECK (returned_generation > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version >= 0),
    returned_checkpoint               TEXT    NOT NULL CHECK (returned_checkpoint IN (
        'IMPLEMENTING', 'VALIDATING', 'BRAIN_REVIEW', 'LOCAL_REVIEW',
        'PUBLISHING', 'ADDRESSING_BRAIN_FINDINGS',
        'ADDRESSING_LOCAL_FEEDBACK')),
    returned_end_reason               TEXT CHECK (returned_end_reason IS NULL),
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
    CHECK ((expected_task_epoch IS NULL
            AND expected_stage_generation IS NULL
            AND expected_stage_version IS NULL)
        OR (expected_task_epoch IS NOT NULL
            AND expected_stage_generation IS NOT NULL
            AND expected_stage_version IS NOT NULL)),
    CHECK (source_checkpoint IS NULL OR expected_task_epoch IS NOT NULL),
    CHECK ((expected_task_epoch IS NOT NULL) = (cause IN (
        'REQUEST_LOCAL_RESULT', 'BEGIN_LOCAL_VALIDATION',
        'ACCEPT_BRAIN_BUDGET_EXHAUSTION'))),
    CHECK ((source_checkpoint IS NOT NULL) = (cause IN (
        'REQUEST_LOCAL_RESULT', 'BEGIN_LOCAL_VALIDATION',
        'ACCEPT_BRAIN_BUDGET_EXHAUSTION'))),
    CHECK (expected_stage_version IS NULL
        OR returned_version = expected_stage_version + 1),
    CHECK ((subject_operation_id IS NULL
            AND subject_task_epoch IS NULL AND subject_stage_id IS NULL
            AND subject_stage_generation IS NULL AND subject_attempt IS NULL
            AND subject_expected_code_fingerprint IS NULL
            AND subject_expected_head_sha IS NULL
            AND subject_expected_base_sha IS NULL)
        OR (subject_operation_id IS NOT NULL
            AND length(subject_operation_id) > 0
            AND subject_task_epoch IS NOT NULL
            AND subject_stage_id = stage_id
            AND subject_stage_generation = returned_generation
            AND subject_attempt > 0)),
    CHECK ((subject_operation_id IS NOT NULL) = (cause IN (
        'REQUEST_LOCAL_RESULT', 'REPLACE_LOCAL_RESULT',
        'CLEAR_LOCAL_RESULT', 'ACCEPT_LOCAL_CODE_RESULT'))),
    CHECK ((returned_pending_operation_id IS NULL
            AND returned_pending_task_epoch IS NULL
            AND returned_pending_stage_id IS NULL
            AND returned_pending_stage_generation IS NULL
            AND returned_pending_attempt IS NULL
            AND returned_pending_code_fingerprint IS NULL
            AND returned_pending_head_sha IS NULL
            AND returned_pending_base_sha IS NULL)
        OR (returned_pending_operation_id IS NOT NULL
            AND length(returned_pending_operation_id) > 0
            AND returned_pending_task_epoch IS NOT NULL
            AND returned_pending_stage_id = stage_id
            AND returned_pending_stage_generation = returned_generation
            AND returned_pending_attempt > 0)),
    CHECK (returned_end_reason IS NULL),
    CHECK (disposition <> 'SUPERSEDED' OR subject_operation_id IS NOT NULL),
    FOREIGN KEY (stage_id, task_id, returned_kind, returned_generation)
        REFERENCES stage(id, task_id, kind, generation) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER local_stage_command_receipt_insert
BEFORE INSERT ON local_stage_command_receipt
BEGIN
    SELECT CASE
        WHEN NEW.cause NOT IN ('REQUEST_LOCAL_RESULT', 'REPLACE_LOCAL_RESULT',
                'CLEAR_LOCAL_RESULT', 'ACCEPT_LOCAL_CODE_RESULT',
                'BEGIN_LOCAL_VALIDATION',
                'ACCEPT_BRAIN_BUDGET_EXHAUSTION')
          OR NEW.returned_kind <> 'LOCAL_DEVELOPMENT'
          OR NEW.actor IS NULL OR length(NEW.actor) = 0
          OR NEW.proof_id IS NULL OR length(NEW.proof_id) = 0
          OR NEW.disposition NOT IN ('APPLIED', 'SUPERSEDED')
          OR NOT EXISTS (
              SELECT 1 FROM stage owner JOIN tasks task ON task.id = owner.task_id
              WHERE owner.id = NEW.stage_id AND owner.task_id = NEW.task_id
                AND owner.kind = 'LOCAL_DEVELOPMENT'
                AND owner.generation = NEW.returned_generation
                AND task.workflow_version = 'V2')
          OR EXISTS (SELECT 1 FROM stage_command_receipt shared
                     WHERE shared.stage_id = NEW.stage_id
                       AND shared.command_id = NEW.command_id)
          OR (NEW.disposition = 'APPLIED' AND NOT EXISTS (
              SELECT 1 FROM stage_transition transition
              WHERE transition.stage_id = NEW.stage_id
                AND transition.command_id = NEW.command_id
                AND transition.generation = NEW.returned_generation
                AND transition.to_checkpoint = NEW.returned_checkpoint
                AND transition.stage_version = NEW.returned_version
                AND transition.cause = NEW.cause
                AND transition.actor = NEW.actor))
            THEN RAISE(ABORT, 'Local Stage receipt owner or transition is invalid')
        WHEN NEW.cause IN ('REQUEST_LOCAL_RESULT', 'BEGIN_LOCAL_VALIDATION',
                'ACCEPT_BRAIN_BUDGET_EXHAUSTION') AND (
            NEW.expected_task_epoch IS NULL
            OR NEW.expected_stage_generation IS NULL
            OR NEW.expected_stage_version IS NULL
            OR NEW.returned_version <> NEW.expected_stage_version + 1)
            THEN RAISE(ABORT, 'Local structural receipt fence is incomplete')
        WHEN NEW.cause IN ('REPLACE_LOCAL_RESULT', 'CLEAR_LOCAL_RESULT',
                'ACCEPT_LOCAL_CODE_RESULT') AND (
            NEW.expected_task_epoch IS NOT NULL
            OR NEW.expected_stage_generation IS NOT NULL
            OR NEW.expected_stage_version IS NOT NULL
            OR NEW.source_checkpoint IS NOT NULL)
            THEN RAISE(ABORT, 'Local result receipt invented a structural fence')
        WHEN NEW.cause IN ('REQUEST_LOCAL_RESULT', 'REPLACE_LOCAL_RESULT',
                'CLEAR_LOCAL_RESULT', 'ACCEPT_LOCAL_CODE_RESULT') AND (
            NEW.subject_operation_id IS NULL
            OR NEW.subject_task_epoch IS NULL
            OR NEW.subject_stage_id <> NEW.stage_id
            OR NEW.subject_stage_generation <> NEW.returned_generation
            OR NEW.subject_attempt IS NULL OR NEW.subject_attempt < 1)
            THEN RAISE(ABORT, 'Local result receipt lacks its complete subject')
        WHEN NEW.cause IN ('BEGIN_LOCAL_VALIDATION',
                'ACCEPT_BRAIN_BUDGET_EXHAUSTION')
          AND NEW.subject_operation_id IS NOT NULL
            THEN RAISE(ABORT, 'Local structural proof cannot carry a result subject')
        WHEN NEW.cause = 'REQUEST_LOCAL_RESULT' AND (
            NEW.source_checkpoint <> NEW.returned_checkpoint
            OR NEW.returned_pending_operation_id <> NEW.subject_operation_id
            OR NEW.returned_pending_task_epoch <> NEW.subject_task_epoch
            OR NEW.returned_pending_stage_id <> NEW.subject_stage_id
            OR NEW.returned_pending_stage_generation
                <> NEW.subject_stage_generation
            OR NEW.returned_pending_attempt <> NEW.subject_attempt
            OR NEW.returned_pending_code_fingerprint
                IS NOT NEW.subject_expected_code_fingerprint
            OR NEW.returned_pending_head_sha
                IS NOT NEW.subject_expected_head_sha
            OR NEW.returned_pending_base_sha
                IS NOT NEW.subject_expected_base_sha
            OR NOT EXISTS (
                SELECT 1 FROM dispatch_ticket ticket
                WHERE ticket.operation_id = NEW.subject_operation_id
                  AND ticket.task_id = NEW.task_id
                  AND ticket.task_epoch = NEW.subject_task_epoch
                  AND ticket.stage_id = NEW.stage_id
                  AND ticket.stage_generation = NEW.subject_stage_generation
                  AND ticket.attempt = NEW.subject_attempt
                  AND ticket.expected_code_fingerprint
                        IS NEW.subject_expected_code_fingerprint
                  AND ticket.expected_head_sha IS NEW.subject_expected_head_sha
                  AND ticket.expected_base_sha IS NEW.subject_expected_base_sha
                  AND ticket.status = 'REQUESTED'
                  AND ((ticket.callback_route = 'STAGE_TURN_RESULT'
                        AND EXISTS (SELECT 1 FROM local_stage_turn_request request
                            JOIN stage_turn turn ON turn.id = request.stage_turn_id
                            WHERE request.id = NEW.proof_id
                              AND turn.operation_id = ticket.operation_id))
                    OR (ticket.callback_route = 'STAGE_VALIDATION_RESULT'
                        AND EXISTS (SELECT 1 FROM validation_operation operation
                            WHERE operation.id = NEW.proof_id
                              AND operation.operation_id = ticket.operation_id)))))
            THEN RAISE(ABORT, 'Local requested result lacks its exact durable work')
        WHEN NEW.cause = 'REPLACE_LOCAL_RESULT' AND NOT EXISTS (
                SELECT 1
                FROM local_stage_turn_request replacement
                JOIN stage_turn next ON next.id = replacement.stage_turn_id
                JOIN local_stage_turn_request previous
                  ON previous.stage_turn_id = replacement.predecessor_turn_id
                JOIN stage_turn old ON old.id = previous.stage_turn_id
                WHERE replacement.id = NEW.proof_id
                  AND old.operation_id = NEW.subject_operation_id
                  AND replacement.task_id = NEW.task_id
                  AND replacement.local_development_stage_id = NEW.stage_id
                  AND replacement.task_epoch = NEW.subject_task_epoch
                  AND replacement.stage_generation = NEW.subject_stage_generation
                  AND next.operation_id <> old.operation_id
                  AND (old.status IN (
                          'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
                    OR previous.cancellation_requested_at_ms IS NOT NULL))
            THEN RAISE(ABORT, 'Local replacement result lacks its exact queue edge')
        WHEN NEW.cause = 'REPLACE_LOCAL_RESULT'
          AND NEW.disposition = 'APPLIED' AND (
            NEW.returned_pending_operation_id IS NULL
            OR NOT EXISTS (
                SELECT 1
                FROM local_stage_turn_request replacement
                JOIN stage_turn next ON next.id = replacement.stage_turn_id
                JOIN dispatch_ticket ticket ON ticket.operation_id = next.operation_id
                WHERE replacement.id = NEW.proof_id
                  AND next.operation_id = NEW.returned_pending_operation_id
                  AND next.attempt = NEW.returned_pending_attempt
                  AND next.expected_code_fingerprint
                        IS NEW.returned_pending_code_fingerprint
                  AND next.expected_head_sha IS NEW.returned_pending_head_sha
                  AND next.expected_base_sha IS NEW.returned_pending_base_sha
                  AND ticket.status = 'REQUESTED'))
            THEN RAISE(ABORT, 'Applied Local replacement did not arm its exact successor')
        WHEN NEW.cause = 'REPLACE_LOCAL_RESULT'
          AND NEW.disposition = 'SUPERSEDED'
          AND NEW.returned_pending_operation_id IS NEW.subject_operation_id
            THEN RAISE(ABORT, 'Superseded Local replacement preserved its stale subject')
        WHEN NEW.cause = 'CLEAR_LOCAL_RESULT' AND NOT EXISTS (
                SELECT 1
                FROM local_stage_turn_request request
                JOIN stage_turn turn ON turn.id = request.stage_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                WHERE request.id = NEW.proof_id
                  AND request.task_id = NEW.task_id
                  AND request.local_development_stage_id = NEW.stage_id
                  AND request.task_epoch = NEW.subject_task_epoch
                  AND request.stage_generation = NEW.subject_stage_generation
                  AND turn.operation_id = NEW.subject_operation_id
                  AND turn.attempt = NEW.subject_attempt
                  AND turn.expected_code_fingerprint
                        IS NEW.subject_expected_code_fingerprint
                  AND turn.expected_head_sha IS NEW.subject_expected_head_sha
                  AND turn.expected_base_sha IS NEW.subject_expected_base_sha
                  AND ticket.task_id = NEW.task_id
                  AND ticket.task_epoch = NEW.subject_task_epoch
                  AND ticket.stage_id = NEW.stage_id
                  AND ticket.stage_generation = NEW.subject_stage_generation
                  AND ticket.attempt = NEW.subject_attempt
                  AND ticket.status IN ('RESULT_PENDING', 'FAILED', 'CANCELED'))
            THEN RAISE(ABORT, 'Local clear result lacks terminal delivery evidence')
        WHEN NEW.cause = 'CLEAR_LOCAL_RESULT'
          AND NEW.disposition = 'APPLIED'
          AND NEW.returned_pending_operation_id IS NOT NULL
            THEN RAISE(ABORT, 'Applied Local clear retained a pending result')
        WHEN NEW.cause = 'CLEAR_LOCAL_RESULT'
          AND NEW.disposition = 'SUPERSEDED'
          AND NEW.returned_pending_operation_id IS NEW.subject_operation_id
            THEN RAISE(ABORT, 'Superseded Local clear preserved its stale subject')
        WHEN NEW.cause = 'ACCEPT_LOCAL_CODE_RESULT' AND (
            NEW.returned_pending_operation_id IS NOT NULL
            OR NOT EXISTS (
                SELECT 1
                FROM dev_report report
                JOIN stage_turn turn ON turn.id = report.stage_turn_id
                WHERE report.id = NEW.proof_id
                  AND report.workflow_version = 'V2'
                  AND report.task_id = NEW.task_id
                  AND report.local_development_stage_id = NEW.stage_id
                  AND report.task_epoch = NEW.subject_task_epoch
                  AND report.stage_generation = NEW.subject_stage_generation
                  AND turn.operation_id = NEW.subject_operation_id
                  AND turn.attempt = NEW.subject_attempt
                  AND report.source_code_fingerprint
                        IS NEW.subject_expected_code_fingerprint
                  AND report.source_head_sha IS NEW.subject_expected_head_sha
                  AND report.source_base_sha IS NEW.subject_expected_base_sha
                  AND NOT EXISTS (
                      SELECT 1 FROM dev_report newer
                      WHERE newer.workflow_version = 'V2'
                        AND newer.local_development_stage_id
                            = report.local_development_stage_id
                        AND newer.revision > report.revision)
                  AND EXISTS (
                      SELECT 1 FROM stage_transition transition
                      WHERE transition.stage_id = NEW.stage_id
                        AND transition.command_id = NEW.command_id
                        AND ((transition.from_checkpoint = 'IMPLEMENTING'
                              AND transition.to_checkpoint = 'VALIDATING')
                          OR (transition.from_checkpoint IN (
                                  'ADDRESSING_BRAIN_FINDINGS',
                                  'ADDRESSING_LOCAL_FEEDBACK')
                              AND transition.to_checkpoint = 'IMPLEMENTING')))))
            THEN RAISE(ABORT, 'Local code result lacks its changed-subject DevReport')
        WHEN NEW.cause = 'BEGIN_LOCAL_VALIDATION' AND (
            NEW.source_checkpoint <> 'IMPLEMENTING'
            OR NEW.returned_checkpoint <> 'VALIDATING'
            OR NEW.returned_pending_operation_id IS NOT NULL
            OR NOT EXISTS (
                SELECT 1 FROM dev_report report
                WHERE report.id = NEW.proof_id
                  AND report.task_id = NEW.task_id
                  AND report.local_development_stage_id = NEW.stage_id
                  AND report.stage_generation = NEW.returned_generation
                  AND NOT EXISTS (SELECT 1 FROM dev_report newer
                      WHERE newer.workflow_version = 'V2'
                        AND newer.local_development_stage_id
                            = report.local_development_stage_id
                        AND newer.revision > report.revision)))
            THEN RAISE(ABORT, 'Local validation transition lacks latest DevReport')
        WHEN NEW.cause = 'ACCEPT_BRAIN_BUDGET_EXHAUSTION' AND (
            NEW.source_checkpoint <> 'BRAIN_REVIEW'
            OR NEW.returned_checkpoint <> 'LOCAL_REVIEW'
            OR NEW.returned_pending_operation_id IS NOT NULL
            OR NOT EXISTS (
                SELECT 1 FROM task_blocker blocker
                WHERE blocker.id = NEW.proof_id
                  AND blocker.task_id = NEW.task_id
                  AND blocker.stage_id = NEW.stage_id
                  AND blocker.owner_kind = 'STAGE'
                  AND blocker.owner_id = NEW.stage_id
                  AND blocker.blocker_type = 'BRAIN_BUDGET_EXHAUSTED'
                  AND blocker.status = 'OPEN'))
            THEN RAISE(ABORT, 'Brain exhaustion transition lacks its exact blocker')
    END;
END;

CREATE TRIGGER local_stage_command_receipt_immutable
BEFORE UPDATE ON local_stage_command_receipt
BEGIN SELECT RAISE(ABORT, 'Local Stage command receipt is immutable'); END;

CREATE TRIGGER stage_command_receipt_local_id_collision
BEFORE INSERT ON stage_command_receipt
WHEN EXISTS (SELECT 1 FROM local_stage_command_receipt local
             WHERE local.stage_id = NEW.stage_id
               AND local.command_id = NEW.command_id)
BEGIN SELECT RAISE(ABORT, 'Stage command id is already used by Local Development'); END;

-- Task Brain admission is a TaskManager command, isolated from the shared
-- receipt schema for the same parallel-migration reason.
CREATE TABLE task_brain_request_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    task_id                           TEXT    NOT NULL
        REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause = 'REQUEST_BRAIN_REVIEW'),
    actor                             TEXT    NOT NULL CHECK (length(actor) > 0),
    disposition                       TEXT    NOT NULL CHECK (disposition = 'APPLIED'),
    expected_task_epoch               INTEGER NOT NULL CHECK (expected_task_epoch > 0),
    expected_task_version             INTEGER NOT NULL CHECK (expected_task_version >= 0),
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
    proof_id                          TEXT    NOT NULL CHECK (length(proof_id) > 0),
    next_stage_id                     TEXT,
    next_stage_kind                   TEXT CHECK (next_stage_kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT', 'CLEANUP')),
    next_stage_generation             INTEGER CHECK (next_stage_generation > 0),
    returned_trunk_id                 TEXT    NOT NULL REFERENCES threads(id),
    returned_lifecycle                TEXT    NOT NULL CHECK (returned_lifecycle = 'ACTIVE'),
    returned_epoch                    INTEGER NOT NULL CHECK (returned_epoch > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version >= 0),
    returned_current_stage_id         TEXT    NOT NULL,
    returned_pending_task_epoch       INTEGER NOT NULL CHECK (returned_pending_task_epoch > 0),
    returned_pending_stage_id         TEXT    NOT NULL,
    returned_pending_stage_generation INTEGER NOT NULL
        CHECK (returned_pending_stage_generation > 0),
    returned_pending_operation_id     TEXT    NOT NULL
        CHECK (length(returned_pending_operation_id) > 0),
    returned_pending_attempt          INTEGER NOT NULL CHECK (returned_pending_attempt > 0),
    returned_pending_code_fingerprint TEXT,
    returned_pending_head_sha         TEXT,
    returned_pending_base_sha         TEXT,
    returned_last_brain_verdict       TEXT CHECK (returned_last_brain_verdict IN (
        'APPROVED', 'CHANGES_REQUESTED')),
    returned_last_brain_task_epoch    INTEGER CHECK (returned_last_brain_task_epoch > 0),
    returned_last_brain_stage_id      TEXT,
    returned_last_brain_stage_generation INTEGER,
    returned_last_brain_operation_id  TEXT,
    returned_last_brain_attempt       INTEGER,
    returned_last_brain_code_fingerprint TEXT,
    returned_last_brain_head_sha      TEXT,
    returned_last_brain_base_sha      TEXT,
    returned_terminal_intent          TEXT CHECK (returned_terminal_intent IN (
        'CANCELED', 'COMPLETED', 'REMOTE_CLOSED')),
    recorded_at_ms                    INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    UNIQUE (task_id, command_id),
    CHECK (brain_verdict IS NULL),
    CHECK (next_stage_id IS NULL AND next_stage_kind IS NULL
        AND next_stage_generation IS NULL),
    CHECK (returned_terminal_intent IS NULL),
    CHECK (returned_epoch = expected_task_epoch),
    CHECK (returned_version = expected_task_version + 1),
    CHECK (returned_current_stage_id = subject_stage_id),
    CHECK (returned_pending_task_epoch = subject_task_epoch
        AND returned_pending_stage_id = subject_stage_id
        AND returned_pending_stage_generation = subject_stage_generation
        AND returned_pending_operation_id = subject_operation_id
        AND returned_pending_attempt = subject_attempt
        AND returned_pending_code_fingerprint
            IS subject_expected_code_fingerprint
        AND returned_pending_head_sha IS subject_expected_head_sha
        AND returned_pending_base_sha IS subject_expected_base_sha),
    CHECK ((returned_last_brain_operation_id IS NULL
            AND returned_last_brain_verdict IS NULL
            AND returned_last_brain_task_epoch IS NULL
            AND returned_last_brain_stage_id IS NULL
            AND returned_last_brain_stage_generation IS NULL
            AND returned_last_brain_attempt IS NULL
            AND returned_last_brain_code_fingerprint IS NULL
            AND returned_last_brain_head_sha IS NULL
            AND returned_last_brain_base_sha IS NULL)
        OR (returned_last_brain_operation_id IS NOT NULL
            AND length(returned_last_brain_operation_id) > 0
            AND returned_last_brain_verdict IS NOT NULL
            AND returned_last_brain_task_epoch IS NOT NULL
            AND returned_last_brain_stage_id IS NOT NULL
            AND returned_last_brain_stage_generation > 0
            AND returned_last_brain_attempt > 0)),
    FOREIGN KEY (subject_stage_id, task_id)
        REFERENCES stage(id, task_id) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (returned_current_stage_id, task_id)
        REFERENCES stage(id, task_id) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER task_brain_request_receipt_insert
BEFORE INSERT ON task_brain_request_receipt
BEGIN
    SELECT CASE WHEN
        NEW.cause <> 'REQUEST_BRAIN_REVIEW'
        OR NEW.disposition <> 'APPLIED'
        OR NEW.expected_task_epoch IS NULL
        OR NEW.expected_task_version IS NULL
        OR NEW.subject_operation_id IS NULL
        OR NEW.subject_task_epoch <> NEW.expected_task_epoch
        OR NEW.subject_stage_id IS NULL
        OR NEW.subject_stage_generation IS NULL
        OR NEW.proof_id IS NULL
        OR NEW.brain_verdict IS NOT NULL
        OR NEW.next_stage_id IS NOT NULL
        OR NEW.returned_lifecycle <> 'ACTIVE'
        OR NEW.returned_epoch <> NEW.expected_task_epoch
        OR NEW.returned_version <> NEW.expected_task_version + 1
        OR NEW.returned_current_stage_id <> NEW.subject_stage_id
        OR NEW.returned_pending_task_epoch <> NEW.subject_task_epoch
        OR NEW.returned_pending_stage_id <> NEW.subject_stage_id
        OR NEW.returned_pending_stage_generation
            <> NEW.subject_stage_generation
        OR NEW.returned_pending_operation_id <> NEW.subject_operation_id
        OR NEW.returned_pending_attempt <> NEW.subject_attempt
        OR NEW.returned_pending_code_fingerprint
            IS NOT NEW.subject_expected_code_fingerprint
        OR NEW.returned_pending_head_sha IS NOT NEW.subject_expected_head_sha
        OR NEW.returned_pending_base_sha IS NOT NEW.subject_expected_base_sha
        OR EXISTS (SELECT 1 FROM task_command_receipt shared
                   WHERE shared.task_id = NEW.task_id
                     AND shared.command_id = NEW.command_id)
        OR NOT EXISTS (
            SELECT 1
            FROM brain_review_episode episode
            JOIN task_turn turn ON turn.id = episode.task_turn_id
            JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
            JOIN tasks task ON task.id = episode.task_id
            JOIN task_current_stage current ON current.task_id = task.id
            WHERE episode.id = NEW.proof_id
              AND episode.task_id = NEW.task_id
              AND episode.task_epoch = NEW.subject_task_epoch
              AND episode.local_development_stage_id = NEW.subject_stage_id
              AND episode.stage_generation = NEW.subject_stage_generation
              AND episode.status = 'REQUESTED'
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND turn.expected_code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND turn.expected_head_sha IS NEW.subject_expected_head_sha
              AND turn.expected_base_sha IS NEW.subject_expected_base_sha
              AND ticket.owner_kind = 'TASK_TURN' AND ticket.owner_id = turn.id
              AND ticket.operation_kind = 'EXECUTE_TASK_TURN'
              AND ticket.async_family = 'AGENT_TURN'
              AND ticket.callback_route = 'TASK_TURN_RESULT'
              AND ticket.status = 'REQUESTED'
              AND task.workflow_version = 'V2'
              AND task.lifecycle_state = 'ACTIVE'
              AND task.epoch = NEW.subject_task_epoch
              AND current.stage_id = NEW.subject_stage_id
              AND current.stage_generation = NEW.subject_stage_generation)
        OR NOT EXISTS (
            SELECT 1 FROM task_transition transition
            WHERE transition.task_id = NEW.task_id
              AND transition.command_id = NEW.command_id
              AND transition.epoch = NEW.returned_epoch
              AND transition.to_state = 'ACTIVE'
              AND transition.aggregate_version = NEW.returned_version
              AND transition.cause = 'REQUEST_BRAIN_REVIEW'
              AND transition.actor = NEW.actor)
    THEN RAISE(ABORT, 'Task Brain request receipt is not exact') END;
END;

CREATE TRIGGER task_brain_request_receipt_immutable
BEFORE UPDATE ON task_brain_request_receipt
BEGIN SELECT RAISE(ABORT, 'Task Brain request receipt is immutable'); END;

CREATE TRIGGER task_command_receipt_brain_request_id_collision
BEFORE INSERT ON task_command_receipt
WHEN EXISTS (SELECT 1 FROM task_brain_request_receipt brain
             WHERE brain.task_id = NEW.task_id
               AND brain.command_id = NEW.command_id)
BEGIN SELECT RAISE(ABORT, 'Task command id is already used by a Brain request'); END;
