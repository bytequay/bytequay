-- Durable, inert V2 Local Development and first-publish protocols. These
-- records do not route production work; they only establish exact owners,
-- immutable proofs, and restart-safe effect identity for later managers.

-- A CapacityLease proves who may write, but not which directory it may write.
-- Provisioning is bound to its own requested path and operation; after that,
-- the immutable TaskCodeIdentity is the sole path authority.
CREATE TRIGGER worktree_lease_v2_path_insert
BEFORE INSERT ON worktree_leases
WHEN NEW.workflow_version = 'V2'
  AND NOT (
      EXISTS (
          SELECT 1 FROM provision_task_operation operation
          WHERE operation.task_id = NEW.task_id
            AND operation.task_epoch = NEW.task_epoch
            AND operation.operation_id = NEW.operation_id
            AND operation.requested_worktree_path = NEW.worktree_path
            AND operation.status = 'DISPATCHED')
      OR EXISTS (
          SELECT 1 FROM task_code_identity code
          WHERE code.task_id = NEW.task_id
            AND code.worktree_path = NEW.worktree_path))
BEGIN SELECT RAISE(ABORT, 'V2 WorktreeLease path lacks exact Task authority'); END;

-- ── Local Development owner and immutable development handoff ─────────────
CREATE TABLE local_development_stage (
    stage_id          TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage(id) ON DELETE CASCADE,
    task_id           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    generation        INTEGER NOT NULL CHECK (generation > 0),
    opened_for_epoch  INTEGER NOT NULL CHECK (opened_for_epoch > 0),
    UNIQUE (stage_id, task_id, generation)
);

CREATE TRIGGER local_development_stage_owner_insert
BEFORE INSERT ON local_development_stage
WHEN NOT EXISTS (
    SELECT 1
    FROM stage s
    JOIN tasks t ON t.id = s.task_id
    JOIN task_current_stage c ON c.stage_id = s.id
    WHERE s.id = NEW.stage_id
      AND s.task_id = NEW.task_id
      AND s.kind = 'LOCAL_DEVELOPMENT'
      AND s.generation = NEW.generation
      AND s.completed_at_ms IS NULL
      AND t.workflow_version = 'V2'
      AND t.lifecycle_state = 'ACTIVE'
      AND t.epoch = NEW.opened_for_epoch
      AND c.task_id = NEW.task_id
      AND c.stage_generation = NEW.generation)
BEGIN
    SELECT RAISE(ABORT, 'Local Development subtype must match its exact open V2 Stage');
END;

CREATE TRIGGER local_development_stage_immutable
BEFORE UPDATE ON local_development_stage
BEGIN SELECT RAISE(ABORT, 'Local Development Stage identity is immutable'); END;

-- V148 already installed the LEGACY DevReport API. Rebuild the table so its
-- one-row-per-Task legacy contract remains available while V2 may append one
-- immutable report per Stage subject. No production store is switched here.
ALTER TABLE dev_report RENAME TO dev_report_v148;

CREATE TABLE dev_report (
    id                         TEXT    NOT NULL PRIMARY KEY,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    summary                    TEXT    NOT NULL,
    decisions_json             TEXT,
    invariants_json            TEXT,
    tricky_spots_json          TEXT,
    test_map_json              TEXT,
    followups_json             TEXT,
    created_at_ms              INTEGER NOT NULL,
    workflow_version           TEXT    NOT NULL DEFAULT 'LEGACY'
        CHECK (workflow_version IN ('LEGACY', 'V2')),
    local_development_stage_id TEXT
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_epoch                 INTEGER CHECK (task_epoch > 0),
    stage_generation           INTEGER CHECK (stage_generation > 0),
    stage_turn_id              TEXT UNIQUE REFERENCES stage_turn(id),
    revision                   INTEGER CHECK (revision > 0),
    code_fingerprint           TEXT,
    head_sha                   TEXT,
    base_sha                   TEXT,
    implemented_intent         TEXT,
    commit_summary             TEXT,
    file_summary               TEXT,
    validation_summary         TEXT,
    known_risks                TEXT,
    unresolved_concerns        TEXT,
    context_refs               TEXT
);

INSERT INTO dev_report(
    id, task_id, summary, decisions_json, invariants_json, tricky_spots_json,
    test_map_json, followups_json, created_at_ms, workflow_version)
SELECT id, task_id, summary, decisions_json, invariants_json, tricky_spots_json,
    test_map_json, followups_json, created_at_ms, 'LEGACY'
FROM dev_report_v148;

DROP TABLE dev_report_v148;

CREATE UNIQUE INDEX idx_dev_report_legacy_task
    ON dev_report(task_id) WHERE workflow_version = 'LEGACY';
CREATE UNIQUE INDEX idx_dev_report_v2_revision
    ON dev_report(local_development_stage_id, revision)
    WHERE workflow_version = 'V2';
CREATE UNIQUE INDEX idx_dev_report_v2_subject
    ON dev_report(local_development_stage_id, code_fingerprint, head_sha, base_sha)
    WHERE workflow_version = 'V2';

CREATE TRIGGER dev_report_owner_insert
BEFORE INSERT ON dev_report
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks t
            WHERE t.id = NEW.task_id
              AND t.workflow_version = NEW.workflow_version)
            THEN RAISE(ABORT, 'DevReport workflow version must match its Task')
        WHEN NEW.workflow_version = 'LEGACY' AND (
            NEW.local_development_stage_id IS NOT NULL
            OR NEW.task_epoch IS NOT NULL OR NEW.stage_generation IS NOT NULL
            OR NEW.stage_turn_id IS NOT NULL OR NEW.revision IS NOT NULL
            OR NEW.code_fingerprint IS NOT NULL OR NEW.head_sha IS NOT NULL
            OR NEW.base_sha IS NOT NULL OR NEW.implemented_intent IS NOT NULL
            OR NEW.commit_summary IS NOT NULL OR NEW.file_summary IS NOT NULL
            OR NEW.validation_summary IS NOT NULL OR NEW.known_risks IS NOT NULL
            OR NEW.unresolved_concerns IS NOT NULL OR NEW.context_refs IS NOT NULL)
            THEN RAISE(ABORT, 'LEGACY DevReport cannot carry a V2 fence')
        WHEN NEW.workflow_version = 'V2' AND (
            NEW.local_development_stage_id IS NULL OR NEW.task_epoch IS NULL
            OR NEW.stage_generation IS NULL OR NEW.stage_turn_id IS NULL
            OR NEW.revision IS NULL OR NEW.code_fingerprint IS NULL
            OR NEW.head_sha IS NULL OR NEW.base_sha IS NULL
            OR NEW.implemented_intent IS NULL OR NEW.commit_summary IS NULL
            OR NEW.file_summary IS NULL OR NEW.validation_summary IS NULL
            OR NEW.known_risks IS NULL OR NEW.unresolved_concerns IS NULL
            OR NEW.context_refs IS NULL)
            THEN RAISE(ABORT, 'V2 DevReport requires its complete typed fence')
        WHEN NEW.workflow_version = 'V2' AND (length(NEW.code_fingerprint) = 0
          OR length(NEW.head_sha) = 0 OR length(NEW.base_sha) = 0
          OR length(NEW.implemented_intent) = 0)
            THEN RAISE(ABORT, 'V2 DevReport subject must not be blank')
        WHEN NEW.workflow_version = 'V2' AND NEW.revision <> COALESCE((
            SELECT MAX(r.revision) + 1 FROM dev_report r
            WHERE r.workflow_version = 'V2'
              AND r.local_development_stage_id = NEW.local_development_stage_id), 1)
            THEN RAISE(ABORT, 'DevReport revision must be the next exact revision')
        WHEN NEW.workflow_version = 'V2' AND NOT EXISTS (
            SELECT 1
            FROM local_development_stage l
            JOIN stage s ON s.id = l.stage_id
            JOIN tasks t ON t.id = l.task_id
            JOIN task_current_stage c ON c.stage_id = l.stage_id
            JOIN stage_turn st ON st.id = NEW.stage_turn_id
            WHERE l.stage_id = NEW.local_development_stage_id
              AND l.task_id = NEW.task_id
              AND l.generation = NEW.stage_generation
              AND l.opened_for_epoch = NEW.task_epoch
              AND s.completed_at_ms IS NULL
              AND t.lifecycle_state = 'ACTIVE'
              AND t.epoch = NEW.task_epoch
              AND c.task_id = NEW.task_id
              AND c.stage_generation = NEW.stage_generation
              AND st.stage_id = l.stage_id
              AND st.stage_generation = l.generation
              AND st.task_epoch = NEW.task_epoch
              AND st.expected_code_fingerprint = NEW.code_fingerprint
              AND st.expected_head_sha = NEW.head_sha
              AND st.expected_base_sha = NEW.base_sha
              AND st.status = 'SUCCEEDED')
            THEN RAISE(ABORT, 'DevReport requires its exact successful StageTurn')
    END;
END;

CREATE TRIGGER dev_report_immutable
BEFORE UPDATE ON dev_report
WHEN OLD.workflow_version = 'V2'
BEGIN SELECT RAISE(ABORT, 'V2 DevReport is immutable'); END;

CREATE TRIGGER dev_report_route_immutable
BEFORE UPDATE OF workflow_version, task_id, local_development_stage_id,
        task_epoch, stage_generation, stage_turn_id, revision,
        code_fingerprint, head_sha, base_sha ON dev_report
WHEN NEW.workflow_version IS NOT OLD.workflow_version
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.local_development_stage_id IS NOT OLD.local_development_stage_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.stage_turn_id IS NOT OLD.stage_turn_id
  OR NEW.revision IS NOT OLD.revision
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.base_sha IS NOT OLD.base_sha
BEGIN SELECT RAISE(ABORT, 'DevReport route and subject are immutable'); END;

-- ── Canonical validation request and accepted immutable evidence ────────
CREATE TABLE validation_operation (
    id                         TEXT    NOT NULL PRIMARY KEY,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    dev_report_id              TEXT    NOT NULL REFERENCES dev_report(id),
    operation_id               TEXT    NOT NULL UNIQUE,
    semantic_attempt           INTEGER NOT NULL CHECK (semantic_attempt > 0),
    code_fingerprint           TEXT    NOT NULL,
    expected_head_sha          TEXT    NOT NULL,
    expected_base_sha          TEXT    NOT NULL,
    status                     TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'COMPLETED', 'FAILED',
        'CANCELED', 'SUPERSEDED')),
    requested_at_ms            INTEGER NOT NULL,
    completed_at_ms            INTEGER,
    error_message              TEXT,
    UNIQUE (local_development_stage_id, code_fingerprint, semantic_attempt),
    CHECK ((status IN ('COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL))
);

CREATE TRIGGER validation_operation_owner_insert
BEFORE INSERT ON validation_operation
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'REQUESTED'
            THEN RAISE(ABORT, 'ValidationOperation must start REQUESTED')
        WHEN NOT EXISTS (
            SELECT 1
            FROM local_development_stage l
            JOIN stage s ON s.id = l.stage_id
            JOIN tasks t ON t.id = l.task_id
            JOIN task_current_stage c ON c.stage_id = l.stage_id
            JOIN dev_report r ON r.id = NEW.dev_report_id
            WHERE l.stage_id = NEW.local_development_stage_id
              AND l.task_id = NEW.task_id
              AND l.generation = NEW.stage_generation
              AND l.opened_for_epoch = NEW.task_epoch
              AND t.epoch = NEW.task_epoch
              AND t.lifecycle_state = 'ACTIVE'
              AND s.completed_at_ms IS NULL
              AND c.task_id = NEW.task_id
              AND c.stage_generation = NEW.stage_generation
              AND r.local_development_stage_id = l.stage_id
              AND r.task_id = NEW.task_id
              AND r.task_epoch = NEW.task_epoch
              AND r.stage_generation = NEW.stage_generation
              AND r.code_fingerprint = NEW.code_fingerprint
              AND r.head_sha = NEW.expected_head_sha
              AND r.base_sha = NEW.expected_base_sha)
            THEN RAISE(ABORT, 'ValidationOperation does not match its DevReport subject')
    END;
END;

CREATE TRIGGER validation_operation_dispatch_fence
BEFORE UPDATE OF status ON validation_operation
WHEN NEW.status = 'DISPATCHED'
  AND NOT EXISTS (
      SELECT 1 FROM dispatch_ticket d
      WHERE d.operation_id = NEW.operation_id
        AND d.operation_kind = 'VALIDATE_LOCAL_DEVELOPMENT'
        AND d.async_family = 'VALIDATION'
        AND d.owner_kind = 'STAGE'
        AND d.owner_id = NEW.local_development_stage_id
        AND d.task_id = NEW.task_id
        AND d.task_epoch = NEW.task_epoch
        AND d.stage_id = NEW.local_development_stage_id
        AND d.stage_generation = NEW.stage_generation
        AND d.attempt = NEW.semantic_attempt
        AND d.expected_code_fingerprint = NEW.code_fingerprint
        AND d.expected_head_sha = NEW.expected_head_sha
        AND d.expected_base_sha = NEW.expected_base_sha
        AND d.exclusive_task = 1
        AND (d.lane_mask & 4) = 4
        AND d.status = 'REQUESTED')
BEGIN
    SELECT RAISE(ABORT, 'dispatched validation requires its exact DispatchTicket');
END;

CREATE TRIGGER validation_operation_identity_immutable
BEFORE UPDATE OF local_development_stage_id, task_id, task_epoch,
        stage_generation, dev_report_id, operation_id, semantic_attempt,
        code_fingerprint, expected_head_sha, expected_base_sha, requested_at_ms
ON validation_operation
WHEN NEW.local_development_stage_id IS NOT OLD.local_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.dev_report_id IS NOT OLD.dev_report_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'ValidationOperation fence is immutable'); END;

CREATE TRIGGER validation_operation_transition
BEFORE UPDATE OF status ON validation_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'illegal ValidationOperation transition'); END;

CREATE TRIGGER validation_operation_terminal_immutable
BEFORE UPDATE ON validation_operation
WHEN OLD.status IN ('COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'terminal ValidationOperation is immutable'); END;

CREATE TABLE validation_evidence (
    id                       TEXT    NOT NULL PRIMARY KEY,
    validation_operation_id TEXT    NOT NULL UNIQUE
        REFERENCES validation_operation(id) ON DELETE CASCADE,
    validation_pass_id       INTEGER NOT NULL UNIQUE REFERENCES validation_pass(id),
    task_id                  TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch               INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_id                 TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation         INTEGER NOT NULL CHECK (stage_generation > 0),
    code_fingerprint         TEXT    NOT NULL,
    head_sha                 TEXT    NOT NULL,
    base_sha                 TEXT    NOT NULL,
    passed                   INTEGER NOT NULL CHECK (passed IN (0, 1)),
    failures_digest          TEXT,
    evidence                 TEXT    NOT NULL,
    completed_at_ms          INTEGER NOT NULL,
    CHECK ((passed = 1 AND failures_digest IS NULL)
        OR (passed = 0 AND length(failures_digest) > 0))
);

CREATE TRIGGER validation_evidence_owner_insert
BEFORE INSERT ON validation_evidence
WHEN NOT EXISTS (
    SELECT 1
    FROM validation_operation o
    JOIN validation_pass p ON p.id = NEW.validation_pass_id
    WHERE o.id = NEW.validation_operation_id
      AND o.status = 'DISPATCHED'
      AND o.task_id = NEW.task_id
      AND o.task_epoch = NEW.task_epoch
      AND o.local_development_stage_id = NEW.stage_id
      AND o.stage_generation = NEW.stage_generation
      AND o.code_fingerprint = NEW.code_fingerprint
      AND o.expected_head_sha = NEW.head_sha
      AND o.expected_base_sha = NEW.base_sha
      AND p.task_id = NEW.task_id
      AND p.workflow_version = 'V2'
      AND p.task_epoch = NEW.task_epoch
      AND p.stage_id = NEW.stage_id
      AND p.stage_generation = NEW.stage_generation
      AND p.operation_id = o.operation_id
      AND p.semantic_attempt = o.semantic_attempt
      AND p.code_fingerprint = NEW.code_fingerprint
      AND p.expected_head_sha = NEW.head_sha
      AND p.expected_base_sha = NEW.base_sha
      AND p.ended_at_ms IS NOT NULL
      AND p.passed = NEW.passed)
BEGIN
    SELECT RAISE(ABORT, 'ValidationEvidence does not match its exact completed pass');
END;

CREATE TRIGGER validation_evidence_immutable
BEFORE UPDATE ON validation_evidence
BEGIN SELECT RAISE(ABORT, 'ValidationEvidence is immutable'); END;

CREATE TRIGGER validation_operation_completion_proof
BEFORE UPDATE OF status ON validation_operation
WHEN NEW.status = 'COMPLETED'
  AND NOT EXISTS (
      SELECT 1 FROM validation_evidence e
      WHERE e.validation_operation_id = NEW.id
        AND e.task_id = NEW.task_id
        AND e.task_epoch = NEW.task_epoch
        AND e.stage_id = NEW.local_development_stage_id
        AND e.stage_generation = NEW.stage_generation
        AND e.code_fingerprint = NEW.code_fingerprint
        AND e.head_sha = NEW.expected_head_sha
        AND e.base_sha = NEW.expected_base_sha)
BEGIN SELECT RAISE(ABORT, 'completed validation requires exact immutable evidence'); END;

-- ── Task-owned Brain review episode ─────────────────────────────────────
CREATE TABLE brain_review_episode (
    id                         TEXT    NOT NULL PRIMARY KEY,
    task_brain_id              TEXT    NOT NULL REFERENCES task_brain(id),
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    dev_report_id              TEXT    NOT NULL REFERENCES dev_report(id),
    validation_evidence_id     TEXT    NOT NULL REFERENCES validation_evidence(id),
    task_turn_id               TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    semantic_attempt           INTEGER NOT NULL CHECK (semantic_attempt > 0),
    code_fingerprint           TEXT    NOT NULL,
    expected_head_sha          TEXT    NOT NULL,
    expected_base_sha          TEXT    NOT NULL,
    status                     TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'REVIEWING', 'SUCCEEDED', 'FAILED', 'CANCELED',
        'SUPERSEDED', 'BUDGET_EXHAUSTED')),
    verdict                    TEXT CHECK (verdict IN (
        'APPROVED', 'CHANGES_REQUESTED', 'BLOCKED')),
    unresolved_finding_count   INTEGER NOT NULL DEFAULT 0
        CHECK (unresolved_finding_count >= 0),
    verdict_summary            TEXT,
    requested_at_ms            INTEGER NOT NULL,
    completed_at_ms            INTEGER,
    error_message              TEXT,
    UNIQUE (local_development_stage_id, dev_report_id, semantic_attempt),
    CHECK ((status = 'SUCCEEDED') = (verdict IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED',
            'SUPERSEDED', 'BUDGET_EXHAUSTED')) = (completed_at_ms IS NOT NULL)),
    CHECK (verdict <> 'APPROVED' OR unresolved_finding_count = 0),
    CHECK (status <> 'BUDGET_EXHAUSTED' OR verdict IS NULL)
);

