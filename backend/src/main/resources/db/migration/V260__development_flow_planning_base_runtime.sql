-- V2 Trunk turns refresh and freeze their planning base through one durable
-- LOCAL_GIT Operation.  LEGACY keeps its background refresher until drain.

CREATE TABLE planning_base_refresh_operation (
    id                       TEXT    NOT NULL PRIMARY KEY,
    trunk_id                 TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    workspace_id             TEXT    NOT NULL,
    command_id               TEXT    NOT NULL,
    actor                    TEXT    NOT NULL,
    operation_id             TEXT    NOT NULL UNIQUE,
    dispatch_ticket_id       TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    launch_command_id        TEXT    NOT NULL UNIQUE,
    reserved_thread_turn_id  TEXT    NOT NULL UNIQUE,
    semantic_attempt         INTEGER NOT NULL CHECK (semantic_attempt > 0),
    repository_root          TEXT    NOT NULL,
    previous_base_sha        TEXT,
    launch_intent            TEXT    NOT NULL CHECK (json_valid(launch_intent)),
    launch_intent_digest     TEXT    NOT NULL CHECK (length(launch_intent_digest) = 64),
    status                   TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    raw_outcome              TEXT CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest        TEXT,
    result_worktree_path     TEXT,
    result_base_ref          TEXT,
    result_base_sha          TEXT,
    error_message            TEXT,
    requested_at_ms          INTEGER NOT NULL,
    completed_at_ms          INTEGER,
    launch_disposition       TEXT    NOT NULL DEFAULT 'PENDING' CHECK (
        launch_disposition IN ('PENDING', 'LAUNCHED', 'SUPPRESSED')),
    launch_disposition_reason TEXT,
    launched_thread_turn_id  TEXT UNIQUE REFERENCES thread_turn(id),
    launched_at_ms           INTEGER,
    UNIQUE (trunk_id, command_id),
    CHECK ((status = 'REQUESTED') = (completed_at_ms IS NULL)),
    CHECK ((status = 'SUCCEEDED') =
        (result_worktree_path IS NOT NULL
          AND result_base_ref IS NOT NULL
          AND result_base_sha IS NOT NULL)),
    CHECK ((launch_disposition = 'PENDING'
            AND launch_disposition_reason IS NULL
            AND launched_thread_turn_id IS NULL AND launched_at_ms IS NULL)
        OR (launch_disposition = 'LAUNCHED'
            AND launch_disposition_reason IS NULL
            AND status = 'SUCCEEDED'
            AND launched_thread_turn_id = reserved_thread_turn_id
            AND launched_at_ms IS NOT NULL)
        OR (launch_disposition = 'SUPPRESSED'
            AND launch_disposition_reason IS NOT NULL
            AND launched_thread_turn_id IS NULL AND launched_at_ms IS NOT NULL))
);
CREATE INDEX idx_planning_base_refresh_ready
    ON planning_base_refresh_operation(status, completed_at_ms, id);

CREATE TABLE trunk_planning_base_request_receipt (
    id                       TEXT    NOT NULL PRIMARY KEY,
    trunk_id                 TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    command_id               TEXT    NOT NULL,
    planning_operation_id    TEXT    NOT NULL UNIQUE
        REFERENCES planning_base_refresh_operation(id) ON DELETE CASCADE,
    expected_trunk_version   INTEGER NOT NULL CHECK (expected_trunk_version >= 0),
    returned_trunk_version   INTEGER NOT NULL CHECK (
        returned_trunk_version = expected_trunk_version + 1),
    returned_lifecycle       TEXT    NOT NULL CHECK (returned_lifecycle IN (
        'ACTIVE', 'IDLE', 'ARCHIVED')),
    recorded_at_ms           INTEGER NOT NULL,
    UNIQUE (trunk_id, command_id)
);

