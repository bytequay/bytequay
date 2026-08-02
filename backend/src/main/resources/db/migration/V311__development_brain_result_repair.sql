-- A repair episode is still an ordinary validated Development Brain episode,
-- but it has a distinct TaskTurn purpose so delivery cannot be confused with
-- a semantic review attempt.
DROP TRIGGER brain_review_episode_owner_insert;
CREATE TRIGGER brain_review_episode_owner_insert
BEFORE INSERT ON brain_review_episode
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'REQUESTED'
            THEN RAISE(ABORT, 'BrainReviewEpisode must start REQUESTED')
        WHEN NOT EXISTS (
            SELECT 1
            FROM task_brain b
            JOIN tasks t ON t.id = b.task_id
            JOIN local_development_stage l ON l.task_id = t.id
            JOIN stage s ON s.id = l.stage_id
            JOIN task_current_stage c ON c.stage_id = l.stage_id
            JOIN dev_report r ON r.id = NEW.dev_report_id
            JOIN validation_evidence v ON v.id = NEW.validation_evidence_id
            JOIN task_turn tt ON tt.id = NEW.task_turn_id
            WHERE b.id = NEW.task_brain_id
              AND b.task_id = NEW.task_id
              AND t.workflow_version = 'V2'
              AND t.lifecycle_state = 'ACTIVE'
              AND t.epoch = NEW.task_epoch
              AND l.stage_id = NEW.local_development_stage_id
              AND l.generation = NEW.stage_generation
              AND l.opened_for_epoch = NEW.task_epoch
              AND s.completed_at_ms IS NULL
              AND c.task_id = NEW.task_id
              AND c.stage_generation = NEW.stage_generation
              AND r.local_development_stage_id = l.stage_id
              AND r.task_id = NEW.task_id
              AND r.task_epoch = NEW.task_epoch
              AND r.stage_generation = NEW.stage_generation
              AND r.code_fingerprint = NEW.code_fingerprint
              AND r.head_sha = NEW.expected_head_sha
              AND r.base_sha = NEW.expected_base_sha
              AND v.validation_operation_id IN (
                  SELECT o.id FROM validation_operation o
                  WHERE o.dev_report_id = r.id AND o.status = 'COMPLETED')
              AND v.passed = 1
              AND v.code_fingerprint = NEW.code_fingerprint
              AND v.head_sha = NEW.expected_head_sha
              AND v.base_sha = NEW.expected_base_sha
              AND tt.task_id = NEW.task_id
              AND tt.task_epoch = NEW.task_epoch
              AND tt.trigger_stage_id = l.stage_id
              AND tt.trigger_stage_generation = l.generation
              AND tt.expected_code_fingerprint = NEW.code_fingerprint
              AND tt.expected_head_sha = NEW.expected_head_sha
              AND tt.expected_base_sha = NEW.expected_base_sha
              AND tt.purpose IN (
                  'DEVELOPMENT_BRAIN_REVIEW',
                  'DEVELOPMENT_BRAIN_RESULT_REPAIR')
              AND tt.status IN ('REQUESTED', 'QUEUED'))
            THEN RAISE(ABORT, 'BrainReviewEpisode requires exact green validation and TaskTurn')
    END;
END;

