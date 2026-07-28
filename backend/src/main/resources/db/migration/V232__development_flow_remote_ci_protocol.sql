-- Exact-head Remote Development, CI repair, and branch-sync persistence.
-- These records are additive and inert until V2 Remote Development routing is
-- enabled. Observations are immutable history; only the Remote Development
-- owner may advance its separately persisted current subject.

CREATE TABLE remote_development_stage (
    stage_id                       TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage(id) ON DELETE CASCADE,
    task_id                        TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    generation                     INTEGER NOT NULL CHECK (generation > 0),
    remote_pr_binding_id           TEXT    NOT NULL UNIQUE
        REFERENCES remote_pr_binding(id),
    accepted_snapshot_id           TEXT    UNIQUE
        REFERENCES remote_pr_snapshot(id) DEFERRABLE INITIALLY DEFERRED,
    accepted_observation_revision  INTEGER NOT NULL DEFAULT 0
        CHECK (accepted_observation_revision >= 0),
    current_head_sha               TEXT    NOT NULL,
    current_base_sha               TEXT    NOT NULL,
    subject_changed_at_ms          INTEGER NOT NULL,
    UNIQUE (stage_id, task_id, generation),
    UNIQUE (stage_id, current_head_sha, current_base_sha),
    CHECK ((accepted_snapshot_id IS NULL AND accepted_observation_revision = 0)
        OR (accepted_snapshot_id IS NOT NULL AND accepted_observation_revision > 0))
);

CREATE TRIGGER remote_development_stage_insert
BEFORE INSERT ON remote_development_stage
WHEN NOT EXISTS (
    SELECT 1
    FROM stage owner
    JOIN tasks task ON task.id = owner.task_id
    JOIN remote_pr_binding binding ON binding.task_id = task.id
    WHERE owner.id = NEW.stage_id
      AND owner.task_id = NEW.task_id
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = NEW.generation
      AND owner.completed_at_ms IS NULL
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state IN ('ACTIVE', 'PAUSING', 'PAUSED', 'RESUMING')
      AND binding.id = NEW.remote_pr_binding_id
      AND binding.remote_head_sha = NEW.current_head_sha
      AND binding.remote_base_sha = NEW.current_base_sha)
BEGIN SELECT RAISE(ABORT, 'Remote Development owner does not match exact V2 PR binding'); END;

CREATE TRIGGER remote_development_stage_identity_immutable
BEFORE UPDATE OF stage_id, task_id, generation, remote_pr_binding_id
        ON remote_development_stage
WHEN NEW.stage_id IS NOT OLD.stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.generation IS NOT OLD.generation
  OR NEW.remote_pr_binding_id IS NOT OLD.remote_pr_binding_id
BEGIN SELECT RAISE(ABORT, 'Remote Development owner identity is immutable'); END;

CREATE TABLE remote_pr_snapshot (
    id                      TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                 TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch              INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation        INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id    TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    observation_revision    INTEGER NOT NULL CHECK (observation_revision > 0),
    observation_key         TEXT    NOT NULL,
    remote_repository_id    TEXT    NOT NULL,
    remote_pr_number        INTEGER NOT NULL CHECK (remote_pr_number > 0),
    head_sha                TEXT    NOT NULL,
    base_sha                TEXT    NOT NULL,
    pr_state                TEXT    NOT NULL CHECK (pr_state IN (
        'DRAFT', 'OPEN', 'MERGED', 'CLOSED')),
    mergeability            TEXT    NOT NULL CHECK (mergeability IN (
        'UNKNOWN', 'MERGEABLE', 'CONFLICTING', 'BLOCKED')),
    merge_queue_state       TEXT    NOT NULL CHECK (merge_queue_state IN (
        'NONE', 'QUEUED', 'DEQUEUED', 'MERGED')),
    effective_approval_count INTEGER NOT NULL DEFAULT 0
        CHECK (effective_approval_count >= 0),
    write_approval_count    INTEGER NOT NULL DEFAULT 0
        CHECK (write_approval_count >= 0),
    changes_requested_count INTEGER NOT NULL DEFAULT 0
        CHECK (changes_requested_count >= 0),
    requested_reviewer_count INTEGER NOT NULL DEFAULT 0
        CHECK (requested_reviewer_count >= 0),
    unresolved_thread_count INTEGER NOT NULL DEFAULT 0
        CHECK (unresolved_thread_count >= 0),
    unresolved_comment_count INTEGER NOT NULL DEFAULT 0
        CHECK (unresolved_comment_count >= 0),
    observed_at_ms          INTEGER NOT NULL,
    raw_evidence            TEXT,
    UNIQUE (remote_development_stage_id, observation_revision),
    UNIQUE (remote_pr_binding_id, observation_key),
    UNIQUE (id, remote_development_stage_id, head_sha, base_sha)
);

