-- Install the exact V2 provisioning-to-Plan runtime protocol. These tables
-- remain inert until V2 dispatch routing is enabled.

-- A Stage is created before its first typed Turn can reference it. Record the
-- one permitted version-0 result request in its own immutable receipt instead
-- of encoding a fake structural transition in stage_command_receipt.
CREATE TABLE stage_initial_result_request (
    id                         TEXT    NOT NULL PRIMARY KEY,
    stage_id                   TEXT    NOT NULL UNIQUE REFERENCES stage(id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    stage_kind                 TEXT    NOT NULL CHECK (stage_kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT', 'CLEANUP')),
    command_id                 TEXT    NOT NULL,
    cause                      TEXT    NOT NULL CHECK (cause IN (
        'REQUEST_PLAN_DRAFT', 'REQUEST_LOCAL_RESULT')),
    actor                      TEXT    NOT NULL,
    expected_task_epoch        INTEGER NOT NULL CHECK (expected_task_epoch > 0),
    expected_stage_generation  INTEGER NOT NULL CHECK (expected_stage_generation > 0),
    expected_stage_version     INTEGER NOT NULL CHECK (expected_stage_version = 0),
    returned_stage_version     INTEGER NOT NULL CHECK (returned_stage_version = 1),
    checkpoint                 TEXT    NOT NULL,
    turn_owner_kind            TEXT    NOT NULL CHECK (turn_owner_kind IN (
        'TASK_TURN', 'STAGE_TURN')),
    turn_id                    TEXT    NOT NULL UNIQUE,
    pending_task_epoch         INTEGER NOT NULL CHECK (pending_task_epoch > 0),
    pending_stage_id           TEXT    NOT NULL,
    pending_stage_generation   INTEGER NOT NULL CHECK (pending_stage_generation > 0),
    pending_operation_id       TEXT    NOT NULL UNIQUE,
    pending_attempt            INTEGER NOT NULL CHECK (pending_attempt > 0),
    pending_code_fingerprint   TEXT,
    pending_head_sha           TEXT,
    pending_base_sha           TEXT,
    requested_at_ms            INTEGER NOT NULL,
    UNIQUE (stage_id, command_id),
    CHECK (length(trim(id)) > 0 AND length(trim(command_id)) > 0
        AND length(trim(actor)) > 0 AND length(trim(turn_id)) > 0
        AND length(trim(pending_operation_id)) > 0),
    CHECK (pending_stage_id = stage_id),
    CHECK (pending_stage_generation = expected_stage_generation),
    CHECK (returned_stage_version = expected_stage_version + 1),
    CHECK ((cause = 'REQUEST_PLAN_DRAFT'
            AND stage_kind = 'PLAN' AND checkpoint = 'DRAFTING'
            AND turn_owner_kind = 'TASK_TURN')
        OR (cause = 'REQUEST_LOCAL_RESULT'
            AND stage_kind = 'LOCAL_DEVELOPMENT' AND checkpoint = 'IMPLEMENTING'
            AND turn_owner_kind = 'STAGE_TURN'))
);

CREATE TRIGGER stage_initial_result_request_insert
BEFORE INSERT ON stage_initial_result_request
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM stage s
            JOIN tasks task ON task.id = s.task_id
            JOIN task_current_stage current ON current.task_id = task.id
            WHERE s.id = NEW.stage_id
              AND s.task_id = NEW.task_id
              AND s.kind = NEW.stage_kind
              AND s.generation = NEW.expected_stage_generation
              AND s.version = NEW.returned_stage_version
              AND s.checkpoint = NEW.checkpoint
              AND s.completed_at_ms IS NULL
              AND task.workflow_version = 'V2'
              AND task.lifecycle_state = 'ACTIVE'
              AND task.epoch = NEW.expected_task_epoch
              AND current.stage_id = s.id
              AND current.stage_generation = s.generation)
            THEN RAISE(ABORT, 'initial result request lacks its exact Stage owner')
        WHEN NEW.pending_task_epoch <> NEW.expected_task_epoch
            THEN RAISE(ABORT, 'initial result request has a stale Task epoch')
        WHEN NEW.turn_owner_kind = 'TASK_TURN' AND NOT EXISTS (
            SELECT 1 FROM task_turn turn
            WHERE turn.id = NEW.turn_id
              AND turn.task_id = NEW.task_id
              AND turn.task_epoch = NEW.pending_task_epoch
              AND turn.trigger_stage_id = NEW.stage_id
              AND turn.trigger_stage_generation = NEW.pending_stage_generation
              AND turn.operation_id = NEW.pending_operation_id
              AND turn.attempt = NEW.pending_attempt
              AND turn.expected_code_fingerprint IS NEW.pending_code_fingerprint
              AND turn.expected_head_sha IS NEW.pending_head_sha
              AND turn.expected_base_sha IS NEW.pending_base_sha
              AND turn.status = 'REQUESTED'
              AND ((NEW.cause = 'REQUEST_PLAN_DRAFT'
                    AND turn.purpose = 'PLAN_DRAFT')))
            THEN RAISE(ABORT, 'initial result request lacks its exact TaskTurn')
        WHEN NEW.turn_owner_kind = 'STAGE_TURN' AND NOT EXISTS (
            SELECT 1 FROM stage_turn turn
            WHERE turn.id = NEW.turn_id
              AND turn.stage_id = NEW.stage_id
              AND turn.stage_generation = NEW.pending_stage_generation
              AND turn.task_epoch = NEW.pending_task_epoch
              AND turn.operation_id = NEW.pending_operation_id
              AND turn.attempt = NEW.pending_attempt
              AND turn.expected_code_fingerprint IS NEW.pending_code_fingerprint
              AND turn.expected_head_sha IS NEW.pending_head_sha
              AND turn.expected_base_sha IS NEW.pending_base_sha
              AND turn.status = 'QUEUED'
              AND NEW.cause = 'REQUEST_LOCAL_RESULT'
              AND turn.purpose = 'IMPLEMENT_LOCAL_PLAN')
            THEN RAISE(ABORT, 'initial result request lacks its exact StageTurn')
        WHEN NOT EXISTS (
            SELECT 1 FROM dispatch_ticket ticket
            WHERE ticket.operation_id = NEW.pending_operation_id
              AND ticket.owner_kind = NEW.turn_owner_kind
              AND ticket.owner_id = NEW.turn_id
              AND ticket.task_id = NEW.task_id
              AND ticket.task_epoch = NEW.pending_task_epoch
              AND ticket.stage_id = NEW.stage_id
              AND ticket.stage_generation = NEW.pending_stage_generation
              AND ticket.attempt = NEW.pending_attempt
              AND ticket.expected_code_fingerprint IS NEW.pending_code_fingerprint
              AND ticket.expected_head_sha IS NEW.pending_head_sha
              AND ticket.expected_base_sha IS NEW.pending_base_sha
              AND ticket.status = 'REQUESTED'
              AND ((NEW.turn_owner_kind = 'TASK_TURN'
                    AND ticket.operation_kind = 'EXECUTE_TASK_TURN'
                    AND ticket.async_family = 'AGENT_TURN'
                    AND ticket.callback_route = 'TASK_TURN_RESULT')
                OR (NEW.turn_owner_kind = 'STAGE_TURN'
                    AND ticket.operation_kind = 'EXECUTE_STAGE_TURN'
                    AND ticket.async_family = 'AGENT_TURN'
                    AND ticket.callback_route = 'STAGE_TURN_RESULT')))
            THEN RAISE(ABORT, 'initial result request lacks its exact DispatchTicket')
    END;
END;

CREATE TRIGGER stage_initial_result_request_immutable
BEFORE UPDATE ON stage_initial_result_request
BEGIN SELECT RAISE(ABORT, 'initial Stage result request is immutable'); END;

CREATE TRIGGER stage_initial_result_request_delete_guard
BEFORE DELETE ON stage_initial_result_request
BEGIN SELECT RAISE(ABORT, 'initial Stage result request cannot be deleted'); END;

-- Plan TaskTurns always run against one current Plan Stage and frozen code.
CREATE UNIQUE INDEX idx_plan_task_turn_one_active_purpose
    ON task_turn(trigger_stage_id, purpose)
    WHERE purpose IN ('PLAN_DRAFT', 'PLAN_SELF_REVIEW')
      AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING');

CREATE TRIGGER plan_task_turn_insert
BEFORE INSERT ON task_turn
WHEN NEW.purpose IN ('PLAN_DRAFT', 'PLAN_SELF_REVIEW')
BEGIN
    SELECT CASE WHEN
        NEW.status <> 'REQUESTED'
        OR NEW.attempt <> 1
        OR NEW.trigger_stage_id IS NULL
        OR NEW.expected_code_fingerprint IS NULL
        OR NEW.expected_head_sha IS NULL
        OR NEW.expected_base_sha IS NULL
        OR NEW.delivery_lane NOT IN ('CLI', 'API')
        OR NOT json_valid(NEW.launch_input)
        OR json_extract(NEW.launch_input, '$.schemaVersion') <> 1
        OR json_extract(NEW.launch_input, '$.transport') <> NEW.delivery_lane
        OR json_extract(NEW.launch_input, '$.toolEndpoint.ownerKind') <> 'TASK_TURN'
        OR json_extract(NEW.launch_input, '$.toolEndpoint.ownerId') <> NEW.id
        OR json_extract(NEW.launch_input, '$.toolEndpoint.operationId') <> NEW.operation_id
        OR json_extract(NEW.launch_input, '$.toolEndpoint.profile') <> 'TASK_BRAIN_READ_ONLY'
        OR json_extract(NEW.launch_input, '$.toolEndpoint.approvalPromptTool')
            <> 'mcp__bytequay__approval_prompt'
        OR json_extract(NEW.launch_input, '$.toolEndpoint.url') NOT LIKE
            '%/api/v2/task-turns/' || NEW.id || '/operations/' || NEW.operation_id || '/mcp'
        OR length(trim(json_extract(NEW.launch_input, '$.prompt'))) = 0
        OR NOT EXISTS (
            SELECT 1
            FROM tasks task
            JOIN task_current_stage current ON current.task_id = task.id
            JOIN stage s ON s.id = current.stage_id
            JOIN plan_stage plan ON plan.stage_id = s.id
            JOIN task_code_identity code ON code.task_id = task.id
            JOIN task_brain brain ON brain.task_id = task.id
            WHERE task.id = NEW.task_id
              AND task.workflow_version = 'V2'
              AND task.lifecycle_state = 'ACTIVE'
              AND task.epoch = NEW.task_epoch
              AND current.stage_id = NEW.trigger_stage_id
              AND current.stage_generation = NEW.trigger_stage_generation
              AND s.kind = 'PLAN' AND s.completed_at_ms IS NULL
              AND s.checkpoint IN ('DRAFTING', 'SELF_REVIEW')
              AND plan.task_id = task.id
              AND plan.generation = s.generation
              AND plan.opened_for_epoch = task.epoch
              AND code.code_fingerprint = NEW.expected_code_fingerprint
              AND code.local_head_sha = NEW.expected_head_sha
              AND code.base_sha = NEW.expected_base_sha
              AND code.worktree_path = json_extract(NEW.launch_input, '$.workingDirectory')
              AND brain.provider = json_extract(NEW.launch_input, '$.provider')
              AND brain.model = json_extract(NEW.launch_input, '$.model'))
    THEN RAISE(ABORT, 'Plan TaskTurn does not match its frozen owner') END;
END;

-- Atomic receipt for the single provisioning result that opens initial Plan.
CREATE TABLE task_provision_plan_receipt (
    id                       TEXT    NOT NULL PRIMARY KEY,
    task_id                  TEXT    NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
    provision_operation_id   TEXT    NOT NULL UNIQUE,
    evidence_digest          TEXT    NOT NULL,
    plan_stage_id            TEXT    NOT NULL UNIQUE REFERENCES plan_stage(stage_id),
    plan_stage_generation    INTEGER NOT NULL CHECK (plan_stage_generation = 1),
    draft_turn_id            TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    draft_operation_id       TEXT    NOT NULL UNIQUE,
    draft_ticket_id          TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    recorded_at_ms           INTEGER NOT NULL,
    CHECK (length(trim(id)) > 0 AND length(trim(provision_operation_id)) > 0
        AND length(trim(evidence_digest)) > 0)
);

CREATE TRIGGER task_provision_plan_receipt_insert
BEFORE INSERT ON task_provision_plan_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM provision_task_operation provision
    JOIN tasks task ON task.id = provision.task_id
    JOIN task_code_identity code
      ON code.task_id = task.id AND code.provision_operation_id = provision.id
    JOIN task_command_receipt task_receipt ON task_receipt.task_id = task.id
    JOIN stage s ON s.id = NEW.plan_stage_id
    JOIN plan_stage plan ON plan.stage_id = s.id
    JOIN stage_initial_result_request request ON request.stage_id = s.id
    JOIN task_turn turn ON turn.id = NEW.draft_turn_id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.draft_ticket_id
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = provision.task_epoch
      AND provision.operation_id = NEW.provision_operation_id
      AND provision.status = 'ACCEPTED'
      AND provision.result_evidence IS NOT NULL
      AND task_receipt.cause = 'ACCEPT_PROVISIONING'
      AND task_receipt.disposition = 'APPLIED'
      AND task_receipt.expected_task_epoch = provision.task_epoch
      AND task_receipt.subject_operation_id = provision.operation_id
      AND task_receipt.proof_id = provision.operation_id
      AND task_receipt.next_stage_id = s.id
      AND task_receipt.next_stage_kind = 'PLAN'
      AND task_receipt.next_stage_generation = NEW.plan_stage_generation
      AND task_receipt.returned_current_stage_id = s.id
      AND s.task_id = task.id AND s.kind = 'PLAN'
      AND s.generation = NEW.plan_stage_generation
      AND s.version = 1 AND s.checkpoint = 'DRAFTING'
      AND plan.task_id = task.id
      AND plan.generation = s.generation
      AND plan.opened_for_epoch = task.epoch
      AND request.cause = 'REQUEST_PLAN_DRAFT'
      AND request.turn_owner_kind = 'TASK_TURN'
      AND request.turn_id = NEW.draft_turn_id
      AND request.pending_operation_id = NEW.draft_operation_id
      AND turn.operation_id = NEW.draft_operation_id
      AND turn.purpose = 'PLAN_DRAFT'
      AND turn.status = 'REQUESTED'
      AND ticket.operation_id = NEW.draft_operation_id
      AND ticket.owner_kind = 'TASK_TURN'
      AND ticket.owner_id = turn.id
      AND ticket.callback_route = 'TASK_TURN_RESULT'
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'provision-to-Plan receipt is not exact'); END;

