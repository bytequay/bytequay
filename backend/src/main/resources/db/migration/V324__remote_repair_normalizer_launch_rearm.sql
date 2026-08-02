-- Rearm only the V323 result normalizer that the former nullable endpoint
-- decoder rejected before a provider or process could start.  The failed
-- AgentExecution remains immutable evidence; the same ticket continues at
-- infrastructure attempt two without charging a semantic repair attempt.
SAVEPOINT remote_repair_normalizer_launch_rearm_v324;

CREATE TEMP TABLE remote_repair_normalizer_launch_suspect_v324 (
    normalization_operation_row_id TEXT NOT NULL PRIMARY KEY
);

INSERT INTO remote_repair_normalizer_launch_suspect_v324
SELECT operation.id
FROM remote_repair_result_normalization_operation_v322 operation
WHERE operation.status = 'FAILED'
  AND (instr(COALESCE(operation.error_message, ''),
          'approvalPromptTool must name the scoped ByteQuay gate') > 0
    OR EXISTS (
        SELECT 1 FROM task_turn turn
        WHERE turn.id = operation.normalization_task_turn_id
          AND instr(COALESCE(turn.error_message, ''),
              'approvalPromptTool must name the scoped ByteQuay gate') > 0)
    OR EXISTS (
        SELECT 1 FROM dispatch_ticket ticket
        WHERE ticket.id = operation.dispatch_ticket_id
          AND instr(COALESCE(ticket.last_error, ''),
              'approvalPromptTool must name the scoped ByteQuay gate') > 0)
    OR EXISTS (
        SELECT 1
        FROM agent_execution execution
        WHERE execution.ticket_id = operation.dispatch_ticket_id
          AND instr(COALESCE(execution.raw_result, ''),
              'approvalPromptTool must name the scoped ByteQuay gate') > 0));

CREATE TEMP TABLE remote_repair_normalizer_launch_candidate_v324 (
    normalization_operation_row_id TEXT NOT NULL PRIMARY KEY,
    normalization_due_id            TEXT NOT NULL UNIQUE,
    normalization_task_turn_id      TEXT NOT NULL UNIQUE,
    dispatch_ticket_id              TEXT NOT NULL UNIQUE,
    agent_execution_id              TEXT NOT NULL UNIQUE,
    ci_repair_episode_id            TEXT NOT NULL,
    blocker_id                      TEXT NOT NULL,
    task_id                         TEXT NOT NULL,
    source_code_subject_revision    INTEGER NOT NULL,
    source_code_subject_kind        TEXT NOT NULL,
    source_code_subject_id          TEXT NOT NULL,
    expected_code_fingerprint       TEXT NOT NULL,
    expected_head_sha               TEXT NOT NULL,
    expected_base_sha               TEXT NOT NULL,
    ticket_version                  INTEGER NOT NULL,
    rerun_count                     INTEGER NOT NULL,
    rerun_limit                     INTEGER NOT NULL,
    fix_attempt_count               INTEGER NOT NULL,
    fix_attempt_limit               INTEGER NOT NULL,
    delivery_retry_count            INTEGER NOT NULL,
    delivery_retry_limit            INTEGER NOT NULL,
    push_count                      INTEGER NOT NULL,
    push_limit                      INTEGER NOT NULL
);