CREATE TRIGGER remote_pr_snapshot_insert
BEFORE INSERT ON remote_pr_snapshot
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM remote_development_stage remote
            JOIN tasks task ON task.id = remote.task_id
            JOIN remote_pr_binding binding ON binding.id = remote.remote_pr_binding_id
            WHERE remote.stage_id = NEW.remote_development_stage_id
              AND remote.task_id = NEW.task_id
              AND remote.generation = NEW.stage_generation
              AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
              AND task.epoch = NEW.task_epoch
              AND binding.remote_repository_id = NEW.remote_repository_id
              AND binding.remote_pr_number = NEW.remote_pr_number)
            THEN RAISE(ABORT, 'Remote snapshot owner and binding fence is invalid')
        WHEN NEW.observation_revision <> COALESCE((
            SELECT MAX(snapshot.observation_revision) + 1
            FROM remote_pr_snapshot snapshot
            WHERE snapshot.remote_development_stage_id =
                NEW.remote_development_stage_id), 1)
            THEN RAISE(ABORT, 'Remote snapshot observation revision must be next')
        WHEN NEW.pr_state = 'MERGED' AND NEW.merge_queue_state NOT IN ('NONE', 'MERGED')
            THEN RAISE(ABORT, 'Merged snapshot has invalid queue state')
    END;
END;

CREATE TRIGGER remote_pr_snapshot_immutable
BEFORE UPDATE ON remote_pr_snapshot
BEGIN SELECT RAISE(ABORT, 'Remote PR snapshot is immutable'); END;

CREATE TRIGGER remote_development_stage_accept_snapshot
BEFORE UPDATE OF accepted_snapshot_id, accepted_observation_revision,
        current_head_sha, current_base_sha, subject_changed_at_ms
        ON remote_development_stage
WHEN NEW.accepted_snapshot_id IS NOT OLD.accepted_snapshot_id
  OR NEW.accepted_observation_revision IS NOT OLD.accepted_observation_revision
  OR NEW.current_head_sha IS NOT OLD.current_head_sha
  OR NEW.current_base_sha IS NOT OLD.current_base_sha
  OR NEW.subject_changed_at_ms IS NOT OLD.subject_changed_at_ms
BEGIN
    SELECT CASE
        WHEN NEW.accepted_observation_revision <= OLD.accepted_observation_revision
            THEN RAISE(ABORT, 'Accepted remote observation must advance')
        WHEN NEW.subject_changed_at_ms < OLD.subject_changed_at_ms
            THEN RAISE(ABORT, 'Remote subject time cannot move backward')
        WHEN NOT EXISTS (
            SELECT 1 FROM remote_pr_snapshot snapshot
            WHERE snapshot.id = NEW.accepted_snapshot_id
              AND snapshot.remote_development_stage_id = NEW.stage_id
              AND snapshot.task_id = NEW.task_id
              AND snapshot.stage_generation = NEW.generation
              AND snapshot.remote_pr_binding_id = NEW.remote_pr_binding_id
              AND snapshot.observation_revision =
                    NEW.accepted_observation_revision
              AND snapshot.head_sha = NEW.current_head_sha
              AND snapshot.base_sha = NEW.current_base_sha
              AND snapshot.observed_at_ms = NEW.subject_changed_at_ms)
            THEN RAISE(ABORT, 'Accepted remote subject lacks exact snapshot proof')
    END;
END;

-- Repository policy is explicit for every normalized non-green CI shape.
-- PASSED and FAILED remain fixed semantic outcomes and cannot be configured
-- into their opposite.
CREATE TABLE remote_ci_policy_revision (
    id                  TEXT    NOT NULL PRIMARY KEY,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    remote_pr_binding_id TEXT   NOT NULL REFERENCES remote_pr_binding(id),
    revision            INTEGER NOT NULL CHECK (revision > 0),
    source              TEXT    NOT NULL,
    none_outcome        TEXT    NOT NULL CHECK (none_outcome IN (
        'ACCEPTED', 'WAITING', 'FAILED')),
    missing_outcome     TEXT    NOT NULL CHECK (missing_outcome IN (
        'ACCEPTED', 'WAITING', 'FAILED')),
    queued_outcome      TEXT    NOT NULL CHECK (queued_outcome IN (
        'ACCEPTED', 'WAITING', 'FAILED')),
    pending_outcome     TEXT    NOT NULL CHECK (pending_outcome IN (
        'ACCEPTED', 'WAITING', 'FAILED')),
    neutral_outcome     TEXT    NOT NULL CHECK (neutral_outcome IN (
        'ACCEPTED', 'WAITING', 'FAILED')),
    skipped_outcome     TEXT    NOT NULL CHECK (skipped_outcome IN (
        'ACCEPTED', 'WAITING', 'FAILED')),
    canceled_outcome    TEXT    NOT NULL CHECK (canceled_outcome IN (
        'ACCEPTED', 'WAITING', 'FAILED')),
    created_by          TEXT    NOT NULL,
    created_at_ms       INTEGER NOT NULL,
    UNIQUE (task_id, revision)
);

