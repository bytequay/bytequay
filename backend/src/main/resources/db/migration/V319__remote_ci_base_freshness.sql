-- Flyway executes this migration outside its transaction so the applied V308
-- dispatch table can be rebuilt without rewriting that immutable baseline.
-- The explicit savepoint keeps the rebuild and all V319 protocol objects
-- atomic; the foreign-key assertion runs before it commits.
PRAGMA foreign_keys = OFF;
PRAGMA legacy_alter_table = ON;
SAVEPOINT remote_ci_base_freshness_v319;

ALTER TABLE branch_sync_dispatch_operation
    RENAME TO branch_sync_dispatch_operation_v319_old;

CREATE TABLE branch_sync_dispatch_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    branch_sync_episode_id      TEXT    NOT NULL
        REFERENCES branch_sync_episode(id) ON DELETE CASCADE,
    branch_sync_effect_step_id  TEXT    NOT NULL
        REFERENCES branch_sync_effect_step(id) ON DELETE CASCADE,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'FETCH_COMPARE', 'MECHANICAL_REBASE', 'CONFLICT_REPAIR',
        'VALIDATE', 'BRAIN_REVIEW', 'FORCE_WITH_LEASE_PUSH')),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    stage_turn_id               TEXT UNIQUE REFERENCES stage_turn(id),
    task_turn_id                TEXT UNIQUE REFERENCES task_turn(id),
    expected_code_fingerprint   TEXT,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    target_base_sha             TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'INDETERMINATE', 'CANCELED', 'SUPERSEDED')),
    result_code_fingerprint     TEXT,
    result_head_sha             TEXT,
    result_evidence             TEXT,
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    UNIQUE (branch_sync_effect_step_id, semantic_attempt),
    CHECK ((kind = 'CONFLICT_REPAIR') = (stage_turn_id IS NOT NULL)),
    CHECK ((kind = 'BRAIN_REVIEW') = (task_turn_id IS NOT NULL)),
    CHECK (stage_turn_id IS NULL OR task_turn_id IS NULL),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE',
            'CANCELED', 'SUPERSEDED')) = (completed_at_ms IS NOT NULL))
);

INSERT INTO branch_sync_dispatch_operation(
    id, branch_sync_episode_id, branch_sync_effect_step_id,
    remote_development_stage_id, task_id, task_epoch, stage_generation, kind,
    operation_id, semantic_attempt, stage_turn_id, task_turn_id,
    expected_code_fingerprint, expected_head_sha, expected_base_sha,
    target_base_sha, status, result_code_fingerprint, result_head_sha,
    result_evidence, requested_at_ms, completed_at_ms, error_message)
SELECT id, branch_sync_episode_id, branch_sync_effect_step_id,
       remote_development_stage_id, task_id, task_epoch, stage_generation, kind,
       operation_id, semantic_attempt, stage_turn_id, task_turn_id,
       expected_code_fingerprint, expected_head_sha, expected_base_sha,
       target_base_sha, status, result_code_fingerprint, result_head_sha,
       result_evidence, requested_at_ms, completed_at_ms, error_message
FROM branch_sync_dispatch_operation_v319_old;

DROP TABLE branch_sync_dispatch_operation_v319_old;
PRAGMA legacy_alter_table = OFF;

CREATE UNIQUE INDEX idx_branch_sync_one_live_dispatch
    ON branch_sync_dispatch_operation(branch_sync_episode_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TRIGGER branch_sync_dispatch_operation_dispatched
BEFORE UPDATE OF status ON branch_sync_dispatch_operation
WHEN NEW.status = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.operation_id = NEW.operation_id
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch lacks its DispatchTicket'); END;

CREATE TRIGGER branch_sync_dispatch_operation_identity_immutable
BEFORE UPDATE OF id, branch_sync_episode_id, branch_sync_effect_step_id,
        remote_development_stage_id, task_id, task_epoch, stage_generation,
        kind, operation_id, semantic_attempt, stage_turn_id, task_turn_id,
        expected_code_fingerprint, expected_head_sha, expected_base_sha,
        target_base_sha, requested_at_ms ON branch_sync_dispatch_operation
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch identity is immutable'); END;

CREATE TRIGGER branch_sync_dispatch_operation_insert
BEFORE INSERT ON branch_sync_dispatch_operation
WHEN NOT EXISTS (
    SELECT 1
    FROM branch_sync_episode episode
    JOIN branch_sync_effect_step step
      ON step.branch_sync_episode_id = episode.id
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE episode.id = NEW.branch_sync_episode_id
      AND step.id = NEW.branch_sync_effect_step_id
      AND step.kind = NEW.kind
      AND step.status = 'REQUESTED'
      AND episode.remote_development_stage_id =
          NEW.remote_development_stage_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.stage_generation = NEW.stage_generation
      AND episode.target_base_sha = NEW.target_base_sha
      AND episode.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED')
      AND remote.current_head_sha = episode.old_head_sha
      AND remote.current_base_sha = episode.observed_base_sha
      AND NEW.expected_head_sha = code.head_sha
      AND NEW.expected_base_sha = code.base_sha
      AND (NEW.expected_code_fingerprint IS NULL
          OR NEW.expected_code_fingerprint = code.code_fingerprint)
      AND task.epoch = NEW.task_epoch
      AND task.lifecycle_state = 'ACTIVE'
      AND NEW.status = 'REQUESTED'
      AND (NEW.kind <> 'CONFLICT_REPAIR' OR EXISTS (
          SELECT 1 FROM stage_turn turn
          WHERE turn.id = NEW.stage_turn_id
            AND turn.operation_id = NEW.operation_id
            AND turn.stage_id = NEW.remote_development_stage_id
            AND turn.stage_generation = NEW.stage_generation
            AND turn.task_epoch = NEW.task_epoch
            AND turn.attempt = NEW.semantic_attempt
            AND turn.expected_code_fingerprint =
                NEW.expected_code_fingerprint
            AND turn.expected_head_sha = NEW.expected_head_sha
            AND turn.expected_base_sha = NEW.expected_base_sha
            AND turn.purpose = 'BRANCH_CONFLICT_REPAIR'
            AND turn.status = 'QUEUED'))
      AND (NEW.kind <> 'BRAIN_REVIEW' OR EXISTS (
          SELECT 1 FROM task_turn turn
          WHERE turn.id = NEW.task_turn_id
            AND turn.operation_id = NEW.operation_id
            AND turn.task_id = NEW.task_id
            AND turn.task_epoch = NEW.task_epoch
            AND turn.trigger_stage_id = NEW.remote_development_stage_id
            AND turn.trigger_stage_generation = NEW.stage_generation
            AND turn.attempt = NEW.semantic_attempt
            AND turn.expected_code_fingerprint =
                NEW.expected_code_fingerprint
            AND turn.expected_head_sha = NEW.expected_head_sha
            AND turn.expected_base_sha = NEW.expected_base_sha
            AND turn.purpose = 'BRANCH_SYNC_BRAIN_REVIEW'
            AND turn.status = 'REQUESTED')))
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch requires its exact worktree subject'); END;

CREATE TRIGGER branch_sync_dispatch_operation_status
BEFORE UPDATE OF status ON branch_sync_dispatch_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'SUCCEEDED', 'FAILED', 'INDETERMINATE',
        'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'INDETERMINATE',
        'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch status transition is invalid'); END;