CREATE TABLE trunk_planning_base_result_receipt (
    id                       TEXT    NOT NULL PRIMARY KEY,
    trunk_id                 TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    command_id               TEXT    NOT NULL UNIQUE,
    planning_operation_id    TEXT    NOT NULL UNIQUE
        REFERENCES planning_base_refresh_operation(id) ON DELETE CASCADE,
    operation_id             TEXT    NOT NULL UNIQUE,
    raw_outcome              TEXT    NOT NULL,
    raw_result_digest        TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance               TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    returned_trunk_version   INTEGER NOT NULL CHECK (returned_trunk_version > 0),
    returned_lifecycle       TEXT    NOT NULL CHECK (returned_lifecycle IN (
        'ACTIVE', 'IDLE', 'ARCHIVED')),
    recorded_at_ms           INTEGER NOT NULL
);

CREATE TRIGGER trunk_planning_base_request_exact
BEFORE INSERT ON trunk_planning_base_request_receipt
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM planning_base_refresh_operation operation
        JOIN dispatch_ticket ticket ON ticket.id = operation.dispatch_ticket_id
        JOIN outbox wake ON wake.aggregate_id = ticket.id
        JOIN trunk_transition transition
          ON transition.trunk_id = operation.trunk_id
         AND transition.command_id = operation.command_id
        WHERE operation.id = NEW.planning_operation_id
          AND operation.trunk_id = NEW.trunk_id
          AND operation.command_id = NEW.command_id
          AND operation.status = 'REQUESTED'
          AND ticket.operation_id = operation.operation_id
          AND ticket.operation_kind = 'REFRESH_PLANNING_BASE'
          AND ticket.async_family = 'LOCAL_GIT'
          AND ticket.owner_kind = 'TRUNK'
          AND ticket.owner_id = operation.trunk_id
          AND ticket.callback_route = 'PLANNING_BASE_REFRESH_RESULT'
          AND ticket.lane_mask = 16
          AND ticket.trunk_control = 1
          AND ticket.exclusive_task = 0
          AND ticket.writer_required = 0
          AND ticket.workspace_id = operation.workspace_id
          AND ticket.trunk_id = operation.trunk_id
          AND ticket.task_id IS NULL AND ticket.stage_id IS NULL
          AND ticket.expected_base_sha IS operation.previous_base_sha
          AND ticket.status = 'REQUESTED'
          AND wake.id = 'V2_DISPATCH_TICKET_REQUESTED:' || ticket.id
          AND wake.status = 'PENDING'
          AND transition.aggregate_version = NEW.returned_trunk_version
          AND transition.cause = 'REQUEST_PLANNING_BASE_REFRESH')
    THEN RAISE(ABORT, 'Planning-base request receipt is not exact') END;
END;

CREATE TRIGGER trunk_planning_base_result_exact
BEFORE INSERT ON trunk_planning_base_result_receipt
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM planning_base_refresh_operation operation
        JOIN dispatch_ticket ticket ON ticket.id = operation.dispatch_ticket_id
        JOIN trunk_transition transition
          ON transition.trunk_id = operation.trunk_id
         AND transition.command_id = NEW.command_id
        WHERE operation.id = NEW.planning_operation_id
          AND operation.trunk_id = NEW.trunk_id
          AND operation.operation_id = NEW.operation_id
          AND operation.raw_outcome = NEW.raw_outcome
          AND operation.raw_result_digest = NEW.raw_result_digest
          AND operation.status <> 'REQUESTED'
          AND ticket.status = 'RESULT_PENDING'
          AND ticket.pending_result_outcome = NEW.raw_outcome
          AND ticket.pending_result_operation_id = operation.operation_id
          AND ticket.pending_result_attempt = operation.semantic_attempt
          AND ticket.pending_result_expected_base_sha IS operation.previous_base_sha
          AND transition.aggregate_version = NEW.returned_trunk_version
          AND transition.cause = 'ACCEPT_PLANNING_BASE_REFRESH')
    THEN RAISE(ABORT, 'Planning-base result receipt is not exact') END;
END;

CREATE TRIGGER planning_base_refresh_identity_immutable
BEFORE UPDATE OF trunk_id, workspace_id, command_id, actor, operation_id,
        dispatch_ticket_id, launch_command_id, reserved_thread_turn_id,
        semantic_attempt,
        repository_root, previous_base_sha, launch_intent,
        launch_intent_digest, requested_at_ms
        ON planning_base_refresh_operation
