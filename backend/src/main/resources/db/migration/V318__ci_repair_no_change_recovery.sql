-- A CI fix budget counts accepted tree changes, not provider executions.  The
-- original ci_repair_operation keeps the semantic attempt; this companion
-- table gives the one automatic no-change continuation its own exact
-- execution fence without spending that semantic attempt.
CREATE TABLE ci_repair_no_change_retry_authorization_v318 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id        TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    blocker_id                  TEXT    NOT NULL UNIQUE REFERENCES task_blocker(id),
    predecessor_tree_result_id  TEXT    NOT NULL UNIQUE,
    predecessor_operation_id    TEXT    NOT NULL UNIQUE,
    predecessor_stage_turn_id   TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    predecessor_accepted_snapshot_id TEXT NOT NULL REFERENCES remote_pr_snapshot(id),
    predecessor_accepted_observation_revision INTEGER NOT NULL CHECK (
        predecessor_accepted_observation_revision > 0),
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt           INTEGER NOT NULL CHECK (execution_attempt > 2),
    command_id                  TEXT    NOT NULL UNIQUE,
    actor                       TEXT    NOT NULL,
    reason                      TEXT    NOT NULL,
    authorized_at_ms            INTEGER NOT NULL CHECK (authorized_at_ms >= 0),
    consumed_at_ms              INTEGER,
    UNIQUE (ci_repair_episode_id, semantic_attempt),
    CHECK (length(trim(id)) > 0
        AND length(trim(command_id)) > 0
        AND length(trim(actor)) > 0
        AND length(trim(reason)) > 0)
);

-- Exact forward settlement for pre-V318 launches whose budget was charged at
-- dispatch even though the provider process provably never started. The
-- receipt preserves the old identity while runtime rows are terminalized.
CREATE TABLE ci_repair_prelaunch_refund_v318 (
    id                       TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id     TEXT    NOT NULL UNIQUE
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    ci_repair_operation_id   TEXT    NOT NULL UNIQUE
        REFERENCES ci_repair_operation(id) ON DELETE CASCADE,
    operation_id             TEXT    NOT NULL UNIQUE,
    stage_turn_id            TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    dispatch_ticket_id       TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    prior_fix_attempt_count  INTEGER NOT NULL CHECK (prior_fix_attempt_count > 0),
    refunded_fix_attempt_count INTEGER NOT NULL CHECK (
        refunded_fix_attempt_count >= 0),
    evidence                 TEXT    NOT NULL,
    recorded_at_ms           INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK (refunded_fix_attempt_count = prior_fix_attempt_count - 1)
);

CREATE TRIGGER ci_repair_prelaunch_refund_immutable_v318
BEFORE UPDATE ON ci_repair_prelaunch_refund_v318
BEGIN SELECT RAISE(ABORT, 'CI prelaunch refund evidence is immutable'); END;

CREATE TABLE agent_turn_worktree_quarantine_v318 (
    id                  TEXT    NOT NULL PRIMARY KEY,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    stage_id            TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    source_operation_id TEXT    NOT NULL UNIQUE,
    worktree_path       TEXT    NOT NULL,
    expected_branch_name TEXT   NOT NULL,
    expected_code_fingerprint TEXT NOT NULL,
    expected_head_sha   TEXT    NOT NULL,
    observed_branch_name TEXT,
    observed_head_sha   TEXT,
    observed_clean      INTEGER CHECK (observed_clean IN (0, 1)),
    observed_code_fingerprint TEXT,
    probe_error         TEXT,
    reason              TEXT    NOT NULL,
    status              TEXT    NOT NULL CHECK (status IN ('OPEN', 'CLEARED')),
    opened_at_ms        INTEGER NOT NULL CHECK (opened_at_ms >= 0),
    cleared_by_cleanup_operation_id TEXT REFERENCES cleanup_operation(id),
    cleared_by_cleanup_step_id TEXT REFERENCES cleanup_step(id),
    cleared_by_repair_operation_id TEXT
        REFERENCES worktree_quarantine_repair_operation_v318(id),
    cleared_at_ms       INTEGER,
    clear_evidence      TEXT,
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_id)) > 0
        AND length(trim(worktree_path)) > 0
        AND length(trim(expected_branch_name)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(reason)) > 0),
    CHECK (observed_head_sha IS NOT NULL OR probe_error IS NOT NULL),
    CHECK (observed_clean IS NOT NULL OR probe_error IS NOT NULL),
    CHECK (observed_code_fingerprint IS NOT NULL OR probe_error IS NOT NULL),
    CHECK (probe_error IS NULL OR length(trim(probe_error)) > 0),
    CHECK ((status = 'OPEN'
            AND cleared_by_cleanup_operation_id IS NULL
            AND cleared_by_cleanup_step_id IS NULL
            AND cleared_by_repair_operation_id IS NULL
            AND cleared_at_ms IS NULL
            AND clear_evidence IS NULL)
        OR (status = 'CLEARED'
            AND cleared_at_ms IS NOT NULL
            AND clear_evidence IS NOT NULL
            AND ((cleared_by_cleanup_operation_id IS NOT NULL
                    AND cleared_by_cleanup_step_id IS NOT NULL
                    AND cleared_by_repair_operation_id IS NULL)
                OR (cleared_by_cleanup_operation_id IS NULL
                    AND cleared_by_cleanup_step_id IS NULL
                    AND cleared_by_repair_operation_id IS NOT NULL))))
);

-- User authority creates one durable, ordinary LOCAL_GIT operation. It does
-- not weaken quarantine for any other writer and does not clear anything at
-- request time.
CREATE TABLE worktree_quarantine_repair_operation_v318 (
    id                  TEXT    NOT NULL PRIMARY KEY,
    quarantine_id       TEXT    NOT NULL
        REFERENCES agent_turn_worktree_quarantine_v318(id) ON DELETE CASCADE,
    blocker_id          TEXT    NOT NULL REFERENCES task_blocker(id),
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch          INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_id            TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation    INTEGER NOT NULL CHECK (stage_generation > 0),
    source_operation_id TEXT    NOT NULL,
    operation_id        TEXT    NOT NULL UNIQUE,
    dispatch_ticket_id  TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    attempt             INTEGER NOT NULL CHECK (attempt > 0),
    command_id          TEXT    NOT NULL UNIQUE,
    actor               TEXT    NOT NULL,
    reason              TEXT    NOT NULL,
    worktree_path       TEXT    NOT NULL,
    expected_branch_name TEXT   NOT NULL,
    expected_code_fingerprint TEXT NOT NULL,
    expected_head_sha   TEXT    NOT NULL,
    expected_base_sha   TEXT    NOT NULL,
    status              TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'CANCELED', 'SUPERSEDED')),
    requested_at_ms     INTEGER NOT NULL CHECK (requested_at_ms >= 0),
    completed_at_ms     INTEGER,
    error_message       TEXT,
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(command_id)) > 0
        AND length(trim(actor)) > 0
        AND length(trim(reason)) > 0
        AND length(trim(worktree_path)) > 0
        AND length(trim(expected_branch_name)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL))
);

