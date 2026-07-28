-- Complete the Stage-owner resume protocol for every asynchronous checkpoint.
-- Agent/validation owners receive a new fenced execution.  Publish and merge
-- retain their original operation, ticket and effect ledger so recovery never
-- forgets an external effect that may already have happened.

CREATE TABLE stage_resume_async_successor_v272 (
    handoff_id             TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage_resume_rearm_intent_v257(handoff_id) ON DELETE CASCADE,
    command_id             TEXT    NOT NULL UNIQUE,
    owner_kind             TEXT    NOT NULL CHECK (owner_kind IN (
        'PLAN_SELF_REVIEW', 'LOCAL_VALIDATION',
        'DEVELOPMENT_BRAIN_REVIEW', 'PUBLISH_RECOVERY',
        'MERGE_RECOVERY')),
    owner_id               TEXT    NOT NULL,
    operation_id           TEXT    NOT NULL,
    dispatch_ticket_id     TEXT    NOT NULL,
    dispatch_attempt       INTEGER NOT NULL CHECK (dispatch_attempt > 0),
    domain_attempt         INTEGER NOT NULL CHECK (domain_attempt > 0),
    status                 TEXT    NOT NULL CHECK (status IN ('PREPARED', 'ARMED')),
    returned_stage_version INTEGER CHECK (returned_stage_version >= 0),
    created_at_ms          INTEGER NOT NULL CHECK (created_at_ms >= 0),
    armed_at_ms            INTEGER,
    CHECK ((status = 'PREPARED' AND armed_at_ms IS NULL
            AND returned_stage_version IS NULL)
        OR (status = 'ARMED' AND armed_at_ms IS NOT NULL)),
    CHECK (returned_stage_version IS NULL
        OR owner_kind <> 'DEVELOPMENT_BRAIN_REVIEW')
);

CREATE INDEX idx_stage_resume_async_successor_operation_v272
    ON stage_resume_async_successor_v272(operation_id, status);

CREATE TRIGGER stage_resume_async_successor_insert_v272
BEFORE INSERT ON stage_resume_async_successor_v272
WHEN NOT EXISTS (
    SELECT 1
    FROM stage_resume_rearm_intent_v257 intent
    JOIN task_resume_handoff_v256 handoff ON handoff.id = intent.handoff_id
    JOIN tasks task ON task.id = intent.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE intent.handoff_id = NEW.handoff_id
      AND intent.status = 'PENDING' AND handoff.status = 'ACCEPTED'
      AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = intent.task_epoch
      AND current.stage_id = intent.stage_id
      AND current.stage_generation = intent.stage_generation
      AND owner.kind = intent.stage_kind
      AND owner.generation = intent.stage_generation
      AND owner.version = intent.stage_version
      AND owner.checkpoint = intent.restore_checkpoint
      AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
      AND code.code_fingerprint = intent.code_fingerprint
      AND code.head_sha = intent.head_sha AND code.base_sha = intent.base_sha
      AND ((NEW.owner_kind = 'PLAN_SELF_REVIEW'
              AND intent.stage_kind = 'PLAN'
              AND intent.restore_checkpoint = 'SELF_REVIEW')
        OR (NEW.owner_kind = 'LOCAL_VALIDATION'
              AND intent.stage_kind = 'LOCAL_DEVELOPMENT'
              AND intent.restore_checkpoint = 'VALIDATING')
        OR (NEW.owner_kind = 'DEVELOPMENT_BRAIN_REVIEW'
              AND intent.stage_kind = 'LOCAL_DEVELOPMENT'
              AND intent.restore_checkpoint = 'BRAIN_REVIEW')
        OR (NEW.owner_kind = 'PUBLISH_RECOVERY'
              AND intent.stage_kind = 'LOCAL_DEVELOPMENT'
              AND intent.restore_checkpoint = 'PUBLISHING')
        OR (NEW.owner_kind = 'MERGE_RECOVERY'
              AND intent.stage_kind = 'REMOTE_DEVELOPMENT'
              AND intent.restore_checkpoint = 'MERGING')))
BEGIN SELECT RAISE(ABORT, 'Async resume successor owner fence is stale'); END;

CREATE TRIGGER stage_resume_async_successor_identity_v272
BEFORE UPDATE OF handoff_id, command_id, owner_kind, owner_id, operation_id,
        dispatch_ticket_id, dispatch_attempt, domain_attempt, created_at_ms
ON stage_resume_async_successor_v272
BEGIN SELECT RAISE(ABORT, 'Async resume successor identity is immutable'); END;

CREATE TRIGGER stage_resume_async_successor_transition_v272
BEFORE UPDATE OF status ON stage_resume_async_successor_v272
WHEN OLD.status <> 'PREPARED' OR NEW.status <> 'ARMED'
  OR NEW.armed_at_ms IS NULL
BEGIN SELECT RAISE(ABORT, 'illegal async resume successor transition'); END;

CREATE TRIGGER stage_resume_async_successor_terminal_v272
BEFORE UPDATE ON stage_resume_async_successor_v272
WHEN OLD.status = 'ARMED'
BEGIN SELECT RAISE(ABORT, 'armed async resume successor is immutable'); END;

-- Execution ordinals advance for exact-result freshness, but a pause/resume
-- never consumes a validation retry or Task-Brain product budget.
CREATE TABLE stage_resume_budget_lineage_v272 (
    handoff_id           TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage_resume_async_successor_v272(handoff_id) ON DELETE CASCADE,
    owner_kind           TEXT    NOT NULL CHECK (owner_kind IN (
        'LOCAL_VALIDATION', 'DEVELOPMENT_BRAIN_REVIEW')),
    predecessor_owner_id TEXT    NOT NULL,
    successor_owner_id   TEXT    NOT NULL,
    execution_attempt    INTEGER NOT NULL CHECK (execution_attempt > 1),
    budget_attempt       INTEGER NOT NULL CHECK (budget_attempt > 0),
    consumes_budget      INTEGER NOT NULL CHECK (consumes_budget = 0),
    recorded_at_ms       INTEGER NOT NULL
);

