-- A quality-scan Task is canceled before its CreateIssue proposal is shown.
-- Approval therefore owns no Task transition: it freezes the exact proposal,
-- claims the exact notification, and dispatches one GITHUB_EFFECT. Recovery
-- probes the immutable marker and never repeats issue creation.

CREATE TABLE v2_quality_issue_publish_v285 (
    id                       TEXT    NOT NULL PRIMARY KEY,
    operation_id             TEXT    NOT NULL UNIQUE,
    notification_id          TEXT    NOT NULL UNIQUE
        REFERENCES notifications(id) ON DELETE CASCADE,
    task_id                  TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch               INTEGER NOT NULL CHECK (task_epoch > 0),
    workspace_id             TEXT    NOT NULL REFERENCES workspaces(id),
    trunk_id                 TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    repo_owner               TEXT    NOT NULL,
    repo_name                TEXT    NOT NULL,
    issue_title              TEXT    NOT NULL,
    issue_body               TEXT    NOT NULL,
    idempotency_marker       TEXT    NOT NULL UNIQUE,
    payload_digest           TEXT    NOT NULL CHECK (length(payload_digest) = 64),
    status                   TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'EXECUTING', 'SUCCEEDED', 'INDETERMINATE',
        'FAILED', 'CANCELED', 'DELIVERED')),
    external_issue_id        INTEGER,
    external_issue_number    INTEGER,
    external_issue_url       TEXT,
    result_json              TEXT,
    last_error               TEXT,
    authorized_at_ms         INTEGER NOT NULL,
    effect_started_at_ms     INTEGER,
    effect_completed_at_ms   INTEGER,
    delivered_at_ms          INTEGER,
    CHECK (length(trim(repo_owner)) > 0
        AND length(trim(repo_name)) > 0
        AND length(trim(issue_title)) > 0
        AND length(trim(issue_body)) > 0
        AND length(trim(idempotency_marker)) > 0),
    CHECK ((status IN ('SUCCEEDED', 'DELIVERED')) =
        (external_issue_id IS NOT NULL
          AND external_issue_number IS NOT NULL
          AND external_issue_number > 0
          AND external_issue_url IS NOT NULL)),
    CHECK (status <> 'EXECUTING' OR effect_started_at_ms IS NOT NULL),
    CHECK (status NOT IN ('SUCCEEDED', 'INDETERMINATE', 'FAILED', 'DELIVERED')
        OR effect_completed_at_ms IS NOT NULL),
    CHECK (status <> 'DELIVERED' OR delivered_at_ms IS NOT NULL),
    CHECK (delivered_at_ms IS NULL OR result_json IS NOT NULL),
    CHECK (delivered_at_ms IS NULL
        OR status IN ('DELIVERED', 'FAILED', 'CANCELED')),
    UNIQUE (id, task_id, task_epoch)
);

CREATE INDEX idx_v2_quality_issue_publish_status_v285
    ON v2_quality_issue_publish_v285(status, authorized_at_ms);

CREATE TRIGGER v2_quality_issue_publish_insert_v285
BEFORE INSERT ON v2_quality_issue_publish_v285
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM notifications notification
            JOIN tasks task ON task.id = notification.task_id
            JOIN threads trunk ON trunk.id = task.thread_id
            WHERE notification.id = NEW.notification_id
              AND notification.kind = 'AWAITING_REVIEW'
              AND notification.status = 'RESOLVING'
              AND notification.thread_id = task.thread_id
              AND task.id = NEW.task_id
              AND task.workflow_version = 'V2'
              AND task.epoch = NEW.task_epoch
              AND task.lifecycle_state IN ('CANCELING', 'CLEANING', 'CANCELED')
              AND task.thread_id = NEW.trunk_id
              AND trunk.workspace_id = NEW.workspace_id
              AND json_valid(notification.payload_json)
              AND json_extract(notification.payload_json, '$.action') = 'create_issue'
              AND json_extract(notification.payload_json, '$.source')
                    = 'automation:quality-scan'
              AND json_extract(notification.payload_json, '$.repo.owner')
                    = NEW.repo_owner
              AND json_extract(notification.payload_json, '$.repo.repo')
                    = NEW.repo_name
              AND json_extract(notification.payload_json, '$.title')
                    = NEW.issue_title)
            THEN RAISE(ABORT,
                'quality issue publish lacks its exact canceled V2 Task proposal')
        WHEN NEW.status <> 'REQUESTED'
          OR NEW.idempotency_marker NOT LIKE
                '<!-- bytequay-quality-issue-operation:v1 id=% -->'
          OR instr(NEW.issue_body, NEW.idempotency_marker) = 0
          OR instr(NEW.issue_body, '<!-- bytequay-quality-scan:v1 -->') = 0
            THEN RAISE(ABORT,
                'quality issue publish lacks immutable idempotency evidence')
    END;
END;

CREATE TRIGGER v2_quality_issue_publish_identity_v285
BEFORE UPDATE OF id, operation_id, notification_id, task_id, task_epoch,
        workspace_id, trunk_id, repo_owner, repo_name, issue_title,
        issue_body, idempotency_marker, payload_digest, authorized_at_ms
ON v2_quality_issue_publish_v285
BEGIN SELECT RAISE(ABORT,
    'quality issue publish identity is immutable'); END;

CREATE TRIGGER v2_quality_issue_publish_delivery_immutable_v285
BEFORE UPDATE OF result_json, last_error, delivered_at_ms
ON v2_quality_issue_publish_v285
WHEN OLD.delivered_at_ms IS NOT NULL
  AND (NEW.result_json IS NOT OLD.result_json
    OR NEW.last_error IS NOT OLD.last_error
    OR NEW.delivered_at_ms IS NOT OLD.delivered_at_ms)