CREATE TRIGGER task_provision_plan_receipt_immutable
BEFORE UPDATE ON task_provision_plan_receipt
BEGIN SELECT RAISE(ABORT, 'provision-to-Plan receipt is immutable'); END;

-- Terminal provisioning failures are accepted by Task ownership and parked
-- behind a durable blocker. This lets the dispatcher finalize the failed
-- ticket without pretending that a Plan can open.
CREATE TABLE task_provision_failure_receipt (
    operation_id       TEXT    NOT NULL PRIMARY KEY,
    task_id            TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch         INTEGER NOT NULL CHECK (task_epoch > 0),
    raw_outcome        TEXT    NOT NULL CHECK (raw_outcome IN (
        'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_digest         TEXT    NOT NULL,
    error_message      TEXT    NOT NULL,
    blocker_id         TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    recorded_at_ms     INTEGER NOT NULL,
    CHECK (length(trim(raw_digest)) > 0 AND length(trim(error_message)) > 0)
);

CREATE TRIGGER task_provision_failure_receipt_insert
BEFORE INSERT ON task_provision_failure_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM provision_task_operation operation
    JOIN tasks task ON task.id = operation.task_id
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    WHERE operation.operation_id = NEW.operation_id
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.status = CASE NEW.raw_outcome
          WHEN 'CANCELED' THEN 'CANCELED' ELSE 'FAILED' END
      AND operation.completed_at_ms IS NOT NULL
      AND operation.error_message = NEW.error_message
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'PROVISIONING'
      AND task.epoch = NEW.task_epoch
      AND blocker.task_id = task.id
      AND blocker.stage_id IS NULL
      AND blocker.owner_kind = 'TASK'
      AND blocker.owner_id = task.id
      AND blocker.blocker_type = 'PROVISIONING_FAILED'
      AND blocker.status = 'OPEN')
BEGIN SELECT RAISE(ABORT, 'provision failure receipt is not exact'); END;

CREATE TRIGGER task_provision_failure_receipt_immutable
BEFORE UPDATE ON task_provision_failure_receipt
BEGIN SELECT RAISE(ABORT, 'provision failure receipt is immutable'); END;

-- One tool submission per TaskTurn. JSON protocol values are typed and kept
-- separate from the aggregate tables rather than being inferred at delivery.
CREATE TABLE plan_turn_submission (
    task_turn_id       TEXT    NOT NULL PRIMARY KEY REFERENCES task_turn(id),
    operation_id       TEXT    NOT NULL UNIQUE,
    task_id            TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch         INTEGER NOT NULL CHECK (task_epoch > 0),
    plan_stage_id      TEXT    NOT NULL REFERENCES plan_stage(stage_id) ON DELETE CASCADE,
    stage_generation   INTEGER NOT NULL CHECK (stage_generation > 0),
    plan_revision_id   TEXT    NOT NULL UNIQUE REFERENCES plan_revision(id),
    submitted_at_ms    INTEGER NOT NULL
);

CREATE TRIGGER plan_turn_submission_insert
BEFORE INSERT ON plan_turn_submission
WHEN NOT EXISTS (
    SELECT 1
    FROM task_turn turn
    JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
    JOIN plan_revision revision ON revision.id = NEW.plan_revision_id
    JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
    JOIN stage s ON s.id = plan.stage_id
    JOIN tasks task ON task.id = turn.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    WHERE turn.id = NEW.task_turn_id
      AND turn.operation_id = NEW.operation_id
      AND turn.purpose = 'PLAN_DRAFT'
      AND turn.status = 'RUNNING'
      AND turn.task_id = NEW.task_id
      AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.plan_stage_id
      AND turn.trigger_stage_generation = NEW.stage_generation
      AND ticket.owner_kind = 'TASK_TURN' AND ticket.owner_id = turn.id
      AND ticket.status = 'RUNNING'
      AND revision.plan_stage_id = NEW.plan_stage_id
      AND revision.created_by = turn.id
      AND plan.task_id = task.id AND plan.generation = NEW.stage_generation
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = s.id AND current.stage_generation = s.generation
      AND s.checkpoint = 'DRAFTING' AND s.completed_at_ms IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM plan_revision newer
          WHERE newer.plan_stage_id = revision.plan_stage_id
            AND newer.revision > revision.revision))