CREATE TRIGGER stage_resume_budget_lineage_insert_v272
BEFORE INSERT ON stage_resume_budget_lineage_v272
WHEN NOT EXISTS (
    SELECT 1
    FROM stage_resume_async_successor_v272 successor
    JOIN stage_resume_rearm_intent_v257 intent
      ON intent.handoff_id = successor.handoff_id
    WHERE successor.handoff_id = NEW.handoff_id
      AND successor.status = 'PREPARED'
      AND successor.owner_kind = NEW.owner_kind
      AND successor.owner_id = NEW.successor_owner_id
      AND successor.domain_attempt = NEW.execution_attempt
      AND intent.status = 'PENDING'
      AND NEW.execution_attempt > NEW.budget_attempt
      AND ((NEW.owner_kind = 'LOCAL_VALIDATION' AND EXISTS (
            SELECT 1
            FROM validation_operation predecessor
            JOIN validation_operation replacement
              ON replacement.id = NEW.successor_owner_id
            WHERE predecessor.id = NEW.predecessor_owner_id
              AND predecessor.status = 'CANCELED'
              AND replacement.task_id = predecessor.task_id
              AND replacement.task_epoch = predecessor.task_epoch
              AND replacement.local_development_stage_id =
                    predecessor.local_development_stage_id
              AND replacement.stage_generation = predecessor.stage_generation
              AND replacement.dev_report_id = predecessor.dev_report_id
              AND replacement.code_fingerprint = predecessor.code_fingerprint
              AND replacement.expected_head_sha = predecessor.expected_head_sha
              AND replacement.expected_base_sha = predecessor.expected_base_sha
              AND replacement.semantic_attempt = predecessor.semantic_attempt + 1
              AND NEW.execution_attempt = replacement.semantic_attempt
              AND NEW.budget_attempt = COALESCE((
                    SELECT prior.budget_attempt
                    FROM stage_resume_budget_lineage_v272 prior
                    WHERE prior.owner_kind = 'LOCAL_VALIDATION'
                      AND prior.successor_owner_id = predecessor.id),
                    predecessor.semantic_attempt)))
        OR (NEW.owner_kind = 'DEVELOPMENT_BRAIN_REVIEW' AND EXISTS (
            SELECT 1
            FROM brain_review_episode predecessor
            JOIN brain_review_episode replacement
              ON replacement.id = NEW.successor_owner_id
            WHERE predecessor.id = NEW.predecessor_owner_id
              AND predecessor.status IN ('CANCELED', 'SUPERSEDED')
              AND replacement.task_brain_id = predecessor.task_brain_id
              AND replacement.task_id = predecessor.task_id
              AND replacement.task_epoch = predecessor.task_epoch
              AND replacement.local_development_stage_id =
                    predecessor.local_development_stage_id
              AND replacement.stage_generation = predecessor.stage_generation
              AND replacement.dev_report_id = predecessor.dev_report_id
              AND replacement.validation_evidence_id =
                    predecessor.validation_evidence_id
              AND replacement.code_fingerprint = predecessor.code_fingerprint
              AND replacement.expected_head_sha = predecessor.expected_head_sha
              AND replacement.expected_base_sha = predecessor.expected_base_sha
              AND replacement.semantic_attempt = predecessor.semantic_attempt + 1
              AND NEW.execution_attempt = replacement.semantic_attempt
              AND NEW.budget_attempt = COALESCE((
                    SELECT prior.budget_attempt
                    FROM stage_resume_budget_lineage_v272 prior
                    WHERE prior.owner_kind = 'DEVELOPMENT_BRAIN_REVIEW'
                      AND prior.successor_owner_id = predecessor.id),
                    predecessor.semantic_attempt)))))
BEGIN SELECT RAISE(ABORT, 'Async resume budget lineage is not exact'); END;

CREATE TRIGGER stage_resume_budget_lineage_immutable_v272
BEFORE UPDATE ON stage_resume_budget_lineage_v272
BEGIN SELECT RAISE(ABORT, 'Async resume budget lineage is immutable'); END;

-- A paused Plan self-review is still the same logical verdict.  Its resumed
-- TaskTurn is recorded outside the two-attempt infrastructure-failure budget,
-- just as user-wait continuations are.
CREATE TABLE plan_self_review_resume_attempt_v272 (
    self_review_id      TEXT    NOT NULL
        REFERENCES plan_self_review(id) ON DELETE CASCADE,
    semantic_attempt    INTEGER NOT NULL CHECK (semantic_attempt > 1),
    task_turn_id        TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    operation_id        TEXT    NOT NULL UNIQUE,
    predecessor_turn_id TEXT    NOT NULL REFERENCES task_turn(id),
    handoff_id          TEXT    NOT NULL UNIQUE
        REFERENCES stage_resume_rearm_intent_v257(handoff_id) ON DELETE CASCADE,
    requested_at_ms     INTEGER NOT NULL,
    PRIMARY KEY (self_review_id, semantic_attempt)
);

CREATE TRIGGER plan_self_review_resume_attempt_exact_v272
BEFORE INSERT ON plan_self_review_resume_attempt_v272
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review review
    JOIN task_turn predecessor ON predecessor.id = NEW.predecessor_turn_id
    JOIN task_turn successor ON successor.id = NEW.task_turn_id
    JOIN stage_resume_async_successor_v272 resume
      ON resume.handoff_id = NEW.handoff_id
    JOIN stage_resume_rearm_intent_v257 intent
      ON intent.handoff_id = resume.handoff_id
    WHERE review.id = NEW.self_review_id AND review.status = 'REQUESTED'
      AND predecessor.purpose = 'PLAN_SELF_REVIEW'
      AND predecessor.status IN ('CANCELED', 'SUPERSEDED')
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
      AND resume.status = 'PREPARED'
      AND resume.owner_kind = 'PLAN_SELF_REVIEW'
      AND resume.owner_id = successor.id
      AND resume.operation_id = successor.operation_id
      AND intent.status = 'PENDING'
      AND intent.task_id = successor.task_id
      AND intent.stage_id = successor.trigger_stage_id
      AND intent.stage_generation = successor.trigger_stage_generation
      AND intent.restore_checkpoint = 'SELF_REVIEW'
      AND NEW.semantic_attempt = 1 + (
          SELECT MAX(attempt.semantic_attempt)
          FROM plan_self_review_all_attempt_v265 attempt
          WHERE attempt.self_review_id = review.id))
BEGIN SELECT RAISE(ABORT, 'Plan self-review resume attempt is not exact'); END;

CREATE TRIGGER plan_self_review_resume_attempt_immutable_v272
BEFORE UPDATE ON plan_self_review_resume_attempt_v272
BEGIN SELECT RAISE(ABORT, 'Plan self-review resume attempt is immutable'); END;

