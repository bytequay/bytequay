-- A malformed Remote CI StageTurn result is not decoded heuristically.  This
-- intent freezes the exact rejected output and, for new executions, the
-- append-only commit candidate captured while the writer fence was live.
-- V322 compatibility rows predate that capture and are the sole rows allowed
-- to discover one candidate from the immutable provider-execution window.
CREATE VIEW task_current_code_subject_fence_v322 AS
SELECT task.id AS task_id,
       task.epoch AS task_epoch,
       revision.revision AS source_code_subject_revision,
       revision.subject_kind AS source_code_subject_kind,
       revision.subject_id AS source_code_subject_id,
       revision.code_fingerprint,
       revision.head_sha,
       revision.base_sha
FROM tasks task
JOIN task_current_code_subject_v230 code ON code.task_id = task.id
JOIN task_code_subject_revision_v320 revision
  ON revision.revision = (
    SELECT candidate.revision
    FROM task_code_subject_revision_v320 candidate
    WHERE candidate.task_id = task.id
      AND candidate.task_epoch = task.epoch
      AND (candidate.subject_kind <> 'LOCAL_BASE_SYNC' OR EXISTS (
          SELECT 1
          FROM local_publish_base_sync_operation operation
          JOIN local_publish_base_sync_episode episode
            ON episode.id = operation.episode_id
          JOIN local_publish_base_sync_delivery_receipt receipt
            ON receipt.operation_row_id = operation.id
          WHERE operation.id = candidate.subject_id
            AND episode.task_id = candidate.task_id
            AND episode.task_epoch = candidate.task_epoch
            AND operation.kind = 'MECHANICAL_REBASE'
            AND operation.status = 'SUCCEEDED'
            AND operation.result_disposition = 'REBASED'
            AND receipt.acceptance IN ('ACCEPTED', 'PARKED')
            AND NOT EXISTS (
                SELECT 1
                FROM local_stage_turn_request request
                JOIN dev_report completed
                  ON completed.stage_turn_id = request.stage_turn_id
                WHERE request.base_sync_episode_id = episode.id)))
    ORDER BY candidate.revision DESC LIMIT 1)
WHERE task.workflow_version = 'V2'
  AND revision.code_fingerprint = code.code_fingerprint
  AND revision.head_sha = code.head_sha
  AND revision.base_sha = code.base_sha;

-- Runtime cannot mint LEGACY_REFLOG_WINDOW_V1 authority.  The migration
-- materializes the exact pre-V322 rows once, before the insert blocker is
-- installed, so malformed or incomplete historical rows remain manual.
CREATE TABLE remote_repair_legacy_eligibility_v322 (
    id                              TEXT    NOT NULL PRIMARY KEY,
    source_operation_row_id         TEXT    NOT NULL UNIQUE
        REFERENCES ci_repair_operation(id) ON DELETE CASCADE,
    ci_repair_episode_id            TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    source_operation_id             TEXT    NOT NULL UNIQUE,
    source_stage_turn_id            TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    source_dispatch_ticket_id       TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    source_agent_execution_id       TEXT    NOT NULL UNIQUE REFERENCES agent_execution(id),
    source_base_repair_authorization_id TEXT
        REFERENCES ci_base_repair_authorization_v303(id),
    blocker_id                      TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    task_id                         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id     TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation                INTEGER NOT NULL CHECK (stage_generation > 0),
    semantic_attempt                INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt               INTEGER NOT NULL CHECK (execution_attempt > 0),
    source_code_subject_revision    INTEGER NOT NULL
        REFERENCES task_code_subject_revision_v320(revision),
    source_code_subject_kind        TEXT    NOT NULL CHECK (
        source_code_subject_kind IN (
            'REMOTE_WORKTREE', 'REMOTE_STEERING', 'CI_BASE_REPAIR',
            'LOCAL_BASE_SYNC')),
    source_code_subject_id          TEXT    NOT NULL,
    expected_code_fingerprint       TEXT    NOT NULL,
    expected_head_sha               TEXT    NOT NULL,
    expected_base_sha               TEXT    NOT NULL,
    source_malformed_output         TEXT    NOT NULL,
    source_raw_result_digest        TEXT    NOT NULL CHECK (
        length(source_raw_result_digest) = 64),
    ticket_window                   TEXT    NOT NULL CHECK (ticket_window IN (
        'FAILED_DELIVERED', 'RESULT_PENDING_AFTER_OWNER_DELIVERY')),
    legacy_output_subject_shape     TEXT    NOT NULL CHECK (
        legacy_output_subject_shape IN (
            'NULL_V1', 'PRE_V322_PARTIAL_V1')),
    source_execution_started_at_ms  INTEGER NOT NULL CHECK (
        source_execution_started_at_ms >= 0),
    source_execution_finished_at_ms INTEGER NOT NULL CHECK (
        source_execution_finished_at_ms >= source_execution_started_at_ms),
    recorded_at_ms                  INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_id)) > 0
        AND length(trim(source_code_subject_id)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0
        AND length(trim(source_malformed_output,
            char(9) || char(10) || char(13) || ' ')) > 0)
);

INSERT INTO remote_repair_legacy_eligibility_v322(
    id, source_operation_row_id, ci_repair_episode_id,
    source_operation_id, source_stage_turn_id, source_dispatch_ticket_id,
    source_agent_execution_id, source_base_repair_authorization_id,
    blocker_id, task_id, task_epoch, remote_development_stage_id,
    stage_generation, semantic_attempt, execution_attempt,
    source_code_subject_revision, source_code_subject_kind,
    source_code_subject_id, expected_code_fingerprint, expected_head_sha,
    expected_base_sha, source_malformed_output, source_raw_result_digest,
    ticket_window, legacy_output_subject_shape,
    source_execution_started_at_ms, source_execution_finished_at_ms,
    recorded_at_ms)
SELECT 'v322-legacy-eligibility:' || source.id,
       source.id, episode.id, source.operation_id, turn.id, ticket.id,
       execution.id, source.base_repair_authorization_id, blocker.id,
       source.task_id, source.task_epoch,
       source.remote_development_stage_id, source.stage_generation,
       source.semantic_attempt, turn.attempt,
       code.source_code_subject_revision, code.source_code_subject_kind,
       code.source_code_subject_id, source.expected_code_fingerprint,
       source.expected_head_sha, source.expected_base_sha,
       json_extract(json_extract(execution.raw_result,
           '$.payloadJson'), '$.finalText'),
       delivery.raw_result_digest,
       CASE ticket.status WHEN 'FAILED' THEN 'FAILED_DELIVERED'
           ELSE 'RESULT_PENDING_AFTER_OWNER_DELIVERY' END,
       CASE COALESCE(json_type(json_extract(execution.raw_result,
               '$.payloadJson'), '$.outputCodeSubject'), 'null')
           WHEN 'null' THEN 'NULL_V1' ELSE 'PRE_V322_PARTIAL_V1' END,
       execution.started_at_ms, execution.finished_at_ms,
       source.completed_at_ms
FROM ci_repair_operation source
JOIN ci_repair_episode episode
  ON episode.id = source.ci_repair_episode_id
JOIN stage_turn turn ON turn.id = source.stage_turn_id
JOIN dispatch_ticket ticket ON ticket.operation_id = source.operation_id
JOIN agent_execution execution
  ON execution.ticket_id = ticket.id
 AND execution.infrastructure_attempt = ticket.infrastructure_attempts
JOIN ci_repair_delivery_receipt delivery
  ON delivery.ci_repair_operation_id = source.id
JOIN task_blocker blocker
  ON blocker.owner_kind = 'EPISODE'
 AND blocker.owner_id = episode.id
JOIN tasks task ON task.id = source.task_id
JOIN task_current_stage current ON current.task_id = task.id
JOIN stage owner ON owner.id = current.stage_id
JOIN remote_development_stage remote ON remote.stage_id = owner.id
JOIN task_current_code_subject_fence_v322 code ON code.task_id = task.id
JOIN task_code_identity identity ON identity.task_id = task.id
LEFT JOIN ci_base_repair_authorization_v303 authorization
  ON authorization.id = source.base_repair_authorization_id
