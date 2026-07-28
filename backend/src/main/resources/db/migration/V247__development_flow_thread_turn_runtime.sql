-- Durable V2 Trunk conversation runtime. A ThreadTurn is admitted only through
-- the reserved Trunk-control lane, and both request and result commands keep an
-- immutable receipt so at-least-once delivery cannot duplicate conversation
-- messages or advance the Trunk twice.

CREATE TABLE trunk_thread_turn_request_receipt (
    id                       TEXT    NOT NULL PRIMARY KEY,
    trunk_id                 TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    command_id               TEXT    NOT NULL,
    actor                    TEXT    NOT NULL,
    expected_trunk_version   INTEGER NOT NULL CHECK (expected_trunk_version >= 0),
    returned_trunk_version   INTEGER NOT NULL CHECK (
        returned_trunk_version = expected_trunk_version + 1),
    returned_lifecycle       TEXT    NOT NULL CHECK (returned_lifecycle IN (
        'ACTIVE', 'IDLE', 'ARCHIVED')),
    turn_id                  TEXT    NOT NULL UNIQUE
        REFERENCES thread_turn(id) ON DELETE CASCADE,
    operation_id             TEXT    NOT NULL UNIQUE,
    dispatch_ticket_id       TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    purpose                  TEXT    NOT NULL,
    delivery_lane            TEXT    NOT NULL CHECK (delivery_lane IN ('CLI', 'API')),
    launch_input_digest      TEXT    NOT NULL CHECK (length(launch_input_digest) = 64),
    user_message_digest      TEXT    NOT NULL CHECK (length(user_message_digest) = 64),
    recorded_at_ms           INTEGER NOT NULL,
    UNIQUE (trunk_id, command_id)
);

CREATE TABLE trunk_thread_turn_result_receipt (
    id                       TEXT    NOT NULL PRIMARY KEY,
    trunk_id                 TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    command_id               TEXT    NOT NULL,
    actor                    TEXT    NOT NULL,
    turn_id                  TEXT    NOT NULL UNIQUE
        REFERENCES thread_turn(id) ON DELETE CASCADE,
    operation_id             TEXT    NOT NULL UNIQUE,
    attempt                  INTEGER NOT NULL CHECK (attempt > 0),
    raw_result_digest        TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    raw_outcome              TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    acceptance               TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    terminal_status          TEXT    NOT NULL CHECK (terminal_status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    assistant_message_id     TEXT REFERENCES thread_message(id) ON DELETE RESTRICT,
    returned_trunk_version   INTEGER NOT NULL CHECK (returned_trunk_version > 0),
    returned_lifecycle       TEXT    NOT NULL CHECK (returned_lifecycle IN (
        'ACTIVE', 'IDLE', 'ARCHIVED')),
    recorded_at_ms           INTEGER NOT NULL,
    CHECK (assistant_message_id IS NULL
        OR (acceptance = 'ACCEPTED' AND terminal_status = 'SUCCEEDED')),
    UNIQUE (trunk_id, command_id)
);

CREATE TRIGGER trunk_thread_turn_request_receipt_exact
BEFORE INSERT ON trunk_thread_turn_request_receipt
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM thread_turn turn
        JOIN thread_message message
          ON message.turn_id = turn.id AND message.seq = 1
        JOIN dispatch_ticket ticket
          ON ticket.id = NEW.dispatch_ticket_id
        WHERE turn.id = NEW.turn_id
          AND turn.trunk_id = NEW.trunk_id
          AND turn.operation_id = NEW.operation_id
          AND turn.purpose = NEW.purpose
          AND turn.delivery_lane = NEW.delivery_lane
          AND message.role = 'user'
          AND ticket.operation_id = NEW.operation_id
          AND ticket.operation_kind = 'EXECUTE_THREAD_TURN'
          AND ticket.async_family = 'AGENT_TURN'
          AND ticket.owner_kind = 'THREAD_TURN'
          AND ticket.owner_id = NEW.turn_id
          AND ticket.callback_route = 'THREAD_TURN_RESULT'
          AND ticket.trunk_control = 1
          AND ticket.exclusive_task = 0
          AND ticket.writer_required = 0
          AND ticket.workspace_id IS NOT NULL
          AND ticket.trunk_id = NEW.trunk_id
          AND ticket.task_id IS NULL
          AND ticket.stage_id IS NULL)
    THEN RAISE(ABORT, 'ThreadTurn request receipt is not exact') END;
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM trunk_transition transition
        WHERE transition.trunk_id = NEW.trunk_id
          AND transition.command_id = NEW.command_id
          AND transition.aggregate_version = NEW.returned_trunk_version
          AND transition.to_state = NEW.returned_lifecycle
          AND transition.cause = 'REQUEST_THREAD_TURN')
    THEN RAISE(ABORT, 'ThreadTurn request lacks its Trunk transition') END;
END;

