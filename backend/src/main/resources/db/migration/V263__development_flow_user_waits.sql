-- Durable V2 user waits. Questions remain physically owned by their exact
-- typed Turn; the extra columns preserve the existing card contract without
-- falling back to agent_question. Permission grants remain on the request
-- that created them so one atomic row owns answer and consumption evidence.

ALTER TABLE thread_question ADD COLUMN context TEXT;
ALTER TABLE thread_question ADD COLUMN options_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE thread_question ADD COLUMN allow_free_form INTEGER NOT NULL DEFAULT 1
    CHECK (allow_free_form IN (0, 1));
ALTER TABLE thread_question ADD COLUMN answer_option_id TEXT;
ALTER TABLE thread_question ADD COLUMN answer_free_form TEXT;
ALTER TABLE thread_question ADD COLUMN answer_actor TEXT;
ALTER TABLE thread_question ADD COLUMN continuation_state TEXT NOT NULL DEFAULT 'WAITING'
    CHECK (continuation_state IN (
        'WAITING', 'READY', 'DISPATCHED', 'SUPERSEDED', 'CANCELED'));
ALTER TABLE thread_question ADD COLUMN successor_turn_id TEXT REFERENCES thread_turn(id);
ALTER TABLE thread_question ADD COLUMN continuation_error TEXT;

ALTER TABLE task_question ADD COLUMN context TEXT;
ALTER TABLE task_question ADD COLUMN options_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE task_question ADD COLUMN allow_free_form INTEGER NOT NULL DEFAULT 1
    CHECK (allow_free_form IN (0, 1));
ALTER TABLE task_question ADD COLUMN answer_option_id TEXT;
ALTER TABLE task_question ADD COLUMN answer_free_form TEXT;
ALTER TABLE task_question ADD COLUMN answer_actor TEXT;
ALTER TABLE task_question ADD COLUMN continuation_state TEXT NOT NULL DEFAULT 'WAITING'
    CHECK (continuation_state IN (
        'WAITING', 'READY', 'DISPATCHED', 'SUPERSEDED', 'CANCELED'));
ALTER TABLE task_question ADD COLUMN successor_turn_id TEXT REFERENCES task_turn(id);
ALTER TABLE task_question ADD COLUMN continuation_error TEXT;

ALTER TABLE stage_question ADD COLUMN context TEXT;
ALTER TABLE stage_question ADD COLUMN options_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE stage_question ADD COLUMN allow_free_form INTEGER NOT NULL DEFAULT 1
    CHECK (allow_free_form IN (0, 1));
ALTER TABLE stage_question ADD COLUMN answer_option_id TEXT;
ALTER TABLE stage_question ADD COLUMN answer_free_form TEXT;
ALTER TABLE stage_question ADD COLUMN answer_actor TEXT;
ALTER TABLE stage_question ADD COLUMN continuation_state TEXT NOT NULL DEFAULT 'WAITING'
    CHECK (continuation_state IN (
        'WAITING', 'READY', 'DISPATCHED', 'SUPERSEDED', 'CANCELED'));
ALTER TABLE stage_question ADD COLUMN successor_turn_id TEXT REFERENCES stage_turn(id);
ALTER TABLE stage_question ADD COLUMN continuation_error TEXT;

ALTER TABLE review_assignment_question ADD COLUMN context TEXT;
ALTER TABLE review_assignment_question ADD COLUMN options_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE review_assignment_question ADD COLUMN allow_free_form INTEGER NOT NULL DEFAULT 1
    CHECK (allow_free_form IN (0, 1));
ALTER TABLE review_assignment_question ADD COLUMN answer_option_id TEXT;
ALTER TABLE review_assignment_question ADD COLUMN answer_free_form TEXT;
ALTER TABLE review_assignment_question ADD COLUMN answer_actor TEXT;
ALTER TABLE review_assignment_question ADD COLUMN continuation_state TEXT NOT NULL DEFAULT 'WAITING'
    CHECK (continuation_state IN (
        'WAITING', 'READY', 'DISPATCHED', 'SUPERSEDED', 'CANCELED'));
ALTER TABLE review_assignment_question ADD COLUMN successor_turn_id TEXT REFERENCES review_assignment_turn(id);
ALTER TABLE review_assignment_question ADD COLUMN continuation_error TEXT;

UPDATE thread_question
SET continuation_state = CASE state
    WHEN 'ANSWERED' THEN 'READY' WHEN 'CANCELED' THEN 'CANCELED'
    ELSE 'WAITING' END;
UPDATE task_question
SET continuation_state = CASE state
    WHEN 'ANSWERED' THEN 'READY' WHEN 'CANCELED' THEN 'CANCELED'
    ELSE 'WAITING' END;
UPDATE stage_question
SET continuation_state = CASE state
    WHEN 'ANSWERED' THEN 'READY' WHEN 'CANCELED' THEN 'CANCELED'
    ELSE 'WAITING' END;
UPDATE review_assignment_question
SET continuation_state = CASE state
    WHEN 'ANSWERED' THEN 'READY' WHEN 'CANCELED' THEN 'CANCELED'
    ELSE 'WAITING' END;

CREATE INDEX idx_thread_question_open
    ON thread_question(state, created_at_ms, id);
CREATE INDEX idx_task_question_open
    ON task_question(state, created_at_ms, id);
CREATE INDEX idx_stage_question_open
    ON stage_question(state, created_at_ms, id);
