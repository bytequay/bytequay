-- A standalone review of somebody else's PR is a comment-only BUILD Trunk.
-- It must never acquire a writable Task/worktree.  Freeze user-reviewable
-- proposals from the immutable V258 selection, then authorize exactly one
-- dispatcher-owned GitHub review or a DB-only discard decision.

-- V281 rebuilt plan_approval. SQLite updated the foreign-key target stored in
-- this V237 child table to the temporary plan_approval_v280 name before that
-- table was dropped. Rebuild the child without changing its rows so Local
-- Development receipts and Trunk cascades resolve the live parent again.
DROP TRIGGER local_initial_implementation_receipt_insert;
DROP TRIGGER local_initial_implementation_receipt_immutable;

ALTER TABLE local_initial_implementation_receipt
    RENAME TO local_initial_implementation_receipt_v286;

CREATE TABLE local_initial_implementation_receipt (
    local_development_stage_id TEXT    NOT NULL PRIMARY KEY
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL
        REFERENCES tasks(id) ON DELETE CASCADE,
    plan_approval_id           TEXT    NOT NULL UNIQUE REFERENCES plan_approval(id),
    stage_turn_request_id      TEXT    NOT NULL UNIQUE
        REFERENCES local_stage_turn_request(id),
    stage_turn_id              TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    operation_id               TEXT    NOT NULL UNIQUE,
    ticket_id                  TEXT    NOT NULL UNIQUE REFERENCES dispatch_ticket(id),
    recorded_at_ms             INTEGER NOT NULL CHECK (recorded_at_ms >= 0)
);

INSERT INTO local_initial_implementation_receipt(
    local_development_stage_id, task_id, plan_approval_id,
    stage_turn_request_id, stage_turn_id, operation_id, ticket_id,
    recorded_at_ms)
SELECT local_development_stage_id, task_id, plan_approval_id,
       stage_turn_request_id, stage_turn_id, operation_id, ticket_id,
       recorded_at_ms
FROM local_initial_implementation_receipt_v286;

DROP TABLE local_initial_implementation_receipt_v286;

CREATE TRIGGER local_initial_implementation_receipt_insert
BEFORE INSERT ON local_initial_implementation_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM local_development_stage local
    JOIN plan_approval approval ON approval.id = NEW.plan_approval_id
    JOIN plan_revision revision ON revision.id = approval.plan_revision_id
    JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
    JOIN local_stage_turn_request request
      ON request.id = NEW.stage_turn_request_id
    JOIN stage_turn turn ON turn.id = request.stage_turn_id
    JOIN dispatch_ticket ticket ON ticket.id = NEW.ticket_id
    JOIN stage_initial_result_request initial
      ON initial.stage_id = local.stage_id
    WHERE local.stage_id = NEW.local_development_stage_id
      AND local.task_id = NEW.task_id
      AND plan.task_id = NEW.task_id
      AND request.local_development_stage_id = local.stage_id
      AND request.task_id = NEW.task_id
      AND request.kind = 'IMPLEMENTATION'
      AND request.queue_mode = 'IMMEDIATE'
      AND request.stage_turn_id = NEW.stage_turn_id
      AND turn.operation_id = NEW.operation_id
      AND ticket.operation_id = NEW.operation_id
      AND ticket.owner_kind = 'STAGE_TURN'
      AND ticket.owner_id = NEW.stage_turn_id
      AND ticket.callback_route = 'STAGE_TURN_RESULT'
      AND initial.turn_owner_kind = 'STAGE_TURN'
      AND initial.turn_id = NEW.stage_turn_id
      AND initial.pending_operation_id = NEW.operation_id)
BEGIN SELECT RAISE(ABORT, 'initial Local implementation receipt is not exact'); END;

CREATE TRIGGER local_initial_implementation_receipt_immutable
BEFORE UPDATE ON local_initial_implementation_receipt
BEGIN SELECT RAISE(ABORT, 'initial Local implementation receipt is immutable'); END;