BEGIN SELECT RAISE(ABORT, 'Plan submission does not match its running TaskTurn'); END;

CREATE TRIGGER plan_turn_submission_immutable
BEFORE UPDATE ON plan_turn_submission
BEGIN SELECT RAISE(ABORT, 'Plan submission is immutable'); END;

CREATE TABLE plan_review_submission (
    task_turn_id       TEXT    NOT NULL PRIMARY KEY REFERENCES task_turn(id),
    operation_id       TEXT    NOT NULL UNIQUE,
    task_id            TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch         INTEGER NOT NULL CHECK (task_epoch > 0),
    plan_stage_id      TEXT    NOT NULL REFERENCES plan_stage(stage_id) ON DELETE CASCADE,
    stage_generation   INTEGER NOT NULL CHECK (stage_generation > 0),
    self_review_id     TEXT    NOT NULL UNIQUE REFERENCES plan_self_review(id),
    plan_revision_id   TEXT    NOT NULL UNIQUE REFERENCES plan_revision(id),
    reviewed_digest    TEXT    NOT NULL,
    verdict            TEXT    NOT NULL CHECK (verdict IN (
        'APPROVED', 'CHANGES_REQUESTED', 'BLOCKED')),
    concerns_json      TEXT    NOT NULL CHECK (
        json_valid(concerns_json) AND json_type(concerns_json) = 'array'),
    follow_ups_json    TEXT    NOT NULL CHECK (
        json_valid(follow_ups_json) AND json_type(follow_ups_json) = 'array'),
    stewardship_json   TEXT    NOT NULL CHECK (
        json_valid(stewardship_json) AND json_type(stewardship_json) = 'array'),
    submitted_at_ms    INTEGER NOT NULL,
    CHECK (verdict <> 'APPROVED' OR concerns_json = '[]')
);

