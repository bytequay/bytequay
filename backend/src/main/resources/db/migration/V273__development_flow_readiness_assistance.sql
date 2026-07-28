-- Scenario 42: an exact-head ready PR that the authenticated viewer cannot
-- merge may expose one explicit human-approved reviewer/maintainer request.
-- Readiness notifications remain projections; they grant no authority here.

ALTER TABLE remote_pr_snapshot ADD COLUMN viewer_login TEXT;
ALTER TABLE remote_pr_snapshot ADD COLUMN viewer_can_merge INTEGER
    CHECK (viewer_can_merge IS NULL OR viewer_can_merge IN (0, 1));

CREATE TABLE remote_readiness_assistance_v273 (
    id                          TEXT    NOT NULL PRIMARY KEY,
    command_id                  TEXT    NOT NULL,
    operation_id                TEXT    NOT NULL UNIQUE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_stage_id             TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id        TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    remote_pr_snapshot_id       TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    readiness_evidence_id       TEXT    NOT NULL REFERENCES remote_readiness_evidence(id),
    automation_policy_id        TEXT    NOT NULL REFERENCES task_automation_policy(id),
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'REQUEST_REVIEWER', 'POST_MAINTAINER_NUDGE')),
    external_target             TEXT    NOT NULL,
    payload                     TEXT    NOT NULL,
    payload_digest              TEXT    NOT NULL CHECK (length(payload_digest) = 64),
    idempotency_key             TEXT    NOT NULL UNIQUE,
    remote_repository_id        TEXT    NOT NULL,
    head_repository_id          TEXT    NOT NULL,
    remote_pr_number            INTEGER NOT NULL CHECK (remote_pr_number > 0),
    expected_head_sha           TEXT    NOT NULL,
    expected_base_sha           TEXT    NOT NULL,
    semantic_attempt            INTEGER NOT NULL DEFAULT 1 CHECK (semantic_attempt = 1),
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'INDETERMINATE',
        'ABANDONED')),
    attempt_count               INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit               INTEGER NOT NULL CHECK (attempt_limit > 0),
    claim_mode                  TEXT CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    claim_owner                 TEXT,
    claimed_at_ms               INTEGER,
    lease_until_ms              INTEGER,
    external_effect_id          TEXT,
    evidence                    TEXT,
    last_error                  TEXT,
    authorized_by               TEXT    NOT NULL,
    authorized_at_ms            INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    UNIQUE (task_id, command_id),
    UNIQUE (id, task_id, task_epoch, remote_stage_id, stage_generation,
        expected_head_sha, expected_base_sha),
    CHECK (length(trim(command_id)) > 0
        AND length(trim(external_target)) > 0
        AND length(trim(payload)) > 0
        AND length(trim(remote_repository_id)) > 0
        AND length(trim(head_repository_id)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0
        AND length(trim(authorized_by)) > 0),
    CHECK ((kind = 'REQUEST_REVIEWER' AND lower(external_target) = lower(payload))
        OR (kind = 'POST_MAINTAINER_NUDGE'
            AND external_target = 'MAINTAINER')),
    CHECK ((status = 'CLAIMED') = (claim_mode IS NOT NULL
        AND claim_owner IS NOT NULL AND claimed_at_ms IS NOT NULL
        AND lease_until_ms IS NOT NULL)),
    CHECK (status <> 'CLAIMED' OR lease_until_ms > claimed_at_ms),
    CHECK (attempt_count <= attempt_limit),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE', 'ABANDONED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED'
        OR (external_effect_id IS NOT NULL AND evidence IS NOT NULL))
);

CREATE INDEX idx_remote_readiness_assistance_subject_v273
    ON remote_readiness_assistance_v273(
        task_id, remote_stage_id, remote_pr_snapshot_id,
        automation_policy_id, status);