WHEN NEW.trunk_id IS NOT OLD.trunk_id
  OR NEW.workspace_id IS NOT OLD.workspace_id
  OR NEW.command_id IS NOT OLD.command_id
  OR NEW.actor IS NOT OLD.actor
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.dispatch_ticket_id IS NOT OLD.dispatch_ticket_id
  OR NEW.launch_command_id IS NOT OLD.launch_command_id
  OR NEW.reserved_thread_turn_id IS NOT OLD.reserved_thread_turn_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.repository_root IS NOT OLD.repository_root
  OR NEW.previous_base_sha IS NOT OLD.previous_base_sha
  OR NEW.launch_intent IS NOT OLD.launch_intent
  OR NEW.launch_intent_digest IS NOT OLD.launch_intent_digest
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'Planning-base Operation identity is immutable'); END;

CREATE TRIGGER planning_base_refresh_transition
BEFORE UPDATE OF status ON planning_base_refresh_operation
WHEN NEW.status IS NOT OLD.status
  AND NOT (OLD.status = 'REQUESTED'
      AND NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
BEGIN SELECT RAISE(ABORT, 'Planning-base Operation transition is invalid'); END;

CREATE TRIGGER planning_base_refresh_terminal_immutable
BEFORE UPDATE ON planning_base_refresh_operation
WHEN OLD.status <> 'REQUESTED'
  AND NOT (OLD.status = 'SUCCEEDED'
      AND OLD.launch_disposition = 'PENDING'
      AND NEW.launch_disposition IN ('LAUNCHED', 'SUPPRESSED')
      AND NEW.launched_at_ms IS NOT NULL
      AND NEW.status = OLD.status
      AND NEW.raw_outcome = OLD.raw_outcome
      AND NEW.raw_result_digest = OLD.raw_result_digest
      AND NEW.result_worktree_path = OLD.result_worktree_path
      AND NEW.result_base_ref = OLD.result_base_ref
      AND NEW.result_base_sha = OLD.result_base_sha
      AND NEW.error_message IS OLD.error_message
      AND NEW.completed_at_ms = OLD.completed_at_ms
      AND ((NEW.launch_disposition = 'LAUNCHED'
              AND NEW.launch_disposition_reason IS NULL
              AND NEW.launched_thread_turn_id = OLD.reserved_thread_turn_id)
          OR (NEW.launch_disposition = 'SUPPRESSED'
              AND NEW.launch_disposition_reason IS NOT NULL
              AND NEW.launched_thread_turn_id IS NULL)))
BEGIN SELECT RAISE(ABORT, 'Terminal Planning-base Operation is immutable'); END;

CREATE TRIGGER planning_base_refresh_delete_immutable
BEFORE DELETE ON planning_base_refresh_operation
BEGIN SELECT RAISE(ABORT, 'Planning-base Operation cannot be deleted'); END;

CREATE TRIGGER trunk_planning_base_request_receipt_immutable
BEFORE UPDATE ON trunk_planning_base_request_receipt
BEGIN SELECT RAISE(ABORT, 'Planning-base request receipt is immutable'); END;
CREATE TRIGGER trunk_planning_base_result_receipt_immutable
BEFORE UPDATE ON trunk_planning_base_result_receipt
BEGIN SELECT RAISE(ABORT, 'Planning-base result receipt is immutable'); END;

-- Trunk conversations carry the exact accepted planning Operation and SHA.
ALTER TABLE thread_turn ADD COLUMN planning_operation_id TEXT
    REFERENCES planning_base_refresh_operation(id);
ALTER TABLE thread_turn ADD COLUMN expected_base_sha TEXT;

DROP TRIGGER thread_turn_identity_immutable;
CREATE TRIGGER thread_turn_identity_immutable
BEFORE UPDATE OF trunk_id, purpose, operation_id, attempt, delivery_lane,
        launch_input, planning_operation_id, expected_base_sha ON thread_turn
WHEN NEW.trunk_id IS NOT OLD.trunk_id
  OR NEW.purpose IS NOT OLD.purpose
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.attempt IS NOT OLD.attempt
  OR NEW.delivery_lane IS NOT OLD.delivery_lane
  OR NEW.launch_input IS NOT OLD.launch_input
  OR NEW.planning_operation_id IS NOT OLD.planning_operation_id
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
BEGIN SELECT RAISE(ABORT, 'thread Turn launch identity is immutable'); END;

CREATE TRIGGER thread_turn_planning_fence_insert
BEFORE INSERT ON thread_turn
WHEN (NEW.purpose = 'TRUNK_CONVERSATION')
     <> (NEW.planning_operation_id IS NOT NULL AND NEW.expected_base_sha IS NOT NULL)
  OR (NEW.planning_operation_id IS NOT NULL AND NOT EXISTS (
      SELECT 1
      FROM planning_base_refresh_operation planning
      JOIN dispatch_ticket ticket ON ticket.id = planning.dispatch_ticket_id
      WHERE planning.id = NEW.planning_operation_id
        AND planning.trunk_id = NEW.trunk_id
        AND planning.reserved_thread_turn_id = NEW.id
        AND planning.status = 'SUCCEEDED'
        AND planning.result_base_sha = NEW.expected_base_sha
        AND planning.result_worktree_path = json_extract(
            NEW.launch_input, '$.workingDirectory')
        AND ticket.status = 'SUCCEEDED'
        AND ticket.delivery_acceptance = 'ACCEPTED'))
BEGIN SELECT RAISE(ABORT, 'ThreadTurn lacks its exact planning snapshot'); END;

CREATE TRIGGER planning_base_dispatch_shape_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'REFRESH_PLANNING_BASE'
  OR NEW.callback_route = 'PLANNING_BASE_REFRESH_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM planning_base_refresh_operation operation
        WHERE operation.dispatch_ticket_id = NEW.id
          AND operation.operation_id = NEW.operation_id
          AND operation.trunk_id = NEW.owner_id
          AND operation.workspace_id = NEW.workspace_id
          AND operation.semantic_attempt = NEW.attempt
          AND operation.previous_base_sha IS NEW.expected_base_sha)
      OR NEW.async_family <> 'LOCAL_GIT'
      OR NEW.owner_kind <> 'TRUNK'
      OR NEW.callback_route <> 'PLANNING_BASE_REFRESH_RESULT'
      OR NEW.lane_mask <> 16
      OR NEW.trunk_control <> 1
      OR NEW.exclusive_task <> 0
      OR NEW.writer_required <> 0
      OR NEW.task_id IS NOT NULL OR NEW.task_epoch IS NOT NULL
      OR NEW.stage_id IS NOT NULL OR NEW.stage_generation IS NOT NULL
    THEN RAISE(ABORT, 'Planning-base refresh ticket is not exact') END;
