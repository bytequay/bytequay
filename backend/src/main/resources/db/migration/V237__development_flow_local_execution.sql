-- Runtime receipts for the exact Local Development execution path. These
-- tables contain owner-specific replay facts; dispatch state remains solely
-- in dispatch_ticket and aggregate state remains in Task/Stage receipts.

CREATE TABLE local_initial_implementation_receipt (
    local_development_stage_id TEXT    NOT NULL PRIMARY KEY
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL
        REFERENCES tasks(id) ON DELETE CASCADE,
    plan_approval_id           TEXT    NOT NULL UNIQUE REFERENCES plan_approval(id),
    stage_turn_request_id      TEXT    NOT NULL UNIQUE
        REFERENCES local_stage_turn_request(id),
    stage_turn_id              TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    operation_id               TEXT    NOT NULL UNIQUE,
    ticket_id                  TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    recorded_at_ms             INTEGER NOT NULL CHECK (recorded_at_ms >= 0)
);

CREATE TRIGGER local_initial_implementation_receipt_insert
BEFORE INSERT ON local_initial_implementation_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM local_development_stage local
    JOIN plan_approval approval ON approval.id = NEW.plan_approval_id
    JOIN plan_revision revision ON revision.id = approval.plan_revision_id
    JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
    JOIN local_stage_turn_request request
      ON request.id = NEW.stage_turn_request_id
    JOIN stage_turn turn ON turn.id = request.stage_turn_id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.ticket_id
    JOIN stage_initial_result_request initial
      ON initial.stage_id = local.stage_id
    WHERE local.stage_id = NEW.local_development_stage_id
      AND local.task_id = NEW.task_id
      AND plan.task_id = NEW.task_id
      AND request.local_development_stage_id = local.stage_id
      AND request.task_id = NEW.task_id
      AND request.kind = 'IMPLEMENTATION'
      AND request.queue_mode = 'IMMEDIATE'
      AND request.stage_turn_id = NEW.stage_turn_id
      AND turn.operation_id = NEW.operation_id
      AND ticket.operation_id = NEW.operation_id
      AND ticket.owner_kind = 'STAGE_TURN'
      AND ticket.owner_id = NEW.stage_turn_id
      AND ticket.callback_route = 'STAGE_TURN_RESULT'
      AND initial.turn_owner_kind = 'STAGE_TURN'
      AND initial.turn_id = NEW.stage_turn_id
      AND initial.pending_operation_id = NEW.operation_id)
BEGIN SELECT RAISE(ABORT, 'initial Local implementation receipt is not exact'); END;

CREATE TRIGGER local_initial_implementation_receipt_immutable
BEFORE UPDATE ON local_initial_implementation_receipt
BEGIN SELECT RAISE(ABORT, 'initial Local implementation receipt is immutable'); END;

CREATE TABLE local_stage_turn_delivery_receipt (
    stage_turn_id       TEXT    NOT NULL PRIMARY KEY REFERENCES stage_turn(id),
    operation_id        TEXT    NOT NULL UNIQUE,
    raw_outcome         TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest   TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance          TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    dev_report_id       TEXT UNIQUE REFERENCES dev_report(id),
    validation_operation_id TEXT UNIQUE REFERENCES validation_operation(id),
    recorded_at_ms      INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK ((dev_report_id IS NOT NULL) =
        (acceptance = 'ACCEPTED' AND raw_outcome = 'SUCCEEDED')),
    CHECK ((validation_operation_id IS NOT NULL) = (dev_report_id IS NOT NULL))
);

CREATE TRIGGER local_stage_turn_delivery_receipt_insert
BEFORE INSERT ON local_stage_turn_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM stage_turn turn
    JOIN local_stage_turn_request request ON request.stage_turn_id = turn.id
    WHERE turn.id = NEW.stage_turn_id
      AND turn.operation_id = NEW.operation_id
      AND turn.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
      AND (NEW.dev_report_id IS NULL OR EXISTS (
          SELECT 1 FROM dev_report report
          WHERE report.id = NEW.dev_report_id
            AND report.stage_turn_id = turn.id))
      AND (NEW.validation_operation_id IS NULL OR EXISTS (
          SELECT 1 FROM validation_operation operation
          WHERE operation.id = NEW.validation_operation_id
            AND operation.dev_report_id = NEW.dev_report_id)))
