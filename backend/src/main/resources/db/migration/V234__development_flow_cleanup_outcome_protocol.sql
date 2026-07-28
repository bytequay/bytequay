-- Exact terminal acceptance, durable Cleanup, and exactly-once Task outcome
-- persistence. Cleanup effects are an ordered ledger; Task terminal state is
-- impossible until required local cleanup has durable success evidence.

ALTER TABLE task_policy_revision
    ADD COLUMN delete_local_branch_on_cleanup INTEGER NOT NULL DEFAULT 1
        CHECK (delete_local_branch_on_cleanup IN (0, 1));

CREATE TABLE task_terminal_acceptance (
    id                          TEXT    NOT NULL PRIMARY KEY,
    task_terminal_intent_id     TEXT    NOT NULL UNIQUE
        REFERENCES task_terminal_intent(id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')),
    source_kind                 TEXT    NOT NULL CHECK (source_kind IN (
        'REMOTE_OBSERVATION', 'USER_CANCEL', 'LOCAL_COMPLETION')),
    source_id                   TEXT    NOT NULL,
    remote_terminal_observation_id TEXT UNIQUE
        REFERENCES remote_terminal_observation(id),
    observed_head_sha           TEXT,
    accepted_by                 TEXT    NOT NULL,
    accepted_at_ms              INTEGER NOT NULL,
    evidence                    TEXT    NOT NULL,
    CHECK ((source_kind = 'REMOTE_OBSERVATION'
            AND kind IN ('COMPLETED', 'REMOTE_CLOSED')
            AND remote_terminal_observation_id IS NOT NULL
            AND observed_head_sha IS NOT NULL)
        OR (source_kind = 'USER_CANCEL' AND kind = 'CANCELED'
            AND remote_terminal_observation_id IS NULL
            AND observed_head_sha IS NULL)
        OR (source_kind = 'LOCAL_COMPLETION' AND kind = 'COMPLETED'
            AND remote_terminal_observation_id IS NULL))
);

CREATE TRIGGER task_terminal_acceptance_insert
BEFORE INSERT ON task_terminal_acceptance
WHEN NOT EXISTS (
    SELECT 1 FROM task_terminal_intent intent
    JOIN tasks task ON task.id = intent.task_id
    WHERE intent.id = NEW.task_terminal_intent_id
      AND intent.task_id = NEW.task_id
      AND intent.accepted = 1
      AND intent.kind = NEW.kind
      AND intent.source = NEW.source_kind
      AND intent.source_id = NEW.source_id
      AND intent.observed_head_sha IS NEW.observed_head_sha
      AND task.workflow_version = 'V2'
      AND task.epoch = NEW.task_epoch
      AND ((NEW.source_kind = 'REMOTE_OBSERVATION'
            AND EXISTS (
                SELECT 1 FROM remote_terminal_observation remote
                WHERE remote.id = NEW.remote_terminal_observation_id
                  AND remote.task_terminal_intent_id = intent.id
                  AND remote.task_id = NEW.task_id
                  AND remote.task_epoch = NEW.task_epoch
                  AND remote.head_sha = NEW.observed_head_sha
                  AND remote.kind = CASE NEW.kind
                      WHEN 'COMPLETED' THEN 'MERGED' ELSE 'CLOSED' END))
        OR (NEW.source_kind = 'USER_CANCEL'
            AND task.lifecycle_state = 'CANCELING'
            AND EXISTS (
                SELECT 1 FROM task_transition transition
                WHERE transition.task_id = NEW.task_id
                  AND transition.command_id = NEW.source_id
                  AND transition.epoch = NEW.task_epoch
                  AND transition.to_state = 'CANCELING'))
        OR (NEW.source_kind = 'LOCAL_COMPLETION'
            AND task.lifecycle_state IN ('ACTIVE', 'PAUSED', 'ARCHIVED'))))
BEGIN SELECT RAISE(ABORT, 'Terminal acceptance lacks exact Task-owned intent proof'); END;

CREATE TRIGGER task_terminal_acceptance_immutable
BEFORE UPDATE ON task_terminal_acceptance
BEGIN SELECT RAISE(ABORT, 'Task terminal acceptance is immutable'); END;

CREATE TABLE cleanup_stage (
    stage_id                     TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage(id) ON DELETE CASCADE,
    task_id                      TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                   INTEGER NOT NULL CHECK (task_epoch > 0),
    generation                   INTEGER NOT NULL CHECK (generation > 0),
    terminal_acceptance_id       TEXT    NOT NULL UNIQUE
        REFERENCES task_terminal_acceptance(id),
    task_terminal_intent_id      TEXT    NOT NULL UNIQUE
        REFERENCES task_terminal_intent(id),
    terminal_reason              TEXT    NOT NULL CHECK (terminal_reason IN (
        'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')),
    task_policy_revision_id      TEXT    NOT NULL REFERENCES task_policy_revision(id),
    remote_pr_binding_id         TEXT REFERENCES remote_pr_binding(id),
    remote_pr_disposition        TEXT    NOT NULL CHECK (remote_pr_disposition IN (
        'PRESERVE_OPEN', 'REMOTE_ALREADY_TERMINAL', 'NO_REMOTE_PR')),
    local_branch_requirement     TEXT    NOT NULL CHECK (local_branch_requirement IN (
        'REQUIRED', 'NOT_APPLICABLE')),
    remote_branch_requirement    TEXT    NOT NULL CHECK (remote_branch_requirement IN (
        'REQUIRED', 'OPTIONAL', 'NOT_APPLICABLE')),
    opened_at_ms                 INTEGER NOT NULL,
    UNIQUE (stage_id, task_id, task_epoch, generation),
    CHECK (terminal_reason <> 'CANCELED'
        OR (remote_pr_disposition = 'PRESERVE_OPEN'
            AND remote_branch_requirement = 'NOT_APPLICABLE')),
    CHECK ((remote_pr_binding_id IS NULL)
        = (remote_pr_disposition = 'NO_REMOTE_PR'))
);

CREATE TRIGGER cleanup_stage_insert
BEFORE INSERT ON cleanup_stage
WHEN NOT EXISTS (
    SELECT 1 FROM stage owner
    JOIN tasks task ON task.id = owner.task_id
    JOIN task_current_stage current ON current.stage_id = owner.id
    JOIN task_terminal_acceptance acceptance
      ON acceptance.id = NEW.terminal_acceptance_id
    JOIN task_terminal_intent intent ON intent.id = acceptance.task_terminal_intent_id
    JOIN task_policy_revision policy ON policy.id = NEW.task_policy_revision_id
    WHERE owner.id = NEW.stage_id
      AND owner.task_id = NEW.task_id
      AND owner.kind = 'CLEANUP'
      AND owner.generation = NEW.generation
      AND owner.checkpoint = 'WAITING_QUIESCENCE'
      AND owner.completed_at_ms IS NULL
      AND current.task_id = NEW.task_id
      AND current.stage_generation = NEW.generation
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'CLEANING'
      AND task.epoch = NEW.task_epoch
      AND task.policy_revision_id = policy.id
      AND acceptance.task_id = NEW.task_id
      AND acceptance.task_epoch = NEW.task_epoch
      AND acceptance.kind = NEW.terminal_reason
      AND intent.id = NEW.task_terminal_intent_id
      AND policy.trunk_id = task.thread_id
      AND (NEW.remote_pr_binding_id IS NULL OR EXISTS (
          SELECT 1 FROM remote_pr_binding binding
          WHERE binding.id = NEW.remote_pr_binding_id
            AND binding.task_id = NEW.task_id))
      AND NEW.remote_pr_disposition = CASE
          WHEN NEW.remote_pr_binding_id IS NULL THEN 'NO_REMOTE_PR'
          WHEN NEW.terminal_reason = 'CANCELED' THEN 'PRESERVE_OPEN'
          ELSE 'REMOTE_ALREADY_TERMINAL' END
      AND NEW.local_branch_requirement = CASE
          WHEN policy.delete_local_branch_on_cleanup = 1 THEN 'REQUIRED'
          ELSE 'NOT_APPLICABLE' END
      AND NEW.remote_branch_requirement = CASE
          WHEN NEW.remote_pr_binding_id IS NULL OR NEW.terminal_reason = 'CANCELED'
              THEN 'NOT_APPLICABLE'
          WHEN policy.require_remote_branch_cleanup = 1 THEN 'REQUIRED'
          ELSE 'OPTIONAL' END)
BEGIN SELECT RAISE(ABORT, 'Cleanup Stage lacks current accepted terminal ownership'); END;

CREATE TRIGGER cleanup_stage_immutable
BEFORE UPDATE ON cleanup_stage
BEGIN SELECT RAISE(ABORT, 'Cleanup Stage identity is immutable'); END;

CREATE TABLE cleanup_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    cleanup_stage_id            TEXT    NOT NULL UNIQUE
        REFERENCES cleanup_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    terminal_acceptance_id      TEXT    NOT NULL UNIQUE
        REFERENCES task_terminal_acceptance(id),
    dispatch_ticket_id          TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'ACTIVE', 'COMPLETED')),
    step_count                  INTEGER NOT NULL CHECK (step_count = 11),
    requested_at_ms             INTEGER NOT NULL,
    started_at_ms               INTEGER,
    completed_at_ms             INTEGER,
    summary_digest              TEXT,
    CHECK ((status = 'REQUESTED') = (started_at_ms IS NULL)),
    CHECK ((status = 'COMPLETED') = (completed_at_ms IS NOT NULL)),
    CHECK (status <> 'COMPLETED' OR summary_digest IS NOT NULL)
);

