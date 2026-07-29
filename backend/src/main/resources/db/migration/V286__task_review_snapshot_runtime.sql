-- A Task-owned AgentReview must freeze its exact code subject before it
-- creates ReviewAssignmentTurns.  The request path records only this intent;
-- Git reads run later under the Task's LOCAL_GIT writer lease.
CREATE TABLE task_review_snapshot_operation_v286 (
    id                        TEXT    NOT NULL PRIMARY KEY,
    review_id                 TEXT    NOT NULL UNIQUE
        REFERENCES review_session(id) ON DELETE CASCADE,
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
    start_options_json        TEXT    NOT NULL CHECK (json_valid(start_options_json)),
    status                    TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'COMPLETED', 'FAILED', 'CANCELED', 'SUPERSEDED')),
    result_json               TEXT,
    error_message             TEXT,
    requested_at_ms           INTEGER NOT NULL,
    completed_at_ms           INTEGER,
    CHECK ((repository IS NULL) = (remote_pr_number IS NULL)),
    CHECK (repository IS NULL OR length(trim(repository)) > 0),
    CHECK (remote_pr_number IS NULL OR remote_pr_number > 0),
    CHECK (length(trim(base_branch)) > 0
        AND length(worktree_path) > 0
        AND length(code_fingerprint) > 0
        AND length(expected_head_sha) > 0
        AND length(expected_base_sha) > 0),
    CHECK ((status = 'REQUESTED') = (completed_at_ms IS NULL)),
    CHECK ((status = 'REQUESTED') = (result_json IS NULL))
);

CREATE TRIGGER task_review_snapshot_operation_insert_v286
BEFORE INSERT ON task_review_snapshot_operation_v286
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
      AND review.status = 'ACTIVE'
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
        'Task review snapshot requires an exact active V2 Task subject');
END;

CREATE TRIGGER task_review_snapshot_identity_immutable_v286
BEFORE UPDATE OF id, review_id, pr_id, repository, remote_pr_number,
    base_branch, pr_title, pr_description, task_id, task_epoch, worktree_path,
    code_fingerprint, expected_head_sha, expected_base_sha,
    start_options_json, requested_at_ms
ON task_review_snapshot_operation_v286
BEGIN
    SELECT RAISE(ABORT, 'Task review snapshot identity is immutable');
END;

CREATE TRIGGER task_review_snapshot_transition_v286
BEFORE UPDATE OF status ON task_review_snapshot_operation_v286
WHEN OLD.status <> 'REQUESTED' OR NEW.status = 'REQUESTED'
BEGIN
    SELECT RAISE(ABORT, 'Task review snapshot has one terminal transition');
END;

CREATE TRIGGER task_review_snapshot_terminal_immutable_v286
BEFORE UPDATE ON task_review_snapshot_operation_v286
WHEN OLD.status <> 'REQUESTED'
BEGIN
    SELECT RAISE(ABORT, 'Terminal Task review snapshot is immutable');
END;

CREATE TRIGGER dispatch_ticket_task_review_snapshot_v286
BEFORE INSERT ON dispatch_ticket
WHEN NEW.callback_route = 'TASK_REVIEW_SNAPSHOT_RESULT'
  OR NEW.operation_kind = 'CAPTURE_TASK_REVIEW_SNAPSHOT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM task_review_snapshot_operation_v286 operation
        WHERE operation.id = NEW.operation_id
          AND operation.task_id = NEW.task_id
          AND operation.task_epoch = NEW.task_epoch
          AND operation.code_fingerprint = NEW.expected_code_fingerprint
          AND operation.expected_head_sha = NEW.expected_head_sha
          AND operation.expected_base_sha = NEW.expected_base_sha
          AND operation.status = 'REQUESTED'
          AND NEW.operation_kind = 'CAPTURE_TASK_REVIEW_SNAPSHOT'
          AND NEW.async_family = 'LOCAL_GIT'
          AND NEW.owner_kind = 'TASK'
          AND NEW.owner_id = NEW.task_id
          AND NEW.callback_route = 'TASK_REVIEW_SNAPSHOT_RESULT'
          AND NEW.lane_mask = 16
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 1
          AND NEW.stage_id IS NULL
          AND NEW.stage_generation IS NULL
          AND NEW.attempt = 1
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'Task review snapshot DispatchTicket is not exact') END;
END;