CREATE TRIGGER plan_review_submission_insert
BEFORE INSERT ON plan_review_submission
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review review
    JOIN plan_revision revision ON revision.id = review.plan_revision_id
    JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
    JOIN stage s ON s.id = plan.stage_id
    JOIN tasks task ON task.id = plan.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN task_turn turn ON turn.id = review.task_turn_id
    JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
    WHERE review.id = NEW.self_review_id
      AND review.plan_revision_id = NEW.plan_revision_id
      AND review.reviewed_digest = NEW.reviewed_digest
      AND review.status = 'REQUESTED'
      AND turn.id = NEW.task_turn_id
      AND turn.operation_id = NEW.operation_id
      AND turn.purpose = 'PLAN_SELF_REVIEW'
      AND turn.status = 'RUNNING'
      AND turn.task_id = NEW.task_id AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.plan_stage_id
      AND turn.trigger_stage_generation = NEW.stage_generation
      AND ticket.owner_kind = 'TASK_TURN' AND ticket.owner_id = turn.id
      AND ticket.status = 'RUNNING'
      AND plan.generation = NEW.stage_generation
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = s.id AND current.stage_generation = s.generation
      AND s.checkpoint = 'SELF_REVIEW' AND s.completed_at_ms IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM plan_revision newer
          WHERE newer.plan_stage_id = revision.plan_stage_id
            AND newer.revision > revision.revision))
BEGIN SELECT RAISE(ABORT, 'Plan review submission is not exact'); END;

CREATE TRIGGER plan_review_submission_immutable
BEFORE UPDATE ON plan_review_submission
BEGIN SELECT RAISE(ABORT, 'Plan review submission is immutable'); END;

-- A Plan self-review is one logical verdict with at most two infrastructure
-- executions. The second TaskTurn is a new exact operation linked to the
-- failed predecessor; it is not a second logical review of the revision.
CREATE TABLE plan_self_review_attempt (
    self_review_id       TEXT    NOT NULL REFERENCES plan_self_review(id) ON DELETE CASCADE,
    attempt              INTEGER NOT NULL CHECK (attempt IN (1, 2)),
    task_turn_id         TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    operation_id         TEXT    NOT NULL UNIQUE,
    predecessor_turn_id  TEXT    UNIQUE REFERENCES task_turn(id),
    requested_at_ms      INTEGER NOT NULL,
    PRIMARY KEY (self_review_id, attempt),
    CHECK ((attempt = 1 AND predecessor_turn_id IS NULL)
        OR (attempt = 2 AND predecessor_turn_id IS NOT NULL))
);

INSERT INTO plan_self_review_attempt(
    self_review_id, attempt, task_turn_id, operation_id,
    predecessor_turn_id, requested_at_ms)
SELECT review.id, 1, review.task_turn_id, turn.operation_id, NULL,
       review.requested_at_ms
FROM plan_self_review review
JOIN task_turn turn ON turn.id = review.task_turn_id;

CREATE TRIGGER plan_self_review_attempt_insert
BEFORE INSERT ON plan_self_review_attempt
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review review
    JOIN plan_revision revision ON revision.id = review.plan_revision_id
    JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
    JOIN task_turn turn ON turn.id = NEW.task_turn_id
    WHERE review.id = NEW.self_review_id
      AND review.status = 'REQUESTED'
      AND turn.operation_id = NEW.operation_id
      AND turn.task_id = plan.task_id
      AND turn.task_epoch = review.task_epoch
      AND turn.trigger_stage_id = plan.stage_id
      AND turn.trigger_stage_generation = plan.generation
      AND turn.purpose = 'PLAN_SELF_REVIEW'
      AND turn.status = 'REQUESTED'
      AND ((NEW.attempt = 1 AND turn.id = review.task_turn_id)
        OR (NEW.attempt = 2 AND EXISTS (
            SELECT 1
            FROM plan_self_review_attempt predecessor
            JOIN task_turn failed ON failed.id = predecessor.task_turn_id
            WHERE predecessor.self_review_id = review.id
              AND predecessor.attempt = 1
              AND predecessor.task_turn_id = NEW.predecessor_turn_id
              AND failed.status = 'FAILED'
              AND failed.finished_at_ms IS NOT NULL))))
BEGIN SELECT RAISE(ABORT, 'Plan self-review attempt is not exact'); END;

CREATE TRIGGER plan_self_review_attempt_immutable
BEFORE UPDATE ON plan_self_review_attempt
BEGIN SELECT RAISE(ABORT, 'Plan self-review attempt is immutable'); END;