CREATE TABLE review_build_comment_proposal_v287 (
    thread_id          TEXT    NOT NULL PRIMARY KEY
        REFERENCES review_build_selection(thread_id) ON DELETE CASCADE,
    review_pass_id     TEXT    NOT NULL UNIQUE
        REFERENCES review_passes(id) ON DELETE RESTRICT,
    selection_digest  TEXT    NOT NULL,
    decision           TEXT CHECK (decision IN ('APPROVE', 'DISCARD')),
    decision_command_id TEXT,
    created_at_ms      INTEGER NOT NULL CHECK (created_at_ms >= 0),
    decided_at_ms      INTEGER,
    CHECK (length(trim(selection_digest)) > 0),
    CHECK ((decision IS NULL AND decision_command_id IS NULL
                AND decided_at_ms IS NULL)
        OR (decision IS NOT NULL AND decision_command_id IS NOT NULL
                AND length(trim(decision_command_id)) > 0
                AND decided_at_ms IS NOT NULL))
);

CREATE TABLE review_build_comment_proposal_item_v287 (
    thread_id          TEXT    NOT NULL
        REFERENCES review_build_comment_proposal_v287(thread_id)
        ON DELETE CASCADE,
    position           INTEGER NOT NULL CHECK (position > 0),
    finding_id         TEXT    NOT NULL,
    finding_revision   INTEGER NOT NULL CHECK (finding_revision > 0),
    content_digest     TEXT    NOT NULL CHECK (length(content_digest) = 64),
    kind               TEXT    NOT NULL CHECK (kind IN ('INLINE', 'TOP_LEVEL')),
    path               TEXT,
    line                INTEGER,
    body               TEXT    NOT NULL,
    PRIMARY KEY (thread_id, position),
    UNIQUE (thread_id, finding_id),
    CHECK (length(trim(finding_id)) > 0 AND length(trim(body)) > 0),
    CHECK ((kind = 'INLINE' AND path IS NOT NULL
                AND length(trim(path)) > 0 AND line > 0)
        OR (kind = 'TOP_LEVEL' AND line IS NULL))
);

CREATE TRIGGER review_build_comment_proposal_from_selection_v287
AFTER INSERT ON review_build_selection
WHEN NEW.spawn_mode = 'suggested_change'
BEGIN
    INSERT INTO review_build_comment_proposal_v287(
        thread_id, review_pass_id, selection_digest, created_at_ms)
    VALUES (NEW.thread_id, NEW.review_pass_id, NEW.selection_digest,
        NEW.frozen_at_ms);
END;

CREATE TRIGGER review_build_comment_item_from_selection_v287
AFTER INSERT ON review_build_selection_item
WHEN EXISTS (
    SELECT 1 FROM review_build_comment_proposal_v287 proposal
    WHERE proposal.thread_id = NEW.thread_id)
BEGIN
    INSERT INTO review_build_comment_proposal_item_v287(
        thread_id, position, finding_id, finding_revision, content_digest,
        kind, path, line, body)
    SELECT NEW.thread_id, NEW.position, NEW.finding_id, NEW.finding_revision,
        NEW.content_digest,
        CASE WHEN json_extract(NEW.content_json, '$.path') IS NOT NULL
                  AND length(trim(json_extract(NEW.content_json, '$.path'))) > 0
                  AND CAST(json_extract(NEW.content_json, '$.line') AS INTEGER) > 0
             THEN 'INLINE' ELSE 'TOP_LEVEL' END,
        json_extract(NEW.content_json, '$.path'),
        CASE WHEN json_extract(NEW.content_json, '$.path') IS NOT NULL
                  AND length(trim(json_extract(NEW.content_json, '$.path'))) > 0
                  AND CAST(json_extract(NEW.content_json, '$.line') AS INTEGER) > 0
             THEN CAST(json_extract(NEW.content_json, '$.line') AS INTEGER)
             ELSE NULL END,
        trim(json_extract(NEW.content_json, '$.body')) || char(10) || char(10)
            || '<!-- bytequay-review-build:' || NEW.finding_id || ':'
            || NEW.content_digest || ' -->';
END;

-- Preserve local development data when this migration follows an already
-- frozen suggested-change selection.
INSERT INTO review_build_comment_proposal_v287(
    thread_id, review_pass_id, selection_digest, created_at_ms)