CREATE UNIQUE INDEX idx_brain_review_episode_one_active
    ON brain_review_episode(task_id)
    WHERE status IN ('REQUESTED', 'REVIEWING');

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
              AND tt.purpose = 'DEVELOPMENT_BRAIN_REVIEW'
              AND tt.status IN ('REQUESTED', 'QUEUED'))
            THEN RAISE(ABORT, 'BrainReviewEpisode requires exact green validation and TaskTurn')
    END;
END;

CREATE TRIGGER brain_review_episode_identity_immutable
BEFORE UPDATE OF task_brain_id, task_id, task_epoch,
        local_development_stage_id, stage_generation, dev_report_id,
        validation_evidence_id, task_turn_id, semantic_attempt,
        code_fingerprint, expected_head_sha, expected_base_sha, requested_at_ms
ON brain_review_episode
WHEN NEW.task_brain_id IS NOT OLD.task_brain_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.local_development_stage_id IS NOT OLD.local_development_stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.dev_report_id IS NOT OLD.dev_report_id
  OR NEW.validation_evidence_id IS NOT OLD.validation_evidence_id
  OR NEW.task_turn_id IS NOT OLD.task_turn_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'BrainReviewEpisode fence is immutable'); END;

CREATE TRIGGER brain_review_episode_transition
BEFORE UPDATE OF status ON brain_review_episode
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'REVIEWING', 'SUCCEEDED', 'FAILED', 'CANCELED',
        'SUPERSEDED', 'BUDGET_EXHAUSTED'))
    OR (OLD.status = 'REVIEWING' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED',
        'SUPERSEDED', 'BUDGET_EXHAUSTED')))
BEGIN SELECT RAISE(ABORT, 'illegal BrainReviewEpisode transition'); END;

CREATE TRIGGER brain_review_episode_result_fence
BEFORE UPDATE OF status ON brain_review_episode
WHEN NEW.status = 'SUCCEEDED'
  AND NOT EXISTS (
      SELECT 1
      FROM task_turn tt
      JOIN tasks t ON t.id = NEW.task_id
      JOIN stage s ON s.id = NEW.local_development_stage_id
      JOIN task_current_stage c ON c.stage_id = s.id
      WHERE tt.id = NEW.task_turn_id
        AND tt.task_id = NEW.task_id
        AND tt.task_epoch = NEW.task_epoch
        AND tt.status = 'SUCCEEDED'
        AND tt.expected_code_fingerprint = NEW.code_fingerprint
        AND tt.expected_head_sha = NEW.expected_head_sha
        AND tt.expected_base_sha = NEW.expected_base_sha
        AND t.epoch = NEW.task_epoch
        AND t.lifecycle_state = 'ACTIVE'
        AND s.task_id = NEW.task_id
        AND s.generation = NEW.stage_generation
        AND s.completed_at_ms IS NULL
        AND c.task_id = NEW.task_id
        AND c.stage_generation = NEW.stage_generation
        AND NOT EXISTS (
            SELECT 1 FROM dev_report newer
            JOIN dev_report current ON current.id = NEW.dev_report_id
            WHERE newer.local_development_stage_id = current.local_development_stage_id
              AND newer.revision > current.revision))
BEGIN SELECT RAISE(ABORT, 'BrainReviewEpisode result is stale'); END;

CREATE TRIGGER brain_review_episode_terminal_immutable
BEFORE UPDATE ON brain_review_episode
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELED',
    'SUPERSEDED', 'BUDGET_EXHAUSTED')
BEGIN SELECT RAISE(ABORT, 'terminal BrainReviewEpisode is immutable'); END;

-- ── Private, revisioned local review and exact submitted batches ────────
CREATE TABLE local_review_thread (
    id                         TEXT    NOT NULL PRIMARY KEY,
    pr_id                      TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    scope                      TEXT    NOT NULL CHECK (scope IN ('PR', 'FILE_LINE')),
    file_path                  TEXT,
    start_line                 INTEGER,
    end_line                   INTEGER,
    source                     TEXT    NOT NULL CHECK (source IN (
        'USER', 'BRAIN', 'ADVISORY_REVIEW', 'BLOCKING_REVIEW',
        'DEVELOPMENT', 'IMPORTED')),
    created_by                 TEXT    NOT NULL,
    created_at_ms              INTEGER NOT NULL,
    CHECK ((scope = 'PR' AND file_path IS NULL
            AND start_line IS NULL AND end_line IS NULL)
        OR (scope = 'FILE_LINE' AND length(file_path) > 0
            AND start_line > 0 AND end_line >= start_line))
);

CREATE TRIGGER local_review_thread_owner_insert
BEFORE INSERT ON local_review_thread
WHEN NOT EXISTS (
    SELECT 1
    FROM pr p
    JOIN tasks t ON t.id = p.task_id
    JOIN local_development_stage l ON l.task_id = t.id
    JOIN stage s ON s.id = l.stage_id
    WHERE p.id = NEW.pr_id
      AND p.origin = 'task'
      AND p.task_id = NEW.task_id
      AND t.workflow_version = 'V2'
      AND t.lifecycle_state = 'ACTIVE'
      AND t.epoch = NEW.task_epoch
      AND l.stage_id = NEW.local_development_stage_id
      AND l.generation = NEW.stage_generation
      AND l.opened_for_epoch = NEW.task_epoch
      AND s.completed_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT, 'LocalReviewThread requires its exact private local PR subject'); END;

CREATE TRIGGER local_review_thread_immutable
BEFORE UPDATE ON local_review_thread
BEGIN SELECT RAISE(ABORT, 'LocalReviewThread is immutable'); END;

CREATE TABLE local_review_comment_revision (
    id                         TEXT    NOT NULL PRIMARY KEY,
    thread_id                  TEXT    NOT NULL
        REFERENCES local_review_thread(id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    dev_report_id              TEXT    NOT NULL REFERENCES dev_report(id),
    revision                   INTEGER NOT NULL CHECK (revision > 0),
    previous_revision_id       TEXT REFERENCES local_review_comment_revision(id),
    author_kind                TEXT    NOT NULL CHECK (author_kind IN (
        'USER', 'BRAIN', 'ADVISORY_REVIEW', 'BLOCKING_REVIEW',
        'DEVELOPMENT', 'IMPORTED')),
    body                       TEXT    NOT NULL,
    body_digest                TEXT    NOT NULL,
    code_fingerprint           TEXT    NOT NULL,
    head_sha                   TEXT    NOT NULL,
    base_sha                   TEXT    NOT NULL,
    state                      TEXT    NOT NULL CHECK (state IN (
        'DRAFT', 'PENDING', 'SUBMITTED', 'ADDRESSED',
        'DISMISSED', 'SUPERSEDED')),
    state_version              INTEGER NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    created_at_ms              INTEGER NOT NULL,
    state_changed_at_ms        INTEGER NOT NULL,
    resolution_reason          TEXT,
    terminal_at_ms             INTEGER,
    UNIQUE (thread_id, revision),
    UNIQUE (thread_id, body_digest, revision),
    CHECK (length(body) > 0 AND length(body_digest) > 0),
    CHECK ((state IN ('ADDRESSED', 'DISMISSED', 'SUPERSEDED'))
        = (terminal_at_ms IS NOT NULL)),
    CHECK (state NOT IN ('DISMISSED', 'SUPERSEDED')
        OR length(resolution_reason) > 0)
);

CREATE TRIGGER local_review_comment_revision_insert
BEFORE INSERT ON local_review_comment_revision
BEGIN
    SELECT CASE
        WHEN NEW.revision <> COALESCE((
            SELECT MAX(r.revision) + 1
            FROM local_review_comment_revision r
            WHERE r.thread_id = NEW.thread_id), 1)
            THEN RAISE(ABORT, 'CommentRevision must be the next exact thread revision')
        WHEN (NEW.revision = 1) <> (NEW.previous_revision_id IS NULL)
            THEN RAISE(ABORT, 'CommentRevision predecessor shape is invalid')
        WHEN NEW.previous_revision_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM local_review_comment_revision previous
            WHERE previous.id = NEW.previous_revision_id
              AND previous.thread_id = NEW.thread_id
              AND previous.revision = NEW.revision - 1)
            THEN RAISE(ABORT, 'CommentRevision predecessor is not exact')
        WHEN NOT EXISTS (
            SELECT 1
            FROM local_review_thread thread
            JOIN dev_report report ON report.id = NEW.dev_report_id
            WHERE thread.id = NEW.thread_id
              AND thread.task_id = NEW.task_id
              AND thread.local_development_stage_id = NEW.local_development_stage_id
              AND thread.task_epoch = NEW.task_epoch
              AND thread.stage_generation = NEW.stage_generation
              AND report.workflow_version = 'V2'
              AND report.task_id = NEW.task_id
              AND report.local_development_stage_id = NEW.local_development_stage_id
              AND report.task_epoch = NEW.task_epoch
              AND report.stage_generation = NEW.stage_generation
              AND report.code_fingerprint = NEW.code_fingerprint
              AND report.head_sha = NEW.head_sha
              AND report.base_sha = NEW.base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = report.local_development_stage_id
                    AND newer.revision > report.revision))
            THEN RAISE(ABORT, 'CommentRevision does not match the latest DevReport subject')
    END;
END;

CREATE TRIGGER local_review_comment_content_immutable
BEFORE UPDATE OF thread_id, task_id, local_development_stage_id, task_epoch,
        stage_generation, dev_report_id, revision, previous_revision_id,
        author_kind, body, body_digest, code_fingerprint, head_sha, base_sha,
        created_at_ms ON local_review_comment_revision
WHEN NEW.thread_id IS NOT OLD.thread_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.local_development_stage_id IS NOT OLD.local_development_stage_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.dev_report_id IS NOT OLD.dev_report_id
  OR NEW.revision IS NOT OLD.revision
  OR NEW.previous_revision_id IS NOT OLD.previous_revision_id
  OR NEW.author_kind IS NOT OLD.author_kind
  OR NEW.body IS NOT OLD.body OR NEW.body_digest IS NOT OLD.body_digest
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.head_sha IS NOT OLD.head_sha OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'CommentRevision content and subject are immutable'); END;

CREATE TRIGGER local_review_comment_state_transition
BEFORE UPDATE OF state ON local_review_comment_revision
WHEN NEW.state_version <> OLD.state_version + 1
  OR NEW.state_changed_at_ms < OLD.state_changed_at_ms
  OR NOT (
      (OLD.state = 'DRAFT' AND NEW.state IN (
          'PENDING', 'SUBMITTED', 'DISMISSED', 'SUPERSEDED'))
      OR (OLD.state = 'PENDING' AND NEW.state IN (
          'SUBMITTED', 'DISMISSED', 'SUPERSEDED'))
      OR (OLD.state = 'SUBMITTED' AND NEW.state IN ('ADDRESSED', 'DISMISSED')))
BEGIN SELECT RAISE(ABORT, 'illegal CommentRevision state transition'); END;

CREATE TRIGGER local_review_comment_state_fields
BEFORE UPDATE OF state_version, state_changed_at_ms,
        resolution_reason, terminal_at_ms ON local_review_comment_revision
WHEN NEW.state IS OLD.state
BEGIN SELECT RAISE(ABORT, 'CommentRevision state fields require a state transition'); END;