-- A CI repair writer is authorized only by an exact accepted Remote
-- observation.  Continuations freeze the accepted snapshot/revision that was
-- current when their predecessor terminalized and require a distinct later
-- revision before another StageTurn can be dispatched.
CREATE TABLE ci_repair_turn_freshness_v319 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id        TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    intent_kind                 TEXT    NOT NULL CHECK (intent_kind IN (
        'OBSERVED_FAILURE', 'NO_CHANGE_CONTINUATION', 'NEXT_FIX',
        'MANUAL_BASE_REPAIR', 'STEERING')),
    intent_id                   TEXT    NOT NULL,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt           INTEGER NOT NULL CHECK (execution_attempt > 0),
    predecessor_snapshot_id     TEXT REFERENCES remote_pr_snapshot(id),
    predecessor_observation_revision INTEGER CHECK (
        predecessor_observation_revision IS NULL
        OR predecessor_observation_revision > 0),
    accepted_snapshot_id        TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    accepted_observation_revision INTEGER NOT NULL CHECK (
        accepted_observation_revision > 0),
    accepted_ci_evaluation_id   TEXT    NOT NULL REFERENCES remote_ci_evaluation(id),
    remote_head_sha             TEXT    NOT NULL,
    authoritative_base_sha      TEXT    NOT NULL,
    code_fingerprint            TEXT    NOT NULL,
    code_head_sha               TEXT    NOT NULL,
    code_base_sha               TEXT    NOT NULL,
    prepublish_branch_sync_episode_id TEXT
        REFERENCES branch_sync_episode(id),
    authorized_at_ms            INTEGER NOT NULL CHECK (authorized_at_ms >= 0),
    UNIQUE (intent_kind, intent_id),
    CHECK ((predecessor_snapshot_id IS NULL)
        = (predecessor_observation_revision IS NULL)),
    CHECK (code_base_sha = authoritative_base_sha),
    CHECK (length(trim(intent_id)) > 0
        AND length(trim(remote_head_sha)) > 0
        AND length(trim(authoritative_base_sha)) > 0
        AND length(trim(code_fingerprint)) > 0
        AND length(trim(code_head_sha)) > 0)
);

CREATE TRIGGER ci_repair_turn_freshness_immutable_v319
BEFORE UPDATE ON ci_repair_turn_freshness_v319
BEGIN SELECT RAISE(ABORT, 'CI repair freshness proof is immutable'); END;

CREATE TABLE ci_repair_manual_turn_intent_v319 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id        TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    base_repair_authorization_id TEXT   NOT NULL UNIQUE
        REFERENCES ci_base_repair_authorization_v303(id),
    predecessor_snapshot_id     TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    predecessor_observation_revision INTEGER NOT NULL CHECK (
        predecessor_observation_revision > 0),
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    status                      TEXT    NOT NULL CHECK (status IN (
        'PENDING', 'DISPATCHED', 'CANCELED')),
    dispatched_operation_row_id TEXT UNIQUE REFERENCES ci_repair_operation(id),
    requested_at_ms             INTEGER NOT NULL CHECK (requested_at_ms >= 0),
    consumed_at_ms              INTEGER,
    UNIQUE (ci_repair_episode_id, semantic_attempt),
    CHECK ((status = 'PENDING') = (consumed_at_ms IS NULL)),
    CHECK ((status = 'DISPATCHED') =
        (dispatched_operation_row_id IS NOT NULL))
);

CREATE TRIGGER ci_repair_manual_turn_intent_insert_v319
BEFORE INSERT ON ci_repair_manual_turn_intent_v319
WHEN NEW.status <> 'PENDING' OR NOT EXISTS (
    SELECT 1
    FROM ci_repair_episode episode
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    JOIN ci_base_repair_authorization_v303 authorization
      ON authorization.id = NEW.base_repair_authorization_id
    WHERE episode.id = NEW.ci_repair_episode_id
      AND episode.classification = 'BASE_DETERMINISTIC'
      AND episode.status = 'OPEN'
      AND episode.fix_attempt_count + 1 = NEW.semantic_attempt
      AND authorization.ci_repair_episode_id = episode.id
      AND authorization.status = 'CLAIMED'
      AND remote.accepted_snapshot_id = NEW.predecessor_snapshot_id
      AND remote.accepted_observation_revision =
          NEW.predecessor_observation_revision
      AND NOT EXISTS (
          SELECT 1 FROM ci_repair_operation operation
          WHERE operation.ci_repair_episode_id = episode.id
            AND operation.status IN ('REQUESTED', 'DISPATCHED'))
      AND NOT EXISTS (
          SELECT 1 FROM ci_repair_fix_continuation_operation_v318 operation
          WHERE operation.ci_repair_episode_id = episode.id
            AND operation.status IN ('REQUESTED', 'DISPATCHED')))
BEGIN SELECT RAISE(ABORT, 'Manual CI repair intent lacks exact authority'); END;

CREATE TRIGGER ci_repair_manual_turn_intent_identity_v319
BEFORE UPDATE OF id, ci_repair_episode_id, base_repair_authorization_id,
        predecessor_snapshot_id, predecessor_observation_revision,
        semantic_attempt, requested_at_ms ON ci_repair_manual_turn_intent_v319
BEGIN SELECT RAISE(ABORT, 'Manual CI repair intent identity is immutable'); END;

CREATE TRIGGER ci_repair_manual_turn_intent_status_v319
BEFORE UPDATE OF status, dispatched_operation_row_id, consumed_at_ms
ON ci_repair_manual_turn_intent_v319
WHEN OLD.status <> 'PENDING'
  OR NEW.status NOT IN ('DISPATCHED', 'CANCELED')
  OR NEW.consumed_at_ms IS NULL
  OR (NEW.status = 'DISPATCHED' AND NOT EXISTS (
      SELECT 1
      FROM ci_repair_operation operation
      JOIN ci_repair_turn_freshness_v319 proof
        ON proof.ci_repair_episode_id = operation.ci_repair_episode_id
       AND proof.semantic_attempt = operation.semantic_attempt
       AND proof.intent_kind = 'MANUAL_BASE_REPAIR'
       AND proof.intent_id = NEW.id
      WHERE operation.id = NEW.dispatched_operation_row_id
        AND operation.ci_repair_episode_id = NEW.ci_repair_episode_id
        AND operation.kind = 'FIX_STAGE_TURN'
        AND operation.semantic_attempt = NEW.semantic_attempt
        AND operation.base_repair_authorization_id =
            NEW.base_repair_authorization_id
        AND operation.status = 'DISPATCHED'))
BEGIN SELECT RAISE(ABORT, 'Manual CI repair intent transition is invalid'); END;

CREATE TABLE ci_repair_steering_fence_v319 (
    request_id                    TEXT NOT NULL PRIMARY KEY
        REFERENCES stage_steering_request_v257(id) ON DELETE CASCADE,
    ci_repair_episode_id          TEXT NOT NULL REFERENCES ci_repair_episode(id),
    predecessor_snapshot_id       TEXT NOT NULL REFERENCES remote_pr_snapshot(id),
    predecessor_observation_revision INTEGER NOT NULL CHECK (
        predecessor_observation_revision > 0),
    semantic_attempt              INTEGER NOT NULL CHECK (semantic_attempt > 0),
    recorded_at_ms                INTEGER NOT NULL CHECK (recorded_at_ms >= 0)
);

CREATE TRIGGER ci_repair_steering_fence_immutable_v319
BEFORE UPDATE ON ci_repair_steering_fence_v319
BEGIN SELECT RAISE(ABORT, 'CI repair steering freshness fence is immutable'); END;