CREATE TRIGGER remote_ci_policy_revision_insert
BEFORE INSERT ON remote_ci_policy_revision
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM remote_pr_binding binding
            JOIN tasks task ON task.id = binding.task_id
            WHERE binding.id = NEW.remote_pr_binding_id
              AND binding.task_id = NEW.task_id
              AND task.workflow_version = 'V2')
            THEN RAISE(ABORT, 'CI policy requires exact V2 remote binding')
        WHEN NEW.revision <> COALESCE((
            SELECT MAX(policy.revision) + 1
            FROM remote_ci_policy_revision policy
            WHERE policy.task_id = NEW.task_id), 1)
            THEN RAISE(ABORT, 'CI policy revision must be next')
    END;
END;

CREATE TRIGGER remote_ci_policy_revision_immutable
BEFORE UPDATE ON remote_ci_policy_revision
BEGIN SELECT RAISE(ABORT, 'CI policy revision is immutable'); END;

CREATE TABLE remote_ci_check_snapshot (
    id                  TEXT    NOT NULL PRIMARY KEY,
    remote_pr_snapshot_id TEXT  NOT NULL REFERENCES remote_pr_snapshot(id) ON DELETE CASCADE,
    check_kind          TEXT    NOT NULL CHECK (check_kind IN (
        'CHECK_RUN', 'STATUS_CONTEXT', 'CHECK_SUITE', 'REQUIRED_MISSING')),
    external_check_id   TEXT    NOT NULL,
    check_name          TEXT    NOT NULL,
    normalized_state    TEXT    NOT NULL CHECK (normalized_state IN (
        'MISSING', 'QUEUED', 'PENDING', 'PASSED', 'FAILED',
        'NEUTRAL', 'SKIPPED', 'CANCELED')),
    provider_status     TEXT,
    provider_conclusion TEXT,
    started_at_ms       INTEGER,
    completed_at_ms     INTEGER,
    observed_at_ms      INTEGER NOT NULL,
    raw_evidence        TEXT,
    UNIQUE (remote_pr_snapshot_id, check_kind, external_check_id),
    CHECK ((normalized_state = 'MISSING') = (check_kind = 'REQUIRED_MISSING')),
    CHECK (completed_at_ms IS NULL OR started_at_ms IS NULL
        OR completed_at_ms >= started_at_ms)
);

CREATE TRIGGER remote_ci_check_snapshot_insert
BEFORE INSERT ON remote_ci_check_snapshot
WHEN NOT EXISTS (
    SELECT 1 FROM remote_pr_snapshot snapshot
    WHERE snapshot.id = NEW.remote_pr_snapshot_id
      AND NEW.observed_at_ms >= snapshot.observed_at_ms)
BEGIN SELECT RAISE(ABORT, 'CI check does not belong to its remote snapshot'); END;

CREATE TRIGGER remote_ci_check_snapshot_immutable
BEFORE UPDATE ON remote_ci_check_snapshot
BEGIN SELECT RAISE(ABORT, 'CI check snapshot is immutable'); END;

CREATE TABLE remote_ci_evaluation (
    id                         TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT  NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    remote_pr_snapshot_id      TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    ci_policy_revision_id      TEXT    NOT NULL REFERENCES remote_ci_policy_revision(id),
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    head_sha                   TEXT    NOT NULL,
    base_sha                   TEXT    NOT NULL,
    normalized_status          TEXT    NOT NULL CHECK (normalized_status IN (
        'NONE', 'MISSING', 'QUEUED', 'PENDING', 'PASSED', 'FAILED',
        'NEUTRAL', 'SKIPPED', 'CANCELED')),
    policy_outcome             TEXT    NOT NULL CHECK (policy_outcome IN (
        'ACCEPTED', 'WAITING', 'FAILED')),
    check_count                INTEGER NOT NULL CHECK (check_count >= 0),
    missing_required_count     INTEGER NOT NULL CHECK (missing_required_count >= 0),
    evidence                   TEXT    NOT NULL,
    evaluated_at_ms            INTEGER NOT NULL,
    UNIQUE (remote_pr_snapshot_id, ci_policy_revision_id),
    CHECK ((normalized_status = 'NONE' AND check_count = 0
            AND missing_required_count = 0)
        OR (normalized_status = 'MISSING' AND missing_required_count > 0)
        OR normalized_status NOT IN ('NONE', 'MISSING')),
    CHECK (normalized_status NOT IN (
        'QUEUED', 'PENDING', 'PASSED', 'FAILED', 'NEUTRAL', 'SKIPPED', 'CANCELED')
        OR check_count > 0)
);