END;

CREATE TRIGGER planning_base_dispatch_shape_update
BEFORE UPDATE OF operation_kind, async_family, owner_kind, owner_id,
        callback_route, lane_mask, trunk_control, exclusive_task,
        writer_required, workspace_id, trunk_id, task_id, task_epoch,
        stage_id, stage_generation, expected_base_sha ON dispatch_ticket
WHEN NEW.operation_kind = 'REFRESH_PLANNING_BASE'
  OR NEW.callback_route = 'PLANNING_BASE_REFRESH_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM planning_base_refresh_operation operation
        WHERE operation.dispatch_ticket_id = NEW.id
          AND operation.operation_id = NEW.operation_id
          AND operation.trunk_id = NEW.owner_id
          AND operation.workspace_id = NEW.workspace_id
          AND operation.semantic_attempt = NEW.attempt
          AND operation.previous_base_sha IS NEW.expected_base_sha)
      OR NEW.async_family <> 'LOCAL_GIT'
      OR NEW.owner_kind <> 'TRUNK'
      OR NEW.callback_route <> 'PLANNING_BASE_REFRESH_RESULT'
      OR NEW.lane_mask <> 16
      OR NEW.trunk_control <> 1
      OR NEW.exclusive_task <> 0
      OR NEW.writer_required <> 0
      OR NEW.task_id IS NOT NULL OR NEW.task_epoch IS NOT NULL
      OR NEW.stage_id IS NOT NULL OR NEW.stage_generation IS NOT NULL
    THEN RAISE(ABORT, 'Planning-base refresh ticket is not exact') END;
END;