SELECT selection.thread_id, selection.review_pass_id,
       selection.selection_digest, selection.frozen_at_ms
FROM review_build_selection selection
WHERE selection.spawn_mode = 'suggested_change';

INSERT INTO review_build_comment_proposal_item_v287(
    thread_id, position, finding_id, finding_revision, content_digest,
    kind, path, line, body)
SELECT item.thread_id, item.position, item.finding_id, item.finding_revision,
       item.content_digest,
       CASE WHEN json_extract(item.content_json, '$.path') IS NOT NULL
                 AND length(trim(json_extract(item.content_json, '$.path'))) > 0
                 AND CAST(json_extract(item.content_json, '$.line') AS INTEGER) > 0
            THEN 'INLINE' ELSE 'TOP_LEVEL' END,
       json_extract(item.content_json, '$.path'),
       CASE WHEN json_extract(item.content_json, '$.path') IS NOT NULL
                 AND length(trim(json_extract(item.content_json, '$.path'))) > 0
                 AND CAST(json_extract(item.content_json, '$.line') AS INTEGER) > 0
            THEN CAST(json_extract(item.content_json, '$.line') AS INTEGER)
            ELSE NULL END,
       trim(json_extract(item.content_json, '$.body')) || char(10) || char(10)
           || '<!-- bytequay-review-build:' || item.finding_id || ':'
           || item.content_digest || ' -->'
FROM review_build_selection_item item
JOIN review_build_comment_proposal_v287 proposal
  ON proposal.thread_id = item.thread_id;

CREATE TRIGGER review_build_comment_proposal_item_insert_guard_v287
BEFORE INSERT ON review_build_comment_proposal_item_v287
WHEN NOT EXISTS (
    SELECT 1
    FROM review_build_comment_proposal_v287 proposal
    JOIN review_build_selection selection
      ON selection.thread_id = proposal.thread_id
    JOIN review_build_selection_item item
      ON item.thread_id = selection.thread_id
     AND item.position = NEW.position
    WHERE proposal.thread_id = NEW.thread_id
      AND selection.spawn_mode = 'suggested_change'
      AND selection.review_pass_id = proposal.review_pass_id
      AND selection.selection_digest = proposal.selection_digest
      AND item.finding_id = NEW.finding_id
      AND item.finding_revision = NEW.finding_revision
      AND item.content_digest = NEW.content_digest)
BEGIN SELECT RAISE(ABORT,
    'review build comment proposal item differs from its frozen selection'); END;