-- The proof is inserted in the same Task command immediately after accepting
-- the candidate snapshot.  It must name that exact current Remote subject and
-- the current local code subject, whose base must equal the authoritative
-- observed target.  A live BranchSync therefore always defers writer launch.
CREATE TRIGGER ci_repair_turn_freshness_insert_v319
BEFORE INSERT ON ci_repair_turn_freshness_v319
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_repair_episode episode
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    JOIN remote_pr_snapshot snapshot
      ON snapshot.id = NEW.accepted_snapshot_id
    JOIN remote_ci_evaluation evaluation
      ON evaluation.id = NEW.accepted_ci_evaluation_id
    JOIN task_current_code_subject_v230 code
      ON code.task_id = episode.task_id
    WHERE episode.id = NEW.ci_repair_episode_id
      AND episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
      AND remote.accepted_snapshot_id = NEW.accepted_snapshot_id
      AND remote.accepted_observation_revision =
          NEW.accepted_observation_revision
      AND remote.current_head_sha = NEW.remote_head_sha
      AND remote.current_base_sha = NEW.authoritative_base_sha
      AND snapshot.remote_development_stage_id =
          episode.remote_development_stage_id
      AND snapshot.task_id = episode.task_id
      AND snapshot.task_epoch = episode.task_epoch
      AND snapshot.stage_generation = episode.stage_generation
      AND snapshot.observation_revision = NEW.accepted_observation_revision
      AND snapshot.head_sha = NEW.remote_head_sha
      AND snapshot.base_sha = NEW.authoritative_base_sha
      AND evaluation.remote_pr_snapshot_id = snapshot.id
      AND evaluation.head_sha = NEW.remote_head_sha
      AND evaluation.base_sha = NEW.authoritative_base_sha
      AND evaluation.policy_outcome = 'FAILED'
      AND code.code_fingerprint = NEW.code_fingerprint
      AND code.head_sha = NEW.code_head_sha
      AND code.base_sha = NEW.code_base_sha
      AND NEW.code_base_sha = NEW.authoritative_base_sha
      AND NOT EXISTS (
          SELECT 1 FROM branch_sync_episode branch
          WHERE branch.remote_development_stage_id =
                episode.remote_development_stage_id
            AND branch.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED'))
      AND ((NEW.predecessor_snapshot_id IS NULL
              AND NEW.predecessor_observation_revision IS NULL)
        OR (NEW.accepted_snapshot_id <> NEW.predecessor_snapshot_id
              AND NEW.accepted_observation_revision >
                  NEW.predecessor_observation_revision))
      AND (
        (NEW.intent_kind = 'OBSERVED_FAILURE'
          AND NEW.intent_id = evaluation.id
          AND NEW.predecessor_snapshot_id IS NULL
          AND NEW.semantic_attempt = episode.fix_attempt_count + 1
          AND NEW.execution_attempt = NEW.semantic_attempt
          AND evaluation.policy_outcome = 'FAILED')
        OR (NEW.intent_kind = 'NO_CHANGE_CONTINUATION' AND EXISTS (
            SELECT 1 FROM ci_repair_fix_continuation_due_v318 due
            WHERE due.id = NEW.intent_id
              AND due.ci_repair_episode_id = episode.id
              AND due.status = 'PENDING'
              AND due.semantic_attempt = NEW.semantic_attempt
              AND due.execution_attempt = NEW.execution_attempt
              AND due.predecessor_accepted_snapshot_id =
                  NEW.predecessor_snapshot_id
              AND due.predecessor_accepted_observation_revision =
                  NEW.predecessor_observation_revision))
        OR (NEW.intent_kind = 'NEXT_FIX' AND EXISTS (
            SELECT 1 FROM ci_repair_next_fix_due_v318 due
            WHERE due.id = NEW.intent_id
              AND due.ci_repair_episode_id = episode.id
              AND due.status = 'PENDING'
              AND due.requested_semantic_attempt = NEW.semantic_attempt
              AND due.requested_semantic_attempt = NEW.execution_attempt
              AND due.predecessor_accepted_snapshot_id =
                  NEW.predecessor_snapshot_id
              AND due.predecessor_accepted_observation_revision =
                  NEW.predecessor_observation_revision))
        OR (NEW.intent_kind = 'MANUAL_BASE_REPAIR' AND EXISTS (
            SELECT 1 FROM ci_repair_manual_turn_intent_v319 intent
            WHERE intent.id = NEW.intent_id
              AND intent.ci_repair_episode_id = episode.id
              AND intent.status = 'PENDING'
              AND intent.semantic_attempt = NEW.semantic_attempt
              AND NEW.execution_attempt = NEW.semantic_attempt
              AND intent.predecessor_snapshot_id =
                  NEW.predecessor_snapshot_id
              AND intent.predecessor_observation_revision =
                  NEW.predecessor_observation_revision))
        OR (NEW.intent_kind = 'STEERING' AND EXISTS (
            SELECT 1
            FROM ci_repair_steering_fence_v319 fence
            JOIN stage_steering_request_v257 steering
              ON steering.id = fence.request_id
            WHERE fence.request_id = NEW.intent_id
              AND fence.ci_repair_episode_id = episode.id
              AND steering.status = 'PENDING'
              AND steering.predecessor_purpose = 'REMOTE_CI_REPAIR'
              AND fence.semantic_attempt = NEW.semantic_attempt
              AND NEW.execution_attempt = NEW.semantic_attempt
              AND fence.predecessor_snapshot_id =
                  NEW.predecessor_snapshot_id
              AND fence.predecessor_observation_revision =
                  NEW.predecessor_observation_revision))))
BEGIN SELECT RAISE(ABORT, 'CI repair Turn lacks fresh exact Remote proof'); END;

CREATE TRIGGER ci_repair_stage_turn_freshness_v319
BEFORE INSERT ON ci_repair_operation
WHEN NEW.kind = 'FIX_STAGE_TURN' AND NOT EXISTS (
    SELECT 1 FROM ci_repair_turn_freshness_v319 proof
    WHERE proof.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND proof.semantic_attempt = NEW.semantic_attempt
      AND proof.execution_attempt = NEW.semantic_attempt
      AND proof.code_fingerprint = NEW.expected_code_fingerprint
      AND proof.code_head_sha = NEW.expected_head_sha
      AND proof.code_base_sha = NEW.expected_base_sha
      AND ((NEW.base_repair_authorization_id IS NULL
              AND proof.intent_kind IN ('OBSERVED_FAILURE', 'NEXT_FIX'))
        OR (NEW.base_repair_authorization_id IS NOT NULL
              AND ((proof.intent_kind = 'MANUAL_BASE_REPAIR' AND EXISTS (
                  SELECT 1 FROM ci_repair_manual_turn_intent_v319 intent
                  WHERE intent.id = proof.intent_id
                    AND intent.base_repair_authorization_id =
                        NEW.base_repair_authorization_id))
                OR (proof.intent_kind IN ('OBSERVED_FAILURE', 'NEXT_FIX')
                    AND EXISTS (
                      SELECT 1 FROM ci_base_repair_authorization_v303 authorization
                      WHERE authorization.id =
                            NEW.base_repair_authorization_id
                        AND authorization.authority_kind =
                            'AUTO_APPROVE_POLICY'))))))
BEGIN SELECT RAISE(ABORT, 'CI repair StageTurn lacks freshness authorization'); END;

CREATE TRIGGER ci_repair_continuation_freshness_v319
BEFORE INSERT ON ci_repair_fix_continuation_operation_v318
WHEN NOT EXISTS (
    SELECT 1 FROM ci_repair_turn_freshness_v319 proof
    WHERE proof.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND proof.intent_kind = 'NO_CHANGE_CONTINUATION'
      AND proof.intent_id = NEW.continuation_due_id
      AND proof.semantic_attempt = NEW.semantic_attempt
      AND proof.execution_attempt = NEW.execution_attempt
      AND proof.code_fingerprint = NEW.expected_code_fingerprint
      AND proof.code_head_sha = NEW.expected_head_sha
      AND proof.code_base_sha = NEW.expected_base_sha)
BEGIN SELECT RAISE(ABORT, 'CI continuation lacks freshness authorization'); END;

CREATE TRIGGER ci_repair_steering_freshness_v319
BEFORE INSERT ON remote_repair_steering_turn_v257
WHEN NEW.owner_family = 'CI_REPAIR' AND NOT EXISTS (
    SELECT 1
    FROM ci_repair_turn_freshness_v319 proof
    JOIN stage_turn turn ON turn.id = NEW.stage_turn_id
    WHERE proof.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND proof.intent_kind = 'STEERING'
      AND proof.intent_id = NEW.request_id
      AND proof.semantic_attempt = NEW.semantic_attempt
      AND proof.execution_attempt = NEW.semantic_attempt
      AND proof.code_fingerprint = turn.expected_code_fingerprint
      AND proof.code_head_sha = turn.expected_head_sha
      AND proof.code_base_sha = turn.expected_base_sha)
BEGIN SELECT RAISE(ABORT, 'CI steering Turn lacks freshness authorization'); END;

