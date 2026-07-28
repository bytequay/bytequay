-- Owner-command continuations for typed Turns suspended on a durable user
-- wait.  These rows are immutable admission receipts; the owner aggregate
-- remains the sole lifecycle writer.

-- V263 did not retain the provider call identity for grant consumption.  Add
-- it without rewriting V263: existing evidence receives a stable legacy
-- identity which cannot alias an ordinary provider call id.
DROP TRIGGER permission_grant_consumption_immutable;
DROP INDEX idx_permission_grant_consumption_request;
ALTER TABLE permission_grant_consumption
    RENAME TO permission_grant_consumption_v263;

CREATE TABLE permission_grant_consumption (
    id                  TEXT    NOT NULL PRIMARY KEY,
    permission_id       TEXT    NOT NULL REFERENCES permission_request(id) ON DELETE CASCADE,
    turn_kind           TEXT    NOT NULL CHECK (turn_kind IN (
        'THREAD', 'TASK', 'STAGE', 'REVIEW_ASSIGNMENT')),
    turn_id             TEXT    NOT NULL,
    operation_id        TEXT    NOT NULL,
    call_id             TEXT    NOT NULL,
    parameters_digest   TEXT    NOT NULL,
    remaining_after     INTEGER NOT NULL CHECK (remaining_after = -1 OR remaining_after >= 0),
    consumed_at_ms      INTEGER NOT NULL,
    UNIQUE (permission_id, turn_kind, turn_id, operation_id,
        call_id, parameters_digest)
);

INSERT INTO permission_grant_consumption(
    id, permission_id, turn_kind, turn_id, operation_id, call_id,
    parameters_digest, remaining_after, consumed_at_ms)
SELECT id, permission_id, turn_kind, turn_id, operation_id,
       '__legacy_v263_consumption__:' || id,
       parameters_digest, remaining_after, consumed_at_ms
FROM permission_grant_consumption_v263;

DROP TABLE permission_grant_consumption_v263;

CREATE INDEX idx_permission_grant_consumption_request
    ON permission_grant_consumption(permission_id, consumed_at_ms, id);

CREATE TRIGGER permission_grant_consumption_immutable
BEFORE UPDATE ON permission_grant_consumption
BEGIN SELECT RAISE(ABORT, 'permission consumption evidence is immutable'); END;

CREATE TRIGGER thread_question_identity_immutable
BEFORE UPDATE ON thread_question
WHEN NEW.id IS NOT OLD.id OR NEW.turn_id IS NOT OLD.turn_id
  OR NEW.call_id IS NOT OLD.call_id OR NEW.prompt IS NOT OLD.prompt
  OR NEW.context IS NOT OLD.context
  OR NEW.options_json IS NOT OLD.options_json
  OR NEW.allow_free_form IS NOT OLD.allow_free_form
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'thread question identity is immutable'); END;
CREATE TRIGGER task_question_identity_immutable
BEFORE UPDATE ON task_question
WHEN NEW.id IS NOT OLD.id OR NEW.turn_id IS NOT OLD.turn_id
  OR NEW.call_id IS NOT OLD.call_id OR NEW.prompt IS NOT OLD.prompt
  OR NEW.context IS NOT OLD.context
  OR NEW.options_json IS NOT OLD.options_json
  OR NEW.allow_free_form IS NOT OLD.allow_free_form
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'task question identity is immutable'); END;
CREATE TRIGGER stage_question_identity_immutable
BEFORE UPDATE ON stage_question
WHEN NEW.id IS NOT OLD.id OR NEW.turn_id IS NOT OLD.turn_id
  OR NEW.call_id IS NOT OLD.call_id OR NEW.prompt IS NOT OLD.prompt
  OR NEW.context IS NOT OLD.context
  OR NEW.options_json IS NOT OLD.options_json
  OR NEW.allow_free_form IS NOT OLD.allow_free_form
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'stage question identity is immutable'); END;
CREATE TRIGGER review_question_identity_immutable
BEFORE UPDATE ON review_assignment_question
WHEN NEW.id IS NOT OLD.id OR NEW.turn_id IS NOT OLD.turn_id
  OR NEW.call_id IS NOT OLD.call_id OR NEW.prompt IS NOT OLD.prompt
  OR NEW.context IS NOT OLD.context
  OR NEW.options_json IS NOT OLD.options_json
  OR NEW.allow_free_form IS NOT OLD.allow_free_form
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'review question identity is immutable'); END;

CREATE TRIGGER thread_question_open_invariant
BEFORE UPDATE ON thread_question
WHEN OLD.state = 'OPEN' AND NEW.state = 'OPEN'
 AND (NEW.answer IS NOT OLD.answer
   OR NEW.answer_revision IS NOT OLD.answer_revision
   OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
   OR NEW.answer_option_id IS NOT OLD.answer_option_id
   OR NEW.answer_free_form IS NOT OLD.answer_free_form
   OR NEW.answer_actor IS NOT OLD.answer_actor
   OR NEW.continuation_state IS NOT OLD.continuation_state
   OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
   OR NEW.continuation_error IS NOT OLD.continuation_error)