INSERT INTO remote_repair_normalizer_launch_candidate_v324
WITH lineage AS (
    SELECT operation.*,
           due.id AS due_id,
           due.blocker_id AS blocker_id,
           due.status AS due_status,
           due.normalization_operation_row_id AS due_operation_row_id,
           due.consumed_at_ms AS due_consumed_at_ms,
           due.recorded_at_ms AS due_recorded_at_ms,
           due.required_result_shape,
           due.candidate_capture_kind,
           due.candidate_code_fingerprint,
           due.candidate_head_sha,
           due.candidate_parent_sha,
           due.candidate_base_sha,
           due.candidate_clean,
           due.candidate_merge_base_sha,
           due.candidate_source_tree_sha,
           due.candidate_result_tree_sha,
           due.candidate_source_head_merge_base_sha,
           due.candidate_branch_name,
           due.source_malformed_output,
           due.source_raw_result_digest,
           due.source_execution_started_at_ms,
           due.source_execution_finished_at_ms,
           eligibility.id AS eligibility_id,
           eligibility.recorded_at_ms AS eligibility_recorded_at_ms,
           eligibility.ticket_window,
           eligibility.legacy_output_subject_shape,
           episode.status AS episode_status,
           episode.classification AS episode_classification,
           episode.fix_attempt_count AS episode_fix_attempt_count,
           episode.fix_attempt_limit AS episode_fix_attempt_limit,
           episode.rerun_count AS episode_rerun_count,
           episode.rerun_limit AS episode_rerun_limit,
           episode.delivery_retry_count AS episode_delivery_retry_count,
           episode.delivery_retry_limit AS episode_delivery_retry_limit,
           episode.push_count AS episode_push_count,
           episode.push_limit AS episode_push_limit,
           blocker.status AS blocker_status,
           turn.status AS turn_status,
           turn.requested_at_ms AS turn_requested_at_ms,
           turn.started_at_ms AS turn_started_at_ms,
           turn.finished_at_ms AS turn_finished_at_ms,
           turn.error_message AS turn_error_message,
           turn.launch_input,
           ticket.version AS ticket_version,
           ticket.status AS ticket_status,
           ticket.infrastructure_attempts,
           ticket.claim_purpose,
           ticket.claim_owner,
           ticket.capacity_lease_id,
           ticket.claim_expires_at_ms,
           ticket.next_attempt_at_ms,
           ticket.cancel_requested_at_ms,
           ticket.started_at_ms AS ticket_started_at_ms,
           ticket.pending_result_outcome,
           ticket.pending_result_payload,
           ticket.pending_result_evidence,
           ticket.pending_result_error,
           ticket.delivery_acceptance,
           ticket.delivery_evidence,
           ticket.created_at_ms AS ticket_created_at_ms,
           ticket.completed_at_ms AS ticket_completed_at_ms,
           ticket.last_error AS ticket_last_error,
           ticket.pending_result_task_epoch,
           ticket.pending_result_stage_id,
           ticket.pending_result_stage_generation,
           ticket.pending_result_operation_id,
           ticket.pending_result_attempt,
           ticket.pending_result_expected_code_fingerprint,
           ticket.pending_result_expected_head_sha,
           ticket.pending_result_expected_base_sha,
           execution.id AS execution_id,
           execution.provider AS execution_provider,
           execution.provider_session_id,
           execution.process_pid,
           execution.log_ref,
           execution.status AS execution_status,
           execution.started_at_ms AS execution_started_at_ms,
           execution.heartbeat_at_ms AS execution_heartbeat_at_ms,
           execution.finished_at_ms AS execution_finished_at_ms,
           execution.raw_result,
           execution.error_class AS execution_error_class,
           execution.error_message AS execution_error_message,
           execution.cost_usd_milli,
           execution.tokens_in,
           execution.tokens_out,
           'invalid frozen Agent Turn launch input: Cannot construct instance of `com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession$OwnerToolEndpoint`, problem: approvalPromptTool must name the scoped ByteQuay gate'
             || char(10)
             || ' at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: '
             || (length(turn.launch_input) - 1)
             || '] (through reference chain: com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler$LaunchInput["toolEndpoint"])'
               AS expected_launch_error
    FROM remote_repair_normalizer_launch_suspect_v324 suspect
    JOIN remote_repair_result_normalization_operation_v322 operation
      ON operation.id = suspect.normalization_operation_row_id
    JOIN remote_repair_result_normalization_due_v322 due
      ON due.id = operation.normalization_due_id
    JOIN remote_repair_legacy_eligibility_v322 eligibility
      ON eligibility.source_operation_row_id = due.source_operation_row_id
    JOIN ci_repair_episode episode
      ON episode.id = operation.ci_repair_episode_id
    JOIN task_blocker blocker ON blocker.id = due.blocker_id
    JOIN tasks task ON task.id = operation.task_id
    JOIN threads trunk ON trunk.id = task.thread_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN remote_development_stage remote
      ON remote.stage_id = operation.remote_development_stage_id
    JOIN task_current_code_subject_fence_v322 code ON code.task_id = task.id
    JOIN accepted_development_report_code_subject_v323 report
      ON report.subject_id = operation.source_code_subject_id
    JOIN ci_repair_operation source
      ON source.id = operation.source_operation_row_id
    JOIN stage_turn source_turn ON source_turn.id = operation.source_stage_turn_id
    JOIN dispatch_ticket source_ticket
      ON source_ticket.id = due.source_dispatch_ticket_id
    JOIN task_turn turn ON turn.id = operation.normalization_task_turn_id
    JOIN dispatch_ticket ticket ON ticket.id = operation.dispatch_ticket_id
    JOIN agent_execution execution
      ON execution.ticket_id = ticket.id
     AND execution.infrastructure_attempt = 1
    WHERE due.id = 'v323-normalization-due:' || due.source_operation_row_id
      AND eligibility.id =
          'v323-legacy-eligibility:' || due.source_operation_row_id
      AND eligibility.ci_repair_episode_id = due.ci_repair_episode_id
      AND eligibility.source_operation_id = due.source_operation_id
      AND eligibility.source_stage_turn_id = due.source_stage_turn_id
      AND eligibility.source_dispatch_ticket_id = due.source_dispatch_ticket_id
      AND eligibility.source_agent_execution_id = due.source_agent_execution_id
      AND eligibility.source_base_repair_authorization_id IS
          due.source_base_repair_authorization_id
      AND eligibility.blocker_id = due.blocker_id
      AND eligibility.task_id = due.task_id
      AND eligibility.task_epoch = due.task_epoch
      AND eligibility.remote_development_stage_id =
          due.remote_development_stage_id
      AND eligibility.stage_generation = due.stage_generation
      AND eligibility.semantic_attempt = due.semantic_attempt
      AND eligibility.execution_attempt = due.execution_attempt
      AND eligibility.source_code_subject_revision =
          due.source_code_subject_revision
      AND eligibility.source_code_subject_kind = due.source_code_subject_kind
      AND eligibility.source_code_subject_id = due.source_code_subject_id
      AND eligibility.expected_code_fingerprint =
          due.expected_code_fingerprint
      AND eligibility.expected_head_sha = due.expected_head_sha
      AND eligibility.expected_base_sha = due.expected_base_sha
      AND eligibility.source_malformed_output = due.source_malformed_output
      AND eligibility.source_raw_result_digest = due.source_raw_result_digest
      AND eligibility.source_execution_started_at_ms =
          due.source_execution_started_at_ms
      AND eligibility.source_execution_finished_at_ms =
          due.source_execution_finished_at_ms
      AND eligibility.recorded_at_ms = due.recorded_at_ms
      AND due.status = 'DISPATCHED'
      AND due.normalization_operation_row_id = operation.id
      AND due.consumed_at_ms = operation.requested_at_ms
      AND due.required_result_shape =
          '{"schemaVersion":1,"summary":"string"}'
      AND due.candidate_capture_kind = 'LEGACY_REFLOG_WINDOW_V1'
      AND due.candidate_code_fingerprint IS NULL
      AND due.candidate_head_sha IS NULL
      AND due.candidate_parent_sha IS NULL
      AND due.candidate_base_sha IS NULL
      AND due.candidate_clean IS NULL
      AND due.candidate_merge_base_sha IS NULL
      AND due.candidate_source_tree_sha IS NULL
      AND due.candidate_result_tree_sha IS NULL
      AND due.candidate_source_head_merge_base_sha IS NULL
      AND due.candidate_branch_name IS NULL
      AND operation.source_operation_row_id = due.source_operation_row_id
      AND operation.source_operation_id = due.source_operation_id
      AND operation.source_stage_turn_id = due.source_stage_turn_id
      AND operation.ci_repair_episode_id = due.ci_repair_episode_id
      AND operation.task_id = due.task_id
      AND operation.task_epoch = due.task_epoch
      AND operation.remote_development_stage_id =
          due.remote_development_stage_id
      AND operation.stage_generation = due.stage_generation
      AND operation.semantic_attempt = due.semantic_attempt
      AND operation.source_execution_attempt = due.execution_attempt
      AND operation.normalization_attempt = due.execution_attempt + 1
      AND operation.source_code_subject_revision =
          due.source_code_subject_revision
      AND operation.source_code_subject_kind = due.source_code_subject_kind
      AND operation.source_code_subject_id = due.source_code_subject_id
      AND operation.expected_code_fingerprint =
          due.expected_code_fingerprint
      AND operation.expected_head_sha = due.expected_head_sha
      AND operation.expected_base_sha = due.expected_base_sha
      AND episode.remote_development_stage_id =
          operation.remote_development_stage_id
      AND episode.task_id = operation.task_id
      AND episode.task_epoch = operation.task_epoch
      AND episode.stage_generation = operation.stage_generation
      AND episode.status = 'FIXING'
      AND episode.fix_attempt_count + 1 = operation.semantic_attempt
      AND blocker.task_id = operation.task_id
      AND blocker.stage_id = operation.remote_development_stage_id
      AND blocker.owner_kind = 'EPISODE'
      AND blocker.owner_id = episode.id
      AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
      AND blocker.status = 'OPEN'
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = operation.task_epoch
      AND current.stage_id = operation.remote_development_stage_id
      AND current.stage_generation = operation.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = operation.stage_generation
      AND owner.completed_at_ms IS NULL
      AND remote.task_id = operation.task_id
      AND remote.generation = operation.stage_generation
      AND code.task_epoch = operation.task_epoch
      AND code.source_code_subject_revision =
          operation.source_code_subject_revision
      AND code.source_code_subject_kind = 'DEVELOPMENT_REPORT'
      AND code.source_code_subject_id = operation.source_code_subject_id
      AND code.code_fingerprint = operation.expected_code_fingerprint
      AND code.head_sha = operation.expected_head_sha
      AND code.base_sha = operation.expected_base_sha
      AND report.task_id = operation.task_id
      AND report.task_epoch = operation.task_epoch
      AND report.code_fingerprint = operation.expected_code_fingerprint
      AND report.head_sha = operation.expected_head_sha
      AND report.base_sha = operation.expected_base_sha
      AND source.ci_repair_episode_id = operation.ci_repair_episode_id
      AND source.remote_development_stage_id =
          operation.remote_development_stage_id
      AND source.task_id = operation.task_id
      AND source.task_epoch = operation.task_epoch
      AND source.stage_generation = operation.stage_generation
      AND source.kind = 'FIX_STAGE_TURN'
      AND source.operation_id = operation.source_operation_id
      AND source.semantic_attempt = operation.semantic_attempt
      AND source.stage_turn_id = operation.source_stage_turn_id
      AND source.expected_code_fingerprint =
          operation.expected_code_fingerprint
      AND source.expected_head_sha = operation.expected_head_sha
      AND source.expected_base_sha = operation.expected_base_sha
      AND source.status = 'FAILED'
      AND source.completed_at_ms = due.recorded_at_ms
      AND source_turn.stage_id = operation.remote_development_stage_id
      AND source_turn.stage_generation = operation.stage_generation
      AND source_turn.task_epoch = operation.task_epoch
      AND source_turn.purpose = 'REMOTE_CI_REPAIR'
      AND source_turn.status = 'FAILED'
      AND source_turn.operation_id = operation.source_operation_id
      AND source_turn.attempt = operation.source_execution_attempt
      AND source_turn.expected_code_fingerprint =
          operation.expected_code_fingerprint
      AND source_turn.expected_head_sha = operation.expected_head_sha
      AND source_turn.expected_base_sha = operation.expected_base_sha
      AND operation.status = 'FAILED'
      AND operation.raw_outcome = 'FAILED'
      AND operation.normalization_raw_result_digest IS NOT NULL
      AND length(operation.normalization_raw_result_digest) = 64
      AND operation.normalization_raw_result_digest =
          lower(operation.normalization_raw_result_digest)
      AND operation.normalization_raw_result_digest NOT GLOB '*[^0-9a-f]*'
      AND operation.normalized_payload IS NULL
      AND operation.normalized_payload_digest IS NULL
      AND operation.acceptance = 'ACCEPTED'
      AND operation.completed_at_ms IS NOT NULL
      AND operation.error_message IS NOT NULL
      AND json_valid(operation.terminal_evidence)
      AND json_type(operation.terminal_evidence) = 'object'
      AND (SELECT COUNT(*) FROM json_each(operation.terminal_evidence)) = 9
      AND json_extract(operation.terminal_evidence, '$.schemaVersion') = 1
      AND json_extract(operation.terminal_evidence,
          '$.normalizationDueId') = operation.normalization_due_id
      AND json_extract(operation.terminal_evidence,
          '$.normalizationTurnId') = operation.normalization_task_turn_id
      AND json_extract(operation.terminal_evidence,
          '$.normalizationOperationId') = operation.operation_id
      AND json_extract(operation.terminal_evidence,
          '$.sourceOperationId') = operation.source_operation_id
      AND json_extract(operation.terminal_evidence,
          '$.sourceCodeSubjectRevision') =
          operation.source_code_subject_revision
      AND json_extract(operation.terminal_evidence,
          '$.sourceCodeSubjectKind') = operation.source_code_subject_kind
      AND json_extract(operation.terminal_evidence,
          '$.sourceCodeSubjectId') = operation.source_code_subject_id
      AND json_extract(operation.terminal_evidence,
          '$.rawResultDigest') = operation.normalization_raw_result_digest
      AND turn.task_id = operation.task_id
      AND turn.task_epoch = operation.task_epoch
      AND turn.trigger_stage_id = operation.remote_development_stage_id
      AND turn.trigger_stage_generation = operation.stage_generation
      AND turn.purpose = 'REMOTE_REPAIR_RESULT_NORMALIZATION'
      AND turn.status = 'FAILED'
      AND turn.operation_id = operation.operation_id
      AND turn.attempt = operation.normalization_attempt
      AND turn.expected_code_fingerprint =
          operation.expected_code_fingerprint
      AND turn.expected_head_sha = operation.expected_head_sha
      AND turn.expected_base_sha = operation.expected_base_sha
      AND turn.delivery_lane = source_turn.delivery_lane
      AND turn.requested_at_ms = operation.requested_at_ms
      AND turn.started_at_ms IS NOT NULL
      AND turn.finished_at_ms = operation.completed_at_ms
      AND json_valid(turn.launch_input)
      AND json_type(turn.launch_input, '$.toolEndpoint') = 'object'
      AND (SELECT COUNT(*) FROM json_each(
          json_extract(turn.launch_input, '$.toolEndpoint'))) = 6
      AND json_extract(turn.launch_input,
          '$.toolEndpoint.serverName') = 'bytequay'
      AND json_extract(turn.launch_input,
          '$.toolEndpoint.ownerKind') = 'TASK_TURN'
      AND json_extract(turn.launch_input,
          '$.toolEndpoint.ownerId') = turn.id
      AND json_extract(turn.launch_input,
          '$.toolEndpoint.operationId') = turn.operation_id
      AND json_extract(turn.launch_input,
          '$.toolEndpoint.profile') = 'TASK_BRAIN_READ_ONLY'
      AND json_type(turn.launch_input,
          '$.toolEndpoint.approvalPromptTool') IS NULL
      AND source_ticket.workspace_id = trunk.workspace_id
      AND source_ticket.trunk_id = task.thread_id
      AND ticket.operation_id = operation.operation_id
      AND ticket.operation_kind = 'EXECUTE_TASK_TURN'
      AND ticket.async_family = 'AGENT_TURN'
      AND ticket.owner_kind = 'TASK_TURN'
      AND ticket.owner_id = operation.normalization_task_turn_id
      AND ticket.callback_route =
          'REMOTE_REPAIR_RESULT_NORMALIZATION_RESULT'
      AND ticket.lane_mask = source_ticket.lane_mask
      AND ticket.trunk_control = 0
      AND ticket.exclusive_task = 1
      AND ticket.writer_required = 0
      AND ticket.workspace_id = trunk.workspace_id
      AND ticket.trunk_id = task.thread_id
      AND ticket.task_id = operation.task_id
      AND ticket.task_epoch = operation.task_epoch
      AND ticket.stage_id = operation.remote_development_stage_id
      AND ticket.stage_generation = operation.stage_generation
      AND ticket.attempt = operation.normalization_attempt
      AND ticket.expected_code_fingerprint =
          operation.expected_code_fingerprint
      AND ticket.expected_head_sha = operation.expected_head_sha
      AND ticket.expected_base_sha = operation.expected_base_sha
      AND ticket.version = 4
      AND ticket.status = 'FAILED'
      AND ticket.claim_purpose IS NULL
      AND ticket.claim_owner IS NULL
      AND ticket.capacity_lease_id IS NULL
      AND ticket.claim_expires_at_ms IS NULL
      AND ticket.next_attempt_at_ms IS NOT NULL
      AND ticket.cancel_requested_at_ms IS NULL
      AND ticket.infrastructure_attempts = 1
      AND ticket.started_at_ms IS NOT NULL
      AND ticket.pending_result_outcome IS NULL
      AND ticket.pending_result_payload IS NULL
      AND ticket.pending_result_evidence IS NULL
      AND ticket.pending_result_error IS NULL
      AND ticket.delivery_acceptance = 'ACCEPTED'
      AND ticket.delivery_evidence =
          'ACCEPTED:Remote repair result normalization failed'
      AND ticket.created_at_ms = operation.requested_at_ms
      AND ticket.completed_at_ms IS NOT NULL
      AND ticket.last_error IS NOT NULL
      AND ticket.pending_result_task_epoch IS NULL
      AND ticket.pending_result_stage_id IS NULL
      AND ticket.pending_result_stage_generation IS NULL
      AND ticket.pending_result_operation_id IS NULL
      AND ticket.pending_result_attempt IS NULL
      AND ticket.pending_result_expected_code_fingerprint IS NULL
      AND ticket.pending_result_expected_head_sha IS NULL
      AND ticket.pending_result_expected_base_sha IS NULL
      AND execution.status = 'FAILED'
      AND execution.provider IS NULL
      AND execution.provider_session_id IS NULL
      AND execution.process_pid IS NULL
      AND execution.log_ref IS NULL
      AND execution.started_at_ms IS NOT NULL
      AND execution.heartbeat_at_ms IS NOT NULL
      AND execution.finished_at_ms IS NOT NULL
      AND execution.started_at_ms <= execution.heartbeat_at_ms
      AND execution.heartbeat_at_ms <= execution.finished_at_ms
      AND execution.error_class IS NULL
      AND execution.error_message IS NULL
      AND execution.cost_usd_milli = 0
      AND execution.tokens_in = 0
      AND execution.tokens_out = 0
      AND (SELECT COUNT(*) FROM agent_execution candidate_execution
          WHERE candidate_execution.ticket_id = ticket.id) = 1
      AND NOT EXISTS (
          SELECT 1 FROM agent_execution_process_attempt process_attempt
          WHERE process_attempt.execution_id = execution.id)
      AND NOT EXISTS (
          SELECT 1 FROM agent_execution_log log
          WHERE log.execution_id = execution.id)
      AND NOT EXISTS (
          SELECT 1 FROM capacity_lease lease
          WHERE (lease.ticket_id = ticket.id
              OR lease.operation_id = operation.operation_id)
            AND lease.released_at_ms IS NULL)
      AND NOT EXISTS (
          SELECT 1 FROM dispatch_delivery_claim claim
          WHERE claim.ticket_id = ticket.id)
      AND NOT EXISTS (
          SELECT 1 FROM ci_base_repair_reauthorization_v322 reauthorization
          WHERE reauthorization.normalization_due_id = due.id
             OR reauthorization.normalization_operation_row_id = operation.id)
      AND NOT EXISTS (
          SELECT 1
          FROM remote_repair_commit_adoption_operation_v322 adoption
          WHERE adoption.normalization_due_id = due.id
             OR adoption.normalization_operation_row_id = operation.id)
      AND NOT EXISTS (
          SELECT 1
          FROM remote_repair_result_normalization_operation_v322 live
          WHERE live.task_id = operation.task_id
            AND live.id <> operation.id
            AND live.status IN ('REQUESTED', 'DISPATCHED'))
), decoded AS (
    SELECT lineage.*,
           CASE WHEN json_valid(raw_result)
               THEN json_extract(raw_result, '$.payloadJson') END
               AS payload_json,
           CASE WHEN json_valid(raw_result)
               THEN json_extract(raw_result, '$.evidenceJson') END
               AS evidence_json
    FROM lineage
), exact AS (
    SELECT decoded.*
    FROM decoded
    WHERE json_valid(raw_result)
      AND json_type(raw_result) = 'object'
      AND (SELECT COUNT(*) FROM json_each(raw_result)) = 5
      AND json_extract(raw_result, '$.outcome') = 'FAILED'
      AND json_extract(raw_result, '$.error') = expected_launch_error
      AND json_extract(raw_result, '$.fence.taskEpoch') = task_epoch
      AND json_extract(raw_result, '$.fence.stageId') =
          remote_development_stage_id
      AND json_extract(raw_result, '$.fence.stageGeneration') =
          stage_generation
      AND json_extract(raw_result, '$.fence.operationId') = operation_id
      AND json_extract(raw_result, '$.fence.attempt') = normalization_attempt
      AND json_extract(raw_result,
          '$.fence.expectedCodeFingerprint') = expected_code_fingerprint
      AND json_extract(raw_result,
          '$.fence.expectedHeadSha') = expected_head_sha
      AND json_extract(raw_result,
          '$.fence.expectedBaseSha') = expected_base_sha
      AND (SELECT COUNT(*) FROM json_each(
          json_extract(raw_result, '$.fence'))) = 8
      AND json_valid(payload_json)
      AND json_type(payload_json) = 'object'
      AND (SELECT COUNT(*) FROM json_each(payload_json)) = 16
      AND json_extract(payload_json, '$.schemaVersion') = 1
      AND json_extract(payload_json, '$.turnId') = normalization_task_turn_id
      AND json_extract(payload_json, '$.ownerKind') = 'TASK_TURN'
      AND json_extract(payload_json, '$.purpose') =
          'REMOTE_REPAIR_RESULT_NORMALIZATION'
      AND json_type(payload_json, '$.transport') = 'null'
      AND json_type(payload_json, '$.provider') = 'null'
      AND json_type(payload_json, '$.providerSessionId') = 'null'
      AND json_extract(payload_json, '$.finalText') = ''
      AND json_extract(payload_json, '$.inputTokens') = 0
      AND json_extract(payload_json, '$.outputTokens') = 0
      AND json_extract(payload_json, '$.costUsdMilli') = 0
      AND json_type(payload_json, '$.processPid') = 'null'
      AND json_extract(payload_json, '$.disposition') =
          'INVALID_LAUNCH_INPUT'
      AND json_extract(payload_json, '$.error') = expected_launch_error
      AND json_type(payload_json, '$.userWait') = 'null'
      AND json_type(payload_json, '$.outputCodeSubject') = 'null'
      AND json_valid(evidence_json)
      AND json_type(evidence_json) = 'object'
      AND (SELECT COUNT(*) FROM json_each(evidence_json)) = 6
      AND json_extract(evidence_json, '$.schemaVersion') = 1
      AND json_extract(evidence_json, '$.disposition') =
          'INVALID_LAUNCH_INPUT'
      AND json_type(evidence_json, '$.launchInputDigest') = 'text'
      AND length(json_extract(evidence_json, '$.launchInputDigest')) = 64
      AND json_extract(evidence_json, '$.launchInputDigest') =
          lower(json_extract(evidence_json, '$.launchInputDigest'))
      AND json_extract(evidence_json, '$.launchInputDigest')
          NOT GLOB '*[^0-9a-f]*'
      AND json_type(evidence_json, '$.writerFence') = 'null'
      AND json_extract(evidence_json, '$.detail') = expected_launch_error
      AND json_type(evidence_json, '$.outputCodeSubject') = 'null'
      AND error_message = expected_launch_error
      AND turn_error_message = expected_launch_error
      AND ticket_last_error = expected_launch_error
      AND operation_id = json_extract(raw_result, '$.fence.operationId')
)
SELECT id, due_id, normalization_task_turn_id, dispatch_ticket_id,
       execution_id, ci_repair_episode_id, blocker_id, task_id,
       source_code_subject_revision, source_code_subject_kind,
       source_code_subject_id, expected_code_fingerprint,
       expected_head_sha, expected_base_sha, ticket_version,
       episode_rerun_count, episode_rerun_limit,
       episode_fix_attempt_count, episode_fix_attempt_limit,
       episode_delivery_retry_count, episode_delivery_retry_limit,
       episode_push_count, episode_push_limit