CREATE TRIGGER remote_ci_evaluation_insert
BEFORE INSERT ON remote_ci_evaluation
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM remote_pr_snapshot snapshot
            JOIN remote_development_stage remote
              ON remote.stage_id = snapshot.remote_development_stage_id
            JOIN tasks task ON task.id = remote.task_id
            WHERE snapshot.id = NEW.remote_pr_snapshot_id
              AND snapshot.remote_development_stage_id =
                    NEW.remote_development_stage_id
              AND snapshot.task_id = NEW.task_id
              AND snapshot.stage_generation = NEW.stage_generation
              AND snapshot.head_sha = NEW.head_sha
              AND snapshot.base_sha = NEW.base_sha
              AND task.epoch = NEW.task_epoch)
            THEN RAISE(ABORT, 'CI evaluation subject fence is invalid')
        WHEN NOT EXISTS (
            SELECT 1 FROM remote_ci_policy_revision policy
            WHERE policy.id = NEW.ci_policy_revision_id
              AND policy.task_id = NEW.task_id
              AND policy.remote_pr_binding_id = (
                  SELECT snapshot.remote_pr_binding_id
                  FROM remote_pr_snapshot snapshot
                  WHERE snapshot.id = NEW.remote_pr_snapshot_id)
              AND NEW.policy_outcome = CASE NEW.normalized_status
                  WHEN 'NONE' THEN policy.none_outcome
                  WHEN 'MISSING' THEN policy.missing_outcome
                  WHEN 'QUEUED' THEN policy.queued_outcome
                  WHEN 'PENDING' THEN policy.pending_outcome
                  WHEN 'NEUTRAL' THEN policy.neutral_outcome
                  WHEN 'SKIPPED' THEN policy.skipped_outcome
                  WHEN 'CANCELED' THEN policy.canceled_outcome
                  WHEN 'PASSED' THEN 'ACCEPTED'
                  WHEN 'FAILED' THEN 'FAILED'
              END)
            THEN RAISE(ABORT, 'CI evaluation does not match explicit policy')
        WHEN NEW.check_count <> (
            SELECT COUNT(*) FROM remote_ci_check_snapshot check_snapshot
            WHERE check_snapshot.remote_pr_snapshot_id =
                NEW.remote_pr_snapshot_id)
            THEN RAISE(ABORT, 'CI evaluation check count is not exact')
        WHEN NEW.missing_required_count <> (
            SELECT COUNT(*) FROM remote_ci_check_snapshot check_snapshot
            WHERE check_snapshot.remote_pr_snapshot_id =
                    NEW.remote_pr_snapshot_id
              AND check_snapshot.normalized_state = 'MISSING')
            THEN RAISE(ABORT, 'CI evaluation missing-check count is not exact')
        WHEN NEW.normalized_status = 'PASSED' AND EXISTS (
            SELECT 1 FROM remote_ci_check_snapshot check_snapshot
            WHERE check_snapshot.remote_pr_snapshot_id =
                    NEW.remote_pr_snapshot_id
              AND check_snapshot.normalized_state <> 'PASSED')
            THEN RAISE(ABORT, 'Green CI evaluation contains a non-passing check')
        WHEN NEW.normalized_status NOT IN ('NONE', 'PASSED')
          AND NOT EXISTS (
            SELECT 1 FROM remote_ci_check_snapshot check_snapshot
            WHERE check_snapshot.remote_pr_snapshot_id =
                    NEW.remote_pr_snapshot_id
              AND check_snapshot.normalized_state = NEW.normalized_status)
            THEN RAISE(ABORT, 'CI aggregate status lacks constituent check evidence')
    END;
END;

CREATE TRIGGER remote_ci_evaluation_immutable
BEFORE UPDATE ON remote_ci_evaluation
BEGIN SELECT RAISE(ABORT, 'CI evaluation is immutable'); END;

