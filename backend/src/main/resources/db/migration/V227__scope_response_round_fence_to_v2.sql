-- Legacy review rounds advance their code fingerprint after an addressing turn.
-- Only V2 rounds carry an immutable operation fence.
DROP TRIGGER response_round_fence_immutable;

CREATE TRIGGER response_round_fence_immutable
BEFORE UPDATE OF workflow_version, task_epoch, stage_id, stage_generation,
        operation_id, semantic_attempt, code_fingerprint,
        expected_head_sha, expected_base_sha ON response_round
WHEN NEW.workflow_version IS NOT OLD.workflow_version
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_id IS NOT OLD.stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR (OLD.workflow_version = 'V2'
    AND NEW.code_fingerprint IS NOT OLD.code_fingerprint)
BEGIN SELECT RAISE(ABORT, 'response round fence is immutable'); END;