CREATE TRIGGER trunk_thread_turn_result_receipt_exact
BEFORE INSERT ON trunk_thread_turn_result_receipt
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM thread_turn turn
        WHERE turn.id = NEW.turn_id
          AND turn.trunk_id = NEW.trunk_id
          AND turn.operation_id = NEW.operation_id
          AND turn.attempt = NEW.attempt
          AND turn.status = NEW.terminal_status
          AND turn.finished_at_ms IS NOT NULL)
    THEN RAISE(ABORT, 'ThreadTurn result receipt is not exact') END;
    SELECT CASE WHEN NEW.assistant_message_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM thread_message message
        WHERE message.id = NEW.assistant_message_id
          AND message.turn_id = NEW.turn_id
          AND message.seq = 2
          AND message.role = 'assistant')
    THEN RAISE(ABORT, 'ThreadTurn result message is not exact') END;
    SELECT CASE WHEN NEW.terminal_status <> 'SUCCEEDED'
        AND EXISTS (
            SELECT 1 FROM thread_message message
            WHERE message.turn_id = NEW.turn_id AND message.seq = 2)
    THEN RAISE(ABORT, 'ThreadTurn result message shape is invalid') END;
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM trunk_transition transition
        WHERE transition.trunk_id = NEW.trunk_id
          AND transition.command_id = NEW.command_id
          AND transition.aggregate_version = NEW.returned_trunk_version
          AND transition.to_state = NEW.returned_lifecycle
          AND transition.cause = 'ACCEPT_THREAD_TURN_RESULT')
    THEN RAISE(ABORT, 'ThreadTurn result lacks its Trunk transition') END;
END;

CREATE TRIGGER trunk_thread_turn_request_receipt_immutable
BEFORE UPDATE ON trunk_thread_turn_request_receipt
BEGIN SELECT RAISE(ABORT, 'ThreadTurn request receipt is immutable'); END;

CREATE TRIGGER trunk_thread_turn_result_receipt_immutable
BEFORE UPDATE ON trunk_thread_turn_result_receipt
BEGIN SELECT RAISE(ABORT, 'ThreadTurn result receipt is immutable'); END;

-- V223 already verifies the typed owner identity. These stronger guards make
-- it impossible for a future caller to put a Trunk conversation onto ordinary
-- Task capacity or grant it a worktree writer lease.
CREATE TRIGGER v2_thread_turn_ticket_shape_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.owner_kind = 'THREAD_TURN'
BEGIN
    SELECT CASE
        WHEN NEW.operation_kind <> 'EXECUTE_THREAD_TURN'
          OR NEW.async_family <> 'AGENT_TURN'
          OR NEW.callback_route <> 'THREAD_TURN_RESULT'
          OR NEW.lane_mask NOT IN (1, 2)
          OR NEW.trunk_control <> 1
          OR NEW.exclusive_task <> 0
          OR NEW.writer_required <> 0
          OR NEW.workspace_id IS NULL
          OR NEW.trunk_id IS NULL
          OR NEW.task_id IS NOT NULL
          OR NEW.task_epoch IS NOT NULL
          OR NEW.stage_id IS NOT NULL
          OR NEW.stage_generation IS NOT NULL
          OR NOT EXISTS (
              SELECT 1 FROM thread_turn turn
              WHERE turn.id = NEW.owner_id
                AND ((turn.delivery_lane = 'CLI' AND NEW.lane_mask = 1)
                  OR (turn.delivery_lane = 'API' AND NEW.lane_mask = 2)))
        THEN RAISE(ABORT, 'ThreadTurn requires exact reserved Trunk-control capacity')
    END;
END;

CREATE TRIGGER v2_thread_turn_ticket_shape_update
BEFORE UPDATE OF operation_kind, async_family, owner_kind, callback_route,
        lane_mask, trunk_control, exclusive_task, writer_required,
        workspace_id, trunk_id, task_id, task_epoch, stage_id,
        stage_generation ON dispatch_ticket
WHEN NEW.owner_kind = 'THREAD_TURN'
BEGIN
    SELECT CASE
        WHEN NEW.operation_kind <> 'EXECUTE_THREAD_TURN'
          OR NEW.async_family <> 'AGENT_TURN'
          OR NEW.callback_route <> 'THREAD_TURN_RESULT'
          OR NEW.lane_mask NOT IN (1, 2)
          OR NEW.trunk_control <> 1
          OR NEW.exclusive_task <> 0
          OR NEW.writer_required <> 0
          OR NEW.workspace_id IS NULL
          OR NEW.trunk_id IS NULL
          OR NEW.task_id IS NOT NULL
          OR NEW.task_epoch IS NOT NULL
          OR NEW.stage_id IS NOT NULL
          OR NEW.stage_generation IS NOT NULL
          OR NOT EXISTS (
              SELECT 1 FROM thread_turn turn
              WHERE turn.id = NEW.owner_id
                AND ((turn.delivery_lane = 'CLI' AND NEW.lane_mask = 1)
                  OR (turn.delivery_lane = 'API' AND NEW.lane_mask = 2)))
        THEN RAISE(ABORT, 'ThreadTurn requires exact reserved Trunk-control capacity')
    END;
END;