CREATE TRIGGER local_review_comment_terminal_immutable
BEFORE UPDATE ON local_review_comment_revision
WHEN OLD.state IN ('ADDRESSED', 'DISMISSED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'terminal CommentRevision is immutable'); END;

CREATE TABLE local_feedback_batch (
    id                         TEXT    NOT NULL PRIMARY KEY,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    pr_id                      TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    dev_report_id              TEXT    NOT NULL REFERENCES dev_report(id),
    source_submission_id       TEXT UNIQUE REFERENCES local_review_submission(id),
    sequence                   INTEGER NOT NULL CHECK (sequence > 0),
    code_fingerprint           TEXT    NOT NULL,
    head_sha                   TEXT    NOT NULL,
    base_sha                   TEXT    NOT NULL,
    status                     TEXT    NOT NULL CHECK (status IN (
        'BUILDING', 'FROZEN', 'QUEUED', 'DISPATCHED', 'ADDRESSED',
        'FAILED', 'CANCELED', 'SUPERSEDED')),
    stage_turn_id              TEXT UNIQUE REFERENCES stage_turn(id),
    created_at_ms              INTEGER NOT NULL,
    frozen_at_ms               INTEGER,
    completed_at_ms            INTEGER,
    error_message              TEXT,
    UNIQUE (local_development_stage_id, sequence),
    CHECK ((status = 'BUILDING') = (frozen_at_ms IS NULL)),
    CHECK ((status IN ('ADDRESSED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL))
);

CREATE UNIQUE INDEX idx_local_feedback_batch_one_dispatched
    ON local_feedback_batch(local_development_stage_id)
    WHERE status = 'DISPATCHED';

CREATE TRIGGER local_feedback_batch_insert
BEFORE INSERT ON local_feedback_batch
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'BUILDING'
            THEN RAISE(ABORT, 'LocalFeedbackBatch must start BUILDING')
        WHEN NEW.sequence <> COALESCE((
            SELECT MAX(b.sequence) + 1 FROM local_feedback_batch b
            WHERE b.local_development_stage_id = NEW.local_development_stage_id), 1)
            THEN RAISE(ABORT, 'LocalFeedbackBatch sequence must be exact')
        WHEN NOT EXISTS (
            SELECT 1
            FROM local_development_stage l
            JOIN stage s ON s.id = l.stage_id
            JOIN tasks t ON t.id = l.task_id
            JOIN task_current_stage c ON c.stage_id = l.stage_id
            JOIN pr p ON p.id = NEW.pr_id
            JOIN dev_report r ON r.id = NEW.dev_report_id
            WHERE l.stage_id = NEW.local_development_stage_id
              AND l.task_id = NEW.task_id
              AND l.opened_for_epoch = NEW.task_epoch
              AND l.generation = NEW.stage_generation
              AND s.completed_at_ms IS NULL
              AND t.lifecycle_state = 'ACTIVE' AND t.epoch = NEW.task_epoch
              AND c.task_id = NEW.task_id
              AND c.stage_generation = NEW.stage_generation
              AND p.task_id = NEW.task_id AND p.origin = 'task'
              AND r.workflow_version = 'V2'
              AND r.local_development_stage_id = l.stage_id
              AND r.task_id = NEW.task_id AND r.task_epoch = NEW.task_epoch
              AND r.stage_generation = NEW.stage_generation
              AND r.code_fingerprint = NEW.code_fingerprint
              AND r.head_sha = NEW.head_sha AND r.base_sha = NEW.base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = r.local_development_stage_id
                    AND newer.revision > r.revision))
            THEN RAISE(ABORT, 'LocalFeedbackBatch does not match the current local subject')
        WHEN NEW.source_submission_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM local_review_submission legacy
            WHERE legacy.id = NEW.source_submission_id
              AND legacy.task_id = NEW.task_id
              AND legacy.pr_id = NEW.pr_id)
            THEN RAISE(ABORT, 'LocalFeedbackBatch legacy source does not match')
    END;
END;

CREATE TRIGGER local_feedback_batch_identity_immutable
BEFORE UPDATE OF local_development_stage_id, task_id, task_epoch,
        stage_generation, pr_id, dev_report_id, source_submission_id, sequence,
        code_fingerprint, head_sha, base_sha, created_at_ms ON local_feedback_batch
WHEN NEW.local_development_stage_id IS NOT OLD.local_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.pr_id IS NOT OLD.pr_id OR NEW.dev_report_id IS NOT OLD.dev_report_id
  OR NEW.source_submission_id IS NOT OLD.source_submission_id
  OR NEW.sequence IS NOT OLD.sequence
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.head_sha IS NOT OLD.head_sha OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'LocalFeedbackBatch subject is immutable'); END;

CREATE TRIGGER local_feedback_batch_turn_bind
BEFORE UPDATE OF stage_turn_id ON local_feedback_batch
WHEN OLD.stage_turn_id IS NOT NULL
  OR NEW.stage_turn_id IS NULL
  OR OLD.status NOT IN ('FROZEN', 'QUEUED')
  OR NOT EXISTS (
      SELECT 1 FROM stage_turn st
      WHERE st.id = NEW.stage_turn_id
        AND st.stage_id = NEW.local_development_stage_id
        AND st.stage_generation = NEW.stage_generation
        AND st.task_epoch = NEW.task_epoch
        AND st.purpose = 'ADDRESS_LOCAL_FEEDBACK'
        AND st.status IN ('REQUESTED', 'QUEUED')
        AND st.expected_code_fingerprint = NEW.code_fingerprint
        AND st.expected_head_sha = NEW.head_sha
        AND st.expected_base_sha = NEW.base_sha)
BEGIN SELECT RAISE(ABORT, 'LocalFeedbackBatch requires one exact StageTurn'); END;

CREATE TRIGGER local_feedback_batch_freeze
BEFORE UPDATE OF status ON local_feedback_batch
WHEN NEW.status = 'FROZEN'
  AND (NEW.frozen_at_ms IS NULL
    OR NOT EXISTS (
        SELECT 1 FROM local_feedback_batch_item item WHERE item.batch_id = NEW.id)
    OR EXISTS (
        SELECT 1
        FROM local_feedback_batch_item item
        JOIN local_review_comment_revision revision
          ON revision.id = item.comment_revision_id
        WHERE item.batch_id = NEW.id
          AND (revision.state <> 'SUBMITTED'
            OR revision.code_fingerprint <> NEW.code_fingerprint
            OR revision.head_sha <> NEW.head_sha
            OR revision.base_sha <> NEW.base_sha)))
BEGIN SELECT RAISE(ABORT, 'frozen LocalFeedbackBatch requires exact submitted revisions'); END;

CREATE TRIGGER local_feedback_batch_dispatch
BEFORE UPDATE OF status ON local_feedback_batch
WHEN NEW.status = 'DISPATCHED'
  AND (NEW.stage_turn_id IS NULL OR NOT EXISTS (
      SELECT 1
      FROM stage_turn st
      JOIN tasks t ON t.id = NEW.task_id
      JOIN task_current_stage c ON c.task_id = t.id
      WHERE st.id = NEW.stage_turn_id
        AND st.stage_id = NEW.local_development_stage_id
        AND st.stage_generation = NEW.stage_generation
        AND st.task_epoch = NEW.task_epoch
        AND st.status IN ('REQUESTED', 'QUEUED')
        AND t.epoch = NEW.task_epoch AND t.lifecycle_state = 'ACTIVE'
        AND c.stage_id = NEW.local_development_stage_id
        AND c.stage_generation = NEW.stage_generation))
BEGIN SELECT RAISE(ABORT, 'dispatched LocalFeedbackBatch requires its exact live StageTurn'); END;

CREATE TRIGGER local_feedback_batch_address_proof
BEFORE UPDATE OF status ON local_feedback_batch
WHEN NEW.status = 'ADDRESSED'
  AND (NOT EXISTS (
      SELECT 1 FROM stage_turn st
      WHERE st.id = NEW.stage_turn_id AND st.status = 'SUCCEEDED')
    OR EXISTS (
      SELECT 1
      FROM local_feedback_batch_item item
      JOIN local_review_comment_revision revision
        ON revision.id = item.comment_revision_id
      WHERE item.batch_id = NEW.id
        AND revision.state NOT IN ('ADDRESSED', 'DISMISSED')))
BEGIN SELECT RAISE(ABORT, 'addressed LocalFeedbackBatch lacks exact Turn/revision proof'); END;

CREATE TRIGGER local_feedback_batch_transition
BEFORE UPDATE OF status ON local_feedback_batch
WHEN NOT (
    (OLD.status = 'BUILDING' AND NEW.status IN ('FROZEN', 'CANCELED'))
    OR (OLD.status = 'FROZEN' AND NEW.status IN (
        'QUEUED', 'DISPATCHED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'QUEUED' AND NEW.status IN (
        'DISPATCHED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'ADDRESSED', 'FAILED', 'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'illegal LocalFeedbackBatch transition'); END;

CREATE TRIGGER local_feedback_batch_state_fields
BEFORE UPDATE OF frozen_at_ms, completed_at_ms, error_message
ON local_feedback_batch
WHEN NEW.status IS OLD.status
BEGIN SELECT RAISE(ABORT, 'LocalFeedbackBatch state fields require a transition'); END;

CREATE TRIGGER local_feedback_batch_terminal_immutable
BEFORE UPDATE ON local_feedback_batch
WHEN OLD.status IN ('ADDRESSED', 'FAILED', 'CANCELED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'terminal LocalFeedbackBatch is immutable'); END;

CREATE TABLE local_feedback_batch_item (
    batch_id              TEXT    NOT NULL
        REFERENCES local_feedback_batch(id) ON DELETE CASCADE,
    position              INTEGER NOT NULL CHECK (position > 0),
    thread_id             TEXT    NOT NULL REFERENCES local_review_thread(id),
    comment_revision_id   TEXT    NOT NULL UNIQUE
        REFERENCES local_review_comment_revision(id),
    body_digest           TEXT    NOT NULL,
    frozen_body           TEXT    NOT NULL,
    frozen_thread_content TEXT    NOT NULL,
    selected_by           TEXT    NOT NULL,
    selected_at_ms        INTEGER NOT NULL,
    PRIMARY KEY (batch_id, position),
    UNIQUE (batch_id, thread_id, comment_revision_id),
    CHECK (length(frozen_body) > 0 AND length(frozen_thread_content) > 0)
);

CREATE TRIGGER local_feedback_batch_item_insert
BEFORE INSERT ON local_feedback_batch_item
WHEN NOT EXISTS (
    SELECT 1
    FROM local_feedback_batch batch
    JOIN local_review_comment_revision revision
      ON revision.id = NEW.comment_revision_id
    WHERE batch.id = NEW.batch_id
      AND batch.status = 'BUILDING'
      AND revision.thread_id = NEW.thread_id
      AND revision.task_id = batch.task_id
      AND revision.local_development_stage_id = batch.local_development_stage_id
      AND revision.task_epoch = batch.task_epoch
      AND revision.stage_generation = batch.stage_generation
      AND revision.dev_report_id = batch.dev_report_id
      AND revision.code_fingerprint = batch.code_fingerprint
      AND revision.head_sha = batch.head_sha
      AND revision.base_sha = batch.base_sha
      AND revision.body_digest = NEW.body_digest
      AND revision.body = NEW.frozen_body)
BEGIN SELECT RAISE(ABORT, 'LocalFeedbackBatch item is not an exact comment snapshot'); END;

CREATE TRIGGER local_feedback_batch_item_immutable
BEFORE UPDATE ON local_feedback_batch_item
BEGIN SELECT RAISE(ABORT, 'LocalFeedbackBatch item is immutable'); END;

CREATE TRIGGER local_feedback_batch_item_delete_guard
BEFORE DELETE ON local_feedback_batch_item
WHEN EXISTS (
    SELECT 1 FROM local_feedback_batch batch
    WHERE batch.id = OLD.batch_id AND batch.status <> 'BUILDING')
BEGIN SELECT RAISE(ABORT, 'frozen LocalFeedbackBatch items cannot be deleted'); END;

CREATE TRIGGER local_review_comment_address_fence
BEFORE UPDATE OF state ON local_review_comment_revision
WHEN NEW.state = 'ADDRESSED'
  AND NOT EXISTS (
      SELECT 1
      FROM local_feedback_batch_item item
      JOIN local_feedback_batch batch ON batch.id = item.batch_id
      JOIN stage_turn st ON st.id = batch.stage_turn_id
      WHERE item.comment_revision_id = NEW.id
        AND batch.status = 'DISPATCHED'
        AND st.status = 'SUCCEEDED'
        AND st.expected_code_fingerprint = NEW.code_fingerprint
        AND st.expected_head_sha = NEW.head_sha
        AND st.expected_base_sha = NEW.base_sha)
BEGIN SELECT RAISE(ABORT, 'only the exact successful feedback Turn may address a revision'); END;

-- ── Explicit-human promotion exceptions and immutable local manifest ────
CREATE TABLE publish_override (
    id                         TEXT    NOT NULL PRIMARY KEY,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    dev_report_id              TEXT    NOT NULL REFERENCES dev_report(id),
    code_fingerprint           TEXT    NOT NULL,
    head_sha                   TEXT    NOT NULL,
    base_sha                   TEXT    NOT NULL,
    actor_kind                 TEXT    NOT NULL CHECK (actor_kind = 'HUMAN'),
    actor_id                   TEXT    NOT NULL,
    reason                     TEXT    NOT NULL,
    created_at_ms              INTEGER NOT NULL,
    CHECK (length(actor_id) > 0 AND length(reason) > 0)
);

CREATE TRIGGER publish_override_insert
BEFORE INSERT ON publish_override
WHEN NOT EXISTS (
    SELECT 1
    FROM local_development_stage l
    JOIN stage s ON s.id = l.stage_id
    JOIN tasks t ON t.id = l.task_id
    JOIN task_current_stage c ON c.stage_id = l.stage_id
    JOIN dev_report report ON report.id = NEW.dev_report_id
    WHERE l.stage_id = NEW.local_development_stage_id
      AND l.task_id = NEW.task_id
      AND l.opened_for_epoch = NEW.task_epoch
      AND l.generation = NEW.stage_generation
      AND s.completed_at_ms IS NULL
      AND t.epoch = NEW.task_epoch AND t.lifecycle_state = 'ACTIVE'
      AND c.task_id = NEW.task_id AND c.stage_generation = NEW.stage_generation
      AND report.workflow_version = 'V2'
      AND report.local_development_stage_id = l.stage_id
      AND report.task_id = NEW.task_id AND report.task_epoch = NEW.task_epoch
      AND report.stage_generation = NEW.stage_generation
      AND report.code_fingerprint = NEW.code_fingerprint
      AND report.head_sha = NEW.head_sha AND report.base_sha = NEW.base_sha
      AND NOT EXISTS (
          SELECT 1 FROM dev_report newer
          WHERE newer.workflow_version = 'V2'
            AND newer.local_development_stage_id = report.local_development_stage_id
            AND newer.revision > report.revision))
BEGIN SELECT RAISE(ABORT, 'PublishOverride requires the exact current local subject'); END;

CREATE TRIGGER publish_override_immutable
BEFORE UPDATE ON publish_override
BEGIN SELECT RAISE(ABORT, 'PublishOverride is immutable'); END;

CREATE TABLE publish_override_item (
    override_id            TEXT NOT NULL REFERENCES publish_override(id) ON DELETE CASCADE,
    position               INTEGER NOT NULL CHECK (position > 0),
    kind                   TEXT NOT NULL CHECK (kind IN (
        'VALIDATION_FAILURE', 'LOCAL_FEEDBACK')),
    task_blocker_id        TEXT NOT NULL UNIQUE REFERENCES task_blocker(id),
    validation_evidence_id TEXT UNIQUE REFERENCES validation_evidence(id),
    comment_revision_id    TEXT UNIQUE REFERENCES local_review_comment_revision(id),
    acknowledged_at_ms     INTEGER NOT NULL,
    PRIMARY KEY (override_id, position),
    CHECK ((kind = 'VALIDATION_FAILURE' AND validation_evidence_id IS NOT NULL
            AND comment_revision_id IS NULL)
        OR (kind = 'LOCAL_FEEDBACK' AND validation_evidence_id IS NULL
            AND comment_revision_id IS NOT NULL))
);

CREATE TRIGGER publish_override_item_insert
BEFORE INSERT ON publish_override_item
BEGIN
    SELECT CASE
        WHEN EXISTS (
            SELECT 1 FROM publish_authorization authorization
            WHERE authorization.publish_override_id = NEW.override_id)
            THEN RAISE(ABORT, 'used PublishOverride items are frozen')
        WHEN NEW.kind = 'VALIDATION_FAILURE' AND NOT EXISTS (
            SELECT 1
            FROM publish_override override
            JOIN validation_evidence evidence
              ON evidence.id = NEW.validation_evidence_id
            JOIN task_blocker blocker ON blocker.id = NEW.task_blocker_id
            WHERE override.id = NEW.override_id
              AND evidence.task_id = override.task_id
              AND evidence.task_epoch = override.task_epoch
              AND evidence.stage_id = override.local_development_stage_id
              AND evidence.stage_generation = override.stage_generation
              AND evidence.code_fingerprint = override.code_fingerprint
              AND evidence.head_sha = override.head_sha
              AND evidence.base_sha = override.base_sha
              AND evidence.passed = 0
              AND blocker.task_id = override.task_id
              AND blocker.stage_id = override.local_development_stage_id
              AND blocker.owner_kind = 'STAGE'
              AND blocker.owner_id = override.local_development_stage_id
              AND blocker.blocker_type = 'LOCAL_VALIDATION_FAILED'
              AND blocker.subject_revision = evidence.id
              AND blocker.status = 'OPEN')
            THEN RAISE(ABORT, 'validation override item does not name its exact open blocker')
        WHEN NEW.kind = 'LOCAL_FEEDBACK' AND NOT EXISTS (
            SELECT 1
            FROM publish_override override
            JOIN local_review_comment_revision revision
              ON revision.id = NEW.comment_revision_id
            JOIN task_blocker blocker ON blocker.id = NEW.task_blocker_id
            WHERE override.id = NEW.override_id
              AND revision.task_id = override.task_id
              AND revision.local_development_stage_id = override.local_development_stage_id
              AND revision.task_epoch = override.task_epoch
              AND revision.stage_generation = override.stage_generation
              AND revision.dev_report_id = override.dev_report_id
              AND revision.code_fingerprint = override.code_fingerprint
              AND revision.head_sha = override.head_sha
              AND revision.base_sha = override.base_sha
              AND revision.state IN ('DRAFT', 'PENDING', 'SUBMITTED')
              AND blocker.task_id = override.task_id
              AND blocker.stage_id = override.local_development_stage_id
              AND blocker.owner_kind = 'STAGE'
              AND blocker.owner_id = override.local_development_stage_id
              AND blocker.blocker_type = 'LOCAL_FEEDBACK_OPEN'
              AND blocker.subject_revision = revision.id
              AND blocker.status = 'OPEN')
            THEN RAISE(ABORT, 'feedback override item does not name its exact open blocker')
    END;
END;

CREATE TRIGGER publish_override_item_immutable
BEFORE UPDATE ON publish_override_item
BEGIN SELECT RAISE(ABORT, 'PublishOverride item is immutable'); END;

CREATE TRIGGER publish_override_item_delete_guard
BEFORE DELETE ON publish_override_item
WHEN EXISTS (
    SELECT 1 FROM publish_authorization authorization
    WHERE authorization.publish_override_id = OLD.override_id)
BEGIN SELECT RAISE(ABORT, 'used PublishOverride items cannot be deleted'); END;

CREATE TABLE promotion_manifest (
    id                         TEXT    NOT NULL PRIMARY KEY,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    dev_report_id              TEXT    NOT NULL REFERENCES dev_report(id),
    pr_id                      TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    policy_revision_id         TEXT    NOT NULL REFERENCES task_policy_revision(id),
    revision                   INTEGER NOT NULL CHECK (revision > 0),
    code_fingerprint           TEXT    NOT NULL,
    head_sha                   TEXT    NOT NULL,
    base_sha                   TEXT    NOT NULL,
    route                      TEXT    NOT NULL CHECK (route IN ('DIRECT', 'FORK')),
    base_repository_id         TEXT    NOT NULL,
    head_repository_id         TEXT    NOT NULL,
    publish_repository_id      TEXT    NOT NULL,
    branch_name                TEXT    NOT NULL,
    head_ref                   TEXT    NOT NULL,
    base_branch                TEXT    NOT NULL,
    worktree_clean             INTEGER NOT NULL CHECK (worktree_clean = 1),
    commits_ahead              INTEGER NOT NULL CHECK (commits_ahead > 0),
    branch_verified            INTEGER NOT NULL CHECK (branch_verified = 1),
    base_verified              INTEGER NOT NULL CHECK (base_verified = 1),
    permission_clear           INTEGER NOT NULL CHECK (permission_clear = 1),
    pr_title                   TEXT    NOT NULL,
    pr_body                    TEXT    NOT NULL,
    pr_content_revision        INTEGER NOT NULL CHECK (pr_content_revision > 0),
    pr_content_digest          TEXT    NOT NULL,
    created_at_ms              INTEGER NOT NULL,
    UNIQUE (local_development_stage_id, revision),
    UNIQUE (local_development_stage_id, code_fingerprint, head_sha,
        pr_content_revision, policy_revision_id),
    CHECK (length(branch_name) > 0 AND length(base_branch) > 0
        AND length(pr_title) > 0 AND length(pr_content_digest) > 0),
    CHECK ((route = 'DIRECT'
            AND base_repository_id = head_repository_id
            AND head_repository_id = publish_repository_id
            AND head_ref = branch_name)
        OR (route = 'FORK'
            AND base_repository_id <> head_repository_id
            AND head_repository_id = publish_repository_id
            AND instr(head_repository_id, '/') > 1
            AND head_ref = substr(
                head_repository_id, 1, instr(head_repository_id, '/') - 1)
                || ':' || branch_name))
);

CREATE TRIGGER promotion_manifest_insert
BEFORE INSERT ON promotion_manifest
BEGIN
    SELECT CASE
        WHEN NEW.revision <> COALESCE((
            SELECT MAX(manifest.revision) + 1 FROM promotion_manifest manifest
            WHERE manifest.local_development_stage_id = NEW.local_development_stage_id), 1)
            THEN RAISE(ABORT, 'PromotionManifest revision must be exact')
        WHEN NOT EXISTS (
            SELECT 1
            FROM local_development_stage l
            JOIN stage s ON s.id = l.stage_id
            JOIN tasks t ON t.id = l.task_id
            JOIN task_current_stage current ON current.stage_id = l.stage_id
            JOIN dev_report report ON report.id = NEW.dev_report_id
            JOIN pr p ON p.id = NEW.pr_id
            JOIN task_policy_revision policy ON policy.id = NEW.policy_revision_id
            JOIN task_creation_context context ON context.task_id = t.id
            JOIN task_code_identity code ON code.task_id = t.id
            WHERE l.stage_id = NEW.local_development_stage_id
              AND l.task_id = NEW.task_id
              AND l.opened_for_epoch = NEW.task_epoch
              AND l.generation = NEW.stage_generation
              AND s.completed_at_ms IS NULL
              AND t.workflow_version = 'V2'
              AND t.lifecycle_state = 'ACTIVE' AND t.epoch = NEW.task_epoch
              AND current.task_id = NEW.task_id
              AND current.stage_generation = NEW.stage_generation
              AND report.workflow_version = 'V2'
              AND report.local_development_stage_id = l.stage_id
              AND report.task_id = NEW.task_id
              AND report.task_epoch = NEW.task_epoch
              AND report.stage_generation = NEW.stage_generation
              AND report.code_fingerprint = NEW.code_fingerprint
              AND report.head_sha = NEW.head_sha
              AND report.base_sha = NEW.base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = report.local_development_stage_id
                    AND newer.revision > report.revision)
              AND p.task_id = NEW.task_id AND p.origin = 'task'
              AND p.branch_name = NEW.branch_name
              AND p.base_branch = NEW.base_branch
              AND p.title = NEW.pr_title AND p.description = NEW.pr_body
              AND policy.id = t.policy_revision_id
              AND policy.trunk_id = t.thread_id
              AND context.policy_revision_id = policy.id
              AND code.branch_name = NEW.branch_name
              AND NEW.publish_repository_id = context.publish_repository_id
              AND NEW.head_repository_id = context.publish_repository_id
              AND ((context.upstream_repository_id IS NULL
                    AND NEW.route = 'DIRECT'
                    AND NEW.base_repository_id = context.repository_id
                    AND context.repository_id = context.publish_repository_id)
                OR (context.upstream_repository_id IS NOT NULL
                    AND NEW.route = 'FORK'
                    AND NEW.base_repository_id = context.upstream_repository_id)))
            THEN RAISE(ABORT, 'PromotionManifest does not prove the exact local publish subject')
    END;
END;

CREATE TRIGGER promotion_manifest_immutable
BEFORE UPDATE ON promotion_manifest
BEGIN SELECT RAISE(ABORT, 'PromotionManifest is immutable'); END;

-- The authorization is a frozen proof, not a mutable "ready" flag.
CREATE TABLE publish_authorization (
    id                         TEXT    NOT NULL PRIMARY KEY,
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    manifest_id                TEXT    NOT NULL UNIQUE REFERENCES promotion_manifest(id),
    dev_report_id              TEXT    NOT NULL REFERENCES dev_report(id),
    validation_evidence_id     TEXT    NOT NULL REFERENCES validation_evidence(id),
    brain_review_episode_id    TEXT    NOT NULL REFERENCES brain_review_episode(id),
    pr_id                      TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    policy_revision_id         TEXT    NOT NULL REFERENCES task_policy_revision(id),
    publish_override_id        TEXT REFERENCES publish_override(id),
    code_fingerprint           TEXT    NOT NULL,
    head_sha                   TEXT    NOT NULL,
    base_sha                   TEXT    NOT NULL,
    route                      TEXT    NOT NULL CHECK (route IN ('DIRECT', 'FORK')),
    base_repository_id         TEXT    NOT NULL,
    head_repository_id         TEXT    NOT NULL,
    publish_repository_id      TEXT    NOT NULL,
    branch_name                TEXT    NOT NULL,
    head_ref                   TEXT    NOT NULL,
    base_branch                TEXT    NOT NULL,
    pr_content_revision        INTEGER NOT NULL CHECK (pr_content_revision > 0),
    pr_content_digest          TEXT    NOT NULL,
    consent_kind               TEXT    NOT NULL CHECK (consent_kind IN (
        'HUMAN', 'STANDING_TASK')),
    consent_id                 TEXT    NOT NULL,
    actor_id                   TEXT    NOT NULL,
    brain_basis                TEXT    NOT NULL CHECK (brain_basis IN (
        'APPROVED', 'HUMAN_ESCALATION')),
    brain_escalation_reason    TEXT,
    authorized_operation_id    TEXT    NOT NULL UNIQUE,
    authorized_attempt         INTEGER NOT NULL CHECK (authorized_attempt > 0),
    created_at_ms              INTEGER NOT NULL,
    revoked_at_ms              INTEGER,
    consumed_at_ms             INTEGER,
    outcome                    TEXT CHECK (outcome IN ('PUBLISHED', 'CANCELED', 'SUPERSEDED')),
    CHECK (revoked_at_ms IS NULL OR consumed_at_ms IS NULL),
    CHECK ((consumed_at_ms IS NULL) = (outcome IS NULL)),
    CHECK ((brain_basis = 'HUMAN_ESCALATION')
        = (brain_escalation_reason IS NOT NULL))
);

CREATE UNIQUE INDEX idx_publish_authorization_one_active
    ON publish_authorization(task_id)
    WHERE revoked_at_ms IS NULL AND consumed_at_ms IS NULL;

CREATE TRIGGER publish_authorization_insert
BEFORE INSERT ON publish_authorization
BEGIN
    SELECT CASE
        WHEN NEW.revoked_at_ms IS NOT NULL OR NEW.consumed_at_ms IS NOT NULL
            THEN RAISE(ABORT, 'PublishAuthorization must start active')
        WHEN NOT EXISTS (
            SELECT 1
            FROM promotion_manifest manifest
            JOIN local_development_stage l
              ON l.stage_id = manifest.local_development_stage_id
            JOIN stage s ON s.id = l.stage_id
            JOIN tasks t ON t.id = l.task_id
            JOIN task_current_stage current ON current.stage_id = l.stage_id
            JOIN dev_report report ON report.id = NEW.dev_report_id
            JOIN pr p ON p.id = NEW.pr_id
            JOIN task_policy_revision policy ON policy.id = NEW.policy_revision_id
            WHERE manifest.id = NEW.manifest_id
              AND manifest.local_development_stage_id = NEW.local_development_stage_id
              AND manifest.task_id = NEW.task_id
              AND manifest.task_epoch = NEW.task_epoch
              AND manifest.stage_generation = NEW.stage_generation
              AND manifest.dev_report_id = NEW.dev_report_id
              AND manifest.pr_id = NEW.pr_id
              AND manifest.policy_revision_id = NEW.policy_revision_id
              AND manifest.code_fingerprint = NEW.code_fingerprint
              AND manifest.head_sha = NEW.head_sha
              AND manifest.base_sha = NEW.base_sha
              AND manifest.route = NEW.route
              AND manifest.base_repository_id = NEW.base_repository_id
              AND manifest.head_repository_id = NEW.head_repository_id
              AND manifest.publish_repository_id = NEW.publish_repository_id
              AND manifest.branch_name = NEW.branch_name
              AND manifest.head_ref = NEW.head_ref
              AND manifest.base_branch = NEW.base_branch
              AND manifest.pr_content_revision = NEW.pr_content_revision
              AND manifest.pr_content_digest = NEW.pr_content_digest
              AND manifest.worktree_clean = 1 AND manifest.commits_ahead > 0
              AND manifest.branch_verified = 1 AND manifest.base_verified = 1
              AND manifest.permission_clear = 1
              AND l.opened_for_epoch = NEW.task_epoch
              AND l.generation = NEW.stage_generation
              AND s.completed_at_ms IS NULL
              AND t.workflow_version = 'V2'
              AND t.lifecycle_state = 'ACTIVE' AND t.epoch = NEW.task_epoch
              AND current.task_id = NEW.task_id
              AND current.stage_generation = NEW.stage_generation
              AND report.workflow_version = 'V2'
              AND report.local_development_stage_id = l.stage_id
              AND report.task_id = NEW.task_id
              AND report.task_epoch = NEW.task_epoch
              AND report.stage_generation = NEW.stage_generation
              AND report.code_fingerprint = NEW.code_fingerprint
              AND report.head_sha = NEW.head_sha AND report.base_sha = NEW.base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = report.local_development_stage_id
                    AND newer.revision > report.revision)
              AND p.task_id = NEW.task_id AND p.origin = 'task'
              AND policy.id = t.policy_revision_id)
            THEN RAISE(ABORT, 'PublishAuthorization does not match its current manifest')
        WHEN NOT EXISTS (
            SELECT 1
            FROM validation_evidence evidence
            JOIN validation_operation operation
              ON operation.id = evidence.validation_operation_id
            WHERE evidence.id = NEW.validation_evidence_id
              AND operation.dev_report_id = NEW.dev_report_id
              AND operation.status = 'COMPLETED'
              AND evidence.task_id = NEW.task_id
              AND evidence.task_epoch = NEW.task_epoch
              AND evidence.stage_id = NEW.local_development_stage_id
              AND evidence.stage_generation = NEW.stage_generation
              AND evidence.code_fingerprint = NEW.code_fingerprint
              AND evidence.head_sha = NEW.head_sha
              AND evidence.base_sha = NEW.base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM validation_operation newer
                  WHERE newer.dev_report_id = operation.dev_report_id
                    AND newer.semantic_attempt > operation.semantic_attempt)
              AND (evidence.passed = 1 OR EXISTS (
                  SELECT 1 FROM publish_override_item item
                  WHERE item.override_id = NEW.publish_override_id
                    AND item.kind = 'VALIDATION_FAILURE'
                    AND item.validation_evidence_id = evidence.id)))
            THEN RAISE(ABORT, 'PublishAuthorization lacks exact current validation evidence')
        WHEN NOT EXISTS (
            SELECT 1 FROM brain_review_episode brain
            WHERE brain.id = NEW.brain_review_episode_id
              AND brain.task_id = NEW.task_id
              AND brain.task_epoch = NEW.task_epoch
              AND brain.local_development_stage_id = NEW.local_development_stage_id
              AND brain.stage_generation = NEW.stage_generation
              AND brain.dev_report_id = NEW.dev_report_id
              AND brain.code_fingerprint = NEW.code_fingerprint
              AND brain.expected_head_sha = NEW.head_sha
              AND brain.expected_base_sha = NEW.base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM brain_review_episode newer
                  WHERE newer.dev_report_id = brain.dev_report_id
                    AND newer.semantic_attempt > brain.semantic_attempt)
              AND ((NEW.brain_basis = 'APPROVED'
                    AND brain.status = 'SUCCEEDED'
                    AND brain.verdict = 'APPROVED'
                    AND brain.unresolved_finding_count = 0)
                OR (NEW.brain_basis = 'HUMAN_ESCALATION'
                    AND NEW.consent_kind = 'HUMAN'
                    AND brain.status = 'BUDGET_EXHAUSTED')))
            THEN RAISE(ABORT, 'PublishAuthorization lacks exact Brain evidence')
        WHEN NEW.publish_override_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM publish_override override
            WHERE override.id = NEW.publish_override_id
              AND override.local_development_stage_id = NEW.local_development_stage_id
              AND override.task_id = NEW.task_id
              AND override.task_epoch = NEW.task_epoch
              AND override.stage_generation = NEW.stage_generation
              AND override.dev_report_id = NEW.dev_report_id
              AND override.code_fingerprint = NEW.code_fingerprint
              AND override.head_sha = NEW.head_sha
              AND override.base_sha = NEW.base_sha
              AND EXISTS (
                  SELECT 1 FROM publish_override_item item
                  WHERE item.override_id = override.id))
            THEN RAISE(ABORT, 'PublishAuthorization override is empty or stale')
        WHEN EXISTS (
            SELECT 1 FROM local_review_comment_revision revision
            WHERE revision.task_id = NEW.task_id
              AND revision.local_development_stage_id = NEW.local_development_stage_id
              AND revision.task_epoch = NEW.task_epoch
              AND revision.stage_generation = NEW.stage_generation
              AND revision.dev_report_id = NEW.dev_report_id
              AND revision.code_fingerprint = NEW.code_fingerprint
              AND revision.head_sha = NEW.head_sha
              AND revision.base_sha = NEW.base_sha
              AND revision.state IN ('DRAFT', 'PENDING', 'SUBMITTED')
              AND NOT EXISTS (
                  SELECT 1 FROM publish_override_item item
                  WHERE item.override_id = NEW.publish_override_id
                    AND item.kind = 'LOCAL_FEEDBACK'
                    AND item.comment_revision_id = revision.id))
            THEN RAISE(ABORT, 'PublishAuthorization has unacknowledged local feedback')
        WHEN NEW.consent_kind = 'STANDING_TASK' AND NOT EXISTS (
            SELECT 1 FROM task_policy_revision policy
            WHERE policy.id = NEW.policy_revision_id
              AND policy.auto_approve = 1
              AND NEW.consent_id = policy.id
              AND NEW.publish_override_id IS NULL
              AND NEW.brain_basis = 'APPROVED')
            THEN RAISE(ABORT, 'standing publish consent is not enabled by exact Task policy')
    END;