CREATE TRIGGER cleanup_operation_insert
BEFORE INSERT ON cleanup_operation
WHEN NEW.status <> 'REQUESTED'
  OR NOT EXISTS (
    SELECT 1 FROM cleanup_stage cleanup
    JOIN stage owner ON owner.id = cleanup.stage_id
    JOIN tasks task ON task.id = cleanup.task_id
    JOIN task_current_stage current ON current.stage_id = owner.id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    JOIN outbox wake ON wake.aggregate_id = ticket.id
    WHERE cleanup.stage_id = NEW.cleanup_stage_id
      AND cleanup.task_id = NEW.task_id
      AND cleanup.task_epoch = NEW.task_epoch
      AND cleanup.generation = NEW.stage_generation
      AND cleanup.terminal_acceptance_id = NEW.terminal_acceptance_id
      AND owner.checkpoint = 'WAITING_QUIESCENCE'
      AND owner.completed_at_ms IS NULL
      AND current.task_id = NEW.task_id
      AND current.stage_generation = NEW.stage_generation
      AND task.lifecycle_state = 'CLEANING'
      AND task.epoch = NEW.task_epoch
      AND ticket.operation_id = NEW.operation_id
      AND ticket.operation_kind = 'RUN_CLEANUP_OPERATION'
      AND ticket.async_family = 'CLEANUP'
      AND ticket.owner_kind = 'STAGE'
      AND ticket.owner_id = NEW.cleanup_stage_id
      AND ticket.callback_route = 'CLEANUP_OPERATION_RESULT'
      AND ticket.lane_mask = 256
      AND ticket.exclusive_task = 1
      AND ticket.writer_required = 1
      AND ticket.workspace_id = (
          SELECT trunk.workspace_id FROM threads trunk
          WHERE trunk.id = task.thread_id)
      AND ticket.trunk_id = task.thread_id
      AND ticket.task_id = NEW.task_id
      AND ticket.task_epoch = NEW.task_epoch
      AND ticket.stage_id = NEW.cleanup_stage_id
      AND ticket.stage_generation = NEW.stage_generation
      AND ticket.attempt = NEW.semantic_attempt
      AND ticket.status = 'REQUESTED'
      AND wake.id = 'V2_DISPATCH_TICKET_REQUESTED:' || ticket.id
      AND wake.dedup_key = wake.id
      AND wake.aggregate_kind = 'DISPATCH_TICKET'
      AND wake.topic = 'V2_DISPATCH_TICKET_REQUESTED'
      AND wake.payload = ticket.id
      AND wake.status = 'PENDING'
      AND wake.attempts = 0
      AND wake.available_at_ms = ticket.created_at_ms
      AND wake.created_at_ms = ticket.created_at_ms
      AND wake.claim_owner IS NULL AND wake.lease_until_ms IS NULL
      AND wake.delivered_at_ms IS NULL AND wake.last_error IS NULL)
BEGIN SELECT RAISE(ABORT, 'CleanupOperation lacks its exact current Cleanup Stage'); END;

CREATE TRIGGER cleanup_operation_identity_immutable
BEFORE UPDATE OF cleanup_stage_id, task_id, task_epoch, stage_generation,
        terminal_acceptance_id, dispatch_ticket_id, operation_id,
        semantic_attempt, step_count,
        requested_at_ms ON cleanup_operation
WHEN NEW.cleanup_stage_id IS NOT OLD.cleanup_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.terminal_acceptance_id IS NOT OLD.terminal_acceptance_id
  OR NEW.dispatch_ticket_id IS NOT OLD.dispatch_ticket_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.step_count IS NOT OLD.step_count
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'CleanupOperation identity is immutable'); END;

CREATE TABLE cleanup_step (
    id                          TEXT    NOT NULL PRIMARY KEY,
    cleanup_operation_id        TEXT    NOT NULL
        REFERENCES cleanup_operation(id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    cleanup_stage_id            TEXT    NOT NULL REFERENCES cleanup_stage(stage_id),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    ordinal                     INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 11),
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'PROVE_NO_NEW_ADMISSIONS', 'RECONCILE_OPEN_WORK',
        'STOP_PROVIDER_SESSIONS', 'RECONCILE_VALIDATION',
        'SEAL_REVIEW_STATE', 'DISMISS_TASK_INTERACTIONS',
        'RELEASE_RUNTIME_LEASES', 'REMOVE_WORKTREE',
        'DELETE_LOCAL_BRANCH', 'DELETE_REMOTE_BRANCH',
        'RECORD_FINAL_EVIDENCE')),
    requirement                 TEXT    NOT NULL CHECK (requirement IN (
        'REQUIRED', 'OPTIONAL', 'NOT_APPLICABLE')),
    idempotency_key             TEXT    NOT NULL UNIQUE,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'WAIVED')),
    attempt_count               INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    execute_attempt_count       INTEGER NOT NULL DEFAULT 0
        CHECK (execute_attempt_count >= 0),
    attempt_limit               INTEGER NOT NULL CHECK (attempt_limit > 0),
    claim_mode                  TEXT CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    claim_owner                 TEXT,
    claimed_at_ms               INTEGER,
    lease_until_ms              INTEGER,
    failure_kind                TEXT CHECK (failure_kind IN (
        'DETERMINATE', 'INDETERMINATE')),
    last_error                  TEXT,
    completed_at_ms             INTEGER,
    UNIQUE (cleanup_operation_id, ordinal),
    CHECK ((status = 'CLAIMED') = (claim_mode IS NOT NULL
        AND claim_owner IS NOT NULL AND claimed_at_ms IS NOT NULL
        AND lease_until_ms IS NOT NULL)),
    CHECK (status <> 'CLAIMED' OR lease_until_ms > claimed_at_ms),
    CHECK (execute_attempt_count <= attempt_limit),
    CHECK ((status = 'FAILED') = (failure_kind IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'SKIPPED', 'WAIVED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED' OR requirement IN ('REQUIRED', 'OPTIONAL')),
    CHECK (status <> 'SKIPPED' OR requirement = 'NOT_APPLICABLE'),
    CHECK (status <> 'WAIVED' OR requirement = 'OPTIONAL')
);

CREATE TRIGGER cleanup_step_insert
BEFORE INSERT ON cleanup_step
WHEN NEW.status <> 'REQUESTED' OR NEW.attempt_count <> 0
  OR NEW.execute_attempt_count <> 0
  OR NOT EXISTS (
    SELECT 1 FROM cleanup_operation operation
    JOIN cleanup_stage cleanup ON cleanup.stage_id = operation.cleanup_stage_id
    WHERE operation.id = NEW.cleanup_operation_id
      AND operation.status = 'REQUESTED'
      AND operation.task_id = NEW.task_id
      AND operation.task_epoch = NEW.task_epoch
      AND operation.cleanup_stage_id = NEW.cleanup_stage_id
      AND operation.stage_generation = NEW.stage_generation
      AND NEW.ordinal = COALESCE((
          SELECT MAX(step.ordinal) + 1 FROM cleanup_step step
          WHERE step.cleanup_operation_id = operation.id), 1)
      AND NEW.kind = CASE NEW.ordinal
          WHEN 1 THEN 'PROVE_NO_NEW_ADMISSIONS'
          WHEN 2 THEN 'RECONCILE_OPEN_WORK'
          WHEN 3 THEN 'STOP_PROVIDER_SESSIONS'
          WHEN 4 THEN 'RECONCILE_VALIDATION'
          WHEN 5 THEN 'SEAL_REVIEW_STATE'
          WHEN 6 THEN 'DISMISS_TASK_INTERACTIONS'
          WHEN 7 THEN 'RELEASE_RUNTIME_LEASES'
          WHEN 8 THEN 'REMOVE_WORKTREE'
          WHEN 9 THEN 'DELETE_LOCAL_BRANCH'
          WHEN 10 THEN 'DELETE_REMOTE_BRANCH'
          WHEN 11 THEN 'RECORD_FINAL_EVIDENCE' END
      AND NEW.requirement = CASE NEW.ordinal
          WHEN 9 THEN cleanup.local_branch_requirement
          WHEN 10 THEN cleanup.remote_branch_requirement ELSE 'REQUIRED' END)
BEGIN SELECT RAISE(ABORT, 'CleanupStep kind, order, or requirement is invalid'); END;

CREATE TRIGGER cleanup_step_identity_immutable
BEFORE UPDATE OF cleanup_operation_id, task_id, task_epoch, cleanup_stage_id,
        stage_generation, ordinal, kind, requirement, idempotency_key,
        attempt_limit ON cleanup_step
WHEN NEW.cleanup_operation_id IS NOT OLD.cleanup_operation_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.cleanup_stage_id IS NOT OLD.cleanup_stage_id
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.ordinal IS NOT OLD.ordinal
  OR NEW.kind IS NOT OLD.kind
  OR NEW.requirement IS NOT OLD.requirement
  OR NEW.idempotency_key IS NOT OLD.idempotency_key
  OR NEW.attempt_limit IS NOT OLD.attempt_limit
BEGIN SELECT RAISE(ABORT, 'CleanupStep identity is immutable'); END;

CREATE TABLE cleanup_interaction_dismissal_evidence (
    id                              TEXT    NOT NULL PRIMARY KEY,
    cleanup_step_id                 TEXT    NOT NULL UNIQUE
        REFERENCES cleanup_step(id) ON DELETE CASCADE,
    cleanup_operation_id            TEXT    NOT NULL
        REFERENCES cleanup_operation(id) ON DELETE CASCADE,
    task_id                         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    dismissed_notification_count    INTEGER NOT NULL
        CHECK (dismissed_notification_count >= 0),
    canceled_permission_count       INTEGER NOT NULL
        CHECK (canceled_permission_count >= 0),
    notification_scope_evidence     TEXT    NOT NULL,
    permission_scope_evidence       TEXT    NOT NULL,
    recorded_at_ms                  INTEGER NOT NULL
);