FROM exact;

CREATE TEMP TABLE remote_repair_normalizer_launch_guard_v324 (
    ok INTEGER CHECK (ok = 1)
);

INSERT INTO remote_repair_normalizer_launch_guard_v324
SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM remote_repair_normalizer_launch_suspect_v324 suspect
    LEFT JOIN remote_repair_normalizer_launch_candidate_v324 candidate
      ON candidate.normalization_operation_row_id =
         suspect.normalization_operation_row_id
    WHERE candidate.normalization_operation_row_id IS NULL)
THEN 1 ELSE 0 END;

CREATE TEMP TABLE remote_repair_normalizer_execution_snapshot_v324 AS
SELECT execution.*
FROM agent_execution execution
JOIN remote_repair_normalizer_launch_candidate_v324 candidate
  ON candidate.agent_execution_id = execution.id;

DROP TRIGGER remote_repair_result_normalization_operation_terminal_v322;
DROP TRIGGER dispatch_ticket_terminal_immutable;

UPDATE remote_repair_result_normalization_operation_v322
SET status = 'REQUESTED',
    raw_outcome = NULL,
    normalization_raw_result_digest = NULL,
    normalized_payload = NULL,
    normalized_payload_digest = NULL,
    acceptance = NULL,
    terminal_evidence = NULL,
    completed_at_ms = NULL,
    error_message = NULL