END;

CREATE TRIGGER publish_authorization_identity_immutable
BEFORE UPDATE OF local_development_stage_id, task_id, task_epoch,
        stage_generation, manifest_id, dev_report_id, validation_evidence_id,
        brain_review_episode_id, pr_id, policy_revision_id, publish_override_id,
        code_fingerprint, head_sha, base_sha, route, base_repository_id,
        head_repository_id, publish_repository_id, branch_name, head_ref,
        base_branch, pr_content_revision, pr_content_digest, consent_kind,
        consent_id, actor_id, brain_basis, brain_escalation_reason,
        authorized_operation_id, authorized_attempt, created_at_ms
ON publish_authorization
WHEN NEW.local_development_stage_id IS NOT OLD.local_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.manifest_id IS NOT OLD.manifest_id
  OR NEW.dev_report_id IS NOT OLD.dev_report_id
  OR NEW.validation_evidence_id IS NOT OLD.validation_evidence_id
  OR NEW.brain_review_episode_id IS NOT OLD.brain_review_episode_id
  OR NEW.pr_id IS NOT OLD.pr_id
  OR NEW.policy_revision_id IS NOT OLD.policy_revision_id
  OR NEW.publish_override_id IS NOT OLD.publish_override_id
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.head_sha IS NOT OLD.head_sha OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.route IS NOT OLD.route
  OR NEW.base_repository_id IS NOT OLD.base_repository_id
  OR NEW.head_repository_id IS NOT OLD.head_repository_id
  OR NEW.publish_repository_id IS NOT OLD.publish_repository_id
  OR NEW.branch_name IS NOT OLD.branch_name OR NEW.head_ref IS NOT OLD.head_ref
  OR NEW.base_branch IS NOT OLD.base_branch
  OR NEW.pr_content_revision IS NOT OLD.pr_content_revision
  OR NEW.pr_content_digest IS NOT OLD.pr_content_digest
  OR NEW.consent_kind IS NOT OLD.consent_kind
  OR NEW.consent_id IS NOT OLD.consent_id OR NEW.actor_id IS NOT OLD.actor_id
  OR NEW.brain_basis IS NOT OLD.brain_basis
  OR NEW.brain_escalation_reason IS NOT OLD.brain_escalation_reason
  OR NEW.authorized_operation_id IS NOT OLD.authorized_operation_id
  OR NEW.authorized_attempt IS NOT OLD.authorized_attempt
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'PublishAuthorization proof is immutable'); END;

