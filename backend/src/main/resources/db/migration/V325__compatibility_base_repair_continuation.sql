-- Continue one pre-V322 base repair after exact normalized-commit adoption.
-- The immutable closed source authorization is effective only through its
-- claimed compatibility authority and exact adopted/rewrite subjects.

DROP TRIGGER IF EXISTS ci_repair_operation_base_authorization_v303;
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
                JOIN remote_repair_commit_adoption_result_v322 adopted
                  ON adopted.id = adoption.result_id
                JOIN remote_repair_commit_adoption_delivery_v322 delivery
                  ON delivery.adoption_operation_row_id = adoption.id
                JOIN remote_worktree_subject worktree
                  ON worktree.source_operation_id = adoption.operation_id
                JOIN task_current_code_subject_fence_v322 current_code
                  ON current_code.task_id = NEW.task_id
                WHERE reauthorization.source_authorization_id =
                      authorization.id
                  AND reauthorization.ci_repair_episode_id =
                      NEW.ci_repair_episode_id
                  AND reauthorization.semantic_attempt = NEW.semantic_attempt
                  AND reauthorization.task_id = NEW.task_id
                  AND reauthorization.task_epoch = NEW.task_epoch
                  AND reauthorization.remote_development_stage_id =
                      NEW.remote_development_stage_id
                  AND reauthorization.stage_generation = NEW.stage_generation
                  AND reauthorization.status = 'CLAIMED'
                  AND adoption.source_base_repair_authorization_id =
                      authorization.id
                  AND adoption.ci_repair_episode_id =
                      NEW.ci_repair_episode_id
                  AND adoption.task_id = NEW.task_id
                  AND adoption.task_epoch = NEW.task_epoch
                  AND adoption.remote_development_stage_id =
                      NEW.remote_development_stage_id
                  AND adoption.stage_generation = NEW.stage_generation
                  AND adoption.status = 'SUCCEEDED'
                  AND adopted.adoption_operation_row_id = adoption.id
                  AND delivery.operation_id = adoption.operation_id
                  AND delivery.result_id = adopted.id
                  AND delivery.raw_outcome = 'SUCCEEDED'
                  AND delivery.acceptance = 'ACCEPTED'
                  AND worktree.task_id = NEW.task_id
                  AND worktree.task_epoch = NEW.task_epoch
                  AND worktree.remote_development_stage_id =
                      NEW.remote_development_stage_id
                  AND worktree.stage_generation = NEW.stage_generation
                  AND worktree.code_fingerprint =
                      adopted.candidate_code_fingerprint
                  AND worktree.head_sha = adopted.candidate_head_sha
                  AND worktree.base_sha = NEW.expected_base_sha
                  AND current_code.task_epoch = NEW.task_epoch
                  AND ((NEW.kind = 'VALIDATE'
                        AND NEW.expected_code_fingerprint =
                            adopted.candidate_code_fingerprint
                        AND NEW.expected_head_sha =
                            adopted.candidate_head_sha
                        AND current_code.source_code_subject_kind =
                            'REMOTE_WORKTREE'
                        AND current_code.source_code_subject_id = worktree.id
                        AND current_code.code_fingerprint =
                            adopted.candidate_code_fingerprint
                        AND current_code.head_sha =
                            adopted.candidate_head_sha
                        AND current_code.base_sha = NEW.expected_base_sha)
                    OR (NEW.kind IN ('BRAIN_REVIEW', 'PUSH_HEAD')
                        AND EXISTS (
                        SELECT 1
                        FROM ci_base_repair_rewrite_result_v303 rewrite
                        JOIN ci_repair_operation validation
                          ON validation.id =
                             rewrite.ci_repair_operation_id
                        JOIN ci_base_repair_subject_v303 subject
                          ON subject.authorization_id = authorization.id
                         AND subject.ci_repair_operation_id = validation.id
                        WHERE rewrite.authorization_id = authorization.id
                          AND rewrite.validation_outcome = 'PASSED'
                          AND rewrite.input_head_sha =
                              adopted.candidate_head_sha
                          AND rewrite.output_head_sha = subject.head_sha
                          AND validation.base_repair_authorization_id =
                              authorization.id
                          AND validation.ci_repair_episode_id =
                              NEW.ci_repair_episode_id
                          AND validation.task_id = NEW.task_id
                          AND validation.task_epoch = NEW.task_epoch
                          AND validation.remote_development_stage_id =
                              NEW.remote_development_stage_id
                          AND validation.stage_generation =
                              NEW.stage_generation
                          AND validation.kind = 'VALIDATE'
                          AND validation.semantic_attempt =
                              NEW.semantic_attempt
                          AND validation.status = 'SUCCEEDED'
                          AND validation.expected_code_fingerprint =
                              adopted.candidate_code_fingerprint
                          AND validation.expected_head_sha =
                              adopted.candidate_head_sha
                          AND validation.expected_base_sha =
                              NEW.expected_base_sha
                          AND subject.task_id = NEW.task_id
                          AND subject.task_epoch = NEW.task_epoch
                          AND subject.remote_development_stage_id =
                              NEW.remote_development_stage_id
                          AND subject.stage_generation =
                              NEW.stage_generation
                          AND subject.code_fingerprint =
                              NEW.expected_code_fingerprint
                          AND subject.head_sha = NEW.expected_head_sha
                          AND subject.base_sha =
                              NEW.expected_base_sha
                          AND current_code.source_code_subject_kind =
                              'CI_BASE_REPAIR'
                          AND current_code.source_code_subject_id = subject.id
                          AND current_code.code_fingerprint =
                              subject.code_fingerprint
                          AND current_code.head_sha = subject.head_sha
                          AND current_code.base_sha = subject.base_sha))))))))