CREATE TRIGGER remote_readiness_assistance_insert_v273
BEFORE INSERT ON remote_readiness_assistance_v273
WHEN NEW.status <> 'REQUESTED' OR NEW.attempt_count <> 0 OR NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN threads trunk ON trunk.id = task.thread_id
    JOIN task_current_stage current ON current.task_id = task.id
    JOIN stage owner ON owner.id = current.stage_id
    JOIN remote_development_stage remote ON remote.stage_id = owner.id
    JOIN remote_pr_binding binding ON binding.id = remote.remote_pr_binding_id
    JOIN remote_pr_snapshot snapshot ON snapshot.id = remote.accepted_snapshot_id
    JOIN remote_readiness_evidence readiness
      ON readiness.id = NEW.readiness_evidence_id
    JOIN task_automation_policy policy ON policy.id = NEW.automation_policy_id
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND current.stage_id = NEW.remote_stage_id
      AND current.stage_generation = NEW.stage_generation
      AND owner.id = NEW.remote_stage_id
      AND owner.task_id = task.id
      AND owner.kind = 'REMOTE_DEVELOPMENT'
      AND owner.generation = NEW.stage_generation
      AND owner.checkpoint = 'READY_TO_MERGE'
      AND owner.completed_at_ms IS NULL
      AND remote.task_id = task.id
      AND remote.generation = NEW.stage_generation
      AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
      AND remote.accepted_snapshot_id = NEW.remote_pr_snapshot_id
      AND remote.current_head_sha = NEW.expected_head_sha
      AND remote.current_base_sha = NEW.expected_base_sha
      AND binding.task_id = task.id
      AND binding.remote_repository_id = NEW.remote_repository_id
      AND binding.head_repository_id = NEW.head_repository_id
      AND binding.remote_pr_number = NEW.remote_pr_number
      AND snapshot.task_id = task.id
      AND snapshot.task_epoch = task.epoch
      AND snapshot.remote_development_stage_id = owner.id
      AND snapshot.stage_generation = owner.generation
      AND snapshot.remote_pr_binding_id = binding.id
      AND snapshot.head_sha = NEW.expected_head_sha
      AND snapshot.base_sha = NEW.expected_base_sha
      AND snapshot.pr_state = 'OPEN'
      AND snapshot.viewer_login = NEW.authorized_by
      AND snapshot.viewer_can_merge = 0
      AND readiness.remote_development_stage_id = owner.id
      AND readiness.task_id = task.id
      AND readiness.task_epoch = task.epoch
      AND readiness.stage_generation = owner.generation
      AND readiness.remote_pr_snapshot_id = snapshot.id
      AND readiness.automation_policy_id = policy.id
      AND readiness.head_sha = NEW.expected_head_sha
      AND readiness.base_sha = NEW.expected_base_sha
      AND readiness.ready = 1
      AND policy.task_id = task.id
      AND policy.revision = (
          SELECT MAX(latest.revision)
          FROM task_automation_policy latest
          WHERE latest.task_id = task.id)
      AND NOT EXISTS (
          SELECT 1
          FROM remote_readiness_assistance_v273 prior
          WHERE prior.task_id = task.id
            AND prior.task_epoch = task.epoch
            AND prior.remote_stage_id = owner.id
            AND prior.stage_generation = owner.generation
            AND prior.remote_pr_snapshot_id = snapshot.id
            AND prior.automation_policy_id = policy.id
            AND prior.status IN (
                'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED',
                'INDETERMINATE')))
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance lacks exact current manually approved authority'); END;

CREATE TRIGGER remote_readiness_assistance_identity_v273
BEFORE UPDATE OF id, command_id, operation_id, task_id, task_epoch,
        remote_stage_id, stage_generation, remote_pr_binding_id,
        remote_pr_snapshot_id, readiness_evidence_id, automation_policy_id,
        kind, external_target, payload, payload_digest, idempotency_key,
        remote_repository_id, head_repository_id, remote_pr_number,
        expected_head_sha, expected_base_sha, semantic_attempt, attempt_limit,
        authorized_by, authorized_at_ms ON remote_readiness_assistance_v273
BEGIN SELECT RAISE(ABORT, 'Readiness assistance identity is immutable'); END;