BEGIN SELECT RAISE(ABORT, 'open thread question is immutable'); END;
CREATE TRIGGER task_question_open_invariant
BEFORE UPDATE ON task_question
WHEN OLD.state = 'OPEN' AND NEW.state = 'OPEN'
 AND (NEW.answer IS NOT OLD.answer
   OR NEW.answer_revision IS NOT OLD.answer_revision
   OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
   OR NEW.answer_option_id IS NOT OLD.answer_option_id
   OR NEW.answer_free_form IS NOT OLD.answer_free_form
   OR NEW.answer_actor IS NOT OLD.answer_actor
   OR NEW.continuation_state IS NOT OLD.continuation_state
   OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
   OR NEW.continuation_error IS NOT OLD.continuation_error)
BEGIN SELECT RAISE(ABORT, 'open task question is immutable'); END;
CREATE TRIGGER stage_question_open_invariant
BEFORE UPDATE ON stage_question
WHEN OLD.state = 'OPEN' AND NEW.state = 'OPEN'
 AND (NEW.answer IS NOT OLD.answer
   OR NEW.answer_revision IS NOT OLD.answer_revision
   OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
   OR NEW.answer_option_id IS NOT OLD.answer_option_id
   OR NEW.answer_free_form IS NOT OLD.answer_free_form
   OR NEW.answer_actor IS NOT OLD.answer_actor
   OR NEW.continuation_state IS NOT OLD.continuation_state
   OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
   OR NEW.continuation_error IS NOT OLD.continuation_error)
BEGIN SELECT RAISE(ABORT, 'open stage question is immutable'); END;
CREATE TRIGGER review_question_open_invariant
BEFORE UPDATE ON review_assignment_question
WHEN OLD.state = 'OPEN' AND NEW.state = 'OPEN'
 AND (NEW.answer IS NOT OLD.answer
   OR NEW.answer_revision IS NOT OLD.answer_revision
   OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
   OR NEW.answer_option_id IS NOT OLD.answer_option_id
   OR NEW.answer_free_form IS NOT OLD.answer_free_form
   OR NEW.answer_actor IS NOT OLD.answer_actor
   OR NEW.continuation_state IS NOT OLD.continuation_state
   OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
   OR NEW.continuation_error IS NOT OLD.continuation_error)
BEGIN SELECT RAISE(ABORT, 'open review question is immutable'); END;

CREATE TRIGGER thread_question_continuation_scope
BEFORE UPDATE OF continuation_state, successor_turn_id ON thread_question
WHEN NEW.continuation_state = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1
    FROM thread_turn source
    JOIN thread_turn successor ON successor.id = NEW.successor_turn_id
    WHERE source.id = OLD.turn_id
      AND successor.id <> source.id
      AND successor.trunk_id = source.trunk_id)
BEGIN SELECT RAISE(ABORT, 'thread question successor scope is stale'); END;

CREATE TRIGGER task_question_continuation_scope
BEFORE UPDATE OF continuation_state, successor_turn_id ON task_question
WHEN NEW.continuation_state = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1
    FROM task_turn source
    JOIN task_turn successor ON successor.id = NEW.successor_turn_id
    WHERE source.id = OLD.turn_id
      AND successor.id <> source.id
      AND successor.task_id = source.task_id
      AND successor.task_epoch = source.task_epoch
      AND successor.trigger_stage_id IS source.trigger_stage_id
      AND successor.trigger_stage_generation IS source.trigger_stage_generation
      AND successor.expected_code_fingerprint IS source.expected_code_fingerprint
      AND successor.expected_head_sha IS source.expected_head_sha
      AND successor.expected_base_sha IS source.expected_base_sha)
BEGIN SELECT RAISE(ABORT, 'task question successor fence is stale'); END;

CREATE TRIGGER stage_question_continuation_scope
BEFORE UPDATE OF continuation_state, successor_turn_id ON stage_question
WHEN NEW.continuation_state = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1
    FROM stage_turn source
    JOIN stage_turn successor ON successor.id = NEW.successor_turn_id
    WHERE source.id = OLD.turn_id
      AND successor.id <> source.id
      AND successor.stage_id = source.stage_id
      AND successor.stage_generation = source.stage_generation
      AND successor.task_epoch = source.task_epoch
      AND successor.expected_code_fingerprint IS source.expected_code_fingerprint
      AND successor.expected_head_sha IS source.expected_head_sha
      AND successor.expected_base_sha IS source.expected_base_sha)