DROP TRIGGER plan_review_submission_insert;
CREATE TRIGGER plan_review_submission_insert
BEFORE INSERT ON plan_review_submission
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review review
    JOIN plan_self_review_attempt attempt
      ON attempt.self_review_id = review.id
    JOIN plan_revision revision ON revision.id = review.plan_revision_id
    JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
    JOIN stage s ON s.id = plan.stage_id
    JOIN tasks task ON task.id = plan.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN task_turn turn ON turn.id = attempt.task_turn_id
    JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
    WHERE review.id = NEW.self_review_id
      AND review.plan_revision_id = NEW.plan_revision_id
      AND review.reviewed_digest = NEW.reviewed_digest
      AND review.status = 'REQUESTED'
      AND turn.id = NEW.task_turn_id
      AND turn.operation_id = NEW.operation_id
      AND turn.purpose = 'PLAN_SELF_REVIEW'
      AND turn.status = 'RUNNING'
      AND turn.task_id = NEW.task_id AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.plan_stage_id
      AND turn.trigger_stage_generation = NEW.stage_generation
      AND ticket.owner_kind = 'TASK_TURN' AND ticket.owner_id = turn.id
      AND ticket.status = 'RUNNING'
      AND plan.generation = NEW.stage_generation
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = s.id AND current.stage_generation = s.generation
      AND s.checkpoint = 'SELF_REVIEW' AND s.completed_at_ms IS NULL
      AND attempt.attempt = (
          SELECT MAX(latest.attempt) FROM plan_self_review_attempt latest
          WHERE latest.self_review_id = review.id)
      AND NOT EXISTS (
          SELECT 1 FROM plan_revision newer
          WHERE newer.plan_stage_id = revision.plan_stage_id
            AND newer.revision > revision.revision))
BEGIN SELECT RAISE(ABORT, 'Plan review submission is not exact'); END;

DROP TRIGGER plan_self_review_result_fence;
CREATE TRIGGER plan_self_review_result_fence
BEFORE UPDATE ON plan_self_review
WHEN NEW.status = 'SUCCEEDED'
  AND NOT EXISTS (
      SELECT 1
      FROM plan_revision revision
      JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
      JOIN stage s ON s.id = plan.stage_id
      JOIN task_current_stage current ON current.stage_id = s.id
      JOIN tasks task ON task.id = plan.task_id
      JOIN plan_self_review_attempt attempt
        ON attempt.self_review_id = NEW.id
      JOIN task_turn turn ON turn.id = attempt.task_turn_id
      WHERE revision.id = NEW.plan_revision_id
        AND revision.content_digest = NEW.reviewed_digest
        AND current.task_id = plan.task_id
        AND current.stage_generation = plan.generation
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'ACTIVE'
        AND task.epoch = NEW.task_epoch
        AND plan.opened_for_epoch = NEW.task_epoch
        AND turn.status = 'SUCCEEDED'
        AND turn.purpose = 'PLAN_SELF_REVIEW'
        AND turn.task_epoch = NEW.task_epoch
        AND turn.trigger_stage_id = plan.stage_id
        AND turn.trigger_stage_generation = plan.generation
        AND attempt.attempt = (
            SELECT MAX(latest.attempt) FROM plan_self_review_attempt latest
            WHERE latest.self_review_id = NEW.id)
        AND s.completed_at_ms IS NULL
        AND NOT EXISTS (
            SELECT 1 FROM plan_revision newer
            WHERE newer.plan_stage_id = revision.plan_stage_id
              AND newer.revision > revision.revision))
BEGIN SELECT RAISE(ABORT, 'Plan self-review result is stale'); END;

CREATE TABLE plan_task_turn_delivery_receipt (
    task_turn_id        TEXT    NOT NULL PRIMARY KEY REFERENCES task_turn(id),
    operation_id        TEXT    NOT NULL UNIQUE,
    raw_outcome         TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_evidence_digest TEXT    NOT NULL,
    acceptance          TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    domain_result       TEXT    NOT NULL CHECK (domain_result IN (
        'DRAFT_ACCEPTED', 'REVIEW_APPROVED', 'REVIEW_FINDINGS',
        'REVIEW_RETRY_REQUESTED', 'REVIEW_BLOCKED', 'PROTOCOL_BLOCKED',
        'TURN_FAILED', 'TURN_CANCELED', 'SUPERSEDED')),
    recorded_at_ms      INTEGER NOT NULL
);

CREATE TRIGGER plan_task_turn_delivery_receipt_insert
BEFORE INSERT ON plan_task_turn_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1 FROM task_turn turn
    WHERE turn.id = NEW.task_turn_id
      AND turn.operation_id = NEW.operation_id
      AND turn.purpose IN ('PLAN_DRAFT', 'PLAN_SELF_REVIEW')
      AND turn.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
      AND ((NEW.acceptance = 'SUPERSEDED'
            AND turn.status = 'SUPERSEDED' AND NEW.domain_result = 'SUPERSEDED')
        OR (NEW.raw_outcome = 'SUCCEEDED' AND turn.status = 'SUCCEEDED'
            AND NEW.domain_result IN ('DRAFT_ACCEPTED', 'REVIEW_APPROVED',
                'REVIEW_FINDINGS', 'REVIEW_BLOCKED'))
        OR (NEW.raw_outcome = 'SUCCEEDED' AND turn.status = 'FAILED'
            AND NEW.domain_result = 'PROTOCOL_BLOCKED')
        OR (NEW.raw_outcome IN ('FAILED', 'INDETERMINATE')
            AND turn.status = 'FAILED' AND NEW.domain_result IN (
                'TURN_FAILED', 'REVIEW_RETRY_REQUESTED', 'REVIEW_BLOCKED'))
        OR (NEW.raw_outcome = 'CANCELED' AND turn.status = 'CANCELED'
            AND NEW.domain_result = 'TURN_CANCELED')))
BEGIN SELECT RAISE(ABORT, 'Plan TaskTurn delivery receipt is not exact'); END;

CREATE TRIGGER plan_task_turn_delivery_receipt_immutable
BEFORE UPDATE ON plan_task_turn_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Plan TaskTurn delivery receipt is immutable'); END;