WHERE id IN (
    SELECT normalization_operation_row_id
    FROM remote_repair_normalizer_launch_candidate_v324);

UPDATE remote_repair_normalizer_launch_guard_v324
SET ok = CASE WHEN changes() = (
    SELECT COUNT(*) FROM remote_repair_normalizer_launch_candidate_v324)
THEN 1 ELSE 0 END;

UPDATE task_turn
SET status = 'REQUESTED',
    started_at_ms = NULL,
    finished_at_ms = NULL,
    error_message = NULL
WHERE id IN (
    SELECT normalization_task_turn_id
    FROM remote_repair_normalizer_launch_candidate_v324);

UPDATE remote_repair_normalizer_launch_guard_v324
SET ok = CASE WHEN changes() = (
    SELECT COUNT(*) FROM remote_repair_normalizer_launch_candidate_v324)
THEN 1 ELSE 0 END;

UPDATE dispatch_ticket
SET version = version + 1,
    status = 'REQUESTED',
    claim_purpose = NULL,
    claim_owner = NULL,
    capacity_lease_id = NULL,
    claim_expires_at_ms = NULL,
    next_attempt_at_ms = NULL,
    cancel_requested_at_ms = NULL,
    started_at_ms = NULL,
    pending_result_outcome = NULL,
    pending_result_payload = NULL,
    pending_result_evidence = NULL,
    pending_result_error = NULL,
    delivery_acceptance = NULL,
    delivery_evidence = NULL,
    completed_at_ms = NULL,
    last_error = NULL,
    pending_result_task_epoch = NULL,
    pending_result_stage_id = NULL,
    pending_result_stage_generation = NULL,
    pending_result_operation_id = NULL,
    pending_result_attempt = NULL,
    pending_result_expected_code_fingerprint = NULL,
    pending_result_expected_head_sha = NULL,
    pending_result_expected_base_sha = NULL