-- BranchSync keeps its existing finite effect protocol and budget.  These
-- immutable columns distinguish scheduled maintenance from a CI precondition
-- and freeze the authority that allowed the latter to bypass a disabled
-- scheduled guard.
ALTER TABLE branch_sync_episode ADD COLUMN purpose TEXT NOT NULL
    DEFAULT 'SCHEDULED' CHECK (purpose IN (
        'SCHEDULED', 'CI_PRECONDITION', 'CI_PRECONDITION_LOCAL'));
ALTER TABLE branch_sync_episode ADD COLUMN authority_kind TEXT NOT NULL
    DEFAULT 'BRANCH_SYNC_POLICY' CHECK (authority_kind IN (
        'BRANCH_SYNC_POLICY', 'AUTO_APPROVE_POLICY', 'MANUAL'));
ALTER TABLE branch_sync_episode ADD COLUMN authority_id TEXT;
ALTER TABLE branch_sync_episode ADD COLUMN ci_repair_episode_id TEXT
    REFERENCES ci_repair_episode(id);
ALTER TABLE branch_sync_episode ADD COLUMN ci_turn_intent_kind TEXT CHECK (
    ci_turn_intent_kind IS NULL OR ci_turn_intent_kind IN (
        'NO_CHANGE_CONTINUATION', 'NEXT_FIX',
        'MANUAL_BASE_REPAIR', 'STEERING'));
ALTER TABLE branch_sync_episode ADD COLUMN ci_turn_intent_id TEXT;
ALTER TABLE branch_sync_episode ADD COLUMN source_code_fingerprint TEXT;
ALTER TABLE branch_sync_episode ADD COLUMN source_code_head_sha TEXT;
ALTER TABLE branch_sync_episode ADD COLUMN source_code_base_sha TEXT;
ALTER TABLE branch_sync_episode ADD COLUMN result_code_fingerprint TEXT;
ALTER TABLE ci_repair_operation ADD COLUMN prepublish_branch_sync_episode_id
    TEXT REFERENCES branch_sync_episode(id);
UPDATE branch_sync_episode
SET authority_id = branch_sync_policy_revision_id
WHERE authority_id IS NULL;

CREATE UNIQUE INDEX branch_sync_ci_turn_intent_v319
    ON branch_sync_episode(ci_turn_intent_kind, ci_turn_intent_id)
    WHERE purpose = 'CI_PRECONDITION_LOCAL';

-- Every local code writer exposes one immutable predecessor edge.  The
-- freshness protocol follows these edges instead of inferring ancestry from
-- whichever subject table currently wins task_current_code_subject_v230.
CREATE VIEW remote_code_subject_predecessor_v319 AS
SELECT subject.task_id,
       subject.task_epoch,
       subject.remote_development_stage_id,
       subject.stage_generation,
       subject.code_fingerprint,
       subject.head_sha,
       subject.base_sha,
       subject.source_code_fingerprint,
       subject.source_head_sha,
       subject.source_base_sha
FROM remote_code_subject subject
UNION ALL
SELECT subject.task_id,
       subject.task_epoch,
       subject.remote_stage_id,
       subject.stage_generation,
       subject.code_fingerprint,
       subject.head_sha,
       subject.base_sha,
       subject.source_code_fingerprint,
       subject.source_head_sha,
       subject.source_base_sha
FROM remote_steering_code_subject_v257 subject
UNION ALL
SELECT subject.task_id,
       subject.task_epoch,
       subject.remote_development_stage_id,
       subject.stage_generation,
       subject.code_fingerprint,
       subject.head_sha,
       subject.base_sha,
       operation.expected_code_fingerprint,
       operation.expected_head_sha,
       operation.expected_base_sha
FROM ci_base_repair_subject_v303 subject
JOIN ci_repair_operation operation
  ON operation.id = subject.ci_repair_operation_id
UNION ALL
SELECT subject.task_id,
       subject.task_epoch,
       subject.remote_development_stage_id,
       subject.stage_generation,
       subject.code_fingerprint,
       subject.head_sha,
       subject.base_sha,
       operation.expected_code_fingerprint,
       operation.expected_head_sha,
       operation.expected_base_sha
FROM remote_worktree_subject subject
JOIN branch_sync_dispatch_operation operation
  ON operation.operation_id = subject.source_operation_id
WHERE subject.source_kind = 'BRANCH_EFFECT'
UNION ALL
SELECT subject.task_id,
       subject.task_epoch,
       subject.remote_development_stage_id,
       subject.stage_generation,
       subject.code_fingerprint,
       subject.head_sha,
       subject.base_sha,
       action.expected_code_fingerprint,
       action.expected_head_sha,
       action.expected_base_sha
FROM remote_worktree_subject subject
JOIN v2_user_remote_action_v270 action
  ON action.operation_id = subject.source_operation_id
WHERE subject.source_kind = 'USER_CI_TRIGGER';

-- A locally rebased precondition remains publication authority across later
-- validation/Brain repair Turns.  Walk the immutable code-subject predecessor
-- chain so every later freshness proof must carry that same exact Episode.
CREATE TRIGGER ci_repair_turn_requires_prepublish_v319
BEFORE INSERT ON ci_repair_turn_freshness_v319
WHEN NEW.prepublish_branch_sync_episode_id IS NULL AND EXISTS (
    WITH RECURSIVE lineage(code_fingerprint, head_sha, base_sha) AS (
        SELECT NEW.code_fingerprint, NEW.code_head_sha, NEW.code_base_sha
        UNION
        SELECT predecessor.source_code_fingerprint,
               predecessor.source_head_sha, predecessor.source_base_sha
        FROM remote_code_subject_predecessor_v319 predecessor
        JOIN lineage child
          ON child.code_fingerprint = predecessor.code_fingerprint
         AND child.head_sha = predecessor.head_sha
         AND child.base_sha = predecessor.base_sha
        JOIN ci_repair_episode ci ON ci.id = NEW.ci_repair_episode_id
        WHERE predecessor.task_id = ci.task_id
          AND predecessor.task_epoch = ci.task_epoch
          AND predecessor.remote_development_stage_id =
              ci.remote_development_stage_id
          AND predecessor.stage_generation = ci.stage_generation
          AND predecessor.source_code_fingerprint IS NOT NULL)
    SELECT 1
    FROM branch_sync_episode branch
    JOIN lineage code
      ON code.code_fingerprint = branch.result_code_fingerprint
     AND code.head_sha = branch.result_head_sha
     AND code.base_sha = branch.target_base_sha
    WHERE branch.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND branch.purpose = 'CI_PRECONDITION_LOCAL'
      AND branch.status = 'SUCCEEDED'
      AND branch.old_head_sha = NEW.remote_head_sha
      AND branch.target_base_sha = NEW.authoritative_base_sha)
BEGIN SELECT RAISE(ABORT, 'CI repair proof dropped prepublish BranchSync lineage'); END;

CREATE TRIGGER ci_repair_turn_prepublish_exact_v319
BEFORE INSERT ON ci_repair_turn_freshness_v319
WHEN NEW.prepublish_branch_sync_episode_id IS NOT NULL AND NOT EXISTS (
    WITH RECURSIVE lineage(code_fingerprint, head_sha, base_sha) AS (
        SELECT NEW.code_fingerprint, NEW.code_head_sha, NEW.code_base_sha
        UNION
        SELECT predecessor.source_code_fingerprint,
               predecessor.source_head_sha, predecessor.source_base_sha
        FROM remote_code_subject_predecessor_v319 predecessor
        JOIN lineage child
          ON child.code_fingerprint = predecessor.code_fingerprint
         AND child.head_sha = predecessor.head_sha
         AND child.base_sha = predecessor.base_sha
        JOIN ci_repair_episode ci ON ci.id = NEW.ci_repair_episode_id
        WHERE predecessor.task_id = ci.task_id
          AND predecessor.task_epoch = ci.task_epoch
          AND predecessor.remote_development_stage_id =
              ci.remote_development_stage_id
          AND predecessor.stage_generation = ci.stage_generation
          AND predecessor.source_code_fingerprint IS NOT NULL)
    SELECT 1
    FROM branch_sync_episode branch
    JOIN lineage code
      ON code.code_fingerprint = branch.result_code_fingerprint
     AND code.head_sha = branch.result_head_sha
     AND code.base_sha = branch.target_base_sha
    WHERE branch.id = NEW.prepublish_branch_sync_episode_id
      AND branch.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND branch.purpose = 'CI_PRECONDITION_LOCAL'
      AND branch.status = 'SUCCEEDED'
      AND branch.old_head_sha = NEW.remote_head_sha
      AND branch.target_base_sha = NEW.authoritative_base_sha)