CREATE TRIGGER publish_authorization_state_transition
BEFORE UPDATE OF revoked_at_ms, consumed_at_ms, outcome ON publish_authorization
WHEN OLD.revoked_at_ms IS NOT NULL OR OLD.consumed_at_ms IS NOT NULL
  OR NOT ((NEW.revoked_at_ms IS NOT NULL AND NEW.consumed_at_ms IS NULL
            AND NEW.outcome IS NULL)
      OR (NEW.revoked_at_ms IS NULL AND NEW.consumed_at_ms IS NOT NULL
            AND NEW.outcome IS NOT NULL))
BEGIN SELECT RAISE(ABORT, 'illegal PublishAuthorization state transition'); END;

-- ── Typed publish operation and ordered, restart-safe effect ledger ─────
CREATE TABLE publish_operation (
    id                         TEXT    NOT NULL PRIMARY KEY,
    publish_authorization_id   TEXT    NOT NULL UNIQUE
        REFERENCES publish_authorization(id),
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    operation_id               TEXT    NOT NULL UNIQUE,
    semantic_attempt           INTEGER NOT NULL CHECK (semantic_attempt > 0),
    code_fingerprint           TEXT    NOT NULL,
    expected_head_sha          TEXT    NOT NULL,
    expected_base_sha          TEXT    NOT NULL,
    status                     TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'CANCELED', 'SUPERSEDED', 'INDETERMINATE')),
    remote_repository_id       TEXT,
    remote_pr_number           INTEGER,
    remote_pr_url              TEXT,
    remote_head_ref            TEXT,
    remote_head_sha            TEXT,
    remote_base_sha            TEXT,
    result_evidence            TEXT,
    requested_at_ms            INTEGER NOT NULL,
    completed_at_ms            INTEGER,
    error_message              TEXT,
    CHECK (
        (status IN ('REQUESTED', 'DISPATCHED')
            AND remote_repository_id IS NULL AND remote_pr_number IS NULL
            AND remote_pr_url IS NULL AND remote_head_ref IS NULL
            AND remote_head_sha IS NULL AND remote_base_sha IS NULL
            AND result_evidence IS NULL AND completed_at_ms IS NULL
            AND error_message IS NULL)
        OR (status = 'SUCCEEDED'
            AND remote_repository_id IS NOT NULL AND remote_pr_number > 0
            AND remote_pr_url IS NOT NULL AND remote_head_ref IS NOT NULL
            AND remote_head_sha IS NOT NULL AND remote_base_sha IS NOT NULL
            AND result_evidence IS NOT NULL AND completed_at_ms IS NOT NULL
            AND error_message IS NULL)
        OR (status IN ('FAILED', 'CANCELED', 'SUPERSEDED', 'INDETERMINATE')
            AND remote_repository_id IS NULL AND remote_pr_number IS NULL
            AND remote_pr_url IS NULL AND remote_head_ref IS NULL
            AND remote_head_sha IS NULL AND remote_base_sha IS NULL
            AND result_evidence IS NULL AND completed_at_ms IS NOT NULL
            AND error_message IS NOT NULL))
);

CREATE UNIQUE INDEX idx_publish_operation_one_active
    ON publish_operation(task_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TRIGGER publish_operation_insert
BEFORE INSERT ON publish_operation
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'REQUESTED'
            THEN RAISE(ABORT, 'PublishOperation must start REQUESTED')
        WHEN NOT EXISTS (
            SELECT 1
            FROM publish_authorization authorization
            JOIN promotion_manifest manifest ON manifest.id = authorization.manifest_id
            JOIN local_development_stage l
              ON l.stage_id = authorization.local_development_stage_id
            JOIN stage s ON s.id = l.stage_id
            JOIN tasks t ON t.id = l.task_id
            JOIN task_current_stage current ON current.stage_id = l.stage_id
            JOIN dev_report report ON report.id = authorization.dev_report_id
            JOIN validation_evidence validation
              ON validation.id = authorization.validation_evidence_id
            JOIN validation_operation validation_operation
              ON validation_operation.id = validation.validation_operation_id
            JOIN brain_review_episode brain
              ON brain.id = authorization.brain_review_episode_id
            WHERE authorization.id = NEW.publish_authorization_id
              AND authorization.revoked_at_ms IS NULL
              AND authorization.consumed_at_ms IS NULL
              AND authorization.local_development_stage_id = NEW.local_development_stage_id
              AND authorization.task_id = NEW.task_id
              AND authorization.task_epoch = NEW.task_epoch
              AND authorization.stage_generation = NEW.stage_generation
              AND authorization.code_fingerprint = NEW.code_fingerprint
              AND authorization.head_sha = NEW.expected_head_sha
              AND authorization.base_sha = NEW.expected_base_sha
              AND authorization.authorized_operation_id = NEW.operation_id
              AND authorization.authorized_attempt = NEW.semantic_attempt
              AND manifest.local_development_stage_id = NEW.local_development_stage_id
              AND manifest.task_id = NEW.task_id
              AND manifest.task_epoch = NEW.task_epoch
              AND manifest.stage_generation = NEW.stage_generation
              AND manifest.code_fingerprint = NEW.code_fingerprint
              AND manifest.head_sha = NEW.expected_head_sha
              AND manifest.base_sha = NEW.expected_base_sha
              AND l.opened_for_epoch = NEW.task_epoch
              AND l.generation = NEW.stage_generation
              AND s.completed_at_ms IS NULL
              AND t.lifecycle_state = 'ACTIVE' AND t.epoch = NEW.task_epoch
              AND current.task_id = NEW.task_id
              AND current.stage_generation = NEW.stage_generation
              AND report.workflow_version = 'V2'
              AND report.local_development_stage_id = l.stage_id
              AND report.code_fingerprint = NEW.code_fingerprint
              AND report.head_sha = NEW.expected_head_sha
              AND report.base_sha = NEW.expected_base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = report.local_development_stage_id
                    AND newer.revision > report.revision)
              AND validation_operation.dev_report_id = report.id
              AND validation_operation.status = 'COMPLETED'
              AND NOT EXISTS (
                  SELECT 1 FROM validation_operation newer
                  WHERE newer.dev_report_id = report.id
                    AND newer.semantic_attempt > validation_operation.semantic_attempt)
              AND brain.dev_report_id = report.id
              AND NOT EXISTS (
                  SELECT 1 FROM brain_review_episode newer
                  WHERE newer.dev_report_id = report.id
                    AND newer.semantic_attempt > brain.semantic_attempt))
            THEN RAISE(ABORT, 'PublishOperation authorization is stale or not exact')
    END;
END;

CREATE TRIGGER publish_operation_identity_immutable
BEFORE UPDATE OF publish_authorization_id, local_development_stage_id,
        task_id, task_epoch, stage_generation, operation_id, semantic_attempt,
        code_fingerprint, expected_head_sha, expected_base_sha, requested_at_ms
ON publish_operation
WHEN NEW.publish_authorization_id IS NOT OLD.publish_authorization_id
  OR NEW.local_development_stage_id IS NOT OLD.local_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.code_fingerprint IS NOT OLD.code_fingerprint
  OR NEW.expected_head_sha IS NOT OLD.expected_head_sha
  OR NEW.expected_base_sha IS NOT OLD.expected_base_sha
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'PublishOperation fence is immutable'); END;

CREATE TRIGGER publish_operation_transition
BEFORE UPDATE OF status ON publish_operation
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN (
        'DISPATCHED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED', 'INDETERMINATE')))
BEGIN SELECT RAISE(ABORT, 'illegal PublishOperation transition'); END;

CREATE TRIGGER publish_operation_terminal_immutable
BEFORE UPDATE ON publish_operation
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELED',
    'SUPERSEDED', 'INDETERMINATE')
BEGIN SELECT RAISE(ABORT, 'terminal PublishOperation is immutable'); END;

CREATE TABLE publish_effect_step (
    id                    TEXT    NOT NULL PRIMARY KEY,
    publish_operation_id  TEXT    NOT NULL
        REFERENCES publish_operation(id) ON DELETE CASCADE,
    ordinal               INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 6),
    kind                  TEXT    NOT NULL CHECK (kind IN (
        'VERIFY_SUBJECT', 'RECONCILE_BRANCH_BASE', 'PUSH_BRANCH',
        'CREATE_OR_ADOPT_DRAFT_PR', 'FETCH_REMOTE_DETAIL',
        'PROVE_REMOTE_HEAD')),
    idempotency_key       TEXT    NOT NULL UNIQUE,
    status                TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'INDETERMINATE')),
    attempt_count         INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit         INTEGER NOT NULL CHECK (attempt_limit > 0),
    claim_mode            TEXT CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    claim_owner           TEXT,
    claimed_at_ms         INTEGER,
    lease_until_ms        INTEGER,
    evidence              TEXT,
    last_error            TEXT,
    completed_at_ms       INTEGER,
    UNIQUE (publish_operation_id, ordinal),
    UNIQUE (publish_operation_id, kind),
    CHECK (
        (status = 'REQUESTED'
            AND claim_mode IS NULL AND claim_owner IS NULL
            AND claimed_at_ms IS NULL AND lease_until_ms IS NULL
            AND evidence IS NULL AND last_error IS NULL
            AND completed_at_ms IS NULL)
        OR (status = 'CLAIMED'
            AND claim_mode IS NOT NULL AND claim_owner IS NOT NULL
            AND claimed_at_ms IS NOT NULL AND lease_until_ms IS NOT NULL
            AND evidence IS NULL AND last_error IS NULL
            AND completed_at_ms IS NULL)
        OR (status = 'SUCCEEDED'
            AND claim_mode IS NULL AND claim_owner IS NULL
            AND claimed_at_ms IS NULL AND lease_until_ms IS NULL
            AND evidence IS NOT NULL AND last_error IS NULL
            AND completed_at_ms IS NOT NULL)
        OR (status IN ('FAILED', 'INDETERMINATE')
            AND claim_mode IS NULL AND claim_owner IS NULL
            AND claimed_at_ms IS NULL AND lease_until_ms IS NULL
            AND evidence IS NULL AND last_error IS NOT NULL
            AND completed_at_ms IS NOT NULL))
);

CREATE UNIQUE INDEX idx_publish_effect_step_one_claimed
    ON publish_effect_step(publish_operation_id)
    WHERE status = 'CLAIMED';

CREATE TRIGGER publish_effect_step_insert
BEFORE INSERT ON publish_effect_step
WHEN NEW.status <> 'REQUESTED'
  OR NEW.attempt_count <> 0
  OR NOT EXISTS (
      SELECT 1 FROM publish_operation operation
      WHERE operation.id = NEW.publish_operation_id
        AND operation.status = 'REQUESTED')
  OR NOT ((NEW.ordinal = 1 AND NEW.kind = 'VERIFY_SUBJECT')
      OR (NEW.ordinal = 2 AND NEW.kind = 'RECONCILE_BRANCH_BASE')
      OR (NEW.ordinal = 3 AND NEW.kind = 'PUSH_BRANCH')
      OR (NEW.ordinal = 4 AND NEW.kind = 'CREATE_OR_ADOPT_DRAFT_PR')
      OR (NEW.ordinal = 5 AND NEW.kind = 'FETCH_REMOTE_DETAIL')
      OR (NEW.ordinal = 6 AND NEW.kind = 'PROVE_REMOTE_HEAD'))
BEGIN SELECT RAISE(ABORT, 'Publish effect step identity/order is invalid'); END;

CREATE TRIGGER publish_effect_step_identity_immutable
BEFORE UPDATE OF publish_operation_id, ordinal, kind, idempotency_key,
        attempt_limit ON publish_effect_step
WHEN NEW.publish_operation_id IS NOT OLD.publish_operation_id
  OR NEW.ordinal IS NOT OLD.ordinal OR NEW.kind IS NOT OLD.kind
  OR NEW.idempotency_key IS NOT OLD.idempotency_key
  OR NEW.attempt_limit IS NOT OLD.attempt_limit
BEGIN SELECT RAISE(ABORT, 'Publish effect step identity is immutable'); END;