BEGIN SELECT RAISE(ABORT,
    'Base repair Operation lacks exact authorization'); END;

DROP TRIGGER IF EXISTS remote_repair_brain_replacement_operation_insert_v309;
CREATE TRIGGER remote_repair_brain_replacement_operation_insert_v309
BEFORE INSERT ON remote_repair_brain_replacement_operation_v309
WHEN NEW.status <> 'REQUESTED'
  OR NEW.verdict IS NOT NULL OR NEW.finding_count IS NOT NULL
  OR NEW.result_summary IS NOT NULL
  OR NEW.result_code_fingerprint IS NOT NULL
  OR NEW.result_head_sha IS NOT NULL OR NEW.result_evidence IS NOT NULL
  OR NEW.completed_at_ms IS NOT NULL OR NEW.error_message IS NOT NULL
  OR NOT EXISTS (
      WITH predecessor AS (
          SELECT 'CI' AS family, 'ORIGINAL' AS source_kind,
                 operation.id AS row_id,
                 operation.ci_repair_episode_id AS episode_id,
                 NULL AS step_id, operation.base_repair_authorization_id,
                 operation.task_id, operation.task_epoch,
                 operation.remote_development_stage_id AS stage_id,
                 operation.stage_generation,
                 operation.semantic_attempt,
                 operation.semantic_attempt AS execution_attempt,
                 operation.task_turn_id, operation.operation_id,
                 operation.expected_code_fingerprint,
                 operation.expected_head_sha, operation.expected_base_sha,
                 operation.status
          FROM ci_repair_operation operation
          WHERE operation.kind = 'BRAIN_REVIEW'
          UNION ALL
          SELECT 'BRANCH', 'ORIGINAL', operation.id,
                 operation.branch_sync_episode_id,
                 operation.branch_sync_effect_step_id, NULL,
                 operation.task_id, operation.task_epoch,
                 operation.remote_development_stage_id,
                 operation.stage_generation, operation.semantic_attempt,
                 operation.semantic_attempt AS execution_attempt,
                 operation.task_turn_id, operation.operation_id,
                 operation.expected_code_fingerprint,
                 operation.expected_head_sha, operation.expected_base_sha,
                 operation.status
          FROM branch_sync_dispatch_operation operation
          WHERE operation.kind = 'BRAIN_REVIEW'
          UNION ALL
          SELECT operation.family, 'REPLACEMENT', operation.id,
                 COALESCE(operation.ci_repair_episode_id,
                          operation.branch_sync_episode_id),
                 operation.branch_sync_effect_step_id,
                 operation.base_repair_authorization_id,
                 operation.task_id, operation.task_epoch,
                 operation.remote_development_stage_id,
                 operation.stage_generation, operation.semantic_attempt,
                 operation.execution_attempt,
                 operation.task_turn_id, operation.operation_id,
                 operation.expected_code_fingerprint,
                 operation.expected_head_sha, operation.expected_base_sha,
                 operation.status
          FROM remote_repair_brain_replacement_operation_v309 operation)
      SELECT 1
      FROM predecessor previous
      JOIN task_turn failed ON failed.id = previous.task_turn_id
      JOIN remote_repair_brain_failure_receipt_v309 failure
        ON failure.id = NEW.predecessor_failure_receipt_id
       AND failure.family = previous.family
       AND failure.source_kind = previous.source_kind
       AND failure.source_operation_row_id = previous.row_id
       AND failure.task_turn_id = previous.task_turn_id
       AND failure.operation_id = previous.operation_id
      JOIN dispatch_ticket failed_ticket
        ON failed_ticket.operation_id = previous.operation_id
       AND failed_ticket.owner_kind = 'TASK_TURN'
       AND failed_ticket.owner_id = failed.id
      JOIN task_blocker blocker
        ON blocker.task_id = previous.task_id
       AND blocker.stage_id IS NULL
       AND blocker.owner_kind = 'TASK'
       AND blocker.owner_id = previous.task_id
       AND blocker.subject_revision = failed.id
       AND blocker.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
       AND blocker.status = 'OPEN'
      JOIN task_brain_protocol_failure_receipt_v300 failure_receipt
        ON failure_receipt.task_id = previous.task_id
       AND failure_receipt.proof_id = blocker.id
       AND failure_receipt.subject_operation_id = previous.operation_id
       AND failure_receipt.subject_attempt = previous.execution_attempt
       AND failure_receipt.subject_expected_code_fingerprint =
           previous.expected_code_fingerprint
       AND failure_receipt.subject_expected_head_sha =
           previous.expected_head_sha
       AND failure_receipt.subject_expected_base_sha =
           previous.expected_base_sha
       AND failure_receipt.returned_pending_operation_id IS NULL
      JOIN tasks task ON task.id = previous.task_id
      JOIN task_applied_protocol_snapshot_v309 current_task
        ON current_task.task_id = task.id
       AND current_task.returned_version = (
           SELECT MAX(latest.returned_version)
           FROM task_applied_protocol_snapshot_v309 latest
           WHERE latest.task_id = task.id
             AND latest.returned_version <= task.aggregate_version)
      JOIN threads trunk ON trunk.id = task.thread_id
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN remote_development_stage remote
        ON remote.stage_id = previous.stage_id
       AND remote.task_id = previous.task_id
       AND remote.generation = previous.stage_generation
      JOIN task_current_code_subject_v230 code ON code.task_id = task.id
      JOIN task_turn replacement ON replacement.id = NEW.task_turn_id
      LEFT JOIN ci_repair_episode ci
        ON previous.family = 'CI' AND ci.id = previous.episode_id
      LEFT JOIN branch_sync_episode branch
        ON previous.family = 'BRANCH' AND branch.id = previous.episode_id
      LEFT JOIN branch_sync_effect_step step
        ON previous.family = 'BRANCH' AND step.id = previous.step_id
      WHERE previous.task_turn_id = NEW.predecessor_turn_id
        AND previous.operation_id = NEW.predecessor_operation_id
        AND previous.status IN ('FAILED', 'CANCELED')
        AND failure.ci_repair_episode_id IS
            CASE WHEN previous.family = 'CI' THEN previous.episode_id END
        AND failure.branch_sync_episode_id IS
            CASE WHEN previous.family = 'BRANCH' THEN previous.episode_id END
        AND failure.branch_sync_effect_step_id IS previous.step_id
        AND failure.base_repair_authorization_id IS
            previous.base_repair_authorization_id
        AND failure.task_id = previous.task_id
        AND failure.task_epoch = previous.task_epoch
        AND failure.remote_development_stage_id = previous.stage_id
        AND failure.stage_generation = previous.stage_generation
        AND failure.semantic_attempt = previous.semantic_attempt
        AND failure.execution_attempt = previous.execution_attempt
        AND failure.expected_code_fingerprint =
            previous.expected_code_fingerprint
        AND failure.expected_head_sha = previous.expected_head_sha
        AND failure.expected_base_sha = previous.expected_base_sha
        AND failure.blocker_id = blocker.id
        AND failure.raw_outcome = previous.status
        AND failed.status IN ('FAILED', 'CANCELED')
        AND failed.task_id = previous.task_id
        AND failed.task_epoch = previous.task_epoch
        AND failed.trigger_stage_id = previous.stage_id
        AND failed.trigger_stage_generation = previous.stage_generation
        AND failed.operation_id = previous.operation_id
        AND failed.attempt = previous.execution_attempt
        AND failed.expected_code_fingerprint =
            previous.expected_code_fingerprint
        AND failed.expected_head_sha = previous.expected_head_sha
        AND failed.expected_base_sha = previous.expected_base_sha
        AND failed_ticket.task_id = previous.task_id
        AND failed_ticket.task_epoch = previous.task_epoch
        AND failed_ticket.stage_id = previous.stage_id
        AND failed_ticket.stage_generation = previous.stage_generation
        AND failed_ticket.attempt = previous.execution_attempt
        AND failed_ticket.expected_code_fingerprint =
            previous.expected_code_fingerprint
        AND failed_ticket.expected_head_sha = previous.expected_head_sha
        AND failed_ticket.expected_base_sha = previous.expected_base_sha
        AND failure_receipt.subject_task_epoch = previous.task_epoch
        AND failure_receipt.subject_stage_id = previous.stage_id
        AND failure_receipt.subject_stage_generation = previous.stage_generation
        AND failure_receipt.returned_trunk_id = task.thread_id
        AND failure_receipt.returned_lifecycle = 'ACTIVE'
        AND failure_receipt.returned_epoch = task.epoch
        AND failure_receipt.returned_version = failure.cleared_task_version
        AND failure.cleared_task_version <= task.aggregate_version
        AND failure_receipt.returned_current_stage_id = current.stage_id
        AND current_task.returned_pending_operation_id IS NULL
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'ACTIVE'
        AND task.epoch = previous.task_epoch
        AND current.stage_id = previous.stage_id
        AND current.stage_generation = previous.stage_generation
        AND owner.kind = 'REMOTE_DEVELOPMENT'
        AND owner.generation = previous.stage_generation
        AND owner.completed_at_ms IS NULL
        AND code.code_fingerprint = previous.expected_code_fingerprint
        AND code.head_sha = previous.expected_head_sha
        AND code.base_sha = previous.expected_base_sha
        AND NEW.family = previous.family
        AND NEW.task_id = previous.task_id
        AND NEW.task_epoch = previous.task_epoch
        AND NEW.remote_development_stage_id = previous.stage_id
        AND NEW.stage_generation = previous.stage_generation
        AND NEW.semantic_attempt = previous.semantic_attempt
        AND NEW.execution_attempt = previous.execution_attempt + 1
        AND NEW.expected_code_fingerprint =
            previous.expected_code_fingerprint
        AND NEW.expected_head_sha = previous.expected_head_sha
        AND NEW.expected_base_sha = previous.expected_base_sha
        AND NEW.base_repair_authorization_id IS
            previous.base_repair_authorization_id
        AND replacement.task_id = NEW.task_id
        AND replacement.task_epoch = NEW.task_epoch
        AND replacement.trigger_stage_id = NEW.remote_development_stage_id
        AND replacement.trigger_stage_generation = NEW.stage_generation
        AND replacement.operation_id = NEW.operation_id
        AND replacement.attempt = NEW.execution_attempt
        AND replacement.expected_code_fingerprint =
            NEW.expected_code_fingerprint
        AND replacement.expected_head_sha = NEW.expected_head_sha
        AND replacement.expected_base_sha = NEW.expected_base_sha
        AND replacement.status = 'REQUESTED'
        AND replacement.delivery_lane = failed.delivery_lane
        AND replacement.purpose = CASE NEW.family
            WHEN 'CI' THEN 'REMOTE_CI_BRAIN_REVIEW'
            ELSE 'BRANCH_SYNC_BRAIN_REVIEW' END
        AND json_valid(replacement.launch_input)
        AND json_type(replacement.launch_input, '$.resumeSessionId') IS NULL
        AND json_type(replacement.launch_input, '$.fallbackPrompt') IS NULL
        AND json_type(replacement.launch_input,
            '$.priorCumulativeInputTokens') IS NULL
        AND json_type(replacement.launch_input,
            '$.priorCumulativeOutputTokens') IS NULL
        AND instr(json_extract(replacement.launch_input, '$.prompt'),
            COALESCE(json_extract(failed.launch_input, '$.fallbackPrompt'),
                     json_extract(failed.launch_input, '$.prompt'))) = 1
        AND instr(json_extract(replacement.launch_input, '$.prompt'),
            'Retry instruction:') > 0
        AND json_extract(replacement.launch_input, '$.transport') IS
            json_extract(failed.launch_input, '$.transport')
        AND json_extract(replacement.launch_input, '$.provider') IS
            json_extract(failed.launch_input, '$.provider')
        AND json_extract(replacement.launch_input, '$.credentialAccount') IS
            json_extract(failed.launch_input, '$.credentialAccount')
        AND json_extract(replacement.launch_input, '$.model') IS
            json_extract(failed.launch_input, '$.model')
        AND json_extract(replacement.launch_input, '$.reasoningEffort') IS
            json_extract(failed.launch_input, '$.reasoningEffort')
        AND json_extract(replacement.launch_input, '$.workingDirectory') =
            json_extract(failed.launch_input, '$.workingDirectory')
        AND json_extract(replacement.launch_input, '$.systemPrompt') =
            json_extract(failed.launch_input, '$.systemPrompt')
        AND json_extract(replacement.launch_input,
            '$.toolEndpoint.ownerKind') = 'TASK_TURN'
        AND json_extract(replacement.launch_input,
            '$.toolEndpoint.ownerId') = NEW.task_turn_id
        AND json_extract(replacement.launch_input,
            '$.toolEndpoint.operationId') = NEW.operation_id
        AND json_extract(replacement.launch_input,
            '$.toolEndpoint.profile') = 'TASK_BRAIN_READ_ONLY'
        AND ((NEW.family = 'CI'
              AND NEW.ci_repair_episode_id = previous.episode_id
              AND ci.status = 'AWAITING_PUSH_CI'
              AND remote.current_head_sha = COALESCE(
                  ci.last_pushed_head_sha, ci.subject_head_sha)
              AND remote.current_base_sha = ci.subject_base_sha
              AND (NEW.base_repair_authorization_id IS NULL OR EXISTS (
                  SELECT 1 FROM ci_base_repair_authorization_v303 authorization
                  WHERE authorization.id = NEW.base_repair_authorization_id
                    AND authorization.ci_repair_episode_id = ci.id
                    AND (authorization.status = 'CLAIMED'
                      OR (authorization.status = 'CLOSED' AND EXISTS (
                          SELECT 1
                          FROM ci_base_repair_reauthorization_v322 reauthorization
                          JOIN remote_repair_commit_adoption_operation_v322 adoption
                            ON adoption.compatibility_reauthorization_id =
                               reauthorization.id
                          JOIN remote_repair_commit_adoption_result_v322 adopted
                            ON adopted.id = adoption.result_id
                          JOIN remote_repair_commit_adoption_delivery_v322 delivery
                            ON delivery.adoption_operation_row_id = adoption.id
                          JOIN remote_worktree_subject worktree
                            ON worktree.source_operation_id =
                               adoption.operation_id
                          JOIN task_current_code_subject_fence_v322 current_code
                            ON current_code.task_id = previous.task_id
                          JOIN ci_base_repair_rewrite_result_v303 rewrite
                            ON rewrite.authorization_id = authorization.id
                          JOIN ci_repair_operation validation
                            ON validation.id = rewrite.ci_repair_operation_id
                          JOIN ci_base_repair_subject_v303 subject
                            ON subject.authorization_id = authorization.id
                           AND subject.ci_repair_operation_id = validation.id
                          WHERE reauthorization.source_authorization_id =
                                authorization.id
                            AND reauthorization.ci_repair_episode_id = ci.id
                            AND reauthorization.semantic_attempt =
                                previous.semantic_attempt
                            AND reauthorization.task_id = previous.task_id
                            AND reauthorization.task_epoch = previous.task_epoch
                            AND reauthorization.remote_development_stage_id =
                                previous.stage_id
                            AND reauthorization.stage_generation =
                                previous.stage_generation
                            AND reauthorization.status = 'CLAIMED'
                            AND adoption.source_base_repair_authorization_id =
                                authorization.id
                            AND adoption.ci_repair_episode_id = ci.id
                            AND adoption.task_id = previous.task_id
                            AND adoption.task_epoch = previous.task_epoch
                            AND adoption.remote_development_stage_id =
                                previous.stage_id
                            AND adoption.stage_generation =
                                previous.stage_generation
                            AND adoption.status = 'SUCCEEDED'
                            AND adopted.adoption_operation_row_id = adoption.id
                            AND delivery.operation_id = adoption.operation_id
                            AND delivery.result_id = adopted.id
                            AND delivery.raw_outcome = 'SUCCEEDED'
                            AND delivery.acceptance = 'ACCEPTED'
                            AND worktree.task_id = previous.task_id
                            AND worktree.task_epoch = previous.task_epoch
                            AND worktree.remote_development_stage_id =
                                previous.stage_id
                            AND worktree.stage_generation =
                                previous.stage_generation
                            AND worktree.code_fingerprint =
                                adopted.candidate_code_fingerprint
                            AND worktree.head_sha = adopted.candidate_head_sha
                            AND worktree.base_sha = adoption.expected_base_sha
                            AND current_code.task_epoch = previous.task_epoch
                            AND rewrite.validation_outcome = 'PASSED'
                            AND rewrite.input_head_sha =
                                adopted.candidate_head_sha
                            AND rewrite.output_head_sha = subject.head_sha
                            AND validation.base_repair_authorization_id =
                                authorization.id
                            AND validation.ci_repair_episode_id = ci.id
                            AND validation.task_id = previous.task_id
                            AND validation.task_epoch = previous.task_epoch
                            AND validation.remote_development_stage_id =
                                previous.stage_id
                            AND validation.stage_generation =
                                previous.stage_generation
                            AND validation.kind = 'VALIDATE'
                            AND validation.semantic_attempt =
                                previous.semantic_attempt
                            AND validation.status = 'SUCCEEDED'
                            AND validation.expected_code_fingerprint =
                                adopted.candidate_code_fingerprint
                            AND validation.expected_head_sha =
                                adopted.candidate_head_sha
                            AND validation.expected_base_sha =
                                adoption.expected_base_sha
                            AND subject.task_id = previous.task_id
                            AND subject.task_epoch = previous.task_epoch
                            AND subject.remote_development_stage_id =
                                previous.stage_id
                            AND subject.stage_generation =
                                previous.stage_generation
                            AND subject.code_fingerprint =
                                previous.expected_code_fingerprint
                            AND subject.head_sha = previous.expected_head_sha
                            AND subject.base_sha =
                                previous.expected_base_sha
                            AND current_code.source_code_subject_kind =
                                'CI_BASE_REPAIR'
                            AND current_code.source_code_subject_id = subject.id
                            AND current_code.code_fingerprint =
                                subject.code_fingerprint
                            AND current_code.head_sha = subject.head_sha
                            AND current_code.base_sha = subject.base_sha))))))
          OR (NEW.family = 'BRANCH'
              AND NEW.branch_sync_episode_id = previous.episode_id
              AND NEW.branch_sync_effect_step_id = previous.step_id
              AND NEW.base_repair_authorization_id IS NULL
              AND branch.status = 'BRAIN_REVIEW'
              AND step.branch_sync_episode_id = branch.id
              AND step.kind = 'BRAIN_REVIEW'
              AND step.status = 'FAILED'
              AND step.attempt_count = previous.semantic_attempt
              AND remote.current_head_sha = branch.old_head_sha
              AND remote.current_base_sha = branch.observed_base_sha))
        AND ((previous.family = 'CI' AND EXISTS (
              SELECT 1 FROM ci_repair_delivery_receipt delivery
              WHERE delivery.ci_repair_operation_id = previous.row_id
                AND delivery.operation_id = previous.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED')))
          OR (previous.family = 'BRANCH' AND EXISTS (
              SELECT 1 FROM branch_sync_delivery_receipt delivery
              WHERE delivery.branch_sync_dispatch_operation_id = previous.row_id
                AND delivery.operation_id = previous.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED')))
          OR EXISTS (
              SELECT 1 FROM remote_repair_brain_replacement_delivery_v309 delivery
              WHERE delivery.replacement_operation_id = previous.row_id
                AND delivery.operation_id = previous.operation_id
                AND delivery.acceptance = 'ACCEPTED'
                AND delivery.raw_outcome IN ('FAILED', 'CANCELED'))))
BEGIN SELECT RAISE(ABORT, 'Remote repair Brain replacement is not exact'); END;