CREATE TABLE review_build_comment_action_v287 (
    id                    TEXT    NOT NULL PRIMARY KEY,
    operation_id          TEXT    NOT NULL UNIQUE,
    thread_id             TEXT    NOT NULL UNIQUE
        REFERENCES review_build_comment_proposal_v287(thread_id)
        ON DELETE CASCADE,
    review_pass_id        TEXT    NOT NULL UNIQUE,
    command_id            TEXT    NOT NULL,
    workspace_id          TEXT    NOT NULL REFERENCES workspaces(id),
    remote_repository_id  TEXT    NOT NULL,
    head_repository_id    TEXT    NOT NULL,
    remote_pr_number      INTEGER NOT NULL CHECK (remote_pr_number > 0),
    branch_name           TEXT    NOT NULL,
    expected_head_sha     TEXT    NOT NULL,
    payload_json          TEXT    NOT NULL CHECK (json_valid(payload_json)),
    payload_digest        TEXT    NOT NULL CHECK (length(payload_digest) = 64),
    semantic_attempt      INTEGER NOT NULL DEFAULT 1 CHECK (semantic_attempt = 1),
    status                TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'INDETERMINATE',
        'CANCELED', 'ABANDONED')),
    attempt_count         INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit         INTEGER NOT NULL CHECK (attempt_limit > 0),
    observation_count     INTEGER NOT NULL DEFAULT 0 CHECK (observation_count >= 0),
    observation_limit     INTEGER NOT NULL CHECK (observation_limit > 0),
    observation_started_at_ms INTEGER CHECK (observation_started_at_ms > 0),
    observation_deadline_ms INTEGER CHECK (observation_deadline_ms > 0),
    claim_mode            TEXT CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    claim_owner           TEXT,
    claimed_at_ms         INTEGER,
    lease_until_ms        INTEGER,
    external_effect_id    TEXT,
    evidence              TEXT,
    last_error            TEXT,
    recovery_baseline_json TEXT,
    authorized_at_ms      INTEGER NOT NULL CHECK (authorized_at_ms >= 0),
    completed_at_ms       INTEGER,
    finalized_at_ms       INTEGER,
    resolved_count        INTEGER CHECK (resolved_count >= 0),
    CHECK (length(trim(command_id)) > 0
        AND length(trim(remote_repository_id)) > 0
        AND length(trim(head_repository_id)) > 0
        AND length(trim(branch_name)) > 0
        AND length(trim(expected_head_sha)) > 0),
    CHECK ((status = 'CLAIMED') = (claim_mode IS NOT NULL
        AND claim_owner IS NOT NULL AND claimed_at_ms IS NOT NULL
        AND lease_until_ms IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (external_effect_id IS NOT NULL
        AND evidence IS NOT NULL AND completed_at_ms IS NOT NULL)),
    CHECK (status NOT IN ('CANCELED', 'ABANDONED')
        OR completed_at_ms IS NOT NULL),
    CHECK (finalized_at_ms IS NULL OR completed_at_ms IS NOT NULL),
    CHECK ((finalized_at_ms IS NULL AND resolved_count IS NULL)
        OR (finalized_at_ms IS NOT NULL AND resolved_count IS NOT NULL)),
    CHECK (attempt_count <= attempt_limit),
    CHECK (observation_count <= observation_limit),
    CHECK ((observation_started_at_ms IS NULL
                AND observation_deadline_ms IS NULL)
        OR (observation_started_at_ms IS NOT NULL
                AND observation_deadline_ms > observation_started_at_ms)),
    UNIQUE (thread_id, command_id),
    UNIQUE (id, thread_id, expected_head_sha)
);

CREATE INDEX idx_review_build_comment_action_finalize_v287
    ON review_build_comment_action_v287(status, finalized_at_ms);

CREATE TRIGGER review_build_comment_action_insert_guard_v287
BEFORE INSERT ON review_build_comment_action_v287
WHEN NOT EXISTS (
    SELECT 1
    FROM review_build_comment_proposal_v287 proposal
    JOIN review_build_selection selection
      ON selection.thread_id = proposal.thread_id
    JOIN threads trunk ON trunk.id = selection.thread_id
    WHERE proposal.thread_id = NEW.thread_id
      AND proposal.review_pass_id = NEW.review_pass_id
      AND proposal.decision IS NULL
      AND selection.spawn_mode = 'suggested_change'
      AND selection.review_pass_id = NEW.review_pass_id
      AND selection.workspace_id = NEW.workspace_id
      AND lower(selection.base_repository_id)
            = lower(NEW.remote_repository_id)
      AND lower(selection.head_repository_id)
            = lower(NEW.head_repository_id)
      AND selection.pr_number = NEW.remote_pr_number
      AND selection.head_ref = NEW.branch_name
      AND selection.reviewed_head_sha = NEW.expected_head_sha
      AND trunk.turn_version = 'V2'
      AND trunk.flow = 'build'
      AND trunk.lifecycle_state IN ('ACTIVE', 'IDLE')
      AND NOT EXISTS (
          SELECT 1 FROM tasks task WHERE task.thread_id = trunk.id)
      AND (SELECT COUNT(*)
           FROM review_build_comment_proposal_item_v287 item
           WHERE item.thread_id = proposal.thread_id)
          = (SELECT COUNT(*)
             FROM review_build_selection_item item
             WHERE item.thread_id = proposal.thread_id)
      AND EXISTS (
          SELECT 1 FROM review_build_comment_proposal_item_v287 item
          WHERE item.thread_id = proposal.thread_id))
BEGIN SELECT RAISE(ABORT,
    'review build comment action lacks its exact zero-Task Trunk subject'); END;

