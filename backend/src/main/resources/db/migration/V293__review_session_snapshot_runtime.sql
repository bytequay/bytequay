-- Standalone reviews persist snapshot intent before any Git/GitHub work.
-- V292 has already widened DispatchTicket.owner_kind to REVIEW_SESSION.
CREATE TABLE review_session_snapshot_operation_v293 (
    id                 TEXT    NOT NULL PRIMARY KEY,
    dispatch_ticket_id TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    review_id          TEXT    NOT NULL
        REFERENCES review_session(id) ON DELETE CASCADE,
    command_id         TEXT    NOT NULL,
    pr_id              TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    repository         TEXT    NOT NULL,
    remote_pr_number   INTEGER NOT NULL CHECK (remote_pr_number > 0),
    base_branch        TEXT    NOT NULL,
    pr_title           TEXT    NOT NULL,
    pr_description     TEXT    NOT NULL,
    workspace_id       TEXT    REFERENCES workspaces(id),
    repository_root    TEXT,
    scope              TEXT    NOT NULL CHECK (scope IN ('quick', 'full')),
    request_json       TEXT    NOT NULL CHECK (json_valid(request_json)),
    expected_base_sha  TEXT    NOT NULL,
    expected_head_sha  TEXT    NOT NULL,
    status             TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    result_json        TEXT,
    error_message      TEXT,
    round_id           TEXT REFERENCES review_round(id),
    requested_at_ms    INTEGER NOT NULL,
    completed_at_ms    INTEGER,
    UNIQUE (review_id, command_id),
    CHECK (length(trim(command_id)) > 0
        AND length(trim(repository)) > 0
        AND length(trim(base_branch)) > 0
        AND length(trim(expected_base_sha)) > 0
        AND length(trim(expected_head_sha)) > 0),
    CHECK ((scope = 'quick') = (workspace_id IS NULL)),
    CHECK ((scope = 'quick') = (repository_root IS NULL)),
    CHECK (repository_root IS NULL OR length(trim(repository_root)) > 0),
    CHECK ((status = 'REQUESTED') = (completed_at_ms IS NULL)),
    CHECK ((status = 'REQUESTED') = (result_json IS NULL)),
    CHECK ((status = 'COMPLETED') = (round_id IS NOT NULL))
);

CREATE UNIQUE INDEX review_session_snapshot_one_requested_v293
    ON review_session_snapshot_operation_v293(review_id)
    WHERE status = 'REQUESTED';

CREATE INDEX review_session_snapshot_latest_v293
    ON review_session_snapshot_operation_v293(review_id, requested_at_ms DESC);

CREATE TRIGGER review_session_snapshot_operation_insert_v293
BEFORE INSERT ON review_session_snapshot_operation_v293
WHEN NOT EXISTS (
    SELECT 1
    FROM review_session review
    JOIN pr ON pr.id = review.pr_id
    WHERE review.id = NEW.review_id
      AND review.pr_id = NEW.pr_id
      AND pr.repo = NEW.repository
      AND pr.remote_pr_number = NEW.remote_pr_number
      AND pr.base_branch = NEW.base_branch
      AND pr.title = NEW.pr_title
      AND pr.description = NEW.pr_description
      AND review.owner_task_id IS NULL
      AND review.owner_thread_id IS NULL
      AND pr.task_id IS NULL
      AND review.status IN ('ACTIVE', 'STALE')
      AND review.workspace_id IS NEW.workspace_id
      AND NEW.status = 'REQUESTED'
      AND ((NEW.scope = 'quick'
              AND NEW.workspace_id IS NULL
              AND NEW.repository_root IS NULL
              AND pr.origin = 'external'
              AND pr.repo IS NOT NULL
              AND pr.remote_pr_number IS NOT NULL)
        OR (NEW.scope = 'full'
              AND NEW.workspace_id IS NOT NULL
              AND NEW.repository_root IS NOT NULL
              AND EXISTS (
                  SELECT 1
                  FROM workspace_repos binding
                  JOIN watched_repos watched
                    ON lower(binding.repo_full_name) =
                       lower(watched.owner || '/' || watched.repo)
                  WHERE binding.workspace_id = NEW.workspace_id
                    AND lower(binding.repo_full_name) = lower(pr.repo)
                    AND watched.local_clone_path = NEW.repository_root)))
      AND NEW.expected_base_sha = COALESCE((
          SELECT commit_row.sha FROM pr_commit commit_row
          WHERE commit_row.pr_id = NEW.pr_id
          ORDER BY commit_row.authored_at_ms, commit_row.id LIMIT 1),
          'unknown-base')
      AND NEW.expected_head_sha = COALESCE((
          SELECT commit_row.sha FROM pr_commit commit_row
          WHERE commit_row.pr_id = NEW.pr_id
          ORDER BY commit_row.authored_at_ms DESC, commit_row.id DESC LIMIT 1),
          'unknown-head'))