-- A malformed result from the one ordinary Development Brain retry may be
-- repaired once by a fresh, read-only TaskTurn.  This row freezes the exact
-- malformed text independently of provider-log retention and is also the
-- durable delivery fence for the repair result.
CREATE TABLE development_brain_result_repair_v311 (
    id                              TEXT    NOT NULL PRIMARY KEY,
    predecessor_failure_id          TEXT    NOT NULL UNIQUE
        REFERENCES development_brain_protocol_failure_v300(id),
    source_failure_id               TEXT    NOT NULL UNIQUE
        REFERENCES development_brain_protocol_failure_v300(id),
    source_task_turn_id             TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    source_operation_id             TEXT    NOT NULL UNIQUE,
    source_malformed_output         TEXT    NOT NULL,
    source_raw_result_digest        TEXT    NOT NULL
        CHECK (length(source_raw_result_digest) = 64),
    required_result_shape           TEXT    NOT NULL,
    repair_brain_review_episode_id  TEXT    NOT NULL UNIQUE
        REFERENCES brain_review_episode(id),
    repair_task_turn_id             TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    repair_operation_id             TEXT    NOT NULL UNIQUE,
    repair_ticket_id                TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    task_id                         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_id                        TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation                INTEGER NOT NULL CHECK (stage_generation > 0),
    code_fingerprint                TEXT    NOT NULL,
    head_sha                        TEXT    NOT NULL,
    base_sha                        TEXT    NOT NULL,
    status                          TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    raw_outcome                     TEXT CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    repair_raw_result_digest        TEXT
        CHECK (repair_raw_result_digest IS NULL
            OR length(repair_raw_result_digest) = 64),
    repaired_payload_digest         TEXT
        CHECK (repaired_payload_digest IS NULL
            OR length(repaired_payload_digest) = 64),
    acceptance                      TEXT CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    terminal_evidence               TEXT CHECK (terminal_evidence IS NULL
        OR (json_valid(terminal_evidence)
            AND json_type(terminal_evidence) = 'object')),
    requested_at_ms                 INTEGER NOT NULL CHECK (requested_at_ms >= 0),
    completed_at_ms                 INTEGER CHECK (completed_at_ms >= requested_at_ms),
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_id)) > 0
        AND length(trim(source_malformed_output,
            char(9) || char(10) || char(13) || ' ')) > 0
        AND length(trim(required_result_shape)) > 0
        AND length(trim(repair_operation_id)) > 0
        AND length(trim(code_fingerprint)) > 0
        AND length(trim(head_sha)) > 0
        AND length(trim(base_sha)) > 0),
    CHECK (required_result_shape =
        '{"schemaVersion":1,"verdict":"APPROVED","summary":"string","findings":[]}'),
    CHECK ((status = 'REQUESTED'
            AND raw_outcome IS NULL
            AND repair_raw_result_digest IS NULL
            AND repaired_payload_digest IS NULL
            AND acceptance IS NULL
            AND terminal_evidence IS NULL
            AND completed_at_ms IS NULL)
        OR (status <> 'REQUESTED'
            AND raw_outcome IS NOT NULL
            AND repair_raw_result_digest IS NOT NULL
            AND acceptance IS NOT NULL
            AND terminal_evidence IS NOT NULL
            AND completed_at_ms IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED'
            AND raw_outcome = 'SUCCEEDED'
            AND acceptance = 'ACCEPTED'
            AND repaired_payload_digest IS NOT NULL)
        OR (status = 'FAILED'
            AND raw_outcome IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')
            AND acceptance = 'ACCEPTED'
            AND repaired_payload_digest IS NULL)
        OR (status = 'CANCELED'
            AND raw_outcome = 'CANCELED'
            AND acceptance = 'ACCEPTED'
            AND repaired_payload_digest IS NULL)
        OR (status = 'SUPERSEDED'
            AND acceptance = 'SUPERSEDED'
            AND repaired_payload_digest IS NULL)
        OR status = 'REQUESTED')
);