CREATE UNIQUE INDEX one_live_worktree_quarantine_repair_v318
    ON worktree_quarantine_repair_operation_v318(quarantine_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

-- Written by the handler while its exact writer lease and CapacityLease are
-- live. This is the proof later delivery consumes; it is never reconstructed
-- from a successful-looking process exit.
CREATE TABLE worktree_quarantine_repair_result_v318 (
    id                  TEXT    NOT NULL PRIMARY KEY,
    repair_operation_id TEXT   NOT NULL UNIQUE
        REFERENCES worktree_quarantine_repair_operation_v318(id)
        ON DELETE CASCADE,
    quarantine_id       TEXT    NOT NULL
        REFERENCES agent_turn_worktree_quarantine_v318(id) ON DELETE CASCADE,
    operation_id        TEXT    NOT NULL UNIQUE,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch          INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_id            TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation    INTEGER NOT NULL CHECK (stage_generation > 0),
    worktree_path       TEXT    NOT NULL,
    expected_branch_name TEXT   NOT NULL,
    expected_code_fingerprint TEXT NOT NULL,
    expected_head_sha   TEXT    NOT NULL,
    result_code_fingerprint TEXT NOT NULL,
    result_head_sha     TEXT    NOT NULL,
    result_branch_name  TEXT    NOT NULL,
    result_clean        INTEGER NOT NULL CHECK (result_clean = 1),
    git_operation_state_clear INTEGER NOT NULL CHECK (
        git_operation_state_clear = 1),
    writer_fencing_token INTEGER NOT NULL CHECK (writer_fencing_token > 0),
    evidence            TEXT    NOT NULL,
    recorded_at_ms      INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK (result_code_fingerprint = expected_code_fingerprint),
    CHECK (result_head_sha = expected_head_sha),
    CHECK (result_branch_name = expected_branch_name),
    CHECK (length(trim(id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(worktree_path)) > 0
        AND length(trim(expected_branch_name)) > 0
        AND length(trim(result_branch_name)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(evidence)) > 0)
);

CREATE TRIGGER worktree_quarantine_repair_result_immutable_v318
BEFORE UPDATE ON worktree_quarantine_repair_result_v318
BEGIN SELECT RAISE(ABORT, 'Worktree quarantine repair result is immutable'); END;

CREATE TABLE worktree_quarantine_repair_delivery_v318 (
    repair_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES worktree_quarantine_repair_operation_v318(id)
        ON DELETE CASCADE,
    operation_id        TEXT    NOT NULL UNIQUE,
    result_id           TEXT UNIQUE
        REFERENCES worktree_quarantine_repair_result_v318(id),
    raw_outcome         TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED')),
    raw_result_digest   TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance          TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    evidence            TEXT    NOT NULL,
    recorded_at_ms      INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK (length(trim(evidence)) > 0),
    CHECK ((raw_outcome = 'SUCCEEDED' AND result_id IS NOT NULL)
        OR raw_outcome IN ('FAILED', 'CANCELED'))
);

CREATE TRIGGER worktree_quarantine_repair_delivery_immutable_v318
BEFORE UPDATE ON worktree_quarantine_repair_delivery_v318
BEGIN SELECT RAISE(ABORT, 'Worktree quarantine repair delivery is immutable'); END;

CREATE UNIQUE INDEX agent_turn_one_open_worktree_quarantine_v318
    ON agent_turn_worktree_quarantine_v318(task_id)
    WHERE status = 'OPEN';

CREATE TRIGGER agent_turn_worktree_quarantine_identity_v318
BEFORE UPDATE OF id, task_id, stage_id, source_operation_id, worktree_path,
        expected_branch_name, expected_code_fingerprint, expected_head_sha,
        observed_branch_name, observed_head_sha, observed_clean,
        observed_code_fingerprint, probe_error, reason, opened_at_ms
ON agent_turn_worktree_quarantine_v318
BEGIN SELECT RAISE(ABORT, 'Agent worktree quarantine identity is immutable'); END;

CREATE TRIGGER worktree_quarantine_repair_operation_insert_v318
BEFORE INSERT ON worktree_quarantine_repair_operation_v318
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1
      FROM agent_turn_worktree_quarantine_v318 quarantine
      JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
      JOIN tasks task ON task.id = quarantine.task_id
      JOIN threads trunk ON trunk.id = task.thread_id
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN stage owner ON owner.id = current.stage_id
      JOIN task_current_code_subject_v230 code ON code.task_id = task.id
      JOIN task_code_identity identity ON identity.task_id = task.id
     WHERE quarantine.id = NEW.quarantine_id
       AND quarantine.status = 'OPEN'
       AND quarantine.task_id = NEW.task_id
       AND quarantine.source_operation_id = NEW.source_operation_id
       AND quarantine.worktree_path = NEW.worktree_path
       AND quarantine.expected_branch_name = NEW.expected_branch_name
       AND quarantine.expected_code_fingerprint =
           NEW.expected_code_fingerprint
       AND quarantine.expected_head_sha = NEW.expected_head_sha
       AND blocker.task_id = task.id
       AND blocker.stage_id = quarantine.stage_id
       AND blocker.owner_kind = 'OPERATION'
       AND blocker.owner_id = quarantine.source_operation_id
       AND blocker.subject_revision = quarantine.id
       AND blocker.blocker_type = 'WORKTREE_RESTORE_QUARANTINED'
       AND blocker.status = 'OPEN'
       AND task.workflow_version = 'V2'
       AND task.lifecycle_state = 'ACTIVE'
       AND task.epoch = NEW.task_epoch
       AND current.stage_id = owner.id
       AND current.stage_generation = NEW.stage_generation
       AND owner.id = NEW.stage_id
       AND owner.task_id = task.id
       AND owner.generation = NEW.stage_generation
       AND owner.completed_at_ms IS NULL
       AND code.code_fingerprint = NEW.expected_code_fingerprint
       AND code.head_sha = NEW.expected_head_sha
       AND code.base_sha = NEW.expected_base_sha
       AND identity.worktree_path = NEW.worktree_path
       AND identity.branch_name = NEW.expected_branch_name)
BEGIN SELECT RAISE(ABORT, 'Worktree quarantine repair request is not exact'); END;

CREATE TRIGGER worktree_quarantine_repair_operation_identity_v318
BEFORE UPDATE OF id, quarantine_id, blocker_id, task_id, task_epoch,
        stage_id, stage_generation, source_operation_id, operation_id,
        dispatch_ticket_id, attempt, command_id, actor, reason,
        worktree_path, expected_branch_name, expected_code_fingerprint,
        expected_head_sha, expected_base_sha, requested_at_ms
ON worktree_quarantine_repair_operation_v318
BEGIN SELECT RAISE(ABORT, 'Worktree quarantine repair identity is immutable'); END;

CREATE TRIGGER worktree_quarantine_repair_operation_status_v318
BEFORE UPDATE OF status, completed_at_ms, error_message
ON worktree_quarantine_repair_operation_v318
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status = 'DISPATCHED'
        AND NEW.completed_at_ms IS NULL)
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
        AND NEW.completed_at_ms IS NOT NULL))
BEGIN SELECT RAISE(ABORT, 'Worktree quarantine repair transition is invalid'); END;

CREATE TRIGGER dispatch_ticket_worktree_quarantine_repair_v318
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'REPAIR_QUARANTINED_WORKTREE'
  OR NEW.callback_route = 'WORKTREE_QUARANTINE_REPAIR_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
          FROM worktree_quarantine_repair_operation_v318 operation
         WHERE operation.operation_id = NEW.operation_id
           AND operation.dispatch_ticket_id = NEW.id
           AND operation.task_id = NEW.task_id
           AND operation.task_epoch = NEW.task_epoch
           AND operation.stage_id = NEW.stage_id
           AND operation.stage_generation = NEW.stage_generation
           AND operation.attempt = NEW.attempt
           AND operation.expected_code_fingerprint =
               NEW.expected_code_fingerprint
           AND operation.expected_head_sha = NEW.expected_head_sha
           AND operation.expected_base_sha = NEW.expected_base_sha
           AND operation.status = 'REQUESTED'
           AND NEW.operation_kind = 'REPAIR_QUARANTINED_WORKTREE'
           AND NEW.async_family = 'LOCAL_GIT'
           AND NEW.owner_kind = 'TASK'
           AND NEW.owner_id = operation.task_id
           AND NEW.callback_route = 'WORKTREE_QUARANTINE_REPAIR_RESULT'
           AND NEW.lane_mask = 16
           AND NEW.trunk_control = 0
           AND NEW.exclusive_task = 1
           AND NEW.writer_required = 1
           AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'Worktree quarantine repair DispatchTicket is not exact') END;
END;

CREATE TRIGGER worktree_quarantine_repair_result_insert_v318
BEFORE INSERT ON worktree_quarantine_repair_result_v318
WHEN NOT EXISTS (
    SELECT 1
      FROM worktree_quarantine_repair_operation_v318 operation
      JOIN agent_turn_worktree_quarantine_v318 quarantine
        ON quarantine.id = operation.quarantine_id
      JOIN dispatch_ticket ticket
        ON ticket.id = operation.dispatch_ticket_id
       AND ticket.operation_id = operation.operation_id
      JOIN capacity_lease capacity
        ON capacity.id = ticket.capacity_lease_id
       AND capacity.operation_id = ticket.operation_id
      JOIN worktree_leases lease
        ON lease.operation_id = operation.operation_id
       AND lease.task_id = operation.task_id
       AND lease.task_epoch = operation.task_epoch
      JOIN tasks task ON task.id = operation.task_id
      JOIN task_current_stage current ON current.task_id = task.id
      JOIN task_current_code_subject_v230 code ON code.task_id = task.id
      JOIN task_code_identity identity ON identity.task_id = task.id
     WHERE operation.id = NEW.repair_operation_id
       AND operation.operation_id = NEW.operation_id
       AND operation.quarantine_id = NEW.quarantine_id
       AND operation.task_id = NEW.task_id
       AND operation.task_epoch = NEW.task_epoch
       AND operation.stage_id = NEW.stage_id
       AND operation.stage_generation = NEW.stage_generation
       AND operation.worktree_path = NEW.worktree_path
       AND operation.expected_branch_name = NEW.expected_branch_name
       AND operation.expected_code_fingerprint =
           NEW.expected_code_fingerprint
       AND operation.expected_head_sha = NEW.expected_head_sha
       AND operation.expected_code_fingerprint =
           NEW.result_code_fingerprint
       AND operation.expected_head_sha = NEW.result_head_sha
       AND operation.expected_branch_name = NEW.result_branch_name
       AND operation.status = 'DISPATCHED'
       AND quarantine.status = 'OPEN'
       AND ticket.operation_kind = 'REPAIR_QUARANTINED_WORKTREE'
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
       AND NEW.git_operation_state_clear = 1
       AND task.workflow_version = 'V2'
       AND task.lifecycle_state = 'ACTIVE'
       AND task.epoch = operation.task_epoch
       AND current.stage_id = operation.stage_id
       AND current.stage_generation = operation.stage_generation
       AND code.code_fingerprint = operation.expected_code_fingerprint
       AND code.head_sha = operation.expected_head_sha
       AND code.base_sha = operation.expected_base_sha
       AND identity.worktree_path = operation.worktree_path
       AND identity.branch_name = operation.expected_branch_name)
BEGIN SELECT RAISE(ABORT, 'Worktree quarantine repair result lacks exact live proof'); END;