BEGIN
    SELECT RAISE(ABORT,
        'ReviewSession snapshot requires an exact standalone review subject');
END;

CREATE TRIGGER review_session_snapshot_identity_immutable_v293
BEFORE UPDATE OF id, dispatch_ticket_id, review_id, command_id, pr_id, repository,
    remote_pr_number, base_branch, pr_title, pr_description, workspace_id,
    repository_root, scope, request_json, expected_base_sha,
    expected_head_sha, requested_at_ms
ON review_session_snapshot_operation_v293
BEGIN
    SELECT RAISE(ABORT, 'ReviewSession snapshot identity is immutable');
END;

CREATE TRIGGER review_session_snapshot_delete_live_guard_v293
BEFORE DELETE ON review_session_snapshot_operation_v293
WHEN EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.id = OLD.dispatch_ticket_id
      AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
BEGIN
    SELECT RAISE(ABORT,
        'live ReviewSession snapshot must be canceled before purge');
END;

-- Detach and re-clone replace or remove the repository beneath every V2
-- owner. They are Workspace commands, not generic Session controls: active
-- Tasks must reach their own terminal lifecycle and durable dispatch must be
-- quiescent before either command commits. The reciprocal ticket guard closes
-- the admission race after a destructive Workspace command has won SQLite's
-- serialized write boundary.
CREATE TRIGGER workspace_repository_detach_quiescence_v293
BEFORE UPDATE OF detached_at_ms ON workspaces
WHEN OLD.detached_at_ms IS NULL
  AND NEW.detached_at_ms IS NOT NULL
  AND (EXISTS (
          SELECT 1
          FROM tasks task
          JOIN threads trunk ON trunk.id = task.thread_id
          WHERE trunk.workspace_id = OLD.id
            AND task.workflow_version = 'V2'
            AND task.lifecycle_state NOT IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
    OR EXISTS (
          SELECT 1 FROM dispatch_ticket ticket
          WHERE ticket.workspace_id = OLD.id
            AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')))
BEGIN
    SELECT RAISE(ABORT,
        'Workspace repository detach requires V2 quiescence');
END;

CREATE TRIGGER workspace_repository_reclone_quiescence_v293
BEFORE INSERT ON workspace_creation
WHEN NEW.operation_kind = 'reclone'
  AND NEW.state IN ('queued', 'forking', 'cloning', 'syncing')
  AND NEW.workspace_id IS NOT NULL
  AND (EXISTS (
          SELECT 1
          FROM tasks task
          JOIN threads trunk ON trunk.id = task.thread_id
          WHERE trunk.workspace_id = NEW.workspace_id
            AND task.workflow_version = 'V2'
            AND task.lifecycle_state NOT IN (
                'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
    OR EXISTS (
          SELECT 1 FROM dispatch_ticket ticket
          WHERE ticket.workspace_id = NEW.workspace_id
            AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')))
BEGIN
    SELECT RAISE(ABORT,
        'Workspace repository re-clone requires V2 quiescence');
END;

CREATE TRIGGER dispatch_ticket_workspace_repository_available_v293
BEFORE INSERT ON dispatch_ticket
WHEN NEW.workspace_id IS NOT NULL
  AND (EXISTS (
          SELECT 1 FROM workspaces workspace
          WHERE workspace.id = NEW.workspace_id
            AND workspace.detached_at_ms IS NOT NULL)
    OR EXISTS (
          SELECT 1 FROM workspace_creation creation
          WHERE creation.workspace_id = NEW.workspace_id
            AND creation.operation_kind = 'reclone'
            AND creation.state IN ('queued', 'forking', 'cloning', 'syncing')))
BEGIN
    SELECT RAISE(ABORT,
        'DispatchTicket Workspace repository is unavailable');
END;

CREATE TRIGGER review_session_snapshot_delete_ticket_v293
AFTER DELETE ON review_session_snapshot_operation_v293
BEGIN
    DELETE FROM dispatch_ticket
    WHERE id = OLD.dispatch_ticket_id
      AND status IN ('SUCCEEDED', 'FAILED', 'CANCELED');
END;

CREATE TRIGGER review_session_snapshot_transition_v293
BEFORE UPDATE OF status ON review_session_snapshot_operation_v293
WHEN OLD.status <> 'REQUESTED' OR NEW.status = 'REQUESTED'
BEGIN
    SELECT RAISE(ABORT,
        'ReviewSession snapshot has one terminal transition');
END;

CREATE TRIGGER review_session_snapshot_terminal_immutable_v293
BEFORE UPDATE ON review_session_snapshot_operation_v293
WHEN OLD.status <> 'REQUESTED'
BEGIN
    SELECT RAISE(ABORT, 'Terminal ReviewSession snapshot is immutable');
END;

CREATE TRIGGER dispatch_ticket_review_session_owner_v293
BEFORE INSERT ON dispatch_ticket
WHEN NEW.owner_kind = 'REVIEW_SESSION'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM review_session_snapshot_operation_v293 operation
        JOIN review_session review ON review.id = operation.review_id
        WHERE operation.id = NEW.operation_id
          AND operation.dispatch_ticket_id = NEW.id
          AND review.id = NEW.owner_id
          AND review.owner_task_id IS NULL
          AND review.owner_thread_id IS NULL
          AND review.workspace_id IS NEW.workspace_id
          AND NEW.trunk_id IS NULL AND NEW.task_id IS NULL
          AND NEW.task_epoch IS NULL AND NEW.stage_id IS NULL
          AND NEW.stage_generation IS NULL)
    THEN RAISE(ABORT, 'DispatchTicket ReviewSession owner is invalid') END;
END;

CREATE TRIGGER dispatch_ticket_review_session_snapshot_v293
BEFORE INSERT ON dispatch_ticket
WHEN NEW.owner_kind = 'REVIEW_SESSION'
  OR NEW.callback_route = 'REVIEW_SESSION_SNAPSHOT_RESULT'
  OR NEW.operation_kind = 'CAPTURE_REVIEW_SESSION_SNAPSHOT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM review_session_snapshot_operation_v293 operation
        WHERE operation.id = NEW.operation_id
          AND operation.review_id = NEW.owner_id
          AND operation.workspace_id IS NEW.workspace_id
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND operation.status = 'REQUESTED'
          AND NEW.operation_kind = 'CAPTURE_REVIEW_SESSION_SNAPSHOT'
          AND NEW.async_family = CASE operation.scope
              WHEN 'quick' THEN 'REMOTE_OBSERVATION' ELSE 'LOCAL_GIT' END
          AND NEW.callback_route = 'REVIEW_SESSION_SNAPSHOT_RESULT'
          AND NEW.lane_mask = CASE operation.scope
              WHEN 'quick' THEN 64 ELSE 48 END
          AND NEW.trunk_control = 0 AND NEW.exclusive_task = 0
          AND NEW.writer_required = 0 AND NEW.trunk_id IS NULL
          AND NEW.task_id IS NULL AND NEW.task_epoch IS NULL
          AND NEW.stage_id IS NULL AND NEW.stage_generation IS NULL
          AND NEW.attempt = 1
          AND NEW.expected_code_fingerprint IS NULL
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'ReviewSession snapshot DispatchTicket is not exact') END;
END;

-- Later Task-owned rounds use a per-command snapshot operation. V286 remains
-- the immutable initial-review protocol; this table removes its intentional
-- one-review/one-operation ceiling without changing historical receipts.
CREATE TABLE task_review_round_snapshot_operation_v293 (
    id                        TEXT    NOT NULL PRIMARY KEY,
    dispatch_ticket_id        TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) DEFERRABLE INITIALLY DEFERRED,
    review_id                 TEXT    NOT NULL
        REFERENCES review_session(id) ON DELETE CASCADE,
    command_id                TEXT    NOT NULL,
    pr_id                     TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    repository                TEXT,
    remote_pr_number          INTEGER,
    base_branch               TEXT    NOT NULL,
    pr_title                  TEXT    NOT NULL,
    pr_description            TEXT    NOT NULL,
    task_id                   TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                INTEGER NOT NULL CHECK (task_epoch > 0),
    worktree_path             TEXT    NOT NULL,
    code_fingerprint          TEXT    NOT NULL,
    expected_head_sha         TEXT    NOT NULL,
    expected_base_sha         TEXT    NOT NULL,
    request_json              TEXT    NOT NULL CHECK (json_valid(request_json)),
    status                    TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    result_json               TEXT,
    error_message             TEXT,
    round_id                  TEXT REFERENCES review_round(id),
    requested_at_ms           INTEGER NOT NULL,
    completed_at_ms           INTEGER,
    UNIQUE (review_id, command_id),
    CHECK ((repository IS NULL) = (remote_pr_number IS NULL)),
    CHECK (repository IS NULL OR length(trim(repository)) > 0),
    CHECK (remote_pr_number IS NULL OR remote_pr_number > 0),
    CHECK (length(trim(command_id)) > 0
        AND length(trim(base_branch)) > 0
        AND length(trim(worktree_path)) > 0
        AND length(trim(code_fingerprint)) > 0
        AND length(trim(expected_head_sha)) > 0
        AND length(trim(expected_base_sha)) > 0),
    CHECK ((status = 'REQUESTED') = (completed_at_ms IS NULL)),
    CHECK ((status = 'REQUESTED') = (result_json IS NULL)),
    CHECK ((status = 'COMPLETED') = (round_id IS NOT NULL))
);

CREATE UNIQUE INDEX task_review_round_snapshot_one_requested_v293
    ON task_review_round_snapshot_operation_v293(review_id)
    WHERE status = 'REQUESTED';

CREATE INDEX task_review_round_snapshot_latest_v293
    ON task_review_round_snapshot_operation_v293(review_id, requested_at_ms DESC);

CREATE TRIGGER task_review_round_snapshot_insert_v293
BEFORE INSERT ON task_review_round_snapshot_operation_v293
WHEN NOT EXISTS (
    SELECT 1
    FROM review_session review
    JOIN pr ON pr.id = review.pr_id
    JOIN tasks task ON task.id = pr.task_id
    JOIN threads trunk ON trunk.id = task.thread_id
    JOIN task_code_identity identity ON identity.task_id = task.id
    JOIN task_current_code_subject_v230 code ON code.task_id = task.id
    WHERE review.id = NEW.review_id
      AND review.pr_id = NEW.pr_id
      AND pr.repo IS NEW.repository
      AND pr.remote_pr_number IS NEW.remote_pr_number
      AND pr.base_branch = NEW.base_branch
      AND pr.title = NEW.pr_title
      AND pr.description = NEW.pr_description
      AND review.owner_task_id = NEW.task_id
      AND review.owner_thread_id = task.thread_id
      AND review.workspace_id = trunk.workspace_id
      AND review.status IN ('ACTIVE', 'STALE')
      AND task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.lifecycle_state = 'ACTIVE'
      AND task.epoch = NEW.task_epoch
      AND identity.worktree_path = NEW.worktree_path
      AND code.code_fingerprint = NEW.code_fingerprint
      AND code.head_sha = NEW.expected_head_sha
      AND code.base_sha = NEW.expected_base_sha
      AND NEW.status = 'REQUESTED')
BEGIN
    SELECT RAISE(ABORT,
        'Task review round snapshot requires an exact active V2 Task subject');
END;

CREATE TRIGGER task_review_round_snapshot_identity_immutable_v293
BEFORE UPDATE OF id, dispatch_ticket_id, review_id, command_id, pr_id,
    repository, remote_pr_number, base_branch, pr_title, pr_description,
    task_id, task_epoch, worktree_path, code_fingerprint,
    expected_head_sha, expected_base_sha, request_json, requested_at_ms
ON task_review_round_snapshot_operation_v293
BEGIN
    SELECT RAISE(ABORT, 'Task review round snapshot identity is immutable');
END;

CREATE TRIGGER task_review_round_snapshot_transition_v293
BEFORE UPDATE OF status ON task_review_round_snapshot_operation_v293
WHEN OLD.status <> 'REQUESTED' OR NEW.status = 'REQUESTED'
BEGIN
    SELECT RAISE(ABORT,
        'Task review round snapshot has one terminal transition');
END;

CREATE TRIGGER task_review_round_snapshot_terminal_immutable_v293
BEFORE UPDATE ON task_review_round_snapshot_operation_v293
WHEN OLD.status <> 'REQUESTED'
BEGIN
    SELECT RAISE(ABORT, 'Terminal Task review round snapshot is immutable');
END;

CREATE TRIGGER task_review_round_snapshot_delete_live_guard_v293
BEFORE DELETE ON task_review_round_snapshot_operation_v293
WHEN EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.id = OLD.dispatch_ticket_id
      AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
BEGIN
    SELECT RAISE(ABORT,
        'live Task review round snapshot must be canceled before purge');
END;

CREATE TRIGGER task_review_round_snapshot_delete_ticket_v293
AFTER DELETE ON task_review_round_snapshot_operation_v293
BEGIN
    DELETE FROM dispatch_ticket
    WHERE id = OLD.dispatch_ticket_id
      AND status IN ('SUCCEEDED', 'FAILED', 'CANCELED');
END;

CREATE TRIGGER dispatch_ticket_task_review_round_snapshot_v293
BEFORE INSERT ON dispatch_ticket
WHEN NEW.callback_route = 'TASK_REVIEW_ROUND_SNAPSHOT_RESULT'
  OR NEW.operation_kind = 'CAPTURE_TASK_REVIEW_ROUND_SNAPSHOT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM task_review_round_snapshot_operation_v293 operation
        WHERE operation.id = NEW.operation_id
          AND operation.dispatch_ticket_id = NEW.id
          AND operation.task_id = NEW.owner_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.code_fingerprint = NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND operation.status = 'REQUESTED'
          AND NEW.operation_kind = 'CAPTURE_TASK_REVIEW_ROUND_SNAPSHOT'
          AND NEW.async_family = 'LOCAL_GIT'
          AND NEW.owner_kind = 'TASK'
          AND NEW.callback_route = 'TASK_REVIEW_ROUND_SNAPSHOT_RESULT'
          AND NEW.lane_mask = 16
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 1
          AND NEW.stage_id IS NULL AND NEW.stage_generation IS NULL
          AND NEW.attempt = 1 AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'Task review round snapshot DispatchTicket is not exact') END;
END;

-- Initial V286 snapshot operations predate the explicit ticket foreign key.
-- Guard their cascade-delete too so Task/PR purge cannot orphan a live ticket.
CREATE TRIGGER task_review_snapshot_delete_live_guard_v293
BEFORE DELETE ON task_review_snapshot_operation_v286
WHEN EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.operation_id = OLD.id
      AND ticket.operation_kind = 'CAPTURE_TASK_REVIEW_SNAPSHOT'
      AND ticket.callback_route = 'TASK_REVIEW_SNAPSHOT_RESULT'
      AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
BEGIN
    SELECT RAISE(ABORT,
        'live initial Task review snapshot must be canceled before purge');
END;

CREATE TRIGGER task_review_snapshot_delete_ticket_v293
AFTER DELETE ON task_review_snapshot_operation_v286
BEGIN
    DELETE FROM dispatch_ticket
    WHERE operation_id = OLD.id
      AND operation_kind = 'CAPTURE_TASK_REVIEW_SNAPSHOT'
      AND callback_route = 'TASK_REVIEW_SNAPSHOT_RESULT'
      AND status IN ('SUCCEEDED', 'FAILED', 'CANCELED');
END;

-- A Workspace delete is the one explicit destructive boundary for a
-- standalone full review. Its typed review Turns and snapshot Operations own
-- DispatchTickets independently of a Trunk, so deleting review_session alone
-- would orphan those tickets and retain their Workspace foreign key.
--
-- Authorization is transaction-scoped by the service: every nonterminal
-- ticket must first carry cancellation intent (or already be delivering a
-- durable result), the review aggregate is removed, the exact ticket graph is
-- deleted child-before-parent, and the authorization is cleared before commit.
CREATE TABLE review_session_purge_authorization_v293 (
    review_id         TEXT    NOT NULL PRIMARY KEY
        REFERENCES review_session(id) DEFERRABLE INITIALLY DEFERRED,
    workspace_id      TEXT    NOT NULL
        REFERENCES workspaces(id) DEFERRABLE INITIALLY DEFERRED,
    authorized_at_ms  INTEGER NOT NULL CHECK (authorized_at_ms > 0)
);

CREATE TRIGGER review_session_purge_authorization_insert_v293
BEFORE INSERT ON review_session_purge_authorization_v293
WHEN NOT EXISTS (
        SELECT 1 FROM review_session review
        WHERE review.id = NEW.review_id
          AND review.workspace_id = NEW.workspace_id
          AND review.owner_thread_id IS NULL
          AND review.owner_task_id IS NULL)
   OR EXISTS (
        SELECT 1
        FROM review_session_snapshot_operation_v293 operation
        JOIN dispatch_ticket ticket
          ON ticket.id = operation.dispatch_ticket_id
        WHERE operation.review_id = NEW.review_id
          AND ticket.status NOT IN (
              'SUCCEEDED', 'FAILED', 'CANCELED',
              'RESULT_PENDING', 'DELIVERING')
          AND NOT (ticket.status = 'CLAIMED'
              AND ticket.claim_purpose = 'DELIVER')
          AND ticket.cancel_requested_at_ms IS NULL)
   OR EXISTS (
        SELECT 1
        FROM review_assignment_turn turn
        JOIN review_assignment assignment ON assignment.id = turn.assignment_id
        JOIN review_round round ON round.id = assignment.round_id
        JOIN dispatch_ticket ticket
          ON ticket.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
         AND ticket.owner_id = turn.id
         AND ticket.operation_id = turn.operation_id
         AND ticket.operation_kind = 'EXECUTE_REVIEW_ASSIGNMENT_TURN'
         AND ticket.callback_route = 'REVIEW_ASSIGNMENT_TURN_RESULT'
        WHERE round.session_id = NEW.review_id
          AND ticket.status NOT IN (
              'SUCCEEDED', 'FAILED', 'CANCELED',
              'RESULT_PENDING', 'DELIVERING')
          AND NOT (ticket.status = 'CLAIMED'
              AND ticket.claim_purpose = 'DELIVER')
          AND ticket.cancel_requested_at_ms IS NULL)
BEGIN
    SELECT RAISE(ABORT,
        'standalone ReviewSession purge requires exact canceled work');
END;

CREATE TRIGGER review_session_purge_authorization_update_v293
BEFORE UPDATE ON review_session_purge_authorization_v293
BEGIN
    SELECT RAISE(ABORT, 'ReviewSession purge authorization is immutable');
END;

CREATE TRIGGER review_session_standalone_delete_guard_v293
BEFORE DELETE ON review_session
WHEN OLD.workspace_id IS NOT NULL
  AND OLD.owner_thread_id IS NULL
  AND OLD.owner_task_id IS NULL
  AND (EXISTS (
          SELECT 1 FROM review_session_snapshot_operation_v293 operation
          WHERE operation.review_id = OLD.id)
    OR EXISTS (
          SELECT 1
          FROM review_assignment_turn turn
          JOIN review_assignment assignment ON assignment.id = turn.assignment_id
          JOIN review_round round ON round.id = assignment.round_id
          WHERE round.session_id = OLD.id))
  AND NOT EXISTS (
      SELECT 1 FROM review_session_purge_authorization_v293 authorization
      WHERE authorization.review_id = OLD.id
        AND authorization.workspace_id = OLD.workspace_id)
BEGIN
    SELECT RAISE(ABORT,
        'standalone ReviewSession typed work requires purge authorization');
END;

-- The ordinary snapshot cascade remains guarded. The explicit Workspace
-- purge is the sole exception, after the authorization trigger above has
-- proved exact cancellation intent.
DROP TRIGGER review_session_snapshot_delete_live_guard_v293;
CREATE TRIGGER review_session_snapshot_delete_live_guard_v293
BEFORE DELETE ON review_session_snapshot_operation_v293
WHEN EXISTS (
    SELECT 1 FROM dispatch_ticket ticket
    WHERE ticket.id = OLD.dispatch_ticket_id
      AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
  AND NOT EXISTS (
    SELECT 1
    FROM review_session_purge_authorization_v293 authorization
    WHERE authorization.review_id = OLD.review_id)
BEGIN
    SELECT RAISE(ABORT,
        'live ReviewSession snapshot must be canceled before purge');
END;