-- Clearing the Task pending-result fence after a terminal repair uses the
-- existing typed Brain protocol-failure command.  Extend its proof only for a
-- repair row that is still REQUESTED; the ordinary review proof is unchanged.
DROP TRIGGER task_brain_protocol_failure_receipt_insert_v300;
CREATE TRIGGER task_brain_protocol_failure_receipt_insert_v300
BEFORE INSERT ON task_brain_protocol_failure_receipt_v300
WHEN NOT EXISTS (
    SELECT 1
    FROM task_transition transition
    JOIN tasks task ON task.id = NEW.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_blocker blocker ON blocker.id = NEW.proof_id
    JOIN task_turn delivered ON delivered.id = blocker.subject_revision
    LEFT JOIN task_turn_user_wait_continuation_v266 continuation
      ON continuation.successor_turn_id = delivered.id
    JOIN task_turn logical ON logical.id = COALESCE(
        continuation.logical_turn_id, delivered.id)
    JOIN brain_review_episode episode
      ON episode.task_id = task.id
     AND episode.task_turn_id = logical.id
    WHERE transition.task_id = task.id
      AND transition.command_id = NEW.command_id
      AND transition.cause = NEW.cause
      AND transition.actor = NEW.actor
      AND transition.aggregate_version = NEW.returned_version
      AND task.workflow_version = 'V2'
      AND task.thread_id = NEW.returned_trunk_id
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.subject_task_epoch
      AND task.aggregate_version = NEW.returned_version
      AND current.stage_id = NEW.subject_stage_id
      AND current.stage_generation = NEW.subject_stage_generation
      AND owner.kind = 'LOCAL_DEVELOPMENT'
      AND owner.checkpoint = 'BRAIN_REVIEW'
      AND owner.completed_at_ms IS NULL
      AND blocker.task_id = task.id
      AND blocker.stage_id IS NULL
      AND blocker.owner_kind = 'TASK'
      AND blocker.owner_id = task.id
      AND blocker.subject_revision = delivered.id
      AND blocker.blocker_type = 'OPERATION_FAILED'
      AND blocker.status = 'OPEN'
      AND episode.local_development_stage_id = owner.id
      AND episode.stage_generation = owner.generation
      AND logical.operation_id = NEW.subject_operation_id
      AND logical.attempt = NEW.subject_attempt
      AND logical.expected_code_fingerprint =
          NEW.subject_expected_code_fingerprint
      AND logical.expected_head_sha = NEW.subject_expected_head_sha
      AND logical.expected_base_sha = NEW.subject_expected_base_sha
      AND delivered.task_id = task.id
      AND delivered.task_epoch = task.epoch
      AND delivered.trigger_stage_id = owner.id
      AND delivered.trigger_stage_generation = owner.generation
      AND delivered.expected_code_fingerprint =
          NEW.subject_expected_code_fingerprint
      AND delivered.expected_head_sha = NEW.subject_expected_head_sha
      AND delivered.expected_base_sha = NEW.subject_expected_base_sha
      AND ((delivered.purpose = 'DEVELOPMENT_BRAIN_REVIEW'
            AND delivered.status = 'FAILED'
            AND episode.status = 'FAILED')
        OR (delivered.purpose = 'DEVELOPMENT_BRAIN_RESULT_REPAIR'
            AND delivered.status IN ('FAILED', 'CANCELED')
            AND episode.status = delivered.status
            AND EXISTS (
                SELECT 1
                FROM development_brain_result_repair_v311 repair
                WHERE repair.repair_brain_review_episode_id = episode.id
                  AND repair.repair_task_turn_id = delivered.id
                  AND repair.repair_operation_id = delivered.operation_id
                  AND repair.task_id = task.id
                  AND repair.task_epoch = task.epoch
                  AND repair.stage_id = owner.id
                  AND repair.stage_generation = owner.generation
                  AND repair.code_fingerprint =
                      delivered.expected_code_fingerprint
                  AND repair.head_sha = delivered.expected_head_sha
                  AND repair.base_sha = delivered.expected_base_sha
                  AND repair.status = 'REQUESTED')))
    UNION ALL
    SELECT 1
    FROM task_transition transition
    JOIN tasks task ON task.id = NEW.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_blocker blocker ON blocker.id = NEW.proof_id
    JOIN task_turn delivered ON delivered.id = blocker.subject_revision
    JOIN remote_brain_operation_v248 operation
      ON operation.task_turn_id = delivered.id
    WHERE transition.task_id = task.id
      AND transition.command_id = NEW.command_id
      AND transition.cause = NEW.cause
      AND transition.actor = NEW.actor
      AND transition.aggregate_version = NEW.returned_version
      AND task.workflow_version = 'V2'
      AND task.thread_id = NEW.returned_trunk_id
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.subject_task_epoch
      AND task.aggregate_version = NEW.returned_version
      AND current.stage_id = NEW.subject_stage_id
      AND current.stage_generation = NEW.subject_stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND blocker.task_id = task.id
      AND blocker.stage_id IS NULL
      AND blocker.owner_kind = 'TASK'
      AND blocker.owner_id = task.id
      AND blocker.subject_revision = delivered.id
      AND blocker.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
      AND blocker.status = 'OPEN'
      AND operation.task_id = task.id
      AND operation.task_epoch = NEW.subject_task_epoch
      AND operation.stage_id = NEW.subject_stage_id
      AND operation.stage_generation = NEW.subject_stage_generation
      AND operation.operation_id = NEW.subject_operation_id
      AND operation.attempt = NEW.subject_attempt
      AND operation.expected_code_fingerprint IS
          NEW.subject_expected_code_fingerprint
      AND operation.expected_head_sha IS NEW.subject_expected_head_sha
      AND operation.expected_base_sha IS NEW.subject_expected_base_sha
      AND operation.status IN ('FAILED', 'CANCELED')
      AND (EXISTS (
              SELECT 1 FROM ci_repair_delivery_receipt delivery
              WHERE delivery.ci_repair_operation_id = operation.proof_id
                AND delivery.operation_id = operation.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED'))
        OR EXISTS (
              SELECT 1 FROM branch_sync_delivery_receipt delivery
              WHERE delivery.branch_sync_dispatch_operation_id =
                    operation.proof_id
                AND delivery.operation_id = operation.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED'))
        OR EXISTS (
              SELECT 1
              FROM remote_repair_brain_replacement_delivery_v309 delivery
              WHERE delivery.replacement_operation_id = operation.proof_id
                AND delivery.operation_id = operation.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED')))
      AND delivered.task_id = task.id
      AND delivered.task_epoch = task.epoch
      AND delivered.trigger_stage_id = owner.id
      AND delivered.trigger_stage_generation = owner.generation
      AND delivered.purpose IN (
          'REMOTE_CI_BRAIN_REVIEW', 'BRANCH_SYNC_BRAIN_REVIEW')
      AND delivered.status IN ('FAILED', 'CANCELED')
      AND delivered.expected_code_fingerprint IS
          NEW.subject_expected_code_fingerprint
      AND delivered.expected_head_sha IS NEW.subject_expected_head_sha
      AND delivered.expected_base_sha IS NEW.subject_expected_base_sha)
BEGIN SELECT RAISE(ABORT,
    'Brain protocol failure Task receipt is not exact'); END;

-- The bridge is admitted only after the normal retry itself produced a
-- protocol failure.  It reuses the existing AGENT_TURN lane and Brain budget
-- lineage; no scheduler, lane, or semantic-budget family is introduced.
CREATE TRIGGER development_brain_result_repair_insert_v311
BEFORE INSERT ON development_brain_result_repair_v311
WHEN NOT EXISTS (
    SELECT 1
    FROM development_brain_protocol_failure_v300 failure
    JOIN development_brain_retry_v300 ordinary_retry
      ON ordinary_retry.replacement_episode_id = failure.brain_review_episode_id
     AND ordinary_retry.replacement_turn_id = failure.owner_turn_id
     AND ordinary_retry.replacement_operation_id = failure.owner_operation_id
    JOIN task_blocker blocker ON blocker.id = failure.blocker_id
    JOIN brain_review_episode source_episode
      ON source_episode.id = failure.brain_review_episode_id
    JOIN task_turn source_turn ON source_turn.id = failure.task_turn_id
    JOIN dispatch_ticket source_ticket
      ON source_ticket.operation_id = source_turn.operation_id
     AND source_ticket.owner_kind = 'TASK_TURN'
     AND source_ticket.owner_id = source_turn.id
    JOIN task_brain_request_receipt repair_request
      ON repair_request.task_id = NEW.task_id
     AND repair_request.proof_id = NEW.repair_brain_review_episode_id
     AND repair_request.subject_operation_id = NEW.repair_operation_id
    JOIN brain_review_episode repair_episode
      ON repair_episode.id = NEW.repair_brain_review_episode_id
    JOIN task_turn repair_turn ON repair_turn.id = NEW.repair_task_turn_id
    JOIN dispatch_ticket repair_ticket ON repair_ticket.id = NEW.repair_ticket_id
    JOIN development_brain_retry_budget_lineage_v300 lineage
      ON lineage.predecessor_episode_id = source_episode.id
     AND lineage.successor_episode_id = repair_episode.id
    JOIN tasks task ON task.id = NEW.task_id
    JOIN threads trunk ON trunk.id = task.thread_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN task_current_code_subject_v230 current_code
      ON current_code.task_id = task.id
    JOIN stage owner ON owner.id = NEW.stage_id
    JOIN local_development_stage local_owner ON local_owner.stage_id = owner.id
    WHERE failure.id = NEW.source_failure_id
      AND ordinary_retry.failure_id = NEW.predecessor_failure_id
      AND failure.task_turn_id = NEW.source_task_turn_id
      AND failure.operation_id = NEW.source_operation_id
      AND failure.raw_result_digest = NEW.source_raw_result_digest
      AND failure.raw_outcome = 'SUCCEEDED'
      AND failure.task_id = NEW.task_id
      AND failure.task_epoch = NEW.task_epoch
      AND failure.stage_id = NEW.stage_id
      AND failure.stage_generation = NEW.stage_generation
      AND failure.code_fingerprint = NEW.code_fingerprint
      AND failure.head_sha = NEW.head_sha
      AND failure.base_sha = NEW.base_sha
      AND length(trim(NEW.source_malformed_output,
          char(9) || char(10) || char(13) || ' ')) > 0
      AND blocker.task_id = NEW.task_id
      AND blocker.owner_kind = 'TASK'
      AND blocker.owner_id = NEW.task_id
      AND blocker.stage_id IS NULL
      AND blocker.subject_revision = source_turn.id
      AND blocker.blocker_type = 'OPERATION_FAILED'
      AND blocker.status = 'OPEN'
      AND source_episode.status = 'FAILED'
      AND source_turn.id = NEW.source_task_turn_id
      AND source_turn.operation_id = NEW.source_operation_id
      AND source_turn.purpose = 'DEVELOPMENT_BRAIN_REVIEW'
      AND source_turn.status = 'FAILED'
      AND source_ticket.callback_route = 'TASK_TURN_RESULT'
      AND EXISTS (
          SELECT 1
          FROM agent_execution execution
          WHERE execution.ticket_id = source_ticket.id
            AND execution.status = 'SUCCEEDED'
            AND execution.infrastructure_attempt =
                source_ticket.infrastructure_attempts
            AND execution.raw_result IS NOT NULL
            AND json_valid(execution.raw_result)
            AND json_extract(execution.raw_result, '$.outcome') = 'SUCCEEDED'
            AND json_extract(execution.raw_result,
                '$.fence.taskEpoch') = NEW.task_epoch
            AND json_extract(execution.raw_result,
                '$.fence.stageId') = NEW.stage_id
            AND json_extract(execution.raw_result,
                '$.fence.stageGeneration') = NEW.stage_generation
            AND json_extract(execution.raw_result,
                '$.fence.operationId') = NEW.source_operation_id
            AND json_extract(execution.raw_result,
                '$.fence.attempt') = source_turn.attempt
            AND json_extract(execution.raw_result,
                '$.fence.expectedCodeFingerprint') = NEW.code_fingerprint
            AND json_extract(execution.raw_result,
                '$.fence.expectedHeadSha') = NEW.head_sha
            AND json_extract(execution.raw_result,
                '$.fence.expectedBaseSha') = NEW.base_sha
            AND json_valid(json_extract(
                execution.raw_result, '$.payloadJson'))
            AND json_extract(json_extract(execution.raw_result,
                '$.payloadJson'), '$.turnId') = NEW.source_task_turn_id
            AND json_extract(json_extract(execution.raw_result,
                '$.payloadJson'), '$.ownerKind') = 'TASK_TURN'
            AND json_extract(json_extract(execution.raw_result,
                '$.payloadJson'), '$.purpose') = 'DEVELOPMENT_BRAIN_REVIEW'
            AND json_type(json_extract(
                execution.raw_result, '$.payloadJson'), '$.finalText') = 'text'
            AND json_extract(json_extract(
                execution.raw_result, '$.payloadJson'), '$.finalText') =
                NEW.source_malformed_output)
      AND repair_episode.task_brain_id = source_episode.task_brain_id
      AND repair_episode.task_id = NEW.task_id
      AND repair_episode.task_epoch = NEW.task_epoch
      AND repair_episode.local_development_stage_id = NEW.stage_id
      AND repair_episode.stage_generation = NEW.stage_generation
      AND repair_episode.dev_report_id = source_episode.dev_report_id
      AND repair_episode.validation_evidence_id = source_episode.validation_evidence_id
      AND repair_episode.task_turn_id = NEW.repair_task_turn_id
      AND repair_episode.semantic_attempt = source_episode.semantic_attempt + 1
      AND repair_episode.code_fingerprint = NEW.code_fingerprint
      AND repair_episode.expected_head_sha = NEW.head_sha
      AND repair_episode.expected_base_sha = NEW.base_sha
      AND repair_episode.status = 'REQUESTED'
      AND repair_turn.operation_id = NEW.repair_operation_id
      AND repair_turn.purpose = 'DEVELOPMENT_BRAIN_RESULT_REPAIR'
      AND repair_turn.status IN ('REQUESTED', 'QUEUED')
      AND repair_turn.task_id = NEW.task_id
      AND repair_turn.task_epoch = NEW.task_epoch
      AND repair_turn.trigger_stage_id = NEW.stage_id
      AND repair_turn.trigger_stage_generation = NEW.stage_generation
      AND repair_turn.attempt = repair_episode.semantic_attempt
      AND repair_turn.expected_code_fingerprint = NEW.code_fingerprint
      AND repair_turn.expected_head_sha = NEW.head_sha
      AND repair_turn.expected_base_sha = NEW.base_sha
      AND repair_turn.delivery_lane = source_turn.delivery_lane
      AND json_valid(repair_turn.launch_input)
      AND json_type(repair_turn.launch_input, '$.prompt') = 'text'
      AND instr(json_extract(repair_turn.launch_input, '$.prompt'),
          json_quote(NEW.source_malformed_output)) > 0
      AND instr(json_extract(repair_turn.launch_input, '$.prompt'),
          NEW.required_result_shape) > 0
      AND json_type(repair_turn.launch_input, '$.resumeSessionId') IS NULL
      AND json_type(repair_turn.launch_input, '$.fallbackPrompt') IS NULL
      AND json_type(repair_turn.launch_input,
          '$.priorCumulativeInputTokens') IS NULL
      AND json_type(repair_turn.launch_input,
          '$.priorCumulativeOutputTokens') IS NULL
      AND json_type(repair_turn.launch_input, '$.images') IS NULL
      AND json_extract(repair_turn.launch_input, '$.transport') =
          json_extract(source_turn.launch_input, '$.transport')
      AND json_extract(repair_turn.launch_input, '$.provider') =
          json_extract(source_turn.launch_input, '$.provider')
      AND json_extract(repair_turn.launch_input, '$.model') =
          json_extract(source_turn.launch_input, '$.model')
      AND json_extract(repair_turn.launch_input, '$.workingDirectory') =
          json_extract(source_turn.launch_input, '$.workingDirectory')
      AND json_extract(repair_turn.launch_input,
          '$.toolEndpoint.ownerKind') = 'TASK_TURN'
      AND json_extract(repair_turn.launch_input,
          '$.toolEndpoint.ownerId') = repair_turn.id
      AND json_extract(repair_turn.launch_input,
          '$.toolEndpoint.operationId') = repair_turn.operation_id
      AND json_extract(repair_turn.launch_input,
          '$.toolEndpoint.profile') = 'TASK_BRAIN_READ_ONLY'
      AND repair_ticket.operation_id = NEW.repair_operation_id
      AND repair_ticket.operation_kind = 'EXECUTE_TASK_TURN'
      AND repair_ticket.async_family = 'AGENT_TURN'
      AND repair_ticket.owner_kind = 'TASK_TURN'
      AND repair_ticket.owner_id = NEW.repair_task_turn_id
      AND repair_ticket.callback_route = 'TASK_TURN_RESULT'
      AND repair_ticket.lane_mask = source_ticket.lane_mask
      AND repair_ticket.trunk_control = 0
      AND repair_ticket.exclusive_task = 1
      AND repair_ticket.writer_required = 0
      AND repair_ticket.workspace_id = trunk.workspace_id
      AND repair_ticket.trunk_id = task.thread_id
      AND repair_ticket.task_id = NEW.task_id
      AND repair_ticket.task_epoch = NEW.task_epoch
      AND repair_ticket.stage_id = NEW.stage_id
      AND repair_ticket.stage_generation = NEW.stage_generation
      AND repair_ticket.attempt = repair_episode.semantic_attempt
      AND repair_ticket.expected_code_fingerprint = NEW.code_fingerprint
      AND repair_ticket.expected_head_sha = NEW.head_sha
      AND repair_ticket.expected_base_sha = NEW.base_sha
      AND repair_ticket.status = 'REQUESTED'
      AND repair_request.cause = 'REQUEST_BRAIN_REVIEW'
      AND repair_request.disposition = 'APPLIED'
      AND repair_request.expected_task_epoch = NEW.task_epoch
      AND repair_request.subject_task_epoch = NEW.task_epoch
      AND repair_request.subject_stage_id = NEW.stage_id
      AND repair_request.subject_stage_generation = NEW.stage_generation
      AND repair_request.subject_attempt = repair_episode.semantic_attempt
      AND repair_request.subject_expected_code_fingerprint = NEW.code_fingerprint
      AND repair_request.subject_expected_head_sha = NEW.head_sha
      AND repair_request.subject_expected_base_sha = NEW.base_sha
      AND repair_request.returned_lifecycle = 'ACTIVE'
      AND repair_request.returned_epoch = NEW.task_epoch
      AND repair_request.returned_version = task.aggregate_version
      AND repair_request.returned_current_stage_id = NEW.stage_id
      AND repair_request.returned_pending_task_epoch = NEW.task_epoch
      AND repair_request.returned_pending_stage_id = NEW.stage_id
      AND repair_request.returned_pending_stage_generation = NEW.stage_generation
      AND repair_request.returned_pending_operation_id = NEW.repair_operation_id
      AND repair_request.returned_pending_attempt = repair_episode.semantic_attempt
      AND repair_request.returned_pending_code_fingerprint = NEW.code_fingerprint
      AND repair_request.returned_pending_head_sha = NEW.head_sha
      AND repair_request.returned_pending_base_sha = NEW.base_sha
      AND lineage.execution_attempt = repair_episode.semantic_attempt
      AND lineage.budget_attempt = ordinary_retry.budget_attempt
      AND lineage.consumes_budget = 0
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.stage_id
      AND current.stage_generation = NEW.stage_generation
      AND current_code.code_fingerprint = NEW.code_fingerprint
      AND current_code.head_sha = NEW.head_sha
      AND current_code.base_sha = NEW.base_sha
      AND owner.task_id = NEW.task_id
      AND owner.kind = 'LOCAL_DEVELOPMENT'
      AND owner.generation = NEW.stage_generation
      AND owner.version = failure.stage_version
      AND owner.checkpoint = 'BRAIN_REVIEW'
      AND owner.completed_at_ms IS NULL
      AND local_owner.task_id = NEW.task_id
      AND local_owner.generation = NEW.stage_generation
      AND local_owner.opened_for_epoch = NEW.task_epoch)
BEGIN SELECT RAISE(ABORT,
    'Development Brain result repair request is not exact'); END;

CREATE TRIGGER development_brain_result_repair_identity_immutable_v311
BEFORE UPDATE OF
    id, predecessor_failure_id, source_failure_id, source_task_turn_id,
    source_operation_id,
    source_malformed_output, source_raw_result_digest,
    required_result_shape,
    repair_brain_review_episode_id, repair_task_turn_id, repair_operation_id,
    repair_ticket_id, task_id, task_epoch, stage_id, stage_generation,
    code_fingerprint, head_sha, base_sha, requested_at_ms
ON development_brain_result_repair_v311
BEGIN SELECT RAISE(ABORT,
    'Development Brain result repair identity is immutable'); END;

-- The owner aggregate is terminalized first while the dispatch ticket still
-- holds the raw RESULT_PENDING delivery.  Only that exact frozen fence may
-- terminalize the repair row, and a terminal row cannot be delivered twice.
CREATE TRIGGER development_brain_result_repair_terminal_v311
BEFORE UPDATE OF
    status, raw_outcome, repair_raw_result_digest, repaired_payload_digest,
    acceptance, terminal_evidence, completed_at_ms
ON development_brain_result_repair_v311
WHEN OLD.status <> 'REQUESTED'
  OR NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
  OR NOT EXISTS (
    SELECT 1
    FROM brain_review_episode episode
    JOIN task_turn turn ON turn.id = episode.task_turn_id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.repair_ticket_id
    JOIN agent_execution execution
      ON execution.ticket_id = ticket.id
     AND execution.infrastructure_attempt = ticket.infrastructure_attempts
    JOIN tasks task ON task.id = NEW.task_id
    WHERE episode.id = NEW.repair_brain_review_episode_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.local_development_stage_id = NEW.stage_id
      AND episode.stage_generation = NEW.stage_generation
      AND episode.task_turn_id = NEW.repair_task_turn_id
      AND episode.code_fingerprint = NEW.code_fingerprint
      AND episode.expected_head_sha = NEW.head_sha
      AND episode.expected_base_sha = NEW.base_sha
      AND episode.status = NEW.status
      AND turn.id = NEW.repair_task_turn_id
      AND turn.operation_id = NEW.repair_operation_id
      AND turn.purpose = 'DEVELOPMENT_BRAIN_RESULT_REPAIR'
      AND turn.status = NEW.status
      AND turn.task_id = NEW.task_id
      AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.stage_id
      AND turn.trigger_stage_generation = NEW.stage_generation
      AND turn.expected_code_fingerprint = NEW.code_fingerprint
      AND turn.expected_head_sha = NEW.head_sha
      AND turn.expected_base_sha = NEW.base_sha
      AND ticket.operation_id = NEW.repair_operation_id
      AND ticket.owner_kind = 'TASK_TURN'
      AND ticket.owner_id = NEW.repair_task_turn_id
      AND ticket.callback_route = 'TASK_TURN_RESULT'
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND ticket.pending_result_task_epoch = NEW.task_epoch
      AND ticket.pending_result_stage_id = NEW.stage_id
      AND ticket.pending_result_stage_generation = NEW.stage_generation
      AND ticket.pending_result_operation_id = NEW.repair_operation_id
      AND ticket.pending_result_attempt = turn.attempt
      AND ticket.pending_result_expected_code_fingerprint = NEW.code_fingerprint
      AND ticket.pending_result_expected_head_sha = NEW.head_sha
      AND ticket.pending_result_expected_base_sha = NEW.base_sha
      AND ticket.infrastructure_attempts > 0
      AND execution.finished_at_ms IS NOT NULL
      AND execution.raw_result IS NOT NULL
      AND json_valid(execution.raw_result)
      AND json_type(execution.raw_result, '$.payloadJson') = 'text'
      AND json_valid(json_extract(execution.raw_result, '$.payloadJson'))
      AND json_extract(execution.raw_result, '$.payloadJson') =
          ticket.pending_result_payload
      AND json_extract(execution.raw_result, '$.outcome') = NEW.raw_outcome
      AND json_extract(execution.raw_result, '$.fence.taskEpoch') = NEW.task_epoch
      AND json_extract(execution.raw_result, '$.fence.stageId') = NEW.stage_id
      AND json_extract(execution.raw_result, '$.fence.stageGeneration') =
          NEW.stage_generation
      AND json_extract(execution.raw_result, '$.fence.operationId') =
          NEW.repair_operation_id
      AND json_extract(execution.raw_result, '$.fence.attempt') = turn.attempt
      AND json_extract(execution.raw_result,
          '$.fence.expectedCodeFingerprint') = NEW.code_fingerprint
      AND json_extract(execution.raw_result, '$.fence.expectedHeadSha') =
          NEW.head_sha
      AND json_extract(execution.raw_result, '$.fence.expectedBaseSha') =
          NEW.base_sha
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.turnId') = NEW.repair_task_turn_id
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.ownerKind') = 'TASK_TURN'
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.purpose') =
          'DEVELOPMENT_BRAIN_RESULT_REPAIR'
      AND ((NEW.raw_outcome = 'SUCCEEDED'
                AND execution.status = 'SUCCEEDED')
        OR (NEW.raw_outcome = 'FAILED' AND execution.status = 'FAILED')
        OR (NEW.raw_outcome = 'CANCELED' AND execution.status = 'CANCELED')
        OR (NEW.raw_outcome = 'INDETERMINATE'
                AND execution.status = 'UNKNOWN'))
      AND json_extract(NEW.terminal_evidence, '$.repairTurnId') =
          NEW.repair_task_turn_id
      AND json_extract(NEW.terminal_evidence, '$.repairOperationId') =
          NEW.repair_operation_id
      AND json_extract(NEW.terminal_evidence, '$.brainReviewEpisodeId') =
          NEW.repair_brain_review_episode_id
      AND json_extract(NEW.terminal_evidence, '$.rawResultDigest') =
          NEW.repair_raw_result_digest
      AND ((NEW.status = 'SUCCEEDED'
            AND json_type(json_extract(execution.raw_result,
                    '$.payloadJson'), '$.finalText') = 'text'
            AND json_extract(json_extract(execution.raw_result,
                    '$.payloadJson'), '$.finalText') =
                json_extract(NEW.terminal_evidence, '$.repairedPayload')
            AND json_extract(NEW.terminal_evidence,
                    '$.repairedPayloadDigest') = NEW.repaired_payload_digest
            AND json_extract(NEW.terminal_evidence, '$.verdict') =
                episode.verdict
            AND json_extract(NEW.terminal_evidence, '$.summary') =
                episode.verdict_summary
            AND EXISTS (
                SELECT 1
                FROM task_command_receipt receipt
                WHERE receipt.task_id = NEW.task_id
                  AND receipt.cause = 'ACCEPT_BRAIN_VERDICT'
                  AND receipt.disposition = 'APPLIED'
                  AND receipt.subject_task_epoch = NEW.task_epoch
                  AND receipt.subject_stage_id = NEW.stage_id
                  AND receipt.subject_stage_generation = NEW.stage_generation
                  AND receipt.subject_operation_id = NEW.repair_operation_id
                  AND receipt.subject_attempt = turn.attempt
                  AND receipt.subject_expected_code_fingerprint =
                      NEW.code_fingerprint
                  AND receipt.subject_expected_head_sha = NEW.head_sha
                  AND receipt.subject_expected_base_sha = NEW.base_sha
                  AND receipt.brain_verdict = episode.verdict
                  AND receipt.returned_version = task.aggregate_version
                  AND receipt.returned_pending_operation_id IS NULL
                  AND receipt.returned_last_brain_task_epoch = NEW.task_epoch
                  AND receipt.returned_last_brain_stage_id = NEW.stage_id
                  AND receipt.returned_last_brain_stage_generation =
                      NEW.stage_generation
                  AND receipt.returned_last_brain_operation_id =
                      NEW.repair_operation_id
                  AND receipt.returned_last_brain_attempt = turn.attempt
                  AND receipt.returned_last_brain_code_fingerprint =
                      NEW.code_fingerprint
                  AND receipt.returned_last_brain_head_sha = NEW.head_sha
                  AND receipt.returned_last_brain_base_sha = NEW.base_sha))
        OR (NEW.status IN ('FAILED', 'CANCELED')
            AND EXISTS (
                SELECT 1
                FROM task_blocker blocker
                JOIN task_brain_protocol_failure_receipt_v300 receipt
                  ON receipt.proof_id = blocker.id
                 AND receipt.task_id = NEW.task_id
                WHERE blocker.id = json_extract(
                        NEW.terminal_evidence, '$.blockerId')
                  AND blocker.task_id = NEW.task_id
                  AND blocker.stage_id IS NULL
                  AND blocker.owner_kind = 'TASK'
                  AND blocker.owner_id = NEW.task_id
                  AND blocker.subject_revision = NEW.repair_task_turn_id
                  AND blocker.blocker_type = 'OPERATION_FAILED'
                  AND blocker.status = 'OPEN'
                  AND receipt.cause = 'ACCEPT_BRAIN_PROTOCOL_FAILURE'
                  AND receipt.disposition = 'APPLIED'
                  AND receipt.subject_task_epoch = NEW.task_epoch
                  AND receipt.subject_stage_id = NEW.stage_id
                  AND receipt.subject_stage_generation = NEW.stage_generation
                  AND receipt.subject_operation_id = NEW.repair_operation_id
                  AND receipt.subject_attempt = turn.attempt
                  AND receipt.subject_expected_code_fingerprint =
                      NEW.code_fingerprint
                  AND receipt.subject_expected_head_sha = NEW.head_sha
                  AND receipt.subject_expected_base_sha = NEW.base_sha
                  AND receipt.returned_version = task.aggregate_version
                  AND receipt.returned_pending_operation_id IS NULL))
        OR NEW.status = 'SUPERSEDED'))
BEGIN SELECT RAISE(ABORT,
    'Development Brain result repair delivery is not exact'); END;