CREATE TRIGGER review_build_comment_action_identity_v287
BEFORE UPDATE OF id, operation_id, thread_id, review_pass_id, command_id,
        workspace_id, remote_repository_id, head_repository_id,
        remote_pr_number, branch_name, expected_head_sha, payload_json,
        payload_digest, semantic_attempt, attempt_limit, authorized_at_ms,
        observation_limit
        ON review_build_comment_action_v287
BEGIN SELECT RAISE(ABORT,
    'review build comment action identity is immutable'); END;

CREATE TRIGGER review_build_comment_action_transition_v287
BEFORE UPDATE OF status ON review_build_comment_action_v287
WHEN NOT (
    (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status IN ('CLAIMED', 'CANCELED', 'ABANDONED'))
    OR (OLD.status = 'CLAIMED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'ABANDONED'))
    OR OLD.status = NEW.status)
BEGIN SELECT RAISE(ABORT,
    'review build comment action transition is invalid'); END;

CREATE TRIGGER review_build_comment_action_baseline_v287
BEFORE UPDATE OF recovery_baseline_json ON review_build_comment_action_v287
WHEN OLD.recovery_baseline_json IS NOT NULL
  OR NEW.recovery_baseline_json IS NULL
  OR OLD.status <> 'CLAIMED'
BEGIN SELECT RAISE(ABORT,
    'review build comment recovery baseline is append-only'); END;

CREATE TRIGGER review_build_comment_observation_window_v287
BEFORE UPDATE OF observation_started_at_ms, observation_deadline_ms
        ON review_build_comment_action_v287
WHEN OLD.observation_started_at_ms IS NOT NULL
  OR OLD.observation_deadline_ms IS NOT NULL
  OR NEW.observation_started_at_ms IS NULL
  OR NEW.observation_deadline_ms <= NEW.observation_started_at_ms
  OR OLD.status <> 'CLAIMED'
BEGIN SELECT RAISE(ABORT,
    'review build comment observation window is immutable'); END;

CREATE TABLE review_build_comment_dispatch_v287 (
    action_id           TEXT NOT NULL PRIMARY KEY
        REFERENCES review_build_comment_action_v287(id) ON DELETE CASCADE,
    dispatch_ticket_id  TEXT NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    operation_id        TEXT NOT NULL UNIQUE,
    dispatched_at_ms    INTEGER NOT NULL CHECK (dispatched_at_ms >= 0)
);

CREATE TRIGGER review_build_comment_action_finalize_v287
BEFORE UPDATE OF finalized_at_ms, resolved_count
        ON review_build_comment_action_v287
WHEN NEW.finalized_at_ms IS NOT NULL
  AND (OLD.finalized_at_ms IS NOT NULL
    OR NEW.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
    OR NOT EXISTS (
        SELECT 1
        FROM review_build_comment_dispatch_v287 dispatch
        JOIN dispatch_ticket ticket
          ON ticket.id = dispatch.dispatch_ticket_id
        WHERE dispatch.action_id = OLD.id
          AND dispatch.operation_id = OLD.operation_id
          AND ticket.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
          AND ticket.delivery_acceptance = 'ACCEPTED'))
BEGIN SELECT RAISE(ABORT,
    'review build comment finalization requires accepted delivery'); END;

CREATE TRIGGER dispatch_ticket_review_build_comment_v287
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'APPLY_REVIEW_BUILD_COMMENTS'
  OR NEW.callback_route = 'REVIEW_BUILD_COMMENT_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM review_build_comment_action_v287 action
        WHERE action.operation_id = NEW.operation_id
          AND action.thread_id = NEW.owner_id
          AND action.thread_id = NEW.trunk_id
          AND action.workspace_id = NEW.workspace_id
          AND action.semantic_attempt = NEW.attempt
          AND action.expected_head_sha = NEW.expected_head_sha
          AND NEW.operation_kind = 'APPLY_REVIEW_BUILD_COMMENTS'
          AND NEW.callback_route = 'REVIEW_BUILD_COMMENT_RESULT'
          AND NEW.async_family = 'GITHUB_EFFECT'
          AND NEW.owner_kind = 'TRUNK'
          AND NEW.lane_mask = 32
          AND NEW.trunk_control = 1
          AND NEW.exclusive_task = 0
          AND NEW.writer_required = 0
          AND NEW.task_id IS NULL AND NEW.task_epoch IS NULL
          AND NEW.stage_id IS NULL AND NEW.stage_generation IS NULL
          AND NEW.expected_code_fingerprint IS NULL
          AND NEW.expected_base_sha IS NULL)
    THEN RAISE(ABORT,
        'review build comment dispatch differs from its authorization') END;