BEGIN SELECT RAISE(ABORT, 'stage question successor fence is stale'); END;

CREATE TRIGGER review_question_continuation_scope
BEFORE UPDATE OF continuation_state, successor_turn_id
ON review_assignment_question
WHEN NEW.continuation_state = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1
    FROM review_assignment_turn source
    JOIN review_assignment_turn successor
      ON successor.id = NEW.successor_turn_id
    WHERE source.id = OLD.turn_id
      AND successor.id <> source.id
      AND successor.assignment_id = source.assignment_id
      AND successor.start_commit = source.start_commit)
BEGIN SELECT RAISE(ABORT, 'review question successor fence is stale'); END;

CREATE TRIGGER permission_request_continuation_scope
BEFORE UPDATE OF continuation_state, successor_turn_id ON permission_request
WHEN NEW.continuation_state = 'DISPATCHED' AND NOT (
    (OLD.turn_kind = 'THREAD' AND EXISTS (
        SELECT 1
        FROM thread_turn source
        JOIN thread_turn successor ON successor.id = NEW.successor_turn_id
        WHERE source.id = OLD.turn_id
          AND successor.id <> source.id
          AND successor.trunk_id = source.trunk_id))
    OR (OLD.turn_kind = 'TASK' AND EXISTS (
        SELECT 1
        FROM task_turn source
        JOIN task_turn successor ON successor.id = NEW.successor_turn_id
        WHERE source.id = OLD.turn_id
          AND successor.id <> source.id
          AND successor.task_id = source.task_id
          AND successor.task_epoch = source.task_epoch
          AND successor.trigger_stage_id IS source.trigger_stage_id
          AND successor.trigger_stage_generation IS source.trigger_stage_generation
          AND successor.expected_code_fingerprint IS source.expected_code_fingerprint
          AND successor.expected_head_sha IS source.expected_head_sha
          AND successor.expected_base_sha IS source.expected_base_sha))
    OR (OLD.turn_kind = 'STAGE' AND EXISTS (
        SELECT 1
        FROM stage_turn source
        JOIN stage_turn successor ON successor.id = NEW.successor_turn_id
        WHERE source.id = OLD.turn_id
          AND successor.id <> source.id
          AND successor.stage_id = source.stage_id
          AND successor.stage_generation = source.stage_generation
          AND successor.task_epoch = source.task_epoch
          AND successor.expected_code_fingerprint IS source.expected_code_fingerprint
          AND successor.expected_head_sha IS source.expected_head_sha
          AND successor.expected_base_sha IS source.expected_base_sha))
    OR (OLD.turn_kind = 'REVIEW_ASSIGNMENT' AND EXISTS (
        SELECT 1
        FROM review_assignment_turn source
        JOIN review_assignment_turn successor
          ON successor.id = NEW.successor_turn_id
        WHERE source.id = OLD.turn_id
          AND successor.id <> source.id
          AND successor.assignment_id = source.assignment_id
          AND successor.start_commit = source.start_commit)))
BEGIN SELECT RAISE(ABORT, 'permission successor fence is stale'); END;

CREATE TRIGGER permission_request_id_immutable_v265
BEFORE UPDATE OF id ON permission_request
WHEN NEW.id IS NOT OLD.id
BEGIN SELECT RAISE(ABORT, 'permission request id is immutable'); END;

CREATE TRIGGER permission_request_open_invariant
BEFORE UPDATE ON permission_request
WHEN OLD.state = 'OPEN' AND NEW.state = 'OPEN'
 AND (NEW.answer IS NOT OLD.answer
   OR NEW.answer_revision IS NOT OLD.answer_revision
   OR NEW.answered_at_ms IS NOT OLD.answered_at_ms
   OR NEW.grant_scope_kind IS NOT OLD.grant_scope_kind
   OR NEW.grant_scope_id IS NOT OLD.grant_scope_id
   OR NEW.granted_uses IS NOT OLD.granted_uses
   OR NEW.remaining_uses IS NOT OLD.remaining_uses
   OR NEW.consumed_uses IS NOT OLD.consumed_uses
   OR NEW.answer_actor IS NOT OLD.answer_actor
   OR NEW.last_consumed_at_ms IS NOT OLD.last_consumed_at_ms
   OR NEW.continuation_state IS NOT OLD.continuation_state
   OR NEW.successor_turn_id IS NOT OLD.successor_turn_id
   OR NEW.continuation_error IS NOT OLD.continuation_error)
BEGIN SELECT RAISE(ABORT, 'open permission request is immutable'); END;