CREATE TABLE stage_plan_review_retry_request (
    id                         TEXT    NOT NULL PRIMARY KEY,
    stage_id                   TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                 TEXT    NOT NULL,
    actor                      TEXT    NOT NULL,
    self_review_id             TEXT    NOT NULL REFERENCES plan_self_review(id),
    predecessor_turn_id        TEXT    NOT NULL REFERENCES task_turn(id),
    replacement_turn_id        TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    expected_stage_version     INTEGER NOT NULL CHECK (expected_stage_version > 0),
    returned_stage_version     INTEGER NOT NULL,
    subject_task_epoch         INTEGER NOT NULL CHECK (subject_task_epoch > 0),
    subject_stage_generation   INTEGER NOT NULL CHECK (subject_stage_generation > 0),
    subject_operation_id       TEXT    NOT NULL UNIQUE,
    subject_attempt            INTEGER NOT NULL CHECK (subject_attempt > 0),
    subject_code_fingerprint   TEXT    NOT NULL,
    subject_head_sha           TEXT    NOT NULL,
    subject_base_sha           TEXT    NOT NULL,
    pending_task_epoch         INTEGER NOT NULL CHECK (pending_task_epoch > 0),
    pending_stage_generation   INTEGER NOT NULL CHECK (pending_stage_generation > 0),
    pending_operation_id       TEXT    NOT NULL UNIQUE,
    pending_attempt            INTEGER NOT NULL CHECK (pending_attempt > 0),
    pending_code_fingerprint   TEXT    NOT NULL,
    pending_head_sha           TEXT    NOT NULL,
    pending_base_sha           TEXT    NOT NULL,
    requested_at_ms            INTEGER NOT NULL,
    UNIQUE (stage_id, command_id),
    CHECK (returned_stage_version = expected_stage_version + 1),
    CHECK (pending_task_epoch = subject_task_epoch
        AND pending_stage_generation = subject_stage_generation)
);

CREATE TRIGGER stage_plan_review_retry_request_insert
BEFORE INSERT ON stage_plan_review_retry_request
WHEN NOT EXISTS (
    SELECT 1
    FROM stage s
    JOIN tasks task ON task.id = s.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN plan_self_review review ON review.id = NEW.self_review_id
    JOIN plan_revision revision ON revision.id = review.plan_revision_id
    JOIN task_turn failed ON failed.id = NEW.predecessor_turn_id
    JOIN plan_self_review_attempt retry
      ON retry.self_review_id = review.id AND retry.attempt = 2
    JOIN task_turn replacement ON replacement.id = NEW.replacement_turn_id
    JOIN dispatch_ticket ticket ON ticket.operation_id = replacement.operation_id
    JOIN stage_transition transition
      ON transition.stage_id = s.id AND transition.command_id = NEW.command_id
    WHERE s.id = NEW.stage_id AND s.task_id = NEW.task_id
      AND s.kind = 'PLAN' AND s.checkpoint = 'SELF_REVIEW'
      AND s.version = NEW.returned_stage_version
      AND s.completed_at_ms IS NULL
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.subject_task_epoch
      AND current.stage_id = s.id AND current.stage_generation = s.generation
      AND revision.plan_stage_id = s.id AND review.status = 'REQUESTED'
      AND failed.id = retry.predecessor_turn_id
      AND failed.operation_id = NEW.subject_operation_id
      AND failed.status = 'FAILED'
      AND failed.trigger_stage_id = s.id
      AND failed.trigger_stage_generation = NEW.subject_stage_generation
      AND failed.trigger_stage_generation = s.generation
      AND failed.expected_code_fingerprint = NEW.subject_code_fingerprint
      AND failed.expected_head_sha = NEW.subject_head_sha
      AND failed.expected_base_sha = NEW.subject_base_sha
      AND replacement.id = retry.task_turn_id
      AND replacement.operation_id = NEW.pending_operation_id
      AND replacement.status = 'REQUESTED'
      AND replacement.trigger_stage_id = s.id
      AND replacement.trigger_stage_generation = s.generation
      AND replacement.expected_code_fingerprint = NEW.pending_code_fingerprint
      AND replacement.expected_head_sha = NEW.pending_head_sha
      AND replacement.expected_base_sha = NEW.pending_base_sha
      AND ticket.owner_kind = 'TASK_TURN'
      AND ticket.owner_id = replacement.id
      AND ticket.status = 'REQUESTED'
      AND transition.generation = s.generation
      AND transition.from_checkpoint = 'SELF_REVIEW'
      AND transition.to_checkpoint = 'SELF_REVIEW'
      AND transition.stage_version = s.version
      AND transition.cause = 'RETRY_PLAN_SELF_REVIEW'
      AND transition.actor = NEW.actor)
BEGIN SELECT RAISE(ABORT, 'Plan self-review retry request is not exact'); END;

CREATE TRIGGER stage_plan_review_retry_request_immutable
BEFORE UPDATE ON stage_plan_review_retry_request
BEGIN SELECT RAISE(ABORT, 'Plan self-review retry request is immutable'); END;

CREATE TABLE stage_plan_terminal_result (
    id                         TEXT    NOT NULL PRIMARY KEY,
    stage_id                   TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                 TEXT    NOT NULL,
    actor                      TEXT    NOT NULL,
    cause                      TEXT    NOT NULL CHECK (cause IN (
        'PLAN_DRAFT_FAILED', 'PLAN_SELF_REVIEW_FAILED',
        'PLAN_SELF_REVIEW_NO_VERDICT', 'PLAN_SELF_REVIEW_BLOCKED')),
    proof_id                   TEXT    NOT NULL,
    checkpoint                 TEXT    NOT NULL CHECK (checkpoint IN (
        'DRAFTING', 'SELF_REVIEW')),
    expected_stage_version     INTEGER NOT NULL CHECK (expected_stage_version > 0),
    returned_stage_version     INTEGER NOT NULL,
    subject_task_epoch         INTEGER NOT NULL CHECK (subject_task_epoch > 0),
    subject_stage_generation   INTEGER NOT NULL CHECK (subject_stage_generation > 0),
    subject_operation_id       TEXT    NOT NULL UNIQUE,
    subject_attempt            INTEGER NOT NULL CHECK (subject_attempt > 0),
    subject_code_fingerprint   TEXT    NOT NULL,
    subject_head_sha           TEXT    NOT NULL,
    subject_base_sha           TEXT    NOT NULL,
    recorded_at_ms             INTEGER NOT NULL,
    UNIQUE (stage_id, command_id),
    CHECK (returned_stage_version = expected_stage_version + 1)
);