CREATE TRIGGER remote_readiness_assistance_transition_v273
BEFORE UPDATE OF status ON remote_readiness_assistance_v273
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status = 'CLAIMED')
    OR (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status = 'ABANDONED')
    OR (OLD.status = 'CLAIMED'
        AND NEW.status IN (
            'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'ABANDONED')))
BEGIN SELECT RAISE(ABORT, 'Readiness assistance transition is invalid'); END;

CREATE TRIGGER remote_readiness_assistance_exhaustion_v273
BEFORE UPDATE OF status ON remote_readiness_assistance_v273
WHEN OLD.status = 'CLAIMED'
  AND OLD.attempt_count >= OLD.attempt_limit
  AND NEW.status IN ('FAILED', 'INDETERMINATE')
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance exhaustion must become terminal'); END;

CREATE TRIGGER remote_readiness_assistance_claim_v273
BEFORE UPDATE OF status ON remote_readiness_assistance_v273
WHEN NEW.status = 'CLAIMED'
  AND (NOT ((OLD.status = 'CLAIMED'
                AND NEW.attempt_count = OLD.attempt_count
                AND NEW.claim_mode = 'PROBE'
                AND NEW.claimed_at_ms >= OLD.lease_until_ms)
            OR (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
                AND NEW.attempt_count = OLD.attempt_count + 1))
    OR NEW.attempt_count > OLD.attempt_limit
    OR OLD.status NOT IN (
        'REQUESTED', 'CLAIMED', 'FAILED', 'INDETERMINATE')
    OR (OLD.status = 'INDETERMINATE' AND NEW.claim_mode <> 'PROBE')
    OR (OLD.status NOT IN ('CLAIMED', 'INDETERMINATE')
        AND NEW.claim_mode <> 'EXECUTE')
    OR NEW.external_effect_id IS NOT NULL OR NEW.evidence IS NOT NULL
    OR NEW.last_error IS NOT NULL OR NEW.completed_at_ms IS NOT NULL
    OR NOT EXISTS (
        SELECT 1
        FROM tasks task
        JOIN task_current_stage current ON current.task_id = task.id
        JOIN stage owner ON owner.id = current.stage_id
        JOIN remote_development_stage remote ON remote.stage_id = owner.id
        JOIN remote_pr_binding binding
          ON binding.id = remote.remote_pr_binding_id
        JOIN remote_pr_snapshot snapshot
          ON snapshot.id = remote.accepted_snapshot_id
        JOIN remote_readiness_evidence readiness
          ON readiness.id = NEW.readiness_evidence_id
        JOIN task_automation_policy policy
          ON policy.id = NEW.automation_policy_id
        WHERE task.id = NEW.task_id
          AND task.workflow_version = 'V2'
          AND task.lifecycle_state = 'ACTIVE'
          AND task.epoch = NEW.task_epoch
          AND current.stage_id = NEW.remote_stage_id
          AND current.stage_generation = NEW.stage_generation
          AND owner.id = NEW.remote_stage_id
          AND owner.task_id = task.id
          AND owner.kind = 'REMOTE_DEVELOPMENT'
          AND owner.generation = NEW.stage_generation
          AND owner.checkpoint = 'READY_TO_MERGE'
          AND owner.completed_at_ms IS NULL
          AND remote.task_id = task.id
          AND remote.generation = owner.generation
          AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
          AND remote.accepted_snapshot_id = NEW.remote_pr_snapshot_id
          AND remote.current_head_sha = NEW.expected_head_sha
          AND remote.current_base_sha = NEW.expected_base_sha
          AND binding.task_id = task.id
          AND binding.remote_repository_id = NEW.remote_repository_id
          AND binding.head_repository_id = NEW.head_repository_id
          AND binding.remote_pr_number = NEW.remote_pr_number
          AND snapshot.task_id = task.id
          AND snapshot.task_epoch = task.epoch
          AND snapshot.remote_development_stage_id = owner.id
          AND snapshot.stage_generation = owner.generation
          AND snapshot.remote_pr_binding_id = binding.id
          AND snapshot.head_sha = NEW.expected_head_sha
          AND snapshot.base_sha = NEW.expected_base_sha
          AND snapshot.pr_state = 'OPEN'
          AND snapshot.viewer_login = NEW.authorized_by
          AND snapshot.viewer_can_merge = 0
          AND readiness.remote_development_stage_id = owner.id
          AND readiness.remote_pr_snapshot_id = snapshot.id
          AND readiness.task_id = task.id
          AND readiness.task_epoch = task.epoch
          AND readiness.stage_generation = owner.generation
          AND readiness.automation_policy_id = policy.id
          AND readiness.head_sha = NEW.expected_head_sha
          AND readiness.base_sha = NEW.expected_base_sha
          AND readiness.ready = 1
          AND policy.task_id = task.id
          AND policy.revision = (
              SELECT MAX(latest.revision)
              FROM task_automation_policy latest
              WHERE latest.task_id = task.id)))
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance claim is stale or no longer allowed'); END;

CREATE TRIGGER remote_readiness_assistance_attempt_v273
BEFORE UPDATE OF attempt_count ON remote_readiness_assistance_v273
WHEN NOT (NEW.status = 'CLAIMED'
    AND ((OLD.status = 'CLAIMED'
            AND NEW.attempt_count = OLD.attempt_count
            AND NEW.claim_mode = 'PROBE'
            AND NEW.claimed_at_ms >= OLD.lease_until_ms)
        OR (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
            AND NEW.attempt_count = OLD.attempt_count + 1)))
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance attempt must be an exact claim'); END;

CREATE TRIGGER remote_readiness_assistance_claim_fields_v273
BEFORE UPDATE OF claim_mode, claim_owner, claimed_at_ms, lease_until_ms
        ON remote_readiness_assistance_v273
WHEN NOT ((NEW.status = 'CLAIMED'
            AND ((OLD.status = 'CLAIMED'
                    AND NEW.attempt_count = OLD.attempt_count
                    AND NEW.claim_mode = 'PROBE'
                    AND NEW.claimed_at_ms >= OLD.lease_until_ms)
                OR (OLD.status IN (
                        'REQUESTED', 'FAILED', 'INDETERMINATE')
                    AND NEW.attempt_count = OLD.attempt_count + 1)))
        OR (OLD.status = 'CLAIMED' AND NEW.status <> 'CLAIMED'
            AND NEW.attempt_count = OLD.attempt_count
            AND NEW.claim_mode IS NULL AND NEW.claim_owner IS NULL
            AND NEW.claimed_at_ms IS NULL AND NEW.lease_until_ms IS NULL))
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance claim fields change only with an attempt'); END;

CREATE TRIGGER remote_readiness_assistance_result_fields_v273
BEFORE UPDATE OF external_effect_id, evidence, last_error, completed_at_ms
        ON remote_readiness_assistance_v273
WHEN (NEW.external_effect_id IS NOT OLD.external_effect_id
    OR NEW.evidence IS NOT OLD.evidence
    OR NEW.last_error IS NOT OLD.last_error
    OR NEW.completed_at_ms IS NOT OLD.completed_at_ms)
  AND NEW.status IS OLD.status
  AND NOT (NEW.status = 'CLAIMED'
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.external_effect_id IS NULL AND NEW.evidence IS NULL
    AND NEW.last_error IS NULL AND NEW.completed_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance result changes only at a result boundary'); END;

CREATE TRIGGER remote_readiness_assistance_succeeded_immutable_v273
BEFORE UPDATE ON remote_readiness_assistance_v273
WHEN OLD.status IN ('SUCCEEDED', 'ABANDONED')
BEGIN SELECT RAISE(ABORT, 'Terminal readiness assistance is immutable'); END;

CREATE TABLE remote_readiness_assistance_dispatch_v273 (
    assistance_id      TEXT    NOT NULL PRIMARY KEY
        REFERENCES remote_readiness_assistance_v273(id) ON DELETE CASCADE,
    dispatch_ticket_id TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    dispatched_at_ms   INTEGER NOT NULL
);

CREATE TRIGGER dispatch_ticket_readiness_assistance_v273
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'APPLY_READINESS_ASSISTANCE'
   OR NEW.callback_route = 'READINESS_ASSISTANCE_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM remote_readiness_assistance_v273 assistance
        JOIN tasks task ON task.id = assistance.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE assistance.operation_id = NEW.operation_id
          AND assistance.status = 'REQUESTED'
          AND NEW.operation_kind = 'APPLY_READINESS_ASSISTANCE'
          AND NEW.async_family = 'GITHUB_EFFECT'
          AND NEW.owner_kind = 'STAGE'
          AND NEW.owner_id = assistance.remote_stage_id
          AND NEW.callback_route = 'READINESS_ASSISTANCE_RESULT'
          AND NEW.lane_mask = 32
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.task_id = assistance.task_id
          AND NEW.task_epoch = assistance.task_epoch
          AND NEW.stage_id = assistance.remote_stage_id
          AND NEW.stage_generation = assistance.stage_generation
          AND NEW.attempt = assistance.semantic_attempt
          AND NEW.expected_code_fingerprint IS NULL
          AND NEW.expected_head_sha = assistance.expected_head_sha
          AND NEW.expected_base_sha = assistance.expected_base_sha
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'Readiness assistance ticket is stale or unowned') END;
END;

CREATE TRIGGER remote_readiness_assistance_dispatch_insert_v273
BEFORE INSERT ON remote_readiness_assistance_dispatch_v273
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_readiness_assistance_v273 assistance
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    WHERE assistance.id = NEW.assistance_id
      AND assistance.operation_id = NEW.operation_id
      AND assistance.status = 'REQUESTED'
      AND ticket.operation_id = assistance.operation_id
      AND ticket.operation_kind = 'APPLY_READINESS_ASSISTANCE'
      AND ticket.callback_route = 'READINESS_ASSISTANCE_RESULT'
      AND ticket.owner_kind = 'STAGE'
      AND ticket.owner_id = assistance.remote_stage_id
      AND ticket.task_id = assistance.task_id
      AND ticket.task_epoch = assistance.task_epoch
      AND ticket.stage_id = assistance.remote_stage_id
      AND ticket.stage_generation = assistance.stage_generation
      AND ticket.expected_head_sha = assistance.expected_head_sha
      AND ticket.expected_base_sha = assistance.expected_base_sha
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance dispatch differs from its authorization'); END;

CREATE TRIGGER remote_readiness_assistance_dispatch_immutable_v273
BEFORE UPDATE ON remote_readiness_assistance_dispatch_v273
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance dispatch is immutable'); END;

CREATE TABLE remote_readiness_assistance_receipt_v273 (
    operation_id      TEXT    NOT NULL PRIMARY KEY,
    raw_result_digest TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance        TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED', 'REJECTED')),
    evidence          TEXT    NOT NULL,
    recorded_at_ms    INTEGER NOT NULL
);

CREATE TRIGGER remote_readiness_assistance_receipt_insert_v273
BEFORE INSERT ON remote_readiness_assistance_receipt_v273
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_readiness_assistance_v273 assistance
    JOIN remote_readiness_assistance_dispatch_v273 dispatch
      ON dispatch.assistance_id = assistance.id
    WHERE assistance.operation_id = NEW.operation_id
      AND dispatch.operation_id = assistance.operation_id)
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance receipt lacks its exact operation'); END;

CREATE TRIGGER remote_readiness_assistance_receipt_immutable_v273
BEFORE UPDATE ON remote_readiness_assistance_receipt_v273
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance receipt is immutable'); END;

-- V269 predates this operation family. Its general live-ticket check blocks
-- execution, while this extension also requires the typed effect to have a
-- durable terminal outcome and an accepted terminal ticket before purge.
CREATE TRIGGER v2_trunk_purge_readiness_assistance_v273
BEFORE INSERT ON v2_trunk_purge_authorization_v269
WHEN EXISTS (
    SELECT 1
    FROM remote_readiness_assistance_v273 assistance
    JOIN tasks task ON task.id = assistance.task_id
    JOIN remote_readiness_assistance_dispatch_v273 dispatch
      ON dispatch.assistance_id = assistance.id
    JOIN dispatch_ticket ticket ON ticket.id = dispatch.dispatch_ticket_id
    WHERE task.thread_id = NEW.trunk_id
      AND (assistance.status NOT IN ('SUCCEEDED', 'ABANDONED')
        OR ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')
        OR ticket.delivery_acceptance <> 'ACCEPTED'))
BEGIN SELECT RAISE(ABORT,
    'V2 Trunk purge requires terminal accepted readiness assistance'); END;

CREATE TRIGGER remote_readiness_assistance_delete_v273
BEFORE DELETE ON remote_readiness_assistance_v273
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE task.id = OLD.task_id)
BEGIN SELECT RAISE(ABORT,
    'Readiness assistance evidence cannot be deleted before purge'); END;