CREATE TRIGGER publish_effect_step_claim
BEFORE UPDATE OF status ON publish_effect_step
WHEN NEW.status = 'CLAIMED'
  AND (OLD.status NOT IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
    OR NEW.attempt_count <> OLD.attempt_count + 1
    OR NEW.attempt_count > NEW.attempt_limit
    OR (OLD.status = 'INDETERMINATE' AND NEW.claim_mode <> 'PROBE')
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

CREATE TRIGGER publish_effect_step_result
BEFORE UPDATE OF status ON publish_effect_step
WHEN NEW.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')
  AND (OLD.status <> 'CLAIMED'
    OR NEW.attempt_count <> OLD.attempt_count
    OR NEW.claim_mode IS NOT NULL OR NEW.claim_owner IS NOT NULL
    OR NEW.claimed_at_ms IS NOT NULL OR NEW.lease_until_ms IS NOT NULL)
BEGIN SELECT RAISE(ABORT, 'Publish effect result requires its exact committed claim'); END;

CREATE TRIGGER publish_effect_step_transition
BEFORE UPDATE OF status ON publish_effect_step
WHEN NOT (
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
BEGIN SELECT RAISE(ABORT, 'Publish effect mutable fields require a status transition'); END;

CREATE TRIGGER publish_effect_step_succeeded_immutable
BEFORE UPDATE ON publish_effect_step
WHEN OLD.status = 'SUCCEEDED'
BEGIN SELECT RAISE(ABORT, 'successful Publish effect step is immutable'); END;

CREATE TRIGGER publish_operation_dispatch
BEFORE UPDATE OF status ON publish_operation
WHEN NEW.status = 'DISPATCHED'
  AND (NOT EXISTS (
      SELECT 1 FROM dispatch_ticket ticket
      WHERE ticket.operation_id = NEW.operation_id
        AND ticket.operation_kind = 'PUBLISH_LOCAL_DEVELOPMENT'
        AND ticket.async_family = 'GITHUB_EFFECT'
        AND ticket.owner_kind = 'STAGE'
        AND ticket.owner_id = NEW.local_development_stage_id
        AND ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND ticket.stage_id = NEW.local_development_stage_id
        AND ticket.stage_generation = NEW.stage_generation
        AND ticket.attempt = NEW.semantic_attempt
        AND ticket.expected_code_fingerprint = NEW.code_fingerprint
        AND ticket.expected_head_sha = NEW.expected_head_sha
        AND ticket.expected_base_sha = NEW.expected_base_sha
        AND ticket.exclusive_task = 1 AND ticket.writer_required = 1
        AND (ticket.lane_mask & 48) = 48
        AND ticket.status = 'REQUESTED')
    OR (SELECT COUNT(*) FROM publish_effect_step step
        WHERE step.publish_operation_id = NEW.id) <> 6)
BEGIN SELECT RAISE(ABORT, 'dispatched PublishOperation requires exact ticket and six effect steps'); END;

CREATE TRIGGER publish_operation_success
BEFORE UPDATE OF status ON publish_operation
WHEN NEW.status = 'SUCCEEDED'
  AND (NEW.remote_head_sha <> NEW.expected_head_sha
    OR NEW.remote_base_sha <> NEW.expected_base_sha
    OR EXISTS (
        SELECT 1 FROM publish_effect_step step
        WHERE step.publish_operation_id = NEW.id
          AND step.status <> 'SUCCEEDED')
    OR (SELECT COUNT(*) FROM publish_effect_step step
        WHERE step.publish_operation_id = NEW.id) <> 6
    OR NOT EXISTS (
        SELECT 1 FROM dispatch_ticket ticket
        WHERE ticket.operation_id = NEW.operation_id
          AND ticket.status = 'RESULT_PENDING'
          AND ticket.pending_result_outcome = 'SUCCEEDED'
          AND ticket.pending_result_task_epoch = NEW.task_epoch
          AND ticket.pending_result_stage_id = NEW.local_development_stage_id
          AND ticket.pending_result_stage_generation = NEW.stage_generation
          AND ticket.pending_result_operation_id = NEW.operation_id
          AND ticket.pending_result_attempt = NEW.semantic_attempt
          AND ticket.pending_result_expected_code_fingerprint = NEW.code_fingerprint
          AND ticket.pending_result_expected_head_sha = NEW.expected_head_sha
          AND ticket.pending_result_expected_base_sha = NEW.expected_base_sha
          AND ticket.pending_result_evidence IS NOT NULL))
BEGIN SELECT RAISE(ABORT, 'successful PublishOperation lacks exact effect/raw-result proof'); END;

-- Remote identity is attached to the same stable local PR aggregate.
CREATE TABLE remote_pr_binding (
    id                       TEXT    NOT NULL PRIMARY KEY,
    task_id                  TEXT    NOT NULL UNIQUE
        REFERENCES tasks(id) ON DELETE CASCADE,
    pr_id                    TEXT    NOT NULL UNIQUE REFERENCES pr(id) ON DELETE CASCADE,
    publish_operation_id     TEXT    NOT NULL UNIQUE REFERENCES publish_operation(id),
    publish_authorization_id TEXT    NOT NULL UNIQUE REFERENCES publish_authorization(id),
    manifest_id              TEXT    NOT NULL UNIQUE REFERENCES promotion_manifest(id),
    route                    TEXT    NOT NULL CHECK (route IN ('DIRECT', 'FORK')),
    base_repository_id       TEXT    NOT NULL,
    head_repository_id       TEXT    NOT NULL,
    remote_repository_id     TEXT    NOT NULL,
    remote_pr_number         INTEGER NOT NULL CHECK (remote_pr_number > 0),
    remote_pr_url            TEXT    NOT NULL,
    remote_head_ref          TEXT    NOT NULL,
    remote_head_sha          TEXT    NOT NULL,
    remote_base_sha          TEXT    NOT NULL,
    evidence                 TEXT    NOT NULL,
    bound_at_ms              INTEGER NOT NULL,
    UNIQUE (remote_repository_id, remote_pr_number)
);

CREATE TRIGGER remote_pr_binding_insert
BEFORE INSERT ON remote_pr_binding
WHEN NOT EXISTS (
    SELECT 1
    FROM publish_operation operation
    JOIN publish_authorization authorization
      ON authorization.id = operation.publish_authorization_id
    JOIN promotion_manifest manifest ON manifest.id = authorization.manifest_id
    JOIN pr p ON p.id = authorization.pr_id
    WHERE operation.id = NEW.publish_operation_id
      AND operation.status = 'SUCCEEDED'
      AND operation.publish_authorization_id = NEW.publish_authorization_id
      AND authorization.manifest_id = NEW.manifest_id
      AND authorization.task_id = NEW.task_id
      AND authorization.pr_id = NEW.pr_id
      AND authorization.route = NEW.route
      AND authorization.base_repository_id = NEW.base_repository_id
      AND authorization.head_repository_id = NEW.head_repository_id
      AND authorization.head_ref = NEW.remote_head_ref
      AND authorization.head_sha = NEW.remote_head_sha
      AND authorization.base_sha = NEW.remote_base_sha
      AND manifest.task_id = NEW.task_id AND manifest.pr_id = NEW.pr_id
      AND manifest.route = NEW.route
      AND manifest.base_repository_id = NEW.base_repository_id
      AND manifest.head_repository_id = NEW.head_repository_id
      AND p.task_id = NEW.task_id AND p.origin = 'task'
      AND operation.remote_repository_id = NEW.remote_repository_id
      AND operation.remote_repository_id = NEW.base_repository_id
      AND operation.remote_pr_number = NEW.remote_pr_number
      AND operation.remote_pr_url = NEW.remote_pr_url
      AND operation.remote_head_ref = NEW.remote_head_ref
      AND operation.remote_head_sha = NEW.remote_head_sha
      AND operation.remote_base_sha = NEW.remote_base_sha)
BEGIN SELECT RAISE(ABORT, 'RemotePrBinding does not match exact successful publish proof'); END;

CREATE TRIGGER remote_pr_binding_immutable
BEFORE UPDATE ON remote_pr_binding
BEGIN SELECT RAISE(ABORT, 'RemotePrBinding is immutable'); END;

CREATE TRIGGER publish_authorization_consumption_proof
BEFORE UPDATE OF consumed_at_ms, outcome ON publish_authorization
WHEN NEW.consumed_at_ms IS NOT NULL
  AND NOT (
      (NEW.outcome = 'PUBLISHED' AND EXISTS (
          SELECT 1 FROM remote_pr_binding binding
          WHERE binding.publish_authorization_id = NEW.id
            AND binding.task_id = NEW.task_id
            AND binding.pr_id = NEW.pr_id
            AND binding.remote_head_sha = NEW.head_sha
            AND binding.remote_base_sha = NEW.base_sha))
      OR (NEW.outcome IN ('CANCELED', 'SUPERSEDED') AND EXISTS (
          SELECT 1 FROM publish_operation operation
          WHERE operation.publish_authorization_id = NEW.id
            AND operation.local_development_stage_id = NEW.local_development_stage_id
            AND operation.task_id = NEW.task_id
            AND operation.task_epoch = NEW.task_epoch
            AND operation.stage_generation = NEW.stage_generation
            AND operation.code_fingerprint = NEW.code_fingerprint
            AND operation.expected_head_sha = NEW.head_sha
            AND operation.expected_base_sha = NEW.base_sha
            AND operation.status = NEW.outcome
            AND operation.completed_at_ms IS NOT NULL)))
BEGIN SELECT RAISE(ABORT, 'consumed PublishAuthorization lacks exact terminal proof'); END;

-- ── Owner-typed idempotent command receipts ─────────────────────────────
-- Transition rows describe mutations and therefore remain unique by owner
-- version. Receipts are separate: they preserve the exact result returned for
-- every command, including many SUPERSEDED commands at one unchanged version.
CREATE UNIQUE INDEX idx_stage_receipt_owner_identity
    ON stage(id, task_id);
CREATE UNIQUE INDEX idx_stage_receipt_full_identity
    ON stage(id, task_id, kind, generation);

CREATE TABLE trunk_command_receipt (
    id                       TEXT    NOT NULL PRIMARY KEY,
    trunk_id                 TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    command_id               TEXT    NOT NULL,
    cause                    TEXT    NOT NULL CHECK (cause IN (
        'MARK_IDLE', 'ACTIVATE', 'ARCHIVE')),
    actor                    TEXT    NOT NULL,
    disposition              TEXT    NOT NULL CHECK (disposition = 'APPLIED'),
    expected_version         INTEGER NOT NULL CHECK (expected_version >= 0),
    returned_lifecycle       TEXT    NOT NULL CHECK (returned_lifecycle IN (
        'ACTIVE', 'IDLE', 'ARCHIVED')),
    returned_version         INTEGER NOT NULL CHECK (returned_version >= 0),
    recorded_at_ms           INTEGER NOT NULL,
    UNIQUE (trunk_id, command_id),
    CHECK (returned_version = expected_version + 1)
);

CREATE TRIGGER trunk_command_receipt_insert
BEFORE INSERT ON trunk_command_receipt
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM threads trunk
            WHERE trunk.id = NEW.trunk_id AND trunk.turn_version = 'V2')
            THEN RAISE(ABORT, 'Trunk command receipt requires a V2 Trunk')
        WHEN NOT EXISTS (
            SELECT 1 FROM trunk_transition transition
            WHERE transition.trunk_id = NEW.trunk_id
              AND transition.command_id = NEW.command_id
              AND transition.to_state = NEW.returned_lifecycle
              AND transition.aggregate_version = NEW.returned_version
              AND transition.cause = NEW.cause AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'applied Trunk receipt lacks its exact transition')
    END;
END;

CREATE TRIGGER trunk_command_receipt_immutable
BEFORE UPDATE ON trunk_command_receipt
BEGIN SELECT RAISE(ABORT, 'Trunk command receipt is immutable'); END;

CREATE TABLE task_command_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause IN (
        'REQUEST_PAUSE', 'COMPLETE_PAUSE', 'REQUEST_RESUME', 'COMPLETE_RESUME',
        'REQUEST_ARCHIVE', 'COMPLETE_ARCHIVE', 'REQUEST_CANCEL',
        'ACCEPT_PROVISIONING', 'OPEN_REPLAN_PLAN', 'OPEN_CANCELED_CLEANUP',
        'OPEN_MERGED_CLEANUP', 'OPEN_REMOTE_CLOSED_CLEANUP',
        'ACCEPT_BRAIN_VERDICT', 'ACCEPT_CLEANUP_COMPLETION',
        'OPEN_LOCAL_DEVELOPMENT', 'OPEN_REMOTE_DEVELOPMENT')),
    actor                             TEXT    NOT NULL,
    disposition                       TEXT    NOT NULL CHECK (disposition IN (
        'APPLIED', 'SUPERSEDED')),

    -- Present only when the command itself carried a structural Task fence.
    expected_task_epoch               INTEGER CHECK (expected_task_epoch > 0),
    expected_task_version             INTEGER CHECK (expected_task_version >= 0),

    -- Optional Task command ResultFence, preserved field-for-field.
    subject_task_epoch                INTEGER CHECK (subject_task_epoch > 0),
    subject_stage_id                  TEXT,
    subject_stage_generation          INTEGER,
    subject_operation_id              TEXT,
    subject_attempt                   INTEGER,
    subject_expected_code_fingerprint TEXT,
    subject_expected_head_sha         TEXT,
    subject_expected_base_sha         TEXT,
    brain_verdict                     TEXT CHECK (brain_verdict IN (
        'APPROVED', 'CHANGES_REQUESTED')),
    proof_id                          TEXT,
    next_stage_id                     TEXT,
    next_stage_kind                   TEXT CHECK (next_stage_kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT', 'CLEANUP')),
    next_stage_generation             INTEGER CHECK (next_stage_generation > 0),

    -- Exact returned TaskManager.State. These are a historical replay
    -- snapshot and intentionally are not compared to today's Task row.
    returned_trunk_id                 TEXT    NOT NULL REFERENCES threads(id),
    returned_lifecycle                TEXT    NOT NULL CHECK (returned_lifecycle IN (
        'PROVISIONING', 'ACTIVE', 'PAUSING', 'PAUSED', 'RESUMING',
        'ARCHIVING', 'ARCHIVED', 'CANCELING', 'CLEANING',
        'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')),
    returned_epoch                    INTEGER NOT NULL CHECK (returned_epoch > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version >= 0),
    returned_current_stage_id         TEXT,
    returned_pending_task_epoch       INTEGER CHECK (returned_pending_task_epoch > 0),
    returned_pending_stage_id         TEXT,
    returned_pending_stage_generation INTEGER,
    returned_pending_operation_id     TEXT,
    returned_pending_attempt          INTEGER,
    returned_pending_code_fingerprint TEXT,
    returned_pending_head_sha         TEXT,
    returned_pending_base_sha         TEXT,
    returned_last_brain_verdict       TEXT CHECK (returned_last_brain_verdict IN (
        'APPROVED', 'CHANGES_REQUESTED')),
    returned_last_brain_task_epoch    INTEGER CHECK (returned_last_brain_task_epoch > 0),
    returned_last_brain_stage_id      TEXT,
    returned_last_brain_stage_generation INTEGER,
    returned_last_brain_operation_id  TEXT,
    returned_last_brain_attempt       INTEGER,
    returned_last_brain_code_fingerprint TEXT,
    returned_last_brain_head_sha      TEXT,
    returned_last_brain_base_sha      TEXT,
    returned_terminal_intent          TEXT CHECK (returned_terminal_intent IN (
        'CANCELED', 'COMPLETED', 'REMOTE_CLOSED')),
    recorded_at_ms                    INTEGER NOT NULL,
    UNIQUE (task_id, command_id),
    CHECK ((expected_task_epoch IS NULL AND expected_task_version IS NULL)
        OR (expected_task_epoch IS NOT NULL AND expected_task_version IS NOT NULL)),
    CHECK ((expected_task_epoch IS NULL) = (cause = 'ACCEPT_BRAIN_VERDICT')),
    CHECK (cause = 'ACCEPT_BRAIN_VERDICT'
        OR returned_version = expected_task_version + 1),
    CHECK ((subject_operation_id IS NULL
            AND subject_task_epoch IS NULL AND subject_stage_id IS NULL
            AND subject_stage_generation IS NULL AND subject_attempt IS NULL
            AND subject_expected_code_fingerprint IS NULL
            AND subject_expected_head_sha IS NULL
            AND subject_expected_base_sha IS NULL)
        OR (subject_operation_id IS NOT NULL AND length(subject_operation_id) > 0
            AND subject_task_epoch IS NOT NULL AND subject_attempt > 0
            AND ((subject_stage_id IS NULL AND subject_stage_generation = 0)
                OR (subject_stage_id IS NOT NULL
                    AND subject_stage_generation > 0)))),
    CHECK ((next_stage_id IS NULL AND next_stage_kind IS NULL
            AND next_stage_generation IS NULL)
        OR (next_stage_id IS NOT NULL AND next_stage_kind IS NOT NULL
            AND next_stage_generation IS NOT NULL)),
    CHECK ((returned_pending_operation_id IS NULL
            AND returned_pending_task_epoch IS NULL
            AND returned_pending_stage_id IS NULL
            AND returned_pending_stage_generation IS NULL
            AND returned_pending_attempt IS NULL
            AND returned_pending_code_fingerprint IS NULL
            AND returned_pending_head_sha IS NULL
            AND returned_pending_base_sha IS NULL)
        OR (returned_pending_operation_id IS NOT NULL
            AND length(returned_pending_operation_id) > 0
            AND returned_pending_task_epoch IS NOT NULL
            AND returned_pending_attempt > 0
            AND ((returned_pending_stage_id IS NULL
                    AND returned_pending_stage_generation = 0)
                OR (returned_pending_stage_id IS NOT NULL
                    AND returned_pending_stage_generation > 0)))),
    CHECK ((returned_last_brain_operation_id IS NULL
            AND returned_last_brain_verdict IS NULL
            AND returned_last_brain_task_epoch IS NULL
            AND returned_last_brain_stage_id IS NULL
            AND returned_last_brain_stage_generation IS NULL
            AND returned_last_brain_attempt IS NULL
            AND returned_last_brain_code_fingerprint IS NULL
            AND returned_last_brain_head_sha IS NULL
            AND returned_last_brain_base_sha IS NULL)
        OR (returned_last_brain_operation_id IS NOT NULL
            AND length(returned_last_brain_operation_id) > 0
            AND returned_last_brain_verdict IS NOT NULL
            AND returned_last_brain_task_epoch IS NOT NULL
            AND returned_last_brain_attempt > 0
            AND ((returned_last_brain_stage_id IS NULL
                    AND returned_last_brain_stage_generation = 0)
                OR (returned_last_brain_stage_id IS NOT NULL
                    AND returned_last_brain_stage_generation > 0)))),
    CHECK ((cause = 'ACCEPT_BRAIN_VERDICT') = (brain_verdict IS NOT NULL)),
    CHECK ((proof_id IS NOT NULL) = (cause IN (
        'COMPLETE_PAUSE', 'COMPLETE_RESUME', 'COMPLETE_ARCHIVE',
        'ACCEPT_PROVISIONING', 'OPEN_REPLAN_PLAN', 'OPEN_CANCELED_CLEANUP',
        'OPEN_MERGED_CLEANUP', 'OPEN_REMOTE_CLOSED_CLEANUP',
        'OPEN_LOCAL_DEVELOPMENT'))),
    CHECK ((next_stage_id IS NOT NULL) = (cause IN (
        'ACCEPT_PROVISIONING', 'OPEN_REPLAN_PLAN', 'OPEN_CANCELED_CLEANUP',
        'OPEN_MERGED_CLEANUP', 'OPEN_REMOTE_CLOSED_CLEANUP',
        'OPEN_LOCAL_DEVELOPMENT', 'OPEN_REMOTE_DEVELOPMENT'))),
    CHECK ((subject_operation_id IS NOT NULL) = (cause IN (
        'ACCEPT_PROVISIONING', 'ACCEPT_BRAIN_VERDICT',
        'ACCEPT_CLEANUP_COMPLETION', 'OPEN_REMOTE_DEVELOPMENT'))),
    CHECK (cause NOT IN ('ACCEPT_PROVISIONING', 'OPEN_REPLAN_PLAN')
        OR next_stage_kind = 'PLAN'),
    CHECK (cause NOT IN ('OPEN_CANCELED_CLEANUP', 'OPEN_MERGED_CLEANUP',
            'OPEN_REMOTE_CLOSED_CLEANUP')
        OR next_stage_kind = 'CLEANUP'),
    CHECK (cause <> 'OPEN_LOCAL_DEVELOPMENT'
        OR next_stage_kind = 'LOCAL_DEVELOPMENT'),
    CHECK (cause <> 'OPEN_REMOTE_DEVELOPMENT'
        OR next_stage_kind = 'REMOTE_DEVELOPMENT'),
    CHECK (disposition <> 'SUPERSEDED' OR cause = 'ACCEPT_BRAIN_VERDICT'),
    CHECK (disposition <> 'SUPERSEDED' OR subject_operation_id IS NOT NULL),
    CHECK (returned_lifecycle NOT IN (
            'PROVISIONING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
        OR returned_current_stage_id IS NULL),
    CHECK (returned_lifecycle IN (
            'PROVISIONING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
        OR returned_current_stage_id IS NOT NULL),
    CHECK ((returned_lifecycle IN ('CANCELING', 'CLEANING'))
        = (returned_terminal_intent IS NOT NULL)
        OR returned_lifecycle IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')),
    CHECK (returned_lifecycle NOT IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
        OR returned_terminal_intent = returned_lifecycle),
    CHECK (returned_lifecycle NOT IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
        OR returned_pending_operation_id IS NULL),
    FOREIGN KEY (returned_current_stage_id, task_id)
        REFERENCES stage(id, task_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (next_stage_id, task_id, next_stage_kind, next_stage_generation)
        REFERENCES stage(id, task_id, kind, generation)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER task_command_receipt_insert
BEFORE INSERT ON task_command_receipt
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks task
            WHERE task.id = NEW.task_id
              AND task.thread_id = NEW.returned_trunk_id
              AND task.workflow_version = 'V2')
            THEN RAISE(ABORT, 'Task command receipt requires a V2 Task')
        WHEN NEW.subject_stage_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM stage subject
            WHERE subject.id = NEW.subject_stage_id
              AND subject.task_id = NEW.task_id
              AND subject.generation = NEW.subject_stage_generation)
            THEN RAISE(ABORT, 'Task receipt subject Stage is invalid')
        WHEN NEW.disposition = 'APPLIED' AND NOT EXISTS (
            SELECT 1 FROM task_transition transition
            WHERE transition.task_id = NEW.task_id
              AND transition.command_id = NEW.command_id
              AND transition.epoch = NEW.returned_epoch
              AND transition.to_state = NEW.returned_lifecycle
              AND transition.aggregate_version = NEW.returned_version
              AND transition.cause = NEW.cause AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'applied Task receipt lacks its exact transition')
        WHEN NEW.cause = 'ACCEPT_PROVISIONING' AND NOT EXISTS (
            SELECT 1 FROM provision_task_operation operation
            WHERE operation.task_id = NEW.task_id
              AND operation.operation_id = NEW.proof_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.result_code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.result_head_sha IS NEW.subject_expected_head_sha
              AND operation.result_base_sha IS NEW.subject_expected_base_sha
              AND operation.status = 'ACCEPTED'
              AND NEW.subject_stage_id IS NULL
              AND NEW.subject_stage_generation = 0
              AND NEW.next_stage_kind = 'PLAN')
            THEN RAISE(ABORT, 'Task receipt provisioning proof is invalid')
        WHEN NEW.cause = 'OPEN_REPLAN_PLAN' AND NOT EXISTS (
            SELECT 1
            FROM task_replan_request request
            JOIN task_quiescence_barrier barrier
              ON barrier.id = request.quiescence_barrier_id
            WHERE request.id = NEW.proof_id
              AND request.task_id = NEW.task_id
              AND request.status IN ('QUIESCING', 'APPLIED')
              AND barrier.task_id = NEW.task_id
              AND barrier.status = 'SATISFIED'
              AND NEW.next_stage_kind = 'PLAN'
              AND (request.new_plan_stage_id IS NULL
                OR (NEW.next_stage_id = request.new_plan_stage_id
                    AND NEW.next_stage_generation = request.new_plan_generation)))
            THEN RAISE(ABORT, 'Task receipt replan proof is invalid')
        WHEN NEW.cause IN ('COMPLETE_PAUSE', 'OPEN_CANCELED_CLEANUP')
          AND NOT EXISTS (
            SELECT 1 FROM task_quiescence_barrier barrier
            WHERE barrier.id = NEW.proof_id
              AND barrier.task_id = NEW.task_id
              AND barrier.status = 'SATISFIED'
              AND ((NEW.cause = 'COMPLETE_PAUSE' AND barrier.reason = 'PAUSE')
                OR (NEW.cause = 'OPEN_CANCELED_CLEANUP'
                    AND barrier.reason = 'CANCEL'
                    AND NEW.next_stage_kind = 'CLEANUP')))
            THEN RAISE(ABORT, 'Task receipt quiescence proof is invalid')
        WHEN NEW.cause = 'OPEN_LOCAL_DEVELOPMENT' AND NOT EXISTS (
            SELECT 1
            FROM plan_approval approval
            JOIN plan_revision revision ON revision.id = approval.plan_revision_id
            JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
            WHERE approval.id = NEW.proof_id
              AND plan.task_id = NEW.task_id
              AND NEW.next_stage_kind = 'LOCAL_DEVELOPMENT')
            THEN RAISE(ABORT, 'Task receipt Plan approval proof is invalid')
        WHEN NEW.cause = 'OPEN_REMOTE_DEVELOPMENT' AND NOT EXISTS (
            SELECT 1
            FROM publish_operation operation
            JOIN remote_pr_binding binding
              ON binding.publish_operation_id = operation.id
            WHERE operation.task_id = NEW.task_id
              AND operation.local_development_stage_id = NEW.subject_stage_id
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.stage_generation = NEW.subject_stage_generation
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.expected_head_sha IS NEW.subject_expected_head_sha
              AND operation.expected_base_sha IS NEW.subject_expected_base_sha
              AND operation.status = 'SUCCEEDED'
              AND binding.task_id = NEW.task_id
              AND NEW.next_stage_kind = 'REMOTE_DEVELOPMENT')
            THEN RAISE(ABORT, 'Task receipt publish-result proof is invalid')
        WHEN NEW.cause = 'ACCEPT_BRAIN_VERDICT' AND NOT EXISTS (
            SELECT 1 FROM brain_review_episode brain
            JOIN task_turn turn ON turn.id = brain.task_turn_id
            WHERE brain.task_id = NEW.task_id
              AND brain.task_epoch = NEW.subject_task_epoch
              AND brain.local_development_stage_id IS NEW.subject_stage_id
              AND brain.stage_generation = NEW.subject_stage_generation
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND brain.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND brain.expected_head_sha IS NEW.subject_expected_head_sha
              AND brain.expected_base_sha IS NEW.subject_expected_base_sha
              AND brain.verdict = NEW.brain_verdict
              AND brain.status = 'SUCCEEDED')
            THEN RAISE(ABORT, 'Task receipt Brain proof is invalid')
    END;
END;

CREATE TRIGGER task_command_receipt_immutable
BEFORE UPDATE ON task_command_receipt
BEGIN SELECT RAISE(ABORT, 'Task command receipt is immutable'); END;

CREATE TABLE stage_command_receipt (
    id                                TEXT    NOT NULL PRIMARY KEY,
    stage_id                          TEXT    NOT NULL,
    task_id                           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id                        TEXT    NOT NULL,
    cause                             TEXT    NOT NULL CHECK (cause IN (
        'OPEN_INITIAL_PLAN', 'OPEN_REPLAN_PLAN', 'ACCEPT_DRAFTED',
        'ACCEPT_PLAN_BRAIN_FINDINGS', 'ACCEPT_PLAN_BRAIN_APPROVAL',
        'REVISE_BEFORE_APPROVAL', 'APPROVE_PLAN', 'OPEN_LOCAL_DEVELOPMENT',
        'ACCEPT_IMPLEMENTATION', 'ACCEPT_VALIDATION', 'ACCEPT_BRAIN_APPROVAL',
        'ACCEPT_BRAIN_FINDINGS', 'ACCEPT_BRAIN_FIXES', 'SUBMIT_LOCAL_FEEDBACK',
        'AUTHORIZE_PUBLISH', 'ACCEPT_LOCAL_FEEDBACK_FIXES', 'ACCEPT_PUBLISHED',
        'OPEN_REMOTE_DEVELOPMENT', 'ACCEPT_CI', 'ACCEPT_READY',
        'BEGIN_REMOTE_FEEDBACK', 'ACCEPT_REMOTE_FEEDBACK_PUSH',
        'ACCEPT_READINESS', 'AUTHORIZE_MERGE', 'ACCEPT_REMOTE_MERGED',
        'ACCEPT_REMOTE_CLOSED', 'SEAL_FOR_REPLAN',
        'SEAL_FOR_TASK_CANCELLATION', 'OPEN_CANCELED_CLEANUP',
        'OPEN_TERMINAL_CLEANUP', 'ACCEPT_CLEANUP_QUIESCENCE',
        'ACCEPT_CLEANUP_COMPLETE')),
    actor                             TEXT    NOT NULL,
    disposition                       TEXT    NOT NULL CHECK (disposition IN (
        'APPLIED', 'SUPERSEDED')),

    -- Structural commands carry these; result commands and Stage creation do
    -- not invent a current version/checkpoint fence.
    expected_task_epoch               INTEGER CHECK (expected_task_epoch > 0),
    expected_stage_generation         INTEGER CHECK (expected_stage_generation > 0),
    expected_stage_version            INTEGER CHECK (expected_stage_version >= 0),
    source_checkpoint                 TEXT,

    -- Optional command ResultFence, including operation attempt.
    subject_task_epoch                INTEGER CHECK (subject_task_epoch > 0),
    subject_stage_id                  TEXT,
    subject_stage_generation          INTEGER,
    subject_operation_id              TEXT,
    subject_attempt                   INTEGER,
    subject_expected_code_fingerprint TEXT,
    subject_expected_head_sha         TEXT,
    subject_expected_base_sha         TEXT,
    proof_id                          TEXT,

    -- Exact returned StageManager.State.
    returned_kind                     TEXT    NOT NULL CHECK (returned_kind IN (
        'PLAN', 'LOCAL_DEVELOPMENT', 'REMOTE_DEVELOPMENT', 'CLEANUP')),
    returned_generation               INTEGER NOT NULL CHECK (returned_generation > 0),
    returned_version                  INTEGER NOT NULL CHECK (returned_version >= 0),
    returned_checkpoint               TEXT    NOT NULL,
    returned_end_reason               TEXT CHECK (returned_end_reason IN (
        'NORMAL', 'SUPERSEDED_BY_REPLAN', 'TASK_CANCELED',
        'REMOTE_MERGED', 'REMOTE_CLOSED')),
    returned_pending_task_epoch INTEGER CHECK (returned_pending_task_epoch > 0),
    returned_pending_stage_id   TEXT,
    returned_pending_stage_generation INTEGER,
    returned_pending_operation_id TEXT,
    returned_pending_attempt     INTEGER,
    returned_pending_code_fingerprint TEXT,
    returned_pending_head_sha    TEXT,
    returned_pending_base_sha    TEXT,
    recorded_at_ms               INTEGER NOT NULL,
    UNIQUE (stage_id, command_id),
    CHECK ((expected_task_epoch IS NULL
            AND expected_stage_generation IS NULL
            AND expected_stage_version IS NULL)
        OR (expected_task_epoch IS NOT NULL
            AND expected_stage_generation IS NOT NULL
            AND expected_stage_version IS NOT NULL)),
    CHECK (source_checkpoint IS NULL OR expected_task_epoch IS NOT NULL),
    CHECK (expected_stage_version IS NULL
        OR returned_version = expected_stage_version + 1),
    CHECK ((subject_operation_id IS NULL
            AND subject_task_epoch IS NULL AND subject_stage_id IS NULL
            AND subject_stage_generation IS NULL AND subject_attempt IS NULL
            AND subject_expected_code_fingerprint IS NULL
            AND subject_expected_head_sha IS NULL
            AND subject_expected_base_sha IS NULL)
        OR (subject_operation_id IS NOT NULL AND length(subject_operation_id) > 0
            AND subject_task_epoch IS NOT NULL AND subject_attempt > 0
            AND ((subject_stage_id IS NULL AND subject_stage_generation = 0)
                OR (subject_stage_id IS NOT NULL
                    AND subject_stage_generation > 0)))),
    CHECK ((returned_pending_operation_id IS NULL
            AND returned_pending_task_epoch IS NULL
            AND returned_pending_stage_id IS NULL
            AND returned_pending_stage_generation IS NULL
            AND returned_pending_attempt IS NULL
            AND returned_pending_code_fingerprint IS NULL
            AND returned_pending_head_sha IS NULL
            AND returned_pending_base_sha IS NULL)
        OR (returned_pending_operation_id IS NOT NULL
            AND length(returned_pending_operation_id) > 0
            AND returned_pending_task_epoch IS NOT NULL
            AND returned_pending_attempt > 0
            AND returned_pending_stage_id = stage_id
            AND returned_pending_stage_generation = returned_generation)),
    CHECK (returned_checkpoint <> 'COMPLETED'
        OR returned_end_reason IS NOT NULL),
    CHECK (returned_checkpoint = 'COMPLETED'
        OR returned_end_reason IS NULL
        OR returned_end_reason IN ('SUPERSEDED_BY_REPLAN', 'TASK_CANCELED')),
    CHECK (returned_end_reason NOT IN ('NORMAL', 'REMOTE_MERGED', 'REMOTE_CLOSED')
        OR returned_checkpoint = 'COMPLETED'),
    CHECK (returned_end_reason NOT IN ('REMOTE_MERGED', 'REMOTE_CLOSED')
        OR returned_kind = 'REMOTE_DEVELOPMENT'),
    CHECK (returned_end_reason IS NULL
        OR returned_pending_operation_id IS NULL),
    CHECK (returned_kind <> 'PLAN' OR returned_checkpoint IN (
        'DRAFTING', 'SELF_REVIEW', 'AWAITING_APPROVAL', 'COMPLETED')),
    CHECK (returned_kind <> 'LOCAL_DEVELOPMENT' OR returned_checkpoint IN (
        'IMPLEMENTING', 'VALIDATING', 'BRAIN_REVIEW', 'LOCAL_REVIEW',
        'PUBLISHING', 'ADDRESSING_BRAIN_FINDINGS',
        'ADDRESSING_LOCAL_FEEDBACK', 'COMPLETED')),
    CHECK (returned_kind <> 'REMOTE_DEVELOPMENT' OR returned_checkpoint IN (
        'WAITING_CI', 'AWAITING_READY', 'WAITING_REMOTE_REVIEW',
        'ADDRESSING_REMOTE_FEEDBACK', 'READY_TO_MERGE', 'MERGING', 'COMPLETED')),
    CHECK (returned_kind <> 'CLEANUP' OR returned_checkpoint IN (
        'WAITING_QUIESCENCE', 'CLEANING', 'COMPLETED')),
    CHECK ((proof_id IS NOT NULL) = (cause IN (
        'OPEN_INITIAL_PLAN', 'OPEN_REPLAN_PLAN', 'REVISE_BEFORE_APPROVAL',
        'APPROVE_PLAN', 'OPEN_LOCAL_DEVELOPMENT', 'SUBMIT_LOCAL_FEEDBACK',
        'AUTHORIZE_PUBLISH', 'BEGIN_REMOTE_FEEDBACK', 'AUTHORIZE_MERGE',
        'ACCEPT_REMOTE_MERGED', 'ACCEPT_REMOTE_CLOSED', 'SEAL_FOR_REPLAN',
        'SEAL_FOR_TASK_CANCELLATION', 'OPEN_CANCELED_CLEANUP',
        'OPEN_TERMINAL_CLEANUP', 'ACCEPT_CLEANUP_QUIESCENCE'))),
    CHECK ((subject_operation_id IS NOT NULL) = (cause IN (
        'OPEN_INITIAL_PLAN', 'ACCEPT_DRAFTED',
        'ACCEPT_PLAN_BRAIN_FINDINGS', 'ACCEPT_PLAN_BRAIN_APPROVAL',
        'ACCEPT_IMPLEMENTATION', 'ACCEPT_VALIDATION', 'ACCEPT_BRAIN_APPROVAL',
        'ACCEPT_BRAIN_FINDINGS', 'ACCEPT_BRAIN_FIXES', 'AUTHORIZE_PUBLISH',
        'ACCEPT_LOCAL_FEEDBACK_FIXES', 'ACCEPT_PUBLISHED',
        'OPEN_REMOTE_DEVELOPMENT', 'ACCEPT_CI', 'ACCEPT_READY',
        'ACCEPT_REMOTE_FEEDBACK_PUSH', 'ACCEPT_READINESS', 'AUTHORIZE_MERGE',
        'ACCEPT_CLEANUP_COMPLETE'))),
    CHECK (cause NOT IN (
        'OPEN_INITIAL_PLAN', 'OPEN_REPLAN_PLAN', 'OPEN_LOCAL_DEVELOPMENT',
        'OPEN_REMOTE_DEVELOPMENT', 'OPEN_CANCELED_CLEANUP',
        'OPEN_TERMINAL_CLEANUP', 'ACCEPT_DRAFTED',
        'ACCEPT_PLAN_BRAIN_FINDINGS', 'ACCEPT_PLAN_BRAIN_APPROVAL',
        'ACCEPT_IMPLEMENTATION', 'ACCEPT_VALIDATION', 'ACCEPT_BRAIN_APPROVAL',
        'ACCEPT_BRAIN_FINDINGS', 'ACCEPT_BRAIN_FIXES',
        'ACCEPT_LOCAL_FEEDBACK_FIXES', 'ACCEPT_PUBLISHED', 'ACCEPT_CI',
        'ACCEPT_READY', 'ACCEPT_REMOTE_FEEDBACK_PUSH', 'ACCEPT_READINESS',
        'ACCEPT_CLEANUP_COMPLETE')
        OR expected_task_epoch IS NULL),
    CHECK (cause IN (
        'OPEN_INITIAL_PLAN', 'OPEN_REPLAN_PLAN', 'OPEN_LOCAL_DEVELOPMENT',
        'OPEN_REMOTE_DEVELOPMENT', 'OPEN_CANCELED_CLEANUP',
        'OPEN_TERMINAL_CLEANUP', 'ACCEPT_DRAFTED',
        'ACCEPT_PLAN_BRAIN_FINDINGS', 'ACCEPT_PLAN_BRAIN_APPROVAL',
        'ACCEPT_IMPLEMENTATION', 'ACCEPT_VALIDATION', 'ACCEPT_BRAIN_APPROVAL',
        'ACCEPT_BRAIN_FINDINGS', 'ACCEPT_BRAIN_FIXES',
        'ACCEPT_LOCAL_FEEDBACK_FIXES', 'ACCEPT_PUBLISHED', 'ACCEPT_CI',
        'ACCEPT_READY', 'ACCEPT_REMOTE_FEEDBACK_PUSH', 'ACCEPT_READINESS',
        'ACCEPT_CLEANUP_COMPLETE')
        OR expected_task_epoch IS NOT NULL),
    CHECK ((source_checkpoint IS NOT NULL) = (cause IN (
        'REVISE_BEFORE_APPROVAL', 'APPROVE_PLAN', 'SUBMIT_LOCAL_FEEDBACK',
        'AUTHORIZE_PUBLISH', 'BEGIN_REMOTE_FEEDBACK', 'AUTHORIZE_MERGE',
        'ACCEPT_CLEANUP_QUIESCENCE'))),
    CHECK (source_checkpoint IS NULL
        OR (cause IN ('REVISE_BEFORE_APPROVAL', 'APPROVE_PLAN')
            AND source_checkpoint = 'AWAITING_APPROVAL')
        OR (cause IN ('SUBMIT_LOCAL_FEEDBACK', 'AUTHORIZE_PUBLISH')
            AND source_checkpoint = 'LOCAL_REVIEW')
        OR (cause = 'BEGIN_REMOTE_FEEDBACK'
            AND source_checkpoint = 'WAITING_REMOTE_REVIEW')
        OR (cause = 'AUTHORIZE_MERGE'
            AND source_checkpoint = 'READY_TO_MERGE')
        OR (cause = 'ACCEPT_CLEANUP_QUIESCENCE'
            AND source_checkpoint = 'WAITING_QUIESCENCE')),
    CHECK (disposition <> 'SUPERSEDED' OR subject_operation_id IS NOT NULL),
    FOREIGN KEY (stage_id, task_id, returned_kind, returned_generation)
        REFERENCES stage(id, task_id, kind, generation)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TRIGGER stage_command_receipt_insert
BEFORE INSERT ON stage_command_receipt
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM stage stage
            JOIN tasks task ON task.id = stage.task_id
            WHERE stage.id = NEW.stage_id
              AND stage.task_id = NEW.task_id
              AND stage.kind = NEW.returned_kind
              AND stage.generation = NEW.returned_generation
              AND task.workflow_version = 'V2')
            THEN RAISE(ABORT, 'Stage command receipt requires its exact V2 owner')
        WHEN NEW.disposition = 'APPLIED' AND NOT EXISTS (
            SELECT 1 FROM stage_transition transition
            WHERE transition.stage_id = NEW.stage_id
              AND transition.command_id = NEW.command_id
              AND transition.generation = NEW.returned_generation
              AND transition.to_checkpoint = NEW.returned_checkpoint
              AND transition.stage_version = NEW.returned_version
              AND transition.cause = NEW.cause AND transition.actor = NEW.actor)
            THEN RAISE(ABORT, 'applied Stage receipt lacks its exact transition')
        WHEN NEW.cause = 'SEAL_FOR_TASK_CANCELLATION' AND NOT EXISTS (
            SELECT 1 FROM task_quiescence_barrier barrier
            WHERE barrier.id = NEW.proof_id
              AND barrier.task_id = NEW.task_id
              AND barrier.task_epoch = NEW.expected_task_epoch
              AND barrier.reason = 'CANCEL'
              AND barrier.status = 'SATISFIED'
              AND NEW.expected_stage_generation = NEW.returned_generation
              AND NEW.returned_end_reason = 'TASK_CANCELED'
              AND NEW.source_checkpoint IS NULL)
            THEN RAISE(ABORT, 'Stage cancellation seal proof is invalid')
        WHEN NEW.cause = 'OPEN_CANCELED_CLEANUP' AND NOT EXISTS (
            SELECT 1
            FROM task_quiescence_barrier barrier
            JOIN task_command_receipt task_receipt
              ON task_receipt.task_id = barrier.task_id
            WHERE barrier.id = NEW.proof_id
              AND barrier.task_id = NEW.task_id
              AND barrier.reason = 'CANCEL'
              AND barrier.status = 'SATISFIED'
              AND task_receipt.command_id = NEW.command_id
              AND task_receipt.cause = 'OPEN_CANCELED_CLEANUP'
              AND task_receipt.actor = NEW.actor
              AND task_receipt.disposition = 'APPLIED'
              AND task_receipt.expected_task_epoch = barrier.task_epoch
              AND task_receipt.proof_id = barrier.id
              AND task_receipt.next_stage_id = NEW.stage_id
              AND task_receipt.next_stage_kind = 'CLEANUP'
              AND task_receipt.next_stage_generation = NEW.returned_generation
              AND task_receipt.returned_current_stage_id = NEW.stage_id
              AND task_receipt.returned_lifecycle = 'CLEANING'
              AND task_receipt.returned_terminal_intent = 'CANCELED'
              AND NEW.returned_kind = 'CLEANUP'
              AND NEW.returned_version = 0
              AND NEW.returned_checkpoint = 'WAITING_QUIESCENCE')
            THEN RAISE(ABORT, 'canceled Cleanup must open waiting on its exact barrier')
        WHEN NEW.cause = 'ACCEPT_CLEANUP_QUIESCENCE' AND NOT EXISTS (
            SELECT 1 FROM task_quiescence_barrier barrier
            WHERE barrier.id = NEW.proof_id
              AND barrier.task_id = NEW.task_id
              AND barrier.task_epoch = NEW.expected_task_epoch
              AND barrier.reason IN ('CANCEL', 'CLEANUP')
              AND barrier.status = 'SATISFIED'
              AND NEW.expected_stage_generation = NEW.returned_generation
              AND NEW.source_checkpoint = 'WAITING_QUIESCENCE'
              AND NEW.returned_kind = 'CLEANUP'
              AND NEW.returned_version = NEW.expected_stage_version + 1
              AND NEW.returned_checkpoint = 'CLEANING'
              AND (barrier.reason <> 'CANCEL' OR EXISTS (
                  SELECT 1 FROM stage_command_receipt opening
                  WHERE opening.stage_id = NEW.stage_id
                    AND opening.task_id = NEW.task_id
                    AND opening.cause = 'OPEN_CANCELED_CLEANUP'
                    AND opening.proof_id = barrier.id
                    AND opening.returned_generation = NEW.returned_generation
                    AND opening.returned_version = 0
                    AND opening.returned_checkpoint = 'WAITING_QUIESCENCE')))
            THEN RAISE(ABORT, 'Cleanup quiescence receipt lacks its exact barrier')
        WHEN NEW.cause = 'OPEN_INITIAL_PLAN' AND NOT EXISTS (
            SELECT 1 FROM provision_task_operation operation
            WHERE operation.task_id = NEW.task_id
              AND operation.operation_id = NEW.proof_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.result_code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.result_head_sha IS NEW.subject_expected_head_sha
              AND operation.result_base_sha IS NEW.subject_expected_base_sha
              AND operation.status = 'ACCEPTED'
              AND NEW.returned_kind = 'PLAN'
              AND NEW.returned_checkpoint = 'DRAFTING')
            THEN RAISE(ABORT, 'Stage receipt initial Plan proof is invalid')
        WHEN NEW.cause = 'OPEN_REPLAN_PLAN' AND NOT EXISTS (
            SELECT 1 FROM task_replan_request request
            JOIN task_quiescence_barrier barrier
              ON barrier.id = request.quiescence_barrier_id
            WHERE request.id = NEW.proof_id
              AND request.task_id = NEW.task_id
              AND barrier.status = 'SATISFIED'
              AND NEW.returned_kind = 'PLAN'
              AND NEW.returned_checkpoint = 'DRAFTING')
            THEN RAISE(ABORT, 'Stage receipt replan proof is invalid')
        WHEN NEW.cause = 'OPEN_LOCAL_DEVELOPMENT' AND NOT EXISTS (
            SELECT 1 FROM plan_approval approval
            JOIN plan_revision revision ON revision.id = approval.plan_revision_id
            JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
            WHERE approval.id = NEW.proof_id
              AND plan.task_id = NEW.task_id
              AND NEW.returned_kind = 'LOCAL_DEVELOPMENT'
              AND NEW.returned_checkpoint = 'IMPLEMENTING')
            THEN RAISE(ABORT, 'Stage receipt Local opening proof is invalid')
        WHEN NEW.cause = 'REVISE_BEFORE_APPROVAL' AND NOT EXISTS (
            SELECT 1 FROM plan_revision revision
            JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
            WHERE revision.id = NEW.proof_id
              AND plan.task_id = NEW.task_id
              AND plan.stage_id = NEW.stage_id
              AND plan.generation = NEW.returned_generation)
            THEN RAISE(ABORT, 'Stage receipt Plan revision proof is invalid')
        WHEN NEW.cause = 'APPROVE_PLAN' AND NOT EXISTS (
            SELECT 1 FROM plan_approval approval
            JOIN plan_revision revision ON revision.id = approval.plan_revision_id
            JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
            WHERE approval.id = NEW.proof_id
              AND plan.task_id = NEW.task_id
              AND plan.stage_id = NEW.stage_id
              AND plan.generation = NEW.returned_generation)
            THEN RAISE(ABORT, 'Stage receipt Plan approval proof is invalid')
        WHEN NEW.cause = 'ACCEPT_IMPLEMENTATION' AND NOT EXISTS (
            SELECT 1 FROM dev_report report
            JOIN stage_turn turn ON turn.id = report.stage_turn_id
            WHERE report.workflow_version = 'V2'
              AND report.task_id = NEW.task_id
              AND report.local_development_stage_id = NEW.stage_id
              AND report.task_epoch = NEW.subject_task_epoch
              AND report.stage_generation = NEW.subject_stage_generation
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND report.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND report.head_sha IS NEW.subject_expected_head_sha
              AND report.base_sha IS NEW.subject_expected_base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = report.local_development_stage_id
                    AND newer.revision > report.revision))
            THEN RAISE(ABORT, 'Stage receipt implementation proof is invalid')
        WHEN NEW.cause = 'ACCEPT_VALIDATION' AND NOT EXISTS (
            SELECT 1 FROM validation_evidence evidence
            JOIN validation_operation operation
              ON operation.id = evidence.validation_operation_id
            WHERE evidence.task_id = NEW.task_id
              AND evidence.task_epoch = NEW.subject_task_epoch
              AND evidence.stage_id = NEW.stage_id
              AND evidence.stage_generation = NEW.subject_stage_generation
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.semantic_attempt = NEW.subject_attempt
              AND evidence.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND evidence.head_sha IS NEW.subject_expected_head_sha
              AND evidence.base_sha IS NEW.subject_expected_base_sha
              AND evidence.passed = 1
              AND operation.status = 'COMPLETED')
            THEN RAISE(ABORT, 'Stage receipt validation proof is invalid')
        WHEN NEW.cause IN ('ACCEPT_BRAIN_APPROVAL', 'ACCEPT_BRAIN_FINDINGS')
          AND NOT EXISTS (
            SELECT 1 FROM brain_review_episode brain
            JOIN task_turn turn ON turn.id = brain.task_turn_id
            WHERE brain.task_id = NEW.task_id
              AND brain.task_epoch = NEW.subject_task_epoch
              AND brain.local_development_stage_id = NEW.stage_id
              AND brain.stage_generation = NEW.subject_stage_generation
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND brain.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND brain.expected_head_sha IS NEW.subject_expected_head_sha
              AND brain.expected_base_sha IS NEW.subject_expected_base_sha
              AND brain.status = 'SUCCEEDED'
              AND ((NEW.cause = 'ACCEPT_BRAIN_APPROVAL'
                    AND brain.verdict = 'APPROVED')
                OR (NEW.cause = 'ACCEPT_BRAIN_FINDINGS'
                    AND brain.verdict = 'CHANGES_REQUESTED')))
            THEN RAISE(ABORT, 'Stage receipt Brain proof is invalid')
        WHEN NEW.cause IN ('ACCEPT_BRAIN_FIXES', 'ACCEPT_LOCAL_FEEDBACK_FIXES')
          AND NOT EXISTS (
            SELECT 1 FROM stage_turn turn
            WHERE turn.stage_id = NEW.stage_id
              AND turn.stage_generation = NEW.subject_stage_generation
              AND turn.task_epoch = NEW.subject_task_epoch
              AND turn.operation_id = NEW.subject_operation_id
              AND turn.attempt = NEW.subject_attempt
              AND turn.expected_code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND turn.expected_head_sha IS NEW.subject_expected_head_sha
              AND turn.expected_base_sha IS NEW.subject_expected_base_sha
              AND turn.status = 'SUCCEEDED')
            THEN RAISE(ABORT, 'Stage receipt fix result proof is invalid')
        WHEN NEW.cause = 'SUBMIT_LOCAL_FEEDBACK' AND NOT EXISTS (
            SELECT 1 FROM local_feedback_batch batch
            WHERE batch.id = NEW.proof_id
              AND batch.task_id = NEW.task_id
              AND batch.local_development_stage_id = NEW.stage_id
              AND batch.stage_generation = NEW.returned_generation
              AND batch.status IN ('FROZEN', 'DISPATCHED', 'ADDRESSED'))
            THEN RAISE(ABORT, 'Stage receipt feedback proof is invalid')
        WHEN NEW.cause = 'AUTHORIZE_PUBLISH' AND NOT EXISTS (
            SELECT 1 FROM publish_authorization authorization
            WHERE authorization.id = NEW.proof_id
              AND authorization.task_id = NEW.task_id
              AND authorization.task_epoch = NEW.subject_task_epoch
              AND authorization.local_development_stage_id = NEW.stage_id
              AND authorization.stage_generation = NEW.subject_stage_generation
              AND authorization.authorized_operation_id = NEW.subject_operation_id
              AND authorization.authorized_attempt = NEW.subject_attempt
              AND authorization.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND authorization.head_sha IS NEW.subject_expected_head_sha
              AND authorization.base_sha IS NEW.subject_expected_base_sha
              AND authorization.revoked_at_ms IS NULL
              AND authorization.consumed_at_ms IS NULL
              AND NEW.returned_pending_task_epoch IS NEW.subject_task_epoch
              AND NEW.returned_pending_stage_id IS NEW.subject_stage_id
              AND NEW.returned_pending_stage_generation
                    IS NEW.subject_stage_generation
              AND NEW.returned_pending_operation_id IS NEW.subject_operation_id
              AND NEW.returned_pending_attempt IS NEW.subject_attempt
              AND NEW.returned_pending_code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND NEW.returned_pending_head_sha IS NEW.subject_expected_head_sha
              AND NEW.returned_pending_base_sha IS NEW.subject_expected_base_sha)
            THEN RAISE(ABORT, 'Stage receipt publish authorization proof is invalid')
        WHEN NEW.cause = 'OPEN_REMOTE_DEVELOPMENT' AND NOT EXISTS (
            SELECT 1 FROM remote_pr_binding binding
            JOIN publish_operation operation
              ON operation.id = binding.publish_operation_id
            WHERE binding.task_id = NEW.task_id
              AND operation.local_development_stage_id = NEW.subject_stage_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.stage_generation = NEW.subject_stage_generation
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.expected_head_sha IS NEW.subject_expected_head_sha
              AND operation.expected_base_sha IS NEW.subject_expected_base_sha
              AND operation.status = 'SUCCEEDED'
              AND NEW.returned_kind = 'REMOTE_DEVELOPMENT'
              AND NEW.returned_checkpoint = 'WAITING_CI')
            THEN RAISE(ABORT, 'Stage receipt remote opening proof is invalid')
        WHEN NEW.cause = 'ACCEPT_PUBLISHED' AND NOT EXISTS (
            SELECT 1 FROM remote_pr_binding binding
            JOIN publish_operation operation
              ON operation.id = binding.publish_operation_id
            WHERE binding.task_id = NEW.task_id
              AND operation.local_development_stage_id = NEW.stage_id
              AND operation.task_epoch = NEW.subject_task_epoch
              AND operation.stage_generation = NEW.subject_stage_generation
              AND operation.operation_id = NEW.subject_operation_id
              AND operation.semantic_attempt = NEW.subject_attempt
              AND operation.code_fingerprint
                    IS NEW.subject_expected_code_fingerprint
              AND operation.expected_head_sha IS NEW.subject_expected_head_sha
              AND operation.expected_base_sha IS NEW.subject_expected_base_sha
              AND operation.status = 'SUCCEEDED')
            THEN RAISE(ABORT, 'Stage receipt remote binding proof is invalid')
        WHEN NEW.returned_checkpoint = 'PUBLISHING'
              AND NEW.cause <> 'AUTHORIZE_PUBLISH'
            THEN RAISE(ABORT, 'LOCAL_REVIEW to PUBLISHING requires PublishAuthorization')
    END;
END;

CREATE TRIGGER stage_command_receipt_immutable
BEFORE UPDATE ON stage_command_receipt
BEGIN SELECT RAISE(ABORT, 'Stage command receipt is immutable'); END;

-- READY_TO_MERGE -> MERGING/terminal-observation proofs belong to the Remote
-- Development protocol, while resume/archive completion proofs belong to the
-- Task controls/Cleanup protocol. Their exact operations are not installed in
-- this local/publish migration; receipts must not replace those future typed
-- records with an unverified generic proof payload.
