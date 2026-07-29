-- Taskless dashboard PR controls are V2 GitHub effects owned by the PR's
-- deterministic REVIEW Trunk.  The command freezes the cached exact remote
-- subject before a DispatchTicket exists; no synthetic Task or Stage is made.

ALTER TABLE pr_detail ADD COLUMN base_sha TEXT;
ALTER TABLE pr_detail ADD COLUMN merge_commit_sha TEXT;

CREATE TABLE external_pr_action_v289 (
    id                    TEXT    NOT NULL PRIMARY KEY,
    operation_id          TEXT    NOT NULL UNIQUE,
    thread_id             TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    workspace_id          TEXT    NOT NULL REFERENCES workspaces(id),
    command_id            TEXT    NOT NULL,
    pr_id                 TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    review_id             TEXT    REFERENCES review_session(id) ON DELETE RESTRICT,
    kind                  TEXT    NOT NULL CHECK (kind IN (
        'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
        'SUBMIT_REVIEW')),
    semantic_action       TEXT    NOT NULL CHECK (semantic_action IN (
        'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
        'SUBMIT_REVIEW', 'RERUN_FAILED_CHECKS', 'SET_DRAFT_STATE',
        'UPDATE_TITLE', 'UPDATE_BODY', 'CLOSE_PULL_REQUEST',
        'COMMENT_AND_CLOSE', 'REPLY_REVIEW_THREAD', 'EDIT_ISSUE_COMMENT',
        'EDIT_REVIEW_COMMENT', 'DELETE_ISSUE_COMMENT',
        'DELETE_REVIEW_COMMENT', 'ADD_REVIEWER', 'REMOVE_REVIEWER',
        'SET_ASSIGNEE', 'SET_LABEL', 'CREATE_INLINE_COMMENT',
        'REACT_PULL_REQUEST', 'REACT_REVIEW_COMMENT',
        'REACT_ISSUE_COMMENT', 'SET_THREAD_RESOLUTION', 'MERGE',
        'ENABLE_AUTO_MERGE', 'DISABLE_AUTO_MERGE')),
    remote_repository_id  TEXT    NOT NULL,
    head_repository_id    TEXT    NOT NULL,
    remote_pr_number      INTEGER NOT NULL CHECK (remote_pr_number > 0),
    branch_name           TEXT    NOT NULL,
    expected_head_sha     TEXT    NOT NULL,
    expected_base_sha     TEXT    NOT NULL,
    payload_json          TEXT    NOT NULL CHECK (json_valid(payload_json)),
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
    authorized_at_ms      INTEGER NOT NULL CHECK (authorized_at_ms >= 0),
    completed_at_ms       INTEGER,
    finalized_at_ms       INTEGER,
    CHECK (length(trim(command_id)) > 0
        AND length(trim(remote_repository_id)) > 0
        AND length(trim(head_repository_id)) > 0
        AND length(trim(branch_name)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0),
    CHECK ((semantic_action IN (
            'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
            'SUBMIT_REVIEW') AND kind = semantic_action)
        OR (semantic_action NOT IN (
            'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
            'SUBMIT_REVIEW') AND kind = 'DEQUEUE')),
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
    UNIQUE (thread_id, command_id)
);

CREATE INDEX idx_external_pr_action_pr_v289
    ON external_pr_action_v289(pr_id, authorized_at_ms DESC);
CREATE INDEX idx_external_pr_action_finalize_v289
    ON external_pr_action_v289(status, finalized_at_ms);

CREATE TRIGGER external_pr_action_insert_v289
BEFORE INSERT ON external_pr_action_v289
WHEN NOT EXISTS (
    SELECT 1
    FROM pr local_pr
    JOIN workspace_repos binding
      ON lower(binding.repo_full_name) = lower(local_pr.repo)
    JOIN threads trunk
      ON trunk.workspace_id = binding.workspace_id
     AND trunk.pr_ref = lower(local_pr.repo) || '#' || local_pr.remote_pr_number
    JOIN pull_requests cached
      ON lower(cached.repo) = lower(local_pr.repo)
     AND cached.number = local_pr.remote_pr_number
    JOIN pr_detail detail ON detail.pr_id = cached.id
    WHERE local_pr.id = NEW.pr_id
      AND local_pr.task_id IS NULL
      AND local_pr.origin = 'external'
      AND local_pr.repo = NEW.remote_repository_id COLLATE NOCASE
      AND local_pr.remote_pr_number = NEW.remote_pr_number
      AND trunk.id = NEW.thread_id
      AND trunk.workspace_id = NEW.workspace_id
      AND trunk.flow = 'review'
      AND trunk.turn_version = 'V2'
      AND trunk.lifecycle_state IN ('ACTIVE', 'IDLE')
      AND NOT EXISTS (SELECT 1 FROM tasks task WHERE task.thread_id = trunk.id)
      AND detail.base_repo = NEW.remote_repository_id COLLATE NOCASE
      AND detail.head_repo = NEW.head_repository_id COLLATE NOCASE
      AND detail.head_ref = NEW.branch_name
      AND detail.head_sha = NEW.expected_head_sha
      AND detail.base_sha = NEW.expected_base_sha
      AND trim(detail.head_sha) <> '' AND trim(detail.base_sha) <> '')
  OR (NEW.review_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM review_session review
    WHERE review.id = NEW.review_id
      AND review.pr_id = NEW.pr_id
      AND review.workspace_id = NEW.workspace_id
      AND review.owner_thread_id = NEW.thread_id
      AND review.base_commit = NEW.expected_base_sha
      AND review.reviewed_head_commit = NEW.expected_head_sha))