CREATE TABLE plan_turn_user_wait_continuation_v265 (
    wait_kind                    TEXT    NOT NULL CHECK (wait_kind IN (
        'QUESTION', 'PERMISSION')),
    wait_id                      TEXT    NOT NULL,
    command_id                   TEXT    NOT NULL UNIQUE,
    task_id                      TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                   INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_id                     TEXT    NOT NULL REFERENCES plan_stage(stage_id) ON DELETE CASCADE,
    stage_generation             INTEGER NOT NULL CHECK (stage_generation > 0),
    predecessor_turn_id          TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    predecessor_operation_id     TEXT    NOT NULL UNIQUE,
    successor_turn_id            TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    successor_operation_id       TEXT    NOT NULL UNIQUE,
    expected_stage_version       INTEGER NOT NULL CHECK (expected_stage_version >= 0),
    returned_stage_version       INTEGER NOT NULL,
    checkpoint                   TEXT    NOT NULL CHECK (checkpoint IN (
        'DRAFTING', 'SELF_REVIEW')),
    admitted_at_ms               INTEGER NOT NULL,
    PRIMARY KEY (wait_kind, wait_id),
    CHECK (returned_stage_version = expected_stage_version + 1)
);

CREATE TRIGGER plan_turn_user_wait_continuation_exact_v265
BEFORE INSERT ON plan_turn_user_wait_continuation_v265
WHEN NOT EXISTS (
    SELECT 1
    FROM task_turn predecessor
    JOIN task_turn successor ON successor.id = NEW.successor_turn_id
    JOIN typed_user_wait_result result
      ON result.operation_id = predecessor.operation_id
    JOIN stage owner ON owner.id = predecessor.trigger_stage_id
    JOIN tasks task ON task.id = predecessor.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    WHERE predecessor.id = NEW.predecessor_turn_id
      AND predecessor.operation_id = NEW.predecessor_operation_id
      AND predecessor.status = 'SUCCEEDED'
      AND result.owner_kind = 'TASK_TURN'
      AND result.turn_id = predecessor.id
      AND result.wait_kind = NEW.wait_kind AND result.wait_id = NEW.wait_id
      AND successor.id <> predecessor.id
      AND successor.operation_id = NEW.successor_operation_id
      AND successor.task_id = predecessor.task_id
      AND successor.task_epoch = predecessor.task_epoch
      AND successor.trigger_stage_id = predecessor.trigger_stage_id
      AND successor.trigger_stage_generation = predecessor.trigger_stage_generation
      AND successor.purpose = predecessor.purpose
      AND successor.expected_code_fingerprint IS predecessor.expected_code_fingerprint
      AND successor.expected_head_sha IS predecessor.expected_head_sha
      AND successor.expected_base_sha IS predecessor.expected_base_sha
      AND successor.status = 'REQUESTED'
      AND task.id = NEW.task_id AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE' AND task.epoch = NEW.task_epoch
      AND owner.id = NEW.stage_id
      AND owner.generation = NEW.stage_generation
      AND owner.version = NEW.returned_stage_version
      AND owner.checkpoint = NEW.checkpoint
      AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
      AND current.stage_id = owner.id
      AND current.stage_generation = owner.generation
      AND ((NEW.wait_kind = 'QUESTION' AND EXISTS (
            SELECT 1 FROM task_question question
            WHERE question.id = NEW.wait_id
              AND question.turn_id = predecessor.id
              AND question.state = 'ANSWERED'
              AND question.continuation_state = 'READY'))
        OR (NEW.wait_kind = 'PERMISSION' AND EXISTS (
            SELECT 1 FROM permission_request permission
            WHERE permission.id = NEW.wait_id
              AND permission.turn_kind = 'TASK'
              AND permission.turn_id = predecessor.id
              AND permission.operation_id = predecessor.operation_id
              AND permission.state <> 'OPEN'
              AND permission.continuation_state = 'READY'))))
BEGIN SELECT RAISE(ABORT, 'Plan user-wait continuation is not exact'); END;

CREATE TRIGGER plan_turn_user_wait_continuation_immutable_v265
BEFORE UPDATE ON plan_turn_user_wait_continuation_v265
BEGIN SELECT RAISE(ABORT, 'Plan user-wait continuation is immutable'); END;

-- Self-review has one logical verdict but may suspend more than once.  Keep
-- those executions outside the old two-attempt infrastructure-retry table.
CREATE TABLE plan_self_review_user_wait_attempt_v265 (
    self_review_id              TEXT    NOT NULL REFERENCES plan_self_review(id) ON DELETE CASCADE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 1),
    task_turn_id                TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    operation_id                TEXT    NOT NULL UNIQUE,
    predecessor_turn_id         TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    wait_kind                   TEXT    NOT NULL,
    wait_id                     TEXT    NOT NULL,
    requested_at_ms             INTEGER NOT NULL,
    PRIMARY KEY (self_review_id, semantic_attempt),
    UNIQUE (wait_kind, wait_id)
);

