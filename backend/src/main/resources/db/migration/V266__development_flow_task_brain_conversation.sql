-- Typed Task Brain conversation and Task-owned USER_WAIT continuation.
-- Conversation is an ordinary read-only TaskTurn; development/remote Brain
-- continuations retain the original logical TaskManager result fence.

CREATE TABLE task_brain_conversation_result_v266 (
    task_turn_id         TEXT    NOT NULL PRIMARY KEY REFERENCES task_turn(id),
    operation_id         TEXT    NOT NULL UNIQUE,
    raw_outcome          TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest    TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance           TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    terminal_status      TEXT    NOT NULL CHECK (terminal_status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    assistant_message_id TEXT    UNIQUE REFERENCES task_message(id),
    evidence             TEXT    NOT NULL,
    recorded_at_ms       INTEGER NOT NULL,
    CHECK ((terminal_status = 'SUCCEEDED') =
        (assistant_message_id IS NOT NULL)),
    CHECK ((acceptance = 'SUPERSEDED') =
        (terminal_status = 'SUPERSEDED'))
);

CREATE TRIGGER task_brain_conversation_result_insert_v266
BEFORE INSERT ON task_brain_conversation_result_v266
WHEN NOT EXISTS (
    SELECT 1 FROM task_turn turn
    WHERE turn.id = NEW.task_turn_id
      AND turn.operation_id = NEW.operation_id
      AND turn.purpose = 'TASK_BRAIN_CONVERSATION'
      AND turn.status = NEW.terminal_status
      AND (NEW.assistant_message_id IS NULL OR EXISTS (
          SELECT 1 FROM task_message message
          WHERE message.id = NEW.assistant_message_id
            AND message.turn_id = turn.id
            AND message.role = 'ASSISTANT'
            AND length(trim(message.body)) > 0)))
BEGIN SELECT RAISE(ABORT, 'Task Brain conversation result is not exact'); END;

CREATE TRIGGER task_brain_conversation_result_immutable_v266
BEFORE UPDATE ON task_brain_conversation_result_v266
BEGIN SELECT RAISE(ABORT, 'Task Brain conversation result is immutable'); END;

CREATE TABLE task_turn_user_wait_continuation_v266 (
    wait_kind                    TEXT    NOT NULL CHECK (wait_kind IN (
        'QUESTION', 'PERMISSION')),
    wait_id                      TEXT    NOT NULL,
    source_turn_id               TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    source_operation_id          TEXT    NOT NULL UNIQUE,
    logical_turn_id              TEXT    NOT NULL REFERENCES task_turn(id),
    logical_operation_id         TEXT    NOT NULL,
    successor_turn_id            TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    successor_operation_id       TEXT    NOT NULL UNIQUE,
    purpose                      TEXT    NOT NULL CHECK (purpose IN (
        'TASK_BRAIN_CONVERSATION', 'DEVELOPMENT_BRAIN_REVIEW',
        'REMOTE_FEEDBACK_BRAIN_REVIEW')),
    task_id                      TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                   INTEGER NOT NULL CHECK (task_epoch > 0),
    trigger_stage_id             TEXT    REFERENCES stage(id),
    trigger_stage_generation     INTEGER,
    expected_code_fingerprint    TEXT,
    expected_head_sha            TEXT,
    expected_base_sha            TEXT,
    requested_at_ms              INTEGER NOT NULL,
    PRIMARY KEY (wait_kind, wait_id),
    CHECK ((trigger_stage_id IS NULL) =
        (trigger_stage_generation IS NULL))
);

CREATE TRIGGER task_turn_user_wait_continuation_exact_v266
BEFORE INSERT ON task_turn_user_wait_continuation_v266
WHEN NOT EXISTS (
    SELECT 1
    FROM task_turn source
    JOIN typed_user_wait_result result
      ON result.operation_id = source.operation_id
    JOIN task_turn logical ON logical.id = NEW.logical_turn_id
    JOIN task_turn successor ON successor.id = NEW.successor_turn_id
    JOIN tasks task ON task.id = source.task_id
    LEFT JOIN task_turn_user_wait_continuation_v266 prior
      ON prior.successor_turn_id = source.id
    WHERE source.id = NEW.source_turn_id
      AND source.operation_id = NEW.source_operation_id
      AND source.status = 'SUCCEEDED'
      AND source.purpose = NEW.purpose
      AND result.owner_kind = 'TASK_TURN'
      AND result.turn_id = source.id
      AND result.wait_kind = NEW.wait_kind
      AND result.wait_id = NEW.wait_id
      AND logical.id = COALESCE(prior.logical_turn_id, source.id)
      AND logical.operation_id = NEW.logical_operation_id
      AND logical.purpose = source.purpose
      AND successor.id <> source.id
      AND successor.operation_id = NEW.successor_operation_id
      AND successor.status = 'REQUESTED'
      AND successor.purpose = source.purpose
      AND successor.task_id = source.task_id
      AND successor.task_epoch = source.task_epoch
      AND successor.attempt = source.attempt + 1
      AND successor.trigger_stage_id IS source.trigger_stage_id
      AND successor.trigger_stage_generation IS
          source.trigger_stage_generation
      AND successor.expected_code_fingerprint IS
          source.expected_code_fingerprint
      AND successor.expected_head_sha IS source.expected_head_sha
      AND successor.expected_base_sha IS source.expected_base_sha
      AND NEW.task_id = source.task_id
      AND NEW.task_epoch = source.task_epoch
      AND NEW.trigger_stage_id IS source.trigger_stage_id
      AND NEW.trigger_stage_generation IS source.trigger_stage_generation
      AND NEW.expected_code_fingerprint IS
          source.expected_code_fingerprint
      AND NEW.expected_head_sha IS source.expected_head_sha
      AND NEW.expected_base_sha IS source.expected_base_sha
      AND task.workflow_version = 'V2'
      AND task.epoch = source.task_epoch
      AND ((source.purpose = 'TASK_BRAIN_CONVERSATION'
            AND source.trigger_stage_id IS NULL
            AND task.lifecycle_state IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
        OR (source.trigger_stage_id IS NOT NULL
            AND task.lifecycle_state = 'ACTIVE'
            AND EXISTS (
                SELECT 1 FROM task_current_stage current
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                WHERE current.task_id = task.id
                  AND current.stage_id = source.trigger_stage_id
                  AND current.stage_generation =
                      source.trigger_stage_generation
                  AND owner.completed_at_ms IS NULL
                  AND code.code_fingerprint IS
                      source.expected_code_fingerprint
                  AND code.head_sha IS source.expected_head_sha
                  AND code.base_sha IS source.expected_base_sha)))
      AND ((NEW.wait_kind = 'QUESTION' AND EXISTS (
            SELECT 1 FROM task_question question
            WHERE question.id = NEW.wait_id
              AND question.turn_id = source.id
              AND question.state = 'ANSWERED'
              AND question.continuation_state = 'READY'))
        OR (NEW.wait_kind = 'PERMISSION' AND EXISTS (
            SELECT 1 FROM permission_request permission
            WHERE permission.id = NEW.wait_id
              AND permission.turn_kind = 'TASK'
              AND permission.turn_id = source.id
              AND permission.operation_id = source.operation_id
              AND permission.state <> 'OPEN'
              AND permission.continuation_state = 'READY'))))
BEGIN SELECT RAISE(ABORT, 'Task Brain user-wait continuation is not exact'); END;

CREATE TRIGGER task_turn_user_wait_continuation_immutable_v266
BEFORE UPDATE ON task_turn_user_wait_continuation_v266
BEGIN SELECT RAISE(ABORT, 'Task Brain user-wait continuation is immutable'); END;

-- Conversation Turns freeze the immutable Task Brain engine, exact Task epoch,
-- current Stage/code subject while active, and no Stage while terminal.
CREATE TRIGGER task_brain_conversation_turn_insert_v266
BEFORE INSERT ON task_turn
WHEN NEW.purpose = 'TASK_BRAIN_CONVERSATION'
  AND NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN task_brain brain ON brain.task_id = task.id
    JOIN task_creation_context creation ON creation.task_id = task.id
    LEFT JOIN task_current_stage current ON current.task_id = task.id
    LEFT JOIN stage owner ON owner.id = current.stage_id
    LEFT JOIN task_current_code_subject_v230 code
      ON code.task_id = task.id
    LEFT JOIN task_outcome outcome ON outcome.task_id = task.id
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.epoch = NEW.task_epoch
      AND NEW.status = 'REQUESTED'
      AND json_valid(NEW.launch_input)
      AND json_extract(NEW.launch_input, '$.schemaVersion') = 1
      AND json_extract(NEW.launch_input, '$.provider') = brain.provider
      AND json_extract(NEW.launch_input, '$.model') = brain.model
      AND json_extract(NEW.launch_input, '$.provider') =
          json_extract(creation.work_model_snapshot, '$.agentOrProvider')
      AND (json_extract(creation.work_model_snapshot, '$.model') IS NULL
        OR json_extract(creation.work_model_snapshot, '$.model') = brain.model)
      AND json_extract(NEW.launch_input, '$.transport') =
          json_extract(creation.work_model_snapshot, '$.kind')
      AND length(json_extract(NEW.launch_input, '$.workingDirectory')) > 0
      AND length(json_extract(NEW.launch_input, '$.prompt')) > 0
      AND json_extract(NEW.launch_input, '$.toolEndpoint.ownerKind') =
          'TASK_TURN'
      AND json_extract(NEW.launch_input, '$.toolEndpoint.ownerId') = NEW.id
      AND json_extract(NEW.launch_input, '$.toolEndpoint.operationId') =
          NEW.operation_id
      AND json_extract(NEW.launch_input, '$.toolEndpoint.profile') =
          'TASK_BRAIN_READ_ONLY'
      AND ((task.lifecycle_state = 'ACTIVE'
            AND current.stage_id = NEW.trigger_stage_id
            AND current.stage_generation = NEW.trigger_stage_generation
            AND owner.completed_at_ms IS NULL
            AND NEW.expected_code_fingerprint IS code.code_fingerprint
            AND NEW.expected_head_sha IS code.head_sha
            AND NEW.expected_base_sha IS code.base_sha)
        OR (task.lifecycle_state IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            AND outcome.task_epoch = NEW.task_epoch
            AND NEW.trigger_stage_id IS NULL
            AND NEW.trigger_stage_generation IS NULL
            AND NEW.expected_code_fingerprint IS code.code_fingerprint
            AND NEW.expected_head_sha IS code.head_sha
            AND NEW.expected_base_sha IS code.base_sha)))
BEGIN SELECT RAISE(ABORT, 'Task Brain conversation Turn is not exact'); END;

CREATE TRIGGER task_brain_conversation_ticket_insert_v266
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'EXECUTE_TASK_TURN'
  AND EXISTS (
      SELECT 1 FROM task_turn turn
      WHERE turn.id = NEW.owner_id
        AND turn.purpose = 'TASK_BRAIN_CONVERSATION')
  AND NOT EXISTS (
    SELECT 1 FROM task_turn turn
    JOIN tasks task ON task.id = turn.task_id
    WHERE turn.id = NEW.owner_id
      AND turn.operation_id = NEW.operation_id
      AND turn.task_id = NEW.task_id
      AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id IS NEW.stage_id
      AND turn.trigger_stage_generation IS NEW.stage_generation
      AND turn.attempt = NEW.attempt
      AND turn.expected_code_fingerprint IS
          NEW.expected_code_fingerprint
      AND turn.expected_head_sha IS NEW.expected_head_sha
      AND turn.expected_base_sha IS NEW.expected_base_sha
      AND NEW.async_family = 'AGENT_TURN'
      AND NEW.owner_kind = 'TASK_TURN'
      AND NEW.callback_route = 'TASK_TURN_RESULT'
      AND NEW.lane_mask = (CASE
          json_extract(turn.launch_input, '$.transport')
          WHEN 'CLI' THEN 1 ELSE 2 END) + CASE
              WHEN task.lifecycle_state = 'ACTIVE' THEN 0 ELSE 8 END
      AND NEW.trunk_control = 0
      AND NEW.writer_required = 0
      AND NEW.status = 'REQUESTED'
      AND ((task.lifecycle_state = 'ACTIVE'
            AND NEW.exclusive_task = 1
            AND NEW.stage_id IS NOT NULL)
        OR (task.lifecycle_state IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            AND NEW.exclusive_task = 0
            AND NEW.stage_id IS NULL)))
BEGIN SELECT RAISE(ABORT, 'Task Brain conversation ticket is not exact'); END;

-- Local Brain deliveries may be a successor execution while the episode and
-- TaskManager retain the original logical result fence.
DROP TRIGGER local_brain_turn_delivery_receipt_insert;
CREATE TRIGGER local_brain_turn_delivery_receipt_insert
BEFORE INSERT ON local_brain_turn_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM task_turn delivered
    LEFT JOIN task_turn_user_wait_continuation_v266 continuation
      ON continuation.successor_turn_id = delivered.id
    JOIN brain_review_episode episode
      ON episode.task_turn_id = COALESCE(
          continuation.logical_turn_id, delivered.id)
    WHERE episode.id = NEW.brain_review_episode_id
      AND delivered.id = NEW.task_turn_id
      AND delivered.operation_id = NEW.operation_id
      AND delivered.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
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

-- The Remote callback has its own route, but the dispatched TaskTurn may also
-- be a continuation of the episode's logical root.
DROP TRIGGER dispatch_ticket_remote_feedback_brain_insert;
CREATE TRIGGER dispatch_ticket_remote_feedback_brain_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'EXECUTE_TASK_TURN'
  AND EXISTS (
      SELECT 1 FROM task_turn turn
      WHERE turn.id = NEW.owner_id
        AND turn.purpose = 'REMOTE_FEEDBACK_BRAIN_REVIEW')
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM task_turn turn
        LEFT JOIN task_turn_user_wait_continuation_v266 continuation
          ON continuation.successor_turn_id = turn.id
        JOIN remote_feedback_brain_episode episode
          ON episode.task_turn_id = COALESCE(
              continuation.logical_turn_id, turn.id)
        WHERE turn.id = NEW.owner_id
          AND turn.operation_id = NEW.operation_id
          AND turn.attempt = NEW.attempt
          AND turn.task_id = NEW.task_id
          AND turn.task_epoch = NEW.task_epoch
          AND turn.trigger_stage_id = NEW.stage_id
          AND turn.trigger_stage_generation = NEW.stage_generation
          AND turn.expected_code_fingerprint =
              NEW.expected_code_fingerprint
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

-- Cleanup continues to reject all ordinary work. Terminal conversation is a
-- read-only provider-lane exception and is never admitted during CLEANING.
DROP TRIGGER cleanup_task_turn_admission;
CREATE TRIGGER cleanup_task_turn_admission
BEFORE INSERT ON task_turn
WHEN EXISTS (
    SELECT 1 FROM tasks task
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state IN (
          'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
  AND NOT EXISTS (
    SELECT 1 FROM tasks task
    JOIN task_outcome outcome ON outcome.task_id = task.id
    WHERE task.id = NEW.task_id
      AND task.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
      AND task.epoch = NEW.task_epoch
      AND ((outcome.summary_state = 'FALLBACK'
            AND NEW.purpose = 'TASK_COMPLETION_SUMMARY'
            AND NEW.status = 'REQUESTED'
            AND NEW.trigger_stage_id IS NULL
            AND NEW.trigger_stage_generation IS NULL
            AND NOT EXISTS (
                SELECT 1 FROM task_outcome_summary_operation summary
                WHERE summary.task_outcome_id = outcome.id
                  AND summary.status IN ('REQUESTED', 'SUCCEEDED'))
            AND NOT EXISTS (
                SELECT 1 FROM task_turn existing
                WHERE existing.task_id = NEW.task_id
                  AND existing.purpose = 'TASK_COMPLETION_SUMMARY'
                  AND existing.status IN (
                    'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')))
        OR (NEW.purpose = 'TASK_BRAIN_CONVERSATION'
            AND NEW.status = 'REQUESTED'
            AND NEW.trigger_stage_id IS NULL
            AND NEW.trigger_stage_generation IS NULL)))
BEGIN SELECT RAISE(ABORT, 'Task admits no regular Turns during or after Cleanup'); END;

DROP TRIGGER cleanup_dispatch_admission;
CREATE TRIGGER cleanup_dispatch_admission
BEFORE INSERT ON dispatch_ticket
WHEN NEW.task_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM tasks task
      WHERE task.id = NEW.task_id
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state IN (
            'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
  AND NOT (
      EXISTS (
          SELECT 1 FROM tasks task
          JOIN task_current_stage current ON current.task_id = task.id
          JOIN stage owner ON owner.id = current.stage_id
          JOIN cleanup_stage cleanup ON cleanup.stage_id = owner.id
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state = 'CLEANING'
            AND task.epoch = NEW.task_epoch
            AND owner.kind = 'CLEANUP'
            AND owner.checkpoint = 'WAITING_QUIESCENCE'
            AND owner.id = NEW.stage_id
            AND owner.generation = NEW.stage_generation
            AND NEW.operation_kind = 'RUN_CLEANUP_OPERATION'
            AND NEW.async_family = 'CLEANUP'
            AND NEW.owner_kind = 'STAGE'
            AND NEW.owner_id = owner.id
            AND NEW.callback_route = 'CLEANUP_OPERATION_RESULT'
            AND NEW.lane_mask = 256
            AND NEW.exclusive_task = 1
            AND NEW.writer_required = 1
            AND NOT EXISTS (
                SELECT 1 FROM dispatch_ticket existing
                WHERE existing.task_id = NEW.task_id
                  AND existing.task_epoch = NEW.task_epoch
                  AND existing.async_family = 'CLEANUP'))
      OR EXISTS (
          SELECT 1 FROM tasks task
          JOIN task_outcome outcome ON outcome.task_id = task.id
          JOIN task_turn turn ON turn.task_id = task.id
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            AND task.epoch = NEW.task_epoch
            AND outcome.summary_state = 'FALLBACK'
            AND turn.id = NEW.owner_id
            AND turn.purpose = 'TASK_COMPLETION_SUMMARY'
            AND turn.operation_id = NEW.operation_id
            AND turn.attempt = NEW.attempt
            AND NEW.operation_kind = 'GENERATE_TASK_OUTCOME_SUMMARY'
            AND NEW.async_family = 'AGENT_TURN'
            AND NEW.owner_kind = 'TASK_TURN'
            AND NEW.callback_route = 'TASK_OUTCOME_SUMMARY_RESULT'
            AND NEW.lane_mask = 2
            AND NEW.trunk_control = 0
            AND NEW.exclusive_task = 0
            AND NEW.writer_required = 0
            AND NEW.stage_id IS NULL
            AND NEW.stage_generation IS NULL)
      OR EXISTS (
          SELECT 1 FROM tasks task
          JOIN task_outcome outcome ON outcome.task_id = task.id
          JOIN task_turn turn ON turn.id = NEW.owner_id
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            AND task.epoch = NEW.task_epoch
            AND outcome.task_epoch = NEW.task_epoch
            AND turn.task_id = task.id
            AND turn.purpose = 'TASK_BRAIN_CONVERSATION'
            AND turn.operation_id = NEW.operation_id
            AND turn.attempt = NEW.attempt
            AND NEW.operation_kind = 'EXECUTE_TASK_TURN'
            AND NEW.async_family = 'AGENT_TURN'
            AND NEW.owner_kind = 'TASK_TURN'
            AND NEW.callback_route = 'TASK_TURN_RESULT'
            AND NEW.lane_mask IN (9, 10)
            AND NEW.trunk_control = 0
            AND NEW.exclusive_task = 0
            AND NEW.writer_required = 0
            AND NEW.stage_id IS NULL
            AND NEW.stage_generation IS NULL))
BEGIN SELECT RAISE(ABORT, 'Task admits no unrelated dispatch during or after Cleanup'); END;