BEGIN SELECT RAISE(ABORT,
    'quality issue delivery is immutable'); END;

CREATE TRIGGER v2_quality_issue_publish_transition_v285
BEFORE UPDATE OF status ON v2_quality_issue_publish_v285
WHEN NEW.status IS NOT OLD.status
  AND NOT (
      (OLD.status = 'REQUESTED'
        AND NEW.status IN ('EXECUTING', 'SUCCEEDED', 'FAILED', 'CANCELED'))
      OR (OLD.status = 'EXECUTING'
        AND NEW.status IN ('SUCCEEDED', 'INDETERMINATE', 'FAILED', 'CANCELED'))
      OR (OLD.status = 'INDETERMINATE'
        AND NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
      OR (OLD.status = 'SUCCEEDED' AND NEW.status = 'DELIVERED'))
BEGIN SELECT RAISE(ABORT,
    'quality issue publish transition is invalid'); END;

CREATE TABLE v2_quality_issue_publish_dispatch_v285 (
    publish_id         TEXT    NOT NULL PRIMARY KEY
        REFERENCES v2_quality_issue_publish_v285(id) ON DELETE CASCADE,
    dispatch_ticket_id TEXT    NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    dispatched_at_ms   INTEGER NOT NULL
);

CREATE TRIGGER dispatch_ticket_v2_quality_issue_publish_v285
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'PUBLISH_V2_QUALITY_ISSUE'
   OR NEW.callback_route = 'V2_QUALITY_ISSUE_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1
        FROM v2_quality_issue_publish_v285 publish
        WHERE publish.operation_id = NEW.operation_id
          AND publish.status = 'REQUESTED'
          AND NEW.operation_kind = 'PUBLISH_V2_QUALITY_ISSUE'
          AND NEW.async_family = 'GITHUB_EFFECT'
          AND NEW.owner_kind = 'TASK'
          AND NEW.owner_id = publish.task_id
          AND NEW.callback_route = 'V2_QUALITY_ISSUE_RESULT'
          AND NEW.lane_mask = 32
          AND NEW.trunk_control = 0
          AND NEW.exclusive_task = 1
          AND NEW.writer_required = 0
          AND NEW.workspace_id = publish.workspace_id
          AND NEW.trunk_id = publish.trunk_id
          AND NEW.task_id = publish.task_id
          AND NEW.task_epoch = publish.task_epoch
          AND NEW.stage_id IS NULL
          AND NEW.stage_generation IS NULL
          AND NEW.attempt = 1
          AND NEW.expected_code_fingerprint IS NULL
          AND NEW.expected_head_sha IS NULL
          AND NEW.expected_base_sha IS NULL
          AND NEW.status = 'REQUESTED')
    THEN RAISE(ABORT,
        'quality issue publish ticket is stale or unowned') END;
END;

CREATE TRIGGER v2_quality_issue_publish_dispatch_insert_v285
BEFORE INSERT ON v2_quality_issue_publish_dispatch_v285
WHEN NOT EXISTS (
    SELECT 1
    FROM v2_quality_issue_publish_v285 publish
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    WHERE publish.id = NEW.publish_id
      AND publish.operation_id = NEW.operation_id
      AND ticket.operation_id = publish.operation_id
      AND ticket.operation_kind = 'PUBLISH_V2_QUALITY_ISSUE'
      AND ticket.callback_route = 'V2_QUALITY_ISSUE_RESULT'
      AND ticket.owner_kind = 'TASK'
      AND ticket.owner_id = publish.task_id
      AND ticket.task_id = publish.task_id
      AND ticket.task_epoch = publish.task_epoch
      AND ticket.stage_id IS NULL
      AND ticket.status = 'REQUESTED')
BEGIN SELECT RAISE(ABORT,
    'quality issue publish dispatch is invalid'); END;

CREATE TRIGGER v2_quality_issue_publish_dispatch_update_v285
BEFORE UPDATE ON v2_quality_issue_publish_dispatch_v285
BEGIN SELECT RAISE(ABORT,
    'quality issue publish dispatch is immutable'); END;

CREATE TRIGGER v2_quality_issue_publish_delete_v285
BEFORE DELETE ON v2_quality_issue_publish_v285
WHEN NOT EXISTS (
    SELECT 1 FROM tasks task
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE task.id = OLD.task_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT,
    'quality issue publish evidence cannot be deleted'); END;

CREATE TRIGGER v2_quality_issue_publish_dispatch_delete_v285
BEFORE DELETE ON v2_quality_issue_publish_dispatch_v285
WHEN NOT EXISTS (
    SELECT 1 FROM v2_quality_issue_publish_v285 publish
    JOIN tasks task ON task.id = publish.task_id
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = task.thread_id
    WHERE publish.id = OLD.publish_id AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT,
    'quality issue publish dispatch cannot be deleted'); END;

CREATE TRIGGER v2_trunk_purge_quality_issue_guard_v285
BEFORE INSERT ON v2_trunk_purge_authorization_v269
WHEN EXISTS (
    SELECT 1
    FROM v2_quality_issue_publish_v285 publish
    JOIN tasks task ON task.id = publish.task_id
    WHERE task.thread_id = NEW.trunk_id
      AND task.workflow_version = 'V2'
      AND (publish.status NOT IN ('DELIVERED', 'FAILED', 'CANCELED')
        OR publish.delivered_at_ms IS NULL))
BEGIN SELECT RAISE(ABORT,
    'V2 Trunk purge cannot race a quality issue publication'); END;