CREATE TRIGGER plan_self_review_user_wait_attempt_exact_v265
BEFORE INSERT ON plan_self_review_user_wait_attempt_v265
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review review
    JOIN task_turn predecessor ON predecessor.id = NEW.predecessor_turn_id
    JOIN task_turn successor ON successor.id = NEW.task_turn_id
    JOIN typed_user_wait_result result
      ON result.operation_id = predecessor.operation_id
    WHERE review.id = NEW.self_review_id AND review.status = 'REQUESTED'
      AND predecessor.purpose = 'PLAN_SELF_REVIEW'
      AND predecessor.status = 'SUCCEEDED'
      AND successor.purpose = 'PLAN_SELF_REVIEW'
      AND successor.status = 'REQUESTED'
      AND successor.operation_id = NEW.operation_id
      AND successor.task_id = predecessor.task_id
      AND successor.task_epoch = predecessor.task_epoch
      AND successor.trigger_stage_id = predecessor.trigger_stage_id
      AND successor.trigger_stage_generation = predecessor.trigger_stage_generation
      AND successor.expected_code_fingerprint IS predecessor.expected_code_fingerprint
      AND successor.expected_head_sha IS predecessor.expected_head_sha
      AND successor.expected_base_sha IS predecessor.expected_base_sha
      AND result.owner_kind = 'TASK_TURN'
      AND result.turn_id = predecessor.id
      AND result.wait_kind = NEW.wait_kind AND result.wait_id = NEW.wait_id
      AND NEW.semantic_attempt = 1 + (
          SELECT COUNT(*) FROM (
              SELECT attempt FROM plan_self_review_attempt infrastructure
              WHERE infrastructure.self_review_id = review.id
              UNION ALL
              SELECT semantic_attempt
              FROM plan_self_review_user_wait_attempt_v265 prior
              WHERE prior.self_review_id = review.id)))
BEGIN SELECT RAISE(ABORT, 'Plan self-review user-wait attempt is not exact'); END;

CREATE TRIGGER plan_self_review_user_wait_attempt_immutable_v265
BEFORE UPDATE ON plan_self_review_user_wait_attempt_v265
BEGIN SELECT RAISE(ABORT, 'Plan self-review user-wait attempt is immutable'); END;

CREATE VIEW plan_self_review_all_attempt_v265 AS
SELECT self_review_id, attempt AS semantic_attempt, task_turn_id,
       operation_id, predecessor_turn_id
FROM plan_self_review_attempt
UNION ALL
SELECT self_review_id, semantic_attempt, task_turn_id,
       operation_id, predecessor_turn_id
FROM plan_self_review_user_wait_attempt_v265;

DROP TRIGGER plan_review_submission_insert;
CREATE TRIGGER plan_review_submission_insert
BEFORE INSERT ON plan_review_submission
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review review
    JOIN plan_self_review_all_attempt_v265 attempt
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
      AND attempt.semantic_attempt = (
          SELECT MAX(latest.semantic_attempt)
          FROM plan_self_review_all_attempt_v265 latest
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
      JOIN plan_self_review_all_attempt_v265 attempt
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
        AND attempt.semantic_attempt = (
            SELECT MAX(latest.semantic_attempt)
            FROM plan_self_review_all_attempt_v265 latest
            WHERE latest.self_review_id = NEW.id)
        AND s.completed_at_ms IS NULL
        AND NOT EXISTS (
            SELECT 1 FROM plan_revision newer
            WHERE newer.plan_stage_id = revision.plan_stage_id
              AND newer.revision > revision.revision))
BEGIN SELECT RAISE(ABORT, 'Plan self-review result is stale'); END;

CREATE TABLE stage_turn_user_wait_continuation_v265 (
    request_id                   TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage_steering_request_v257(id) ON DELETE CASCADE,
    wait_kind                    TEXT    NOT NULL CHECK (wait_kind IN (
        'QUESTION', 'PERMISSION')),
    wait_id                      TEXT    NOT NULL,
    predecessor_turn_id          TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    predecessor_operation_id     TEXT    NOT NULL UNIQUE,
    successor_turn_id            TEXT    UNIQUE REFERENCES stage_turn(id),
    successor_operation_id       TEXT    UNIQUE,
    admitted_at_ms               INTEGER,
    UNIQUE (wait_kind, wait_id),
    CHECK ((successor_turn_id IS NULL) = (successor_operation_id IS NULL)),
    CHECK ((successor_turn_id IS NULL) = (admitted_at_ms IS NULL))
);