WHERE id IN (
    SELECT dispatch_ticket_id
    FROM remote_repair_normalizer_launch_candidate_v324);

UPDATE remote_repair_normalizer_launch_guard_v324
SET ok = CASE WHEN changes() = (
    SELECT COUNT(*) FROM remote_repair_normalizer_launch_candidate_v324)
THEN 1 ELSE 0 END;

CREATE TRIGGER dispatch_ticket_terminal_immutable
BEFORE UPDATE ON dispatch_ticket
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
 AND NOT (
    OLD.status = 'CANCELED' AND NEW.status = 'REQUESTED'
    AND NEW.version = OLD.version + 1
    AND NEW.cancel_requested_at_ms IS NULL
    AND NEW.pending_result_outcome IS NULL
    AND NEW.delivery_acceptance IS NULL
    AND NEW.completed_at_ms IS NULL
    AND EXISTS (
        SELECT 1
        FROM stage_resume_async_successor_v272 resume
        JOIN stage_resume_rearm_intent_v257 intent
          ON intent.handoff_id = resume.handoff_id
        WHERE resume.status = 'PREPARED'
          AND resume.dispatch_ticket_id = OLD.id
          AND resume.operation_id = OLD.operation_id
          AND resume.dispatch_attempt = OLD.attempt
          AND intent.status = 'PENDING'
          AND intent.task_id = OLD.task_id
          AND intent.task_epoch = OLD.task_epoch
          AND intent.stage_id = OLD.stage_id
          AND intent.stage_generation = OLD.stage_generation
          AND ((resume.owner_kind = 'PUBLISH_RECOVERY'
                AND OLD.operation_kind = 'PUBLISH_LOCAL_DEVELOPMENT'
                AND OLD.async_family = 'GITHUB_EFFECT'
                AND OLD.callback_route = 'STAGE_PUBLISH_RESULT')
            OR (resume.owner_kind = 'MERGE_RECOVERY'
                AND OLD.operation_kind = 'MERGE_REMOTE_PULL_REQUEST'
                AND OLD.async_family = 'MERGE'
                AND OLD.callback_route = 'REMOTE_MERGE_RESULT'))))