CREATE TRIGGER stage_plan_terminal_result_insert
BEFORE INSERT ON stage_plan_terminal_result
WHEN NOT EXISTS (
    SELECT 1
    FROM stage s
    JOIN tasks task ON task.id = s.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN task_turn turn ON turn.operation_id = NEW.subject_operation_id
    JOIN stage_transition transition
      ON transition.stage_id = s.id AND transition.command_id = NEW.command_id
    WHERE s.id = NEW.stage_id AND s.task_id = NEW.task_id
      AND s.kind = 'PLAN' AND s.checkpoint = NEW.checkpoint
      AND s.version = NEW.returned_stage_version
      AND s.completed_at_ms IS NULL
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.subject_task_epoch
      AND current.stage_id = s.id AND current.stage_generation = s.generation
      AND turn.task_id = task.id AND turn.task_epoch = task.epoch
      AND turn.trigger_stage_id = s.id
      AND turn.trigger_stage_generation = NEW.subject_stage_generation
      AND turn.trigger_stage_generation = s.generation
      AND turn.expected_code_fingerprint = NEW.subject_code_fingerprint
      AND turn.expected_head_sha = NEW.subject_head_sha
      AND turn.expected_base_sha = NEW.subject_base_sha
      AND turn.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
      AND ((NEW.cause = 'PLAN_DRAFT_FAILED' AND turn.purpose = 'PLAN_DRAFT')
        OR (NEW.cause <> 'PLAN_DRAFT_FAILED'
            AND turn.purpose = 'PLAN_SELF_REVIEW'))
      AND transition.generation = s.generation
      AND transition.from_checkpoint = NEW.checkpoint
      AND transition.to_checkpoint = NEW.checkpoint
      AND transition.stage_version = s.version
      AND transition.cause = NEW.cause
      AND transition.actor = NEW.actor)
BEGIN SELECT RAISE(ABORT, 'Plan terminal result is not exact'); END;

CREATE TRIGGER stage_plan_terminal_result_immutable
BEFORE UPDATE ON stage_plan_terminal_result
BEGIN SELECT RAISE(ABORT, 'Plan terminal result is immutable'); END;

-- The edited revision already exists before its mandatory review Turn can be
-- armed. This dedicated Stage receipt avoids widening the generic Stage cause
-- enum and keeps the returned pending-result projection restart-safe.
CREATE TABLE stage_plan_edit_review_request (
    id                         TEXT    NOT NULL PRIMARY KEY,
    stage_id                   TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                 TEXT    NOT NULL,
    actor                      TEXT    NOT NULL,
    expected_task_epoch        INTEGER NOT NULL CHECK (expected_task_epoch > 0),
    expected_stage_generation  INTEGER NOT NULL CHECK (expected_stage_generation > 0),
    expected_stage_version     INTEGER NOT NULL CHECK (expected_stage_version > 0),
    returned_stage_version     INTEGER NOT NULL,
    revision_id                TEXT    NOT NULL REFERENCES plan_revision(id),
    pending_task_epoch         INTEGER NOT NULL CHECK (pending_task_epoch > 0),
    pending_stage_id           TEXT    NOT NULL,
    pending_stage_generation   INTEGER NOT NULL CHECK (pending_stage_generation > 0),
    pending_operation_id       TEXT    NOT NULL UNIQUE,
    pending_attempt            INTEGER NOT NULL CHECK (pending_attempt > 0),
    pending_code_fingerprint   TEXT    NOT NULL,
    pending_head_sha           TEXT    NOT NULL,
    pending_base_sha           TEXT    NOT NULL,
    requested_at_ms            INTEGER NOT NULL,
    UNIQUE (stage_id, command_id),
    CHECK (returned_stage_version = expected_stage_version + 1),
    CHECK (pending_stage_id = stage_id
        AND pending_stage_generation = expected_stage_generation
        AND pending_task_epoch = expected_task_epoch)
);

CREATE TRIGGER stage_plan_edit_review_request_insert
BEFORE INSERT ON stage_plan_edit_review_request
WHEN NOT EXISTS (
    SELECT 1
    FROM stage s
    JOIN tasks task ON task.id = s.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN plan_revision revision ON revision.id = NEW.revision_id
    JOIN task_turn turn ON turn.operation_id = NEW.pending_operation_id
    JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
    JOIN stage_transition transition
      ON transition.stage_id = s.id
     AND transition.command_id = NEW.command_id
    WHERE s.id = NEW.stage_id AND s.task_id = NEW.task_id
      AND s.kind = 'PLAN' AND s.generation = NEW.expected_stage_generation
      AND s.version = NEW.returned_stage_version
      AND s.checkpoint = 'SELF_REVIEW' AND s.completed_at_ms IS NULL
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.expected_task_epoch
      AND current.stage_id = s.id
      AND current.stage_generation = s.generation
      AND revision.plan_stage_id = s.id
      AND NOT EXISTS (
          SELECT 1 FROM plan_revision newer
          WHERE newer.plan_stage_id = revision.plan_stage_id
            AND newer.revision > revision.revision)
      AND turn.task_id = task.id AND turn.task_epoch = task.epoch
      AND turn.trigger_stage_id = s.id
      AND turn.trigger_stage_generation = s.generation
      AND turn.purpose = 'PLAN_SELF_REVIEW' AND turn.status = 'REQUESTED'
      AND turn.expected_code_fingerprint = NEW.pending_code_fingerprint
      AND turn.expected_head_sha = NEW.pending_head_sha
      AND turn.expected_base_sha = NEW.pending_base_sha
      AND ticket.owner_kind = 'TASK_TURN' AND ticket.owner_id = turn.id
      AND ticket.callback_route = 'TASK_TURN_RESULT'
      AND ticket.status = 'REQUESTED'
      AND transition.generation = s.generation
      AND transition.from_checkpoint = 'DRAFTING'
      AND transition.to_checkpoint = 'SELF_REVIEW'
      AND transition.stage_version = s.version
      AND transition.cause = 'REQUEST_EDITED_PLAN_SELF_REVIEW'
      AND transition.actor = NEW.actor)
BEGIN SELECT RAISE(ABORT, 'edited Plan review request lacks its exact owner'); END;

CREATE TRIGGER stage_plan_edit_review_request_immutable
BEFORE UPDATE ON stage_plan_edit_review_request
BEGIN SELECT RAISE(ABORT, 'edited Plan review request is immutable'); END;

-- A material user edit is one immutable request. It creates the next Plan
-- revision, invalidates the predecessor's approval eligibility, and arms the
-- exact mandatory self-review before the transaction commits.
CREATE TABLE plan_user_edit_receipt (
    request_id                TEXT    NOT NULL PRIMARY KEY,
    task_id                   TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                INTEGER NOT NULL CHECK (task_epoch > 0),
    plan_stage_id             TEXT    NOT NULL REFERENCES plan_stage(stage_id) ON DELETE CASCADE,
    stage_generation          INTEGER NOT NULL CHECK (stage_generation > 0),
    expected_stage_version    INTEGER NOT NULL CHECK (expected_stage_version >= 0),
    actor                     TEXT    NOT NULL,
    previous_revision_id      TEXT    NOT NULL REFERENCES plan_revision(id),
    plan_revision_id          TEXT    NOT NULL UNIQUE REFERENCES plan_revision(id),
    content_digest            TEXT    NOT NULL,
    self_review_id            TEXT    NOT NULL UNIQUE REFERENCES plan_self_review(id),
    review_turn_id            TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    review_operation_id       TEXT    NOT NULL UNIQUE,
    review_ticket_id          TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    review_command_id         TEXT    NOT NULL UNIQUE,
    recorded_at_ms            INTEGER NOT NULL,
    CHECK (length(trim(request_id)) > 0 AND length(trim(actor)) > 0
        AND length(trim(content_digest)) > 0)
);

