-- Explicit human actions against a V2 Task's pull request use one narrow,
-- durable GitHub-effect protocol.  The immutable authorization freezes the
-- exact remote identity/head and the requested payload; the dispatcher only
-- executes that frozen subject.  No row here grants standing automation
-- authority or participates in the Remote feedback-batch state machine.

CREATE TABLE v2_user_remote_action_v270 (
    id                    TEXT    NOT NULL PRIMARY KEY,
    operation_id          TEXT    NOT NULL UNIQUE,
    task_id               TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id            TEXT    NOT NULL,
    task_epoch            INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_stage_id       TEXT    NOT NULL REFERENCES stage(id) ON DELETE CASCADE,
    stage_generation      INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id  TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    pr_id                 TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    kind                  TEXT    NOT NULL CHECK (kind IN (
        'DEQUEUE', 'DELETE_REMOTE_BRANCH',
        'POST_TOP_LEVEL_COMMENT', 'SUBMIT_REVIEW')),
    remote_repository_id  TEXT    NOT NULL,
    head_repository_id    TEXT    NOT NULL,
    remote_pr_number      INTEGER NOT NULL CHECK (remote_pr_number > 0),
    branch_name           TEXT    NOT NULL,
    expected_head_sha     TEXT    NOT NULL,
    expected_base_sha     TEXT    NOT NULL,
    payload_json          TEXT    NOT NULL,
    payload_digest        TEXT    NOT NULL CHECK (length(payload_digest) = 64),
    handled_action        TEXT CHECK (handled_action IN (
        'APPROVED', 'CHANGES_REQUESTED', 'COMMENTED')),
    semantic_attempt      INTEGER NOT NULL DEFAULT 1 CHECK (semantic_attempt = 1),
    status                TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'INDETERMINATE',
        'CANCELED', 'ABANDONED')),
    attempt_count         INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit         INTEGER NOT NULL CHECK (attempt_limit > 0),
    claim_mode            TEXT CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    claim_owner           TEXT,
    claimed_at_ms         INTEGER,
    lease_until_ms        INTEGER,
    external_effect_id    TEXT,
    evidence              TEXT,
    last_error            TEXT,
    recovery_baseline_json TEXT,
    authorized_by         TEXT    NOT NULL,
    authorized_at_ms      INTEGER NOT NULL,
    completed_at_ms       INTEGER,
    finalized_at_ms       INTEGER,
    CHECK (length(trim(remote_repository_id)) > 0
        AND length(trim(head_repository_id)) > 0
        AND length(trim(branch_name)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0
        AND length(trim(payload_json)) > 0
        AND length(trim(command_id)) > 0
        AND length(trim(authorized_by)) > 0),
    CHECK ((kind IN ('POST_TOP_LEVEL_COMMENT', 'SUBMIT_REVIEW'))
        OR handled_action IS NULL),
    CHECK ((status = 'CLAIMED') = (claim_mode IS NOT NULL
        AND claim_owner IS NOT NULL AND claimed_at_ms IS NOT NULL
        AND lease_until_ms IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (external_effect_id IS NOT NULL
        AND evidence IS NOT NULL AND completed_at_ms IS NOT NULL)),
    CHECK (status NOT IN ('CANCELED', 'ABANDONED')
        OR completed_at_ms IS NOT NULL),
    CHECK ((status IN ('SUCCEEDED', 'CANCELED', 'ABANDONED'))
        OR finalized_at_ms IS NULL),
    CHECK (finalized_at_ms IS NULL OR completed_at_ms IS NOT NULL),
    CHECK (attempt_count <= attempt_limit),
    UNIQUE (id, task_id, task_epoch, remote_stage_id, stage_generation,
        expected_head_sha, expected_base_sha),
    UNIQUE (task_id, command_id)
);

CREATE INDEX idx_v2_user_remote_action_live_v270
    ON v2_user_remote_action_v270(task_id, kind, payload_digest, status);
CREATE INDEX idx_v2_user_remote_action_finalize_v270
    ON v2_user_remote_action_v270(status, finalized_at_ms);

CREATE TRIGGER v2_user_remote_action_insert_v270
BEFORE INSERT ON v2_user_remote_action_v270
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM tasks task
            JOIN threads trunk ON trunk.id = task.thread_id
            JOIN remote_pr_binding binding
              ON binding.id = NEW.remote_pr_binding_id
            JOIN pr pull_request ON pull_request.id = NEW.pr_id
            JOIN remote_development_stage remote
              ON remote.stage_id = NEW.remote_stage_id
            JOIN stage owner ON owner.id = remote.stage_id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.id = remote.accepted_snapshot_id
            WHERE task.id = NEW.task_id
              AND task.workflow_version = 'V2'
              AND task.epoch = NEW.task_epoch
              AND trunk.id = task.thread_id
              AND binding.task_id = task.id
              AND binding.pr_id = pull_request.id
              AND binding.remote_repository_id = NEW.remote_repository_id
              AND binding.head_repository_id = NEW.head_repository_id
              AND binding.remote_pr_number = NEW.remote_pr_number
              AND pull_request.task_id = task.id
              AND pull_request.origin = 'task'
              AND pull_request.repo = NEW.remote_repository_id
              AND pull_request.remote_pr_number = NEW.remote_pr_number
              AND pull_request.branch_name = NEW.branch_name
              AND remote.task_id = task.id
              AND remote.generation = NEW.stage_generation
              AND owner.task_id = task.id
              AND owner.kind = 'REMOTE_DEVELOPMENT'
              AND owner.generation = remote.generation
              AND snapshot.task_id = task.id
              AND snapshot.remote_development_stage_id = remote.stage_id
              AND snapshot.stage_generation = remote.generation
              AND snapshot.remote_pr_binding_id = binding.id
              AND snapshot.head_sha = NEW.expected_head_sha
              AND snapshot.base_sha = NEW.expected_base_sha
              AND remote.current_head_sha = NEW.expected_head_sha
              AND remote.current_base_sha = NEW.expected_base_sha)
            THEN RAISE(ABORT,
                'V2 user remote action lacks its exact Task/PR/Stage subject')
        WHEN NEW.kind NOT IN (
                'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT')
          AND NOT EXISTS (
            SELECT 1
            FROM tasks task
            JOIN task_current_stage current ON current.task_id = task.id
            JOIN stage owner ON owner.id = current.stage_id
            JOIN remote_development_stage remote ON remote.stage_id = owner.id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.id = remote.accepted_snapshot_id
            WHERE task.id = NEW.task_id
              AND task.lifecycle_state = 'ACTIVE'
              AND current.stage_id = NEW.remote_stage_id
              AND current.stage_generation = NEW.stage_generation
              AND owner.completed_at_ms IS NULL
              AND remote.current_head_sha = NEW.expected_head_sha
              AND remote.current_base_sha = NEW.expected_base_sha
              AND snapshot.pr_state = 'OPEN')
            THEN RAISE(ABORT,
                'V2 user remote action requires the current open Remote Stage')
        WHEN NEW.kind = 'POST_TOP_LEVEL_COMMENT' AND NOT EXISTS (
            SELECT 1
            FROM tasks task
            JOIN pr pull_request ON pull_request.id = NEW.pr_id
            JOIN remote_development_stage remote
              ON remote.stage_id = NEW.remote_stage_id
            JOIN stage owner ON owner.id = remote.stage_id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.id = remote.accepted_snapshot_id
            LEFT JOIN task_current_stage current ON current.task_id = task.id
            WHERE task.id = NEW.task_id
              AND remote.task_id = task.id
              AND remote.generation = NEW.stage_generation
              AND remote.current_head_sha = NEW.expected_head_sha
              AND remote.current_base_sha = NEW.expected_base_sha
              AND snapshot.head_sha = NEW.expected_head_sha
              AND snapshot.base_sha = NEW.expected_base_sha
              AND (
                  (task.lifecycle_state = 'ACTIVE'
                    AND current.stage_id = NEW.remote_stage_id
                    AND current.stage_generation = NEW.stage_generation
                    AND owner.completed_at_ms IS NULL
                    AND snapshot.pr_state = 'OPEN')
                  OR
                  (pull_request.status IN ('merged', 'closed')
                    AND snapshot.pr_state = CASE pull_request.status
                        WHEN 'merged' THEN 'MERGED' ELSE 'CLOSED' END
                    AND NOT EXISTS (
                        SELECT 1
                        FROM remote_development_stage newer
                        WHERE newer.task_id = NEW.task_id
                          AND newer.generation > NEW.stage_generation))))
            THEN RAISE(ABORT,
                'V2 comment requires the current open or exact terminal PR')
        WHEN NEW.kind = 'DELETE_REMOTE_BRANCH' AND NOT EXISTS (
            SELECT 1
            FROM pr pull_request
            JOIN remote_development_stage remote
              ON remote.stage_id = NEW.remote_stage_id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.id = remote.accepted_snapshot_id
            WHERE pull_request.id = NEW.pr_id
              AND pull_request.status = 'merged'
              AND snapshot.pr_state = 'MERGED'
              AND snapshot.head_sha = NEW.expected_head_sha
              AND snapshot.base_sha = NEW.expected_base_sha
              AND NOT EXISTS (
                  SELECT 1
                  FROM remote_development_stage newer
                  WHERE newer.task_id = NEW.task_id
                    AND newer.generation > NEW.stage_generation))
            THEN RAISE(ABORT,
                'V2 remote branch deletion requires preserved merged proof')
    END;
