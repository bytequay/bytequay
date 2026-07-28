-- An expired Publish effect is ambiguous. Reclaim it only as a probe; a
-- fresh EXECUTE is allowed only after that probe records a definite miss.

DROP TRIGGER publish_effect_step_claim;
DROP TRIGGER publish_effect_step_transition;
DROP TRIGGER publish_effect_step_fields_guard;

CREATE TRIGGER publish_effect_step_claim
BEFORE UPDATE OF status ON publish_effect_step
WHEN NEW.status = 'CLAIMED'
  AND (OLD.status NOT IN ('REQUESTED', 'CLAIMED', 'FAILED', 'INDETERMINATE')
    OR NEW.attempt_count <> OLD.attempt_count + 1
    OR NEW.attempt_count > NEW.attempt_limit
    OR NEW.claim_owner IS NULL OR length(NEW.claim_owner) = 0
    OR NEW.claimed_at_ms IS NULL OR NEW.lease_until_ms <= NEW.claimed_at_ms
    OR NEW.evidence IS NOT NULL OR NEW.last_error IS NOT NULL
    OR NEW.completed_at_ms IS NOT NULL
    OR (OLD.status = 'CLAIMED'
        AND (NEW.claim_mode <> 'PROBE'
          OR OLD.lease_until_ms IS NULL
          OR NEW.claimed_at_ms < OLD.lease_until_ms))
    OR (OLD.status = 'INDETERMINATE' AND NEW.claim_mode <> 'PROBE')
    OR (OLD.status IN ('REQUESTED', 'FAILED')
        AND NEW.claim_mode <> 'EXECUTE')
    OR EXISTS (
        SELECT 1 FROM publish_effect_step prior
        WHERE prior.publish_operation_id = NEW.publish_operation_id
          AND prior.ordinal < NEW.ordinal
          AND prior.status <> 'SUCCEEDED')
    OR NOT EXISTS (
        SELECT 1 FROM publish_operation operation
        WHERE operation.id = NEW.publish_operation_id
          AND operation.status = 'DISPATCHED'))
BEGIN SELECT RAISE(ABORT, 'Publish effect step claim is not ordered or recoverable'); END;

CREATE TRIGGER publish_effect_step_transition
BEFORE UPDATE OF status ON publish_effect_step
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status = 'CLAIMED')
    OR (OLD.status = 'CLAIMED'
        AND NEW.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')))
BEGIN SELECT RAISE(ABORT, 'illegal Publish effect step transition'); END;

CREATE TRIGGER publish_effect_step_fields_guard
BEFORE UPDATE OF attempt_count, claim_mode, claim_owner, claimed_at_ms,
        lease_until_ms, evidence, last_error, completed_at_ms
ON publish_effect_step
WHEN NEW.status IS OLD.status
  AND NOT (OLD.status = 'CLAIMED'
    AND NEW.status = 'CLAIMED'
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.claim_mode = 'PROBE'
    AND NEW.claimed_at_ms >= OLD.lease_until_ms
    AND NEW.lease_until_ms > NEW.claimed_at_ms
    AND NEW.claim_owner IS NOT NULL AND length(NEW.claim_owner) > 0
    AND NEW.evidence IS NULL AND NEW.last_error IS NULL
    AND NEW.completed_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT, 'Publish effect mutable fields require an attempt boundary'); END;