CREATE TRIGGER plan_user_edit_receipt_insert
BEFORE INSERT ON plan_user_edit_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage s ON s.id = current.stage_id
    JOIN plan_stage plan ON plan.stage_id = s.id
    JOIN plan_revision revision ON revision.id = NEW.plan_revision_id
    JOIN plan_revision previous ON previous.id = NEW.previous_revision_id
    JOIN plan_self_review review ON review.id = NEW.self_review_id
    JOIN task_turn turn ON turn.id = NEW.review_turn_id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.review_ticket_id
    JOIN stage_plan_edit_review_request request
      ON request.command_id = NEW.review_command_id
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.plan_stage_id
      AND current.stage_generation = NEW.stage_generation
      AND s.kind = 'PLAN' AND s.generation = NEW.stage_generation
      AND s.checkpoint = 'SELF_REVIEW'
      AND s.version = NEW.expected_stage_version + 2
      AND s.completed_at_ms IS NULL
      AND plan.task_id = task.id AND plan.generation = s.generation
      AND revision.plan_stage_id = plan.stage_id
      AND revision.revision = previous.revision + 1
      AND revision.source = 'USER_EDIT'
      AND revision.created_by = NEW.actor
      AND revision.content_digest = NEW.content_digest
      AND previous.plan_stage_id = revision.plan_stage_id
      AND NOT EXISTS (
          SELECT 1 FROM plan_revision newer
          WHERE newer.plan_stage_id = revision.plan_stage_id
            AND newer.revision > revision.revision)
      AND review.plan_revision_id = revision.id
      AND review.task_turn_id = turn.id
      AND review.task_epoch = NEW.task_epoch
      AND review.reviewed_digest = revision.content_digest
      AND review.status = 'REQUESTED'
      AND turn.operation_id = NEW.review_operation_id
      AND turn.task_id = task.id AND turn.task_epoch = task.epoch
      AND turn.trigger_stage_id = s.id
      AND turn.trigger_stage_generation = s.generation
      AND turn.purpose = 'PLAN_SELF_REVIEW'
      AND turn.status = 'REQUESTED'
      AND ticket.operation_id = turn.operation_id
      AND ticket.owner_kind = 'TASK_TURN' AND ticket.owner_id = turn.id
      AND ticket.callback_route = 'TASK_TURN_RESULT'
      AND ticket.status = 'REQUESTED'
      AND request.stage_id = s.id AND request.task_id = task.id
      AND request.expected_task_epoch = task.epoch
      AND request.expected_stage_generation = s.generation
      AND request.expected_stage_version = NEW.expected_stage_version + 1
      AND request.returned_stage_version = s.version
      AND request.revision_id = revision.id
      AND request.pending_operation_id = turn.operation_id)
BEGIN SELECT RAISE(ABORT, 'Plan user edit receipt is not exact'); END;

CREATE TRIGGER plan_user_edit_receipt_immutable
BEFORE UPDATE ON plan_user_edit_receipt
BEGIN SELECT RAISE(ABORT, 'Plan user edit receipt is immutable'); END;

-- Replan completion opens a new Plan generation and immediately arms its
-- first draft. The receipt makes restart replay exact without reusing any
-- Turn from the superseded Task epoch.
CREATE TABLE task_replan_plan_receipt (
    replan_request_id      TEXT    NOT NULL PRIMARY KEY
        REFERENCES task_replan_request(id),
    task_id                TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch             INTEGER NOT NULL CHECK (task_epoch > 1),
    plan_stage_id          TEXT    NOT NULL UNIQUE REFERENCES plan_stage(stage_id),
    plan_stage_generation  INTEGER NOT NULL CHECK (plan_stage_generation > 0),
    draft_turn_id          TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    draft_operation_id     TEXT    NOT NULL UNIQUE,
    draft_ticket_id        TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    recorded_at_ms         INTEGER NOT NULL
);

CREATE TRIGGER task_replan_plan_receipt_insert
BEFORE INSERT ON task_replan_plan_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM task_replan_request replan
    JOIN tasks task ON task.id = replan.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage stage ON stage.id = current.stage_id
    JOIN plan_stage plan ON plan.stage_id = stage.id
    JOIN stage_initial_result_request request
      ON request.stage_id = stage.id
    JOIN task_turn turn ON turn.id = request.turn_id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.draft_ticket_id
    WHERE replan.id = NEW.replan_request_id
      AND replan.task_id = NEW.task_id
      AND replan.status = 'APPLIED'
      AND replan.target_task_epoch = NEW.task_epoch
      AND replan.new_plan_stage_id = NEW.plan_stage_id
      AND replan.new_plan_generation = NEW.plan_stage_generation
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.plan_stage_id
      AND current.stage_generation = NEW.plan_stage_generation
      AND stage.kind = 'PLAN' AND stage.checkpoint = 'DRAFTING'
      AND stage.version = 1 AND stage.completed_at_ms IS NULL
      AND plan.generation = NEW.plan_stage_generation
      AND plan.opened_for_epoch = NEW.task_epoch
      AND request.cause = 'REQUEST_PLAN_DRAFT'
      AND request.turn_owner_kind = 'TASK_TURN'
      AND request.turn_id = NEW.draft_turn_id
      AND request.pending_operation_id = NEW.draft_operation_id
      AND turn.task_id = NEW.task_id AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.plan_stage_id
      AND turn.trigger_stage_generation = NEW.plan_stage_generation
      AND turn.purpose = 'PLAN_DRAFT' AND turn.status = 'REQUESTED'
      AND turn.operation_id = NEW.draft_operation_id
      AND ticket.operation_id = NEW.draft_operation_id
      AND ticket.owner_kind = 'TASK_TURN' AND ticket.owner_id = turn.id
      AND ticket.callback_route = 'TASK_TURN_RESULT'
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'replan-to-Plan receipt is not exact'); END;

CREATE TRIGGER task_replan_plan_receipt_immutable
BEFORE UPDATE ON task_replan_plan_receipt
BEGIN SELECT RAISE(ABORT, 'replan-to-Plan receipt is immutable'); END;