CREATE TRIGGER stage_turn_user_wait_continuation_insert_v265
BEFORE INSERT ON stage_turn_user_wait_continuation_v265
WHEN NOT EXISTS (
    SELECT 1
    FROM stage_steering_request_v257 request
    JOIN stage_turn predecessor ON predecessor.id = NEW.predecessor_turn_id
    JOIN typed_user_wait_result result
      ON result.operation_id = predecessor.operation_id
    WHERE request.id = NEW.request_id AND request.status = 'PENDING'
      AND request.mode = 'CANCEL_AND_REPLACE'
      AND request.predecessor_owner_kind = 'STAGE_TURN'
      AND request.predecessor_owner_id = predecessor.id
      AND request.predecessor_operation_id = predecessor.operation_id
      AND predecessor.operation_id = NEW.predecessor_operation_id
      AND predecessor.status = 'SUCCEEDED'
      AND result.owner_kind = 'STAGE_TURN'
      AND result.turn_id = predecessor.id
      AND result.wait_kind = NEW.wait_kind AND result.wait_id = NEW.wait_id
      AND ((NEW.wait_kind = 'QUESTION' AND EXISTS (
            SELECT 1 FROM stage_question question
            WHERE question.id = NEW.wait_id
              AND question.turn_id = predecessor.id
              AND question.state = 'ANSWERED'
              AND question.continuation_state = 'READY'))
        OR (NEW.wait_kind = 'PERMISSION' AND EXISTS (
            SELECT 1 FROM permission_request permission
            WHERE permission.id = NEW.wait_id
              AND permission.turn_kind = 'STAGE'
              AND permission.turn_id = predecessor.id
              AND permission.operation_id = predecessor.operation_id
              AND permission.state <> 'OPEN'
              AND permission.continuation_state = 'READY'))))
BEGIN SELECT RAISE(ABORT, 'Stage user-wait continuation is not exact'); END;

CREATE TRIGGER stage_turn_user_wait_continuation_update_v265
BEFORE UPDATE OF successor_turn_id, successor_operation_id, admitted_at_ms
ON stage_turn_user_wait_continuation_v265
WHEN OLD.successor_turn_id IS NOT NULL OR NOT EXISTS (
    SELECT 1
    FROM stage_turn predecessor
    JOIN stage_turn successor ON successor.id = NEW.successor_turn_id
    WHERE predecessor.id = OLD.predecessor_turn_id
      AND successor.operation_id = NEW.successor_operation_id
      AND successor.id <> predecessor.id
      AND successor.stage_id = predecessor.stage_id
      AND successor.stage_generation = predecessor.stage_generation
      AND successor.task_epoch = predecessor.task_epoch
      AND successor.purpose = predecessor.purpose
      AND successor.status = 'QUEUED'
      AND successor.attempt = predecessor.attempt + 1
      AND successor.expected_code_fingerprint IS predecessor.expected_code_fingerprint
      AND successor.expected_head_sha IS predecessor.expected_head_sha
      AND successor.expected_base_sha IS predecessor.expected_base_sha)
BEGIN SELECT RAISE(ABORT, 'Stage user-wait successor is not exact'); END;

CREATE TRIGGER stage_turn_user_wait_continuation_identity_v265
BEFORE UPDATE OF request_id, wait_kind, wait_id, predecessor_turn_id,
        predecessor_operation_id ON stage_turn_user_wait_continuation_v265
BEGIN SELECT RAISE(ABORT, 'Stage user-wait continuation identity is immutable'); END;

CREATE TABLE review_turn_user_wait_continuation_v265 (
    wait_kind                    TEXT    NOT NULL CHECK (wait_kind IN (
        'QUESTION', 'PERMISSION')),
    wait_id                      TEXT    NOT NULL,
    predecessor_turn_id          TEXT    NOT NULL UNIQUE REFERENCES review_assignment_turn(id),
    predecessor_operation_id     TEXT    NOT NULL UNIQUE,
    successor_turn_id            TEXT    NOT NULL UNIQUE REFERENCES review_assignment_turn(id),
    successor_operation_id       TEXT    NOT NULL UNIQUE,
    admitted_at_ms               INTEGER NOT NULL,
    PRIMARY KEY (wait_kind, wait_id)
);