CREATE TRIGGER cleanup_interaction_dismissal_evidence_insert
BEFORE INSERT ON cleanup_interaction_dismissal_evidence
WHEN NOT EXISTS (
    SELECT 1 FROM cleanup_step step
    JOIN cleanup_operation operation ON operation.id = step.cleanup_operation_id
    WHERE step.id = NEW.cleanup_step_id
      AND step.cleanup_operation_id = NEW.cleanup_operation_id
      AND step.task_id = NEW.task_id
      AND step.task_epoch = NEW.task_epoch
      AND step.ordinal = 6
      AND step.kind = 'DISMISS_TASK_INTERACTIONS'
      AND step.status = 'CLAIMED'
      AND operation.status = 'ACTIVE'
      AND NEW.dismissed_notification_count = (
          SELECT COUNT(*) FROM notifications notification
          WHERE notification.task_id = NEW.task_id
            AND notification.status = 'DISMISSED'
            AND notification.read_at_ms IS NOT NULL
            AND notification.read_at_ms <= NEW.recorded_at_ms)
      AND NEW.canceled_permission_count = (
          SELECT COUNT(*) FROM permission_request permission
          WHERE permission.state = 'CANCELED'
            AND permission.answered_at_ms IS NOT NULL
            AND permission.answered_at_ms <= NEW.recorded_at_ms
            AND ((permission.turn_kind = 'TASK' AND EXISTS (
                    SELECT 1 FROM task_turn turn
                    WHERE turn.id = permission.turn_id
                      AND turn.task_id = NEW.task_id))
              OR (permission.turn_kind = 'STAGE' AND EXISTS (
                    SELECT 1 FROM stage_turn turn
                    JOIN stage owner ON owner.id = turn.stage_id
                    WHERE turn.id = permission.turn_id
                      AND owner.task_id = NEW.task_id))))
      AND NOT EXISTS (
          SELECT 1 FROM notifications notification
          WHERE notification.task_id = NEW.task_id
            AND notification.status = 'UNREAD')
      AND NOT EXISTS (
          SELECT 1 FROM permission_request permission
          WHERE permission.state = 'OPEN'
            AND ((permission.turn_kind = 'TASK' AND EXISTS (
                    SELECT 1 FROM task_turn turn
                    WHERE turn.id = permission.turn_id
                      AND turn.task_id = NEW.task_id))
              OR (permission.turn_kind = 'STAGE' AND EXISTS (
                    SELECT 1 FROM stage_turn turn
                    JOIN stage owner ON owner.id = turn.stage_id
                    WHERE turn.id = permission.turn_id
                      AND owner.task_id = NEW.task_id)))))
BEGIN SELECT RAISE(ABORT, 'Cleanup interaction evidence is incomplete'); END;

CREATE TRIGGER cleanup_interaction_dismissal_evidence_immutable
BEFORE UPDATE ON cleanup_interaction_dismissal_evidence
BEGIN SELECT RAISE(ABORT, 'Cleanup interaction evidence is immutable'); END;

CREATE TABLE cleanup_step_attempt_result (
    id                          TEXT    NOT NULL PRIMARY KEY,
    cleanup_step_id             TEXT    NOT NULL REFERENCES cleanup_step(id) ON DELETE CASCADE,
    cleanup_operation_id        TEXT    NOT NULL REFERENCES cleanup_operation(id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    ordinal                     INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 11),
    attempt                     INTEGER NOT NULL CHECK (attempt > 0),
    claim_mode                  TEXT    NOT NULL CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    outcome                     TEXT    NOT NULL CHECK (outcome IN (
        'SUCCEEDED', 'FAILED', 'INDETERMINATE')),
    external_effect_id          TEXT,
    evidence                    TEXT    NOT NULL,
    evidence_digest             TEXT    NOT NULL,
    error_message               TEXT,
    recorded_at_ms              INTEGER NOT NULL,
    UNIQUE (cleanup_step_id, attempt),
    CHECK (outcome <> 'SUCCEEDED' OR error_message IS NULL),
    CHECK (outcome = 'SUCCEEDED' OR error_message IS NOT NULL),
    CHECK (outcome <> 'SUCCEEDED' OR ordinal NOT IN (8, 9, 10)
        OR external_effect_id IS NOT NULL)
);

