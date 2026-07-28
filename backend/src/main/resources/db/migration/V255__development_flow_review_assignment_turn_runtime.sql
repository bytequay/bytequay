-- Durable execution and result projection for typed AgentReview seats.

CREATE UNIQUE INDEX idx_review_assignment_turn_assignment_attempt
    ON review_assignment_turn(assignment_id, attempt);

CREATE TABLE review_assignment_turn_request_receipt (
    id                  TEXT    NOT NULL PRIMARY KEY,
    turn_id             TEXT    NOT NULL UNIQUE
        REFERENCES review_assignment_turn(id) ON DELETE CASCADE,
    assignment_id       TEXT    NOT NULL REFERENCES review_assignment(id) ON DELETE CASCADE,
    round_id            TEXT    NOT NULL REFERENCES review_round(id) ON DELETE CASCADE,
    operation_id        TEXT    NOT NULL UNIQUE,
    dispatch_ticket_id  TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    attempt             INTEGER NOT NULL CHECK (attempt > 0),
    start_commit        TEXT    NOT NULL,
    recorded_at_ms      INTEGER NOT NULL
);

CREATE TRIGGER review_assignment_turn_request_receipt_exact
BEFORE INSERT ON review_assignment_turn_request_receipt
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM review_assignment_turn turn
        JOIN review_assignment assignment ON assignment.id = turn.assignment_id
        JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
        WHERE turn.id = NEW.turn_id
          AND turn.assignment_id = NEW.assignment_id
          AND assignment.round_id = NEW.round_id
          AND turn.operation_id = NEW.operation_id
          AND turn.attempt = NEW.attempt
          AND turn.start_commit = NEW.start_commit
          AND ticket.operation_id = NEW.operation_id
          AND ticket.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
          AND ticket.owner_id = NEW.turn_id)
    THEN RAISE(ABORT, 'review Turn request receipt is not exact') END;
END;

CREATE TRIGGER review_assignment_turn_request_receipt_immutable
BEFORE UPDATE ON review_assignment_turn_request_receipt
BEGIN SELECT RAISE(ABORT, 'review Turn request receipt is immutable'); END;

CREATE TABLE review_assignment_turn_result_receipt (
    id                   TEXT    NOT NULL PRIMARY KEY,
    turn_id              TEXT    NOT NULL UNIQUE
        REFERENCES review_assignment_turn(id) ON DELETE CASCADE,
    assignment_id        TEXT    NOT NULL REFERENCES review_assignment(id) ON DELETE CASCADE,
    round_id             TEXT    NOT NULL REFERENCES review_round(id) ON DELETE CASCADE,
    review_id            TEXT    NOT NULL REFERENCES review_session(id) ON DELETE CASCADE,
    operation_id         TEXT    NOT NULL UNIQUE,
    attempt              INTEGER NOT NULL CHECK (attempt > 0),
    start_commit         TEXT    NOT NULL,
    raw_result_digest    TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    raw_outcome          TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    disposition          TEXT,
    acceptance           TEXT    NOT NULL CHECK (acceptance IN ('ACCEPTED', 'SUPERSEDED')),
    terminal_status      TEXT    NOT NULL CHECK (terminal_status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    final_text           TEXT    NOT NULL,
    input_tokens         INTEGER NOT NULL CHECK (input_tokens >= 0),
    output_tokens        INTEGER NOT NULL CHECK (output_tokens >= 0),
    cost_usd_milli       INTEGER NOT NULL CHECK (cost_usd_milli >= 0),
    provider_session_id  TEXT,
    payload_json         TEXT,
    evidence_json        TEXT,
    error_message        TEXT,
    recorded_at_ms       INTEGER NOT NULL
);

CREATE TRIGGER review_assignment_turn_result_receipt_exact
BEFORE INSERT ON review_assignment_turn_result_receipt
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM review_assignment_turn turn
        JOIN review_assignment assignment ON assignment.id = turn.assignment_id
        JOIN review_round round ON round.id = assignment.round_id
        WHERE turn.id = NEW.turn_id
          AND turn.assignment_id = NEW.assignment_id
          AND assignment.round_id = NEW.round_id
          AND round.session_id = NEW.review_id
          AND turn.operation_id = NEW.operation_id
          AND turn.attempt = NEW.attempt
          AND turn.start_commit = NEW.start_commit
          AND turn.status = NEW.terminal_status
          AND turn.finished_at_ms IS NOT NULL)
    THEN RAISE(ABORT, 'review Turn result receipt is not exact') END;
END;

CREATE TRIGGER review_assignment_turn_result_receipt_immutable
BEFORE UPDATE ON review_assignment_turn_result_receipt
BEGIN SELECT RAISE(ABORT, 'review Turn result receipt is immutable'); END;