BEGIN SELECT RAISE(ABORT, 'Local StageTurn delivery receipt is not exact'); END;

CREATE TRIGGER local_stage_turn_delivery_receipt_immutable
BEFORE UPDATE ON local_stage_turn_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Local StageTurn delivery receipt is immutable'); END;

CREATE TABLE local_validation_delivery_receipt (
    validation_operation_id TEXT    NOT NULL PRIMARY KEY
        REFERENCES validation_operation(id) ON DELETE CASCADE,
    operation_id             TEXT    NOT NULL UNIQUE,
    raw_outcome              TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest        TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance               TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    validation_evidence_id   TEXT UNIQUE REFERENCES validation_evidence(id),
    brain_review_episode_id  TEXT UNIQUE REFERENCES brain_review_episode(id),
    recorded_at_ms           INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK (brain_review_episode_id IS NULL OR validation_evidence_id IS NOT NULL)
);

CREATE TRIGGER local_validation_delivery_receipt_insert
BEFORE INSERT ON local_validation_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM validation_operation operation
    WHERE operation.id = NEW.validation_operation_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN ('COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')
      AND (NEW.validation_evidence_id IS NULL OR EXISTS (
          SELECT 1 FROM validation_evidence evidence
          WHERE evidence.id = NEW.validation_evidence_id
            AND evidence.validation_operation_id = operation.id))
      AND (NEW.brain_review_episode_id IS NULL OR EXISTS (
          SELECT 1 FROM brain_review_episode episode
          WHERE episode.id = NEW.brain_review_episode_id
            AND episode.validation_evidence_id = NEW.validation_evidence_id)))
BEGIN SELECT RAISE(ABORT, 'Local Validation delivery receipt is not exact'); END;

CREATE TRIGGER local_validation_delivery_receipt_immutable
BEFORE UPDATE ON local_validation_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Local Validation delivery receipt is immutable'); END;

CREATE TABLE local_brain_turn_delivery_receipt (
    task_turn_id          TEXT    NOT NULL PRIMARY KEY REFERENCES task_turn(id),
    operation_id          TEXT    NOT NULL UNIQUE,
    raw_outcome           TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest     TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance            TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    brain_review_episode_id TEXT  NOT NULL UNIQUE REFERENCES brain_review_episode(id),
    verdict               TEXT CHECK (verdict IN ('APPROVED', 'CHANGES_REQUESTED')),
    blocker_id            TEXT UNIQUE REFERENCES task_blocker(id),
    next_stage_turn_request_id TEXT UNIQUE REFERENCES local_stage_turn_request(id),
    recorded_at_ms        INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK ((verdict IS NOT NULL) = (raw_outcome = 'SUCCEEDED'
        AND acceptance = 'ACCEPTED')),
    CHECK (blocker_id IS NULL OR verdict IS NULL),
    CHECK (next_stage_turn_request_id IS NULL
        OR verdict = 'CHANGES_REQUESTED')
);

CREATE TRIGGER local_brain_turn_delivery_receipt_insert
BEFORE INSERT ON local_brain_turn_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM brain_review_episode episode
    JOIN task_turn turn ON turn.id = episode.task_turn_id
    WHERE episode.id = NEW.brain_review_episode_id
      AND turn.id = NEW.task_turn_id
      AND turn.operation_id = NEW.operation_id
      AND turn.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
      AND episode.status IN ('SUCCEEDED', 'FAILED', 'CANCELED',
          'SUPERSEDED', 'BUDGET_EXHAUSTED')
      AND episode.verdict IS NEW.verdict
      AND (NEW.blocker_id IS NULL OR EXISTS (
          SELECT 1 FROM task_blocker blocker
          WHERE blocker.id = NEW.blocker_id
            AND blocker.task_id = episode.task_id
            AND blocker.blocker_type = 'BRAIN_BUDGET_EXHAUSTED'))
      AND (NEW.next_stage_turn_request_id IS NULL OR EXISTS (
          SELECT 1 FROM local_stage_turn_request request
          WHERE request.id = NEW.next_stage_turn_request_id
            AND request.brain_review_episode_id = episode.id)))