-- The old `attempt` column is the two-slot failure budget, not an execution
-- ordinal.  Persist a separate ordinal for infrastructure retry attempt two.
CREATE TABLE plan_self_review_failure_execution_v272 (
    self_review_id         TEXT    NOT NULL,
    infrastructure_attempt INTEGER NOT NULL CHECK (infrastructure_attempt = 2),
    execution_attempt      INTEGER NOT NULL CHECK (execution_attempt > 1),
    requested_at_ms        INTEGER NOT NULL,
    PRIMARY KEY (self_review_id, infrastructure_attempt),
    UNIQUE (self_review_id, execution_attempt),
    FOREIGN KEY (self_review_id, infrastructure_attempt)
        REFERENCES plan_self_review_attempt(self_review_id, attempt)
        ON DELETE CASCADE
);

INSERT INTO plan_self_review_failure_execution_v272(
    self_review_id, infrastructure_attempt, execution_attempt, requested_at_ms)
SELECT failure.self_review_id, 2,
       1 + MAX(COALESCE(wait.semantic_attempt, 1)),
       failure.requested_at_ms
FROM plan_self_review_attempt failure
LEFT JOIN plan_self_review_user_wait_attempt_v265 wait
  ON wait.self_review_id = failure.self_review_id
WHERE failure.attempt = 2
GROUP BY failure.self_review_id, failure.requested_at_ms;

CREATE TRIGGER plan_self_review_failure_execution_insert_v272
AFTER INSERT ON plan_self_review_attempt
WHEN NEW.attempt = 2
BEGIN
    INSERT INTO plan_self_review_failure_execution_v272(
        self_review_id, infrastructure_attempt, execution_attempt,
        requested_at_ms)
    SELECT NEW.self_review_id, 2,
           1 + MAX(execution_attempt), NEW.requested_at_ms
    FROM (
        SELECT 1 AS execution_attempt
        UNION ALL
        SELECT semantic_attempt
        FROM plan_self_review_user_wait_attempt_v265
        WHERE self_review_id = NEW.self_review_id
        UNION ALL
        SELECT semantic_attempt
        FROM plan_self_review_resume_attempt_v272
        WHERE self_review_id = NEW.self_review_id);
END;

CREATE TRIGGER plan_self_review_failure_execution_immutable_v272
BEFORE UPDATE ON plan_self_review_failure_execution_v272
BEGIN SELECT RAISE(ABORT, 'Plan self-review failure execution is immutable'); END;

DROP VIEW plan_self_review_all_attempt_v265;
CREATE VIEW plan_self_review_all_attempt_v265 AS
SELECT attempt.self_review_id,
       CASE WHEN attempt.attempt = 1 THEN 1 ELSE execution.execution_attempt END
           AS semantic_attempt,
       attempt.task_turn_id, attempt.operation_id, attempt.predecessor_turn_id
FROM plan_self_review_attempt attempt
LEFT JOIN plan_self_review_failure_execution_v272 execution
  ON execution.self_review_id = attempt.self_review_id
 AND execution.infrastructure_attempt = attempt.attempt
UNION ALL
SELECT self_review_id, semantic_attempt, task_turn_id,
       operation_id, predecessor_turn_id
FROM plan_self_review_user_wait_attempt_v265
UNION ALL
SELECT self_review_id, semantic_attempt, task_turn_id,
       operation_id, predecessor_turn_id
FROM plan_self_review_resume_attempt_v272;

-- Resume/user-wait executions do not consume the single infrastructure
-- failure retry.  If the latest continuation fails, attempt two may name that
-- failed execution while the base table still records the budget ordinal.
DROP TRIGGER plan_self_review_attempt_insert;
CREATE TRIGGER plan_self_review_attempt_insert
BEFORE INSERT ON plan_self_review_attempt
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review review
    JOIN plan_revision revision ON revision.id = review.plan_revision_id
    JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
    JOIN task_turn turn ON turn.id = NEW.task_turn_id
    WHERE review.id = NEW.self_review_id
      AND review.status = 'REQUESTED'
      AND turn.operation_id = NEW.operation_id
      AND turn.task_id = plan.task_id
      AND turn.task_epoch = review.task_epoch
      AND turn.trigger_stage_id = plan.stage_id
      AND turn.trigger_stage_generation = plan.generation
      AND turn.purpose = 'PLAN_SELF_REVIEW'
      AND turn.status = 'REQUESTED'
      AND ((NEW.attempt = 1 AND turn.id = review.task_turn_id)
        OR (NEW.attempt = 2
          AND NOT EXISTS (
              SELECT 1 FROM plan_self_review_attempt consumed
              WHERE consumed.self_review_id = review.id
                AND consumed.attempt = 2)
          AND EXISTS (
              SELECT 1 FROM plan_self_review_attempt first
              WHERE first.self_review_id = review.id AND first.attempt = 1)
          AND EXISTS (
              SELECT 1
              FROM plan_self_review_all_attempt_v265 predecessor
              JOIN task_turn failed ON failed.id = predecessor.task_turn_id
              WHERE predecessor.self_review_id = review.id
                AND predecessor.task_turn_id = NEW.predecessor_turn_id
                AND predecessor.semantic_attempt = (
                    SELECT MAX(latest.semantic_attempt)
                    FROM plan_self_review_all_attempt_v265 latest
                    WHERE latest.self_review_id = review.id)
                AND failed.status = 'FAILED'
                AND failed.finished_at_ms IS NOT NULL))))
BEGIN SELECT RAISE(ABORT, 'Plan self-review attempt is not exact'); END;

-- A user wait after a resume must count the resumed execution when assigning
-- the next logical self-review attempt.
DROP TRIGGER plan_self_review_user_wait_attempt_exact_v265;
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
          SELECT MAX(attempt.semantic_attempt)
          FROM plan_self_review_all_attempt_v265 attempt
          WHERE attempt.self_review_id = review.id))
BEGIN SELECT RAISE(ABORT, 'Plan self-review user-wait attempt is not exact'); END;

-- Publish/merge recovery re-arms the same terminal ticket.  This is the only
-- terminal-ticket reversal: it requires the exact prepared Stage resume proof
-- and retains every immutable identity/fence column.
DROP TRIGGER dispatch_ticket_terminal_immutable;
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