CREATE TABLE ci_repair_episode (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id        TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    failed_ci_evaluation_id     TEXT    NOT NULL REFERENCES remote_ci_evaluation(id),
    subject_head_sha            TEXT    NOT NULL,
    subject_base_sha            TEXT    NOT NULL,
    classification              TEXT    NOT NULL CHECK (classification IN (
        'FLAKY', 'INFRASTRUCTURE', 'TASK_DETERMINISTIC',
        'BASE_DETERMINISTIC', 'UNKNOWN')),
    status                      TEXT    NOT NULL CHECK (status IN (
        'OPEN', 'AWAITING_RERUN', 'FIXING', 'VALIDATING', 'AWAITING_PUSH_CI',
        'SUCCEEDED', 'EXHAUSTED', 'STOPPED')),
    rerun_count                 INTEGER NOT NULL DEFAULT 0 CHECK (rerun_count >= 0),
    rerun_limit                 INTEGER NOT NULL CHECK (rerun_limit >= 0),
    fix_attempt_count           INTEGER NOT NULL DEFAULT 0 CHECK (fix_attempt_count >= 0),
    fix_attempt_limit           INTEGER NOT NULL CHECK (fix_attempt_limit >= 0),
    delivery_retry_count        INTEGER NOT NULL DEFAULT 0 CHECK (delivery_retry_count >= 0),
    delivery_retry_limit        INTEGER NOT NULL CHECK (delivery_retry_limit >= 0),
    push_count                  INTEGER NOT NULL DEFAULT 0 CHECK (push_count >= 0),
    push_limit                  INTEGER NOT NULL CHECK (push_limit >= 0),
    last_pushed_head_sha        TEXT,
    last_push_result_evaluation_id TEXT REFERENCES remote_ci_evaluation(id),
    terminal_ci_evaluation_id   TEXT REFERENCES remote_ci_evaluation(id),
    opened_at_ms                INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    stop_reason                 TEXT,
    CHECK (rerun_count <= rerun_limit
        AND fix_attempt_count <= fix_attempt_limit
        AND delivery_retry_count <= delivery_retry_limit
        AND push_count <= push_limit),
    CHECK ((push_count = 0 AND last_pushed_head_sha IS NULL
            AND last_push_result_evaluation_id IS NULL)
        OR (push_count > 0 AND last_pushed_head_sha IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED'))
            = (completed_at_ms IS NOT NULL)),
    CHECK (classification NOT IN ('FLAKY', 'INFRASTRUCTURE')
        OR (fix_attempt_count = 0 AND fix_attempt_limit = 0
            AND push_count = 0 AND push_limit = 0))
);

CREATE UNIQUE INDEX idx_ci_repair_one_live_subject
    ON ci_repair_episode(remote_development_stage_id, subject_head_sha)
    WHERE status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED');

CREATE TRIGGER ci_repair_episode_insert
BEFORE INSERT ON ci_repair_episode
WHEN NOT EXISTS (
    SELECT 1 FROM remote_development_stage remote
    JOIN tasks task ON task.id = remote.task_id
    JOIN remote_ci_evaluation evaluation
      ON evaluation.remote_development_stage_id = remote.stage_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
      AND remote.current_head_sha = NEW.subject_head_sha
      AND remote.current_base_sha = NEW.subject_base_sha
      AND task.epoch = NEW.task_epoch
      AND evaluation.id = NEW.failed_ci_evaluation_id
      AND evaluation.head_sha = NEW.subject_head_sha
      AND evaluation.base_sha = NEW.subject_base_sha
      AND evaluation.policy_outcome = 'FAILED')
BEGIN SELECT RAISE(ABORT, 'CI repair requires the current failed exact-head evidence'); END;

CREATE TRIGGER ci_repair_episode_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, failed_ci_evaluation_id,
        subject_head_sha, subject_base_sha, classification, rerun_limit,
        fix_attempt_limit, delivery_retry_limit, push_limit, opened_at_ms
        ON ci_repair_episode
WHEN NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.remote_pr_binding_id IS NOT OLD.remote_pr_binding_id
  OR NEW.failed_ci_evaluation_id IS NOT OLD.failed_ci_evaluation_id
  OR NEW.subject_head_sha IS NOT OLD.subject_head_sha
  OR NEW.subject_base_sha IS NOT OLD.subject_base_sha
  OR NEW.classification IS NOT OLD.classification
  OR NEW.rerun_limit IS NOT OLD.rerun_limit
  OR NEW.fix_attempt_limit IS NOT OLD.fix_attempt_limit
  OR NEW.delivery_retry_limit IS NOT OLD.delivery_retry_limit
  OR NEW.push_limit IS NOT OLD.push_limit
  OR NEW.opened_at_ms IS NOT OLD.opened_at_ms
BEGIN SELECT RAISE(ABORT, 'CI repair subject and budgets are immutable'); END;

CREATE TRIGGER ci_repair_episode_counter_update
BEFORE UPDATE OF rerun_count, fix_attempt_count, delivery_retry_count, push_count
        ON ci_repair_episode
WHEN NEW.rerun_count NOT BETWEEN OLD.rerun_count AND OLD.rerun_count + 1
  OR NEW.fix_attempt_count NOT BETWEEN OLD.fix_attempt_count AND OLD.fix_attempt_count + 1
  OR NEW.delivery_retry_count NOT BETWEEN OLD.delivery_retry_count AND OLD.delivery_retry_count + 1
  OR NEW.push_count NOT BETWEEN OLD.push_count AND OLD.push_count + 1
BEGIN SELECT RAISE(ABORT, 'CI repair counters advance independently by at most one'); END;

CREATE TRIGGER ci_repair_episode_push_subject
BEFORE UPDATE OF push_count, last_pushed_head_sha ON ci_repair_episode
WHEN (NEW.push_count = OLD.push_count + 1
        AND (NEW.last_pushed_head_sha IS NULL
            OR NEW.last_pushed_head_sha IS OLD.last_pushed_head_sha
            OR NEW.last_pushed_head_sha IS NEW.subject_head_sha
            OR NEW.last_push_result_evaluation_id IS NOT NULL))
  OR (NEW.push_count = OLD.push_count
        AND NEW.last_pushed_head_sha IS NOT OLD.last_pushed_head_sha)
BEGIN SELECT RAISE(ABORT, 'Each CI repair push must record exactly one new unresolved head'); END;

CREATE TRIGGER ci_repair_episode_push_result
BEFORE UPDATE OF last_pushed_head_sha, last_push_result_evaluation_id
        ON ci_repair_episode
WHEN NEW.last_push_result_evaluation_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM remote_ci_evaluation evaluation
      WHERE evaluation.id = NEW.last_push_result_evaluation_id
        AND evaluation.remote_development_stage_id =
            NEW.remote_development_stage_id
        AND evaluation.task_id = NEW.task_id
        AND evaluation.task_epoch = NEW.task_epoch
        AND evaluation.head_sha = NEW.last_pushed_head_sha)