BEGIN SELECT RAISE(ABORT, 'terminal DispatchTicket is immutable'); END;

CREATE TRIGGER remote_repair_result_normalization_operation_terminal_v322
BEFORE UPDATE OF status, raw_outcome, normalization_raw_result_digest,
    normalized_payload, normalized_payload_digest, acceptance,
    terminal_evidence, completed_at_ms, error_message
ON remote_repair_result_normalization_operation_v322
WHEN NEW.status <> 'DISPATCHED'
  AND (NEW.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
    OR OLD.status <> 'DISPATCHED'
    OR NOT EXISTS (
    SELECT 1
    FROM task_turn turn
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    JOIN agent_execution execution
      ON execution.ticket_id = ticket.id
     AND execution.infrastructure_attempt = ticket.infrastructure_attempts
    WHERE turn.id = NEW.normalization_task_turn_id
      AND turn.operation_id = NEW.operation_id
      AND turn.purpose = 'REMOTE_REPAIR_RESULT_NORMALIZATION'
      AND turn.status = NEW.status
      AND turn.task_id = NEW.task_id
      AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.remote_development_stage_id
      AND turn.trigger_stage_generation = NEW.stage_generation
      AND turn.attempt = NEW.normalization_attempt
      AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND turn.expected_head_sha = NEW.expected_head_sha
      AND turn.expected_base_sha = NEW.expected_base_sha
      AND ticket.operation_id = NEW.operation_id
      AND ticket.owner_kind = 'TASK_TURN'
      AND ticket.owner_id = NEW.normalization_task_turn_id
      AND ticket.callback_route = 'REMOTE_REPAIR_RESULT_NORMALIZATION_RESULT'
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND ticket.pending_result_task_epoch = NEW.task_epoch
      AND ticket.pending_result_stage_id = NEW.remote_development_stage_id
      AND ticket.pending_result_stage_generation = NEW.stage_generation
      AND ticket.pending_result_operation_id = NEW.operation_id
      AND ticket.pending_result_attempt = NEW.normalization_attempt
      AND ticket.pending_result_expected_code_fingerprint =
          NEW.expected_code_fingerprint
      AND ticket.pending_result_expected_head_sha = NEW.expected_head_sha
      AND ticket.pending_result_expected_base_sha = NEW.expected_base_sha
      AND execution.finished_at_ms IS NOT NULL
      AND execution.raw_result IS NOT NULL
      AND json_valid(execution.raw_result)
      AND json_type(execution.raw_result, '$.payloadJson') = 'text'
      AND json_valid(json_extract(execution.raw_result, '$.payloadJson'))
      AND json_extract(execution.raw_result, '$.payloadJson') =
          ticket.pending_result_payload
      AND json_extract(execution.raw_result, '$.outcome') = NEW.raw_outcome
      AND json_extract(execution.raw_result,
          '$.fence.operationId') = NEW.operation_id
      AND json_extract(execution.raw_result,
          '$.fence.attempt') = NEW.normalization_attempt
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.turnId') = NEW.normalization_task_turn_id
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.ownerKind') = 'TASK_TURN'
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.purpose') =
          'REMOTE_REPAIR_RESULT_NORMALIZATION'
      AND ((NEW.raw_outcome = 'SUCCEEDED'
                AND execution.status = 'SUCCEEDED')
        OR (NEW.raw_outcome = 'FAILED' AND execution.status = 'FAILED')
        OR (NEW.raw_outcome = 'CANCELED' AND execution.status = 'CANCELED')
        OR (NEW.raw_outcome = 'INDETERMINATE'
                AND execution.status = 'UNKNOWN'))
      AND json_type(NEW.terminal_evidence, '$.schemaVersion') = 'integer'
      AND json_extract(NEW.terminal_evidence, '$.schemaVersion') = 1
      AND json_extract(NEW.terminal_evidence, '$.normalizationDueId') =
          NEW.normalization_due_id
      AND json_extract(NEW.terminal_evidence, '$.normalizationTurnId') =
          NEW.normalization_task_turn_id
      AND json_extract(NEW.terminal_evidence, '$.normalizationOperationId') =
          NEW.operation_id
      AND json_extract(NEW.terminal_evidence, '$.sourceOperationId') =
          NEW.source_operation_id
      AND json_extract(NEW.terminal_evidence,
          '$.sourceCodeSubjectRevision') = NEW.source_code_subject_revision
      AND json_extract(NEW.terminal_evidence,
          '$.sourceCodeSubjectKind') = NEW.source_code_subject_kind
      AND json_extract(NEW.terminal_evidence,
          '$.sourceCodeSubjectId') = NEW.source_code_subject_id
      AND json_extract(NEW.terminal_evidence, '$.rawResultDigest') =
          NEW.normalization_raw_result_digest
      AND ((NEW.status = 'SUCCEEDED'
            AND json_type(json_extract(execution.raw_result,
                    '$.payloadJson'), '$.finalText') = 'text'
            AND json_extract(json_extract(execution.raw_result,
                    '$.payloadJson'), '$.finalText') = NEW.normalized_payload
            AND json_extract(NEW.terminal_evidence,
                    '$.normalizedPayload') = NEW.normalized_payload
            AND json_extract(NEW.terminal_evidence,
                    '$.normalizedPayloadDigest') =
                NEW.normalized_payload_digest
            AND json_valid(NEW.normalized_payload)
            AND json_type(NEW.normalized_payload) = 'object'
            AND json_type(NEW.normalized_payload, '$.schemaVersion') = 'integer'
            AND json_extract(NEW.normalized_payload, '$.schemaVersion') = 1
            AND json_type(NEW.normalized_payload, '$.summary') = 'text'
            AND length(trim(json_extract(
                NEW.normalized_payload, '$.summary'))) > 0
            AND (SELECT COUNT(*) FROM json_each(NEW.normalized_payload)) = 2)
        OR (NEW.status IN ('FAILED', 'CANCELED', 'SUPERSEDED')
            AND NEW.normalized_payload IS NULL
            AND NEW.normalized_payload_digest IS NULL))))