CREATE TRIGGER worktree_quarantine_repair_delivery_insert_v318
BEFORE INSERT ON worktree_quarantine_repair_delivery_v318
WHEN NOT EXISTS (
    SELECT 1
      FROM worktree_quarantine_repair_operation_v318 operation
      JOIN dispatch_ticket ticket
        ON ticket.id = operation.dispatch_ticket_id
       AND ticket.operation_id = operation.operation_id
      LEFT JOIN worktree_quarantine_repair_result_v318 result
        ON result.repair_operation_id = operation.id
     WHERE operation.id = NEW.repair_operation_id
       AND operation.operation_id = NEW.operation_id
       AND ticket.status = 'RESULT_PENDING'
       AND ticket.pending_result_outcome = NEW.raw_outcome
       AND ((NEW.acceptance = 'SUPERSEDED'
               AND operation.status = 'SUPERSEDED')
         OR (NEW.acceptance = 'ACCEPTED'
               AND operation.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')))
       AND ((NEW.raw_outcome = 'SUCCEEDED'
               AND result.id = NEW.result_id
               AND operation.status IN ('SUCCEEDED', 'SUPERSEDED'))
         OR (NEW.raw_outcome IN ('FAILED', 'CANCELED')
               AND NEW.result_id IS NULL)))
BEGIN SELECT RAISE(ABORT, 'Worktree quarantine repair delivery is not exact'); END;

-- The only V318 bypass is the exact claimed REMOVE_WORKTREE Cleanup step.
-- REPAIR_QUARANTINED_WORKTREE has its own independent proof arm below.
CREATE TRIGGER agent_turn_worktree_quarantine_cleanup_clear_v318
BEFORE UPDATE OF status, cleared_by_cleanup_operation_id,
        cleared_by_cleanup_step_id, cleared_by_repair_operation_id,
        cleared_at_ms, clear_evidence
ON agent_turn_worktree_quarantine_v318
WHEN OLD.status <> 'OPEN' OR NEW.status <> 'CLEARED'
  OR NEW.cleared_at_ms IS NULL
  OR NEW.clear_evidence IS NULL
  OR length(trim(NEW.clear_evidence)) = 0
  OR NOT (
    (NEW.cleared_by_repair_operation_id IS NULL
      AND EXISTS (
        SELECT 1
        FROM cleanup_operation operation
        JOIN cleanup_step step
          ON step.cleanup_operation_id = operation.id
        JOIN dispatch_ticket ticket
          ON ticket.operation_id = operation.operation_id
        JOIN capacity_lease capacity
          ON capacity.id = ticket.capacity_lease_id
         AND capacity.operation_id = ticket.operation_id
        JOIN worktree_leases lease
          ON lease.operation_id = operation.operation_id
         AND lease.task_id = operation.task_id
         AND lease.task_epoch = operation.task_epoch
         AND lease.worktree_path = OLD.worktree_path
        JOIN tasks task ON task.id = operation.task_id
        JOIN task_current_stage current ON current.task_id = task.id
        JOIN stage owner ON owner.id = operation.cleanup_stage_id
        JOIN task_code_identity identity ON identity.task_id = task.id
        WHERE operation.id = NEW.cleared_by_cleanup_operation_id
          AND operation.task_id = OLD.task_id
          AND operation.status = 'ACTIVE'
          AND task.workflow_version = 'V2'
          AND task.lifecycle_state = 'CLEANING'
          AND task.epoch = operation.task_epoch
          AND current.stage_id = operation.cleanup_stage_id
          AND current.stage_generation = operation.stage_generation
          AND owner.task_id = operation.task_id
          AND owner.kind = 'CLEANUP'
          AND owner.generation = operation.stage_generation
          AND owner.checkpoint = 'CLEANING'
          AND owner.completed_at_ms IS NULL
          AND step.id = NEW.cleared_by_cleanup_step_id
          AND step.task_id = operation.task_id
          AND step.task_epoch = operation.task_epoch
          AND step.cleanup_stage_id = operation.cleanup_stage_id
          AND step.stage_generation = operation.stage_generation
          AND step.kind = 'REMOVE_WORKTREE'
          AND step.status = 'CLAIMED'
          AND ticket.id = operation.dispatch_ticket_id
          AND ticket.operation_kind = 'RUN_CLEANUP_OPERATION'
          AND ticket.async_family = 'CLEANUP'
          AND ticket.owner_kind = 'STAGE'
          AND ticket.owner_id = operation.cleanup_stage_id
          AND ticket.callback_route = 'CLEANUP_OPERATION_RESULT'
          AND ticket.lane_mask = 256
          AND ticket.exclusive_task = 1
          AND ticket.status = 'RUNNING'
          AND ticket.writer_required = 1
          AND ticket.task_id = operation.task_id
          AND ticket.task_epoch = operation.task_epoch
          AND ticket.stage_id = operation.cleanup_stage_id
          AND ticket.stage_generation = operation.stage_generation
          AND capacity.workflow_source = 'V2'
          AND capacity.task_id = operation.task_id
          AND capacity.task_epoch = operation.task_epoch
          AND capacity.writer_required = 1
          AND capacity.fencing_token = lease.fencing_token
          AND capacity.holder = lease.lease_owner
          AND capacity.released_at_ms IS NULL
          AND capacity.expires_at_ms > NEW.cleared_at_ms
          AND lease.workflow_version = 'V2'
          AND lease.expires_at_ms > NEW.cleared_at_ms
          AND identity.worktree_path = OLD.worktree_path))
    OR (NEW.cleared_by_cleanup_operation_id IS NULL
      AND NEW.cleared_by_cleanup_step_id IS NULL
      AND EXISTS (
        SELECT 1
          FROM worktree_quarantine_repair_operation_v318 operation
          JOIN worktree_quarantine_repair_result_v318 result
            ON result.repair_operation_id = operation.id
          JOIN worktree_quarantine_repair_delivery_v318 delivery
            ON delivery.repair_operation_id = operation.id
          JOIN tasks task ON task.id = operation.task_id
          JOIN task_current_stage current ON current.task_id = task.id
          JOIN task_current_code_subject_v230 code
            ON code.task_id = task.id
          JOIN task_code_identity identity ON identity.task_id = task.id
         WHERE operation.id = NEW.cleared_by_repair_operation_id
           AND operation.quarantine_id = OLD.id
           AND operation.task_id = OLD.task_id
           AND operation.worktree_path = OLD.worktree_path
           AND operation.expected_branch_name = OLD.expected_branch_name
           AND operation.expected_code_fingerprint =
               OLD.expected_code_fingerprint
           AND operation.expected_head_sha = OLD.expected_head_sha
           AND operation.status = 'SUCCEEDED'
           AND result.quarantine_id = OLD.id
           AND result.operation_id = operation.operation_id
           AND result.result_code_fingerprint =
               OLD.expected_code_fingerprint
           AND result.result_head_sha = OLD.expected_head_sha
           AND result.result_branch_name = OLD.expected_branch_name
           AND result.result_clean = 1
           AND result.git_operation_state_clear = 1
           AND delivery.operation_id = operation.operation_id
           AND delivery.result_id = result.id
           AND delivery.raw_outcome = 'SUCCEEDED'
           AND delivery.acceptance = 'ACCEPTED'
           AND task.workflow_version = 'V2'
           AND task.lifecycle_state = 'ACTIVE'
           AND task.epoch = operation.task_epoch
           AND current.stage_id = operation.stage_id
           AND current.stage_generation = operation.stage_generation
           AND code.code_fingerprint = operation.expected_code_fingerprint
           AND code.head_sha = operation.expected_head_sha
           AND code.base_sha = operation.expected_base_sha
           AND identity.worktree_path = operation.worktree_path
           AND identity.branch_name = operation.expected_branch_name)))
BEGIN SELECT RAISE(ABORT, 'Agent worktree quarantine clear is not exact'); END;

CREATE TRIGGER agent_turn_worktree_quarantine_insert_v318
BEFORE INSERT ON agent_turn_worktree_quarantine_v318
WHEN NOT EXISTS (
    SELECT 1
    FROM stage_turn turn
    JOIN stage owner ON owner.id = turn.stage_id
    JOIN tasks task ON task.id = owner.task_id
    JOIN dispatch_ticket ticket
      ON ticket.operation_id = turn.operation_id
    JOIN capacity_lease capacity
      ON capacity.id = ticket.capacity_lease_id
     AND capacity.operation_id = ticket.operation_id
    JOIN worktree_leases lease
      ON lease.operation_id = ticket.operation_id
     AND lease.task_id = ticket.task_id
     AND lease.task_epoch = ticket.task_epoch
    WHERE turn.operation_id = NEW.source_operation_id
      AND turn.stage_id = NEW.stage_id
      AND owner.task_id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND turn.task_epoch = ticket.task_epoch
      AND turn.expected_code_fingerprint =
          NEW.expected_code_fingerprint
      AND turn.expected_head_sha = NEW.expected_head_sha
      AND NEW.expected_branch_name = (
          SELECT identity.branch_name
          FROM task_code_identity identity
          WHERE identity.task_id = NEW.task_id
            AND identity.worktree_path = NEW.worktree_path)
      AND turn.status IN ('QUEUED', 'CLAIMED', 'RUNNING')
      AND ticket.owner_kind = 'STAGE_TURN'
      AND ticket.owner_id = turn.id
      AND ticket.task_id = NEW.task_id
      AND ticket.stage_id = NEW.stage_id
      AND ticket.writer_required = 1
      AND ticket.status = 'RUNNING'
      AND capacity.workflow_source = 'V2'
      AND capacity.task_id = ticket.task_id
      AND capacity.task_epoch = ticket.task_epoch
      AND capacity.writer_required = 1
      AND capacity.released_at_ms IS NULL
      AND capacity.expires_at_ms > NEW.opened_at_ms
      AND lease.workflow_version = 'V2'
      AND lease.worktree_path = NEW.worktree_path
      AND lease.fencing_token = capacity.fencing_token
      AND lease.lease_owner = capacity.holder
      AND lease.expires_at_ms > NEW.opened_at_ms)
BEGIN SELECT RAISE(ABORT, 'Agent worktree quarantine source is not exact'); END;

-- Extend the baseline owner guard before the quarantine's AFTER INSERT trigger
-- creates its blocker. The blocker is owned by the exact writer Operation from
-- the source StageTurn; without this forward replacement the retained V308
-- guard would reject the blocker and roll back the quarantine itself.
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
                      AND episode.remote_development_stage_id = NEW.stage_id
                    UNION ALL
                    SELECT 1 FROM remote_feedback_batch episode
                    WHERE episode.id = NEW.owner_id
                      AND episode.task_id = NEW.task_id
                      AND episode.remote_development_stage_id = NEW.stage_id))
            THEN RAISE(ABORT, 'Task blocker Episode owner is invalid')
        WHEN NEW.owner_kind = 'OPERATION'
                AND (NEW.stage_id IS NULL OR NOT EXISTS (
                    SELECT 1 FROM remote_merge_operation operation
                    WHERE operation.id = NEW.owner_id
                      AND operation.task_id = NEW.task_id
                      AND operation.remote_development_stage_id = NEW.stage_id
                    UNION ALL
                    SELECT 1 FROM remote_mark_ready_operation operation
                    WHERE operation.id = NEW.owner_id
                      AND operation.task_id = NEW.task_id
                      AND operation.remote_development_stage_id = NEW.stage_id
                    UNION ALL
                    SELECT 1 FROM cleanup_operation operation
                    WHERE operation.id = NEW.owner_id
                      AND operation.task_id = NEW.task_id
                      AND operation.cleanup_stage_id = NEW.stage_id
                    UNION ALL
                    SELECT 1 FROM stage_turn turn
                    JOIN stage owner ON owner.id = turn.stage_id
                    JOIN agent_turn_worktree_quarantine_v318 quarantine
                      ON quarantine.source_operation_id = turn.operation_id
                    WHERE turn.operation_id = NEW.owner_id
                      AND owner.task_id = NEW.task_id
                      AND owner.id = NEW.stage_id
                      AND NEW.blocker_type = 'WORKTREE_RESTORE_QUARANTINED'
                      AND quarantine.id = NEW.subject_revision
                      AND quarantine.task_id = NEW.task_id
                      AND quarantine.stage_id = NEW.stage_id
                      AND quarantine.status = 'OPEN'))
            THEN RAISE(ABORT, 'Task blocker Operation owner is invalid')
    END;