BEGIN SELECT RAISE(ABORT, 'CI repair push result does not match the pushed head'); END;

CREATE TRIGGER ci_repair_episode_exhaustion
BEFORE UPDATE OF status ON ci_repair_episode
WHEN NEW.status = 'EXHAUSTED'
  AND (
    (NEW.classification IN ('FLAKY', 'INFRASTRUCTURE')
        AND NEW.rerun_count < NEW.rerun_limit)
    OR (NEW.classification NOT IN ('FLAKY', 'INFRASTRUCTURE')
        AND NEW.fix_attempt_count < NEW.fix_attempt_limit
        AND NEW.push_count < NEW.push_limit)
    OR (NEW.last_pushed_head_sha IS NOT NULL
        AND (NEW.last_push_result_evaluation_id IS NULL
            OR NOT EXISTS (
                SELECT 1 FROM remote_ci_evaluation evaluation
                WHERE evaluation.id = NEW.last_push_result_evaluation_id
                  AND evaluation.head_sha = NEW.last_pushed_head_sha
                  AND evaluation.policy_outcome = 'FAILED'))))
BEGIN SELECT RAISE(ABORT, 'CI repair cannot exhaust before its final result and budget'); END;

CREATE TRIGGER ci_repair_episode_terminal_result
BEFORE UPDATE OF status ON ci_repair_episode
WHEN NEW.status IN ('SUCCEEDED', 'EXHAUSTED')
  AND NOT EXISTS (
      SELECT 1 FROM remote_ci_evaluation evaluation
      JOIN remote_development_stage remote
        ON remote.stage_id = evaluation.remote_development_stage_id
      WHERE evaluation.id = NEW.terminal_ci_evaluation_id
        AND evaluation.remote_development_stage_id =
            NEW.remote_development_stage_id
        AND evaluation.task_id = NEW.task_id
        AND evaluation.task_epoch = NEW.task_epoch
        AND evaluation.stage_generation = NEW.stage_generation
        AND evaluation.head_sha = COALESCE(
            NEW.last_pushed_head_sha, NEW.subject_head_sha)
        AND evaluation.base_sha = NEW.subject_base_sha
        AND evaluation.policy_outcome = CASE NEW.status
            WHEN 'SUCCEEDED' THEN 'ACCEPTED' ELSE 'FAILED' END
        AND remote.accepted_snapshot_id = evaluation.remote_pr_snapshot_id
        AND remote.current_head_sha = evaluation.head_sha
        AND remote.current_base_sha = evaluation.base_sha
        AND (NEW.last_pushed_head_sha IS NULL
            OR NEW.last_push_result_evaluation_id = evaluation.id))
BEGIN SELECT RAISE(ABORT, 'CI repair terminal state lacks accepted exact-head CI result'); END;

CREATE TRIGGER ci_repair_episode_terminal_immutable
BEFORE UPDATE ON ci_repair_episode
WHEN OLD.status IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
BEGIN SELECT RAISE(ABORT, 'Terminal CI repair episode is immutable'); END;

CREATE TABLE branch_sync_episode (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id        TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    source_snapshot_id          TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    old_head_sha                TEXT    NOT NULL,
    observed_base_sha           TEXT    NOT NULL,
    target_base_sha             TEXT    NOT NULL,
    policy_source               TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'OPEN', 'REBASING', 'CONFLICT_REPAIR', 'VALIDATING', 'BRAIN_REVIEW',
        'PUSHING', 'AWAITING_HEAD', 'SUCCEEDED', 'FAILED', 'STOPPED')),
    attempt_count               INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit               INTEGER NOT NULL CHECK (attempt_limit > 0),
    result_head_sha             TEXT,
    result_snapshot_id          TEXT REFERENCES remote_pr_snapshot(id),
    opened_at_ms                INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    error_message               TEXT,
    CHECK (attempt_count <= attempt_limit),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'STOPPED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED'
        OR (result_head_sha IS NOT NULL AND result_snapshot_id IS NOT NULL))
);

CREATE UNIQUE INDEX idx_branch_sync_one_live_subject
    ON branch_sync_episode(
        remote_development_stage_id, old_head_sha, target_base_sha)
    WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED');

CREATE TRIGGER branch_sync_episode_insert
BEFORE INSERT ON branch_sync_episode
WHEN NOT EXISTS (
    SELECT 1 FROM remote_development_stage remote
    JOIN tasks task ON task.id = remote.task_id
    JOIN remote_pr_snapshot snapshot
      ON snapshot.remote_development_stage_id = remote.stage_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
      AND remote.current_head_sha = NEW.old_head_sha
      AND remote.current_base_sha = NEW.observed_base_sha
      AND task.epoch = NEW.task_epoch
      AND snapshot.id = NEW.source_snapshot_id
      AND snapshot.head_sha = NEW.old_head_sha
      AND snapshot.base_sha = NEW.observed_base_sha)
BEGIN SELECT RAISE(ABORT, 'Branch sync requires the current exact remote subject'); END;