-- V223 initially allowed only detached review tickets. Review seats now keep
-- the exact Workspace / Trunk / Task epoch of their owning review session.
DROP TRIGGER dispatch_ticket_owner_insert;
CREATE TRIGGER dispatch_ticket_owner_insert
BEFORE INSERT ON dispatch_ticket
BEGIN
    SELECT CASE
        WHEN NEW.task_id IS NOT NULL AND NOT EXISTS (
            SELECT 1
            FROM tasks t
            JOIN threads h ON h.id = t.thread_id
            WHERE t.id = NEW.task_id
              AND t.workflow_version = 'V2'
              AND t.epoch = NEW.task_epoch
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'DispatchTicket Task scope is invalid')
        WHEN NEW.stage_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM stage s
            WHERE s.id = NEW.stage_id
              AND s.task_id = NEW.task_id
              AND s.generation = NEW.stage_generation)
            THEN RAISE(ABORT, 'DispatchTicket Stage scope is invalid')
        WHEN NEW.owner_kind = 'TRUNK' AND NOT EXISTS (
            SELECT 1 FROM threads x
            WHERE x.id = NEW.owner_id
              AND x.id = NEW.trunk_id
              AND x.workspace_id IS NEW.workspace_id
              AND x.turn_version = 'V2'
              AND NEW.task_id IS NULL AND NEW.stage_id IS NULL)
            THEN RAISE(ABORT, 'DispatchTicket Trunk owner is invalid')
        WHEN NEW.owner_kind = 'TASK' AND NOT EXISTS (
            SELECT 1 FROM tasks x JOIN threads h ON h.id = x.thread_id
            WHERE x.id = NEW.owner_id AND x.workflow_version = 'V2'
              AND x.id = NEW.task_id AND x.epoch = NEW.task_epoch
              AND x.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'DispatchTicket Task owner fence is invalid')
        WHEN NEW.owner_kind = 'STAGE' AND NOT EXISTS (
            SELECT 1
            FROM stage s
            JOIN tasks t ON t.id = s.task_id
            JOIN threads h ON h.id = t.thread_id
            WHERE s.id = NEW.owner_id
              AND s.id = NEW.stage_id
              AND s.generation = NEW.stage_generation
              AND t.id = NEW.task_id
              AND t.epoch = NEW.task_epoch
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'DispatchTicket Stage owner fence is invalid')
        WHEN NEW.owner_kind = 'THREAD_TURN' AND NOT EXISTS (
            SELECT 1 FROM thread_turn x JOIN threads h ON h.id = x.trunk_id
            WHERE x.id = NEW.owner_id AND x.operation_id = NEW.operation_id
              AND x.trunk_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id
              AND NEW.task_id IS NULL AND NEW.stage_id IS NULL)
            THEN RAISE(ABORT, 'DispatchTicket ThreadTurn owner is invalid')
        WHEN NEW.owner_kind = 'TASK_TURN' AND NOT EXISTS (
            SELECT 1
            FROM task_turn x
            JOIN tasks t ON t.id = x.task_id
            JOIN threads h ON h.id = t.thread_id
            WHERE x.id = NEW.owner_id AND x.operation_id = NEW.operation_id
              AND x.task_id = NEW.task_id AND x.task_epoch = NEW.task_epoch
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id
              AND (NEW.stage_id IS NULL OR EXISTS (
                  SELECT 1 FROM stage s
                  WHERE s.id = NEW.stage_id
                    AND s.task_id = NEW.task_id
                    AND s.generation = NEW.stage_generation)))
            THEN RAISE(ABORT, 'DispatchTicket TaskTurn owner fence is invalid')
        WHEN NEW.owner_kind = 'STAGE_TURN' AND NOT EXISTS (
            SELECT 1
            FROM stage_turn x
            JOIN stage s ON s.id = x.stage_id
            JOIN tasks t ON t.id = s.task_id
            JOIN threads h ON h.id = t.thread_id
            WHERE x.id = NEW.owner_id AND x.operation_id = NEW.operation_id
              AND x.task_epoch = NEW.task_epoch
              AND x.stage_id = NEW.stage_id
              AND x.stage_generation = NEW.stage_generation
              AND s.task_id = NEW.task_id
              AND t.thread_id = NEW.trunk_id
              AND h.workspace_id IS NEW.workspace_id)
            THEN RAISE(ABORT, 'DispatchTicket StageTurn owner fence is invalid')
        WHEN NEW.owner_kind = 'REVIEW_ASSIGNMENT_TURN' AND NOT EXISTS (
            SELECT 1
            FROM review_assignment_turn turn
            JOIN review_assignment assignment ON assignment.id = turn.assignment_id
            JOIN review_round round ON round.id = assignment.round_id
            JOIN review_session session ON session.id = round.session_id
            LEFT JOIN tasks task ON task.id = session.owner_task_id
            WHERE turn.id = NEW.owner_id
              AND turn.operation_id = NEW.operation_id
              AND NEW.workspace_id IS session.workspace_id
              AND NEW.trunk_id IS session.owner_thread_id
              AND NEW.task_id IS session.owner_task_id
              AND NEW.task_epoch IS CASE
                  WHEN session.owner_task_id IS NULL THEN NULL ELSE task.epoch END
              AND NEW.stage_id IS NULL
              AND (session.owner_task_id IS NULL
                OR task.workflow_version = 'V2'))
            THEN RAISE(ABORT, 'DispatchTicket review Turn owner is invalid')
    END;