END;

CREATE TRIGGER agent_turn_worktree_quarantine_blocker_v318
AFTER INSERT ON agent_turn_worktree_quarantine_v318
BEGIN
    INSERT INTO task_blocker(
        id, task_id, stage_id, owner_kind, owner_id, subject_revision,
        blocker_type, status, payload_json, opened_at_ms)
    VALUES (
        'worktree-quarantine-v318:' || NEW.id,
        NEW.task_id, NEW.stage_id, 'OPERATION', NEW.source_operation_id,
        NEW.id, 'WORKTREE_RESTORE_QUARANTINED', 'OPEN',
        json_object(
            'quarantineId', NEW.id,
            'expectedHeadSha', NEW.expected_head_sha,
            'expectedCodeFingerprint', NEW.expected_code_fingerprint,
            'observedHeadSha', NEW.observed_head_sha,
            'observedClean', NEW.observed_clean,
            'observedCodeFingerprint', NEW.observed_code_fingerprint,
            'probeError', NEW.probe_error,
            'reason', NEW.reason),
        NEW.opened_at_ms);
END;

CREATE TRIGGER agent_turn_worktree_quarantine_blocker_clear_v318
AFTER UPDATE OF status ON agent_turn_worktree_quarantine_v318
WHEN OLD.status = 'OPEN' AND NEW.status = 'CLEARED'
BEGIN
    UPDATE task_blocker
       SET status = 'RESOLVED', resolved_at_ms = NEW.cleared_at_ms,
           resolution_evidence = NEW.clear_evidence
     WHERE id = 'worktree-quarantine-v318:' || NEW.id
       AND status = 'OPEN';
END;

CREATE TABLE ci_repair_fix_continuation_due_v318 (
    id                       TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id     TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    predecessor_tree_result_id TEXT NOT NULL UNIQUE,
    predecessor_operation_id TEXT    NOT NULL UNIQUE,
    predecessor_stage_turn_id TEXT   NOT NULL UNIQUE REFERENCES stage_turn(id),
    predecessor_accepted_snapshot_id TEXT NOT NULL REFERENCES remote_pr_snapshot(id),
    predecessor_accepted_observation_revision INTEGER NOT NULL CHECK (
        predecessor_accepted_observation_revision > 0),
    recovery_authorization_id TEXT UNIQUE
        REFERENCES ci_repair_no_change_retry_authorization_v318(id),
    semantic_attempt         INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt        INTEGER NOT NULL CHECK (execution_attempt > 1),
    status                   TEXT    NOT NULL CHECK (status IN (
        'PENDING', 'DISPATCHED', 'CANCELED')),
    continuation_operation_id TEXT UNIQUE,
    recorded_at_ms           INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    consumed_at_ms           INTEGER,
    UNIQUE (ci_repair_episode_id, semantic_attempt, execution_attempt),
    CHECK ((status = 'PENDING') = (consumed_at_ms IS NULL)),
    CHECK ((status = 'DISPATCHED') = (continuation_operation_id IS NOT NULL)),
    CHECK (length(trim(id)) > 0
        AND length(trim(predecessor_tree_result_id)) > 0
        AND length(trim(predecessor_operation_id)) > 0)
);

CREATE TABLE ci_repair_next_fix_due_v318 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id        TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    source_kind                 TEXT    NOT NULL CHECK (source_kind IN (
        'VALIDATION_FAILED', 'BRAIN_CHANGES_REQUESTED')),
    source_semantic_attempt     INTEGER NOT NULL CHECK (source_semantic_attempt > 0),
    requested_semantic_attempt  INTEGER NOT NULL CHECK (requested_semantic_attempt > 1),
    predecessor_accepted_snapshot_id TEXT NOT NULL REFERENCES remote_pr_snapshot(id),
    predecessor_accepted_observation_revision INTEGER NOT NULL CHECK (
        predecessor_accepted_observation_revision > 0),
    prompt                      TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'PENDING', 'DISPATCHED', 'CANCELED')),
    dispatched_operation_row_id TEXT UNIQUE REFERENCES ci_repair_operation(id),
    recorded_at_ms              INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    consumed_at_ms              INTEGER,
    UNIQUE (ci_repair_episode_id, requested_semantic_attempt),
    CHECK (requested_semantic_attempt = source_semantic_attempt + 1),
    CHECK (length(trim(id)) > 0 AND length(trim(prompt)) > 0),
    CHECK ((status = 'PENDING') = (consumed_at_ms IS NULL)),
    CHECK ((status = 'DISPATCHED') = (dispatched_operation_row_id IS NOT NULL))
);