CREATE TRIGGER branch_sync_episode_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, source_snapshot_id,
        old_head_sha, observed_base_sha, target_base_sha, policy_source,
        attempt_limit, opened_at_ms ON branch_sync_episode
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
BEGIN SELECT RAISE(ABORT, 'Branch sync subject is immutable'); END;

CREATE TRIGGER branch_sync_episode_attempt_monotonic
BEFORE UPDATE OF attempt_count ON branch_sync_episode
WHEN NEW.attempt_count < OLD.attempt_count
  OR NEW.attempt_count > OLD.attempt_count + 1
BEGIN SELECT RAISE(ABORT, 'Branch sync attempt must advance by at most one'); END;

CREATE TABLE branch_sync_effect_step (
    id                     TEXT    NOT NULL PRIMARY KEY,
    branch_sync_episode_id TEXT    NOT NULL REFERENCES branch_sync_episode(id) ON DELETE CASCADE,
    ordinal                INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 6),
    kind                   TEXT    NOT NULL CHECK (kind IN (
        'FETCH_COMPARE', 'MECHANICAL_REBASE', 'CONFLICT_REPAIR',
        'VALIDATE', 'BRAIN_REVIEW', 'FORCE_WITH_LEASE_PUSH')),
    idempotency_key        TEXT    NOT NULL UNIQUE,
    status                 TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED',
        'INDETERMINATE', 'SKIPPED')),
    attempt_count          INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit          INTEGER NOT NULL CHECK (attempt_limit > 0),
    claim_mode             TEXT CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    claim_owner            TEXT,
    claimed_at_ms          INTEGER,
    lease_until_ms         INTEGER,
    evidence               TEXT,
    last_error             TEXT,
    completed_at_ms        INTEGER,
    UNIQUE (branch_sync_episode_id, ordinal),
    CHECK ((status = 'CLAIMED') = (claim_mode IS NOT NULL
        AND claim_owner IS NOT NULL AND claimed_at_ms IS NOT NULL
        AND lease_until_ms IS NOT NULL)),
    CHECK (status <> 'CLAIMED' OR lease_until_ms > claimed_at_ms),
    CHECK (attempt_count <= attempt_limit),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE', 'SKIPPED'))
        = (completed_at_ms IS NOT NULL))
);

CREATE TRIGGER branch_sync_effect_step_insert
BEFORE INSERT ON branch_sync_effect_step
WHEN NEW.kind <> CASE NEW.ordinal
        WHEN 1 THEN 'FETCH_COMPARE'
        WHEN 2 THEN 'MECHANICAL_REBASE'
        WHEN 3 THEN 'CONFLICT_REPAIR'
        WHEN 4 THEN 'VALIDATE'
        WHEN 5 THEN 'BRAIN_REVIEW'
        WHEN 6 THEN 'FORCE_WITH_LEASE_PUSH'
    END
BEGIN SELECT RAISE(ABORT, 'Branch sync effect ordinal and kind do not match'); END;

CREATE TRIGGER branch_sync_effect_step_identity_immutable
BEFORE UPDATE OF branch_sync_episode_id, ordinal, kind, idempotency_key,
        attempt_limit ON branch_sync_effect_step
WHEN NEW.branch_sync_episode_id IS NOT OLD.branch_sync_episode_id
  OR NEW.ordinal IS NOT OLD.ordinal
  OR NEW.kind IS NOT OLD.kind
  OR NEW.idempotency_key IS NOT OLD.idempotency_key
  OR NEW.attempt_limit IS NOT OLD.attempt_limit
BEGIN SELECT RAISE(ABORT, 'Branch sync effect identity is immutable'); END;

CREATE TRIGGER branch_sync_effect_step_claim
BEFORE UPDATE OF status ON branch_sync_effect_step
WHEN NEW.status = 'CLAIMED'
  AND (NEW.attempt_count <> OLD.attempt_count + 1
    OR (OLD.status = 'INDETERMINATE' AND NEW.claim_mode <> 'PROBE')
    OR (OLD.status <> 'INDETERMINATE' AND NEW.claim_mode <> 'EXECUTE')
    OR EXISTS (
        SELECT 1 FROM branch_sync_effect_step previous
        WHERE previous.branch_sync_episode_id = NEW.branch_sync_episode_id
          AND previous.ordinal < NEW.ordinal
          AND previous.status NOT IN ('SUCCEEDED', 'SKIPPED')))
BEGIN SELECT RAISE(ABORT, 'Branch sync effect claim is unordered or unsafe'); END;

CREATE TABLE branch_sync_push_proof (
    branch_sync_effect_step_id TEXT NOT NULL PRIMARY KEY
        REFERENCES branch_sync_effect_step(id) ON DELETE CASCADE,
    branch_sync_episode_id     TEXT NOT NULL REFERENCES branch_sync_episode(id) ON DELETE CASCADE,
    old_head_sha               TEXT NOT NULL,
    observed_base_sha          TEXT NOT NULL,
    target_base_sha            TEXT NOT NULL,
    force_with_lease_expected_sha TEXT NOT NULL,
    pushed_head_sha            TEXT NOT NULL,
    force_with_lease_used      INTEGER NOT NULL CHECK (force_with_lease_used = 1),
    remote_probe_identity      TEXT NOT NULL,
    evidence                   TEXT NOT NULL,
    recorded_at_ms             INTEGER NOT NULL,
    UNIQUE (branch_sync_episode_id, pushed_head_sha)
);