END;

CREATE TRIGGER review_build_comment_dispatch_insert_v287
BEFORE INSERT ON review_build_comment_dispatch_v287
WHEN NOT EXISTS (
    SELECT 1
    FROM review_build_comment_action_v287 action
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    WHERE action.id = NEW.action_id
      AND action.operation_id = NEW.operation_id
      AND ticket.operation_id = NEW.operation_id
      AND ticket.owner_kind = 'TRUNK'
      AND ticket.owner_id = action.thread_id
      AND ticket.trunk_id = action.thread_id
      AND ticket.operation_kind = 'APPLY_REVIEW_BUILD_COMMENTS'
      AND ticket.callback_route = 'REVIEW_BUILD_COMMENT_RESULT')
BEGIN SELECT RAISE(ABORT,
    'review build comment dispatch map is not exact'); END;

CREATE TRIGGER review_build_comment_proposal_decision_v287
BEFORE UPDATE OF decision, decision_command_id, decided_at_ms
        ON review_build_comment_proposal_v287
WHEN OLD.decision IS NOT NULL
  OR NEW.decision IS NULL
  OR NEW.decision_command_id IS NULL
  OR NEW.decided_at_ms IS NULL
  OR (NEW.decision = 'APPROVE' AND NOT EXISTS (
      SELECT 1 FROM review_build_comment_action_v287 action
      WHERE action.thread_id = OLD.thread_id
        AND action.review_pass_id = OLD.review_pass_id
        AND action.command_id = NEW.decision_command_id))
  OR (NEW.decision = 'DISCARD' AND EXISTS (
      SELECT 1 FROM review_build_comment_action_v287 action
      WHERE action.thread_id = OLD.thread_id))
BEGIN SELECT RAISE(ABORT,
    'review build comment decision is stale or lacks exact authorization'); END;

CREATE TRIGGER review_build_comment_proposal_identity_v287
BEFORE UPDATE OF thread_id, review_pass_id, selection_digest, created_at_ms
        ON review_build_comment_proposal_v287
BEGIN SELECT RAISE(ABORT,
    'review build comment proposal identity is immutable'); END;

CREATE TRIGGER review_build_comment_proposal_item_update_v287
BEFORE UPDATE ON review_build_comment_proposal_item_v287
BEGIN SELECT RAISE(ABORT,
    'review build comment proposal item is immutable'); END;