CREATE TABLE ci_repair_fix_continuation_operation_v318 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id        TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    continuation_due_id         TEXT    NOT NULL UNIQUE
        REFERENCES ci_repair_fix_continuation_due_v318(id),
    predecessor_operation_id    TEXT    NOT NULL UNIQUE,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    stage_turn_id               TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    operation_id                TEXT    NOT NULL UNIQUE,
    dispatch_ticket_id          TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt           INTEGER NOT NULL CHECK (execution_attempt > 1),
    base_repair_authorization_id TEXT REFERENCES ci_base_repair_authorization_v303(id),
    expected_code_fingerprint   TEXT    NOT NULL,
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED',
        'CANCELED', 'SUPERSEDED')),
    result_code_fingerprint     TEXT,
    result_head_sha             TEXT,
    result_evidence             TEXT,
    requested_at_ms             INTEGER NOT NULL CHECK (requested_at_ms >= 0),
    completed_at_ms             INTEGER,
    error_message               TEXT,
    UNIQUE (ci_repair_episode_id, semantic_attempt, execution_attempt),
    CHECK (execution_attempt > semantic_attempt),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (length(trim(id)) > 0
        AND length(trim(predecessor_operation_id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(expected_code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0)
);

CREATE UNIQUE INDEX ci_repair_one_live_fix_continuation_v318
    ON ci_repair_fix_continuation_operation_v318(ci_repair_episode_id)
    WHERE status IN ('REQUESTED', 'DISPATCHED');

CREATE TABLE ci_repair_fix_continuation_delivery_v318 (
    continuation_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES ci_repair_fix_continuation_operation_v318(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    raw_outcome        TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest  TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance         TEXT    NOT NULL CHECK (acceptance IN ('ACCEPTED', 'SUPERSEDED')),
    recorded_at_ms     INTEGER NOT NULL CHECK (recorded_at_ms >= 0)
);

CREATE TRIGGER ci_repair_fix_continuation_delivery_immutable_v318
BEFORE UPDATE ON ci_repair_fix_continuation_delivery_v318
BEGIN SELECT RAISE(ABORT, 'CI fix continuation delivery is immutable'); END;

CREATE TRIGGER ci_repair_fix_continuation_delivery_insert_v318
BEFORE INSERT ON ci_repair_fix_continuation_delivery_v318
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_repair_fix_continuation_operation_v318 operation
    JOIN dispatch_ticket ticket
      ON ticket.operation_id = operation.operation_id
    WHERE operation.id = NEW.continuation_operation_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN (
          'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND ((NEW.acceptance = 'SUPERSEDED'
              AND operation.status = 'SUPERSEDED')
        OR (NEW.acceptance = 'ACCEPTED'
              AND operation.status <> 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'CI fix continuation delivery is not exact'); END;

-- This is the authoritative, writer-lease-captured tree result.  Equal trees
-- are a no-op even when an agent created an empty commit with a different SHA.
CREATE TABLE ci_repair_fix_tree_result_v318 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    ci_repair_episode_id        TEXT    NOT NULL
        REFERENCES ci_repair_episode(id) ON DELETE CASCADE,
    source_kind                 TEXT    NOT NULL CHECK (source_kind IN (
        'ORIGINAL', 'CONTINUATION')),
    source_operation_row_id     TEXT    NOT NULL UNIQUE,
    operation_id                TEXT    NOT NULL UNIQUE,
    stage_turn_id               TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    execution_attempt           INTEGER NOT NULL CHECK (execution_attempt > 0),
    disposition                 TEXT    NOT NULL CHECK (disposition IN (
        'CHANGED', 'NO_CHANGE')),
    source_tree_sha             TEXT    NOT NULL,
    result_tree_sha             TEXT    NOT NULL,
    raw_result_digest           TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    recorded_at_ms              INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    UNIQUE (ci_repair_episode_id, semantic_attempt, execution_attempt),
    CHECK ((disposition = 'NO_CHANGE') = (source_tree_sha = result_tree_sha)),
    CHECK (length(trim(id)) > 0
        AND length(trim(source_operation_row_id)) > 0
        AND length(trim(operation_id)) > 0
        AND length(trim(source_tree_sha)) > 0
        AND length(trim(result_tree_sha)) > 0)
);

CREATE TRIGGER ci_repair_fix_tree_result_immutable_v318
BEFORE UPDATE ON ci_repair_fix_tree_result_v318
BEGIN SELECT RAISE(ABORT, 'CI fix tree result is immutable'); END;

CREATE TRIGGER ci_repair_fix_tree_result_insert_v318
BEFORE INSERT ON ci_repair_fix_tree_result_v318
WHEN NOT (
    (NEW.source_kind = 'ORIGINAL' AND EXISTS (
        SELECT 1
        FROM ci_repair_operation operation
        JOIN ci_repair_delivery_receipt delivery
          ON delivery.ci_repair_operation_id = operation.id
        WHERE operation.id = NEW.source_operation_row_id
          AND operation.ci_repair_episode_id = NEW.ci_repair_episode_id
          AND operation.kind = 'FIX_STAGE_TURN'
          AND operation.operation_id = NEW.operation_id
          AND operation.stage_turn_id = NEW.stage_turn_id
          AND operation.semantic_attempt = NEW.semantic_attempt
          AND NEW.execution_attempt = operation.semantic_attempt
          AND operation.status = CASE NEW.disposition
              WHEN 'CHANGED' THEN 'SUCCEEDED' ELSE 'FAILED' END
          AND delivery.operation_id = NEW.operation_id
          AND delivery.raw_outcome = 'SUCCEEDED'
          AND delivery.raw_result_digest = NEW.raw_result_digest
          AND delivery.acceptance = 'ACCEPTED'))
    OR (NEW.source_kind = 'CONTINUATION' AND EXISTS (
        SELECT 1
        FROM ci_repair_fix_continuation_operation_v318 operation
        JOIN ci_repair_fix_continuation_delivery_v318 delivery
          ON delivery.continuation_operation_id = operation.id
        WHERE operation.id = NEW.source_operation_row_id
          AND operation.ci_repair_episode_id = NEW.ci_repair_episode_id
          AND operation.operation_id = NEW.operation_id
          AND operation.stage_turn_id = NEW.stage_turn_id
          AND operation.semantic_attempt = NEW.semantic_attempt
          AND operation.execution_attempt = NEW.execution_attempt
          AND operation.status = CASE NEW.disposition
              WHEN 'CHANGED' THEN 'SUCCEEDED' ELSE 'FAILED' END
          AND delivery.operation_id = NEW.operation_id
          AND delivery.raw_outcome = 'SUCCEEDED'
          AND delivery.raw_result_digest = NEW.raw_result_digest
          AND delivery.acceptance = 'ACCEPTED')))
BEGIN SELECT RAISE(ABORT, 'CI fix tree result lacks exact terminal delivery'); END;

-- RETRY_ONCE is an explicit authority for one third execution of the same
-- semantic attempt. It is admitted only from the exact second no-change
-- result and is consumed into a parked continuation intent; it never changes
-- a semantic CI counter or launches a writer itself.
CREATE TRIGGER ci_repair_no_change_retry_authorization_insert_v318
BEFORE INSERT ON ci_repair_no_change_retry_authorization_v318
WHEN NEW.consumed_at_ms IS NOT NULL OR NOT EXISTS (
    SELECT 1
    FROM ci_repair_fix_tree_result_v318 result
    JOIN ci_repair_fix_continuation_operation_v318 operation
      ON operation.id = result.source_operation_row_id
    JOIN ci_repair_fix_continuation_due_v318 predecessor_due
      ON predecessor_due.id = operation.continuation_due_id
    JOIN ci_repair_turn_freshness_v319 freshness
      ON freshness.intent_kind = 'NO_CHANGE_CONTINUATION'
     AND freshness.intent_id = predecessor_due.id
    JOIN ci_repair_episode episode
      ON episode.id = result.ci_repair_episode_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_blocker blocker ON blocker.id = NEW.blocker_id
    WHERE result.id = NEW.predecessor_tree_result_id
      AND result.source_kind = 'CONTINUATION'
      AND result.disposition = 'NO_CHANGE'
      AND result.operation_id = NEW.predecessor_operation_id
      AND result.stage_turn_id = NEW.predecessor_stage_turn_id
      AND result.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND result.semantic_attempt = NEW.semantic_attempt
      AND result.execution_attempt + 1 = NEW.execution_attempt
      AND operation.status = 'FAILED'
      AND freshness.ci_repair_episode_id = episode.id
      AND freshness.semantic_attempt = result.semantic_attempt
      AND freshness.execution_attempt = result.execution_attempt
      AND freshness.accepted_snapshot_id =
          NEW.predecessor_accepted_snapshot_id
      AND freshness.accepted_observation_revision =
          NEW.predecessor_accepted_observation_revision
      AND episode.status = 'FIXING'
      AND episode.fix_attempt_count + 1 = NEW.semantic_attempt
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = episode.task_epoch
      AND current.stage_id = episode.remote_development_stage_id
      AND current.stage_generation = episode.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.completed_at_ms IS NULL
      AND blocker.task_id = episode.task_id
      AND blocker.stage_id = episode.remote_development_stage_id
      AND blocker.owner_kind = 'EPISODE'
      AND blocker.owner_id = episode.id
      AND blocker.blocker_type = 'CI_REPAIR_NO_CHANGE'
      AND blocker.status = 'OPEN'
      AND (SELECT COUNT(*) FROM ci_repair_fix_tree_result_v318 prior
           WHERE prior.ci_repair_episode_id = episode.id
             AND prior.semantic_attempt = NEW.semantic_attempt
             AND prior.disposition = 'NO_CHANGE') = 2
      AND NOT EXISTS (
          SELECT 1 FROM ci_repair_operation live
          WHERE live.ci_repair_episode_id = episode.id
            AND live.status IN ('REQUESTED', 'DISPATCHED'))
      AND NOT EXISTS (
          SELECT 1 FROM ci_repair_fix_continuation_operation_v318 live
          WHERE live.ci_repair_episode_id = episode.id
            AND live.status IN ('REQUESTED', 'DISPATCHED'))
      AND NOT EXISTS (
          SELECT 1 FROM ci_repair_fix_continuation_due_v318 due
          WHERE due.ci_repair_episode_id = episode.id
            AND due.status = 'PENDING'))
BEGIN SELECT RAISE(ABORT, 'CI no-change retry lacks its exact blocker'); END;

CREATE TRIGGER ci_repair_no_change_retry_authorization_identity_v318
BEFORE UPDATE OF id, ci_repair_episode_id, blocker_id,
        predecessor_tree_result_id, predecessor_operation_id,
        predecessor_stage_turn_id, predecessor_accepted_snapshot_id,
        predecessor_accepted_observation_revision, semantic_attempt,
        execution_attempt, command_id, actor, reason, authorized_at_ms
ON ci_repair_no_change_retry_authorization_v318
BEGIN SELECT RAISE(ABORT, 'CI no-change retry identity is immutable'); END;

CREATE TRIGGER ci_repair_no_change_retry_authorization_consumption_v318
BEFORE UPDATE OF consumed_at_ms
ON ci_repair_no_change_retry_authorization_v318
WHEN OLD.consumed_at_ms IS NOT NULL OR NEW.consumed_at_ms IS NULL
  OR NOT EXISTS (
      SELECT 1 FROM ci_repair_fix_continuation_due_v318 due
      WHERE due.recovery_authorization_id = NEW.id
        AND due.ci_repair_episode_id = NEW.ci_repair_episode_id
        AND due.predecessor_tree_result_id = NEW.predecessor_tree_result_id
        AND due.semantic_attempt = NEW.semantic_attempt
        AND due.execution_attempt = NEW.execution_attempt
        AND due.status = 'PENDING')
BEGIN SELECT RAISE(ABORT, 'CI no-change retry was not consumed into intent'); END;

CREATE TRIGGER ci_repair_fix_continuation_due_insert_v318
BEFORE INSERT ON ci_repair_fix_continuation_due_v318
WHEN NEW.status <> 'PENDING' OR NOT EXISTS (
    SELECT 1
    FROM ci_repair_fix_tree_result_v318 result
    JOIN ci_repair_episode episode
      ON episode.id = result.ci_repair_episode_id
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    WHERE result.id = NEW.predecessor_tree_result_id
      AND result.ci_repair_episode_id = NEW.ci_repair_episode_id
      AND result.operation_id = NEW.predecessor_operation_id
      AND result.stage_turn_id = NEW.predecessor_stage_turn_id
      AND result.semantic_attempt = NEW.semantic_attempt
      AND result.execution_attempt + 1 = NEW.execution_attempt
      AND result.disposition = 'NO_CHANGE'
      AND episode.status = 'FIXING'
      AND episode.fix_attempt_count + 1 = NEW.semantic_attempt
      AND remote.accepted_snapshot_id = NEW.predecessor_accepted_snapshot_id
      AND remote.accepted_observation_revision =
          NEW.predecessor_accepted_observation_revision
      AND ((NEW.recovery_authorization_id IS NULL
            AND (SELECT COUNT(*) FROM ci_repair_fix_tree_result_v318 prior
                 WHERE prior.ci_repair_episode_id = episode.id
                   AND prior.semantic_attempt = NEW.semantic_attempt
                   AND prior.disposition = 'NO_CHANGE') = 1)
        OR (NEW.recovery_authorization_id IS NOT NULL
            AND (SELECT COUNT(*) FROM ci_repair_fix_tree_result_v318 prior
                 WHERE prior.ci_repair_episode_id = episode.id
                   AND prior.semantic_attempt = NEW.semantic_attempt
                   AND prior.disposition = 'NO_CHANGE') = 2
            AND EXISTS (
                SELECT 1
                FROM ci_repair_no_change_retry_authorization_v318 authorization
                WHERE authorization.id = NEW.recovery_authorization_id
                  AND authorization.ci_repair_episode_id = episode.id
                  AND authorization.predecessor_tree_result_id = result.id
                  AND authorization.predecessor_operation_id = result.operation_id
                  AND authorization.predecessor_stage_turn_id = result.stage_turn_id
                  AND authorization.predecessor_accepted_snapshot_id =
                      NEW.predecessor_accepted_snapshot_id
                  AND authorization.predecessor_accepted_observation_revision =
                      NEW.predecessor_accepted_observation_revision
                  AND authorization.semantic_attempt = NEW.semantic_attempt
                  AND authorization.execution_attempt = NEW.execution_attempt
                  AND authorization.consumed_at_ms IS NULL))))
BEGIN SELECT RAISE(ABORT, 'CI fix continuation due lacks exact no-change authority'); END;

CREATE TRIGGER ci_repair_fix_continuation_due_identity_v318
BEFORE UPDATE OF id, ci_repair_episode_id, predecessor_tree_result_id,
        predecessor_operation_id, predecessor_stage_turn_id,
        predecessor_accepted_snapshot_id,
        predecessor_accepted_observation_revision,
        recovery_authorization_id,
        semantic_attempt, execution_attempt, recorded_at_ms
ON ci_repair_fix_continuation_due_v318
BEGIN SELECT RAISE(ABORT, 'CI fix continuation due identity is immutable'); END;

CREATE TRIGGER ci_repair_fix_continuation_due_status_v318
BEFORE UPDATE OF status, continuation_operation_id, consumed_at_ms
ON ci_repair_fix_continuation_due_v318
WHEN OLD.status <> 'PENDING'
  OR NEW.status NOT IN ('DISPATCHED', 'CANCELED')
  OR NEW.consumed_at_ms IS NULL
  OR (NEW.status = 'DISPATCHED' AND NOT EXISTS (
      SELECT 1 FROM ci_repair_fix_continuation_operation_v318 operation
      WHERE operation.id = NEW.continuation_operation_id
        AND operation.continuation_due_id = NEW.id
        AND operation.status = 'DISPATCHED'))
BEGIN SELECT RAISE(ABORT, 'CI fix continuation due transition is invalid'); END;

CREATE TRIGGER ci_repair_next_fix_due_insert_v318
BEFORE INSERT ON ci_repair_next_fix_due_v318
WHEN NEW.status <> 'PENDING' OR NOT EXISTS (
    SELECT 1 FROM ci_repair_episode episode
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    WHERE episode.id = NEW.ci_repair_episode_id
      AND episode.fix_attempt_count = NEW.source_semantic_attempt
      AND episode.fix_attempt_count < episode.fix_attempt_limit
      AND episode.push_count < episode.push_limit
      AND remote.accepted_snapshot_id = NEW.predecessor_accepted_snapshot_id
      AND remote.accepted_observation_revision =
          NEW.predecessor_accepted_observation_revision
      AND ((NEW.source_kind = 'VALIDATION_FAILED'
              AND episode.status = 'VALIDATING')
        OR (NEW.source_kind = 'BRAIN_CHANGES_REQUESTED'
              AND episode.status = 'AWAITING_PUSH_CI'))
      AND NOT EXISTS (
          SELECT 1 FROM ci_repair_operation operation
          WHERE operation.ci_repair_episode_id = episode.id
            AND operation.status IN ('REQUESTED', 'DISPATCHED'))
      AND NOT EXISTS (
          SELECT 1 FROM ci_repair_fix_continuation_operation_v318 operation
          WHERE operation.ci_repair_episode_id = episode.id
            AND operation.status IN ('REQUESTED', 'DISPATCHED')))
BEGIN SELECT RAISE(ABORT, 'Next CI fix due lacks exact terminal predecessor'); END;

CREATE TRIGGER ci_repair_next_fix_due_identity_v318
BEFORE UPDATE OF id, ci_repair_episode_id, source_kind,
        source_semantic_attempt, requested_semantic_attempt, prompt,
        predecessor_accepted_snapshot_id,
        predecessor_accepted_observation_revision,
        recorded_at_ms ON ci_repair_next_fix_due_v318
BEGIN SELECT RAISE(ABORT, 'Next CI fix due identity is immutable'); END;

CREATE TRIGGER ci_repair_next_fix_due_status_v318
BEFORE UPDATE OF status, dispatched_operation_row_id, consumed_at_ms
ON ci_repair_next_fix_due_v318
WHEN OLD.status <> 'PENDING'
  OR NEW.status NOT IN ('DISPATCHED', 'CANCELED')
  OR NEW.consumed_at_ms IS NULL
  OR (NEW.status = 'DISPATCHED' AND NOT EXISTS (
      SELECT 1 FROM ci_repair_operation operation
      WHERE operation.id = NEW.dispatched_operation_row_id
        AND operation.ci_repair_episode_id = NEW.ci_repair_episode_id
        AND operation.kind = 'FIX_STAGE_TURN'
        AND operation.semantic_attempt = NEW.requested_semantic_attempt
        AND operation.status = 'DISPATCHED'))
BEGIN SELECT RAISE(ABORT, 'Next CI fix due transition is invalid'); END;

CREATE TRIGGER ci_repair_fix_continuation_insert_v318
BEFORE INSERT ON ci_repair_fix_continuation_operation_v318
WHEN NEW.status <> 'REQUESTED' OR NOT EXISTS (
    SELECT 1
    FROM ci_repair_episode episode
    JOIN remote_development_stage remote
      ON remote.stage_id = episode.remote_development_stage_id
    JOIN tasks task ON task.id = episode.task_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    JOIN stage_turn turn ON turn.id = NEW.stage_turn_id
    JOIN ci_repair_fix_continuation_due_v318 due
      ON due.id = NEW.continuation_due_id
    JOIN ci_repair_fix_tree_result_v318 predecessor
      ON predecessor.id = due.predecessor_tree_result_id
    WHERE episode.id = NEW.ci_repair_episode_id
      AND episode.status = 'FIXING'
      AND episode.fix_attempt_count + 1 = NEW.semantic_attempt
      AND episode.remote_development_stage_id = NEW.remote_development_stage_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND episode.stage_generation = NEW.stage_generation
      AND task.lifecycle_state = 'ACTIVE' AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.remote_development_stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.kind = 'REMOTE_DEVELOPMENT' AND owner.completed_at_ms IS NULL
      AND remote.current_head_sha = COALESCE(
          episode.last_pushed_head_sha, episode.subject_head_sha)
      AND remote.current_base_sha = episode.subject_base_sha
      AND code.code_fingerprint = NEW.expected_code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND predecessor.ci_repair_episode_id = episode.id
      AND due.ci_repair_episode_id = episode.id
      AND due.status = 'PENDING'
      AND due.predecessor_operation_id = NEW.predecessor_operation_id
      AND due.semantic_attempt = NEW.semantic_attempt
      AND due.execution_attempt = NEW.execution_attempt
      AND predecessor.semantic_attempt = NEW.semantic_attempt
      AND predecessor.execution_attempt + 1 = NEW.execution_attempt
      AND predecessor.disposition = 'NO_CHANGE'
      AND ((due.recovery_authorization_id IS NULL
            AND (SELECT COUNT(*) FROM ci_repair_fix_tree_result_v318 prior
                 WHERE prior.ci_repair_episode_id = episode.id
                   AND prior.semantic_attempt = NEW.semantic_attempt
                   AND prior.disposition = 'NO_CHANGE') = 1)
        OR (due.recovery_authorization_id IS NOT NULL
            AND (SELECT COUNT(*) FROM ci_repair_fix_tree_result_v318 prior
                 WHERE prior.ci_repair_episode_id = episode.id
                   AND prior.semantic_attempt = NEW.semantic_attempt
                   AND prior.disposition = 'NO_CHANGE') = 2
            AND EXISTS (
                SELECT 1
                FROM ci_repair_no_change_retry_authorization_v318 authorization
                WHERE authorization.id = due.recovery_authorization_id
                  AND authorization.ci_repair_episode_id = episode.id
                  AND authorization.predecessor_tree_result_id = predecessor.id
                  AND authorization.semantic_attempt = NEW.semantic_attempt
                  AND authorization.execution_attempt = NEW.execution_attempt
                  AND authorization.consumed_at_ms IS NOT NULL)))
      AND turn.operation_id = NEW.operation_id
      AND turn.stage_id = NEW.remote_development_stage_id
      AND turn.stage_generation = NEW.stage_generation
      AND turn.task_epoch = NEW.task_epoch
      AND turn.attempt = NEW.execution_attempt
      AND turn.expected_code_fingerprint = NEW.expected_code_fingerprint
      AND turn.expected_head_sha = NEW.expected_head_sha
      AND turn.expected_base_sha = NEW.expected_base_sha
      AND turn.purpose = 'REMOTE_CI_REPAIR'
      AND turn.status = 'QUEUED'
      AND (episode.classification <> 'BASE_DETERMINISTIC'
        OR EXISTS (
            SELECT 1 FROM ci_base_repair_authorization_v303 authorization
            WHERE authorization.id = NEW.base_repair_authorization_id
              AND authorization.ci_repair_episode_id = episode.id
              AND authorization.semantic_attempt = NEW.semantic_attempt
              AND authorization.status = 'CLAIMED'))
      AND (episode.classification = 'BASE_DETERMINISTIC'
        OR NEW.base_repair_authorization_id IS NULL))
BEGIN SELECT RAISE(ABORT, 'CI fix continuation lacks one exact no-change predecessor'); END;

CREATE TRIGGER ci_repair_fix_continuation_dispatched_v318
BEFORE UPDATE OF status ON ci_repair_fix_continuation_operation_v318
WHEN NEW.status = 'DISPATCHED' AND NOT EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.id = NEW.dispatch_ticket_id
      AND ticket.operation_id = NEW.operation_id
      AND ticket.attempt = NEW.execution_attempt
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'CI fix continuation lacks its DispatchTicket'); END;

CREATE TRIGGER ci_repair_fix_continuation_identity_v318
BEFORE UPDATE OF id, ci_repair_episode_id, predecessor_operation_id,
        continuation_due_id, remote_development_stage_id, task_id, task_epoch, stage_generation,
        stage_turn_id, operation_id, dispatch_ticket_id, semantic_attempt,
        execution_attempt, base_repair_authorization_id,
        expected_code_fingerprint, expected_head_sha, expected_base_sha,
        requested_at_ms ON ci_repair_fix_continuation_operation_v318
BEGIN SELECT RAISE(ABORT, 'CI fix continuation identity is immutable'); END;

CREATE TRIGGER ci_repair_fix_continuation_status_v318
BEFORE UPDATE OF status ON ci_repair_fix_continuation_operation_v318
WHEN NOT (
    (OLD.status = 'REQUESTED' AND NEW.status = 'DISPATCHED')
    OR (OLD.status = 'DISPATCHED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'CI fix continuation transition is invalid'); END;

-- A semantic attempt is consumed only after an exact changed-tree receipt.
DROP TRIGGER IF EXISTS ci_repair_episode_counter_update;

INSERT INTO ci_repair_prelaunch_refund_v318(
    id, ci_repair_episode_id, ci_repair_operation_id, operation_id,
    stage_turn_id, dispatch_ticket_id, prior_fix_attempt_count,
    refunded_fix_attempt_count, evidence, recorded_at_ms)
SELECT 'v318-prelaunch-refund:' || operation.id,
       episode.id, operation.id, operation.operation_id,
       operation.stage_turn_id, ticket.id, episode.fix_attempt_count,
       episode.fix_attempt_count - 1,
       'V318 proved the CI writer was canceled before provider launch',
       COALESCE(ticket.completed_at_ms, turn.finished_at_ms,
                operation.requested_at_ms)
FROM ci_repair_episode episode
JOIN ci_repair_operation operation
  ON operation.ci_repair_episode_id = episode.id
JOIN stage_turn turn ON turn.id = operation.stage_turn_id
JOIN dispatch_ticket ticket
  ON ticket.operation_id = operation.operation_id
JOIN tasks task ON task.id = episode.task_id
JOIN stage owner ON owner.id = episode.remote_development_stage_id
LEFT JOIN task_current_stage current ON current.task_id = task.id
WHERE episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
  AND episode.fix_attempt_count > 0
  AND operation.kind = 'FIX_STAGE_TURN'
  AND operation.semantic_attempt = episode.fix_attempt_count
  AND operation.status = 'DISPATCHED'
  AND operation.completed_at_ms IS NULL
  AND operation.result_code_fingerprint IS NULL
  AND operation.result_head_sha IS NULL
  AND operation.result_evidence IS NULL
  AND operation.error_message IS NULL
  AND turn.status = 'CANCELED'
  AND turn.started_at_ms IS NULL
  AND turn.finished_at_ms IS NOT NULL
  AND ticket.status = 'CANCELED'
  AND ticket.started_at_ms IS NULL
  AND ticket.completed_at_ms IS NOT NULL
  AND ticket.delivery_acceptance = 'SUPERSEDED'
  AND ticket.pending_result_outcome IS NULL
  AND ticket.pending_result_payload IS NULL
  AND ticket.pending_result_evidence IS NULL
  AND ticket.pending_result_error IS NULL
  AND ticket.pending_result_task_epoch IS NULL
  AND ticket.pending_result_stage_id IS NULL
  AND ticket.pending_result_stage_generation IS NULL
  AND ticket.pending_result_operation_id IS NULL
  AND ticket.pending_result_attempt IS NULL
  AND ticket.pending_result_expected_code_fingerprint IS NULL
  AND ticket.pending_result_expected_head_sha IS NULL
  AND ticket.pending_result_expected_base_sha IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM ci_repair_delivery_receipt receipt
      WHERE receipt.ci_repair_operation_id = operation.id)
  AND NOT EXISTS (
      SELECT 1 FROM agent_execution execution
      WHERE execution.ticket_id = ticket.id)
  AND NOT EXISTS (
      SELECT 1 FROM capacity_lease lease
      WHERE lease.ticket_id = ticket.id)
  AND NOT EXISTS (
      SELECT 1 FROM remote_code_subject subject
      WHERE subject.stage_turn_id = turn.id)
  AND NOT EXISTS (
      SELECT 1 FROM remote_worktree_subject subject
      WHERE subject.source_operation_id = operation.operation_id)
  AND (task.lifecycle_state IN (
          'CANCELING', 'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
       OR owner.completed_at_ms IS NOT NULL
       OR current.stage_id IS NOT episode.remote_development_stage_id
       OR current.stage_generation IS NOT episode.stage_generation);

UPDATE ci_repair_operation
SET status = 'CANCELED',
    completed_at_ms = (
        SELECT refund.recorded_at_ms
        FROM ci_repair_prelaunch_refund_v318 refund
        WHERE refund.ci_repair_operation_id = ci_repair_operation.id),
    error_message = 'V318 settled cancellation before provider launch'
WHERE id IN (
    SELECT ci_repair_operation_id
    FROM ci_repair_prelaunch_refund_v318);

UPDATE ci_base_repair_authorization_v303
SET status = 'CLOSED',
    terminal_at_ms = (
        SELECT refund.recorded_at_ms
        FROM ci_repair_prelaunch_refund_v318 refund
        JOIN ci_repair_operation operation
          ON operation.id = refund.ci_repair_operation_id
        WHERE operation.base_repair_authorization_id =
              ci_base_repair_authorization_v303.id),
    terminal_evidence = 'V318 settled cancellation before provider launch'
WHERE status = 'CLAIMED'
  AND id IN (
      SELECT operation.base_repair_authorization_id
      FROM ci_repair_prelaunch_refund_v318 refund
      JOIN ci_repair_operation operation
        ON operation.id = refund.ci_repair_operation_id
      WHERE operation.base_repair_authorization_id IS NOT NULL);

UPDATE ci_repair_episode
SET fix_attempt_count = fix_attempt_count - 1,
    status = 'STOPPED',
    completed_at_ms = (
        SELECT refund.recorded_at_ms
        FROM ci_repair_prelaunch_refund_v318 refund
        WHERE refund.ci_repair_episode_id = ci_repair_episode.id),
    stop_reason = 'V318 settled cancellation before provider launch'
WHERE id IN (
    SELECT ci_repair_episode_id
    FROM ci_repair_prelaunch_refund_v318);

UPDATE task_blocker
SET status = 'RESOLVED',
    resolved_at_ms = (
        SELECT refund.recorded_at_ms
        FROM ci_repair_prelaunch_refund_v318 refund
        WHERE refund.ci_repair_episode_id = task_blocker.owner_id),
    resolution_evidence = 'V318 settled cancellation before provider launch'
WHERE owner_kind = 'EPISODE' AND status = 'OPEN'
  AND owner_id IN (
      SELECT ci_repair_episode_id
      FROM ci_repair_prelaunch_refund_v318);

CREATE TRIGGER ci_repair_episode_counter_update
BEFORE UPDATE OF rerun_count, fix_attempt_count, delivery_retry_count, push_count
        ON ci_repair_episode
WHEN NEW.rerun_count NOT BETWEEN OLD.rerun_count AND OLD.rerun_count + 1
  OR NEW.fix_attempt_count NOT BETWEEN OLD.fix_attempt_count AND OLD.fix_attempt_count + 1
  OR NEW.delivery_retry_count NOT BETWEEN OLD.delivery_retry_count AND OLD.delivery_retry_count + 1
  OR NEW.push_count NOT BETWEEN OLD.push_count AND OLD.push_count + 1
  OR (NEW.fix_attempt_count = OLD.fix_attempt_count + 1 AND NOT EXISTS (
      SELECT 1 FROM ci_repair_fix_tree_result_v318 result
      WHERE result.ci_repair_episode_id = NEW.id
        AND result.semantic_attempt = NEW.fix_attempt_count
        AND result.disposition = 'CHANGED'))
BEGIN SELECT RAISE(ABORT, 'CI repair counters require one exact accepted result'); END;

-- V309 widened this guard for Brain replacements.  Widen it once more for the
-- exact writer continuation while preserving every original/V309 branch.
DROP TRIGGER IF EXISTS dispatch_ticket_ci_repair_insert;
CREATE TRIGGER dispatch_ticket_ci_repair_insert
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind IN (
        'RERUN_REMOTE_CI', 'VALIDATE_REMOTE_CI_REPAIR',
        'REWRITE_VALIDATE_REMOTE_CI_BASE_REPAIR',
        'PUSH_REMOTE_CI_REPAIR')
  OR NEW.callback_route IN (
        'REMOTE_CI_RERUN_RESULT', 'REMOTE_CI_STAGE_TURN_RESULT',
        'REMOTE_CI_VALIDATION_RESULT',
        'REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT',
        'REMOTE_CI_BRAIN_RESULT', 'REMOTE_CI_PUSH_RESULT')
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM ci_repair_operation operation
        WHERE operation.operation_id = NEW.operation_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.remote_development_stage_id = NEW.stage_id
          AND operation.stage_generation = NEW.stage_generation
          AND operation.semantic_attempt = NEW.attempt
          AND operation.expected_code_fingerprint IS NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND NEW.callback_route = CASE operation.kind
              WHEN 'RERUN' THEN 'REMOTE_CI_RERUN_RESULT'
              WHEN 'FIX_STAGE_TURN' THEN 'REMOTE_CI_STAGE_TURN_RESULT'
              WHEN 'VALIDATE' THEN CASE
                  WHEN operation.base_repair_authorization_id IS NULL
                      THEN 'REMOTE_CI_VALIDATION_RESULT'
                  ELSE 'REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT' END
              WHEN 'BRAIN_REVIEW' THEN 'REMOTE_CI_BRAIN_RESULT'
              WHEN 'PUSH_HEAD' THEN 'REMOTE_CI_PUSH_RESULT' END
          AND NEW.operation_kind = CASE operation.kind
              WHEN 'RERUN' THEN 'RERUN_REMOTE_CI'
              WHEN 'FIX_STAGE_TURN' THEN 'EXECUTE_STAGE_TURN'
              WHEN 'VALIDATE' THEN CASE
                  WHEN operation.base_repair_authorization_id IS NULL
                      THEN 'VALIDATE_REMOTE_CI_REPAIR'
                  ELSE 'REWRITE_VALIDATE_REMOTE_CI_BASE_REPAIR' END
              WHEN 'BRAIN_REVIEW' THEN 'EXECUTE_TASK_TURN'
              WHEN 'PUSH_HEAD' THEN 'PUSH_REMOTE_CI_REPAIR' END
          AND NEW.async_family = CASE operation.kind
              WHEN 'RERUN' THEN 'GITHUB_EFFECT'
              WHEN 'FIX_STAGE_TURN' THEN 'AGENT_TURN'
              WHEN 'VALIDATE' THEN 'VALIDATION'
              WHEN 'BRAIN_REVIEW' THEN 'AGENT_TURN'
              WHEN 'PUSH_HEAD' THEN 'GITHUB_EFFECT' END
          AND NEW.owner_kind = CASE operation.kind
              WHEN 'FIX_STAGE_TURN' THEN 'STAGE_TURN'
              WHEN 'BRAIN_REVIEW' THEN 'TASK_TURN'
              ELSE 'STAGE' END
          AND NEW.owner_id = COALESCE(operation.stage_turn_id,
              operation.task_turn_id, operation.remote_development_stage_id)
          AND NEW.lane_mask = CASE operation.kind
              WHEN 'RERUN' THEN 32
              WHEN 'FIX_STAGE_TURN' THEN NEW.lane_mask
              WHEN 'VALIDATE' THEN 4
              WHEN 'BRAIN_REVIEW' THEN NEW.lane_mask
              WHEN 'PUSH_HEAD' THEN 48 END
          AND (operation.kind NOT IN ('FIX_STAGE_TURN', 'BRAIN_REVIEW')
              OR NEW.lane_mask IN (1, 2))
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = CASE operation.kind
              WHEN 'RERUN' THEN 0 ELSE 1 END
          AND NEW.writer_required = CASE operation.kind
              WHEN 'FIX_STAGE_TURN' THEN 1
              WHEN 'VALIDATE' THEN CASE
                  WHEN operation.base_repair_authorization_id IS NULL
                      THEN 0 ELSE 1 END
              WHEN 'PUSH_HEAD' THEN 1 ELSE 0 END
          AND NEW.status = 'REQUESTED')
      AND NOT EXISTS (
        SELECT 1
        FROM remote_repair_brain_replacement_operation_v309 replacement
        JOIN task_turn turn ON turn.id = replacement.task_turn_id
        JOIN dispatch_ticket predecessor
          ON predecessor.operation_id = replacement.predecessor_operation_id
         AND predecessor.owner_kind = 'TASK_TURN'
         AND predecessor.owner_id = replacement.predecessor_turn_id
        JOIN tasks task ON task.id = replacement.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE replacement.family = 'CI'
          AND replacement.status = 'REQUESTED'
          AND replacement.dispatch_ticket_id = NEW.id
          AND replacement.operation_id = NEW.operation_id
          AND replacement.task_id = NEW.task_id
          AND replacement.task_epoch = NEW.task_epoch
          AND replacement.remote_development_stage_id = NEW.stage_id
          AND replacement.stage_generation = NEW.stage_generation
          AND replacement.execution_attempt = NEW.attempt
          AND replacement.expected_code_fingerprint IS NEW.expected_code_fingerprint
          AND replacement.expected_head_sha = NEW.expected_head_sha
          AND replacement.expected_base_sha = NEW.expected_base_sha
          AND turn.operation_id = replacement.operation_id
          AND turn.status = 'REQUESTED'
          AND NEW.operation_kind = 'EXECUTE_TASK_TURN'
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'TASK_TURN'
          AND NEW.owner_id = replacement.task_turn_id
          AND NEW.callback_route = 'REMOTE_CI_BRAIN_RESULT'
          AND NEW.lane_mask = predecessor.lane_mask
          AND NEW.trunk_control = 0 AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.status = 'REQUESTED')
      AND NOT EXISTS (
        SELECT 1
        FROM ci_repair_fix_continuation_operation_v318 continuation
        JOIN stage_turn turn ON turn.id = continuation.stage_turn_id
        JOIN tasks task ON task.id = continuation.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE continuation.status = 'REQUESTED'
          AND continuation.dispatch_ticket_id = NEW.id
          AND continuation.operation_id = NEW.operation_id
          AND continuation.task_id = NEW.task_id
          AND continuation.task_epoch = NEW.task_epoch
          AND continuation.remote_development_stage_id = NEW.stage_id
          AND continuation.stage_generation = NEW.stage_generation
          AND continuation.execution_attempt = NEW.attempt
          AND continuation.expected_code_fingerprint IS NEW.expected_code_fingerprint
          AND continuation.expected_head_sha = NEW.expected_head_sha
          AND continuation.expected_base_sha = NEW.expected_base_sha
          AND turn.operation_id = continuation.operation_id
          AND turn.attempt = continuation.execution_attempt
          AND turn.status = 'QUEUED'
          AND NEW.operation_kind = 'EXECUTE_STAGE_TURN'
          AND NEW.async_family = 'AGENT_TURN'
          AND NEW.owner_kind = 'STAGE_TURN'
          AND NEW.owner_id = continuation.stage_turn_id
          AND NEW.callback_route = 'REMOTE_CI_STAGE_TURN_RESULT'
          AND NEW.lane_mask IN (1, 2)
          AND NEW.trunk_control = 0 AND NEW.exclusive_task = 1
          AND NEW.writer_required = 1
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'CI repair DispatchTicket is not exact') END;
END;

-- A successful continuation is the same domain code-subject source as the
-- original CI StageTurn, even though its execution row lives separately.
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