BEGIN SELECT RAISE(ABORT,
    'external PR action lacks its exact cached V2 REVIEW Trunk subject'); END;

CREATE TRIGGER external_pr_action_identity_v289
BEFORE UPDATE OF id, operation_id, thread_id, workspace_id, command_id,
    pr_id, review_id, kind, semantic_action, remote_repository_id,
    head_repository_id, remote_pr_number, branch_name, expected_head_sha,
    expected_base_sha, payload_json, payload_digest, handled_action,
    semantic_attempt, attempt_limit, authorized_at_ms
ON external_pr_action_v289
BEGIN SELECT RAISE(ABORT, 'external PR action identity is immutable'); END;

CREATE TRIGGER external_pr_action_transition_v289
BEFORE UPDATE OF status ON external_pr_action_v289
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status IN ('CLAIMED', 'CANCELED', 'ABANDONED'))
    OR (OLD.status = 'CLAIMED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'ABANDONED')))
BEGIN SELECT RAISE(ABORT, 'external PR action transition is invalid'); END;

CREATE TRIGGER external_pr_action_baseline_v289
BEFORE UPDATE OF recovery_baseline_json ON external_pr_action_v289
WHEN OLD.recovery_baseline_json IS NOT NULL
  OR NEW.recovery_baseline_json IS NULL
  OR OLD.status <> 'CLAIMED'
  OR NEW.status <> OLD.status
BEGIN SELECT RAISE(ABORT,
    'external PR action recovery baseline is immutable'); END;

CREATE TABLE external_pr_action_draft_v289 (
    action_id       TEXT    NOT NULL
        REFERENCES external_pr_action_v289(id) ON DELETE CASCADE,
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

CREATE TRIGGER external_pr_action_draft_insert_v289
BEFORE INSERT ON external_pr_action_draft_v289
WHEN NOT EXISTS (
    SELECT 1
    FROM external_pr_action_v289 action
    JOIN pr_comment comment ON comment.id = NEW.comment_id
    WHERE action.id = NEW.action_id
      AND action.semantic_action = 'SUBMIT_REVIEW'
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
    SELECT 1 FROM external_pr_action_draft_v289 reserved
    JOIN external_pr_action_v289 action ON action.id = reserved.action_id
    WHERE reserved.comment_id = NEW.comment_id
      AND (action.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
        OR action.finalized_at_ms IS NULL))
BEGIN SELECT RAISE(ABORT, 'external review draft changed before authorization'); END;

CREATE TRIGGER external_pr_action_draft_update_v289
BEFORE UPDATE ON external_pr_action_draft_v289
BEGIN SELECT RAISE(ABORT, 'external PR action draft is immutable'); END;

CREATE TABLE external_pr_action_dispatch_v289 (
    action_id          TEXT NOT NULL PRIMARY KEY
        REFERENCES external_pr_action_v289(id) ON DELETE CASCADE,
    dispatch_ticket_id TEXT NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    operation_id       TEXT NOT NULL UNIQUE,
    dispatched_at_ms   INTEGER NOT NULL CHECK (dispatched_at_ms >= 0)
);

CREATE TRIGGER dispatch_ticket_external_pr_action_v289
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'APPLY_V2_EXTERNAL_PR_ACTION'
  OR NEW.callback_route = 'V2_EXTERNAL_PR_ACTION_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM external_pr_action_v289 action
        WHERE action.operation_id = NEW.operation_id
          AND action.thread_id = NEW.owner_id
          AND action.thread_id = NEW.trunk_id
          AND action.workspace_id = NEW.workspace_id
          AND action.semantic_attempt = NEW.attempt
          AND action.expected_head_sha = NEW.expected_head_sha
          AND action.expected_base_sha = NEW.expected_base_sha
          AND action.status = 'REQUESTED'
          AND NEW.operation_kind = 'APPLY_V2_EXTERNAL_PR_ACTION'
          AND NEW.async_family = 'GITHUB_EFFECT'
          AND NEW.owner_kind = 'TRUNK'
          AND NEW.callback_route = 'V2_EXTERNAL_PR_ACTION_RESULT'
          AND NEW.lane_mask = 32
          AND NEW.trunk_control = 1
          AND NEW.exclusive_task = 0
          AND NEW.writer_required = 0
          AND NEW.task_id IS NULL AND NEW.task_epoch IS NULL
          AND NEW.stage_id IS NULL AND NEW.stage_generation IS NULL
          AND NEW.expected_code_fingerprint IS NULL
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT, 'external PR action ticket differs from authorization') END;
END;

CREATE TRIGGER external_pr_action_dispatch_insert_v289
BEFORE INSERT ON external_pr_action_dispatch_v289
WHEN NOT EXISTS (
    SELECT 1 FROM external_pr_action_v289 action
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    WHERE action.id = NEW.action_id
      AND action.operation_id = NEW.operation_id
      AND ticket.operation_id = NEW.operation_id
      AND ticket.owner_kind = 'TRUNK'
      AND ticket.owner_id = action.thread_id
      AND ticket.trunk_id = action.thread_id
      AND ticket.operation_kind = 'APPLY_V2_EXTERNAL_PR_ACTION'
      AND ticket.callback_route = 'V2_EXTERNAL_PR_ACTION_RESULT'
      AND ticket.expected_head_sha = action.expected_head_sha
      AND ticket.expected_base_sha = action.expected_base_sha
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT, 'external PR action dispatch is invalid'); END;

CREATE TRIGGER external_pr_action_dispatch_update_v289
BEFORE UPDATE ON external_pr_action_dispatch_v289
BEGIN SELECT RAISE(ABORT, 'external PR action dispatch is immutable'); END;

CREATE TRIGGER external_pr_action_finalize_v289
BEFORE UPDATE OF finalized_at_ms ON external_pr_action_v289
WHEN NEW.finalized_at_ms IS NOT NULL AND (
    OLD.finalized_at_ms IS NOT NULL
    OR NEW.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
    OR NOT EXISTS (
        SELECT 1 FROM external_pr_action_dispatch_v289 link
        JOIN dispatch_ticket ticket ON ticket.id = link.dispatch_ticket_id
        WHERE link.action_id = NEW.id
          AND ticket.delivery_acceptance = 'ACCEPTED'
          AND ticket.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')))
BEGIN SELECT RAISE(ABORT,
    'external PR action finalization requires accepted delivery'); END;

CREATE TRIGGER external_pr_action_delete_v289
BEFORE DELETE ON external_pr_action_v289
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.thread_id)
BEGIN SELECT RAISE(ABORT, 'external PR action cannot be deleted'); END;

CREATE TRIGGER external_pr_action_draft_delete_v289
BEFORE DELETE ON external_pr_action_draft_v289
WHEN NOT EXISTS (
    SELECT 1 FROM external_pr_action_v289 action
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = action.thread_id
    WHERE action.id = OLD.action_id)
BEGIN SELECT RAISE(ABORT, 'external PR action draft cannot be deleted'); END;

CREATE TRIGGER external_pr_action_dispatch_delete_v289
BEFORE DELETE ON external_pr_action_dispatch_v289
WHEN NOT EXISTS (
    SELECT 1 FROM external_pr_action_v289 action
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = action.thread_id
    WHERE action.id = OLD.action_id)
BEGIN SELECT RAISE(ABORT, 'external PR action dispatch cannot be deleted'); END;

CREATE TRIGGER v2_trunk_purge_external_pr_action_guard_v289
BEFORE INSERT ON v2_trunk_purge_authorization_v269
WHEN EXISTS (
    SELECT 1 FROM external_pr_action_v289 action
    WHERE action.thread_id = NEW.trunk_id
      AND (action.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
        OR action.finalized_at_ms IS NULL))
BEGIN SELECT RAISE(ABORT,
    'V2 Trunk purge cannot race a nonterminal external PR action'); END;