BEGIN SELECT RAISE(ABORT, 'CI repair proof has stale prepublish BranchSync lineage'); END;

-- A semantic effect retry is another immutable dispatch row for the same
-- ordered step.  The consolidated baseline keys those rows by
-- (step, semantic_attempt); this guard makes the next attempt exact.
CREATE TRIGGER branch_sync_dispatch_attempt_v319
BEFORE INSERT ON branch_sync_dispatch_operation
WHEN NOT EXISTS (
    SELECT 1 FROM branch_sync_effect_step step
    WHERE step.id = NEW.branch_sync_effect_step_id
      AND step.branch_sync_episode_id = NEW.branch_sync_episode_id
      AND step.status = 'REQUESTED'
      AND NEW.semantic_attempt = step.attempt_count + 1
      AND NEW.semantic_attempt <= step.attempt_limit)
BEGIN SELECT RAISE(ABORT, 'Branch sync dispatch exceeds its frozen step budget'); END;

CREATE TRIGGER branch_sync_effect_retry_v319
BEFORE UPDATE OF status ON branch_sync_effect_step
WHEN NEW.status = 'REQUESTED' AND (
    OLD.status <> 'FAILED'
    OR OLD.attempt_count >= OLD.attempt_limit
    OR NEW.attempt_count <> OLD.attempt_count
    OR NEW.claim_mode IS NOT NULL OR NEW.claim_owner IS NOT NULL
    OR NEW.claimed_at_ms IS NOT NULL OR NEW.lease_until_ms IS NOT NULL
    OR NEW.evidence IS NOT NULL OR NEW.last_error IS NOT NULL
    OR NEW.completed_at_ms IS NOT NULL)
BEGIN SELECT RAISE(ABORT, 'Branch sync effect retry is not exact'); END;

CREATE TRIGGER ci_repair_prepublish_branch_identity_v319
BEFORE UPDATE OF prepublish_branch_sync_episode_id ON ci_repair_operation
BEGIN SELECT RAISE(ABORT, 'CI prepublish BranchSync identity is immutable'); END;

CREATE TRIGGER ci_repair_prepublish_branch_shape_v319
BEFORE INSERT ON ci_repair_operation
WHEN NEW.prepublish_branch_sync_episode_id IS NOT NULL
  AND NEW.kind <> 'PUSH_HEAD'
BEGIN SELECT RAISE(ABORT, 'CI prepublish BranchSync applies only to push'); END;

CREATE TRIGGER ci_repair_push_prepublish_branch_v319
BEFORE INSERT ON ci_repair_operation
WHEN NEW.kind = 'PUSH_HEAD' AND NOT EXISTS (
        SELECT 1
        FROM ci_repair_turn_freshness_v319 proof
        LEFT JOIN branch_sync_episode branch
          ON branch.id = proof.prepublish_branch_sync_episode_id
        JOIN ci_repair_episode ci
          ON ci.id = proof.ci_repair_episode_id
        JOIN remote_development_stage remote
          ON remote.stage_id = ci.remote_development_stage_id
        WHERE proof.ci_repair_episode_id = NEW.ci_repair_episode_id
          AND proof.semantic_attempt = NEW.semantic_attempt
          AND ((proof.prepublish_branch_sync_episode_id IS NULL
                  AND NEW.prepublish_branch_sync_episode_id IS NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM ci_repair_turn_freshness_v319 carried
                      WHERE carried.ci_repair_episode_id =
                            NEW.ci_repair_episode_id
                        AND carried.semantic_attempt = NEW.semantic_attempt
                        AND carried.prepublish_branch_sync_episode_id
                            IS NOT NULL))
            OR (proof.prepublish_branch_sync_episode_id =
                    NEW.prepublish_branch_sync_episode_id
                  AND branch.purpose = 'CI_PRECONDITION_LOCAL'
                  AND branch.status = 'SUCCEEDED'
                  AND branch.target_base_sha = NEW.expected_base_sha
                  AND branch.old_head_sha = NEW.lease_expected_sha
                  AND EXISTS (
                      WITH RECURSIVE lineage(
                              code_fingerprint, head_sha, base_sha) AS (
                          SELECT NEW.expected_code_fingerprint,
                                 NEW.expected_head_sha,
                                 NEW.expected_base_sha
                          UNION
                          SELECT predecessor.source_code_fingerprint,
                                 predecessor.source_head_sha,
                                 predecessor.source_base_sha
                          FROM remote_code_subject_predecessor_v319 predecessor
                          JOIN lineage child
                            ON child.code_fingerprint =
                                  predecessor.code_fingerprint
                           AND child.head_sha = predecessor.head_sha
                           AND child.base_sha = predecessor.base_sha
                          WHERE predecessor.task_id = NEW.task_id
                            AND predecessor.task_epoch = NEW.task_epoch
                            AND predecessor.remote_development_stage_id =
                                  NEW.remote_development_stage_id
                            AND predecessor.stage_generation =
                                  NEW.stage_generation
                            AND predecessor.source_code_fingerprint IS NOT NULL)
                      SELECT 1 FROM lineage code
                      WHERE code.code_fingerprint =
                            branch.result_code_fingerprint
                        AND code.head_sha = branch.result_head_sha
                        AND code.base_sha = branch.target_base_sha)
                  AND remote.accepted_snapshot_id = proof.accepted_snapshot_id
                  AND remote.current_head_sha = branch.old_head_sha
                  AND remote.current_base_sha = branch.target_base_sha)))
BEGIN SELECT RAISE(ABORT, 'CI push lacks exact local-precondition lineage'); END;

CREATE TABLE branch_sync_exhaustion_v319 (
    branch_sync_episode_id TEXT NOT NULL PRIMARY KEY
        REFERENCES branch_sync_episode(id) ON DELETE CASCADE,
    blocker_id          TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    task_id             TEXT    NOT NULL REFERENCES tasks(id),
    stage_id            TEXT    NOT NULL REFERENCES stage(id),
    remote_head_sha     TEXT    NOT NULL,
    remote_base_sha     TEXT    NOT NULL,
    code_fingerprint    TEXT    NOT NULL,
    code_head_sha       TEXT    NOT NULL,
    code_base_sha       TEXT    NOT NULL,
    reason              TEXT    NOT NULL,
    exhausted_at_ms     INTEGER NOT NULL CHECK (exhausted_at_ms >= 0),
    CHECK (length(trim(reason)) > 0)
);

CREATE TRIGGER branch_sync_exhaustion_insert_v319
BEFORE INSERT ON branch_sync_exhaustion_v319
WHEN NOT EXISTS (
    SELECT 1
    FROM branch_sync_episode episode
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    JOIN task_current_code_subject_v230 code ON code.task_id = episode.task_id
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    WHERE episode.id = NEW.branch_sync_episode_id
      AND episode.status = 'FAILED'
      AND episode.task_id = NEW.task_id
      AND episode.remote_development_stage_id = NEW.stage_id
      AND remote.current_head_sha = NEW.remote_head_sha
      AND remote.current_base_sha = NEW.remote_base_sha
      AND code.code_fingerprint = NEW.code_fingerprint
      AND code.head_sha = NEW.code_head_sha
      AND code.base_sha = NEW.code_base_sha
      AND blocker.task_id = NEW.task_id
      AND blocker.stage_id = NEW.stage_id
      AND blocker.owner_kind = 'EPISODE'
      AND blocker.owner_id = episode.id
      AND blocker.blocker_type = 'BRANCH_SYNC_EXHAUSTED'
      AND blocker.status = 'OPEN')