BEGIN SELECT RAISE(ABORT,
    'Remote repair result-normalization delivery is not exact'); END;

UPDATE remote_repair_result_normalization_operation_v322
SET status = 'DISPATCHED'
WHERE id IN (
    SELECT normalization_operation_row_id
    FROM remote_repair_normalizer_launch_candidate_v324);

UPDATE remote_repair_normalizer_launch_guard_v324
SET ok = CASE WHEN changes() = (
    SELECT COUNT(*) FROM remote_repair_normalizer_launch_candidate_v324)
THEN 1 ELSE 0 END;

DELETE FROM remote_repair_normalizer_launch_guard_v324;
INSERT INTO remote_repair_normalizer_launch_guard_v324
SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM remote_repair_normalizer_launch_candidate_v324 candidate
    JOIN remote_repair_result_normalization_operation_v322 operation
      ON operation.id = candidate.normalization_operation_row_id
    JOIN remote_repair_result_normalization_due_v322 due
      ON due.id = candidate.normalization_due_id
    JOIN task_turn turn
      ON turn.id = candidate.normalization_task_turn_id
    JOIN dispatch_ticket ticket
      ON ticket.id = candidate.dispatch_ticket_id
    JOIN ci_repair_episode episode
      ON episode.id = candidate.ci_repair_episode_id
    JOIN task_blocker blocker ON blocker.id = candidate.blocker_id
    JOIN task_current_code_subject_fence_v322 code
      ON code.task_id = candidate.task_id
    WHERE operation.status <> 'DISPATCHED'
       OR operation.raw_outcome IS NOT NULL
       OR operation.normalization_raw_result_digest IS NOT NULL
       OR operation.normalized_payload IS NOT NULL
       OR operation.normalized_payload_digest IS NOT NULL
       OR operation.acceptance IS NOT NULL
       OR operation.terminal_evidence IS NOT NULL
       OR operation.completed_at_ms IS NOT NULL
       OR operation.error_message IS NOT NULL
       OR due.status <> 'DISPATCHED'
       OR due.normalization_operation_row_id <> operation.id
       OR turn.status <> 'REQUESTED'
       OR turn.started_at_ms IS NOT NULL
       OR turn.finished_at_ms IS NOT NULL
       OR turn.error_message IS NOT NULL
       OR ticket.status <> 'REQUESTED'
       OR ticket.version <> candidate.ticket_version + 1
       OR ticket.infrastructure_attempts <> 1
       OR ticket.claim_purpose IS NOT NULL
       OR ticket.claim_owner IS NOT NULL
       OR ticket.capacity_lease_id IS NOT NULL
       OR ticket.claim_expires_at_ms IS NOT NULL
       OR ticket.next_attempt_at_ms IS NOT NULL
       OR ticket.cancel_requested_at_ms IS NOT NULL
       OR ticket.started_at_ms IS NOT NULL
       OR ticket.pending_result_outcome IS NOT NULL
       OR ticket.pending_result_payload IS NOT NULL
       OR ticket.pending_result_evidence IS NOT NULL
       OR ticket.pending_result_error IS NOT NULL
       OR ticket.delivery_acceptance IS NOT NULL
       OR ticket.delivery_evidence IS NOT NULL
       OR ticket.completed_at_ms IS NOT NULL
       OR ticket.last_error IS NOT NULL
       OR ticket.pending_result_task_epoch IS NOT NULL
       OR ticket.pending_result_stage_id IS NOT NULL
       OR ticket.pending_result_stage_generation IS NOT NULL
       OR ticket.pending_result_operation_id IS NOT NULL
       OR ticket.pending_result_attempt IS NOT NULL
       OR ticket.pending_result_expected_code_fingerprint IS NOT NULL
       OR ticket.pending_result_expected_head_sha IS NOT NULL
       OR ticket.pending_result_expected_base_sha IS NOT NULL
       OR episode.rerun_count <> candidate.rerun_count
       OR episode.rerun_limit <> candidate.rerun_limit
       OR episode.fix_attempt_count <> candidate.fix_attempt_count
       OR episode.fix_attempt_limit <> candidate.fix_attempt_limit
       OR episode.delivery_retry_count <> candidate.delivery_retry_count
       OR episode.delivery_retry_limit <> candidate.delivery_retry_limit
       OR episode.push_count <> candidate.push_count
       OR episode.push_limit <> candidate.push_limit
       OR blocker.status <> 'OPEN'
       OR code.task_epoch <> operation.task_epoch
       OR code.source_code_subject_revision <>
          candidate.source_code_subject_revision
       OR code.source_code_subject_kind <>
          candidate.source_code_subject_kind
       OR code.source_code_subject_id <> candidate.source_code_subject_id
       OR code.code_fingerprint <> candidate.expected_code_fingerprint
       OR code.head_sha <> candidate.expected_head_sha
       OR code.base_sha <> candidate.expected_base_sha)
  AND NOT EXISTS (
      SELECT *
      FROM remote_repair_normalizer_execution_snapshot_v324
      EXCEPT
      SELECT execution.*
      FROM agent_execution execution
      JOIN remote_repair_normalizer_launch_candidate_v324 candidate
        ON candidate.agent_execution_id = execution.id)
  AND NOT EXISTS (
      SELECT execution.*
      FROM agent_execution execution
      JOIN remote_repair_normalizer_launch_candidate_v324 candidate
        ON candidate.agent_execution_id = execution.id
      EXCEPT
      SELECT *
      FROM remote_repair_normalizer_execution_snapshot_v324)
  AND NOT EXISTS (SELECT 1 FROM pragma_foreign_key_check)