CREATE TRIGGER review_turn_user_wait_continuation_exact_v265
BEFORE INSERT ON review_turn_user_wait_continuation_v265
WHEN NOT EXISTS (
    SELECT 1
    FROM review_assignment_turn predecessor
    JOIN review_assignment_turn successor
      ON successor.id = NEW.successor_turn_id
    JOIN typed_user_wait_result result
      ON result.operation_id = predecessor.operation_id
    WHERE predecessor.id = NEW.predecessor_turn_id
      AND predecessor.operation_id = NEW.predecessor_operation_id
      AND predecessor.status = 'SUCCEEDED'
      AND successor.operation_id = NEW.successor_operation_id
      AND successor.id <> predecessor.id
      AND successor.assignment_id = predecessor.assignment_id
      AND successor.purpose = predecessor.purpose
      AND successor.subject_key = predecessor.subject_key
      AND successor.verifier_run_id IS predecessor.verifier_run_id
      AND successor.attempt = predecessor.attempt + 1
      AND successor.start_commit = predecessor.start_commit
      AND successor.status = 'REQUESTED'
      AND result.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
      AND result.turn_id = predecessor.id
      AND result.wait_kind = NEW.wait_kind AND result.wait_id = NEW.wait_id
      AND ((NEW.wait_kind = 'QUESTION' AND EXISTS (
            SELECT 1 FROM review_assignment_question question
            WHERE question.id = NEW.wait_id
              AND question.turn_id = predecessor.id
              AND question.state = 'ANSWERED'
              AND question.continuation_state = 'READY'))
        OR (NEW.wait_kind = 'PERMISSION' AND EXISTS (
            SELECT 1 FROM permission_request permission
            WHERE permission.id = NEW.wait_id
              AND permission.turn_kind = 'REVIEW_ASSIGNMENT'
              AND permission.turn_id = predecessor.id
              AND permission.operation_id = predecessor.operation_id
              AND permission.state <> 'OPEN'
              AND permission.continuation_state = 'READY'))))
BEGIN SELECT RAISE(ABORT, 'Review user-wait continuation is not exact'); END;

CREATE TRIGGER review_turn_user_wait_continuation_immutable_v265
BEFORE UPDATE ON review_turn_user_wait_continuation_v265
BEGIN SELECT RAISE(ABORT, 'Review user-wait continuation is immutable'); END;

-- Existing steering was admitted only while its predecessor was live.  A
-- user-wait predecessor is already terminal by definition, but its exact
-- typed result receipt is equivalent quiescence evidence.
DROP TRIGGER stage_steering_request_insert_v257;
CREATE TRIGGER stage_steering_request_insert_v257
BEFORE INSERT ON stage_steering_request_v257
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'PENDING'
            THEN RAISE(ABORT, 'Stage steering must start pending')
        WHEN NOT EXISTS (
            SELECT 1 FROM stage owner
            JOIN tasks task ON task.id = owner.task_id
            JOIN task_current_stage current ON current.task_id = task.id
            WHERE owner.id = NEW.stage_id AND owner.task_id = NEW.task_id
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
              AND ticket.expected_code_fingerprint IS NEW.predecessor_code_fingerprint
              AND ticket.expected_head_sha IS NEW.predecessor_head_sha
              AND ticket.expected_base_sha IS NEW.predecessor_base_sha
              AND (ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')
                OR (ticket.status = 'SUCCEEDED' AND EXISTS (
                    SELECT 1 FROM typed_user_wait_result result
                    WHERE result.operation_id = ticket.operation_id
                      AND result.owner_kind = 'STAGE_TURN'
                      AND result.turn_id = ticket.owner_id)))
              AND ticket.owner_kind = 'STAGE_TURN'
              AND EXISTS (SELECT 1 FROM stage_turn turn
                  WHERE turn.id = ticket.owner_id
                    AND turn.operation_id = ticket.operation_id
                    AND turn.purpose = NEW.predecessor_purpose))
            THEN RAISE(ABORT, 'Stage steering predecessor fence is stale')
    END;
END;

DROP TRIGGER local_stage_turn_request_insert;
CREATE TRIGGER local_stage_turn_request_insert
BEFORE INSERT ON local_stage_turn_request
BEGIN
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
                OR (NEW.kind = 'STEERING' AND turn.purpose = 'USER_STEERING')))
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
    END;
END;