BEGIN SELECT RAISE(ABORT, 'Branch sync exhaustion lacks its exact subject'); END;

CREATE TRIGGER branch_sync_exhaustion_immutable_v319
BEFORE UPDATE ON branch_sync_exhaustion_v319
BEGIN SELECT RAISE(ABORT, 'Branch sync exhaustion is immutable'); END;

-- Exhausted BranchSync is recovered by its own domain command.  Both choices
-- are terminal control decisions: they resolve the attention item while the
-- immutable exhaustion continues to suppress the exact unchanged subject.
CREATE TABLE branch_sync_control_command_v319 (
    id                     TEXT    NOT NULL PRIMARY KEY,
    branch_sync_episode_id TEXT    NOT NULL UNIQUE
        REFERENCES branch_sync_episode(id) ON DELETE CASCADE,
    blocker_id             TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    task_id                TEXT    NOT NULL REFERENCES tasks(id),
    stage_id               TEXT    NOT NULL REFERENCES stage(id),
    command_id             TEXT    NOT NULL UNIQUE,
    kind                   TEXT    NOT NULL CHECK (kind IN (
        'MANUAL_TAKEOVER', 'STOP_AUTOMATION')),
    actor                  TEXT    NOT NULL,
    reason                 TEXT    NOT NULL,
    created_at_ms          INTEGER NOT NULL CHECK (created_at_ms >= 0),
    consumed_at_ms         INTEGER NOT NULL CHECK (consumed_at_ms >= created_at_ms),
    CHECK (length(trim(command_id)) > 0
        AND length(trim(actor)) > 0
        AND length(trim(reason)) > 0)
);

CREATE TRIGGER branch_sync_control_command_insert_v319
BEFORE INSERT ON branch_sync_control_command_v319
WHEN NOT EXISTS (
    SELECT 1
    FROM branch_sync_exhaustion_v319 exhaustion
    JOIN branch_sync_episode episode
      ON episode.id = exhaustion.branch_sync_episode_id
    JOIN task_blocker blocker ON blocker.id = exhaustion.blocker_id
    JOIN tasks task ON task.id = exhaustion.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN remote_development_stage remote
      ON remote.stage_id = exhaustion.stage_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE exhaustion.branch_sync_episode_id = NEW.branch_sync_episode_id
      AND exhaustion.blocker_id = NEW.blocker_id
      AND exhaustion.task_id = NEW.task_id
      AND exhaustion.stage_id = NEW.stage_id
      AND episode.status = 'FAILED'
      AND episode.task_id = task.id
      AND episode.remote_development_stage_id = owner.id
      AND episode.task_epoch = task.epoch
      AND episode.stage_generation = owner.generation
      AND blocker.task_id = task.id
      AND blocker.stage_id = owner.id
      AND blocker.owner_kind = 'EPISODE'
      AND blocker.owner_id = episode.id
      AND blocker.subject_revision = episode.id
      AND blocker.blocker_type = 'BRANCH_SYNC_EXHAUSTED'
      AND blocker.status = 'OPEN'
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND current.stage_id = owner.id
      AND current.stage_generation = owner.generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND remote.current_head_sha = exhaustion.remote_head_sha
      AND remote.current_base_sha = exhaustion.remote_base_sha
      AND code.code_fingerprint = exhaustion.code_fingerprint
      AND code.head_sha = exhaustion.code_head_sha
      AND code.base_sha = exhaustion.code_base_sha)
BEGIN SELECT RAISE(ABORT, 'Branch sync control lost its exact exhaustion'); END;

CREATE TRIGGER branch_sync_control_command_immutable_v319
BEFORE UPDATE ON branch_sync_control_command_v319
BEGIN SELECT RAISE(ABORT, 'Branch sync control command is immutable'); END;

CREATE TABLE ci_branch_sync_manual_authorization_v319 (
    id                  TEXT    NOT NULL PRIMARY KEY,
    blocker_id          TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    ci_repair_episode_id TEXT   NOT NULL REFERENCES ci_repair_episode(id),
    task_id             TEXT    NOT NULL REFERENCES tasks(id),
    stage_id            TEXT    NOT NULL REFERENCES stage(id),
    predecessor_snapshot_id TEXT NOT NULL REFERENCES remote_pr_snapshot(id),
    predecessor_observation_revision INTEGER NOT NULL CHECK (
        predecessor_observation_revision > 0),
    command_id          TEXT    NOT NULL UNIQUE,
    observation_operation_id TEXT NOT NULL UNIQUE
        REFERENCES remote_observation_operation(operation_id),
    actor               TEXT    NOT NULL,
    reason              TEXT    NOT NULL,
    status              TEXT    NOT NULL CHECK (status IN (
        'CLAIMED', 'CONSUMED', 'CANCELED')),
    branch_sync_episode_id TEXT UNIQUE REFERENCES branch_sync_episode(id),
    claimed_at_ms       INTEGER NOT NULL CHECK (claimed_at_ms >= 0),
    consumed_at_ms      INTEGER,
    CHECK ((status = 'CONSUMED') =
        (branch_sync_episode_id IS NOT NULL AND consumed_at_ms IS NOT NULL)),
    CHECK (length(trim(actor)) > 0 AND length(trim(reason)) > 0)
);

CREATE UNIQUE INDEX ci_branch_sync_one_claimed_stage_v319
    ON ci_branch_sync_manual_authorization_v319(stage_id)
    WHERE status = 'CLAIMED';

CREATE TRIGGER ci_branch_sync_manual_authorization_insert_v319
BEFORE INSERT ON ci_branch_sync_manual_authorization_v319
WHEN NEW.status <> 'CLAIMED' OR NOT EXISTS (
    SELECT 1
    FROM task_blocker blocker
    JOIN remote_development_stage remote ON remote.stage_id = blocker.stage_id
    JOIN ci_repair_episode ci ON ci.id = NEW.ci_repair_episode_id
    JOIN remote_observation_operation observation
      ON observation.operation_id = NEW.observation_operation_id
    WHERE blocker.id = NEW.blocker_id
      AND blocker.task_id = NEW.task_id
      AND blocker.stage_id = NEW.stage_id
      AND blocker.owner_kind = 'STAGE'
      AND blocker.owner_id = NEW.stage_id
      AND blocker.blocker_type = 'CI_BRANCH_SYNC_REQUIRED'
      AND blocker.subject_revision = NEW.predecessor_snapshot_id
      AND blocker.status = 'OPEN'
      AND remote.task_id = NEW.task_id
      AND ci.task_id = NEW.task_id
      AND ci.remote_development_stage_id = NEW.stage_id
      AND ci.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
      AND observation.task_id = NEW.task_id
      AND observation.remote_development_stage_id = NEW.stage_id
      AND observation.task_epoch = ci.task_epoch
      AND observation.stage_generation = ci.stage_generation
      AND observation.status IN ('REQUESTED', 'DISPATCHED')
      AND remote.accepted_snapshot_id = NEW.predecessor_snapshot_id
      AND remote.accepted_observation_revision =
          NEW.predecessor_observation_revision)
BEGIN SELECT RAISE(ABORT, 'Manual CI branch sync lacks its exact blocker'); END;

CREATE TRIGGER ci_branch_sync_manual_authorization_identity_v319
BEFORE UPDATE OF id, blocker_id, ci_repair_episode_id, task_id, stage_id,
        predecessor_snapshot_id, predecessor_observation_revision,
        command_id, observation_operation_id, actor, reason, claimed_at_ms
ON ci_branch_sync_manual_authorization_v319
BEGIN SELECT RAISE(ABORT, 'Manual CI branch-sync authority is immutable'); END;