CREATE TRIGGER cleanup_step_attempt_result_insert
BEFORE INSERT ON cleanup_step_attempt_result
WHEN NOT EXISTS (
    SELECT 1 FROM cleanup_step step
    JOIN cleanup_operation operation ON operation.id = step.cleanup_operation_id
    JOIN cleanup_stage cleanup ON cleanup.stage_id = step.cleanup_stage_id
    JOIN tasks task ON task.id = step.task_id
    WHERE step.id = NEW.cleanup_step_id
      AND step.cleanup_operation_id = NEW.cleanup_operation_id
      AND step.task_id = NEW.task_id
      AND step.task_epoch = NEW.task_epoch
      AND step.ordinal = NEW.ordinal
      AND step.status = 'CLAIMED'
      AND step.attempt_count = NEW.attempt
      AND step.claim_mode = NEW.claim_mode
      AND operation.status = 'ACTIVE'
      AND task.lifecycle_state = 'CLEANING'
      AND task.epoch = NEW.task_epoch
      AND ((step.ordinal = 1
            AND task.lifecycle_state = 'CLEANING'
            AND cleanup.stage_id = step.cleanup_stage_id)
        OR step.ordinal <> 1)
      AND (step.ordinal <> 2 OR (
          NOT EXISTS (
              SELECT 1 FROM task_turn turn
              WHERE turn.task_id = NEW.task_id
                AND turn.task_epoch = NEW.task_epoch
                AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
          AND NOT EXISTS (
              SELECT 1 FROM stage_turn turn
              JOIN stage owner ON owner.id = turn.stage_id
              WHERE owner.task_id = NEW.task_id
                AND turn.task_epoch = NEW.task_epoch
                AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
          AND NOT EXISTS (
              SELECT 1 FROM dispatch_ticket ticket
              WHERE ticket.task_id = NEW.task_id
                AND ticket.task_epoch = NEW.task_epoch
                AND ticket.async_family <> 'CLEANUP'
                AND ticket.status IN ('REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                    'RESULT_PENDING', 'CLAIMED', 'RUNNING', 'DELIVERING'))
          AND NOT EXISTS (
              SELECT 1 FROM ci_repair_episode episode
              WHERE episode.task_id = NEW.task_id
                AND episode.task_epoch = NEW.task_epoch
                AND episode.status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED'))
          AND NOT EXISTS (
              SELECT 1 FROM branch_sync_episode episode
              WHERE episode.task_id = NEW.task_id
                AND episode.task_epoch = NEW.task_epoch
                AND episode.status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED'))
          AND NOT EXISTS (
              SELECT 1 FROM remote_mark_ready_operation remote_operation
              WHERE remote_operation.task_id = NEW.task_id
                AND remote_operation.task_epoch = NEW.task_epoch
                AND remote_operation.status NOT IN ('SUCCEEDED', 'CANCELED'))
          AND NOT EXISTS (
              SELECT 1 FROM remote_merge_operation merge_operation
              WHERE merge_operation.task_id = NEW.task_id
                AND merge_operation.task_epoch = NEW.task_epoch
                AND merge_operation.status NOT IN (
                    'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED'))))
      AND (step.ordinal <> 3 OR NOT EXISTS (
          SELECT 1 FROM agent_execution execution
          JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
          WHERE ticket.task_id = NEW.task_id
            AND execution.status IN ('STARTING', 'RUNNING', 'UNKNOWN')))
      AND (step.ordinal <> 4 OR (
          NOT EXISTS (
              SELECT 1 FROM validation_operation validation
              WHERE validation.task_id = NEW.task_id
                AND validation.status IN ('REQUESTED', 'DISPATCHED'))
          AND NOT EXISTS (
              SELECT 1 FROM validation_pass validation
              WHERE validation.task_id = NEW.task_id
                AND validation.ended_at_ms IS NULL)))
      AND (step.ordinal <> 5 OR (
          NOT EXISTS (
              SELECT 1 FROM local_feedback_batch batch
              WHERE batch.task_id = NEW.task_id
                AND batch.status IN ('BUILDING', 'FROZEN', 'QUEUED', 'DISPATCHED'))
          AND NOT EXISTS (
              SELECT 1 FROM remote_feedback_batch batch
              WHERE batch.task_id = NEW.task_id
                AND batch.status NOT IN ('COMPLETED', 'SUPERSEDED'))
          AND NOT EXISTS (
              SELECT 1 FROM remote_mark_ready_authorization authorization
              WHERE authorization.task_id = NEW.task_id
                AND authorization.status = 'ACTIVE')
          AND NOT EXISTS (
              SELECT 1 FROM remote_merge_authorization authorization
              WHERE authorization.task_id = NEW.task_id
                AND authorization.status = 'ACTIVE')))
      AND (step.ordinal <> 6 OR NOT EXISTS (
          SELECT 1 FROM permission_request permission
          WHERE permission.state = 'OPEN'
            AND ((permission.turn_kind = 'TASK' AND EXISTS (
                    SELECT 1 FROM task_turn turn
                    WHERE turn.id = permission.turn_id
                      AND turn.task_id = NEW.task_id))
              OR (permission.turn_kind = 'STAGE' AND EXISTS (
                    SELECT 1 FROM stage_turn turn
                    JOIN stage owner ON owner.id = turn.stage_id
                    WHERE turn.id = permission.turn_id
                      AND owner.task_id = NEW.task_id)))))
      AND (step.ordinal <> 6 OR EXISTS (
          SELECT 1 FROM cleanup_interaction_dismissal_evidence interaction
          WHERE interaction.cleanup_step_id = step.id
            AND interaction.cleanup_operation_id = operation.id
            AND interaction.task_id = NEW.task_id
            AND interaction.task_epoch = NEW.task_epoch
            AND interaction.recorded_at_ms <= NEW.recorded_at_ms))
      AND (step.ordinal <> 7 OR (
          NOT EXISTS (
              SELECT 1 FROM capacity_lease lease
              JOIN dispatch_ticket ticket ON ticket.id = lease.ticket_id
              WHERE lease.workflow_source = 'V2'
                AND lease.task_id = NEW.task_id
                AND lease.task_epoch = NEW.task_epoch
                AND ticket.async_family <> 'CLEANUP'
                AND lease.released_at_ms IS NULL
                AND lease.expires_at_ms > NEW.recorded_at_ms)
          AND NOT EXISTS (
              SELECT 1 FROM worktree_leases lease
              WHERE lease.workflow_version = 'V2'
                AND lease.task_id = NEW.task_id
                AND lease.task_epoch = NEW.task_epoch
                AND lease.expires_at_ms > NEW.recorded_at_ms))))
BEGIN SELECT RAISE(ABORT, 'Cleanup result lacks exact claimed or reconciled evidence'); END;

CREATE TRIGGER cleanup_step_attempt_result_immutable
BEFORE UPDATE ON cleanup_step_attempt_result
BEGIN SELECT RAISE(ABORT, 'Cleanup step attempt result is immutable'); END;

CREATE TABLE cleanup_step_skip_evidence (
    id                  TEXT    NOT NULL PRIMARY KEY,
    cleanup_step_id     TEXT    NOT NULL UNIQUE REFERENCES cleanup_step(id) ON DELETE CASCADE,
    reason              TEXT    NOT NULL,
    evidence            TEXT    NOT NULL,
    skipped_by          TEXT    NOT NULL,
    skipped_at_ms       INTEGER NOT NULL
);

CREATE TRIGGER cleanup_step_skip_evidence_insert
BEFORE INSERT ON cleanup_step_skip_evidence
WHEN NOT EXISTS (
    SELECT 1 FROM cleanup_step step
    WHERE step.id = NEW.cleanup_step_id
      AND step.requirement = 'NOT_APPLICABLE'
      AND step.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'Cleanup skip requires an inapplicable requested step'); END;

CREATE TRIGGER cleanup_step_skip_evidence_immutable
BEFORE UPDATE ON cleanup_step_skip_evidence
BEGIN SELECT RAISE(ABORT, 'Cleanup skip evidence is immutable'); END;

CREATE TABLE cleanup_step_waiver (
    id                  TEXT    NOT NULL PRIMARY KEY,
    cleanup_step_id     TEXT    NOT NULL UNIQUE REFERENCES cleanup_step(id) ON DELETE CASCADE,
    actor_id            TEXT    NOT NULL,
    reason              TEXT    NOT NULL,
    evidence            TEXT    NOT NULL,
    waived_at_ms        INTEGER NOT NULL
);

CREATE TRIGGER cleanup_step_waiver_insert
BEFORE INSERT ON cleanup_step_waiver
WHEN NOT EXISTS (
    SELECT 1 FROM cleanup_step step
    JOIN task_blocker blocker
      ON blocker.owner_id = step.cleanup_operation_id
    WHERE step.id = NEW.cleanup_step_id
      AND step.requirement = 'OPTIONAL'
      AND step.status = 'FAILED'
      AND blocker.task_id = step.task_id
      AND blocker.stage_id = step.cleanup_stage_id
      AND blocker.owner_kind = 'OPERATION'
      AND blocker.subject_revision = CAST(step.ordinal AS TEXT)
      AND blocker.status = 'OPEN')
BEGIN SELECT RAISE(ABORT, 'Cleanup waiver requires an explicit optional failure blocker'); END;

CREATE TRIGGER cleanup_step_waiver_immutable
BEFORE UPDATE ON cleanup_step_waiver
BEGIN SELECT RAISE(ABORT, 'Cleanup waiver is immutable'); END;

CREATE TRIGGER cleanup_step_transition
BEFORE UPDATE OF status ON cleanup_step
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status IN ('REQUESTED', 'FAILED') AND NEW.status = 'CLAIMED')
    OR (OLD.status = 'CLAIMED' AND NEW.status IN ('SUCCEEDED', 'FAILED'))
    OR (OLD.status = 'REQUESTED' AND NEW.status = 'SKIPPED')
    OR (OLD.status = 'FAILED' AND NEW.status = 'WAIVED'))
BEGIN SELECT RAISE(ABORT, 'CleanupStep transition is invalid'); END;

CREATE TRIGGER cleanup_step_claim
BEFORE UPDATE OF status ON cleanup_step
WHEN NEW.status = 'CLAIMED'
  AND (NEW.attempt_count <> OLD.attempt_count + 1
    OR NEW.execute_attempt_count <> OLD.execute_attempt_count
        + CASE NEW.claim_mode WHEN 'EXECUTE' THEN 1 ELSE 0 END
    OR (NEW.claim_mode = 'EXECUTE'
        AND OLD.execute_attempt_count >= OLD.attempt_limit)
    OR OLD.status NOT IN ('REQUESTED', 'CLAIMED', 'FAILED')
    OR (OLD.status = 'CLAIMED'
        AND (NEW.claim_mode <> 'PROBE'
          OR NEW.claimed_at_ms < OLD.lease_until_ms))
    OR (OLD.status = 'FAILED' AND OLD.failure_kind = 'INDETERMINATE'
        AND NEW.claim_mode <> 'PROBE')
    OR ((OLD.status = 'REQUESTED'
          OR (OLD.status = 'FAILED' AND OLD.failure_kind = 'DETERMINATE'))
        AND NEW.claim_mode <> 'EXECUTE')
    OR NEW.failure_kind IS NOT NULL OR NEW.last_error IS NOT NULL
    OR NEW.completed_at_ms IS NOT NULL
    OR EXISTS (
        SELECT 1 FROM cleanup_step previous
        WHERE previous.cleanup_operation_id = NEW.cleanup_operation_id
          AND previous.ordinal < NEW.ordinal
          AND NOT ((previous.requirement = 'REQUIRED'
                    AND previous.status = 'SUCCEEDED')
            OR (previous.requirement = 'OPTIONAL'
                    AND previous.status IN ('SUCCEEDED', 'WAIVED'))
            OR (previous.requirement = 'NOT_APPLICABLE'
                    AND previous.status = 'SKIPPED')))
    OR NOT EXISTS (
        SELECT 1 FROM cleanup_operation operation
        JOIN stage owner ON owner.id = operation.cleanup_stage_id
        JOIN tasks task ON task.id = operation.task_id
        WHERE operation.id = NEW.cleanup_operation_id
          AND operation.status = 'ACTIVE'
          AND owner.checkpoint = 'CLEANING'
          AND owner.completed_at_ms IS NULL
          AND task.lifecycle_state = 'CLEANING'
          AND task.epoch = NEW.task_epoch))
BEGIN SELECT RAISE(ABORT, 'CleanupStep claim is stale, unordered, or unsafe'); END;

CREATE TRIGGER cleanup_step_attempt
BEFORE UPDATE OF attempt_count, execute_attempt_count ON cleanup_step
WHEN NEW.status <> 'CLAIMED'
  OR NEW.attempt_count <> OLD.attempt_count + 1
  OR NEW.execute_attempt_count <> OLD.execute_attempt_count
        + CASE NEW.claim_mode WHEN 'EXECUTE' THEN 1 ELSE 0 END
  OR OLD.status NOT IN ('REQUESTED', 'CLAIMED', 'FAILED')
  OR (OLD.status = 'CLAIMED'
      AND (NEW.claim_mode <> 'PROBE'
        OR NEW.claimed_at_ms < OLD.lease_until_ms))
  OR (OLD.status = 'FAILED' AND OLD.failure_kind = 'INDETERMINATE'
      AND NEW.claim_mode <> 'PROBE')
  OR ((OLD.status = 'REQUESTED'
        OR (OLD.status = 'FAILED' AND OLD.failure_kind = 'DETERMINATE'))
      AND NEW.claim_mode <> 'EXECUTE')
  OR (NEW.claim_mode = 'EXECUTE'
      AND OLD.execute_attempt_count >= OLD.attempt_limit)
  OR NEW.claim_owner IS NULL OR NEW.claimed_at_ms IS NULL
  OR NEW.lease_until_ms IS NULL OR NEW.lease_until_ms <= NEW.claimed_at_ms
  OR NEW.failure_kind IS NOT NULL OR NEW.last_error IS NOT NULL
  OR NEW.completed_at_ms IS NOT NULL
  OR EXISTS (
      SELECT 1 FROM cleanup_step previous
      WHERE previous.cleanup_operation_id = NEW.cleanup_operation_id
        AND previous.ordinal < NEW.ordinal
        AND NOT ((previous.requirement = 'REQUIRED'
                  AND previous.status = 'SUCCEEDED')
          OR (previous.requirement = 'OPTIONAL'
                  AND previous.status IN ('SUCCEEDED', 'WAIVED'))
          OR (previous.requirement = 'NOT_APPLICABLE'
                  AND previous.status = 'SKIPPED')))
  OR NOT EXISTS (
      SELECT 1 FROM cleanup_operation operation
      JOIN stage owner ON owner.id = operation.cleanup_stage_id
      JOIN tasks task ON task.id = operation.task_id
      WHERE operation.id = NEW.cleanup_operation_id
        AND operation.status = 'ACTIVE'
        AND owner.checkpoint = 'CLEANING'
        AND owner.completed_at_ms IS NULL
        AND task.lifecycle_state = 'CLEANING'
        AND task.epoch = NEW.task_epoch)
BEGIN SELECT RAISE(ABORT, 'CleanupStep attempt must be an exact claim'); END;

CREATE TRIGGER cleanup_step_claim_fields
BEFORE UPDATE OF claim_mode, claim_owner, claimed_at_ms, lease_until_ms
        ON cleanup_step
WHEN NOT ((NEW.status = 'CLAIMED'
            AND NEW.attempt_count = OLD.attempt_count + 1)
        OR (OLD.status = 'CLAIMED' AND NEW.status <> 'CLAIMED'
            AND NEW.attempt_count = OLD.attempt_count
            AND NEW.claim_mode IS NULL AND NEW.claim_owner IS NULL
            AND NEW.claimed_at_ms IS NULL AND NEW.lease_until_ms IS NULL))
BEGIN SELECT RAISE(ABORT, 'CleanupStep claim fields change only with an attempt boundary'); END;

CREATE TRIGGER cleanup_step_result_fields
BEFORE UPDATE OF failure_kind, last_error, completed_at_ms ON cleanup_step
WHEN (NEW.failure_kind IS NOT OLD.failure_kind
    OR NEW.last_error IS NOT OLD.last_error
    OR NEW.completed_at_ms IS NOT OLD.completed_at_ms)
  AND NEW.status IS OLD.status
  AND NOT (NEW.status = 'CLAIMED'
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.failure_kind IS NULL AND NEW.last_error IS NULL
    AND NEW.completed_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT, 'CleanupStep result changes only at a result or claim boundary'); END;

CREATE TRIGGER cleanup_step_success
BEFORE UPDATE OF status ON cleanup_step
WHEN NEW.status = 'SUCCEEDED'
  AND NOT EXISTS (
      SELECT 1 FROM cleanup_step_attempt_result result
      WHERE result.cleanup_step_id = NEW.id
        AND result.cleanup_operation_id = NEW.cleanup_operation_id
        AND result.task_id = NEW.task_id
        AND result.task_epoch = NEW.task_epoch
        AND result.ordinal = NEW.ordinal
        AND result.attempt = NEW.attempt_count
        AND result.claim_mode = OLD.claim_mode
        AND result.outcome = 'SUCCEEDED')
BEGIN SELECT RAISE(ABORT, 'CleanupStep success lacks exact attempt evidence'); END;

CREATE TRIGGER cleanup_step_failure
BEFORE UPDATE OF status ON cleanup_step
WHEN NEW.status = 'FAILED'
  AND (NOT EXISTS (
      SELECT 1 FROM cleanup_step_attempt_result result
      WHERE result.cleanup_step_id = NEW.id
        AND result.attempt = NEW.attempt_count
        AND result.claim_mode = OLD.claim_mode
        AND ((result.outcome = 'FAILED' AND NEW.failure_kind = 'DETERMINATE')
          OR (result.outcome = 'INDETERMINATE'
                AND NEW.failure_kind = 'INDETERMINATE')))
    OR NOT EXISTS (
      SELECT 1 FROM task_blocker blocker
      WHERE blocker.task_id = NEW.task_id
        AND blocker.stage_id = NEW.cleanup_stage_id
        AND blocker.owner_kind = 'OPERATION'
        AND blocker.owner_id = NEW.cleanup_operation_id
        AND blocker.subject_revision = CAST(NEW.ordinal AS TEXT)
        AND blocker.status = 'OPEN'))
BEGIN SELECT RAISE(ABORT, 'CleanupStep failure lacks exact result and blocker'); END;

CREATE TRIGGER cleanup_step_skip
BEFORE UPDATE OF status ON cleanup_step
WHEN NEW.status = 'SKIPPED'
  AND NOT EXISTS (
      SELECT 1 FROM cleanup_step_skip_evidence evidence
      WHERE evidence.cleanup_step_id = NEW.id)
BEGIN SELECT RAISE(ABORT, 'CleanupStep skip lacks explicit inapplicability evidence'); END;

CREATE TRIGGER cleanup_step_waive
BEFORE UPDATE OF status ON cleanup_step
WHEN NEW.status = 'WAIVED'
  AND NOT EXISTS (
      SELECT 1 FROM cleanup_step_waiver waiver
      WHERE waiver.cleanup_step_id = NEW.id)
BEGIN SELECT RAISE(ABORT, 'CleanupStep waiver lacks explicit user evidence'); END;

CREATE TRIGGER cleanup_step_terminal_immutable
BEFORE UPDATE ON cleanup_step
WHEN OLD.status IN ('SUCCEEDED', 'SKIPPED', 'WAIVED')
BEGIN SELECT RAISE(ABORT, 'Settled CleanupStep is immutable'); END;

CREATE TRIGGER cleanup_operation_transition
BEFORE UPDATE OF status ON cleanup_operation
WHEN NEW.status IS NOT OLD.status
  AND NOT ((OLD.status = 'REQUESTED' AND NEW.status = 'ACTIVE')
        OR (OLD.status = 'ACTIVE' AND NEW.status = 'COMPLETED'))
BEGIN SELECT RAISE(ABORT, 'CleanupOperation transition is invalid'); END;

CREATE TRIGGER cleanup_operation_active
BEFORE UPDATE OF status ON cleanup_operation
WHEN NEW.status = 'ACTIVE'
  AND (OLD.status <> 'REQUESTED'
    OR NEW.started_at_ms IS NULL OR NEW.completed_at_ms IS NOT NULL
    OR NEW.summary_digest IS NOT NULL
    OR (SELECT COUNT(*) FROM cleanup_step step
        WHERE step.cleanup_operation_id = NEW.id) <> NEW.step_count
    OR EXISTS (
        SELECT 1 FROM cleanup_step step
        WHERE step.cleanup_operation_id = NEW.id
          AND step.status <> 'REQUESTED')
    OR NOT EXISTS (
        SELECT 1 FROM dispatch_ticket ticket
        JOIN cleanup_stage cleanup ON cleanup.stage_id = NEW.cleanup_stage_id
        JOIN stage owner ON owner.id = cleanup.stage_id
        JOIN tasks task ON task.id = cleanup.task_id
        WHERE ticket.id = NEW.dispatch_ticket_id
          AND ticket.operation_id = NEW.operation_id
          AND ticket.status IN ('CLAIMED', 'RUNNING')
          AND ticket.claim_purpose = 'EXECUTE'
          AND ticket.task_id = NEW.task_id
          AND ticket.task_epoch = NEW.task_epoch
          AND ticket.stage_id = NEW.cleanup_stage_id
          AND ticket.stage_generation = NEW.stage_generation
          AND owner.checkpoint = 'WAITING_QUIESCENCE'
          AND owner.completed_at_ms IS NULL
          AND task.lifecycle_state = 'CLEANING'
          AND task.epoch = NEW.task_epoch))
BEGIN SELECT RAISE(ABORT, 'CleanupOperation cannot activate without its claimed dispatch and full ledger'); END;

CREATE TRIGGER cleanup_operation_complete
BEFORE UPDATE OF status ON cleanup_operation
WHEN NEW.status = 'COMPLETED'
  AND (OLD.status <> 'ACTIVE'
    OR NEW.summary_digest IS NULL OR NEW.completed_at_ms IS NULL
    OR (SELECT COUNT(*) FROM cleanup_step step
        WHERE step.cleanup_operation_id = NEW.id) <> NEW.step_count
    OR EXISTS (
        SELECT 1 FROM cleanup_step step
        WHERE step.cleanup_operation_id = NEW.id
          AND NOT ((step.requirement = 'REQUIRED' AND step.status = 'SUCCEEDED')
            OR (step.requirement = 'OPTIONAL'
                AND step.status IN ('SUCCEEDED', 'WAIVED'))
            OR (step.requirement = 'NOT_APPLICABLE'
                AND step.status = 'SKIPPED')))
    OR EXISTS (
        SELECT 1 FROM task_blocker blocker
        WHERE blocker.task_id = NEW.task_id
          AND blocker.stage_id = NEW.cleanup_stage_id
          AND blocker.owner_kind = 'OPERATION'
          AND blocker.owner_id = NEW.id
          AND blocker.status = 'OPEN')
    OR NOT EXISTS (
        SELECT 1 FROM cleanup_step final_step
        WHERE final_step.cleanup_operation_id = NEW.id
          AND final_step.ordinal = 11
          AND final_step.kind = 'RECORD_FINAL_EVIDENCE'
          AND final_step.status = 'SUCCEEDED'
          AND EXISTS (
              SELECT 1 FROM cleanup_step_attempt_result result
              WHERE result.cleanup_step_id = final_step.id
                AND result.attempt = final_step.attempt_count
                AND result.outcome = 'SUCCEEDED'
                AND result.evidence_digest = NEW.summary_digest))
    OR NOT EXISTS (
        SELECT 1 FROM dispatch_ticket ticket
        WHERE ticket.id = NEW.dispatch_ticket_id
          AND ticket.operation_id = NEW.operation_id
          AND ticket.status = 'RESULT_PENDING'
          AND ticket.pending_result_outcome = 'SUCCEEDED'
          AND ticket.pending_result_payload = NEW.summary_digest
          AND ticket.pending_result_evidence IS NOT NULL
          AND ticket.pending_result_task_epoch = NEW.task_epoch
          AND ticket.pending_result_stage_id = NEW.cleanup_stage_id
          AND ticket.pending_result_stage_generation = NEW.stage_generation
          AND ticket.pending_result_operation_id = NEW.operation_id
          AND ticket.pending_result_attempt = NEW.semantic_attempt))
BEGIN SELECT RAISE(ABORT, 'CleanupOperation completion lacks exact settled evidence'); END;

CREATE TRIGGER cleanup_operation_result_fields
BEFORE UPDATE OF started_at_ms, completed_at_ms, summary_digest
        ON cleanup_operation
WHEN NEW.status IS OLD.status
  AND (NEW.started_at_ms IS NOT OLD.started_at_ms
    OR NEW.completed_at_ms IS NOT OLD.completed_at_ms
    OR NEW.summary_digest IS NOT OLD.summary_digest)
BEGIN SELECT RAISE(ABORT, 'CleanupOperation result fields change only with status'); END;

CREATE TRIGGER cleanup_operation_terminal_immutable
BEFORE UPDATE ON cleanup_operation
WHEN OLD.status = 'COMPLETED'
BEGIN SELECT RAISE(ABORT, 'Terminal CleanupOperation is immutable'); END;

CREATE TRIGGER cleanup_stage_checkpoint_update
BEFORE UPDATE OF checkpoint, completed_at_ms, end_reason ON stage
WHEN OLD.kind = 'CLEANUP'
  AND NOT ((OLD.checkpoint = 'WAITING_QUIESCENCE'
            AND NEW.checkpoint = 'CLEANING'
            AND NEW.completed_at_ms IS NULL AND NEW.end_reason IS NULL
            AND EXISTS (
                SELECT 1 FROM cleanup_operation operation
                WHERE operation.cleanup_stage_id = OLD.id
                  AND operation.status = 'ACTIVE')
            AND EXISTS (
                SELECT 1 FROM stage_transition transition
                WHERE transition.stage_id = OLD.id
                  AND transition.generation = OLD.generation
                  AND transition.from_checkpoint = OLD.checkpoint
                  AND transition.to_checkpoint = NEW.checkpoint
                  AND transition.stage_version = NEW.version
                  AND transition.cause = 'START_CLEANUP'))
        OR (OLD.checkpoint = 'CLEANING'
            AND NEW.checkpoint = 'COMPLETED'
            AND NEW.completed_at_ms IS NOT NULL
            AND NEW.end_reason = 'NORMAL'
            AND EXISTS (
                SELECT 1 FROM cleanup_operation operation
                WHERE operation.cleanup_stage_id = OLD.id
                  AND operation.status = 'COMPLETED')
            AND EXISTS (
                SELECT 1 FROM stage_transition transition
                WHERE transition.stage_id = OLD.id
                  AND transition.generation = OLD.generation
                  AND transition.from_checkpoint = OLD.checkpoint
                  AND transition.to_checkpoint = NEW.checkpoint
                  AND transition.stage_version = NEW.version
                  AND transition.cause = 'ACCEPT_CLEANUP_COMPLETION')))
BEGIN SELECT RAISE(ABORT, 'Cleanup Stage checkpoint lacks its exact operation transition'); END;

-- Cleanup failures are first-class Operation blockers. Preserve every owner
-- type installed by the remote protocol and add only the CleanupOperation.
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
                      AND operation.cleanup_stage_id = NEW.stage_id))
            THEN RAISE(ABORT, 'Task blocker Operation owner is invalid')
    END;
END;

-- The durable Task result is created exactly once, after Cleanup has been
-- accepted and its dispatch result has itself been durably delivered.
CREATE TABLE task_outcome (
    id                              TEXT    NOT NULL PRIMARY KEY,
    task_id                         TEXT    NOT NULL UNIQUE
        REFERENCES tasks(id) ON DELETE CASCADE,
    trunk_id                        TEXT    NOT NULL REFERENCES threads(id),
    task_epoch                      INTEGER NOT NULL CHECK (task_epoch > 0),
    terminal_acceptance_id          TEXT    NOT NULL UNIQUE
        REFERENCES task_terminal_acceptance(id),
    cleanup_operation_id            TEXT    NOT NULL UNIQUE
        REFERENCES cleanup_operation(id),
    cleanup_stage_id                TEXT    NOT NULL UNIQUE
        REFERENCES cleanup_stage(stage_id),
    terminal_reason                 TEXT    NOT NULL CHECK (terminal_reason IN (
        'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')),
    pr_id                           TEXT REFERENCES pr(id),
    remote_pr_binding_id            TEXT REFERENCES remote_pr_binding(id),
    remote_terminal_observation_id  TEXT REFERENCES remote_terminal_observation(id),
    observed_head_sha               TEXT,
    cleanup_summary_digest          TEXT    NOT NULL,
    summary_state                   TEXT    NOT NULL CHECK (summary_state IN (
        'FALLBACK', 'BRAIN_GENERATED')),
    summary_text                    TEXT    NOT NULL,
    summary_digest                  TEXT    NOT NULL,
    summary_operation_id            TEXT,
    follow_up_proposals_json        TEXT    NOT NULL DEFAULT '[]'
        CHECK (json_valid(follow_up_proposals_json)),
    backlog_items_json              TEXT    NOT NULL DEFAULT '[]'
        CHECK (json_valid(backlog_items_json)),
    recorded_at_ms                  INTEGER NOT NULL,
    summary_updated_at_ms           INTEGER,
    CHECK ((pr_id IS NULL) = (remote_pr_binding_id IS NULL)),
    CHECK ((summary_state = 'FALLBACK'
            AND summary_operation_id IS NULL
            AND summary_updated_at_ms IS NULL
            AND summary_text = 'TaskOutcome:' || task_id || ':'
                || terminal_reason || ':' || cleanup_summary_digest
            AND summary_digest = cleanup_summary_digest
            AND follow_up_proposals_json = '[]'
            AND backlog_items_json = '[]')
        OR (summary_state = 'BRAIN_GENERATED'
            AND summary_operation_id IS NOT NULL
            AND summary_updated_at_ms IS NOT NULL))
);

CREATE TRIGGER task_outcome_insert
BEFORE INSERT ON task_outcome
WHEN NEW.summary_state <> 'FALLBACK'
  OR NOT EXISTS (
    SELECT 1 FROM tasks task
    JOIN threads trunk ON trunk.id = task.thread_id
    JOIN task_terminal_acceptance acceptance
      ON acceptance.id = NEW.terminal_acceptance_id
    JOIN cleanup_operation cleanup ON cleanup.id = NEW.cleanup_operation_id
    JOIN cleanup_stage cleanup_owner ON cleanup_owner.stage_id = NEW.cleanup_stage_id
    JOIN stage owner ON owner.id = cleanup_owner.stage_id
    JOIN task_current_stage current ON current.stage_id = owner.id
    JOIN dispatch_ticket ticket ON ticket.id = cleanup.dispatch_ticket_id
    WHERE task.id = NEW.task_id
      AND task.thread_id = NEW.trunk_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'CLEANING'
      AND task.epoch = NEW.task_epoch
      AND trunk.turn_version = 'V2'
      AND acceptance.task_id = NEW.task_id
      AND acceptance.task_epoch = NEW.task_epoch
      AND acceptance.kind = NEW.terminal_reason
      AND acceptance.remote_terminal_observation_id
            IS NEW.remote_terminal_observation_id
      AND acceptance.observed_head_sha IS NEW.observed_head_sha
      AND cleanup.cleanup_stage_id = NEW.cleanup_stage_id
      AND cleanup.task_id = NEW.task_id
      AND cleanup.task_epoch = NEW.task_epoch
      AND cleanup.terminal_acceptance_id = acceptance.id
      AND cleanup.status = 'COMPLETED'
      AND cleanup.summary_digest = NEW.cleanup_summary_digest
      AND cleanup_owner.task_id = NEW.task_id
      AND cleanup_owner.task_epoch = NEW.task_epoch
      AND cleanup_owner.terminal_reason = NEW.terminal_reason
      AND owner.checkpoint = 'COMPLETED'
      AND owner.completed_at_ms IS NOT NULL
      AND current.task_id = NEW.task_id
      AND current.stage_generation = owner.generation
      AND ticket.status = 'SUCCEEDED'
      AND ticket.delivery_acceptance = 'ACCEPTED'
      AND ticket.delivery_evidence IS NOT NULL
      AND ((NEW.remote_pr_binding_id IS NULL
            AND NEW.pr_id IS NULL)
        OR EXISTS (
            SELECT 1 FROM remote_pr_binding binding
            WHERE binding.id = NEW.remote_pr_binding_id
              AND binding.task_id = NEW.task_id
              AND binding.pr_id = NEW.pr_id))
      AND (NEW.remote_terminal_observation_id IS NULL OR EXISTS (
            SELECT 1 FROM remote_terminal_observation remote
            WHERE remote.id = NEW.remote_terminal_observation_id
              AND remote.task_id = NEW.task_id
              AND remote.task_epoch = NEW.task_epoch
              AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
              AND remote.head_sha = NEW.observed_head_sha)))
BEGIN SELECT RAISE(ABORT, 'TaskOutcome lacks exact accepted Cleanup proof'); END;

CREATE TRIGGER task_outcome_identity_immutable
BEFORE UPDATE OF task_id, trunk_id, task_epoch, terminal_acceptance_id,
        cleanup_operation_id, cleanup_stage_id, terminal_reason, pr_id,
        remote_pr_binding_id, remote_terminal_observation_id,
        observed_head_sha, cleanup_summary_digest, recorded_at_ms
        ON task_outcome
WHEN NEW.task_id IS NOT OLD.task_id
  OR NEW.trunk_id IS NOT OLD.trunk_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.terminal_acceptance_id IS NOT OLD.terminal_acceptance_id
  OR NEW.cleanup_operation_id IS NOT OLD.cleanup_operation_id
  OR NEW.cleanup_stage_id IS NOT OLD.cleanup_stage_id
  OR NEW.terminal_reason IS NOT OLD.terminal_reason
  OR NEW.pr_id IS NOT OLD.pr_id
  OR NEW.remote_pr_binding_id IS NOT OLD.remote_pr_binding_id
  OR NEW.remote_terminal_observation_id IS NOT OLD.remote_terminal_observation_id
  OR NEW.observed_head_sha IS NOT OLD.observed_head_sha
  OR NEW.cleanup_summary_digest IS NOT OLD.cleanup_summary_digest
  OR NEW.recorded_at_ms IS NOT OLD.recorded_at_ms
BEGIN SELECT RAISE(ABORT, 'TaskOutcome identity is immutable'); END;

CREATE TRIGGER task_outcome_delete_immutable
BEFORE DELETE ON task_outcome
BEGIN SELECT RAISE(ABORT, 'TaskOutcome cannot be deleted'); END;

CREATE TABLE trunk_outcome_inbox (
    id                      TEXT    NOT NULL PRIMARY KEY,
    trunk_id                TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    task_id                 TEXT    NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
    task_outcome_id         TEXT    NOT NULL UNIQUE REFERENCES task_outcome(id) ON DELETE CASCADE,
    delivery_key            TEXT    NOT NULL UNIQUE,
    fallback_summary_marker TEXT    NOT NULL,
    status                  TEXT    NOT NULL CHECK (status IN ('PENDING', 'DELIVERED')),
    created_at_ms           INTEGER NOT NULL,
    delivered_at_ms         INTEGER,
    delivery_evidence       TEXT,
    CHECK ((status = 'DELIVERED') =
        (delivered_at_ms IS NOT NULL AND delivery_evidence IS NOT NULL))
);

CREATE TRIGGER trunk_outcome_inbox_insert
BEFORE INSERT ON trunk_outcome_inbox
WHEN NEW.id <> 'TRUNK_OUTCOME:' || NEW.task_outcome_id
  OR NEW.delivery_key <> NEW.id
  OR NEW.status <> 'PENDING'
  OR NEW.delivered_at_ms IS NOT NULL OR NEW.delivery_evidence IS NOT NULL
  OR NOT EXISTS (
      SELECT 1 FROM task_outcome outcome
      WHERE outcome.id = NEW.task_outcome_id
        AND outcome.task_id = NEW.task_id
        AND outcome.trunk_id = NEW.trunk_id
        AND NEW.fallback_summary_marker =
            'FALLBACK:' || outcome.cleanup_summary_digest)
BEGIN SELECT RAISE(ABORT, 'Trunk outcome inbox item is not exact'); END;

CREATE TRIGGER task_outcome_create_trunk_inbox
AFTER INSERT ON task_outcome
BEGIN
    INSERT INTO trunk_outcome_inbox(
        id, trunk_id, task_id, task_outcome_id, delivery_key,
        fallback_summary_marker, status, created_at_ms,
        delivered_at_ms, delivery_evidence)
    VALUES ('TRUNK_OUTCOME:' || NEW.id, NEW.trunk_id, NEW.task_id, NEW.id,
        'TRUNK_OUTCOME:' || NEW.id,
        'FALLBACK:' || NEW.cleanup_summary_digest,
        'PENDING', NEW.recorded_at_ms, NULL, NULL);
END;

CREATE TRIGGER trunk_outcome_inbox_transition
BEFORE UPDATE ON trunk_outcome_inbox
WHEN NOT (OLD.status = 'PENDING' AND NEW.status = 'DELIVERED'
      AND NEW.id = OLD.id AND NEW.trunk_id = OLD.trunk_id
      AND NEW.task_id = OLD.task_id
      AND NEW.task_outcome_id = OLD.task_outcome_id
      AND NEW.delivery_key = OLD.delivery_key
      AND NEW.fallback_summary_marker = OLD.fallback_summary_marker
      AND NEW.created_at_ms = OLD.created_at_ms
      AND NEW.delivered_at_ms >= OLD.created_at_ms
      AND NEW.delivery_evidence IS NOT NULL)
BEGIN SELECT RAISE(ABORT, 'Trunk outcome inbox transition is invalid'); END;

CREATE TRIGGER trunk_outcome_inbox_delete_immutable
BEFORE DELETE ON trunk_outcome_inbox
BEGIN SELECT RAISE(ABORT, 'Trunk outcome inbox evidence cannot be deleted'); END;

-- Brain enrichment is an optional async TaskTurn. The fallback outcome above
-- is already complete and visible to the Trunk before this work is admitted.
CREATE TABLE task_outcome_summary_operation (
    id                  TEXT    NOT NULL PRIMARY KEY,
    task_outcome_id     TEXT    NOT NULL REFERENCES task_outcome(id) ON DELETE CASCADE,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch          INTEGER NOT NULL CHECK (task_epoch > 0),
    task_turn_id        TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    dispatch_ticket_id  TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    operation_id        TEXT    NOT NULL UNIQUE,
    semantic_attempt    INTEGER NOT NULL CHECK (semantic_attempt > 0),
    status              TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    summary_text        TEXT,
    summary_digest      TEXT,
    error_message       TEXT,
    requested_at_ms     INTEGER NOT NULL,
    completed_at_ms     INTEGER,
    UNIQUE (task_outcome_id, semantic_attempt),
    CHECK ((status = 'REQUESTED') = (completed_at_ms IS NULL)),
    CHECK ((status = 'SUCCEEDED') =
        (summary_text IS NOT NULL AND summary_digest IS NOT NULL)),
    CHECK (status <> 'FAILED' OR error_message IS NOT NULL)
);
CREATE UNIQUE INDEX idx_task_outcome_one_requested_summary
    ON task_outcome_summary_operation(task_outcome_id)
    WHERE status = 'REQUESTED';

CREATE TRIGGER task_outcome_summary_operation_insert
BEFORE INSERT ON task_outcome_summary_operation
WHEN NEW.status <> 'REQUESTED'
  OR NEW.semantic_attempt <> COALESCE((
      SELECT MAX(previous.semantic_attempt) + 1
      FROM task_outcome_summary_operation previous
      WHERE previous.task_outcome_id = NEW.task_outcome_id), 1)
  OR NOT EXISTS (
      SELECT 1 FROM task_outcome outcome
      JOIN tasks task ON task.id = outcome.task_id
      JOIN task_turn turn ON turn.id = NEW.task_turn_id
      JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
      JOIN outbox wake ON wake.aggregate_id = ticket.id
      WHERE outcome.id = NEW.task_outcome_id
        AND outcome.task_id = NEW.task_id
        AND outcome.task_epoch = NEW.task_epoch
        AND outcome.summary_state = 'FALLBACK'
        AND task.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
        AND task.epoch = NEW.task_epoch
        AND turn.task_id = NEW.task_id
        AND turn.task_epoch = NEW.task_epoch
        AND turn.purpose = 'TASK_COMPLETION_SUMMARY'
        AND turn.status = 'REQUESTED'
        AND turn.operation_id = NEW.operation_id
        AND turn.attempt = NEW.semantic_attempt
        AND turn.trigger_stage_id IS NULL
        AND ticket.operation_id = NEW.operation_id
        AND ticket.operation_kind = 'GENERATE_TASK_OUTCOME_SUMMARY'
        AND ticket.async_family = 'AGENT_TURN'
        AND ticket.owner_kind = 'TASK_TURN'
        AND ticket.owner_id = turn.id
        AND ticket.callback_route = 'TASK_OUTCOME_SUMMARY_RESULT'
        AND ticket.lane_mask = 2
        AND ticket.trunk_control = 0
        AND ticket.exclusive_task = 0
        AND ticket.writer_required = 0
        AND ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND ticket.stage_id IS NULL
        AND ticket.stage_generation IS NULL
        AND ticket.attempt = NEW.semantic_attempt
        AND ticket.status = 'REQUESTED'
        AND wake.id = 'V2_DISPATCH_TICKET_REQUESTED:' || ticket.id
        AND wake.dedup_key = wake.id
        AND wake.aggregate_kind = 'DISPATCH_TICKET'
        AND wake.topic = 'V2_DISPATCH_TICKET_REQUESTED'
        AND wake.payload = ticket.id
        AND wake.status = 'PENDING'
        AND wake.attempts = 0
        AND wake.available_at_ms = ticket.created_at_ms
        AND wake.created_at_ms = ticket.created_at_ms
        AND wake.claim_owner IS NULL AND wake.lease_until_ms IS NULL
        AND wake.delivered_at_ms IS NULL AND wake.last_error IS NULL)
BEGIN SELECT RAISE(ABORT, 'Task outcome summary lacks exact async dispatch'); END;

CREATE TRIGGER task_outcome_summary_operation_identity_immutable
BEFORE UPDATE OF task_outcome_id, task_id, task_epoch, task_turn_id,
        dispatch_ticket_id, operation_id, semantic_attempt, requested_at_ms
        ON task_outcome_summary_operation
WHEN NEW.task_outcome_id IS NOT OLD.task_outcome_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.task_turn_id IS NOT OLD.task_turn_id
  OR NEW.dispatch_ticket_id IS NOT OLD.dispatch_ticket_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'Task outcome summary identity is immutable'); END;

CREATE TRIGGER task_outcome_summary_operation_transition
BEFORE UPDATE OF status ON task_outcome_summary_operation
WHEN NEW.status IS NOT OLD.status
  AND NOT (OLD.status = 'REQUESTED'
      AND NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
BEGIN SELECT RAISE(ABORT, 'Task outcome summary transition is invalid'); END;

CREATE TRIGGER task_outcome_summary_operation_success
BEFORE UPDATE OF status ON task_outcome_summary_operation
WHEN NEW.status = 'SUCCEEDED'
  AND (NEW.completed_at_ms IS NULL OR NEW.summary_text IS NULL
    OR NEW.summary_digest IS NULL OR NEW.error_message IS NOT NULL
    OR NOT EXISTS (
        SELECT 1 FROM task_turn turn
        JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
        WHERE turn.id = NEW.task_turn_id
          AND turn.operation_id = NEW.operation_id
          AND turn.status = 'SUCCEEDED'
          AND turn.finished_at_ms IS NOT NULL
          AND ticket.operation_id = NEW.operation_id
          AND ticket.status = 'RESULT_PENDING'
          AND ticket.pending_result_outcome = 'SUCCEEDED'
          AND ticket.pending_result_payload = NEW.summary_digest
          AND ticket.pending_result_evidence IS NOT NULL
          AND ticket.pending_result_task_epoch = NEW.task_epoch
          AND ticket.pending_result_stage_id IS NULL
          AND ticket.pending_result_stage_generation IS NULL
          AND ticket.pending_result_operation_id = NEW.operation_id
          AND ticket.pending_result_attempt = NEW.semantic_attempt))
BEGIN SELECT RAISE(ABORT, 'Task outcome summary success lacks exact result'); END;

CREATE TRIGGER task_outcome_summary_operation_non_success
BEFORE UPDATE OF status ON task_outcome_summary_operation
WHEN NEW.status IN ('FAILED', 'CANCELED')
  AND (NEW.completed_at_ms IS NULL
    OR NEW.summary_text IS NOT NULL OR NEW.summary_digest IS NOT NULL
    OR (NEW.status = 'FAILED' AND NEW.error_message IS NULL)
    OR NOT EXISTS (
        SELECT 1 FROM task_turn turn
        JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
        WHERE turn.id = NEW.task_turn_id
          AND turn.operation_id = NEW.operation_id
          AND turn.status = NEW.status
          AND turn.finished_at_ms IS NOT NULL
          AND ticket.operation_id = NEW.operation_id
          AND ticket.status = 'RESULT_PENDING'
          AND ((NEW.status = 'FAILED'
                AND ticket.pending_result_outcome IN (
                    'FAILED', 'INDETERMINATE'))
            OR (NEW.status = 'CANCELED'
                AND ticket.pending_result_outcome = 'CANCELED'))
          AND ticket.pending_result_evidence IS NOT NULL
          AND ticket.pending_result_task_epoch = NEW.task_epoch
          AND ticket.pending_result_stage_id IS NULL
          AND ticket.pending_result_stage_generation IS NULL
          AND ticket.pending_result_operation_id = NEW.operation_id
          AND ticket.pending_result_attempt = NEW.semantic_attempt))
BEGIN SELECT RAISE(ABORT, 'Task outcome summary failure lacks exact result'); END;

CREATE TRIGGER task_outcome_summary_operation_result_fields
BEFORE UPDATE OF summary_text, summary_digest, error_message, completed_at_ms
        ON task_outcome_summary_operation
WHEN NEW.status IS OLD.status
  AND (NEW.summary_text IS NOT OLD.summary_text
    OR NEW.summary_digest IS NOT OLD.summary_digest
    OR NEW.error_message IS NOT OLD.error_message
    OR NEW.completed_at_ms IS NOT OLD.completed_at_ms)
BEGIN SELECT RAISE(ABORT, 'Task outcome summary result changes only with status'); END;

CREATE TRIGGER task_outcome_summary_operation_terminal_immutable
BEFORE UPDATE ON task_outcome_summary_operation
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
BEGIN SELECT RAISE(ABORT, 'Terminal Task outcome summary is immutable'); END;

CREATE TRIGGER task_outcome_summary_operation_delete_immutable
BEFORE DELETE ON task_outcome_summary_operation
BEGIN SELECT RAISE(ABORT, 'Task outcome summary operation cannot be deleted'); END;

CREATE TRIGGER task_outcome_summary_update
BEFORE UPDATE ON task_outcome
WHEN NOT (OLD.summary_state = 'FALLBACK'
      AND NEW.summary_state = 'BRAIN_GENERATED'
      AND NEW.summary_text IS NOT OLD.summary_text
      AND NEW.summary_digest IS NOT OLD.summary_digest
      AND NEW.summary_operation_id IS NOT NULL
      AND NEW.summary_updated_at_ms IS NOT NULL
      AND NEW.follow_up_proposals_json IS NOT NULL
      AND json_valid(NEW.follow_up_proposals_json)
      AND NEW.backlog_items_json IS NOT NULL
      AND json_valid(NEW.backlog_items_json)
      AND EXISTS (
          SELECT 1 FROM task_outcome_summary_operation operation
          JOIN dispatch_ticket ticket ON ticket.id = operation.dispatch_ticket_id
          WHERE operation.id = NEW.summary_operation_id
            AND operation.task_outcome_id = OLD.id
            AND operation.task_id = OLD.task_id
            AND operation.task_epoch = OLD.task_epoch
            AND operation.status = 'SUCCEEDED'
            AND operation.summary_text = NEW.summary_text
            AND operation.summary_digest = NEW.summary_digest
            AND ticket.status = 'SUCCEEDED'
            AND ticket.delivery_acceptance = 'ACCEPTED'))
BEGIN SELECT RAISE(ABORT, 'TaskOutcome summary update lacks exact Brain result'); END;

-- Cleanup completion is a Task-owned synchronous transition. The receipt is
-- recorded before the Task row changes and binds the exact CleanupOperation.
CREATE TRIGGER task_cleanup_completion_receipt_insert
BEFORE INSERT ON task_command_receipt
WHEN NEW.cause = 'ACCEPT_CLEANUP_COMPLETION'
  AND NOT EXISTS (
      SELECT 1 FROM task_outcome outcome
      JOIN cleanup_operation cleanup ON cleanup.id = outcome.cleanup_operation_id
      JOIN cleanup_stage cleanup_owner ON cleanup_owner.stage_id = outcome.cleanup_stage_id
      JOIN stage owner ON owner.id = cleanup_owner.stage_id
      WHERE outcome.task_id = NEW.task_id
        AND outcome.task_epoch = NEW.subject_task_epoch
        AND outcome.terminal_reason = NEW.returned_lifecycle
        AND cleanup.operation_id = NEW.subject_operation_id
        AND cleanup.semantic_attempt = NEW.subject_attempt
        AND cleanup.status = 'COMPLETED'
        AND owner.id = NEW.subject_stage_id
        AND owner.generation = NEW.subject_stage_generation
        AND owner.checkpoint = 'COMPLETED'
        AND NEW.disposition = 'APPLIED'
        AND NEW.expected_task_epoch = outcome.task_epoch
        AND NEW.expected_task_version = NEW.returned_version - 1
        AND NEW.returned_epoch = outcome.task_epoch
        AND NEW.returned_current_stage_id IS NULL
        AND NEW.returned_terminal_intent = outcome.terminal_reason)
BEGIN SELECT RAISE(ABORT, 'Task cleanup completion receipt lacks exact outcome'); END;

CREATE TRIGGER task_terminalize_after_cleanup
BEFORE UPDATE OF lifecycle_state ON tasks
WHEN OLD.workflow_version = 'V2'
  AND NEW.lifecycle_state IS NOT OLD.lifecycle_state
  AND (OLD.lifecycle_state = 'CLEANING'
        OR NEW.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
  AND NOT (OLD.lifecycle_state = 'CLEANING'
      AND NEW.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
      AND NEW.epoch = OLD.epoch
      AND EXISTS (
          SELECT 1 FROM task_outcome outcome
          JOIN trunk_outcome_inbox inbox
            ON inbox.task_outcome_id = outcome.id
          JOIN task_transition transition
            ON transition.task_id = outcome.task_id
          JOIN task_command_receipt receipt
            ON receipt.task_id = outcome.task_id
          WHERE outcome.task_id = OLD.id
            AND outcome.task_epoch = OLD.epoch
            AND outcome.terminal_reason = NEW.lifecycle_state
            AND inbox.trunk_id = outcome.trunk_id
            AND inbox.task_id = outcome.task_id
            AND inbox.delivery_key = 'TRUNK_OUTCOME:' || outcome.id
            AND inbox.fallback_summary_marker =
                'FALLBACK:' || outcome.cleanup_summary_digest
            AND inbox.status IN ('PENDING', 'DELIVERED')
            AND transition.command_id = receipt.command_id
            AND transition.epoch = OLD.epoch
            AND transition.from_state = 'CLEANING'
            AND transition.to_state = NEW.lifecycle_state
            AND transition.aggregate_version = NEW.aggregate_version
            AND transition.cause = 'ACCEPT_CLEANUP_COMPLETION'
            AND receipt.cause = transition.cause
            AND receipt.disposition = 'APPLIED'
            AND receipt.expected_task_epoch = OLD.epoch
            AND receipt.expected_task_version = OLD.aggregate_version
            AND receipt.returned_lifecycle = NEW.lifecycle_state
            AND receipt.returned_epoch = OLD.epoch
            AND receipt.returned_version = NEW.aggregate_version
            AND receipt.returned_current_stage_id IS NULL))
BEGIN SELECT RAISE(ABORT, 'Task terminal state lacks exact Cleanup outcome receipt'); END;

CREATE TRIGGER task_terminal_state_immutable
BEFORE UPDATE OF lifecycle_state ON tasks
WHEN OLD.workflow_version = 'V2'
  AND OLD.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
  AND NEW.lifecycle_state IS NOT OLD.lifecycle_state
BEGIN SELECT RAISE(ABORT, 'Terminal Task lifecycle is immutable'); END;

-- Once Cleanup owns the Task, no regular work may enter. The only admitted
-- work is the Cleanup runner itself, followed by optional summary enrichment
-- after the terminal TaskOutcome already exists.
CREATE TRIGGER cleanup_task_turn_admission
BEFORE INSERT ON task_turn
WHEN EXISTS (
    SELECT 1 FROM tasks task
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state IN (
          'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
  AND NOT EXISTS (
    SELECT 1 FROM tasks task
    JOIN task_outcome outcome ON outcome.task_id = task.id
    WHERE task.id = NEW.task_id
      AND task.lifecycle_state IN ('COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
      AND task.epoch = NEW.task_epoch
      AND outcome.summary_state = 'FALLBACK'
      AND NEW.purpose = 'TASK_COMPLETION_SUMMARY'
      AND NEW.status = 'REQUESTED'
      AND NEW.trigger_stage_id IS NULL
      AND NEW.trigger_stage_generation IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM task_outcome_summary_operation summary
          WHERE summary.task_outcome_id = outcome.id
            AND summary.status IN ('REQUESTED', 'SUCCEEDED'))
      AND NOT EXISTS (
          SELECT 1 FROM task_turn existing
          WHERE existing.task_id = NEW.task_id
            AND existing.purpose = 'TASK_COMPLETION_SUMMARY'
            AND existing.status IN (
                'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')))
BEGIN SELECT RAISE(ABORT, 'Task admits no regular Turns during or after Cleanup'); END;

CREATE TRIGGER cleanup_stage_turn_admission
BEFORE INSERT ON stage_turn
WHEN EXISTS (
    SELECT 1 FROM stage owner
    JOIN tasks task ON task.id = owner.task_id
    WHERE owner.id = NEW.stage_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state IN (
          'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
BEGIN SELECT RAISE(ABORT, 'Task admits no StageTurns during or after Cleanup'); END;

CREATE TRIGGER cleanup_dispatch_admission
BEFORE INSERT ON dispatch_ticket
WHEN NEW.task_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM tasks task
      WHERE task.id = NEW.task_id
        AND task.workflow_version = 'V2'
        AND task.lifecycle_state IN (
            'CLEANING', 'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
  AND NOT (
      EXISTS (
          SELECT 1 FROM tasks task
          JOIN task_current_stage current ON current.task_id = task.id
          JOIN stage owner ON owner.id = current.stage_id
          JOIN cleanup_stage cleanup ON cleanup.stage_id = owner.id
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state = 'CLEANING'
            AND task.epoch = NEW.task_epoch
            AND owner.kind = 'CLEANUP'
            AND owner.checkpoint = 'WAITING_QUIESCENCE'
            AND owner.id = NEW.stage_id
            AND owner.generation = NEW.stage_generation
            AND NEW.operation_kind = 'RUN_CLEANUP_OPERATION'
            AND NEW.async_family = 'CLEANUP'
            AND NEW.owner_kind = 'STAGE'
            AND NEW.owner_id = owner.id
            AND NEW.callback_route = 'CLEANUP_OPERATION_RESULT'
            AND NEW.lane_mask = 256
            AND NEW.exclusive_task = 1
            AND NEW.writer_required = 1
            AND NOT EXISTS (
                SELECT 1 FROM dispatch_ticket existing
                WHERE existing.task_id = NEW.task_id
                  AND existing.task_epoch = NEW.task_epoch
                  AND existing.async_family = 'CLEANUP'))
      OR EXISTS (
          SELECT 1 FROM tasks task
          JOIN task_outcome outcome ON outcome.task_id = task.id
          JOIN task_turn turn ON turn.task_id = task.id
          WHERE task.id = NEW.task_id
            AND task.lifecycle_state IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
            AND task.epoch = NEW.task_epoch
            AND outcome.summary_state = 'FALLBACK'
            AND turn.id = NEW.owner_id
            AND turn.purpose = 'TASK_COMPLETION_SUMMARY'
            AND turn.operation_id = NEW.operation_id
            AND turn.attempt = NEW.attempt
            AND NEW.operation_kind = 'GENERATE_TASK_OUTCOME_SUMMARY'
            AND NEW.async_family = 'AGENT_TURN'
            AND NEW.owner_kind = 'TASK_TURN'
            AND NEW.callback_route = 'TASK_OUTCOME_SUMMARY_RESULT'
            AND NEW.lane_mask = 2
            AND NEW.trunk_control = 0
            AND NEW.exclusive_task = 0
            AND NEW.writer_required = 0
            AND NEW.stage_id IS NULL
            AND NEW.stage_generation IS NULL))
BEGIN SELECT RAISE(ABORT, 'Task admits no unrelated dispatch during or after Cleanup'); END;