-- Preserve the repair, steering, and resume admission paths from V257 while
-- admitting an answered user wait only through its exact typed receipt and
-- immutable continuation link.
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
              AND turn.expected_code_fingerprint = previous_repair.code_fingerprint
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
              AND NOT EXISTS (
                  SELECT 1 FROM stage_turn_user_wait_continuation_v265 wait
                  WHERE wait.request_id = steering.id)
              AND previous_turn.status IN (
                  'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
              AND turn.expected_head_sha = previous_turn.expected_head_sha
              AND turn.expected_code_fingerprint =
                  previous_turn.expected_code_fingerprint))
        OR (NEW.semantic_attempt > 1 AND EXISTS (
            SELECT 1 FROM remote_feedback_stage_turn_request previous
            JOIN stage_turn previous_turn
              ON previous_turn.id = previous.stage_turn_id
            JOIN stage_steering_request_v257 steering
              ON steering.predecessor_owner_id = previous.stage_turn_id
             AND steering.predecessor_operation_id = previous_turn.operation_id
            JOIN remote_stage_steering_handoff_v257 handoff
              ON handoff.request_id = steering.id
            JOIN stage_turn_user_wait_continuation_v265 continuation
              ON continuation.request_id = steering.id
             AND continuation.predecessor_turn_id = previous_turn.id
             AND continuation.predecessor_operation_id = previous_turn.operation_id
            JOIN typed_user_wait_result result
              ON result.operation_id = previous_turn.operation_id
             AND result.owner_kind = 'STAGE_TURN'
             AND result.turn_id = previous_turn.id
             AND result.wait_kind = continuation.wait_kind
             AND result.wait_id = continuation.wait_id
            WHERE previous.remote_feedback_batch_id = batch.id
              AND previous.task_id = NEW.task_id
              AND previous.task_epoch = NEW.task_epoch
              AND previous.remote_development_stage_id =
                  NEW.remote_development_stage_id
              AND previous.stage_generation = NEW.stage_generation
              AND previous.semantic_attempt = NEW.semantic_attempt - 1
              AND previous.stage_turn_id = NEW.predecessor_turn_id
              AND previous_turn.stage_id = NEW.remote_development_stage_id
              AND previous_turn.stage_generation = NEW.stage_generation
              AND previous_turn.task_epoch = NEW.task_epoch
              AND previous_turn.attempt = previous.semantic_attempt
              AND previous_turn.purpose = 'ADDRESS_REMOTE_FEEDBACK'
              AND previous_turn.status = 'SUCCEEDED'
              AND steering.status = 'PENDING'
              AND steering.mode = 'CANCEL_AND_REPLACE'
              AND steering.task_id = NEW.task_id
              AND steering.task_epoch = NEW.task_epoch
              AND steering.stage_id = NEW.remote_development_stage_id
              AND steering.stage_generation = NEW.stage_generation
              AND steering.predecessor_owner_kind = 'STAGE_TURN'
              AND steering.predecessor_owner_id = previous_turn.id
              AND steering.predecessor_operation_id = previous_turn.operation_id
              AND steering.predecessor_purpose = 'ADDRESS_REMOTE_FEEDBACK'
              AND handoff.owner_family = 'REMOTE_FEEDBACK'
              AND handoff.owner_purpose = 'ADDRESS_REMOTE_FEEDBACK'
              AND handoff.predecessor_turn_id = previous_turn.id
              AND handoff.predecessor_operation_id = previous_turn.operation_id
              AND handoff.status = 'PARKED'
              AND continuation.successor_turn_id IS NULL
              AND turn.attempt = previous_turn.attempt + 1
              AND turn.expected_code_fingerprint IS
                  previous_turn.expected_code_fingerprint
              AND turn.expected_head_sha IS previous_turn.expected_head_sha
              AND turn.expected_base_sha IS previous_turn.expected_base_sha
              AND ((continuation.wait_kind = 'QUESTION' AND EXISTS (
                    SELECT 1 FROM stage_question question
                    WHERE question.id = continuation.wait_id
                      AND question.turn_id = previous_turn.id
                      AND question.state = 'ANSWERED'
                      AND question.continuation_state = 'READY'))
                OR (continuation.wait_kind = 'PERMISSION' AND EXISTS (
                    SELECT 1 FROM permission_request permission
                    WHERE permission.id = continuation.wait_id
                      AND permission.turn_kind = 'STAGE'
                      AND permission.turn_id = previous_turn.id
                      AND permission.operation_id = previous_turn.operation_id
                      AND permission.state <> 'OPEN'
                      AND permission.continuation_state = 'READY')))))
        OR (NEW.semantic_attempt > 1 AND EXISTS (
            SELECT 1 FROM remote_feedback_stage_turn_request previous
            JOIN stage_turn previous_turn
              ON previous_turn.id = previous.stage_turn_id
            JOIN stage_resume_rearm_successor_v257 successor
              ON successor.owner_id = NEW.stage_turn_id
             AND successor.operation_id = turn.operation_id
             AND successor.semantic_attempt = NEW.semantic_attempt
            JOIN stage_resume_rearm_intent_v257 intent
              ON intent.handoff_id = successor.handoff_id
            WHERE previous.remote_feedback_batch_id = batch.id
              AND previous.semantic_attempt = NEW.semantic_attempt - 1
              AND previous.stage_turn_id = NEW.predecessor_turn_id
              AND previous_turn.status = 'CANCELED'
              AND successor.status = 'PREPARED'
              AND successor.owner_kind = 'STAGE_TURN'
              AND successor.purpose = 'ADDRESS_REMOTE_FEEDBACK'
              AND intent.status = 'PENDING'
              AND intent.task_id = NEW.task_id
              AND intent.stage_id = NEW.remote_development_stage_id
              AND intent.stage_generation = NEW.stage_generation
              AND intent.restore_checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
              AND turn.expected_head_sha = previous_turn.expected_head_sha
              AND turn.expected_code_fingerprint =
                  previous_turn.expected_code_fingerprint))))
BEGIN SELECT RAISE(ABORT, 'Remote feedback StageTurn lacks its exact batch and owner'); END;