END;

CREATE TRIGGER v2_user_remote_action_identity_v270
BEFORE UPDATE OF id, operation_id, task_id, command_id, task_epoch, remote_stage_id,
        stage_generation, remote_pr_binding_id, pr_id, kind,
        remote_repository_id, head_repository_id, remote_pr_number,
        branch_name, expected_head_sha, expected_base_sha, payload_json,
        payload_digest, handled_action, semantic_attempt, attempt_limit,
        authorized_by, authorized_at_ms ON v2_user_remote_action_v270
BEGIN SELECT RAISE(ABORT, 'V2 user remote action identity is immutable'); END;

CREATE TRIGGER v2_user_remote_action_transition_v270
BEFORE UPDATE OF status ON v2_user_remote_action_v270
WHEN NEW.status IS NOT OLD.status
  AND NOT (
      (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status IN ('CLAIMED', 'CANCELED', 'ABANDONED'))
      OR (OLD.status = 'CLAIMED'
        AND NEW.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE',
            'CANCELED', 'ABANDONED')))
BEGIN SELECT RAISE(ABORT, 'V2 user remote action transition is invalid'); END;

CREATE TRIGGER v2_user_remote_action_baseline_v270
BEFORE UPDATE OF recovery_baseline_json ON v2_user_remote_action_v270
WHEN OLD.recovery_baseline_json IS NOT NULL
  OR NEW.recovery_baseline_json IS NULL
  OR OLD.status <> 'CLAIMED'
  OR NEW.status <> OLD.status