CREATE INDEX idx_review_assignment_question_open
    ON review_assignment_question(state, created_at_ms, id);

-- Every answer attempt is retained even when its optimistic revision is stale
-- or another actor already terminalized the wait.
CREATE TABLE typed_question_answer_attempt (
    id                  TEXT    NOT NULL PRIMARY KEY,
    owner_kind          TEXT    NOT NULL CHECK (owner_kind IN (
        'THREAD_TURN', 'TASK_TURN', 'STAGE_TURN',
        'REVIEW_ASSIGNMENT_TURN')),
    question_id         TEXT    NOT NULL,
    expected_revision   INTEGER NOT NULL CHECK (expected_revision >= 0),
    answer_option_id    TEXT,
    answer_free_form    TEXT,
    actor               TEXT    NOT NULL,
    outcome             TEXT    NOT NULL CHECK (outcome IN (
        'ACCEPTED', 'ALREADY_TERMINAL', 'REVISION_CONFLICT',
        'OWNER_TERMINAL')),
    attempted_at_ms     INTEGER NOT NULL,
    CHECK (answer_option_id IS NOT NULL OR answer_free_form IS NOT NULL)
);

CREATE INDEX idx_typed_question_answer_attempt_question
    ON typed_question_answer_attempt(owner_kind, question_id,
        attempted_at_ms, id);

CREATE TRIGGER thread_question_answer_transition
BEFORE UPDATE OF state, answer, answer_revision, answered_at_ms,
        answer_option_id, answer_free_form, answer_actor
ON thread_question
WHEN NEW.state IS NOT OLD.state
BEGIN
    SELECT CASE WHEN OLD.state <> 'OPEN'
        OR NEW.state NOT IN ('ANSWERED', 'CANCELED')
        OR NEW.answer_revision <> OLD.answer_revision + 1
        OR NEW.answer_actor IS NULL OR length(trim(NEW.answer_actor)) = 0
        OR NEW.answer IS NULL OR length(trim(NEW.answer)) = 0
        OR NEW.answered_at_ms IS NULL OR NEW.answered_at_ms < OLD.created_at_ms
        OR NEW.state = 'ANSWERED'
            AND NEW.answer_option_id IS NULL AND NEW.answer_free_form IS NULL
        OR NEW.state = 'CANCELED' AND NEW.continuation_state <> 'CANCELED'
    THEN RAISE(ABORT, 'thread question answer transition is invalid') END;
END;
CREATE TRIGGER task_question_answer_transition
BEFORE UPDATE OF state, answer, answer_revision, answered_at_ms,
        answer_option_id, answer_free_form, answer_actor
ON task_question
WHEN NEW.state IS NOT OLD.state
BEGIN
    SELECT CASE WHEN OLD.state <> 'OPEN'
        OR NEW.state NOT IN ('ANSWERED', 'CANCELED')
        OR NEW.answer_revision <> OLD.answer_revision + 1
        OR NEW.answer_actor IS NULL OR length(trim(NEW.answer_actor)) = 0
        OR NEW.answer IS NULL OR length(trim(NEW.answer)) = 0
        OR NEW.answered_at_ms IS NULL OR NEW.answered_at_ms < OLD.created_at_ms
        OR NEW.state = 'ANSWERED'
            AND NEW.answer_option_id IS NULL AND NEW.answer_free_form IS NULL
        OR NEW.state = 'CANCELED' AND NEW.continuation_state <> 'CANCELED'
    THEN RAISE(ABORT, 'task question answer transition is invalid') END;
END;
CREATE TRIGGER stage_question_answer_transition
BEFORE UPDATE OF state, answer, answer_revision, answered_at_ms,
        answer_option_id, answer_free_form, answer_actor
ON stage_question
WHEN NEW.state IS NOT OLD.state
BEGIN
    SELECT CASE WHEN OLD.state <> 'OPEN'
        OR NEW.state NOT IN ('ANSWERED', 'CANCELED')
        OR NEW.answer_revision <> OLD.answer_revision + 1
        OR NEW.answer_actor IS NULL OR length(trim(NEW.answer_actor)) = 0
        OR NEW.answer IS NULL OR length(trim(NEW.answer)) = 0
        OR NEW.answered_at_ms IS NULL OR NEW.answered_at_ms < OLD.created_at_ms
        OR NEW.state = 'ANSWERED'
            AND NEW.answer_option_id IS NULL AND NEW.answer_free_form IS NULL
        OR NEW.state = 'CANCELED' AND NEW.continuation_state <> 'CANCELED'
    THEN RAISE(ABORT, 'stage question answer transition is invalid') END;
END;
CREATE TRIGGER review_assignment_question_answer_transition
BEFORE UPDATE OF state, answer, answer_revision, answered_at_ms,
        answer_option_id, answer_free_form, answer_actor
ON review_assignment_question
WHEN NEW.state IS NOT OLD.state
BEGIN
    SELECT CASE WHEN OLD.state <> 'OPEN'
        OR NEW.state NOT IN ('ANSWERED', 'CANCELED')
        OR NEW.answer_revision <> OLD.answer_revision + 1
        OR NEW.answer_actor IS NULL OR length(trim(NEW.answer_actor)) = 0
        OR NEW.answer IS NULL OR length(trim(NEW.answer)) = 0
        OR NEW.answered_at_ms IS NULL OR NEW.answered_at_ms < OLD.created_at_ms
        OR NEW.state = 'ANSWERED'
            AND NEW.answer_option_id IS NULL AND NEW.answer_free_form IS NULL
        OR NEW.state = 'CANCELED' AND NEW.continuation_state <> 'CANCELED'
    THEN RAISE(ABORT, 'review question answer transition is invalid') END;