CREATE TRIGGER branch_sync_push_proof_insert
BEFORE INSERT ON branch_sync_push_proof
WHEN NOT EXISTS (
    SELECT 1 FROM branch_sync_effect_step step
    JOIN branch_sync_episode episode
      ON episode.id = step.branch_sync_episode_id
    WHERE step.id = NEW.branch_sync_effect_step_id
      AND step.branch_sync_episode_id = NEW.branch_sync_episode_id
      AND step.kind = 'FORCE_WITH_LEASE_PUSH'
      AND step.status = 'CLAIMED'
      AND episode.old_head_sha = NEW.old_head_sha
      AND episode.observed_base_sha = NEW.observed_base_sha
      AND episode.target_base_sha = NEW.target_base_sha
      AND NEW.force_with_lease_expected_sha = NEW.old_head_sha
      AND NEW.pushed_head_sha <> NEW.old_head_sha)
BEGIN SELECT RAISE(ABORT, 'Branch sync push proof is not exact force-with-lease evidence'); END;

CREATE TRIGGER branch_sync_push_proof_immutable
BEFORE UPDATE ON branch_sync_push_proof
BEGIN SELECT RAISE(ABORT, 'Branch sync push proof is immutable'); END;

CREATE TRIGGER branch_sync_push_success
BEFORE UPDATE OF status ON branch_sync_effect_step
WHEN NEW.status = 'SUCCEEDED' AND NEW.kind = 'FORCE_WITH_LEASE_PUSH'
  AND NOT EXISTS (
      SELECT 1 FROM branch_sync_push_proof proof
      WHERE proof.branch_sync_effect_step_id = NEW.id
        AND proof.branch_sync_episode_id = NEW.branch_sync_episode_id)
BEGIN SELECT RAISE(ABORT, 'Branch sync push success lacks force-with-lease proof'); END;

CREATE TRIGGER branch_sync_episode_success
BEFORE UPDATE OF status ON branch_sync_episode
WHEN NEW.status = 'SUCCEEDED'
  AND ((SELECT COUNT(*) FROM branch_sync_effect_step step
        WHERE step.branch_sync_episode_id = NEW.id) <> 6
    OR EXISTS (
        SELECT 1 FROM branch_sync_effect_step step
        WHERE step.branch_sync_episode_id = NEW.id
          AND step.status NOT IN ('SUCCEEDED', 'SKIPPED'))
    OR NOT EXISTS (
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
          AND remote.current_base_sha = NEW.target_base_sha))
BEGIN SELECT RAISE(ABORT, 'Branch sync success lacks complete ordered effect proof'); END;

CREATE TRIGGER branch_sync_episode_terminal_immutable
BEFORE UPDATE ON branch_sync_episode
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'STOPPED')
BEGIN SELECT RAISE(ABORT, 'Terminal branch sync episode is immutable'); END;

-- Episode blockers become exact now that their domain tables exist. Generic
-- operation ownership remains rejected until its typed operation is installed.
DROP TRIGGER task_blocker_owner_insert;
CREATE TRIGGER task_blocker_owner_insert
BEFORE INSERT ON task_blocker
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks task
            WHERE task.id = NEW.task_id AND task.workflow_version = 'V2')
            THEN RAISE(ABORT, 'Task blocker requires a V2 Task')
        WHEN NEW.owner_kind = 'TASK'
                AND (NEW.owner_id <> NEW.task_id OR NEW.stage_id IS NOT NULL)
            THEN RAISE(ABORT, 'Task blocker Task owner is invalid')
        WHEN NEW.owner_kind = 'STAGE'
                AND (NEW.stage_id IS NULL OR NEW.owner_id <> NEW.stage_id
                    OR NOT EXISTS (
                        SELECT 1 FROM stage owner
                        WHERE owner.id = NEW.stage_id
                          AND owner.task_id = NEW.task_id))
            THEN RAISE(ABORT, 'Task blocker Stage owner is invalid')
        WHEN NEW.owner_kind = 'EPISODE'
                AND (NEW.stage_id IS NULL OR NOT EXISTS (
                    SELECT 1 FROM ci_repair_episode episode
                    WHERE episode.id = NEW.owner_id
                      AND episode.task_id = NEW.task_id
                      AND episode.remote_development_stage_id = NEW.stage_id
                    UNION ALL
                    SELECT 1 FROM branch_sync_episode episode
                    WHERE episode.id = NEW.owner_id
                      AND episode.task_id = NEW.task_id
                      AND episode.remote_development_stage_id = NEW.stage_id))
            THEN RAISE(ABORT, 'Task blocker Episode owner is invalid')
        WHEN NEW.owner_kind = 'OPERATION'
            THEN RAISE(ABORT, 'Task blocker operation owner table is not installed yet')
    END;
END;