BEGIN SELECT RAISE(ABORT,
    'V2 user remote action recovery baseline is immutable'); END;

CREATE TABLE v2_user_remote_action_draft_v270 (
    action_id       TEXT    NOT NULL
        REFERENCES v2_user_remote_action_v270(id) ON DELETE CASCADE,
    position        INTEGER NOT NULL CHECK (position > 0),
    comment_id      TEXT    NOT NULL REFERENCES pr_comment(id),
    scope           TEXT    NOT NULL CHECK (scope IN ('pr', 'file-line')),
    file_path       TEXT,
    line_number     INTEGER,
    side            TEXT,
    start_line      INTEGER,
    start_side      TEXT,
    body            TEXT    NOT NULL,
    finding_id      TEXT,
    PRIMARY KEY (action_id, position),
    UNIQUE (action_id, comment_id),
    CHECK ((scope = 'pr' AND file_path IS NULL AND line_number IS NULL)
        OR (scope = 'file-line' AND file_path IS NOT NULL
            AND line_number IS NOT NULL AND side IS NOT NULL))
);

CREATE TRIGGER v2_user_remote_action_draft_insert_v270
BEFORE INSERT ON v2_user_remote_action_draft_v270
WHEN NOT EXISTS (
    SELECT 1
    FROM v2_user_remote_action_v270 action
    JOIN pr_comment comment ON comment.id = NEW.comment_id
    WHERE action.id = NEW.action_id
      AND action.kind = 'SUBMIT_REVIEW'
      AND comment.pr_id = action.pr_id
      AND comment.origin = 'local'
      AND comment.parent_comment_id IS NULL
      AND comment.published_at_ms IS NULL
      AND comment.stripped_on_push_at_ms IS NULL
      AND comment.resolved_at_ms IS NULL
      AND comment.dismissed_at_ms IS NULL
      AND comment.scope = NEW.scope
      AND comment.file_path IS NEW.file_path
      AND comment.line_number IS NEW.line_number
      AND COALESCE(comment.side,
            CASE WHEN comment.scope = 'file-line' THEN 'RIGHT' END) IS NEW.side
      AND comment.start_line IS NEW.start_line
      AND comment.start_side IS NEW.start_side
      AND comment.body = NEW.body
      AND comment.finding_id IS NEW.finding_id)
  OR EXISTS (
    SELECT 1
    FROM v2_user_remote_action_draft_v270 reserved
    JOIN v2_user_remote_action_v270 action
      ON action.id = reserved.action_id
    WHERE reserved.comment_id = NEW.comment_id
      AND (action.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
        OR action.finalized_at_ms IS NULL))
BEGIN SELECT RAISE(ABORT, 'V2 review draft changed before authorization'); END;

CREATE TRIGGER v2_user_remote_action_draft_update_v270
BEFORE UPDATE ON v2_user_remote_action_draft_v270
BEGIN SELECT RAISE(ABORT, 'V2 user remote action draft is immutable'); END;

CREATE TABLE v2_user_remote_action_dispatch_v270 (
    action_id          TEXT    NOT NULL PRIMARY KEY
        REFERENCES v2_user_remote_action_v270(id) ON DELETE CASCADE,
    dispatch_ticket_id TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    dispatched_at_ms   INTEGER NOT NULL
);