THEN 1 ELSE 0 END;

DELETE FROM remote_repair_normalizer_launch_guard_v324;
INSERT INTO remote_repair_normalizer_launch_guard_v324
SELECT CASE WHEN EXISTS (
    SELECT 1 FROM sqlite_master
    WHERE type = 'trigger'
      AND name = 'dispatch_ticket_terminal_immutable')
  AND EXISTS (
    SELECT 1 FROM sqlite_master
    WHERE type = 'trigger'
      AND name =
          'remote_repair_result_normalization_operation_terminal_v322')
THEN 1 ELSE 0 END;

DROP TABLE remote_repair_normalizer_execution_snapshot_v324;
DROP TABLE remote_repair_normalizer_launch_candidate_v324;
DROP TABLE remote_repair_normalizer_launch_suspect_v324;

DELETE FROM remote_repair_normalizer_launch_guard_v324;
INSERT INTO remote_repair_normalizer_launch_guard_v324
SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM sqlite_temp_master
    WHERE name IN (
        'remote_repair_normalizer_execution_snapshot_v324',
        'remote_repair_normalizer_launch_candidate_v324',
        'remote_repair_normalizer_launch_suspect_v324'))
THEN 1 ELSE 0 END;
DROP TABLE remote_repair_normalizer_launch_guard_v324;

RELEASE remote_repair_normalizer_launch_rearm_v324;