-- A canceled Publish keeps its manifest and effect ledger.  Reactivating the
-- original consent is safe only inside its exact resume command transaction.
DROP TRIGGER publish_authorization_state_transition;
CREATE TRIGGER publish_authorization_state_transition
BEFORE UPDATE OF revoked_at_ms, consumed_at_ms, outcome ON publish_authorization
WHEN NOT (
    (OLD.revoked_at_ms IS NULL AND OLD.consumed_at_ms IS NULL
      AND ((NEW.revoked_at_ms IS NOT NULL AND NEW.consumed_at_ms IS NULL
              AND NEW.outcome IS NULL)
        OR (NEW.revoked_at_ms IS NULL AND NEW.consumed_at_ms IS NOT NULL
              AND NEW.outcome IS NOT NULL)))
    OR (OLD.revoked_at_ms IS NULL AND OLD.consumed_at_ms IS NOT NULL
      AND OLD.outcome = 'CANCELED'
      AND NEW.revoked_at_ms IS NULL AND NEW.consumed_at_ms IS NULL
      AND NEW.outcome IS NULL
      AND EXISTS (
          SELECT 1
          FROM publish_operation operation
          JOIN stage_resume_async_successor_v272 resume
            ON resume.owner_id = operation.id
           AND resume.operation_id = operation.operation_id
          JOIN stage_resume_rearm_intent_v257 intent
            ON intent.handoff_id = resume.handoff_id
          WHERE operation.publish_authorization_id = OLD.id
            AND operation.status = 'CANCELED'
            AND resume.status = 'PREPARED'
            AND resume.owner_kind = 'PUBLISH_RECOVERY'
            AND intent.status = 'PENDING'
            AND intent.task_id = operation.task_id
            AND intent.stage_id = operation.local_development_stage_id)))
BEGIN SELECT RAISE(ABORT, 'illegal PublishAuthorization state transition'); END;

DROP TRIGGER publish_operation_transition;
CREATE TRIGGER publish_operation_transition
BEFORE UPDATE OF status ON publish_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED', 'INDETERMINATE'))
    OR (OLD.status = 'CANCELED' AND NEW.status = 'DISPATCHED'
        AND NEW.completed_at_ms IS NULL AND NEW.error_message IS NULL
        AND EXISTS (
            SELECT 1
            FROM stage_resume_async_successor_v272 resume
            JOIN stage_resume_rearm_intent_v257 intent
              ON intent.handoff_id = resume.handoff_id
            JOIN dispatch_ticket ticket ON ticket.id = resume.dispatch_ticket_id
            JOIN publish_authorization authorization
              ON authorization.id = NEW.publish_authorization_id
            WHERE resume.status = 'PREPARED'
              AND resume.owner_kind = 'PUBLISH_RECOVERY'
              AND resume.owner_id = NEW.id
              AND resume.operation_id = NEW.operation_id
              AND intent.status = 'PENDING'
              AND ticket.operation_id = NEW.operation_id
              AND ticket.status = 'REQUESTED'
              AND authorization.revoked_at_ms IS NULL
              AND authorization.consumed_at_ms IS NULL)))
BEGIN SELECT RAISE(ABORT, 'illegal PublishOperation transition'); END;

DROP TRIGGER publish_operation_terminal_immutable;
CREATE TRIGGER publish_operation_terminal_immutable
BEFORE UPDATE ON publish_operation
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELED',
    'SUPERSEDED', 'INDETERMINATE')
 AND NOT (OLD.status = 'CANCELED' AND NEW.status = 'DISPATCHED'
    AND EXISTS (
        SELECT 1 FROM stage_resume_async_successor_v272 resume
        JOIN stage_resume_rearm_intent_v257 intent
          ON intent.handoff_id = resume.handoff_id
        WHERE resume.status = 'PREPARED'
          AND resume.owner_kind = 'PUBLISH_RECOVERY'
          AND resume.owner_id = OLD.id
          AND resume.operation_id = OLD.operation_id
          AND intent.status = 'PENDING'))
BEGIN SELECT RAISE(ABORT, 'terminal PublishOperation is immutable'); END;

-- Cancellation of an in-flight merge cannot prove that the remote effect did
-- not happen.  On resume, reinterpret only the cancellation-produced FAILED
-- claim as INDETERMINATE so the existing operation probes its idempotency key.
DROP TRIGGER remote_merge_effect_attempt_transition;
CREATE TRIGGER remote_merge_effect_attempt_transition
BEFORE UPDATE OF status ON remote_merge_effect_attempt
WHEN NEW.status IS NOT OLD.status
  AND NOT ((OLD.status = 'CLAIMED'
              AND NEW.status IN ('AWAITING_OBSERVATION', 'SUCCEEDED',
                  'FAILED', 'INDETERMINATE'))
        OR (OLD.status = 'AWAITING_OBSERVATION'
              AND NEW.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE'))
        OR (OLD.status = 'FAILED' AND NEW.status = 'INDETERMINATE'
              AND OLD.last_error = 'merge dispatch cancellation requested'
              AND EXISTS (
                  SELECT 1
                  FROM remote_merge_operation operation
                  JOIN stage_resume_async_successor_v272 resume
                    ON resume.owner_id = operation.id
                   AND resume.operation_id = operation.operation_id
                  JOIN stage_resume_rearm_intent_v257 intent
                    ON intent.handoff_id = resume.handoff_id
                  WHERE operation.id = OLD.merge_operation_id
                    AND operation.status = 'CANCELED'
                    AND resume.status = 'PREPARED'
                    AND resume.owner_kind = 'MERGE_RECOVERY'
                    AND intent.status = 'PENDING')))
BEGIN SELECT RAISE(ABORT, 'Merge effect attempt transition is invalid'); END;

DROP TRIGGER remote_merge_effect_attempt_terminal_immutable;
CREATE TRIGGER remote_merge_effect_attempt_terminal_immutable
BEFORE UPDATE ON remote_merge_effect_attempt
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')
 AND NOT (OLD.status = 'FAILED' AND NEW.status = 'INDETERMINATE'
    AND OLD.last_error = 'merge dispatch cancellation requested'
    AND EXISTS (
        SELECT 1
        FROM remote_merge_operation operation
        JOIN stage_resume_async_successor_v272 resume
          ON resume.owner_id = operation.id
         AND resume.operation_id = operation.operation_id
        JOIN stage_resume_rearm_intent_v257 intent
          ON intent.handoff_id = resume.handoff_id
        WHERE operation.id = OLD.merge_operation_id
          AND operation.status = 'CANCELED'
          AND resume.status = 'PREPARED'
          AND resume.owner_kind = 'MERGE_RECOVERY'
          AND intent.status = 'PENDING'))
BEGIN SELECT RAISE(ABORT, 'Terminal merge effect attempt is immutable'); END;

DROP TRIGGER remote_merge_operation_transition;
CREATE TRIGGER remote_merge_operation_transition
BEFORE UPDATE OF status ON remote_merge_operation
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN ('CLAIMED', 'CANCELED'))
    OR (OLD.status = 'CLAIMED'
        AND NEW.status IN ('QUEUE_ENTERED', 'AWAITING_OBSERVATION',
            'FAILED', 'BLOCKED', 'CANCELED'))
    OR (OLD.status = 'QUEUE_ENTERED'
        AND NEW.status IN ('AWAITING_OBSERVATION', 'BLOCKED', 'CANCELED'))
    OR (OLD.status = 'AWAITING_OBSERVATION'
        AND NEW.status IN ('CLAIMED', 'QUEUE_ENTERED', 'SUCCEEDED',
            'FAILED', 'BLOCKED', 'CANCELED'))
    OR (OLD.status = 'CANCELED'
        AND NEW.status IN ('REQUESTED', 'AWAITING_OBSERVATION')
        AND NEW.completed_at_ms IS NULL AND NEW.block_reason IS NULL
        AND EXISTS (
            SELECT 1
            FROM stage_resume_async_successor_v272 resume
            JOIN stage_resume_rearm_intent_v257 intent
              ON intent.handoff_id = resume.handoff_id
            JOIN dispatch_ticket ticket ON ticket.id = resume.dispatch_ticket_id
            WHERE resume.status = 'PREPARED'
              AND resume.owner_kind = 'MERGE_RECOVERY'
              AND resume.owner_id = NEW.id
              AND resume.operation_id = NEW.operation_id
              AND intent.status = 'PENDING'
              AND ticket.operation_id = NEW.operation_id
              AND ticket.status = 'REQUESTED')))