END;

CREATE TRIGGER thread_question_terminal_immutable
BEFORE UPDATE ON thread_question
WHEN OLD.state <> 'OPEN'
  AND (NEW.state IS NOT OLD.state OR NEW.answer IS NOT OLD.answer
    OR NEW.answer_revision IS NOT OLD.answer_revision
    OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
    OR NEW.answer_option_id IS NOT OLD.answer_option_id
    OR NEW.answer_free_form IS NOT OLD.answer_free_form
    OR NEW.answer_actor IS NOT OLD.answer_actor)
BEGIN SELECT RAISE(ABORT, 'terminal thread question is immutable'); END;
CREATE TRIGGER task_question_terminal_immutable
BEFORE UPDATE ON task_question
WHEN OLD.state <> 'OPEN'
  AND (NEW.state IS NOT OLD.state OR NEW.answer IS NOT OLD.answer
    OR NEW.answer_revision IS NOT OLD.answer_revision
    OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
    OR NEW.answer_option_id IS NOT OLD.answer_option_id
    OR NEW.answer_free_form IS NOT OLD.answer_free_form
    OR NEW.answer_actor IS NOT OLD.answer_actor)
BEGIN SELECT RAISE(ABORT, 'terminal task question is immutable'); END;
CREATE TRIGGER stage_question_terminal_immutable
BEFORE UPDATE ON stage_question
WHEN OLD.state <> 'OPEN'
  AND (NEW.state IS NOT OLD.state OR NEW.answer IS NOT OLD.answer
    OR NEW.answer_revision IS NOT OLD.answer_revision
    OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
    OR NEW.answer_option_id IS NOT OLD.answer_option_id
    OR NEW.answer_free_form IS NOT OLD.answer_free_form
    OR NEW.answer_actor IS NOT OLD.answer_actor)
BEGIN SELECT RAISE(ABORT, 'terminal stage question is immutable'); END;
CREATE TRIGGER review_assignment_question_terminal_immutable
BEFORE UPDATE ON review_assignment_question
WHEN OLD.state <> 'OPEN'
  AND (NEW.state IS NOT OLD.state OR NEW.answer IS NOT OLD.answer
    OR NEW.answer_revision IS NOT OLD.answer_revision
    OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
    OR NEW.answer_option_id IS NOT OLD.answer_option_id
    OR NEW.answer_free_form IS NOT OLD.answer_free_form
    OR NEW.answer_actor IS NOT OLD.answer_actor)
BEGIN SELECT RAISE(ABORT, 'terminal review question is immutable'); END;