WHERE source.kind = 'FIX_STAGE_TURN'
  AND source.status = 'FAILED'
  AND source.completed_at_ms IS NOT NULL
  AND source.error_message LIKE 'OWNER_OUTPUT_MALFORMED:%'
  AND turn.operation_id = source.operation_id
  AND turn.stage_id = source.remote_development_stage_id
  AND turn.stage_generation = source.stage_generation
  AND turn.task_epoch = source.task_epoch
  AND turn.purpose = 'REMOTE_CI_REPAIR'
  AND turn.status = 'FAILED'
  AND turn.attempt > 0
  AND turn.expected_code_fingerprint = source.expected_code_fingerprint
  AND turn.expected_head_sha = source.expected_head_sha
  AND turn.expected_base_sha = source.expected_base_sha
  AND ticket.operation_kind = 'EXECUTE_STAGE_TURN'
  AND ticket.async_family = 'AGENT_TURN'
  AND ticket.owner_kind = 'STAGE_TURN'
  AND ticket.owner_id = turn.id
  AND ticket.callback_route = 'REMOTE_CI_STAGE_TURN_RESULT'
  AND ticket.task_id = source.task_id
  AND ticket.task_epoch = source.task_epoch
  AND ticket.stage_id = source.remote_development_stage_id
  AND ticket.stage_generation = source.stage_generation
  AND ticket.attempt = turn.attempt
  AND ticket.expected_code_fingerprint = source.expected_code_fingerprint
  AND ticket.expected_head_sha = source.expected_head_sha
  AND ticket.expected_base_sha = source.expected_base_sha
  AND ((ticket.status = 'FAILED'
          AND ticket.delivery_acceptance = 'ACCEPTED'
          AND ticket.completed_at_ms = source.completed_at_ms)
    OR (ticket.status = 'RESULT_PENDING'
          AND ticket.delivery_acceptance IS NULL
          AND ticket.completed_at_ms IS NULL
          AND ticket.pending_result_outcome = 'FAILED'
          AND ticket.pending_result_task_epoch = source.task_epoch
          AND ticket.pending_result_stage_id =
              source.remote_development_stage_id
          AND ticket.pending_result_stage_generation = source.stage_generation
          AND ticket.pending_result_operation_id = source.operation_id
          AND ticket.pending_result_attempt = turn.attempt
          AND ticket.pending_result_expected_code_fingerprint =
              source.expected_code_fingerprint
          AND ticket.pending_result_expected_head_sha =
              source.expected_head_sha
          AND ticket.pending_result_expected_base_sha =
              source.expected_base_sha
          AND ticket.pending_result_payload = json_extract(
              execution.raw_result, '$.payloadJson')
          AND ticket.pending_result_evidence = json_extract(
              execution.raw_result, '$.evidenceJson')
          AND ticket.pending_result_error IS json_extract(
              execution.raw_result, '$.error')))
  AND ticket.infrastructure_attempts > 0
  AND execution.status = 'FAILED'
  AND execution.finished_at_ms IS NOT NULL
  AND execution.raw_result IS NOT NULL
  AND json_valid(execution.raw_result)
  AND json_extract(execution.raw_result, '$.outcome') = 'FAILED'
  AND json_extract(execution.raw_result,
      '$.fence.taskEpoch') = source.task_epoch
  AND json_extract(execution.raw_result,
      '$.fence.stageId') = source.remote_development_stage_id
  AND json_extract(execution.raw_result,
      '$.fence.stageGeneration') = source.stage_generation
  AND json_extract(execution.raw_result,
      '$.fence.operationId') = source.operation_id
  AND json_extract(execution.raw_result, '$.fence.attempt') = turn.attempt
  AND json_extract(execution.raw_result,
      '$.fence.expectedCodeFingerprint') = source.expected_code_fingerprint
  AND json_extract(execution.raw_result,
      '$.fence.expectedHeadSha') = source.expected_head_sha
  AND json_extract(execution.raw_result,
      '$.fence.expectedBaseSha') = source.expected_base_sha
  AND json_type(execution.raw_result, '$.payloadJson') = 'text'
  AND json_valid(json_extract(execution.raw_result, '$.payloadJson'))
  AND json_extract(json_extract(execution.raw_result,
      '$.payloadJson'), '$.turnId') = turn.id
  AND json_extract(json_extract(execution.raw_result,
      '$.payloadJson'), '$.ownerKind') = 'STAGE_TURN'
  AND json_extract(json_extract(execution.raw_result,
      '$.payloadJson'), '$.purpose') = 'REMOTE_CI_REPAIR'
  AND json_extract(json_extract(execution.raw_result,
      '$.payloadJson'), '$.disposition') = 'OWNER_OUTPUT_MALFORMED'
  AND json_type(json_extract(execution.raw_result,
      '$.payloadJson'), '$.finalText') = 'text'
  AND length(trim(json_extract(json_extract(execution.raw_result,
      '$.payloadJson'), '$.finalText'))) > 0
  AND delivery.operation_id = source.operation_id
  AND delivery.raw_outcome = 'FAILED'
  AND delivery.acceptance = 'ACCEPTED'
  AND episode.status = 'FIXING'
  AND episode.fix_attempt_count + 1 = source.semantic_attempt
  AND episode.remote_development_stage_id =
      source.remote_development_stage_id
  AND episode.task_id = source.task_id
  AND episode.task_epoch = source.task_epoch
  AND episode.stage_generation = source.stage_generation
  AND episode.subject_head_sha = source.expected_head_sha
  AND episode.subject_base_sha = source.expected_base_sha
  AND blocker.task_id = source.task_id
  AND blocker.stage_id = source.remote_development_stage_id
  AND blocker.subject_revision = source.expected_head_sha
  AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
  AND blocker.status = 'OPEN'
  AND task.workflow_version = 'V2'
  AND task.lifecycle_state = 'ACTIVE'
  AND task.epoch = source.task_epoch
  AND current.stage_id = source.remote_development_stage_id
  AND current.stage_generation = source.stage_generation
  AND owner.kind = 'REMOTE_DEVELOPMENT'
  AND owner.completed_at_ms IS NULL
  AND remote.generation = source.stage_generation
  AND remote.current_head_sha = source.expected_head_sha
  AND remote.current_base_sha = source.expected_base_sha
  AND code.code_fingerprint = source.expected_code_fingerprint
  AND code.head_sha = source.expected_head_sha
  AND code.base_sha = source.expected_base_sha
  AND ((source.base_repair_authorization_id IS NULL
          AND episode.classification <> 'BASE_DETERMINISTIC')
    OR (source.base_repair_authorization_id IS NOT NULL
          AND episode.classification = 'BASE_DETERMINISTIC'
          AND authorization.ci_repair_episode_id = episode.id
          AND authorization.semantic_attempt = source.semantic_attempt
          AND authorization.expected_worktree_head_sha =
              source.expected_head_sha
          AND authorization.subject_head_sha = source.expected_head_sha
          AND authorization.subject_base_sha = source.expected_base_sha
          AND authorization.status = 'CLOSED'))
  AND (COALESCE(json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject'), 'null') = 'null'
    OR (json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject') = 'object'
      AND json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.candidateParentSha') IS NULL
      AND NOT EXISTS (
          SELECT 1
          FROM json_each(json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject')) member
          WHERE member.key NOT IN (
              'codeFingerprint', 'headSha', 'baseSha', 'clean',
              'mergeBaseSha', 'sourceTreeSha', 'resultTreeSha',
              'discardedNoChangeHeadSha', 'restoredHeadSha',
              'sourceHeadMergeBaseSha', 'branchName'))
      AND json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.codeFingerprint') = 'text'
      AND length(trim(json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.codeFingerprint'))) > 0
      AND json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.headSha') = 'text'
      AND length(trim(json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.headSha'))) > 0
      AND COALESCE(json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.baseSha'), 'null')
          IN ('null', 'text')
      AND (json_type(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.baseSha') IS NULL
          OR length(trim(json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.baseSha'))) > 0)
      AND COALESCE(json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.mergeBaseSha'), 'null')
          IN ('null', 'text')
      AND (json_type(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.mergeBaseSha') IS NULL
          OR length(trim(json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.mergeBaseSha'))) > 0)
      AND json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.clean') IN ('true', 'false')
      AND json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.branchName') = 'text'
      AND length(trim(json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.branchName'))) > 0
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject.branchName') =
          identity.branch_name
      AND ((json_type(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.sourceTreeSha') IS NULL
              AND json_type(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.resultTreeSha') IS NULL)
        OR (json_type(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.sourceTreeSha') = 'text'
              AND json_type(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.resultTreeSha') = 'text'
              AND length(trim(json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.sourceTreeSha'))) > 0
              AND length(trim(json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.resultTreeSha'))) > 0))
      AND ((json_type(json_extract(execution.raw_result, '$.payloadJson'),
              '$.outputCodeSubject.discardedNoChangeHeadSha') IS NULL
              AND json_type(json_extract(execution.raw_result, '$.payloadJson'),
              '$.outputCodeSubject.restoredHeadSha') IS NULL)
        OR (json_type(json_extract(execution.raw_result, '$.payloadJson'),
              '$.outputCodeSubject.discardedNoChangeHeadSha') = 'text'
              AND json_type(json_extract(execution.raw_result, '$.payloadJson'),
              '$.outputCodeSubject.restoredHeadSha') = 'text'
              AND length(trim(json_extract(json_extract(execution.raw_result,
              '$.payloadJson'),
              '$.outputCodeSubject.discardedNoChangeHeadSha'))) > 0
              AND length(trim(json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.restoredHeadSha'))) > 0
              AND json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.sourceTreeSha') =
                  json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.resultTreeSha')
              AND json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.headSha') =
                  json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.restoredHeadSha')
              AND json_extract(json_extract(execution.raw_result,
              '$.payloadJson'),
              '$.outputCodeSubject.discardedNoChangeHeadSha') <>
                  json_extract(json_extract(execution.raw_result,
              '$.payloadJson'), '$.outputCodeSubject.restoredHeadSha')))
      AND (json_type(json_extract(execution.raw_result,
              '$.payloadJson'),
              '$.outputCodeSubject.sourceHeadMergeBaseSha') IS NULL
          OR (json_type(json_extract(execution.raw_result,
              '$.payloadJson'),
              '$.outputCodeSubject.sourceHeadMergeBaseSha') = 'text'
              AND length(trim(json_extract(json_extract(execution.raw_result,
              '$.payloadJson'),
              '$.outputCodeSubject.sourceHeadMergeBaseSha'))) > 0))
      AND json_type(execution.raw_result, '$.evidenceJson') = 'text'
      AND json_valid(json_extract(execution.raw_result, '$.evidenceJson'))
      AND json_extract(json_extract(execution.raw_result,
          '$.evidenceJson'), '$.disposition') = 'OWNER_OUTPUT_MALFORMED'
      AND json_type(json_extract(execution.raw_result,
          '$.evidenceJson'), '$.outputCodeSubject') = 'object'
      AND json_extract(json_extract(execution.raw_result,
          '$.evidenceJson'), '$.outputCodeSubject') =
          json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.outputCodeSubject')));

CREATE TRIGGER remote_repair_legacy_eligibility_insert_block_v322
BEFORE INSERT ON remote_repair_legacy_eligibility_v322
BEGIN SELECT RAISE(ABORT,
    'Remote repair legacy eligibility is migration-only'); END;

CREATE TRIGGER remote_repair_legacy_eligibility_immutable_v322
BEFORE UPDATE ON remote_repair_legacy_eligibility_v322
BEGIN SELECT RAISE(ABORT,
    'Remote repair legacy eligibility is immutable'); END;

CREATE TRIGGER remote_repair_legacy_eligibility_delete_v322
BEFORE DELETE ON remote_repair_legacy_eligibility_v322
BEGIN SELECT RAISE(ABORT,
    'Remote repair legacy eligibility is immutable'); END;

CREATE TABLE remote_repair_result_normalization_due_v322 (
    id                              TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id            TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    source_operation_row_id         TEXT    NOT NULL UNIQUE
        REFERENCES ci_repair_operation(id) ON DELETE CASCADE,
    source_operation_id             TEXT    NOT NULL UNIQUE,
    source_stage_turn_id            TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    source_dispatch_ticket_id       TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    source_agent_execution_id       TEXT    NOT NULL UNIQUE REFERENCES agent_execution(id),
    source_base_repair_authorization_id TEXT
        REFERENCES ci_base_repair_authorization_v303(id),
    blocker_id                      TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    task_id                         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id     TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation                INTEGER NOT NULL CHECK (stage_generation > 0),
    semantic_attempt                INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt               INTEGER NOT NULL CHECK (execution_attempt > 0),
    source_code_subject_revision    INTEGER NOT NULL
        REFERENCES task_code_subject_revision_v320(revision),
    source_code_subject_kind        TEXT    NOT NULL CHECK (
        source_code_subject_kind IN (
            'REMOTE_WORKTREE', 'REMOTE_STEERING', 'CI_BASE_REPAIR',
            'LOCAL_BASE_SYNC')),
    source_code_subject_id          TEXT    NOT NULL,
    expected_code_fingerprint       TEXT    NOT NULL,
    expected_head_sha               TEXT    NOT NULL,
    expected_base_sha               TEXT    NOT NULL,
    source_malformed_output         TEXT    NOT NULL,
    source_raw_result_digest        TEXT    NOT NULL
        CHECK (length(source_raw_result_digest) = 64),
    required_result_shape           TEXT    NOT NULL,
    candidate_capture_kind          TEXT    NOT NULL CHECK (
        candidate_capture_kind IN (
            'FROZEN_WRITER_PROOF_V1', 'LEGACY_REFLOG_WINDOW_V1')),
    candidate_code_fingerprint      TEXT,
    candidate_head_sha              TEXT,
    candidate_parent_sha            TEXT,
    candidate_base_sha              TEXT,
    candidate_clean                 INTEGER CHECK (candidate_clean = 1),
    candidate_merge_base_sha        TEXT,
    candidate_source_tree_sha       TEXT,
    candidate_result_tree_sha       TEXT,
    candidate_source_head_merge_base_sha TEXT,
    candidate_branch_name           TEXT,
    source_execution_started_at_ms  INTEGER NOT NULL CHECK (
        source_execution_started_at_ms >= 0),
    source_execution_finished_at_ms INTEGER NOT NULL CHECK (
        source_execution_finished_at_ms >= source_execution_started_at_ms),
    status                          TEXT    NOT NULL CHECK (status IN (
        'PENDING', 'DISPATCHED', 'CANCELED')),
    normalization_operation_row_id  TEXT UNIQUE,
    recorded_at_ms                  INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    consumed_at_ms                  INTEGER,
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_id)) > 0
        AND length(trim(source_code_subject_id)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0
        AND length(trim(source_malformed_output,
            char(9) || char(10) || char(13) || ' ')) > 0),
    CHECK (required_result_shape =
        '{"schemaVersion":1,"summary":"string"}'),
    CHECK ((status = 'PENDING' AND normalization_operation_row_id IS NULL
            AND consumed_at_ms IS NULL)
        OR (status = 'DISPATCHED'
            AND normalization_operation_row_id IS NOT NULL
            AND consumed_at_ms IS NOT NULL)
        OR (status = 'CANCELED'
            AND normalization_operation_row_id IS NULL
            AND consumed_at_ms IS NOT NULL)),
    CHECK ((candidate_capture_kind = 'FROZEN_WRITER_PROOF_V1'
            AND candidate_code_fingerprint IS NOT NULL
            AND candidate_head_sha IS NOT NULL
            AND candidate_parent_sha = expected_head_sha
            AND candidate_base_sha IS NOT NULL
            AND candidate_clean = 1
            AND candidate_merge_base_sha IS NOT NULL
            AND candidate_source_tree_sha IS NOT NULL
            AND candidate_result_tree_sha IS NOT NULL
            AND candidate_source_head_merge_base_sha IS NOT NULL
            AND candidate_branch_name IS NOT NULL
            AND candidate_head_sha <> expected_head_sha
            AND candidate_base_sha = expected_base_sha
            AND candidate_merge_base_sha = expected_base_sha
            AND candidate_source_tree_sha <> candidate_result_tree_sha
            AND candidate_source_head_merge_base_sha = expected_head_sha)
        OR (candidate_capture_kind = 'LEGACY_REFLOG_WINDOW_V1'
            AND candidate_code_fingerprint IS NULL
            AND candidate_head_sha IS NULL
            AND candidate_parent_sha IS NULL
            AND candidate_base_sha IS NULL
            AND candidate_clean IS NULL
            AND candidate_merge_base_sha IS NULL
            AND candidate_source_tree_sha IS NULL
            AND candidate_result_tree_sha IS NULL
            AND candidate_source_head_merge_base_sha IS NULL
            AND candidate_branch_name IS NULL))
);

CREATE TRIGGER remote_repair_result_normalization_due_insert_v322
BEFORE INSERT ON remote_repair_result_normalization_due_v322
WHEN NEW.status <> 'PENDING' OR NOT EXISTS (
    SELECT 1
    FROM ci_repair_operation source
    JOIN ci_repair_episode episode
      ON episode.id = source.ci_repair_episode_id
    JOIN stage_turn turn ON turn.id = source.stage_turn_id
    JOIN dispatch_ticket ticket
      ON ticket.id = NEW.source_dispatch_ticket_id
     AND ticket.operation_id = source.operation_id
    JOIN agent_execution execution
      ON execution.id = NEW.source_agent_execution_id
     AND execution.ticket_id = ticket.id
    JOIN ci_repair_delivery_receipt delivery
      ON delivery.ci_repair_operation_id = source.id
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    JOIN tasks task ON task.id = source.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN remote_development_stage remote ON remote.stage_id = owner.id
    JOIN task_current_code_subject_fence_v322 code ON code.task_id = task.id
    JOIN task_code_identity identity ON identity.task_id = task.id
    LEFT JOIN ci_base_repair_authorization_v303 authorization
      ON authorization.id = source.base_repair_authorization_id
    WHERE source.id = NEW.source_operation_row_id
      AND source.operation_id = NEW.source_operation_id
      AND source.kind = 'FIX_STAGE_TURN'
      AND source.status = 'FAILED'
      AND source.error_message LIKE 'OWNER_OUTPUT_MALFORMED:%'
      AND source.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND source.task_id = NEW.task_id
      AND source.task_epoch = NEW.task_epoch
      AND source.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND source.stage_generation = NEW.stage_generation
      AND source.semantic_attempt = NEW.semantic_attempt
      AND code.source_code_subject_revision =
          NEW.source_code_subject_revision
      AND code.source_code_subject_kind = NEW.source_code_subject_kind
      AND code.source_code_subject_id = NEW.source_code_subject_id
      AND source.expected_code_fingerprint =
          NEW.expected_code_fingerprint
      AND source.expected_head_sha = NEW.expected_head_sha
      AND source.expected_base_sha = NEW.expected_base_sha
      AND source.base_repair_authorization_id IS
          NEW.source_base_repair_authorization_id
      AND turn.id = NEW.source_stage_turn_id
      AND turn.operation_id = NEW.source_operation_id
      AND turn.stage_id = NEW.remote_development_stage_id
      AND turn.stage_generation = NEW.stage_generation
      AND turn.task_epoch = NEW.task_epoch
      AND turn.purpose = 'REMOTE_CI_REPAIR'
      AND turn.status = 'FAILED'
      AND turn.attempt = NEW.execution_attempt
      AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND turn.expected_head_sha = NEW.expected_head_sha
      AND turn.expected_base_sha = NEW.expected_base_sha
      AND ticket.operation_kind = 'EXECUTE_STAGE_TURN'
      AND ticket.async_family = 'AGENT_TURN'
      AND ticket.owner_kind = 'STAGE_TURN'
      AND ticket.owner_id = turn.id
      AND ticket.callback_route = 'REMOTE_CI_STAGE_TURN_RESULT'
      AND ticket.task_id = NEW.task_id
      AND ticket.task_epoch = NEW.task_epoch
      AND ticket.stage_id = NEW.remote_development_stage_id
      AND ticket.stage_generation = NEW.stage_generation
      AND ticket.attempt = NEW.execution_attempt
      AND ticket.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND ticket.expected_head_sha = NEW.expected_head_sha
      AND ticket.expected_base_sha = NEW.expected_base_sha
      AND ((ticket.status = 'FAILED'
              AND ticket.delivery_acceptance = 'ACCEPTED'
              AND ticket.completed_at_ms = source.completed_at_ms)
        OR (ticket.status = 'RESULT_PENDING'
              AND ticket.delivery_acceptance IS NULL
              AND ticket.completed_at_ms IS NULL
              AND ticket.pending_result_outcome = 'FAILED'
              AND ticket.pending_result_task_epoch = NEW.task_epoch
              AND ticket.pending_result_stage_id =
                  NEW.remote_development_stage_id
              AND ticket.pending_result_stage_generation =
                  NEW.stage_generation
              AND ticket.pending_result_operation_id =
                  NEW.source_operation_id
              AND ticket.pending_result_attempt = NEW.execution_attempt
              AND ticket.pending_result_expected_code_fingerprint =
                  NEW.expected_code_fingerprint
              AND ticket.pending_result_expected_head_sha =
                  NEW.expected_head_sha
              AND ticket.pending_result_expected_base_sha =
                  NEW.expected_base_sha
              AND ticket.pending_result_payload = json_extract(
                  execution.raw_result, '$.payloadJson')
              AND ticket.pending_result_evidence = json_extract(
                  execution.raw_result, '$.evidenceJson')
              AND ticket.pending_result_error IS json_extract(
                  execution.raw_result, '$.error')))
      AND ticket.infrastructure_attempts > 0
      AND execution.infrastructure_attempt = ticket.infrastructure_attempts
      AND execution.status = 'FAILED'
      AND execution.started_at_ms = NEW.source_execution_started_at_ms
      AND execution.finished_at_ms = NEW.source_execution_finished_at_ms
      AND execution.raw_result IS NOT NULL
      AND json_valid(execution.raw_result)
      AND json_extract(execution.raw_result, '$.outcome') = 'FAILED'
      AND json_extract(execution.raw_result,
          '$.fence.taskEpoch') = NEW.task_epoch
      AND json_extract(execution.raw_result,
          '$.fence.stageId') = NEW.remote_development_stage_id
      AND json_extract(execution.raw_result,
          '$.fence.stageGeneration') = NEW.stage_generation
      AND json_extract(execution.raw_result,
          '$.fence.operationId') = NEW.source_operation_id
      AND json_extract(execution.raw_result,
          '$.fence.attempt') = NEW.execution_attempt
      AND json_extract(execution.raw_result,
          '$.fence.expectedCodeFingerprint') = NEW.expected_code_fingerprint
      AND json_extract(execution.raw_result,
          '$.fence.expectedHeadSha') = NEW.expected_head_sha
      AND json_extract(execution.raw_result,
          '$.fence.expectedBaseSha') = NEW.expected_base_sha
      AND json_type(execution.raw_result, '$.payloadJson') = 'text'
      AND json_valid(json_extract(execution.raw_result, '$.payloadJson'))
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.turnId') = NEW.source_stage_turn_id
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.ownerKind') = 'STAGE_TURN'
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.purpose') = 'REMOTE_CI_REPAIR'
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.disposition') = 'OWNER_OUTPUT_MALFORMED'
      AND json_type(json_extract(execution.raw_result,
          '$.payloadJson'), '$.finalText') = 'text'
      AND json_extract(json_extract(execution.raw_result,
          '$.payloadJson'), '$.finalText') = NEW.source_malformed_output
      AND delivery.operation_id = NEW.source_operation_id
      AND delivery.raw_outcome = 'FAILED'
      AND delivery.raw_result_digest = NEW.source_raw_result_digest
      AND delivery.acceptance = 'ACCEPTED'
      AND episode.status = 'FIXING'
      AND episode.fix_attempt_count + 1 = NEW.semantic_attempt
      AND episode.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.stage_generation = NEW.stage_generation
      AND episode.subject_head_sha = NEW.expected_head_sha
      AND episode.subject_base_sha = NEW.expected_base_sha
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.remote_development_stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND remote.generation = NEW.stage_generation
      AND remote.current_head_sha = NEW.expected_head_sha
      AND remote.current_base_sha = NEW.expected_base_sha
      AND code.code_fingerprint = NEW.expected_code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND blocker.task_id = NEW.task_id
      AND blocker.stage_id = NEW.remote_development_stage_id
      AND blocker.owner_kind = 'EPISODE'
      AND blocker.owner_id = NEW.ci_repair_episode_id
      AND blocker.subject_revision = NEW.expected_head_sha
      AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
      AND blocker.status = 'OPEN'
      AND ((source.base_repair_authorization_id IS NULL
              AND episode.classification <> 'BASE_DETERMINISTIC')
        OR (source.base_repair_authorization_id IS NOT NULL
              AND episode.classification = 'BASE_DETERMINISTIC'
              AND authorization.ci_repair_episode_id = episode.id
              AND authorization.semantic_attempt = NEW.semantic_attempt
              AND authorization.expected_worktree_head_sha =
                  NEW.expected_head_sha
              AND authorization.subject_head_sha = NEW.expected_head_sha
              AND authorization.subject_base_sha = NEW.expected_base_sha
              AND ((NEW.candidate_capture_kind =
                        'LEGACY_REFLOG_WINDOW_V1'
                      AND authorization.status = 'CLOSED')
                OR (NEW.candidate_capture_kind =
                        'FROZEN_WRITER_PROOF_V1'
                      AND authorization.status = 'CLAIMED'))))
      AND ((NEW.candidate_capture_kind = 'LEGACY_REFLOG_WINDOW_V1'
              AND EXISTS (
                  SELECT 1
                  FROM remote_repair_legacy_eligibility_v322 eligibility
                  WHERE eligibility.source_operation_row_id = source.id
                    AND eligibility.ci_repair_episode_id = episode.id
                    AND eligibility.source_operation_id = source.operation_id
                    AND eligibility.source_stage_turn_id = turn.id
                    AND eligibility.source_dispatch_ticket_id = ticket.id
                    AND eligibility.source_agent_execution_id = execution.id
                    AND eligibility.source_base_repair_authorization_id IS
                        source.base_repair_authorization_id
                    AND eligibility.blocker_id = blocker.id
                    AND eligibility.task_id = task.id
                    AND eligibility.task_epoch = task.epoch
                    AND eligibility.remote_development_stage_id = owner.id
                    AND eligibility.stage_generation = owner.generation
                    AND eligibility.semantic_attempt = source.semantic_attempt
                    AND eligibility.execution_attempt = turn.attempt
                    AND eligibility.source_code_subject_revision =
                        code.source_code_subject_revision
                    AND eligibility.source_code_subject_kind =
                        code.source_code_subject_kind
                    AND eligibility.source_code_subject_id =
                        code.source_code_subject_id
                    AND eligibility.expected_code_fingerprint =
                        source.expected_code_fingerprint
                    AND eligibility.expected_head_sha =
                        source.expected_head_sha
                    AND eligibility.expected_base_sha =
                        source.expected_base_sha
                    AND eligibility.source_malformed_output =
                        NEW.source_malformed_output
                    AND eligibility.source_raw_result_digest =
                        NEW.source_raw_result_digest
                    AND eligibility.ticket_window = CASE ticket.status
                        WHEN 'FAILED' THEN 'FAILED_DELIVERED'
                        ELSE 'RESULT_PENDING_AFTER_OWNER_DELIVERY' END
                    AND eligibility.source_execution_started_at_ms =
                        execution.started_at_ms
                    AND eligibility.source_execution_finished_at_ms =
                        execution.finished_at_ms
                    AND eligibility.recorded_at_ms = source.completed_at_ms)
              AND NEW.candidate_code_fingerprint IS NULL
              AND NEW.candidate_head_sha IS NULL
              AND NEW.candidate_parent_sha IS NULL
              AND NEW.candidate_base_sha IS NULL
              AND NEW.candidate_clean IS NULL
              AND NEW.candidate_merge_base_sha IS NULL
              AND NEW.candidate_source_tree_sha IS NULL
              AND NEW.candidate_result_tree_sha IS NULL
              AND NEW.candidate_source_head_merge_base_sha IS NULL
              AND NEW.candidate_branch_name IS NULL)
        OR (NEW.candidate_capture_kind = 'FROZEN_WRITER_PROOF_V1'
              AND json_type(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject') = 'object'
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.codeFingerprint') =
                  NEW.candidate_code_fingerprint
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.headSha') =
                  NEW.candidate_head_sha
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.candidateParentSha') =
                  NEW.candidate_parent_sha
              AND NEW.candidate_parent_sha = NEW.expected_head_sha
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.baseSha') =
                  NEW.candidate_base_sha
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.clean') = 1
              AND NEW.candidate_clean = 1
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.mergeBaseSha') =
                  NEW.candidate_merge_base_sha
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.sourceTreeSha') =
                  NEW.candidate_source_tree_sha
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.resultTreeSha') =
                  NEW.candidate_result_tree_sha
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'),
                  '$.outputCodeSubject.sourceHeadMergeBaseSha') =
                  NEW.candidate_source_head_merge_base_sha
              AND json_extract(json_extract(execution.raw_result,
                  '$.payloadJson'), '$.outputCodeSubject.branchName') =
                  NEW.candidate_branch_name
              AND NEW.candidate_branch_name = identity.branch_name))
      AND NEW.recorded_at_ms = source.completed_at_ms)
BEGIN SELECT RAISE(ABORT,
    'Remote repair result-normalization due is not exact'); END;

CREATE TRIGGER remote_repair_result_normalization_due_identity_v322
BEFORE UPDATE OF id, ci_repair_episode_id, source_operation_row_id,
    source_operation_id, source_stage_turn_id, source_dispatch_ticket_id,
    source_agent_execution_id, source_base_repair_authorization_id,
    blocker_id, task_id, task_epoch, remote_development_stage_id,
    stage_generation, semantic_attempt, execution_attempt,
    source_code_subject_revision, source_code_subject_kind,
    source_code_subject_id,
    expected_code_fingerprint, expected_head_sha, expected_base_sha,
    source_malformed_output, source_raw_result_digest, required_result_shape,
    candidate_capture_kind, candidate_code_fingerprint, candidate_head_sha,
    candidate_parent_sha,
    candidate_base_sha, candidate_clean, candidate_merge_base_sha,
    candidate_source_tree_sha, candidate_result_tree_sha,
    candidate_source_head_merge_base_sha, candidate_branch_name,
    source_execution_started_at_ms, source_execution_finished_at_ms,
    recorded_at_ms
ON remote_repair_result_normalization_due_v322
BEGIN SELECT RAISE(ABORT,
    'Remote repair result-normalization due identity is immutable'); END;

-- The bridge is one fresh, read-only TaskTurn.  Its own result is still
-- decoded by the unchanged strict Stage result decoder; this table merely
-- freezes the replacement execution and its terminal delivery.
CREATE TABLE remote_repair_result_normalization_operation_v322 (
    id                              TEXT    NOT NULL PRIMARY KEY,
    normalization_due_id            TEXT    NOT NULL UNIQUE
        REFERENCES remote_repair_result_normalization_due_v322(id)
        ON DELETE CASCADE,
    source_operation_row_id         TEXT    NOT NULL UNIQUE
        REFERENCES ci_repair_operation(id) ON DELETE CASCADE,
    source_operation_id             TEXT    NOT NULL UNIQUE,
    source_stage_turn_id            TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    ci_repair_episode_id            TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    task_id                         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id     TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation                INTEGER NOT NULL CHECK (stage_generation > 0),
    normalization_task_turn_id      TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    operation_id                    TEXT    NOT NULL UNIQUE,
    dispatch_ticket_id              TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    semantic_attempt                INTEGER NOT NULL CHECK (semantic_attempt > 0),
    source_execution_attempt        INTEGER NOT NULL CHECK (source_execution_attempt > 0),
    normalization_attempt           INTEGER NOT NULL CHECK (normalization_attempt > 1),
    source_code_subject_revision    INTEGER NOT NULL
        REFERENCES task_code_subject_revision_v320(revision),
    source_code_subject_kind        TEXT    NOT NULL CHECK (
        source_code_subject_kind IN (
            'REMOTE_WORKTREE', 'REMOTE_STEERING', 'CI_BASE_REPAIR',
            'LOCAL_BASE_SYNC')),
    source_code_subject_id          TEXT    NOT NULL,
    expected_code_fingerprint       TEXT    NOT NULL,
    expected_head_sha               TEXT    NOT NULL,
    expected_base_sha               TEXT    NOT NULL,
    status                          TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'CANCELED', 'SUPERSEDED')),
    raw_outcome                     TEXT CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    normalization_raw_result_digest TEXT CHECK (
        normalization_raw_result_digest IS NULL
        OR length(normalization_raw_result_digest) = 64),
    normalized_payload              TEXT,
    normalized_payload_digest       TEXT CHECK (
        normalized_payload_digest IS NULL
        OR length(normalized_payload_digest) = 64),
    acceptance                      TEXT CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    terminal_evidence               TEXT CHECK (terminal_evidence IS NULL
        OR (json_valid(terminal_evidence)
            AND json_type(terminal_evidence) = 'object')),
    requested_at_ms                 INTEGER NOT NULL CHECK (requested_at_ms >= 0),
    completed_at_ms                 INTEGER,
    error_message                   TEXT,
    CHECK (normalization_attempt = source_execution_attempt + 1),
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(source_code_subject_id)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0),
    CHECK ((status IN ('REQUESTED', 'DISPATCHED')
            AND raw_outcome IS NULL
            AND normalization_raw_result_digest IS NULL
            AND normalized_payload IS NULL
            AND normalized_payload_digest IS NULL
            AND acceptance IS NULL
            AND terminal_evidence IS NULL
            AND completed_at_ms IS NULL
            AND error_message IS NULL)
        OR (status NOT IN ('REQUESTED', 'DISPATCHED')
            AND raw_outcome IS NOT NULL
            AND normalization_raw_result_digest IS NOT NULL
            AND acceptance IS NOT NULL
            AND terminal_evidence IS NOT NULL
            AND completed_at_ms IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED'
            AND raw_outcome = 'SUCCEEDED'
            AND acceptance = 'ACCEPTED'
            AND normalized_payload IS NOT NULL
            AND normalized_payload_digest IS NOT NULL
            AND error_message IS NULL)
        OR (status = 'FAILED'
            AND raw_outcome IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')
            AND acceptance = 'ACCEPTED'
            AND normalized_payload IS NULL
            AND normalized_payload_digest IS NULL
            AND length(trim(error_message)) > 0)
        OR (status = 'CANCELED'
            AND raw_outcome = 'CANCELED'
            AND acceptance = 'ACCEPTED'
            AND normalized_payload IS NULL
            AND normalized_payload_digest IS NULL
            AND length(trim(error_message)) > 0)
        OR (status = 'SUPERSEDED'
            AND acceptance = 'SUPERSEDED'
            AND normalized_payload IS NULL
            AND normalized_payload_digest IS NULL
            AND length(trim(error_message)) > 0)
        OR status IN ('REQUESTED', 'DISPATCHED'))
);

CREATE UNIQUE INDEX one_live_remote_repair_normalization_v322
    ON remote_repair_result_normalization_operation_v322(task_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TRIGGER remote_repair_result_normalization_operation_insert_v322
BEFORE INSERT ON remote_repair_result_normalization_operation_v322
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1
    FROM remote_repair_result_normalization_due_v322 due
    JOIN ci_repair_episode episode ON episode.id = due.ci_repair_episode_id
    JOIN task_blocker blocker ON blocker.id = due.blocker_id
    JOIN tasks task ON task.id = due.task_id
    JOIN threads trunk ON trunk.id = task.thread_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_current_code_subject_fence_v322 code ON code.task_id = task.id
    JOIN task_turn turn ON turn.id = NEW.normalization_task_turn_id
    JOIN stage_turn source_turn ON source_turn.id = due.source_stage_turn_id
    JOIN dispatch_ticket source_ticket
      ON source_ticket.id = due.source_dispatch_ticket_id
    WHERE due.id = NEW.normalization_due_id
      AND due.status = 'PENDING'
      AND due.source_operation_row_id = NEW.source_operation_row_id
      AND due.source_operation_id = NEW.source_operation_id
      AND due.source_stage_turn_id = NEW.source_stage_turn_id
      AND due.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND due.task_id = NEW.task_id
      AND due.task_epoch = NEW.task_epoch
      AND due.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND due.stage_generation = NEW.stage_generation
      AND due.semantic_attempt = NEW.semantic_attempt
      AND due.execution_attempt = NEW.source_execution_attempt
      AND due.execution_attempt + 1 = NEW.normalization_attempt
      AND due.source_code_subject_revision =
          NEW.source_code_subject_revision
      AND due.source_code_subject_kind = NEW.source_code_subject_kind
      AND due.source_code_subject_id = NEW.source_code_subject_id
      AND due.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND due.expected_head_sha = NEW.expected_head_sha
      AND due.expected_base_sha = NEW.expected_base_sha
      AND episode.status = 'FIXING'
      AND episode.fix_attempt_count + 1 = NEW.semantic_attempt
      AND blocker.status = 'OPEN'
      AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.remote_development_stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND code.code_fingerprint = NEW.expected_code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND code.source_code_subject_revision =
          NEW.source_code_subject_revision
      AND code.source_code_subject_kind = NEW.source_code_subject_kind
      AND code.source_code_subject_id = NEW.source_code_subject_id
      AND turn.task_id = NEW.task_id
      AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.remote_development_stage_id
      AND turn.trigger_stage_generation = NEW.stage_generation
      AND turn.purpose = 'REMOTE_REPAIR_RESULT_NORMALIZATION'
      AND turn.status IN ('REQUESTED', 'QUEUED')
      AND turn.operation_id = NEW.operation_id
      AND turn.attempt = NEW.normalization_attempt
      AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND turn.expected_head_sha = NEW.expected_head_sha
      AND turn.expected_base_sha = NEW.expected_base_sha
      AND turn.delivery_lane = source_turn.delivery_lane
      AND json_valid(turn.launch_input)
      AND json_type(turn.launch_input, '$.schemaVersion') = 'integer'
      AND json_extract(turn.launch_input, '$.schemaVersion') = 1
      AND json_extract(turn.launch_input, '$.systemPrompt') =
          'You are a syntax-only result normalizer. Do not inspect files, ' ||
          'use tools, edit the workspace, or perform remote effects.' ||
          char(10) ||
          'Return exactly one raw JSON object shaped ' ||
          '{"schemaVersion":1,"summary":"string"}.' || char(10) ||
          'Preserve the meaning of the frozen malformed result. Do not add ' ||
          'fields, Markdown fences, or surrounding prose.' || char(10)
      AND json_type(turn.launch_input, '$.prompt') = 'text'
      AND json_extract(turn.launch_input, '$.prompt') =
          'Normalize this frozen malformed Remote CI repair result into the ' ||
          'required shape.' || char(10) || char(10) || 'Required shape:' ||
          char(10) || due.required_result_shape || char(10) || char(10) ||
          'Source trace:' || char(10) || 'sourceOperationId=' ||
          due.source_operation_id || char(10) || 'sourceRawResultDigest=' ||
          due.source_raw_result_digest || char(10) || 'taskId=' ||
          due.task_id || char(10) || 'taskEpoch=' || due.task_epoch ||
          char(10) || 'stageId=' || due.remote_development_stage_id ||
          char(10) || 'stageGeneration=' || due.stage_generation ||
          char(10) || 'sourceCodeSubjectRevision=' ||
          due.source_code_subject_revision ||
          char(10) || 'sourceCodeSubjectKind=' ||
          due.source_code_subject_kind ||
          char(10) || 'sourceCodeSubjectId=' ||
          due.source_code_subject_id ||
          char(10) || 'expectedCodeFingerprint=' ||
          due.expected_code_fingerprint || char(10) || 'expectedHeadSha=' ||
          due.expected_head_sha || char(10) || 'expectedBaseSha=' ||
          due.expected_base_sha || char(10) || char(10) ||
          'Frozen malformed output encoded as one JSON string:' || char(10) ||
          json_quote(due.source_malformed_output)
      AND json_type(turn.launch_input, '$.resumeSessionId') IS NULL
      AND json_type(turn.launch_input, '$.fallbackPrompt') IS NULL
      AND COALESCE(json_extract(turn.launch_input,
          '$.priorCumulativeInputTokens'), 0) = 0
      AND COALESCE(json_extract(turn.launch_input,
          '$.priorCumulativeOutputTokens'), 0) = 0
      AND (json_type(turn.launch_input, '$.images') IS NULL
          OR json_array_length(json_extract(turn.launch_input, '$.images')) = 0)
      AND json_extract(turn.launch_input, '$.transport') IS
          json_extract(source_turn.launch_input, '$.transport')
      AND json_extract(turn.launch_input, '$.provider') IS
          json_extract(source_turn.launch_input, '$.provider')
      AND json_extract(turn.launch_input, '$.credentialAccount') IS
          json_extract(source_turn.launch_input, '$.credentialAccount')
      AND json_extract(turn.launch_input, '$.model') IS
          json_extract(source_turn.launch_input, '$.model')
      AND json_extract(turn.launch_input, '$.reasoningEffort') IS
          json_extract(source_turn.launch_input, '$.reasoningEffort')
      AND json_extract(turn.launch_input, '$.workingDirectory') =
          json_extract(source_turn.launch_input, '$.workingDirectory')
      AND json_extract(turn.launch_input,
          '$.toolEndpoint.serverName') = 'bytequay'
      AND CAST(substr(json_extract(turn.launch_input,
              '$.toolEndpoint.url'), 18,
              length(json_extract(turn.launch_input,
                  '$.toolEndpoint.url')) - 17 - length(
                  '/api/v2/task-turns/' || turn.id || '/operations/' ||
                  turn.operation_id || '/mcp')) AS INTEGER)
          BETWEEN 1 AND 65535
      AND json_extract(turn.launch_input, '$.toolEndpoint.url') =
          'http://127.0.0.1:' || CAST(CAST(substr(
              json_extract(turn.launch_input, '$.toolEndpoint.url'), 18,
              length(json_extract(turn.launch_input,
                  '$.toolEndpoint.url')) - 17 - length(
                  '/api/v2/task-turns/' || turn.id || '/operations/' ||
                  turn.operation_id || '/mcp')) AS INTEGER) AS TEXT) ||
          '/api/v2/task-turns/' || turn.id || '/operations/' ||
          turn.operation_id || '/mcp'
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
      AND source_ticket.trunk_id = task.thread_id)
BEGIN SELECT RAISE(ABORT,
    'Remote repair result-normalization operation is not exact'); END;

CREATE TRIGGER remote_repair_result_normalization_operation_identity_v322
BEFORE UPDATE OF id, normalization_due_id, source_operation_row_id,
    source_operation_id, source_stage_turn_id, ci_repair_episode_id,
    task_id, task_epoch, remote_development_stage_id, stage_generation,
    normalization_task_turn_id, operation_id, dispatch_ticket_id,
    semantic_attempt, source_execution_attempt, normalization_attempt,
    source_code_subject_revision, source_code_subject_kind,
    source_code_subject_id,
    expected_code_fingerprint, expected_head_sha, expected_base_sha,
    requested_at_ms
ON remote_repair_result_normalization_operation_v322
BEGIN SELECT RAISE(ABORT,
    'Remote repair result-normalization operation identity is immutable'); END;

CREATE TRIGGER remote_repair_result_normalization_operation_dispatch_v322
BEFORE UPDATE OF status
ON remote_repair_result_normalization_operation_v322
WHEN NEW.status = 'DISPATCHED' AND NOT (
    OLD.status = 'REQUESTED' AND EXISTS (
        SELECT 1 FROM dispatch_ticket ticket
        WHERE ticket.id = NEW.dispatch_ticket_id
          AND ticket.operation_id = NEW.operation_id
          AND ticket.status = 'REQUESTED'))
BEGIN SELECT RAISE(ABORT,
    'Remote repair result-normalization dispatch is not exact'); END;

CREATE TRIGGER dispatch_ticket_remote_repair_normalization_v322
BEFORE INSERT ON dispatch_ticket
WHEN NEW.callback_route = 'REMOTE_REPAIR_RESULT_NORMALIZATION_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM remote_repair_result_normalization_operation_v322 operation
        JOIN task_turn turn
          ON turn.id = operation.normalization_task_turn_id
        JOIN tasks task ON task.id = operation.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        JOIN remote_repair_result_normalization_due_v322 due
          ON due.id = operation.normalization_due_id
        JOIN dispatch_ticket source
          ON source.id = due.source_dispatch_ticket_id
        WHERE operation.dispatch_ticket_id = NEW.id
          AND operation.operation_id = NEW.operation_id
          AND operation.status = 'REQUESTED'
          AND turn.operation_id = NEW.operation_id
          AND turn.status IN ('REQUESTED', 'QUEUED')
          AND NEW.operation_kind = 'EXECUTE_TASK_TURN'
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'TASK_TURN'
          AND NEW.owner_id = operation.normalization_task_turn_id
          AND NEW.lane_mask = source.lane_mask
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.task_id = operation.task_id
          AND NEW.task_epoch = operation.task_epoch
          AND NEW.stage_id = operation.remote_development_stage_id
          AND NEW.stage_generation = operation.stage_generation
          AND NEW.attempt = operation.normalization_attempt
          AND NEW.expected_code_fingerprint =
              operation.expected_code_fingerprint
          AND NEW.expected_head_sha = operation.expected_head_sha
          AND NEW.expected_base_sha = operation.expected_base_sha
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'Remote repair result-normalization DispatchTicket is not exact') END;
END;

CREATE TRIGGER remote_repair_result_normalization_due_status_v322
BEFORE UPDATE OF status, normalization_operation_row_id, consumed_at_ms
ON remote_repair_result_normalization_due_v322
WHEN OLD.status <> 'PENDING'
  OR NEW.status NOT IN ('DISPATCHED', 'CANCELED')
  OR NEW.consumed_at_ms IS NULL
  OR (NEW.status = 'DISPATCHED' AND NOT EXISTS (
      SELECT 1
      FROM remote_repair_result_normalization_operation_v322 operation
      WHERE operation.id = NEW.normalization_operation_row_id
        AND operation.normalization_due_id = NEW.id
        AND operation.status = 'DISPATCHED'))
BEGIN SELECT RAISE(ABORT,
    'Remote repair result-normalization due transition is invalid'); END;

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

-- Only pre-V322 rows closed their original BASE_DETERMINISTIC authority before
-- the normalization transition existed.  This companion never rewrites that
-- immutable history; it temporarily makes that one closed authority effective
-- again for the normalized candidate's adoption, rewrite validation and push.
CREATE TABLE ci_base_repair_reauthorization_v322 (
    id                              TEXT    NOT NULL PRIMARY KEY,
    source_authorization_id         TEXT    NOT NULL UNIQUE
        REFERENCES ci_base_repair_authorization_v303(id),
    normalization_due_id            TEXT    NOT NULL UNIQUE
        REFERENCES remote_repair_result_normalization_due_v322(id),
    normalization_operation_row_id  TEXT    NOT NULL UNIQUE
        REFERENCES remote_repair_result_normalization_operation_v322(id),
    ci_repair_episode_id            TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    source_operation_row_id         TEXT    NOT NULL UNIQUE
        REFERENCES ci_repair_operation(id),
    source_operation_id             TEXT    NOT NULL UNIQUE,
    blocker_id                      TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    adoption_command_id             TEXT    NOT NULL UNIQUE,
    task_id                         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id     TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation                INTEGER NOT NULL CHECK (stage_generation > 0),
    semantic_attempt                INTEGER NOT NULL CHECK (semantic_attempt > 0),
    source_code_subject_revision    INTEGER NOT NULL
        REFERENCES task_code_subject_revision_v320(revision),
    source_code_subject_kind        TEXT    NOT NULL CHECK (
        source_code_subject_kind IN (
            'REMOTE_WORKTREE', 'REMOTE_STEERING', 'CI_BASE_REPAIR',
            'LOCAL_BASE_SYNC')),
    source_code_subject_id          TEXT    NOT NULL,
    expected_code_fingerprint       TEXT    NOT NULL,
    expected_head_sha               TEXT    NOT NULL,
    expected_base_sha               TEXT    NOT NULL,
    status                          TEXT    NOT NULL CHECK (status IN (
        'CLAIMED', 'CONSUMED', 'CLOSED')),
    claimed_at_ms                   INTEGER NOT NULL CHECK (claimed_at_ms >= 0),
    terminal_at_ms                  INTEGER,
    terminal_evidence               TEXT,
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_id)) > 0
        AND length(trim(adoption_command_id)) > 0
        AND length(trim(source_code_subject_id)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0),
    CHECK ((status = 'CLAIMED'
            AND terminal_at_ms IS NULL AND terminal_evidence IS NULL)
        OR (status IN ('CONSUMED', 'CLOSED')
            AND terminal_at_ms IS NOT NULL
            AND length(trim(terminal_evidence)) > 0))
);

CREATE TRIGGER ci_base_repair_reauthorization_insert_v322
BEFORE INSERT ON ci_base_repair_reauthorization_v322
WHEN NEW.status <> 'CLAIMED' OR NOT EXISTS (
    SELECT 1
    FROM remote_repair_result_normalization_due_v322 due
    JOIN remote_repair_result_normalization_operation_v322 normalization
      ON normalization.id = NEW.normalization_operation_row_id
    JOIN ci_repair_operation source
      ON source.id = NEW.source_operation_row_id
    JOIN ci_repair_episode episode ON episode.id = NEW.ci_repair_episode_id
    JOIN ci_base_repair_authorization_v303 authorization
      ON authorization.id = NEW.source_authorization_id
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    JOIN tasks task ON task.id = NEW.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_current_code_subject_fence_v322 code ON code.task_id = task.id
    WHERE due.id = NEW.normalization_due_id
      AND due.candidate_capture_kind = 'LEGACY_REFLOG_WINDOW_V1'
      AND due.source_base_repair_authorization_id =
          NEW.source_authorization_id
      AND due.source_operation_row_id = NEW.source_operation_row_id
      AND due.source_operation_id = NEW.source_operation_id
      AND due.blocker_id = NEW.blocker_id
      AND due.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND due.task_id = NEW.task_id
      AND due.task_epoch = NEW.task_epoch
      AND due.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND due.stage_generation = NEW.stage_generation
      AND due.semantic_attempt = NEW.semantic_attempt
      AND due.source_code_subject_revision =
          NEW.source_code_subject_revision
      AND due.source_code_subject_kind = NEW.source_code_subject_kind
      AND due.source_code_subject_id = NEW.source_code_subject_id
      AND due.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND due.expected_head_sha = NEW.expected_head_sha
      AND due.expected_base_sha = NEW.expected_base_sha
      AND normalization.normalization_due_id = due.id
      AND normalization.status = 'SUCCEEDED'
      AND source.kind = 'FIX_STAGE_TURN'
      AND source.status = 'FAILED'
      AND source.base_repair_authorization_id = authorization.id
      AND authorization.ci_repair_episode_id = episode.id
      AND authorization.semantic_attempt = NEW.semantic_attempt
      AND authorization.expected_worktree_head_sha = NEW.expected_head_sha
      AND authorization.subject_head_sha = NEW.expected_head_sha
      AND authorization.subject_base_sha = NEW.expected_base_sha
      AND authorization.status = 'CLOSED'
      AND episode.classification = 'BASE_DETERMINISTIC'
      AND episode.status = 'FIXING'
      AND episode.fix_attempt_count + 1 = NEW.semantic_attempt
      AND blocker.task_id = NEW.task_id
      AND blocker.stage_id = NEW.remote_development_stage_id
      AND blocker.owner_kind = 'EPISODE'
      AND blocker.owner_id = episode.id
      AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
      AND blocker.status = 'OPEN'
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.remote_development_stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND code.code_fingerprint = NEW.expected_code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND code.source_code_subject_revision =
          NEW.source_code_subject_revision
      AND code.source_code_subject_kind = NEW.source_code_subject_kind
      AND code.source_code_subject_id = NEW.source_code_subject_id)
BEGIN SELECT RAISE(ABORT,
    'CI base-repair compatibility reauthorization is not exact'); END;

CREATE TRIGGER ci_base_repair_reauthorization_identity_v322
BEFORE UPDATE OF id, source_authorization_id, normalization_due_id,
    normalization_operation_row_id, ci_repair_episode_id,
    source_operation_row_id, source_operation_id, blocker_id,
    adoption_command_id, task_id, task_epoch, remote_development_stage_id,
    stage_generation, semantic_attempt, expected_code_fingerprint,
    source_code_subject_revision, source_code_subject_kind,
    source_code_subject_id,
    expected_head_sha, expected_base_sha, claimed_at_ms
ON ci_base_repair_reauthorization_v322
BEGIN SELECT RAISE(ABORT,
    'CI base-repair compatibility reauthorization identity is immutable'); END;

CREATE TRIGGER ci_base_repair_reauthorization_status_v322
BEFORE UPDATE OF status, terminal_at_ms, terminal_evidence
ON ci_base_repair_reauthorization_v322
WHEN OLD.status <> 'CLAIMED'
  OR NEW.status NOT IN ('CONSUMED', 'CLOSED')
  OR NEW.terminal_at_ms IS NULL
  OR length(trim(NEW.terminal_evidence)) = 0
BEGIN SELECT RAISE(ABORT,
    'CI base-repair compatibility reauthorization transition is invalid'); END;

-- Once the strict normalized payload succeeds, one ordinary LOCAL_GIT writer
-- may adopt the already-created commit.  It can only fast-forward the exact
-- clean task branch from the frozen source head to a single direct child.
CREATE TABLE remote_repair_commit_adoption_operation_v322 (
    id                              TEXT    NOT NULL PRIMARY KEY,
    normalization_due_id            TEXT    NOT NULL UNIQUE
        REFERENCES remote_repair_result_normalization_due_v322(id),
    normalization_operation_row_id  TEXT    NOT NULL UNIQUE
        REFERENCES remote_repair_result_normalization_operation_v322(id),
    ci_repair_episode_id            TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    source_operation_row_id         TEXT    NOT NULL UNIQUE
        REFERENCES ci_repair_operation(id),
    source_operation_id             TEXT    NOT NULL UNIQUE,
    source_stage_turn_id            TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    source_base_repair_authorization_id TEXT
        REFERENCES ci_base_repair_authorization_v303(id),
    compatibility_reauthorization_id TEXT UNIQUE
        REFERENCES ci_base_repair_reauthorization_v322(id),
    blocker_id                      TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    command_id                      TEXT    NOT NULL UNIQUE,
    task_id                         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id     TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation                INTEGER NOT NULL CHECK (stage_generation > 0),
    operation_id                    TEXT    NOT NULL UNIQUE,
    dispatch_ticket_id              TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    adoption_attempt                INTEGER NOT NULL CHECK (adoption_attempt = 1),
    worktree_path                   TEXT    NOT NULL,
    expected_branch_name            TEXT    NOT NULL,
    source_code_subject_revision    INTEGER NOT NULL
        REFERENCES task_code_subject_revision_v320(revision),
    source_code_subject_kind        TEXT    NOT NULL CHECK (
        source_code_subject_kind IN (
            'REMOTE_WORKTREE', 'REMOTE_STEERING', 'CI_BASE_REPAIR',
            'LOCAL_BASE_SYNC')),
    source_code_subject_id          TEXT    NOT NULL,
    expected_code_fingerprint       TEXT    NOT NULL,
    expected_head_sha               TEXT    NOT NULL,
    expected_base_sha               TEXT    NOT NULL,
    candidate_capture_kind          TEXT    NOT NULL CHECK (
        candidate_capture_kind IN (
            'FROZEN_WRITER_PROOF_V1', 'LEGACY_REFLOG_WINDOW_V1')),
    candidate_code_fingerprint      TEXT,
    candidate_head_sha              TEXT,
    candidate_source_tree_sha       TEXT,
    candidate_result_tree_sha       TEXT,
    source_execution_started_at_ms  INTEGER NOT NULL CHECK (
        source_execution_started_at_ms >= 0),
    source_execution_finished_at_ms INTEGER NOT NULL CHECK (
        source_execution_finished_at_ms >= source_execution_started_at_ms),
    status                          TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'CANCELED', 'SUPERSEDED')),
    result_id                       TEXT UNIQUE,
    requested_at_ms                 INTEGER NOT NULL CHECK (requested_at_ms >= 0),
    completed_at_ms                 INTEGER,
    error_message                   TEXT,
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_id)) > 0
        AND length(trim(command_id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(worktree_path)) > 0
        AND length(trim(expected_branch_name)) > 0
        AND length(trim(source_code_subject_id)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0),
    CHECK ((candidate_capture_kind = 'FROZEN_WRITER_PROOF_V1'
            AND candidate_code_fingerprint IS NOT NULL
            AND candidate_head_sha IS NOT NULL
            AND candidate_source_tree_sha IS NOT NULL
            AND candidate_result_tree_sha IS NOT NULL)
        OR (candidate_capture_kind = 'LEGACY_REFLOG_WINDOW_V1'
            AND candidate_code_fingerprint IS NULL
            AND candidate_head_sha IS NULL
            AND candidate_source_tree_sha IS NULL
            AND candidate_result_tree_sha IS NULL)),
    CHECK ((status IN ('REQUESTED', 'DISPATCHED')
            AND result_id IS NULL AND completed_at_ms IS NULL
            AND error_message IS NULL)
        OR (status = 'SUCCEEDED'
            AND result_id IS NOT NULL AND completed_at_ms IS NOT NULL
            AND error_message IS NULL)
        OR (status IN ('FAILED', 'CANCELED', 'SUPERSEDED')
            AND result_id IS NULL AND completed_at_ms IS NOT NULL
            AND length(trim(error_message)) > 0)),
    CHECK ((source_base_repair_authorization_id IS NULL
            AND compatibility_reauthorization_id IS NULL)
        OR source_base_repair_authorization_id IS NOT NULL)
);

CREATE UNIQUE INDEX one_live_remote_repair_commit_adoption_v322
    ON remote_repair_commit_adoption_operation_v322(task_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TABLE remote_repair_commit_adoption_result_v322 (
    id                              TEXT    NOT NULL PRIMARY KEY,
    adoption_operation_row_id       TEXT    NOT NULL UNIQUE
        REFERENCES remote_repair_commit_adoption_operation_v322(id)
        ON DELETE CASCADE,
    operation_id                    TEXT    NOT NULL UNIQUE,
    task_id                         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id     TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation                INTEGER NOT NULL CHECK (stage_generation > 0),
    worktree_path                   TEXT    NOT NULL,
    expected_branch_name            TEXT    NOT NULL,
    expected_code_fingerprint       TEXT    NOT NULL,
    expected_head_sha               TEXT    NOT NULL,
    expected_base_sha               TEXT    NOT NULL,
    candidate_capture_kind          TEXT    NOT NULL CHECK (
        candidate_capture_kind IN (
            'FROZEN_WRITER_PROOF_V1', 'LEGACY_REFLOG_WINDOW_V1')),
    candidate_code_fingerprint      TEXT    NOT NULL,
    candidate_head_sha              TEXT    NOT NULL,
    candidate_parent_sha            TEXT    NOT NULL,
    candidate_source_tree_sha       TEXT    NOT NULL,
    candidate_result_tree_sha       TEXT    NOT NULL,
    candidate_branch_name           TEXT    NOT NULL,
    candidate_base_merge_base_sha   TEXT    NOT NULL,
    candidate_source_head_merge_base_sha TEXT NOT NULL,
    result_clean                    INTEGER NOT NULL CHECK (result_clean = 1),
    git_operation_state_clear       INTEGER NOT NULL CHECK (
        git_operation_state_clear = 1),
    writer_fencing_token            INTEGER NOT NULL CHECK (
        writer_fencing_token > 0),
    evidence                        TEXT    NOT NULL CHECK (
        json_valid(evidence) AND json_type(evidence) = 'object'),
    recorded_at_ms                  INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK (candidate_head_sha <> expected_head_sha),
    CHECK (candidate_parent_sha = expected_head_sha),
    CHECK (candidate_source_tree_sha <> candidate_result_tree_sha),
    CHECK (candidate_branch_name = expected_branch_name),
    CHECK (candidate_base_merge_base_sha = expected_base_sha),
    CHECK (candidate_source_head_merge_base_sha = expected_head_sha),
    CHECK (length(trim(id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(worktree_path)) > 0
        AND length(trim(candidate_code_fingerprint)) > 0
        AND length(trim(candidate_head_sha)) > 0
        AND length(trim(candidate_parent_sha)) > 0
        AND length(trim(candidate_source_tree_sha)) > 0
        AND length(trim(candidate_result_tree_sha)) > 0)
);

CREATE TRIGGER remote_repair_commit_adoption_result_immutable_v322
BEFORE UPDATE ON remote_repair_commit_adoption_result_v322
BEGIN SELECT RAISE(ABORT,
    'Remote repair commit-adoption result is immutable'); END;

CREATE TABLE remote_repair_commit_adoption_delivery_v322 (
    adoption_operation_row_id       TEXT    NOT NULL PRIMARY KEY
        REFERENCES remote_repair_commit_adoption_operation_v322(id)
        ON DELETE CASCADE,
    operation_id                    TEXT    NOT NULL UNIQUE,
    result_id                       TEXT UNIQUE
        REFERENCES remote_repair_commit_adoption_result_v322(id),
    raw_outcome                     TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest               TEXT    NOT NULL CHECK (
        length(raw_result_digest) = 64),
    acceptance                      TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    evidence                        TEXT    NOT NULL,
    recorded_at_ms                  INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK (length(trim(operation_id)) > 0
        AND length(trim(evidence)) > 0),
    CHECK ((raw_outcome = 'SUCCEEDED' AND acceptance = 'ACCEPTED'
            AND result_id IS NOT NULL)
        OR (raw_outcome IN ('FAILED', 'CANCELED', 'INDETERMINATE')
            AND result_id IS NULL)
        OR (acceptance = 'SUPERSEDED' AND result_id IS NULL))
);

CREATE TRIGGER remote_repair_commit_adoption_delivery_immutable_v322
BEFORE UPDATE ON remote_repair_commit_adoption_delivery_v322
BEGIN SELECT RAISE(ABORT,
    'Remote repair commit-adoption delivery is immutable'); END;

CREATE TRIGGER remote_repair_commit_adoption_operation_insert_v322
BEFORE INSERT ON remote_repair_commit_adoption_operation_v322
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1
    FROM remote_repair_result_normalization_due_v322 due
    JOIN remote_repair_result_normalization_operation_v322 normalization
      ON normalization.id = NEW.normalization_operation_row_id
    JOIN ci_repair_episode episode ON episode.id = NEW.ci_repair_episode_id
    JOIN ci_repair_operation source ON source.id = NEW.source_operation_row_id
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    JOIN tasks task ON task.id = NEW.task_id
    JOIN threads trunk ON trunk.id = task.thread_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_current_code_subject_fence_v322 code ON code.task_id = task.id
    JOIN task_code_identity identity ON identity.task_id = task.id
    LEFT JOIN ci_base_repair_authorization_v303 authorization
      ON authorization.id = NEW.source_base_repair_authorization_id
    LEFT JOIN ci_base_repair_reauthorization_v322 reauthorization
      ON reauthorization.id = NEW.compatibility_reauthorization_id
    WHERE due.id = NEW.normalization_due_id
      AND due.status = 'DISPATCHED'
      AND due.normalization_operation_row_id = normalization.id
      AND due.source_operation_row_id = NEW.source_operation_row_id
      AND due.source_operation_id = NEW.source_operation_id
      AND due.source_stage_turn_id = NEW.source_stage_turn_id
      AND due.source_base_repair_authorization_id IS
          NEW.source_base_repair_authorization_id
      AND due.blocker_id = NEW.blocker_id
      AND due.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND due.task_id = NEW.task_id
      AND due.task_epoch = NEW.task_epoch
      AND due.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND due.stage_generation = NEW.stage_generation
      AND due.source_code_subject_revision =
          NEW.source_code_subject_revision
      AND due.source_code_subject_kind = NEW.source_code_subject_kind
      AND due.source_code_subject_id = NEW.source_code_subject_id
      AND due.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND due.expected_head_sha = NEW.expected_head_sha
      AND due.expected_base_sha = NEW.expected_base_sha
      AND due.candidate_capture_kind = NEW.candidate_capture_kind
      AND due.candidate_code_fingerprint IS NEW.candidate_code_fingerprint
      AND due.candidate_head_sha IS NEW.candidate_head_sha
      AND due.candidate_source_tree_sha IS NEW.candidate_source_tree_sha
      AND due.candidate_result_tree_sha IS NEW.candidate_result_tree_sha
      AND due.source_execution_started_at_ms =
          NEW.source_execution_started_at_ms
      AND due.source_execution_finished_at_ms =
          NEW.source_execution_finished_at_ms
      AND normalization.normalization_due_id = due.id
      AND normalization.status = 'SUCCEEDED'
      AND normalization.source_code_subject_revision =
          NEW.source_code_subject_revision
      AND normalization.source_code_subject_kind =
          NEW.source_code_subject_kind
      AND normalization.source_code_subject_id = NEW.source_code_subject_id
      AND normalization.normalized_payload IS NOT NULL
      AND source.kind = 'FIX_STAGE_TURN'
      AND source.status = 'FAILED'
      AND source.operation_id = NEW.source_operation_id
      AND episode.status = 'FIXING'
      AND episode.fix_attempt_count + 1 = due.semantic_attempt
      AND blocker.task_id = NEW.task_id
      AND blocker.stage_id = NEW.remote_development_stage_id
      AND blocker.owner_kind = 'EPISODE'
      AND blocker.owner_id = episode.id
      AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
      AND blocker.status = 'OPEN'
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.remote_development_stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND code.code_fingerprint = NEW.expected_code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND code.source_code_subject_revision =
          NEW.source_code_subject_revision
      AND code.source_code_subject_kind = NEW.source_code_subject_kind
      AND code.source_code_subject_id = NEW.source_code_subject_id
      AND identity.worktree_path = NEW.worktree_path
      AND identity.branch_name = NEW.expected_branch_name
      AND ((source.base_repair_authorization_id IS NULL
              AND episode.classification <> 'BASE_DETERMINISTIC'
              AND NEW.compatibility_reauthorization_id IS NULL)
        OR (source.base_repair_authorization_id IS NOT NULL
              AND episode.classification = 'BASE_DETERMINISTIC'
              AND authorization.ci_repair_episode_id = episode.id
              AND authorization.semantic_attempt = due.semantic_attempt
              AND ((due.candidate_capture_kind = 'FROZEN_WRITER_PROOF_V1'
                      AND authorization.status = 'CLAIMED'
                      AND NEW.compatibility_reauthorization_id IS NULL)
                OR (due.candidate_capture_kind = 'LEGACY_REFLOG_WINDOW_V1'
                      AND authorization.status = 'CLOSED'
                      AND reauthorization.source_authorization_id =
                          authorization.id
                      AND reauthorization.normalization_due_id = due.id
                      AND reauthorization.normalization_operation_row_id =
                          normalization.id
                      AND reauthorization.adoption_command_id = NEW.command_id
                      AND reauthorization.source_code_subject_revision =
                          NEW.source_code_subject_revision
                      AND reauthorization.source_code_subject_kind =
                          NEW.source_code_subject_kind
                      AND reauthorization.source_code_subject_id =
                          NEW.source_code_subject_id
                      AND reauthorization.status = 'CLAIMED'))))
      AND NOT EXISTS (
          SELECT 1 FROM ci_repair_operation live
          WHERE live.ci_repair_episode_id = episode.id
            AND live.status IN ('REQUESTED', 'DISPATCHED'))
      AND NOT EXISTS (
          SELECT 1 FROM remote_repair_commit_adoption_operation_v322 live
          WHERE live.task_id = task.id
            AND live.status IN ('REQUESTED', 'DISPATCHED')))
BEGIN SELECT RAISE(ABORT,
    'Remote repair commit-adoption operation is not exact'); END;

CREATE TRIGGER remote_repair_commit_adoption_operation_identity_v322
BEFORE UPDATE OF id, normalization_due_id,
    normalization_operation_row_id, ci_repair_episode_id,
    source_operation_row_id, source_operation_id, source_stage_turn_id,
    source_base_repair_authorization_id, compatibility_reauthorization_id,
    blocker_id, command_id, task_id, task_epoch,
    remote_development_stage_id, stage_generation, operation_id,
    dispatch_ticket_id, adoption_attempt, worktree_path,
    expected_branch_name, source_code_subject_revision,
    source_code_subject_kind, source_code_subject_id,
    expected_code_fingerprint, expected_head_sha,
    expected_base_sha, candidate_capture_kind, candidate_code_fingerprint,
    candidate_head_sha, candidate_source_tree_sha, candidate_result_tree_sha,
    source_execution_started_at_ms, source_execution_finished_at_ms,
    requested_at_ms
ON remote_repair_commit_adoption_operation_v322
BEGIN SELECT RAISE(ABORT,
    'Remote repair commit-adoption operation identity is immutable'); END;

CREATE TRIGGER remote_repair_commit_adoption_operation_status_v322
BEFORE UPDATE OF status, result_id, completed_at_ms, error_message
ON remote_repair_commit_adoption_operation_v322
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status = 'DISPATCHED'
        AND NEW.result_id IS NULL AND NEW.completed_at_ms IS NULL
        AND EXISTS (
            SELECT 1 FROM dispatch_ticket ticket
            WHERE ticket.id = NEW.dispatch_ticket_id
              AND ticket.operation_id = NEW.operation_id
              AND ticket.status = 'REQUESTED'))
    OR (OLD.status = 'DISPATCHED'
        AND NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
        AND NEW.completed_at_ms IS NOT NULL
        AND ((NEW.status = 'SUCCEEDED' AND EXISTS (
                SELECT 1 FROM remote_repair_commit_adoption_result_v322 result
                WHERE result.id = NEW.result_id
                  AND result.adoption_operation_row_id = NEW.id
                  AND result.operation_id = NEW.operation_id))
            OR (NEW.status <> 'SUCCEEDED' AND NEW.result_id IS NULL))))
BEGIN SELECT RAISE(ABORT,
    'Remote repair commit-adoption transition is invalid'); END;

CREATE TRIGGER dispatch_ticket_remote_repair_commit_adoption_v322
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'ADOPT_NORMALIZED_REMOTE_REPAIR'
  OR NEW.callback_route = 'REMOTE_REPAIR_COMMIT_ADOPTION_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM remote_repair_commit_adoption_operation_v322 operation
        JOIN tasks task ON task.id = operation.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE operation.dispatch_ticket_id = NEW.id
          AND operation.operation_id = NEW.operation_id
          AND operation.status = 'REQUESTED'
          AND NEW.operation_kind = 'ADOPT_NORMALIZED_REMOTE_REPAIR'
          AND NEW.async_family = 'LOCAL_GIT'
          AND NEW.owner_kind = 'TASK'
          AND NEW.owner_id = operation.task_id
          AND NEW.callback_route = 'REMOTE_REPAIR_COMMIT_ADOPTION_RESULT'
          AND NEW.lane_mask = 16
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 1
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.task_id = operation.task_id
          AND NEW.task_epoch = operation.task_epoch
          AND NEW.stage_id = operation.remote_development_stage_id
          AND NEW.stage_generation = operation.stage_generation
          AND NEW.attempt = operation.adoption_attempt
          AND NEW.expected_code_fingerprint =
              operation.expected_code_fingerprint
          AND NEW.expected_head_sha = operation.expected_head_sha
          AND NEW.expected_base_sha = operation.expected_base_sha
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'Remote repair commit-adoption DispatchTicket is not exact') END;
END;

CREATE TRIGGER remote_repair_commit_adoption_result_insert_v322
BEFORE INSERT ON remote_repair_commit_adoption_result_v322
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_repair_commit_adoption_operation_v322 operation
    JOIN remote_repair_result_normalization_due_v322 due
      ON due.id = operation.normalization_due_id
    JOIN ci_repair_episode episode
      ON episode.id = operation.ci_repair_episode_id
    JOIN task_blocker blocker ON blocker.id = operation.blocker_id
    JOIN dispatch_ticket ticket ON ticket.id = operation.dispatch_ticket_id
    JOIN capacity_lease capacity
      ON capacity.id = ticket.capacity_lease_id
     AND capacity.operation_id = ticket.operation_id
    JOIN worktree_leases lease
      ON lease.operation_id = operation.operation_id
     AND lease.task_id = operation.task_id
     AND lease.task_epoch = operation.task_epoch
    JOIN tasks task ON task.id = operation.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_current_code_subject_fence_v322 code ON code.task_id = task.id
    JOIN task_code_identity identity ON identity.task_id = task.id
    WHERE operation.id = NEW.adoption_operation_row_id
      AND operation.operation_id = NEW.operation_id
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND operation.worktree_path = NEW.worktree_path
      AND operation.expected_branch_name = NEW.expected_branch_name
      AND operation.expected_code_fingerprint =
          NEW.expected_code_fingerprint
      AND operation.expected_head_sha = NEW.expected_head_sha
      AND operation.expected_base_sha = NEW.expected_base_sha
      AND operation.candidate_capture_kind = NEW.candidate_capture_kind
      AND operation.status = 'DISPATCHED'
      AND due.source_code_subject_revision =
          operation.source_code_subject_revision
      AND due.source_code_subject_kind = operation.source_code_subject_kind
      AND due.source_code_subject_id = operation.source_code_subject_id
      AND episode.fix_attempt_count + 1 = due.semantic_attempt
      AND episode.status = 'FIXING'
      AND episode.task_id = operation.task_id
      AND episode.task_epoch = operation.task_epoch
      AND episode.remote_development_stage_id =
          operation.remote_development_stage_id
      AND episode.stage_generation = operation.stage_generation
      AND episode.subject_head_sha = operation.expected_head_sha
      AND episode.subject_base_sha = operation.expected_base_sha
      AND blocker.task_id = operation.task_id
      AND blocker.stage_id = operation.remote_development_stage_id
      AND blocker.owner_kind = 'EPISODE'
      AND blocker.owner_id = episode.id
      AND blocker.subject_revision = operation.expected_head_sha
      AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
      AND blocker.status = 'OPEN'
      AND ticket.operation_kind = 'ADOPT_NORMALIZED_REMOTE_REPAIR'
      AND ticket.async_family = 'LOCAL_GIT'
      AND ticket.status = 'RUNNING'
      AND ticket.writer_required = 1
      AND capacity.workflow_source = 'V2'
      AND capacity.task_id = operation.task_id
      AND capacity.task_epoch = operation.task_epoch
      AND capacity.writer_required = 1
      AND capacity.fencing_token = NEW.writer_fencing_token
      AND capacity.released_at_ms IS NULL
      AND capacity.expires_at_ms > NEW.recorded_at_ms
      AND lease.workflow_version = 'V2'
      AND lease.worktree_path = operation.worktree_path
      AND lease.fencing_token = NEW.writer_fencing_token
      AND lease.lease_owner = capacity.holder
      AND lease.expires_at_ms > NEW.recorded_at_ms
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = operation.task_epoch
      AND current.stage_id = operation.remote_development_stage_id
      AND current.stage_generation = operation.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND code.code_fingerprint = operation.expected_code_fingerprint
      AND code.head_sha = operation.expected_head_sha
      AND code.base_sha = operation.expected_base_sha
      AND code.source_code_subject_revision =
          operation.source_code_subject_revision
      AND code.source_code_subject_kind = operation.source_code_subject_kind
      AND code.source_code_subject_id = operation.source_code_subject_id
      AND identity.worktree_path = operation.worktree_path
      AND identity.branch_name = operation.expected_branch_name
      AND NEW.candidate_parent_sha = operation.expected_head_sha
      AND NEW.candidate_branch_name = operation.expected_branch_name
      AND NEW.candidate_base_merge_base_sha = operation.expected_base_sha
      AND NEW.candidate_source_head_merge_base_sha =
          operation.expected_head_sha
      AND NEW.result_clean = 1
      AND NEW.git_operation_state_clear = 1
      AND ((operation.candidate_capture_kind = 'FROZEN_WRITER_PROOF_V1'
              AND NEW.candidate_code_fingerprint =
                  operation.candidate_code_fingerprint
              AND NEW.candidate_head_sha = operation.candidate_head_sha
              AND NEW.candidate_source_tree_sha =
                  operation.candidate_source_tree_sha
              AND NEW.candidate_result_tree_sha =
                  operation.candidate_result_tree_sha)
        OR operation.candidate_capture_kind = 'LEGACY_REFLOG_WINDOW_V1')
      AND json_extract(NEW.evidence, '$.schemaVersion') = 1
      AND json_extract(NEW.evidence, '$.operationId') = NEW.operation_id
      AND json_extract(NEW.evidence, '$.candidateCaptureKind') =
          NEW.candidate_capture_kind
      AND json_extract(NEW.evidence, '$.candidateHeadSha') =
          NEW.candidate_head_sha
      AND json_extract(NEW.evidence, '$.candidateParentSha') =
          NEW.candidate_parent_sha
      AND json_extract(NEW.evidence, '$.sourceCodeSubjectRevision') =
          operation.source_code_subject_revision
      AND json_extract(NEW.evidence, '$.sourceCodeSubjectKind') =
          operation.source_code_subject_kind
      AND json_extract(NEW.evidence, '$.sourceCodeSubjectId') =
          operation.source_code_subject_id
      AND json_extract(NEW.evidence, '$.candidateCount') = 1
      AND json_extract(NEW.evidence, '$.sourceExecutionStartedAtMs') =
          due.source_execution_started_at_ms
      AND json_extract(NEW.evidence, '$.sourceExecutionFinishedAtMs') =
          due.source_execution_finished_at_ms)
BEGIN SELECT RAISE(ABORT,
    'Remote repair commit-adoption result is not exact'); END;

CREATE TRIGGER remote_repair_commit_adoption_delivery_insert_v322
BEFORE INSERT ON remote_repair_commit_adoption_delivery_v322
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_repair_commit_adoption_operation_v322 operation
    JOIN dispatch_ticket ticket ON ticket.id = operation.dispatch_ticket_id
    LEFT JOIN remote_repair_commit_adoption_result_v322 result
      ON result.id = NEW.result_id
    WHERE operation.id = NEW.adoption_operation_row_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN (
          'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
      AND operation.result_id IS NEW.result_id
      AND ticket.operation_id = NEW.operation_id
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND ticket.pending_result_operation_id = NEW.operation_id
      AND ticket.pending_result_task_epoch = operation.task_epoch
      AND ticket.pending_result_stage_id =
          operation.remote_development_stage_id
      AND ticket.pending_result_stage_generation =
          operation.stage_generation
      AND ticket.pending_result_attempt = operation.adoption_attempt
      AND ticket.pending_result_expected_code_fingerprint =
          operation.expected_code_fingerprint
      AND ticket.pending_result_expected_head_sha =
          operation.expected_head_sha
      AND ticket.pending_result_expected_base_sha =
          operation.expected_base_sha
      AND ((NEW.acceptance = 'SUPERSEDED'
              AND operation.status = 'SUPERSEDED')
        OR (NEW.acceptance = 'ACCEPTED'
              AND operation.status <> 'SUPERSEDED'))
      AND ((operation.status = 'SUCCEEDED'
              AND NEW.raw_outcome = 'SUCCEEDED'
              AND result.adoption_operation_row_id = operation.id)
        OR operation.status <> 'SUCCEEDED'))
BEGIN SELECT RAISE(ABORT,
    'Remote repair commit-adoption delivery is not exact'); END;

-- A closed pre-V322 authorization is effective only after its exact adopted
-- candidate is the current durable worktree subject.  Ordinary CLAIMED
-- authorization behavior remains unchanged.
DROP TRIGGER ci_repair_operation_base_authorization_v303;
CREATE TRIGGER ci_repair_operation_base_authorization_v303
BEFORE INSERT ON ci_repair_operation
WHEN EXISTS (
        SELECT 1 FROM ci_repair_episode episode
        WHERE episode.id = NEW.ci_repair_episode_id
          AND episode.classification = 'BASE_DETERMINISTIC')
 AND (NEW.kind <> 'RERUN' AND NOT EXISTS (
        SELECT 1
        FROM ci_base_repair_authorization_v303 authorization
        WHERE authorization.id = NEW.base_repair_authorization_id
          AND authorization.ci_repair_episode_id = NEW.ci_repair_episode_id
          AND authorization.semantic_attempt = NEW.semantic_attempt
          AND authorization.subject_base_sha = NEW.expected_base_sha
          AND (NEW.kind <> 'FIX_STAGE_TURN'
            OR authorization.expected_worktree_head_sha = NEW.expected_head_sha)
          AND (authorization.status = 'CLAIMED'
            OR (authorization.status = 'CLOSED' AND EXISTS (
                SELECT 1
                FROM ci_base_repair_reauthorization_v322 reauthorization
                JOIN remote_repair_commit_adoption_operation_v322 adoption
                  ON adoption.compatibility_reauthorization_id =
                     reauthorization.id
                JOIN remote_repair_commit_adoption_result_v322 result
                  ON result.id = adoption.result_id
                JOIN remote_repair_commit_adoption_delivery_v322 delivery
                  ON delivery.adoption_operation_row_id = adoption.id
                JOIN remote_worktree_subject subject
                  ON subject.source_operation_id = adoption.operation_id
                WHERE reauthorization.source_authorization_id = authorization.id
                  AND reauthorization.ci_repair_episode_id =
                      NEW.ci_repair_episode_id
                  AND reauthorization.semantic_attempt = NEW.semantic_attempt
                  AND reauthorization.status = 'CLAIMED'
                  AND adoption.status = 'SUCCEEDED'
                  AND delivery.raw_outcome = 'SUCCEEDED'
                  AND delivery.acceptance = 'ACCEPTED'
                  AND result.candidate_code_fingerprint =
                      NEW.expected_code_fingerprint
                  AND result.candidate_head_sha = NEW.expected_head_sha
                  AND subject.task_id = NEW.task_id
                  AND subject.task_epoch = NEW.task_epoch
                  AND subject.remote_development_stage_id =
                      NEW.remote_development_stage_id
                  AND subject.stage_generation = NEW.stage_generation
                  AND subject.code_fingerprint = NEW.expected_code_fingerprint
                  AND subject.head_sha = NEW.expected_head_sha
                  AND subject.base_sha = NEW.expected_base_sha)))))
BEGIN SELECT RAISE(ABORT,
    'Base repair Operation lacks exact authorization'); END;

DROP TRIGGER ci_base_repair_rewrite_result_insert_v303;
CREATE TRIGGER ci_base_repair_rewrite_result_insert_v303
BEFORE INSERT ON ci_base_repair_rewrite_result_v303
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_base_repair_authorization_v303 authorization
    JOIN ci_repair_operation operation
      ON operation.id = NEW.ci_repair_operation_id
    WHERE authorization.id = NEW.authorization_id
      AND operation.base_repair_authorization_id = authorization.id
      AND operation.ci_repair_episode_id = authorization.ci_repair_episode_id
      AND operation.kind = 'VALIDATE'
      AND operation.semantic_attempt = authorization.semantic_attempt
      AND operation.expected_head_sha = NEW.input_head_sha
      AND operation.result_head_sha = NEW.output_head_sha
      AND operation.status IN ('SUCCEEDED', 'FAILED')
      AND (authorization.status = 'CLAIMED'
        OR (authorization.status = 'CLOSED' AND EXISTS (
            SELECT 1
            FROM ci_base_repair_reauthorization_v322 reauthorization
            JOIN remote_repair_commit_adoption_operation_v322 adoption
              ON adoption.compatibility_reauthorization_id =
                 reauthorization.id
            JOIN remote_repair_commit_adoption_result_v322 result
              ON result.id = adoption.result_id
            JOIN remote_repair_commit_adoption_delivery_v322 delivery
              ON delivery.adoption_operation_row_id = adoption.id
            WHERE reauthorization.source_authorization_id = authorization.id
              AND reauthorization.status = 'CLAIMED'
              AND adoption.status = 'SUCCEEDED'
              AND delivery.raw_outcome = 'SUCCEEDED'
              AND delivery.acceptance = 'ACCEPTED'
              AND result.candidate_head_sha = operation.expected_head_sha))))
BEGIN SELECT RAISE(ABORT,
    'Base repair rewrite result is not exact'); END;

-- A successful adoption is an ordinary CI worktree subject.  The failed
-- source StageTurn stays immutable; the adoption operation is its explicit,
-- lease-proven successor source.
DROP TRIGGER IF EXISTS remote_worktree_subject_insert;
CREATE TRIGGER remote_worktree_subject_insert
BEFORE INSERT ON remote_worktree_subject
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_repair_operation operation
    WHERE NEW.source_kind = 'CI_STAGE_TURN'
      AND operation.operation_id = NEW.source_operation_id
      AND operation.kind = 'FIX_STAGE_TURN'
      AND operation.status = 'SUCCEEDED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id = NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND operation.expected_base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM ci_repair_fix_continuation_operation_v318 operation
    WHERE NEW.source_kind = 'CI_STAGE_TURN'
      AND operation.operation_id = NEW.source_operation_id
      AND operation.status = 'SUCCEEDED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id = NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND operation.expected_base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM remote_repair_commit_adoption_operation_v322 operation
    JOIN remote_repair_commit_adoption_result_v322 result
      ON result.id = operation.result_id
    JOIN remote_repair_commit_adoption_delivery_v322 delivery
      ON delivery.adoption_operation_row_id = operation.id
    WHERE NEW.source_kind = 'CI_STAGE_TURN'
      AND operation.operation_id = NEW.source_operation_id
      AND operation.status = 'SUCCEEDED'
      AND delivery.operation_id = operation.operation_id
      AND delivery.raw_outcome = 'SUCCEEDED'
      AND delivery.acceptance = 'ACCEPTED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id = NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND result.candidate_code_fingerprint = NEW.code_fingerprint
      AND result.candidate_head_sha = NEW.head_sha
      AND operation.expected_base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM branch_sync_dispatch_operation operation
    JOIN branch_sync_effect_step step
      ON step.id = operation.branch_sync_effect_step_id
    WHERE NEW.source_kind = 'BRANCH_EFFECT'
      AND operation.operation_id = NEW.source_operation_id
      AND operation.kind IN (
          'MECHANICAL_REBASE', 'VALIDATE', 'FORCE_WITH_LEASE_PUSH')
      AND operation.status = 'SUCCEEDED' AND step.status = 'SUCCEEDED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id = NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND operation.target_base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM branch_sync_dispatch_operation operation
    JOIN branch_sync_effect_step step
      ON step.id = operation.branch_sync_effect_step_id
    WHERE NEW.source_kind = 'BRANCH_STAGE_TURN'
      AND operation.operation_id = NEW.source_operation_id
      AND operation.kind = 'CONFLICT_REPAIR'
      AND operation.status = 'SUCCEEDED' AND step.status = 'SUCCEEDED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.remote_development_stage_id = NEW.remote_development_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND operation.target_base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM v2_user_remote_action_v270 action
    WHERE NEW.source_kind = 'USER_CI_TRIGGER'
      AND action.operation_id = NEW.source_operation_id
      AND action.semantic_action = 'TRIGGER_CI_EMPTY_COMMIT'
      AND action.status = 'SUCCEEDED'
      AND action.task_id = NEW.task_id
      AND action.task_epoch = NEW.task_epoch
      AND action.remote_stage_id = NEW.remote_development_stage_id
      AND action.stage_generation = NEW.stage_generation
      AND action.expected_code_fingerprint = NEW.code_fingerprint
      AND action.expected_base_sha = NEW.base_sha
      AND action.expected_head_sha <> NEW.head_sha
      AND action.external_effect_id = 'ci-trigger-empty-commit:' || NEW.head_sha)
BEGIN SELECT RAISE(ABORT,
    'Worktree subject lacks exact successful repair or CI-trigger evidence'); END;

DROP TRIGGER remote_code_subject_insert;
CREATE TRIGGER remote_code_subject_insert
BEFORE INSERT ON remote_code_subject
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_development_stage remote
    JOIN stage_turn turn ON turn.id = NEW.stage_turn_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND turn.stage_id = remote.stage_id
      AND turn.stage_generation = remote.generation
      AND turn.task_epoch = NEW.task_epoch
      AND turn.expected_code_fingerprint = NEW.source_code_fingerprint
      AND turn.expected_head_sha = NEW.source_head_sha
      AND turn.expected_base_sha = NEW.source_base_sha
      AND turn.status = 'SUCCEEDED'
    UNION ALL
    SELECT 1
    FROM remote_repair_commit_adoption_operation_v322 adoption
    JOIN remote_repair_commit_adoption_result_v322 result
      ON result.id = adoption.result_id
    JOIN remote_repair_commit_adoption_delivery_v322 delivery
      ON delivery.adoption_operation_row_id = adoption.id
    JOIN stage_turn source_turn
      ON source_turn.id = adoption.source_stage_turn_id
    WHERE adoption.status = 'SUCCEEDED'
      AND delivery.operation_id = adoption.operation_id
      AND delivery.raw_outcome = 'SUCCEEDED'
      AND delivery.acceptance = 'ACCEPTED'
      AND adoption.source_stage_turn_id = NEW.stage_turn_id
      AND adoption.task_id = NEW.task_id
      AND adoption.task_epoch = NEW.task_epoch
      AND adoption.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND adoption.stage_generation = NEW.stage_generation
      AND adoption.expected_code_fingerprint = NEW.source_code_fingerprint
      AND adoption.expected_head_sha = NEW.source_head_sha
      AND adoption.expected_base_sha = NEW.source_base_sha
      AND result.candidate_code_fingerprint = NEW.code_fingerprint
      AND result.candidate_head_sha = NEW.head_sha
      AND adoption.expected_base_sha = NEW.base_sha
      AND source_turn.purpose = 'REMOTE_CI_REPAIR'
      AND source_turn.status = 'FAILED')
BEGIN SELECT RAISE(ABORT,
    'Remote code subject lacks exact successful StageTurn or adoption'); END;

-- V318 charges a semantic fix only for one exact changed-tree result.  Keep
-- that proof unchanged and add the independently writer-proven V322 adoption
-- as the only equivalent accepted result.
DROP TRIGGER IF EXISTS ci_repair_episode_counter_update;
CREATE TRIGGER ci_repair_episode_counter_update
BEFORE UPDATE OF rerun_count, fix_attempt_count, delivery_retry_count, push_count
        ON ci_repair_episode
WHEN NEW.rerun_count NOT BETWEEN OLD.rerun_count AND OLD.rerun_count + 1
  OR NEW.fix_attempt_count NOT BETWEEN OLD.fix_attempt_count AND OLD.fix_attempt_count + 1
  OR NEW.delivery_retry_count NOT BETWEEN OLD.delivery_retry_count AND OLD.delivery_retry_count + 1
  OR NEW.push_count NOT BETWEEN OLD.push_count AND OLD.push_count + 1
  OR (NEW.fix_attempt_count = OLD.fix_attempt_count + 1 AND NOT (
      EXISTS (
          SELECT 1 FROM ci_repair_fix_tree_result_v318 result
          WHERE result.ci_repair_episode_id = NEW.id
            AND result.semantic_attempt = NEW.fix_attempt_count
            AND result.disposition = 'CHANGED')
      OR EXISTS (
          SELECT 1
          FROM remote_repair_commit_adoption_operation_v322 adoption
          JOIN remote_repair_result_normalization_due_v322 due
            ON due.id = adoption.normalization_due_id
          JOIN remote_repair_result_normalization_operation_v322 normalization
            ON normalization.id = adoption.normalization_operation_row_id
          JOIN remote_repair_commit_adoption_result_v322 result
            ON result.id = adoption.result_id
          JOIN remote_repair_commit_adoption_delivery_v322 delivery
            ON delivery.adoption_operation_row_id = adoption.id
          JOIN task_blocker blocker ON blocker.id = adoption.blocker_id
          JOIN remote_code_subject code
            ON code.stage_turn_id = adoption.source_stage_turn_id
          JOIN remote_worktree_subject worktree
            ON worktree.source_operation_id = adoption.operation_id
          WHERE adoption.ci_repair_episode_id = NEW.id
            AND due.semantic_attempt = NEW.fix_attempt_count
            AND due.status = 'DISPATCHED'
            AND normalization.status = 'SUCCEEDED'
            AND adoption.status = 'SUCCEEDED'
            AND delivery.operation_id = adoption.operation_id
            AND delivery.result_id = result.id
            AND delivery.raw_outcome = 'SUCCEEDED'
            AND delivery.acceptance = 'ACCEPTED'
            AND blocker.owner_kind = 'EPISODE'
            AND blocker.owner_id = NEW.id
            AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
            AND blocker.status = 'OPEN'
            AND code.task_id = adoption.task_id
            AND code.task_epoch = adoption.task_epoch
            AND code.remote_development_stage_id =
                adoption.remote_development_stage_id
            AND code.stage_generation = adoption.stage_generation
            AND code.source_code_fingerprint =
                adoption.expected_code_fingerprint
            AND code.source_head_sha = adoption.expected_head_sha
            AND code.source_base_sha = adoption.expected_base_sha
            AND code.code_fingerprint = result.candidate_code_fingerprint
            AND code.head_sha = result.candidate_head_sha
            AND code.base_sha = adoption.expected_base_sha
            AND worktree.task_id = adoption.task_id
            AND worktree.task_epoch = adoption.task_epoch
            AND worktree.remote_development_stage_id =
                adoption.remote_development_stage_id
            AND worktree.stage_generation = adoption.stage_generation
            AND worktree.source_kind = 'CI_STAGE_TURN'
            AND worktree.code_fingerprint =
                result.candidate_code_fingerprint
            AND worktree.head_sha = result.candidate_head_sha
            AND worktree.base_sha = adoption.expected_base_sha)))
BEGIN SELECT RAISE(ABORT,
    'CI repair counters require one exact accepted result'); END;

-- Forward-only compatibility creates dues only from the migration-frozen
-- allowlist.  Inexact historical failures remain blocked for manual recovery.
INSERT INTO remote_repair_result_normalization_due_v322(
    id, ci_repair_episode_id, source_operation_row_id,
    source_operation_id, source_stage_turn_id,
    source_dispatch_ticket_id, source_agent_execution_id,
    source_base_repair_authorization_id, blocker_id,
    task_id, task_epoch, remote_development_stage_id, stage_generation,
    semantic_attempt, execution_attempt,
    source_code_subject_revision, source_code_subject_kind,
    source_code_subject_id,
    expected_code_fingerprint, expected_head_sha, expected_base_sha,
    source_malformed_output, source_raw_result_digest,
    required_result_shape, candidate_capture_kind,
    candidate_code_fingerprint, candidate_head_sha, candidate_parent_sha,
    candidate_base_sha, candidate_clean, candidate_merge_base_sha,
    candidate_source_tree_sha, candidate_result_tree_sha,
    candidate_source_head_merge_base_sha, candidate_branch_name,
    source_execution_started_at_ms, source_execution_finished_at_ms,
    status, recorded_at_ms)
SELECT 'v322-normalization-due:' || eligibility.source_operation_row_id,
       eligibility.ci_repair_episode_id,
       eligibility.source_operation_row_id,
       eligibility.source_operation_id,
       eligibility.source_stage_turn_id,
       eligibility.source_dispatch_ticket_id,
       eligibility.source_agent_execution_id,
       eligibility.source_base_repair_authorization_id,
       eligibility.blocker_id, eligibility.task_id, eligibility.task_epoch,
       eligibility.remote_development_stage_id,
       eligibility.stage_generation, eligibility.semantic_attempt,
       eligibility.execution_attempt,
       eligibility.source_code_subject_revision,
       eligibility.source_code_subject_kind,
       eligibility.source_code_subject_id,
       eligibility.expected_code_fingerprint,
       eligibility.expected_head_sha, eligibility.expected_base_sha,
       eligibility.source_malformed_output,
       eligibility.source_raw_result_digest,
       '{"schemaVersion":1,"summary":"string"}',
       'LEGACY_REFLOG_WINDOW_V1',
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
       eligibility.source_execution_started_at_ms,
       eligibility.source_execution_finished_at_ms,
       'PENDING', eligibility.recorded_at_ms
FROM remote_repair_legacy_eligibility_v322 eligibility;