BEGIN SELECT RAISE(ABORT, 'Merge operation transition is invalid'); END;

DROP TRIGGER remote_merge_operation_terminal_immutable;
CREATE TRIGGER remote_merge_operation_terminal_immutable
BEFORE UPDATE ON remote_merge_operation
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')
 AND NOT (OLD.status = 'CANCELED'
    AND NEW.status IN ('REQUESTED', 'AWAITING_OBSERVATION')
    AND EXISTS (
        SELECT 1 FROM stage_resume_async_successor_v272 resume
        JOIN stage_resume_rearm_intent_v257 intent
          ON intent.handoff_id = resume.handoff_id
        WHERE resume.status = 'PREPARED'
          AND resume.owner_kind = 'MERGE_RECOVERY'
          AND resume.owner_id = OLD.id
          AND resume.operation_id = OLD.operation_id
          AND intent.status = 'PENDING'))
BEGIN SELECT RAISE(ABORT, 'Terminal merge operation is immutable'); END;

-- Preserve all V257/V263 materialization proofs and admit the five typed
-- async successors only after their owner graph is fully durable and armed.
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
 AND NOT EXISTS (
    SELECT 1
    FROM stage_resume_async_successor_v272 successor
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
      AND ((successor.owner_kind = 'PLAN_SELF_REVIEW'
            AND OLD.stage_kind = 'PLAN'
            AND OLD.restore_checkpoint = 'SELF_REVIEW'
            AND successor.returned_stage_version = owner.version
            AND EXISTS (
                SELECT 1
                FROM plan_self_review_resume_attempt_v272 attempt
                JOIN task_turn turn ON turn.id = attempt.task_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                WHERE attempt.handoff_id = successor.handoff_id
                  AND turn.id = successor.owner_id
                  AND turn.operation_id = successor.operation_id
                  AND turn.attempt = successor.dispatch_attempt
                  AND attempt.semantic_attempt = successor.domain_attempt
                  AND turn.task_id = OLD.task_id
                  AND turn.task_epoch = OLD.task_epoch
                  AND turn.trigger_stage_id = OLD.stage_id
                  AND turn.trigger_stage_generation = OLD.stage_generation
                  AND turn.purpose = 'PLAN_SELF_REVIEW'
                  AND turn.status = 'REQUESTED'
                  AND turn.expected_code_fingerprint = OLD.code_fingerprint
                  AND turn.expected_head_sha = OLD.head_sha
                  AND turn.expected_base_sha = OLD.base_sha
                  AND ticket.operation_id = successor.operation_id
                  AND ticket.operation_kind = 'EXECUTE_TASK_TURN'
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                  AND ticket.task_id = OLD.task_id
                  AND ticket.task_epoch = OLD.task_epoch
                  AND ticket.stage_id = OLD.stage_id
                  AND ticket.stage_generation = OLD.stage_generation
                  AND ticket.attempt = successor.dispatch_attempt
                  AND ticket.expected_code_fingerprint = OLD.code_fingerprint
                  AND ticket.expected_head_sha = OLD.head_sha
                  AND ticket.expected_base_sha = OLD.base_sha
                  AND ticket.status = 'REQUESTED'))
        OR (successor.owner_kind = 'LOCAL_VALIDATION'
            AND OLD.stage_kind = 'LOCAL_DEVELOPMENT'
            AND OLD.restore_checkpoint = 'VALIDATING'
            AND successor.returned_stage_version = owner.version
            AND EXISTS (
                SELECT 1 FROM validation_operation operation
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                WHERE operation.id = successor.owner_id
                  AND operation.operation_id = successor.operation_id
                  AND operation.semantic_attempt = successor.domain_attempt
                  AND operation.task_id = OLD.task_id
                  AND operation.task_epoch = OLD.task_epoch
                  AND operation.local_development_stage_id = OLD.stage_id
                  AND operation.stage_generation = OLD.stage_generation
                  AND operation.code_fingerprint = OLD.code_fingerprint
                  AND operation.expected_head_sha = OLD.head_sha
                  AND operation.expected_base_sha = OLD.base_sha
                  AND operation.status = 'DISPATCHED'
                  AND ticket.operation_id = successor.operation_id
                  AND ticket.operation_kind = 'VALIDATE_LOCAL_DEVELOPMENT'
                  AND ticket.async_family = 'VALIDATION'
                  AND ticket.owner_kind = 'STAGE'
                  AND ticket.owner_id = OLD.stage_id
                  AND ticket.callback_route = 'STAGE_VALIDATION_RESULT'
                  AND ticket.task_id = OLD.task_id
                  AND ticket.task_epoch = OLD.task_epoch
                  AND ticket.stage_id = OLD.stage_id
                  AND ticket.stage_generation = OLD.stage_generation
                  AND ticket.attempt = successor.dispatch_attempt
                  AND ticket.expected_code_fingerprint = OLD.code_fingerprint
                  AND ticket.expected_head_sha = OLD.head_sha
                  AND ticket.expected_base_sha = OLD.base_sha
                  AND ticket.status = 'REQUESTED'
                  AND EXISTS (
                      SELECT 1
                      FROM stage_resume_budget_lineage_v272 lineage
                      WHERE lineage.handoff_id = successor.handoff_id
                        AND lineage.owner_kind = successor.owner_kind
                        AND lineage.successor_owner_id = operation.id
                        AND lineage.execution_attempt =
                            operation.semantic_attempt
                        AND lineage.consumes_budget = 0)))
        OR (successor.owner_kind = 'DEVELOPMENT_BRAIN_REVIEW'
            AND OLD.stage_kind = 'LOCAL_DEVELOPMENT'
            AND OLD.restore_checkpoint = 'BRAIN_REVIEW'
            AND successor.returned_stage_version IS NULL
            AND EXISTS (
                SELECT 1 FROM brain_review_episode episode
                JOIN task_turn turn ON turn.id = episode.task_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                JOIN task_brain_request_receipt receipt
                  ON receipt.command_id = successor.command_id
                WHERE episode.id = successor.owner_id
                  AND episode.semantic_attempt = successor.domain_attempt
                  AND episode.status = 'REQUESTED'
                  AND turn.operation_id = successor.operation_id
                  AND turn.attempt = successor.dispatch_attempt
                  AND turn.status = 'QUEUED'
                  AND turn.task_id = OLD.task_id
                  AND turn.task_epoch = OLD.task_epoch
                  AND turn.trigger_stage_id = OLD.stage_id
                  AND turn.trigger_stage_generation = OLD.stage_generation
                  AND turn.expected_code_fingerprint = OLD.code_fingerprint
                  AND turn.expected_head_sha = OLD.head_sha
                  AND turn.expected_base_sha = OLD.base_sha
                  AND ticket.operation_id = successor.operation_id
                  AND ticket.operation_kind = 'EXECUTE_TASK_TURN'
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                  AND ticket.task_id = OLD.task_id
                  AND ticket.task_epoch = OLD.task_epoch
                  AND ticket.stage_id = OLD.stage_id
                  AND ticket.stage_generation = OLD.stage_generation
                  AND ticket.attempt = successor.dispatch_attempt
                  AND ticket.expected_code_fingerprint = OLD.code_fingerprint
                  AND ticket.expected_head_sha = OLD.head_sha
                  AND ticket.expected_base_sha = OLD.base_sha
                  AND ticket.status = 'REQUESTED'
                  AND receipt.task_id = OLD.task_id
                  AND receipt.cause = 'REQUEST_BRAIN_REVIEW'
                  AND receipt.disposition = 'APPLIED'
                  AND receipt.proof_id = episode.id
                  AND receipt.returned_pending_operation_id = successor.operation_id
                  AND receipt.returned_pending_attempt = successor.dispatch_attempt
                  AND EXISTS (
                      SELECT 1
                      FROM stage_resume_budget_lineage_v272 lineage
                      WHERE lineage.handoff_id = successor.handoff_id
                        AND lineage.owner_kind = successor.owner_kind
                        AND lineage.successor_owner_id = episode.id
                        AND lineage.execution_attempt = episode.semantic_attempt
                        AND lineage.consumes_budget = 0)))
        OR (successor.owner_kind = 'PUBLISH_RECOVERY'
            AND OLD.stage_kind = 'LOCAL_DEVELOPMENT'
            AND OLD.restore_checkpoint = 'PUBLISHING'
            AND successor.returned_stage_version = owner.version
            AND EXISTS (
                SELECT 1 FROM publish_operation operation
                JOIN publish_authorization authorization
                  ON authorization.id = operation.publish_authorization_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                WHERE operation.id = successor.owner_id
                  AND operation.operation_id = successor.operation_id
                  AND operation.semantic_attempt = successor.domain_attempt
                  AND operation.task_id = OLD.task_id
                  AND operation.task_epoch = OLD.task_epoch
                  AND operation.local_development_stage_id = OLD.stage_id
                  AND operation.stage_generation = OLD.stage_generation
                  AND operation.code_fingerprint = OLD.code_fingerprint
                  AND operation.expected_head_sha = OLD.head_sha
                  AND operation.expected_base_sha = OLD.base_sha
                  AND operation.status = 'DISPATCHED'
                  AND authorization.revoked_at_ms IS NULL
                  AND authorization.consumed_at_ms IS NULL
                  AND ticket.operation_id = successor.operation_id
                  AND ticket.operation_kind = 'PUBLISH_LOCAL_DEVELOPMENT'
                  AND ticket.async_family = 'GITHUB_EFFECT'
                  AND ticket.owner_kind = 'STAGE'
                  AND ticket.owner_id = OLD.stage_id
                  AND ticket.callback_route = 'STAGE_PUBLISH_RESULT'
                  AND ticket.task_id = OLD.task_id
                  AND ticket.task_epoch = OLD.task_epoch
                  AND ticket.stage_id = OLD.stage_id
                  AND ticket.stage_generation = OLD.stage_generation
                  AND ticket.attempt = successor.dispatch_attempt
                  AND ticket.expected_code_fingerprint = OLD.code_fingerprint
                  AND ticket.expected_head_sha = OLD.head_sha
                  AND ticket.expected_base_sha = OLD.base_sha
                  AND ticket.status = 'REQUESTED'))
        OR (successor.owner_kind = 'MERGE_RECOVERY'
            AND OLD.stage_kind = 'REMOTE_DEVELOPMENT'
            AND OLD.restore_checkpoint = 'MERGING'
            AND successor.returned_stage_version = owner.version
            AND EXISTS (
                SELECT 1 FROM remote_merge_operation operation
                JOIN remote_merge_authorization authorization
                  ON authorization.id = operation.merge_authorization_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                WHERE operation.id = successor.owner_id
                  AND operation.operation_id = successor.operation_id
                  AND operation.semantic_attempt = successor.domain_attempt
                  AND operation.task_id = OLD.task_id
                  AND operation.task_epoch = OLD.task_epoch
                  AND operation.remote_development_stage_id = OLD.stage_id
                  AND operation.stage_generation = OLD.stage_generation
                  AND operation.head_sha = OLD.head_sha
                  AND operation.base_sha = OLD.base_sha
                  AND operation.status IN ('REQUESTED', 'AWAITING_OBSERVATION')
                  AND authorization.status = 'CONSUMED'
                  AND ticket.operation_id = successor.operation_id
                  AND ticket.operation_kind = 'MERGE_REMOTE_PULL_REQUEST'
                  AND ticket.async_family = 'MERGE'
                  AND ticket.owner_kind = 'STAGE'
                  AND ticket.owner_id = OLD.stage_id
                  AND ticket.callback_route = 'REMOTE_MERGE_RESULT'
                  AND ticket.task_id = OLD.task_id
                  AND ticket.task_epoch = OLD.task_epoch
                  AND ticket.stage_id = OLD.stage_id
                  AND ticket.stage_generation = OLD.stage_generation
                  AND ticket.attempt = successor.dispatch_attempt
                  AND ticket.expected_code_fingerprint IS NULL
                  AND ticket.expected_head_sha = OLD.head_sha
                  AND ticket.expected_base_sha = OLD.base_sha
                  AND ticket.status = 'REQUESTED'))))