END;

CREATE TRIGGER v2_review_assignment_turn_ticket_shape_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
BEGIN
    SELECT CASE
        WHEN NEW.operation_kind <> 'EXECUTE_REVIEW_ASSIGNMENT_TURN'
          OR NEW.async_family <> 'AGENT_TURN'
          OR NEW.callback_route <> 'REVIEW_ASSIGNMENT_TURN_RESULT'
          OR NEW.lane_mask NOT IN (9, 10)
          OR NEW.trunk_control <> 0
          OR NEW.exclusive_task <> 0
          OR NEW.writer_required <> 0
          OR NEW.stage_id IS NOT NULL
          OR NEW.stage_generation IS NOT NULL
          OR NEW.expected_code_fingerprint IS NOT NULL
          OR NEW.expected_base_sha IS NOT NULL
          OR NOT EXISTS (
              SELECT 1
              FROM review_assignment_turn turn
              WHERE turn.id = NEW.owner_id
                AND turn.operation_id = NEW.operation_id
                AND turn.attempt = NEW.attempt
                AND turn.start_commit = NEW.expected_head_sha
                AND ((turn.delivery_lane = 'CLI' AND NEW.lane_mask = 9)
                  OR (turn.delivery_lane = 'API' AND NEW.lane_mask = 10))
                AND json_valid(turn.launch_input)
                AND json_extract(turn.launch_input, '$.schemaVersion') = 1
                AND json_extract(turn.launch_input, '$.toolEndpoint.ownerKind')
                    = 'REVIEW_ASSIGNMENT_TURN'
                AND json_extract(turn.launch_input, '$.toolEndpoint.ownerId') = turn.id
                AND json_extract(turn.launch_input, '$.toolEndpoint.operationId')
                    = turn.operation_id
                AND json_extract(turn.launch_input, '$.toolEndpoint.profile')
                    = 'REVIEW_ASSIGNMENT_READ_ONLY')
        THEN RAISE(ABORT, 'review Turn requires exact read-only REVIEW capacity')
    END;
END;

CREATE TRIGGER v2_review_assignment_turn_ticket_shape_update
BEFORE UPDATE OF operation_kind, async_family, owner_kind, callback_route,
        lane_mask, trunk_control, exclusive_task, writer_required,
        workspace_id, trunk_id, task_id, task_epoch, stage_id,
        stage_generation, expected_code_fingerprint, expected_head_sha,
        expected_base_sha ON dispatch_ticket
WHEN NEW.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
BEGIN
    SELECT CASE
        WHEN NEW.operation_kind <> 'EXECUTE_REVIEW_ASSIGNMENT_TURN'
          OR NEW.async_family <> 'AGENT_TURN'
          OR NEW.callback_route <> 'REVIEW_ASSIGNMENT_TURN_RESULT'
          OR NEW.lane_mask NOT IN (9, 10)
          OR NEW.trunk_control <> 0
          OR NEW.exclusive_task <> 0
          OR NEW.writer_required <> 0
          OR NEW.stage_id IS NOT NULL
          OR NEW.stage_generation IS NOT NULL
          OR NEW.expected_code_fingerprint IS NOT NULL
          OR NEW.expected_base_sha IS NOT NULL
          OR NOT EXISTS (
              SELECT 1
              FROM review_assignment_turn turn
              WHERE turn.id = NEW.owner_id
                AND turn.operation_id = NEW.operation_id
                AND turn.attempt = NEW.attempt
                AND turn.start_commit = NEW.expected_head_sha
                AND ((turn.delivery_lane = 'CLI' AND NEW.lane_mask = 9)
                  OR (turn.delivery_lane = 'API' AND NEW.lane_mask = 10)))
        THEN RAISE(ABORT, 'review Turn requires exact read-only REVIEW capacity')
    END;
END;