CREATE TRIGGER ci_branch_sync_manual_authorization_status_v319
BEFORE UPDATE OF status, branch_sync_episode_id, consumed_at_ms
ON ci_branch_sync_manual_authorization_v319
WHEN NEW.status <> 'CANCELED' AND (
  OLD.status <> 'CLAIMED' OR NEW.status <> 'CONSUMED'
  OR NEW.consumed_at_ms IS NULL OR NOT EXISTS (
      SELECT 1 FROM branch_sync_episode episode
      WHERE episode.id = NEW.branch_sync_episode_id
        AND episode.task_id = NEW.task_id
        AND episode.remote_development_stage_id = NEW.stage_id
        AND episode.ci_repair_episode_id = NEW.ci_repair_episode_id
        AND episode.purpose IN (
            'CI_PRECONDITION', 'CI_PRECONDITION_LOCAL')
        AND episode.authority_kind = 'MANUAL'
        AND episode.authority_id = NEW.id
        AND episode.source_snapshot_id <> NEW.predecessor_snapshot_id
        AND EXISTS (
            SELECT 1 FROM remote_pr_snapshot snapshot
            WHERE snapshot.id = episode.source_snapshot_id
              AND snapshot.observation_revision >
                  NEW.predecessor_observation_revision)))
BEGIN SELECT RAISE(ABORT, 'Manual CI branch-sync authority was not consumed exactly'); END;

CREATE TRIGGER ci_branch_sync_manual_authorization_cancel_v319
BEFORE UPDATE OF status, branch_sync_episode_id, consumed_at_ms
ON ci_branch_sync_manual_authorization_v319
WHEN NEW.status = 'CANCELED' AND (
    OLD.status <> 'CLAIMED' OR NEW.branch_sync_episode_id IS NOT NULL
    OR NEW.consumed_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT, 'Manual CI branch-sync cancellation is invalid'); END;

DROP TRIGGER branch_sync_episode_identity_immutable;
CREATE TRIGGER branch_sync_episode_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, source_snapshot_id,
        old_head_sha, observed_base_sha, target_base_sha, policy_source,
        attempt_limit, opened_at_ms, branch_sync_policy_revision_id,
        purpose, authority_kind, authority_id, ci_repair_episode_id,
        ci_turn_intent_kind, ci_turn_intent_id, source_code_fingerprint,
        source_code_head_sha, source_code_base_sha ON branch_sync_episode
WHEN NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.remote_pr_binding_id IS NOT OLD.remote_pr_binding_id
  OR NEW.source_snapshot_id IS NOT OLD.source_snapshot_id
  OR NEW.old_head_sha IS NOT OLD.old_head_sha
  OR NEW.observed_base_sha IS NOT OLD.observed_base_sha
  OR NEW.target_base_sha IS NOT OLD.target_base_sha
  OR NEW.policy_source IS NOT OLD.policy_source
  OR NEW.attempt_limit IS NOT OLD.attempt_limit
  OR NEW.opened_at_ms IS NOT OLD.opened_at_ms
  OR NEW.branch_sync_policy_revision_id IS NOT OLD.branch_sync_policy_revision_id
  OR NEW.purpose IS NOT OLD.purpose
  OR NEW.authority_kind IS NOT OLD.authority_kind
  OR NEW.authority_id IS NOT OLD.authority_id
  OR NEW.ci_repair_episode_id IS NOT OLD.ci_repair_episode_id
  OR NEW.ci_turn_intent_kind IS NOT OLD.ci_turn_intent_kind
  OR NEW.ci_turn_intent_id IS NOT OLD.ci_turn_intent_id
  OR NEW.source_code_fingerprint IS NOT OLD.source_code_fingerprint
  OR NEW.source_code_head_sha IS NOT OLD.source_code_head_sha
  OR NEW.source_code_base_sha IS NOT OLD.source_code_base_sha
BEGIN SELECT RAISE(ABORT, 'Branch sync subject is immutable'); END;

DROP TRIGGER branch_sync_episode_policy_v264;
CREATE TRIGGER branch_sync_episode_policy_v319
BEFORE INSERT ON branch_sync_episode
WHEN NEW.branch_sync_policy_revision_id IS NULL OR NEW.authority_id IS NULL
  OR NOT EXISTS (
      SELECT 1 FROM task_branch_sync_policy_revision policy
      WHERE policy.id = NEW.branch_sync_policy_revision_id
        AND policy.task_id = NEW.task_id
        AND policy.attempt_limit = NEW.attempt_limit
        AND policy.revision = (
            SELECT MAX(current.revision)
            FROM task_branch_sync_policy_revision current
            WHERE current.task_id = NEW.task_id)
        AND ((NEW.purpose = 'SCHEDULED'
              AND NEW.authority_kind = 'BRANCH_SYNC_POLICY'
              AND NEW.authority_id = policy.id
              AND NEW.policy_source = policy.source
              AND policy.enabled = 1)
          OR (NEW.purpose IN (
                  'CI_PRECONDITION', 'CI_PRECONDITION_LOCAL')
              AND NEW.policy_source = 'CI_PRECONDITION'
              AND ((NEW.authority_kind = 'AUTO_APPROVE_POLICY' AND EXISTS (
                  SELECT 1 FROM task_automation_policy automation
                  WHERE automation.id = NEW.authority_id
                    AND automation.task_id = NEW.task_id
                    AND automation.auto_approve = 1
                    AND automation.stewardship_exception = 0
                    AND automation.revision = (
                        SELECT MAX(current.revision)
                        FROM task_automation_policy current
                        WHERE current.task_id = NEW.task_id)))
                OR (NEW.authority_kind = 'MANUAL' AND EXISTS (
                  SELECT 1
                  FROM ci_branch_sync_manual_authorization_v319 authorization
                  JOIN task_blocker blocker
                    ON blocker.id = authorization.blocker_id
                  JOIN ci_repair_episode ci
                    ON ci.id = authorization.ci_repair_episode_id
                  WHERE authorization.id = NEW.authority_id
                    AND authorization.task_id = NEW.task_id
                    AND authorization.ci_repair_episode_id IS
                        NEW.ci_repair_episode_id
                    AND authorization.stage_id =
                        NEW.remote_development_stage_id
                    AND authorization.predecessor_snapshot_id <>
                        NEW.source_snapshot_id
                    AND authorization.status = 'CLAIMED'
                    AND blocker.task_id = NEW.task_id
                    AND blocker.stage_id = NEW.remote_development_stage_id
                    AND blocker.owner_kind = 'STAGE'
                    AND blocker.owner_id = NEW.remote_development_stage_id
                    AND blocker.subject_revision =
                        authorization.predecessor_snapshot_id
                    AND blocker.blocker_type = 'CI_BRANCH_SYNC_REQUIRED'
                    AND blocker.status = 'OPEN'
                    AND ci.task_id = NEW.task_id
                    AND ci.remote_development_stage_id =
                        NEW.remote_development_stage_id
                    AND ci.task_epoch = NEW.task_epoch
                    AND ci.stage_generation = NEW.stage_generation
                    AND ci.status NOT IN (
                        'SUCCEEDED', 'EXHAUSTED', 'STOPPED')
                    AND EXISTS (
                      SELECT 1 FROM remote_pr_snapshot source
                      WHERE source.id = NEW.source_snapshot_id
                        AND source.observation_revision >
                            authorization.predecessor_observation_revision)))))))
BEGIN SELECT RAISE(ABORT, 'Branch sync lacks exact scheduled or CI authority'); END;