CREATE TRIGGER dispatch_ticket_v2_user_remote_action_v270
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'APPLY_V2_USER_REMOTE_ACTION'
   OR NEW.callback_route = 'V2_USER_REMOTE_ACTION_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM v2_user_remote_action_v270 action
        JOIN tasks task ON task.id = action.task_id
        JOIN threads trunk ON trunk.id = task.thread_id
        WHERE action.operation_id = NEW.operation_id
          AND action.status = 'REQUESTED'
          AND NEW.operation_kind = 'APPLY_V2_USER_REMOTE_ACTION'
          AND NEW.async_family = 'GITHUB_EFFECT'
          AND NEW.owner_kind = 'TASK'
          AND NEW.owner_id = action.task_id
          AND NEW.callback_route = 'V2_USER_REMOTE_ACTION_RESULT'
          AND NEW.lane_mask = 32
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.workspace_id = trunk.workspace_id
          AND NEW.trunk_id = task.thread_id
          AND NEW.task_id = action.task_id
          AND NEW.task_epoch = action.task_epoch
          AND NEW.stage_id = action.remote_stage_id
          AND NEW.stage_generation = action.stage_generation
          AND NEW.attempt = action.semantic_attempt
          AND NEW.expected_code_fingerprint IS NULL
          AND NEW.expected_head_sha = action.expected_head_sha
          AND NEW.expected_base_sha = action.expected_base_sha
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'V2 user remote action ticket is stale or unowned') END;
END;

CREATE TRIGGER v2_user_remote_action_dispatch_insert_v270
BEFORE INSERT ON v2_user_remote_action_dispatch_v270
WHEN NOT EXISTS (
    SELECT 1
    FROM v2_user_remote_action_v270 action
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    WHERE action.id = NEW.action_id
      AND action.operation_id = NEW.operation_id
      AND action.status = 'REQUESTED'
      AND ticket.operation_id = action.operation_id
      AND ticket.operation_kind = 'APPLY_V2_USER_REMOTE_ACTION'
      AND ticket.callback_route = 'V2_USER_REMOTE_ACTION_RESULT'
      AND ticket.owner_kind = 'TASK'
      AND ticket.owner_id = action.task_id
      AND ticket.task_id = action.task_id
      AND ticket.task_epoch = action.task_epoch
      AND ticket.stage_id = action.remote_stage_id
      AND ticket.stage_generation = action.stage_generation
      AND ticket.expected_head_sha = action.expected_head_sha
      AND ticket.expected_base_sha = action.expected_base_sha
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'V2 user remote action dispatch is invalid'); END;

CREATE TRIGGER v2_user_remote_action_dispatch_update_v270
BEFORE UPDATE ON v2_user_remote_action_dispatch_v270
BEGIN SELECT RAISE(ABORT, 'V2 user remote action dispatch is immutable'); END;

-- Evidence is append-only during normal operation but must disappear with an
-- explicitly authorized physical Trunk purge.  Child guards use the same
-- transaction-local V269 authority as the parent row.
CREATE TRIGGER v2_user_remote_action_delete_v270
BEFORE DELETE ON v2_user_remote_action_v270
WHEN NOT EXISTS (
    SELECT 1 FROM tasks task
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE task.id = OLD.task_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'V2 user remote action cannot be deleted'); END;

CREATE TRIGGER v2_user_remote_action_draft_delete_v270
BEFORE DELETE ON v2_user_remote_action_draft_v270
WHEN NOT EXISTS (
    SELECT 1 FROM v2_user_remote_action_v270 action
    JOIN tasks task ON task.id = action.task_id
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE action.id = OLD.action_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'V2 user remote action draft cannot be deleted'); END;

CREATE TRIGGER v2_user_remote_action_dispatch_delete_v270
BEFORE DELETE ON v2_user_remote_action_dispatch_v270
WHEN NOT EXISTS (
    SELECT 1 FROM v2_user_remote_action_v270 action
    JOIN tasks task ON task.id = action.task_id
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE action.id = OLD.action_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'V2 user remote action dispatch cannot be deleted'); END;

CREATE TRIGGER v2_trunk_purge_user_remote_action_guard_v270
BEFORE INSERT ON v2_trunk_purge_authorization_v269
WHEN EXISTS (
    SELECT 1
    FROM v2_user_remote_action_v270 action
    JOIN tasks task ON task.id = action.task_id
    WHERE task.thread_id = NEW.trunk_id
      AND task.workflow_version = 'V2'
      AND (action.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
        OR action.finalized_at_ms IS NULL))
BEGIN SELECT RAISE(ABORT,
    'V2 Trunk purge cannot race a nonterminal user remote action'); END;