BEGIN SELECT RAISE(ABORT, 'Local Brain delivery receipt is not exact'); END;

CREATE TRIGGER local_brain_turn_delivery_receipt_immutable
BEFORE UPDATE ON local_brain_turn_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Local Brain delivery receipt is immutable'); END;

-- Task owns clearing an exhausted Brain operation. This receipt mirrors the
-- Task command projection so a restart can load the new aggregate version
-- without treating Stage-owned evidence as Task state.
CREATE TABLE task_brain_budget_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL
        CHECK (cause = 'ACCEPT_BRAIN_BUDGET_EXHAUSTION'),
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
    subject_expected_code_fingerprint TEXT    NOT NULL,
    subject_expected_head_sha         TEXT    NOT NULL,
    subject_expected_base_sha         TEXT    NOT NULL,
    brain_verdict                     TEXT,
    proof_id                          TEXT    NOT NULL REFERENCES task_blocker(id),
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
    recorded_at_ms                    INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    UNIQUE (task_id, command_id),
    CHECK (expected_task_epoch IS NULL AND expected_task_version IS NULL),
    CHECK (brain_verdict IS NULL AND next_stage_id IS NULL
        AND next_stage_kind IS NULL AND next_stage_generation IS NULL),
    CHECK ((returned_pending_operation_id IS NULL
            AND returned_pending_task_epoch IS NULL
            AND returned_pending_stage_id IS NULL
            AND returned_pending_stage_generation IS NULL
            AND returned_pending_attempt IS NULL
            AND returned_pending_code_fingerprint IS NULL
            AND returned_pending_head_sha IS NULL
            AND returned_pending_base_sha IS NULL)
        OR disposition = 'SUPERSEDED')
);

CREATE TRIGGER task_brain_budget_receipt_insert
BEFORE INSERT ON task_brain_budget_receipt
BEGIN
    SELECT CASE
        WHEN NEW.disposition = 'APPLIED' AND NOT EXISTS (
            SELECT 1 FROM task_transition transition
            WHERE transition.task_id = NEW.task_id
              AND transition.command_id = NEW.command_id
              AND transition.cause = NEW.cause
              AND transition.aggregate_version = NEW.returned_version)
            THEN RAISE(ABORT, 'Brain budget receipt lacks its Task transition')
        WHEN NOT EXISTS (
            SELECT 1
            FROM task_blocker blocker
            JOIN brain_review_episode episode
              ON episode.task_id = blocker.task_id
            JOIN task_turn turn ON turn.id = episode.task_turn_id
            WHERE blocker.id = NEW.proof_id
              AND blocker.task_id = NEW.task_id
              AND blocker.stage_id = NEW.subject_stage_id
              AND blocker.blocker_type = 'BRAIN_BUDGET_EXHAUSTED'
              AND blocker.status = 'OPEN'
              AND episode.status = 'BUDGET_EXHAUSTED'
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.task_epoch = NEW.subject_task_epoch
              AND turn.trigger_stage_id = NEW.subject_stage_id
              AND turn.trigger_stage_generation = NEW.subject_stage_generation
              AND turn.expected_code_fingerprint
                    = NEW.subject_expected_code_fingerprint
              AND turn.expected_head_sha = NEW.subject_expected_head_sha
              AND turn.expected_base_sha = NEW.subject_expected_base_sha
              AND turn.status = 'FAILED')
            THEN RAISE(ABORT, 'Brain budget receipt lacks exact terminal evidence')
    END;
END;

CREATE TRIGGER task_brain_budget_receipt_immutable
BEFORE UPDATE ON task_brain_budget_receipt
BEGIN SELECT RAISE(ABORT, 'Brain budget receipt is immutable'); END;