BEGIN SELECT RAISE(ABORT,
    'Resume materialization lacks its exact successor or passive wait'); END;

-- A resumed merge has a new Stage version but the same merge authorization.
-- Admit a new irreversible EXECUTE claim only when that version is backed by
-- the exact ARMED resume proof; ordinary first execution keeps the original
-- AUTHORIZE_MERGE receipt requirement.
DROP TRIGGER remote_merge_effect_attempt_execute_owner;
CREATE TRIGGER remote_merge_effect_attempt_execute_owner
BEFORE INSERT ON remote_merge_effect_attempt
WHEN NEW.claim_mode = 'EXECUTE'
 AND NOT EXISTS (
      SELECT 1
      FROM remote_merge_operation operation
      JOIN remote_merge_authorization authorization
        ON authorization.id = operation.merge_authorization_id
      JOIN task_automation_policy policy
        ON policy.id = authorization.automation_policy_id
      JOIN tasks task ON task.id = operation.task_id
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN stage_command_receipt receipt
        ON receipt.stage_id = owner.id
       AND receipt.returned_version = owner.version
       AND receipt.disposition = 'APPLIED'
      WHERE operation.id = NEW.merge_operation_id
        AND policy.task_id = operation.task_id
        AND policy.revision = (
            SELECT MAX(current_policy.revision)
            FROM task_automation_policy current_policy
            WHERE current_policy.task_id = operation.task_id)
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'ACTIVE'
        AND task.epoch = operation.task_epoch
        AND current.stage_id = operation.remote_development_stage_id
        AND current.stage_generation = operation.stage_generation
        AND owner.task_id = operation.task_id
        AND owner.kind = 'REMOTE_DEVELOPMENT'
        AND owner.generation = operation.stage_generation
        AND owner.checkpoint = 'MERGING'
        AND owner.completed_at_ms IS NULL
        AND owner.end_reason IS NULL
        AND receipt.cause = 'AUTHORIZE_MERGE'
        AND receipt.returned_kind = 'REMOTE_DEVELOPMENT'
        AND receipt.returned_generation = operation.stage_generation
        AND receipt.returned_checkpoint = 'MERGING'
        AND receipt.returned_end_reason IS NULL
        AND receipt.returned_pending_task_epoch = operation.task_epoch
        AND receipt.returned_pending_stage_id = operation.remote_development_stage_id
        AND receipt.returned_pending_stage_generation = operation.stage_generation
        AND receipt.returned_pending_operation_id = operation.operation_id
        AND receipt.returned_pending_attempt = operation.semantic_attempt
        AND receipt.returned_pending_code_fingerprint IS NULL
        AND receipt.returned_pending_head_sha = operation.head_sha
        AND receipt.returned_pending_base_sha = operation.base_sha)
 AND NOT EXISTS (
      SELECT 1
      FROM remote_merge_operation operation
      JOIN remote_merge_authorization authorization
        ON authorization.id = operation.merge_authorization_id
      JOIN task_automation_policy policy
        ON policy.id = authorization.automation_policy_id
      JOIN tasks task ON task.id = operation.task_id
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN stage_resume_async_successor_v272 resume
        ON resume.owner_id = operation.id
       AND resume.operation_id = operation.operation_id
      JOIN stage_resume_rearm_intent_v257 intent
        ON intent.handoff_id = resume.handoff_id
      WHERE operation.id = NEW.merge_operation_id
        AND authorization.status = 'CONSUMED'
        AND policy.task_id = operation.task_id
        AND policy.revision = (
            SELECT MAX(current_policy.revision)
            FROM task_automation_policy current_policy
            WHERE current_policy.task_id = operation.task_id)
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state = 'ACTIVE'
        AND task.epoch = operation.task_epoch
        AND current.stage_id = operation.remote_development_stage_id
        AND current.stage_generation = operation.stage_generation
        AND owner.task_id = operation.task_id
        AND owner.kind = 'REMOTE_DEVELOPMENT'
        AND owner.generation = operation.stage_generation
        AND owner.checkpoint = 'MERGING'
        AND owner.completed_at_ms IS NULL
        AND owner.end_reason IS NULL
        AND resume.status = 'ARMED'
        AND resume.owner_kind = 'MERGE_RECOVERY'
        AND resume.domain_attempt = operation.semantic_attempt
        AND resume.returned_stage_version = owner.version
        AND intent.status = 'MATERIALIZED'
        AND intent.task_id = operation.task_id
        AND intent.task_epoch = operation.task_epoch
        AND intent.stage_id = operation.remote_development_stage_id
        AND intent.stage_generation = operation.stage_generation
        AND intent.restore_checkpoint = 'MERGING'
        AND intent.head_sha = operation.head_sha
        AND intent.base_sha = operation.base_sha)