-- Human-included arbitration findings are as authoritative as consensus
-- findings. Keep the writable author path on the same frozen revision rule.
DROP TRIGGER review_build_task_materialization;
CREATE TRIGGER review_build_task_materialization
BEFORE INSERT ON tasks
WHEN NEW.workflow_version = 'V2'
  AND EXISTS (
      SELECT 1
      FROM task_assignment assignment
      JOIN threads trunk ON trunk.id = NEW.thread_id
      WHERE assignment.id = NEW.assignment_id
        AND assignment.trunk_id = trunk.id
        AND assignment.kind = 'REVIEW_FINDINGS'
        AND (trunk.parent_review_pass_id IS NOT NULL OR EXISTS (
            SELECT 1 FROM review_build_selection selection
            WHERE selection.thread_id = NEW.thread_id)))
  AND NOT EXISTS (
      SELECT 1
      FROM task_assignment assignment
      JOIN threads trunk ON trunk.id = NEW.thread_id
      JOIN review_build_selection selection
        ON selection.thread_id = trunk.id
       AND selection.review_pass_id = assignment.source_id
      WHERE assignment.id = NEW.assignment_id
        AND assignment.trunk_id = trunk.id
        AND assignment.kind = 'REVIEW_FINDINGS'
        AND trunk.parent_review_pass_id = selection.review_pass_id
        AND trunk.workspace_id = selection.workspace_id
        AND selection.spawn_mode = 'author_is_reviewer'
        AND assignment.pr_number = selection.pr_number
        AND assignment.remote_head_sha = selection.reviewed_head_sha
        AND lower(assignment.base_repository_id)
              = lower(selection.base_repository_id)
        AND lower(assignment.head_repository_id)
              = lower(selection.head_repository_id)
        AND lower(assignment.repository_id)
              = lower(selection.head_repository_id)
        AND lower(selection.repo_full_name)
              = lower(selection.base_repository_id)
        AND ((lower(selection.base_repository_id)
                  = lower(selection.head_repository_id)
                AND assignment.repository_route = 'DIRECT')
          OR (lower(selection.base_repository_id)
                  <> lower(selection.head_repository_id)
                AND assignment.repository_route = 'FORK'))
        AND assignment.base_ref = selection.base_ref
        AND assignment.head_ref = selection.head_ref
        AND (SELECT COUNT(*)
             FROM task_assignment_review_finding assigned
             WHERE assigned.assignment_id = assignment.id)
            = (SELECT COUNT(*)
               FROM review_build_selection_item frozen
               WHERE frozen.thread_id = selection.thread_id)
        AND NOT EXISTS (
            SELECT 1
            FROM review_build_selection_item frozen
            LEFT JOIN task_assignment_review_finding assigned
              ON assigned.assignment_id = assignment.id
             AND assigned.source_review_id = frozen.review_pass_id
             AND assigned.finding_id = frozen.finding_id
             AND assigned.finding_revision = frozen.finding_revision
             AND assigned.content_digest = frozen.content_digest
            JOIN review_findings current ON current.id = frozen.finding_id
            WHERE frozen.thread_id = selection.thread_id
              AND (assigned.finding_id IS NULL
                OR current.review_pass_id <> frozen.review_pass_id
                OR current.revision <> frozen.finding_revision
                OR current.status NOT IN ('agreed', 'arbitrated')))
  )
BEGIN
    SELECT RAISE(ABORT,
        'V2 Task materialization has a stale or mismatched review build selection');
END;

CREATE TRIGGER review_build_comment_dispatch_update_v287
BEFORE UPDATE ON review_build_comment_dispatch_v287
BEGIN SELECT RAISE(ABORT,
    'review build comment dispatch is immutable'); END;

-- Every row is immutable outside an explicitly authorized V269 Trunk purge.
CREATE TRIGGER review_build_comment_proposal_delete_v287
BEFORE DELETE ON review_build_comment_proposal_v287
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.thread_id)
BEGIN SELECT RAISE(ABORT,
    'review build comment proposal cannot be deleted'); END;

CREATE TRIGGER review_build_comment_proposal_item_delete_v287
BEFORE DELETE ON review_build_comment_proposal_item_v287
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.thread_id)
BEGIN SELECT RAISE(ABORT,
    'review build comment proposal item cannot be deleted'); END;

CREATE TRIGGER review_build_comment_action_delete_v287
BEFORE DELETE ON review_build_comment_action_v287
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.thread_id)
BEGIN SELECT RAISE(ABORT,
    'review build comment action cannot be deleted'); END;

CREATE TRIGGER review_build_comment_dispatch_delete_v287
BEFORE DELETE ON review_build_comment_dispatch_v287
WHEN NOT EXISTS (
    SELECT 1
    FROM review_build_comment_action_v287 action
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = action.thread_id
    WHERE action.id = OLD.action_id)
BEGIN SELECT RAISE(ABORT,
    'review build comment dispatch cannot be deleted'); END;

CREATE TRIGGER v2_trunk_purge_review_build_comment_guard_v287
BEFORE INSERT ON v2_trunk_purge_authorization_v269
WHEN EXISTS (
    SELECT 1 FROM review_build_comment_action_v287 action
    WHERE action.thread_id = NEW.trunk_id
      AND (action.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
        OR action.finalized_at_ms IS NULL))
BEGIN SELECT RAISE(ABORT,
    'V2 Trunk purge cannot race a review build comment action'); END;