CREATE TRIGGER thread_question_continuation_transition
BEFORE UPDATE OF continuation_state, successor_turn_id, continuation_error
ON thread_question
BEGIN
    SELECT CASE WHEN OLD.continuation_state = 'WAITING'
            AND NEW.continuation_state NOT IN ('WAITING', 'READY', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state = 'READY'
            AND NEW.continuation_state NOT IN ('READY', 'DISPATCHED', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state IN ('DISPATCHED', 'CANCELED', 'SUPERSEDED')
            AND (NEW.continuation_state IS NOT OLD.continuation_state
                OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
                OR NEW.continuation_error IS NOT OLD.continuation_error)
        OR NEW.continuation_state = 'DISPATCHED' AND NEW.successor_turn_id IS NULL
    THEN RAISE(ABORT, 'thread question continuation is invalid') END;
END;
CREATE TRIGGER task_question_continuation_transition
BEFORE UPDATE OF continuation_state, successor_turn_id, continuation_error
ON task_question
BEGIN
    SELECT CASE WHEN OLD.continuation_state = 'WAITING'
            AND NEW.continuation_state NOT IN ('WAITING', 'READY', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state = 'READY'
            AND NEW.continuation_state NOT IN ('READY', 'DISPATCHED', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state IN ('DISPATCHED', 'CANCELED', 'SUPERSEDED')
            AND (NEW.continuation_state IS NOT OLD.continuation_state
                OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
                OR NEW.continuation_error IS NOT OLD.continuation_error)
        OR NEW.continuation_state = 'DISPATCHED' AND NEW.successor_turn_id IS NULL
    THEN RAISE(ABORT, 'task question continuation is invalid') END;
END;
CREATE TRIGGER stage_question_continuation_transition
BEFORE UPDATE OF continuation_state, successor_turn_id, continuation_error
ON stage_question
BEGIN
    SELECT CASE WHEN OLD.continuation_state = 'WAITING'
            AND NEW.continuation_state NOT IN ('WAITING', 'READY', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state = 'READY'
            AND NEW.continuation_state NOT IN ('READY', 'DISPATCHED', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state IN ('DISPATCHED', 'CANCELED', 'SUPERSEDED')
            AND (NEW.continuation_state IS NOT OLD.continuation_state
                OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
                OR NEW.continuation_error IS NOT OLD.continuation_error)
        OR NEW.continuation_state = 'DISPATCHED' AND NEW.successor_turn_id IS NULL
    THEN RAISE(ABORT, 'stage question continuation is invalid') END;
END;
CREATE TRIGGER review_assignment_question_continuation_transition
BEFORE UPDATE OF continuation_state, successor_turn_id, continuation_error
ON review_assignment_question
BEGIN
    SELECT CASE WHEN OLD.continuation_state = 'WAITING'
            AND NEW.continuation_state NOT IN ('WAITING', 'READY', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state = 'READY'
            AND NEW.continuation_state NOT IN ('READY', 'DISPATCHED', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state IN ('DISPATCHED', 'CANCELED', 'SUPERSEDED')
            AND (NEW.continuation_state IS NOT OLD.continuation_state
                OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
                OR NEW.continuation_error IS NOT OLD.continuation_error)
        OR NEW.continuation_state = 'DISPATCHED' AND NEW.successor_turn_id IS NULL
    THEN RAISE(ABORT, 'review question continuation is invalid') END;
END;

-- A USER_WAIT is a successful execution suspension, not a failed/canceled
-- provider result. This receipt is the durable distinction used by restart
-- recovery before any successor Turn is admitted.
CREATE TABLE typed_user_wait_result (
    operation_id       TEXT    NOT NULL PRIMARY KEY,
    owner_kind         TEXT    NOT NULL CHECK (owner_kind IN (
        'THREAD_TURN', 'TASK_TURN', 'STAGE_TURN',
        'REVIEW_ASSIGNMENT_TURN')),
    turn_id            TEXT    NOT NULL,
    wait_kind          TEXT    NOT NULL CHECK (wait_kind IN (
        'QUESTION', 'PERMISSION')),
    wait_id            TEXT    NOT NULL,
    payload_digest     TEXT    NOT NULL,
    result_evidence    TEXT    NOT NULL,
    accepted_at_ms     INTEGER NOT NULL,
    UNIQUE (owner_kind, turn_id, wait_kind, wait_id)
);

CREATE TRIGGER typed_user_wait_result_owner_insert
BEFORE INSERT ON typed_user_wait_result
BEGIN
    SELECT CASE WHEN
        (NEW.owner_kind = 'THREAD_TURN' AND NOT EXISTS (
            SELECT 1 FROM thread_turn turn
            WHERE turn.id = NEW.turn_id AND turn.operation_id = NEW.operation_id))
        OR (NEW.owner_kind = 'TASK_TURN' AND NOT EXISTS (
            SELECT 1 FROM task_turn turn
            WHERE turn.id = NEW.turn_id AND turn.operation_id = NEW.operation_id))
        OR (NEW.owner_kind = 'STAGE_TURN' AND NOT EXISTS (
            SELECT 1 FROM stage_turn turn
            WHERE turn.id = NEW.turn_id AND turn.operation_id = NEW.operation_id))
        OR (NEW.owner_kind = 'REVIEW_ASSIGNMENT_TURN' AND NOT EXISTS (
            SELECT 1 FROM review_assignment_turn turn
            WHERE turn.id = NEW.turn_id AND turn.operation_id = NEW.operation_id))
    THEN RAISE(ABORT, 'user-wait result owner is not exact') END;

    SELECT CASE WHEN NEW.wait_kind = 'QUESTION' AND NOT (
        (NEW.owner_kind = 'THREAD_TURN' AND EXISTS (
            SELECT 1 FROM thread_question question
            WHERE question.id = NEW.wait_id AND question.turn_id = NEW.turn_id))
        OR (NEW.owner_kind = 'TASK_TURN' AND EXISTS (
            SELECT 1 FROM task_question question
            WHERE question.id = NEW.wait_id AND question.turn_id = NEW.turn_id))
        OR (NEW.owner_kind = 'STAGE_TURN' AND EXISTS (
            SELECT 1 FROM stage_question question
            WHERE question.id = NEW.wait_id AND question.turn_id = NEW.turn_id))
        OR (NEW.owner_kind = 'REVIEW_ASSIGNMENT_TURN' AND EXISTS (
            SELECT 1 FROM review_assignment_question question
            WHERE question.id = NEW.wait_id AND question.turn_id = NEW.turn_id)))
    THEN RAISE(ABORT, 'question wait does not belong to typed Turn') END;

    SELECT CASE WHEN NEW.wait_kind = 'PERMISSION' AND NOT EXISTS (
        SELECT 1 FROM permission_request permission
        WHERE permission.id = NEW.wait_id
          AND permission.turn_id = NEW.turn_id
          AND permission.operation_id = NEW.operation_id
          AND CASE permission.turn_kind
              WHEN 'THREAD' THEN 'THREAD_TURN'
              WHEN 'TASK' THEN 'TASK_TURN'
              WHEN 'STAGE' THEN 'STAGE_TURN'
              WHEN 'REVIEW_ASSIGNMENT' THEN 'REVIEW_ASSIGNMENT_TURN'
              END = NEW.owner_kind)
    THEN RAISE(ABORT, 'permission wait does not belong to typed Turn') END;
END;

CREATE TRIGGER typed_user_wait_result_immutable
BEFORE UPDATE ON typed_user_wait_result
BEGIN SELECT RAISE(ABORT, 'user-wait result evidence is immutable'); END;

-- A resolved request may also be the durable source of a narrowly-scoped
-- future grant. -1 means an unlimited Task/repository grant; finite grants
-- are consumed with a guarded one-row decrement.
ALTER TABLE permission_request ADD COLUMN parameters_digest TEXT;
ALTER TABLE permission_request ADD COLUMN grant_scope_kind TEXT
    CHECK (grant_scope_kind IN ('CALL', 'TRUNK', 'TASK', 'REPOSITORY'));
ALTER TABLE permission_request ADD COLUMN grant_scope_id TEXT;
ALTER TABLE permission_request ADD COLUMN granted_uses INTEGER
    CHECK (granted_uses = -1 OR granted_uses > 0);
ALTER TABLE permission_request ADD COLUMN remaining_uses INTEGER
    CHECK (remaining_uses = -1 OR remaining_uses >= 0);
ALTER TABLE permission_request ADD COLUMN consumed_uses INTEGER NOT NULL DEFAULT 0
    CHECK (consumed_uses >= 0);
ALTER TABLE permission_request ADD COLUMN answer_actor TEXT;
ALTER TABLE permission_request ADD COLUMN last_consumed_at_ms INTEGER;
ALTER TABLE permission_request ADD COLUMN continuation_state TEXT NOT NULL DEFAULT 'WAITING'
    CHECK (continuation_state IN (
        'WAITING', 'READY', 'DISPATCHED', 'SUPERSEDED', 'CANCELED'));
ALTER TABLE permission_request ADD COLUMN successor_turn_id TEXT;
ALTER TABLE permission_request ADD COLUMN continuation_error TEXT;

CREATE INDEX idx_permission_request_active_grant
    ON permission_request(
        grant_scope_kind, grant_scope_id, tool_name, state, remaining_uses,
        requested_at_ms);

CREATE TABLE permission_answer_attempt (
    id                  TEXT    NOT NULL PRIMARY KEY,
    permission_id       TEXT    NOT NULL REFERENCES permission_request(id) ON DELETE CASCADE,
    expected_revision   INTEGER NOT NULL CHECK (expected_revision >= 0),
    proposed_state      TEXT    NOT NULL CHECK (proposed_state IN (
        'ALLOWED_ONCE', 'ALLOWED_NEXT', 'ALLOWED_TASK',
        'ALLOWED_REPOSITORY', 'DENIED', 'CANCELED', 'EXPIRED')),
    actor               TEXT    NOT NULL,
    answer              TEXT    NOT NULL,
    outcome             TEXT    NOT NULL CHECK (outcome IN (
        'ACCEPTED', 'ALREADY_TERMINAL', 'REVISION_CONFLICT')),
    attempted_at_ms     INTEGER NOT NULL
);

CREATE INDEX idx_permission_answer_attempt_request
    ON permission_answer_attempt(permission_id, attempted_at_ms, id);

CREATE TABLE permission_grant_consumption (
    id                  TEXT    NOT NULL PRIMARY KEY,
    permission_id       TEXT    NOT NULL REFERENCES permission_request(id) ON DELETE CASCADE,
    turn_kind           TEXT    NOT NULL CHECK (turn_kind IN (
        'THREAD', 'TASK', 'STAGE', 'REVIEW_ASSIGNMENT')),
    turn_id             TEXT    NOT NULL,
    operation_id        TEXT    NOT NULL,
    parameters_digest   TEXT    NOT NULL,
    remaining_after     INTEGER NOT NULL CHECK (remaining_after = -1 OR remaining_after >= 0),
    consumed_at_ms      INTEGER NOT NULL,
    UNIQUE (permission_id, turn_kind, turn_id, operation_id,
        parameters_digest)
);

CREATE INDEX idx_permission_grant_consumption_request
    ON permission_grant_consumption(permission_id, consumed_at_ms, id);

CREATE TRIGGER permission_grant_consumption_immutable
BEFORE UPDATE ON permission_grant_consumption
BEGIN SELECT RAISE(ABORT, 'permission consumption evidence is immutable'); END;

CREATE TRIGGER permission_request_answer_transition
BEFORE UPDATE OF
    state, answer, answer_revision, answered_at_ms, answer_actor,
    grant_scope_kind, grant_scope_id, granted_uses, remaining_uses
ON permission_request
WHEN NEW.state IS NOT OLD.state
BEGIN
    SELECT CASE WHEN OLD.state <> 'OPEN'
        OR NEW.state = 'OPEN'
        OR NEW.answer IS NULL OR length(trim(NEW.answer)) = 0
        OR NEW.answer_actor IS NULL OR length(trim(NEW.answer_actor)) = 0
        OR NEW.answer_revision <> OLD.answer_revision + 1
        OR NEW.answered_at_ms IS NULL
        OR NEW.answered_at_ms < OLD.requested_at_ms
        OR NEW.state IN ('ALLOWED_ONCE', 'ALLOWED_NEXT', 'ALLOWED_TASK',
                'ALLOWED_REPOSITORY', 'DENIED')
            AND NEW.continuation_state <> 'READY'
        OR NEW.state IN ('CANCELED', 'EXPIRED')
            AND NEW.continuation_state <> 'CANCELED'
    THEN RAISE(ABORT, 'permission answer transition is invalid') END;

    SELECT CASE WHEN NEW.state IN ('DENIED', 'CANCELED', 'EXPIRED')
        AND (NEW.grant_scope_kind IS NOT NULL
             OR NEW.grant_scope_id IS NOT NULL
             OR NEW.granted_uses IS NOT NULL
             OR NEW.remaining_uses IS NOT NULL)
    THEN RAISE(ABORT, 'denied permission cannot create a grant') END;

    SELECT CASE WHEN NEW.state = 'ALLOWED_ONCE'
        AND (NEW.grant_scope_kind <> 'CALL'
             OR NEW.parameters_digest IS NULL
             OR NEW.grant_scope_id <> NEW.parameters_digest
             OR NEW.granted_uses <> 1 OR NEW.remaining_uses <> 1)
    THEN RAISE(ABORT, 'one-time permission grant is invalid') END;

    SELECT CASE WHEN NEW.state = 'ALLOWED_NEXT'
        AND (NEW.grant_scope_kind NOT IN ('TRUNK', 'TASK', 'REPOSITORY')
             OR NEW.grant_scope_id IS NULL
             OR length(trim(NEW.grant_scope_id)) = 0
             OR NEW.granted_uses IS NULL OR NEW.granted_uses <= 0
             OR NEW.remaining_uses <> NEW.granted_uses)
    THEN RAISE(ABORT, 'finite permission grant is invalid') END;

    SELECT CASE WHEN NEW.state = 'ALLOWED_TASK'
        AND (NEW.grant_scope_kind <> 'TASK'
             OR NEW.grant_scope_id IS NULL
             OR length(trim(NEW.grant_scope_id)) = 0
             OR NEW.granted_uses <> -1 OR NEW.remaining_uses <> -1)
    THEN RAISE(ABORT, 'Task permission grant is invalid') END;

    SELECT CASE WHEN NEW.state = 'ALLOWED_REPOSITORY'
        AND (NEW.grant_scope_kind <> 'REPOSITORY'
             OR NEW.grant_scope_id IS NULL
             OR length(trim(NEW.grant_scope_id)) = 0
             OR NEW.granted_uses <> -1 OR NEW.remaining_uses <> -1)
    THEN RAISE(ABORT, 'repository permission grant is invalid') END;
END;

CREATE TRIGGER permission_request_terminal_state_immutable
BEFORE UPDATE OF state, answer, answer_revision, answered_at_ms, answer_actor
ON permission_request
WHEN OLD.state <> 'OPEN'
  AND (NEW.state IS NOT OLD.state
       OR NEW.answer IS NOT OLD.answer
       OR NEW.answer_revision IS NOT OLD.answer_revision
       OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
       OR NEW.answer_actor IS NOT OLD.answer_actor)
BEGIN SELECT RAISE(ABORT, 'terminal permission answer is immutable'); END;

CREATE TRIGGER permission_request_continuation_transition
BEFORE UPDATE OF continuation_state, successor_turn_id, continuation_error
ON permission_request
BEGIN
    SELECT CASE WHEN OLD.continuation_state = 'WAITING'
            AND NEW.continuation_state NOT IN ('WAITING', 'READY', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state = 'READY'
            AND NEW.continuation_state NOT IN ('READY', 'DISPATCHED', 'CANCELED', 'SUPERSEDED')
        OR OLD.continuation_state IN ('DISPATCHED', 'CANCELED', 'SUPERSEDED')
            AND (NEW.continuation_state IS NOT OLD.continuation_state
                OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
                OR NEW.continuation_error IS NOT OLD.continuation_error)
        OR NEW.continuation_state = 'DISPATCHED'
            AND (NEW.successor_turn_id IS NULL
                OR length(trim(NEW.successor_turn_id)) = 0)
    THEN RAISE(ABORT, 'permission continuation transition is invalid') END;
END;

CREATE TRIGGER permission_request_grant_identity_immutable
BEFORE UPDATE OF
    parameters_digest, grant_scope_kind, grant_scope_id, granted_uses
ON permission_request
WHEN OLD.state <> 'OPEN'
  AND (NEW.parameters_digest IS NOT OLD.parameters_digest
       OR NEW.grant_scope_kind IS NOT OLD.grant_scope_kind
       OR NEW.grant_scope_id IS NOT OLD.grant_scope_id
       OR NEW.granted_uses IS NOT OLD.granted_uses)
BEGIN SELECT RAISE(ABORT, 'permission grant identity is immutable'); END;

CREATE TRIGGER permission_request_parameters_digest_immutable
BEFORE UPDATE OF parameters_digest ON permission_request
WHEN NEW.parameters_digest IS NOT OLD.parameters_digest
BEGIN SELECT RAISE(ABORT, 'permission parameter digest is immutable'); END;

CREATE TRIGGER permission_request_grant_consume
BEFORE UPDATE OF remaining_uses, consumed_uses, last_consumed_at_ms
ON permission_request
WHEN NEW.state IS OLD.state
  AND (NEW.remaining_uses IS NOT OLD.remaining_uses
       OR NEW.consumed_uses IS NOT OLD.consumed_uses
       OR NEW.last_consumed_at_ms IS NOT OLD.last_consumed_at_ms)
BEGIN
    SELECT CASE WHEN OLD.state NOT IN (
            'ALLOWED_ONCE', 'ALLOWED_NEXT',
            'ALLOWED_TASK', 'ALLOWED_REPOSITORY')
        OR OLD.remaining_uses IS NULL OR OLD.remaining_uses = 0
        OR NEW.consumed_uses <> OLD.consumed_uses + 1
        OR NEW.last_consumed_at_ms IS NULL
        OR (OLD.last_consumed_at_ms IS NOT NULL
            AND NEW.last_consumed_at_ms < OLD.last_consumed_at_ms)
        OR NOT (
            (OLD.remaining_uses = -1 AND NEW.remaining_uses = -1)
            OR (OLD.remaining_uses > 0
                AND NEW.remaining_uses = OLD.remaining_uses - 1))
    THEN RAISE(ABORT, 'permission grant consumption is invalid') END;
END;

-- A Plan approval card and Local Review surface are passive durable waits.
-- Resuming a Task at either checkpoint restores the existing gate and must
-- not fabricate an Agent Turn merely to satisfy the older rearm protocol.
CREATE TABLE stage_resume_passive_wait_v263 (
    handoff_id          TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage_resume_rearm_intent_v257(handoff_id) ON DELETE CASCADE,
    wait_kind           TEXT    NOT NULL CHECK (wait_kind IN (
        'PLAN_APPROVAL', 'LOCAL_REVIEW')),
    stage_id            TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation    INTEGER NOT NULL CHECK (stage_generation > 0),
    checkpoint          TEXT    NOT NULL CHECK (checkpoint IN (
        'AWAITING_APPROVAL', 'LOCAL_REVIEW')),
    recorded_at_ms      INTEGER NOT NULL
);

CREATE TRIGGER stage_resume_passive_wait_insert_v263
BEFORE INSERT ON stage_resume_passive_wait_v263
WHEN NOT EXISTS (
    SELECT 1
    FROM stage_resume_rearm_intent_v257 intent
    JOIN task_resume_handoff_v256 handoff ON handoff.id = intent.handoff_id
    JOIN tasks task ON task.id = intent.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    WHERE intent.handoff_id = NEW.handoff_id
      AND intent.status = 'PENDING' AND handoff.status = 'ACCEPTED'
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = intent.task_epoch
      AND current.stage_id = intent.stage_id
      AND current.stage_generation = intent.stage_generation
      AND owner.id = NEW.stage_id
      AND owner.generation = NEW.stage_generation
      AND owner.checkpoint = NEW.checkpoint
      AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
      AND ((NEW.wait_kind = 'PLAN_APPROVAL'
            AND owner.kind = 'PLAN'
            AND NEW.checkpoint = 'AWAITING_APPROVAL')
        OR (NEW.wait_kind = 'LOCAL_REVIEW'
            AND owner.kind = 'LOCAL_DEVELOPMENT'
            AND NEW.checkpoint = 'LOCAL_REVIEW')))
BEGIN SELECT RAISE(ABORT, 'passive resume wait owner is stale'); END;

CREATE TRIGGER stage_resume_passive_wait_immutable_v263
BEFORE UPDATE ON stage_resume_passive_wait_v263
BEGIN SELECT RAISE(ABORT, 'passive resume wait is immutable'); END;

DROP TRIGGER stage_resume_rearm_materialize_v257;
CREATE TRIGGER stage_resume_rearm_materialize_v257
BEFORE UPDATE OF status ON stage_resume_rearm_intent_v257
WHEN NEW.status = 'MATERIALIZED'
 AND NOT EXISTS (
    SELECT 1
    FROM stage_resume_passive_wait_v263 passive
    JOIN tasks task ON task.id = OLD.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    WHERE passive.handoff_id = OLD.handoff_id
      AND passive.stage_id = OLD.stage_id
      AND passive.stage_generation = OLD.stage_generation
      AND passive.checkpoint = OLD.restore_checkpoint
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = OLD.task_epoch
      AND current.stage_id = OLD.stage_id
      AND current.stage_generation = OLD.stage_generation
      AND owner.kind = OLD.stage_kind
      AND owner.generation = OLD.stage_generation
      AND owner.checkpoint = OLD.restore_checkpoint
      AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
      AND ((passive.wait_kind = 'PLAN_APPROVAL'
            AND OLD.stage_kind = 'PLAN'
            AND OLD.restore_checkpoint = 'AWAITING_APPROVAL')
        OR (passive.wait_kind = 'LOCAL_REVIEW'
            AND OLD.stage_kind = 'LOCAL_DEVELOPMENT'
            AND OLD.restore_checkpoint = 'LOCAL_REVIEW')))
 AND NOT EXISTS (
    SELECT 1
    FROM stage_resume_rearm_successor_v257 successor
    JOIN tasks task ON task.id = OLD.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    WHERE successor.handoff_id = OLD.handoff_id
      AND successor.status = 'ARMED'
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = OLD.task_epoch
      AND current.stage_id = OLD.stage_id
      AND current.stage_generation = OLD.stage_generation
      AND owner.kind = OLD.stage_kind
      AND owner.generation = OLD.stage_generation
      AND owner.checkpoint = OLD.restore_checkpoint
      AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
      AND ((successor.owner_kind = 'REMOTE_OBSERVATION'
            AND OLD.stage_kind = 'REMOTE_DEVELOPMENT'
            AND OLD.restore_checkpoint IN (
                'WAITING_CI', 'AWAITING_READY', 'WAITING_REMOTE_REVIEW',
                'READY_TO_MERGE')
            AND successor.purpose = 'REMOTE_OBSERVATION'
            AND successor.returned_stage_version IS NULL
            AND EXISTS (
                SELECT 1 FROM remote_observation_operation operation
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                WHERE operation.id = successor.owner_id
                  AND operation.operation_id = successor.operation_id
                  AND operation.semantic_attempt = successor.semantic_attempt
                  AND operation.task_id = OLD.task_id
                  AND operation.task_epoch = OLD.task_epoch
                  AND operation.remote_development_stage_id = OLD.stage_id
                  AND operation.stage_generation = OLD.stage_generation
                  AND operation.expected_head_sha = OLD.head_sha
                  AND operation.expected_base_sha = OLD.base_sha
                  AND operation.status = 'DISPATCHED'
                  AND ticket.operation_id = successor.operation_id
                  AND ticket.async_family = 'REMOTE_OBSERVATION'
                  AND ticket.owner_kind = 'STAGE'
                  AND ticket.owner_id = OLD.stage_id
                  AND ticket.task_id = OLD.task_id
                  AND ticket.task_epoch = OLD.task_epoch
                  AND ticket.stage_id = OLD.stage_id
                  AND ticket.stage_generation = OLD.stage_generation
                  AND ticket.attempt = successor.semantic_attempt
                  AND ticket.expected_code_fingerprint IS NULL
                  AND ticket.expected_head_sha = OLD.head_sha
                  AND ticket.expected_base_sha = OLD.base_sha
                  AND ticket.status = 'REQUESTED'))
        OR (successor.owner_kind = 'TASK_TURN'
            AND OLD.stage_kind = 'PLAN'
            AND OLD.restore_checkpoint = 'DRAFTING'
            AND successor.purpose = 'PLAN_DRAFT'
            AND successor.returned_stage_version = owner.version
            AND EXISTS (
                SELECT 1 FROM task_turn turn
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                WHERE turn.id = successor.owner_id
                  AND turn.operation_id = successor.operation_id
                  AND turn.attempt = successor.semantic_attempt
                  AND turn.task_id = OLD.task_id
                  AND turn.task_epoch = OLD.task_epoch
                  AND turn.trigger_stage_id = OLD.stage_id
                  AND turn.trigger_stage_generation = OLD.stage_generation
                  AND turn.purpose = successor.purpose
                  AND turn.expected_code_fingerprint = OLD.code_fingerprint
                  AND turn.expected_head_sha = OLD.head_sha
                  AND turn.expected_base_sha = OLD.base_sha
                  AND turn.status = 'REQUESTED'
                  AND ticket.operation_id = successor.operation_id
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = successor.owner_id
                  AND ticket.task_id = OLD.task_id
                  AND ticket.task_epoch = OLD.task_epoch
                  AND ticket.stage_id = OLD.stage_id
                  AND ticket.stage_generation = OLD.stage_generation
                  AND ticket.attempt = successor.semantic_attempt
                  AND ticket.expected_code_fingerprint = OLD.code_fingerprint
                  AND ticket.expected_head_sha = OLD.head_sha
                  AND ticket.expected_base_sha = OLD.base_sha
                  AND ticket.status = 'REQUESTED'))
        OR (successor.owner_kind = 'STAGE_TURN'
            AND ((OLD.stage_kind = 'LOCAL_DEVELOPMENT'
                  AND (OLD.restore_checkpoint = 'IMPLEMENTING'
                       AND successor.purpose = 'IMPLEMENT_LOCAL_PLAN'
                    OR OLD.restore_checkpoint = 'ADDRESSING_BRAIN_FINDINGS'
                       AND successor.purpose = 'ADDRESS_BRAIN_FINDINGS'
                    OR OLD.restore_checkpoint = 'ADDRESSING_LOCAL_FEEDBACK'
                       AND successor.purpose = 'ADDRESS_LOCAL_FEEDBACK')
                  AND successor.returned_stage_version = owner.version)
              OR (OLD.stage_kind = 'REMOTE_DEVELOPMENT'
                  AND OLD.restore_checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
                  AND successor.purpose = 'ADDRESS_REMOTE_FEEDBACK'
                  AND successor.returned_stage_version IS NULL))
            AND EXISTS (
                SELECT 1 FROM stage_turn turn
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                WHERE turn.id = successor.owner_id
                  AND turn.operation_id = successor.operation_id
                  AND turn.attempt = successor.semantic_attempt
                  AND turn.stage_id = OLD.stage_id
                  AND turn.stage_generation = OLD.stage_generation
                  AND turn.task_epoch = OLD.task_epoch
                  AND turn.purpose = successor.purpose
                  AND turn.expected_code_fingerprint = OLD.code_fingerprint
                  AND turn.expected_head_sha = OLD.head_sha
                  AND turn.expected_base_sha = OLD.base_sha
                  AND turn.status = 'QUEUED'
                  AND ticket.operation_id = successor.operation_id
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.owner_kind = 'STAGE_TURN'
                  AND ticket.owner_id = successor.owner_id
                  AND ticket.task_id = OLD.task_id
                  AND ticket.task_epoch = OLD.task_epoch
                  AND ticket.stage_id = OLD.stage_id
                  AND ticket.stage_generation = OLD.stage_generation
                  AND ticket.attempt = successor.semantic_attempt
                  AND ticket.expected_code_fingerprint = OLD.code_fingerprint
                  AND ticket.expected_head_sha = OLD.head_sha
                  AND ticket.expected_base_sha = OLD.base_sha
                  AND ticket.status = 'REQUESTED'))))
BEGIN SELECT RAISE(ABORT, 'Resume materialization lacks its exact successor or passive wait'); END;