BEGIN SELECT RAISE(ABORT,
    'Merge execution claim lacks current Task and Stage authority'); END;

-- A Plan self-review remains REQUESTED while pause/resume replaces its exact
-- canceled TaskTurn.  It is live only while one execution in that review's
-- lineage is live; otherwise the durable logical owner would prevent the
-- exact pause barrier that is required before its replacement can be armed.
DROP VIEW task_live_work_counts_v230;
CREATE VIEW task_live_work_counts_v230 AS
SELECT task.id AS task_id,
       task.epoch AS task_epoch,
       (SELECT COUNT(*) FROM task_turn turn
        WHERE turn.task_id = task.id AND turn.task_epoch = task.epoch
          AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
           AS active_task_turn_count,
       (SELECT COUNT(*) FROM stage_turn turn
        JOIN stage owner ON owner.id = turn.stage_id
        WHERE owner.task_id = task.id AND turn.task_epoch = task.epoch
          AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
           AS active_stage_turn_count,
       (SELECT COUNT(*)
        FROM review_assignment_turn turn
        JOIN review_assignment assignment ON assignment.id = turn.assignment_id
        JOIN review_round round ON round.id = assignment.round_id
        JOIN review_session session ON session.id = round.session_id
        JOIN pr pull_request ON pull_request.id = session.pr_id
        WHERE pull_request.origin = 'task' AND pull_request.task_id = task.id
          AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
           AS active_review_turn_count,
       (SELECT COUNT(*) FROM plan_self_review review
        JOIN plan_revision revision ON revision.id = review.plan_revision_id
        JOIN plan_stage owner ON owner.stage_id = revision.plan_stage_id
        WHERE owner.task_id = task.id AND review.task_epoch = task.epoch
          AND review.status = 'REQUESTED'
          AND EXISTS (
              SELECT 1
              FROM plan_self_review_all_attempt_v265 attempt
              JOIN task_turn turn ON turn.id = attempt.task_turn_id
              WHERE attempt.self_review_id = review.id
                AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')))
           AS active_plan_review_count,
       (SELECT COUNT(*) FROM validation_operation operation
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND operation.status IN ('REQUESTED', 'DISPATCHED'))
           AS active_validation_count,
       (SELECT COUNT(*) FROM brain_review_episode episode
        WHERE episode.task_id = task.id AND episode.task_epoch = task.epoch
          AND episode.status IN ('REQUESTED', 'REVIEWING'))
           AS active_brain_episode_count,
       (SELECT COUNT(*) FROM provision_task_operation operation
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND operation.status IN ('REQUESTED', 'DISPATCHED'))
           AS active_provision_operation_count,
       (SELECT COUNT(*) FROM dispatch_ticket ticket
        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
          AND ticket.status IN (
              'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
              'CLAIMED', 'RUNNING', 'DELIVERING'))
           AS active_dispatch_count,
       (SELECT COUNT(*) FROM dispatch_ticket ticket
        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
          AND ticket.writer_required = 1
          AND ticket.status IN (
              'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', 'RESULT_PENDING',
              'CLAIMED', 'RUNNING', 'DELIVERING'))
           AS active_writer_dispatch_count,
       (SELECT COUNT(*) FROM agent_execution execution
        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
          AND execution.status IN ('STARTING', 'RUNNING'))
           AS active_agent_execution_count,
       (SELECT COUNT(*) FROM agent_execution execution
        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
          AND execution.status = 'UNKNOWN') AS unreconciled_execution_count,
       (SELECT COUNT(*) FROM task_quiescence_barrier barrier
        WHERE barrier.task_id = task.id AND barrier.task_epoch = task.epoch
          AND barrier.status = 'REQUESTED') AS active_quiescence_count,
       (SELECT COUNT(*) FROM task_replan_request request
        WHERE request.task_id = task.id
          AND request.source_task_epoch = task.epoch
          AND request.status IN ('REQUESTED', 'QUIESCING'))
           AS active_replan_count,
       (SELECT COUNT(*) FROM local_feedback_batch batch
        WHERE batch.task_id = task.id AND batch.task_epoch = task.epoch
          AND batch.status IN ('BUILDING', 'FROZEN', 'QUEUED', 'DISPATCHED'))
           AS active_feedback_batch_count,
       (SELECT COUNT(*) FROM publish_operation operation
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND operation.status IN ('REQUESTED', 'DISPATCHED'))
           AS active_publish_operation_count,
       (SELECT COUNT(*) FROM publish_operation operation
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND operation.status = 'INDETERMINATE')
           AS unreconciled_publish_operation_count,
       (SELECT COUNT(*) FROM publish_effect_step step
        JOIN publish_operation operation ON operation.id = step.publish_operation_id
        WHERE operation.task_id = task.id AND operation.task_epoch = task.epoch
          AND step.status IN ('CLAIMED', 'INDETERMINATE'))
           AS active_publish_effect_count,
       (SELECT COUNT(*) FROM publish_authorization authorization
        WHERE authorization.task_id = task.id
          AND authorization.task_epoch = task.epoch
          AND authorization.revoked_at_ms IS NULL
          AND authorization.consumed_at_ms IS NULL)
           AS active_publish_authorization_count,
       (SELECT COUNT(*) FROM permission_request permission
        WHERE permission.state = 'OPEN'
          AND ((permission.turn_kind = 'TASK' AND EXISTS (
                    SELECT 1 FROM task_turn turn
                    WHERE turn.id = permission.turn_id
                      AND turn.task_id = task.id
                      AND turn.task_epoch = task.epoch))
            OR (permission.turn_kind = 'STAGE' AND EXISTS (
                    SELECT 1 FROM stage_turn turn
                    JOIN stage owner ON owner.id = turn.stage_id
                    WHERE turn.id = permission.turn_id
                      AND owner.task_id = task.id
                      AND turn.task_epoch = task.epoch))))
           AS open_permission_count,
       (SELECT COUNT(*) FROM task_terminal_intent intent
        WHERE intent.task_id = task.id AND intent.accepted = 1)
           AS accepted_terminal_intent_count,
       (SELECT COUNT(*) FROM stage cleanup
        WHERE cleanup.task_id = task.id AND cleanup.kind = 'CLEANUP'
          AND cleanup.completed_at_ms IS NULL) AS open_cleanup_stage_count
FROM tasks task
WHERE task.workflow_version = 'V2';