CREATE TRIGGER branch_sync_ci_local_insert_v319
BEFORE INSERT ON branch_sync_episode
WHEN NEW.purpose = 'CI_PRECONDITION_LOCAL' AND NOT EXISTS (
    SELECT 1
    FROM ci_repair_episode ci
    JOIN remote_development_stage remote
      ON remote.stage_id = ci.remote_development_stage_id
    JOIN task_current_code_subject_v230 code ON code.task_id = ci.task_id
    WHERE ci.id = NEW.ci_repair_episode_id
      AND ci.remote_development_stage_id = NEW.remote_development_stage_id
      AND ci.task_id = NEW.task_id
      AND ci.task_epoch = NEW.task_epoch
      AND ci.stage_generation = NEW.stage_generation
      AND ci.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
      AND remote.accepted_snapshot_id = NEW.source_snapshot_id
      AND remote.current_head_sha = NEW.old_head_sha
      AND remote.current_base_sha = NEW.observed_base_sha
      AND NEW.target_base_sha = remote.current_base_sha
      AND NEW.source_code_fingerprint = code.code_fingerprint
      AND NEW.source_code_head_sha = code.head_sha
      AND NEW.source_code_base_sha = code.base_sha
      AND NEW.source_code_base_sha <> NEW.target_base_sha
      AND length(trim(NEW.ci_turn_intent_id)) > 0
      AND ((NEW.ci_turn_intent_kind = 'NO_CHANGE_CONTINUATION' AND EXISTS (
              SELECT 1 FROM ci_repair_fix_continuation_due_v318 due
              WHERE due.id = NEW.ci_turn_intent_id
                AND due.ci_repair_episode_id = ci.id
                AND due.status = 'PENDING'))
        OR (NEW.ci_turn_intent_kind = 'NEXT_FIX' AND EXISTS (
              SELECT 1 FROM ci_repair_next_fix_due_v318 due
              WHERE due.id = NEW.ci_turn_intent_id
                AND due.ci_repair_episode_id = ci.id
                AND due.status = 'PENDING'))
        OR (NEW.ci_turn_intent_kind = 'MANUAL_BASE_REPAIR' AND EXISTS (
              SELECT 1 FROM ci_repair_manual_turn_intent_v319 intent
              WHERE intent.id = NEW.ci_turn_intent_id
                AND intent.ci_repair_episode_id = ci.id
                AND intent.status = 'PENDING'))
        OR (NEW.ci_turn_intent_kind = 'STEERING' AND EXISTS (
              SELECT 1
              FROM ci_repair_steering_fence_v319 fence
              JOIN stage_steering_request_v257 steering
                ON steering.id = fence.request_id
              WHERE fence.request_id = NEW.ci_turn_intent_id
                AND fence.ci_repair_episode_id = ci.id
                AND steering.status = 'PENDING'))))
BEGIN SELECT RAISE(ABORT, 'Local CI precondition lacks its exact pending repair'); END;

CREATE TRIGGER branch_sync_ci_local_first_effect_v319
BEFORE INSERT ON branch_sync_dispatch_operation
WHEN NEW.kind = 'FETCH_COMPARE' AND EXISTS (
    SELECT 1 FROM branch_sync_episode episode
    WHERE episode.id = NEW.branch_sync_episode_id
      AND episode.purpose = 'CI_PRECONDITION_LOCAL')
  AND NOT EXISTS (
    SELECT 1 FROM branch_sync_episode episode
    WHERE episode.id = NEW.branch_sync_episode_id
      AND NEW.expected_code_fingerprint = episode.source_code_fingerprint
      AND NEW.expected_head_sha = episode.source_code_head_sha
      AND NEW.expected_base_sha = episode.source_code_base_sha)
BEGIN SELECT RAISE(ABORT, 'Local CI precondition changed its source subject'); END;

DROP TRIGGER branch_sync_episode_success;
CREATE TRIGGER branch_sync_episode_success
BEFORE UPDATE OF status ON branch_sync_episode
WHEN NEW.status = 'SUCCEEDED'
  AND ((SELECT COUNT(*) FROM branch_sync_effect_step step
        WHERE step.branch_sync_episode_id = NEW.id) <> 6
    OR EXISTS (
        SELECT 1 FROM branch_sync_effect_step step
        WHERE step.branch_sync_episode_id = NEW.id
          AND step.status NOT IN ('SUCCEEDED', 'SKIPPED'))
    OR (NEW.purpose <> 'CI_PRECONDITION_LOCAL' AND (
        NOT EXISTS (
            SELECT 1 FROM branch_sync_push_proof proof
            WHERE proof.branch_sync_episode_id = NEW.id
              AND proof.pushed_head_sha = NEW.result_head_sha)
        OR NOT EXISTS (
            SELECT 1 FROM remote_pr_snapshot snapshot
            JOIN remote_development_stage remote
              ON remote.stage_id = snapshot.remote_development_stage_id
            WHERE snapshot.id = NEW.result_snapshot_id
              AND snapshot.remote_development_stage_id =
                    NEW.remote_development_stage_id
              AND snapshot.task_id = NEW.task_id
              AND snapshot.stage_generation = NEW.stage_generation
              AND snapshot.remote_pr_binding_id = NEW.remote_pr_binding_id
              AND snapshot.head_sha = NEW.result_head_sha
              AND snapshot.base_sha = NEW.target_base_sha
              AND remote.accepted_snapshot_id = snapshot.id
              AND remote.current_head_sha = NEW.result_head_sha
              AND remote.current_base_sha = NEW.target_base_sha)))
    OR (NEW.purpose = 'CI_PRECONDITION_LOCAL' AND (
        NEW.result_snapshot_id IS NOT NEW.source_snapshot_id
        OR NEW.result_code_fingerprint IS NULL
        OR EXISTS (
            SELECT 1 FROM branch_sync_push_proof proof
            WHERE proof.branch_sync_episode_id = NEW.id)
        OR NOT EXISTS (
            SELECT 1
            FROM task_current_code_subject_v230 code
            JOIN remote_development_stage remote
              ON remote.stage_id = NEW.remote_development_stage_id
            WHERE code.task_id = NEW.task_id
              AND code.code_fingerprint = NEW.result_code_fingerprint
              AND code.head_sha = NEW.result_head_sha
              AND code.base_sha = NEW.target_base_sha
              AND remote.accepted_snapshot_id = NEW.source_snapshot_id
              AND remote.current_head_sha = NEW.old_head_sha
              AND remote.current_base_sha = NEW.target_base_sha)
        OR NOT EXISTS (
            SELECT 1
            FROM remote_worktree_subject subject
            JOIN branch_sync_dispatch_operation operation
              ON operation.operation_id = subject.source_operation_id
            JOIN branch_sync_effect_step step
              ON step.id = operation.branch_sync_effect_step_id
            WHERE operation.branch_sync_episode_id = NEW.id
              AND operation.status = 'SUCCEEDED'
              AND step.status = 'SUCCEEDED'
              AND step.ordinal IN (2, 3)
              AND subject.source_kind = CASE step.ordinal
                    WHEN 2 THEN 'BRANCH_EFFECT'
                    WHEN 3 THEN 'BRANCH_STAGE_TURN' END
              AND subject.task_id = NEW.task_id
              AND subject.task_epoch = NEW.task_epoch
              AND subject.remote_development_stage_id =
                    NEW.remote_development_stage_id
              AND subject.stage_generation = NEW.stage_generation
              AND subject.code_fingerprint = NEW.result_code_fingerprint
              AND subject.head_sha = NEW.result_head_sha
              AND subject.base_sha = NEW.target_base_sha)
        OR EXISTS (
            SELECT 1 FROM branch_sync_effect_step step
            WHERE step.branch_sync_episode_id = NEW.id
              AND ((step.ordinal IN (1, 2)
                      AND step.status <> 'SUCCEEDED')
                OR (step.ordinal = 3
                      AND step.status NOT IN ('SUCCEEDED', 'SKIPPED'))
                OR (step.ordinal IN (4, 5, 6)
                      AND step.status <> 'SKIPPED'))))))
BEGIN SELECT RAISE(ABORT, 'Branch sync success lacks complete ordered effect proof'); END;

CREATE TABLE remote_ci_base_freshness_fk_assert_v319 (
    value INTEGER NOT NULL CHECK (value = 1)
);
INSERT INTO remote_ci_base_freshness_fk_assert_v319(value)
VALUES ((SELECT CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END
         FROM pragma_foreign_key_check));
DROP TABLE remote_ci_base_freshness_fk_assert_v319;

RELEASE remote_ci_base_freshness_v319;
PRAGMA foreign_keys = ON;
