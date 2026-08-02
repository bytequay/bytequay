-- Current code-subject ownership is causal, not wall-clock ordered. SQLite
-- serializes writers, so this append-only revision ledger records the durable
-- order in which admitted local subjects become visible.
CREATE TABLE task_code_subject_revision_v320 (
    revision         INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id          TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch       INTEGER NOT NULL CHECK (task_epoch > 0),
    subject_kind     TEXT    NOT NULL CHECK (subject_kind IN (
        'REMOTE_WORKTREE', 'REMOTE_STEERING', 'CI_BASE_REPAIR',
        'LOCAL_BASE_SYNC')),
    subject_id       TEXT    NOT NULL,
    code_fingerprint TEXT    NOT NULL,
    head_sha         TEXT    NOT NULL,
    base_sha         TEXT    NOT NULL,
    UNIQUE (subject_kind, subject_id)
);
CREATE INDEX task_code_subject_revision_task_v320
    ON task_code_subject_revision_v320(task_id, task_epoch, revision);

-- Keep compatibility data readable. New writes below never consult these
-- timestamps; the ordering is used only once while upgrading old databases.
INSERT INTO task_code_subject_revision_v320(
    task_id, task_epoch, subject_kind, subject_id,
    code_fingerprint, head_sha, base_sha)
SELECT task_id, task_epoch, subject_kind, subject_id,
       code_fingerprint, head_sha, base_sha
FROM (
    SELECT task_id, task_epoch, 'REMOTE_WORKTREE' AS subject_kind,
           id AS subject_id, code_fingerprint, head_sha, base_sha,
           recorded_at_ms,
           1 AS compatibility_order
    FROM remote_worktree_subject
    UNION ALL
    SELECT task_id, task_epoch, 'REMOTE_STEERING', request_id,
           code_fingerprint, head_sha, base_sha, recorded_at_ms, 2
    FROM remote_steering_code_subject_v257
    UNION ALL
    SELECT task_id, task_epoch, 'CI_BASE_REPAIR', id,
           code_fingerprint, head_sha, base_sha, recorded_at_ms, 3
    FROM ci_base_repair_subject_v303
    UNION ALL
    SELECT episode.task_id, episode.task_epoch, 'LOCAL_BASE_SYNC',
           operation.id, operation.result_code_fingerprint,
           operation.result_head_sha, operation.result_base_sha,
           operation.completed_at_ms, 4
    FROM local_publish_base_sync_operation operation
    JOIN local_publish_base_sync_episode episode
      ON episode.id = operation.episode_id
    JOIN local_publish_base_sync_delivery_receipt receipt
      ON receipt.operation_row_id = operation.id
    WHERE operation.kind = 'MECHANICAL_REBASE'
      AND operation.status = 'SUCCEEDED'
      AND operation.result_disposition = 'REBASED'
      AND receipt.acceptance IN ('ACCEPTED', 'PARKED')
)
ORDER BY task_id, task_epoch, recorded_at_ms,
         compatibility_order, subject_id;

-- The ledger may only mirror an already-admitted immutable subject.
CREATE TRIGGER task_code_subject_revision_insert_v320
BEFORE INSERT ON task_code_subject_revision_v320
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_worktree_subject subject
    WHERE NEW.subject_kind = 'REMOTE_WORKTREE'
      AND subject.id = NEW.subject_id
      AND subject.task_id = NEW.task_id
      AND subject.task_epoch = NEW.task_epoch
      AND subject.code_fingerprint = NEW.code_fingerprint
      AND subject.head_sha = NEW.head_sha
      AND subject.base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM remote_steering_code_subject_v257 subject
    WHERE NEW.subject_kind = 'REMOTE_STEERING'
      AND subject.request_id = NEW.subject_id
      AND subject.task_id = NEW.task_id
      AND subject.task_epoch = NEW.task_epoch
      AND subject.code_fingerprint = NEW.code_fingerprint
      AND subject.head_sha = NEW.head_sha
      AND subject.base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM ci_base_repair_subject_v303 subject
    WHERE NEW.subject_kind = 'CI_BASE_REPAIR'
      AND subject.id = NEW.subject_id
      AND subject.task_id = NEW.task_id
      AND subject.task_epoch = NEW.task_epoch
      AND subject.code_fingerprint = NEW.code_fingerprint
      AND subject.head_sha = NEW.head_sha
      AND subject.base_sha = NEW.base_sha
    UNION ALL
    SELECT 1
    FROM local_publish_base_sync_operation operation
    JOIN local_publish_base_sync_episode episode
      ON episode.id = operation.episode_id
    JOIN local_publish_base_sync_delivery_receipt receipt
      ON receipt.operation_row_id = operation.id
    WHERE NEW.subject_kind = 'LOCAL_BASE_SYNC'
      AND operation.id = NEW.subject_id
      AND episode.task_id = NEW.task_id
      AND episode.task_epoch = NEW.task_epoch
      AND operation.kind = 'MECHANICAL_REBASE'
      AND operation.status = 'SUCCEEDED'
      AND operation.result_disposition = 'REBASED'
      AND operation.result_code_fingerprint = NEW.code_fingerprint
      AND operation.result_head_sha = NEW.head_sha
      AND operation.result_base_sha = NEW.base_sha
      AND receipt.acceptance IN ('ACCEPTED', 'PARKED'))
BEGIN SELECT RAISE(ABORT,
    'Code subject revision lacks exact admitted evidence'); END;

CREATE TRIGGER task_code_subject_revision_immutable_v320
BEFORE UPDATE ON task_code_subject_revision_v320
BEGIN SELECT RAISE(ABORT, 'Code subject revision is immutable'); END;

CREATE TRIGGER remote_worktree_subject_revision_v320
AFTER INSERT ON remote_worktree_subject
BEGIN
    INSERT INTO task_code_subject_revision_v320(
        task_id, task_epoch, subject_kind, subject_id,
        code_fingerprint, head_sha, base_sha)
    VALUES (NEW.task_id, NEW.task_epoch, 'REMOTE_WORKTREE', NEW.id,
            NEW.code_fingerprint, NEW.head_sha, NEW.base_sha);
END;

CREATE TRIGGER remote_steering_code_subject_revision_v320
AFTER INSERT ON remote_steering_code_subject_v257
BEGIN
    INSERT INTO task_code_subject_revision_v320(
        task_id, task_epoch, subject_kind, subject_id,
        code_fingerprint, head_sha, base_sha)
    VALUES (NEW.task_id, NEW.task_epoch, 'REMOTE_STEERING', NEW.request_id,
            NEW.code_fingerprint, NEW.head_sha, NEW.base_sha);
END;

CREATE TRIGGER ci_base_repair_subject_revision_v320
AFTER INSERT ON ci_base_repair_subject_v303
BEGIN
    INSERT INTO task_code_subject_revision_v320(
        task_id, task_epoch, subject_kind, subject_id,
        code_fingerprint, head_sha, base_sha)
    VALUES (NEW.task_id, NEW.task_epoch, 'CI_BASE_REPAIR', NEW.id,
            NEW.code_fingerprint, NEW.head_sha, NEW.base_sha);
END;

CREATE TRIGGER local_base_sync_subject_revision_v320
AFTER INSERT ON local_publish_base_sync_delivery_receipt
WHEN NEW.acceptance IN ('ACCEPTED', 'PARKED')
 AND EXISTS (
    SELECT 1
    FROM local_publish_base_sync_operation operation
    WHERE operation.id = NEW.operation_row_id
      AND operation.kind = 'MECHANICAL_REBASE'
      AND operation.status = 'SUCCEEDED'
      AND operation.result_disposition = 'REBASED')
BEGIN
    INSERT INTO task_code_subject_revision_v320(
        task_id, task_epoch, subject_kind, subject_id,
        code_fingerprint, head_sha, base_sha)
    SELECT episode.task_id, episode.task_epoch, 'LOCAL_BASE_SYNC',
           operation.id, operation.result_code_fingerprint,
           operation.result_head_sha, operation.result_base_sha
    FROM local_publish_base_sync_operation operation
    JOIN local_publish_base_sync_episode episode
      ON episode.id = operation.episode_id
    WHERE operation.id = NEW.operation_row_id;
END;

-- A completed local BASE_SYNC Turn hands authority back to its DevReport.
-- Other admitted subjects remain current by durable revision, independent of
-- process clocks or lexicographic subject ids.
DROP VIEW task_current_code_subject_v230;
CREATE VIEW task_current_code_subject_v230 AS
SELECT task.id AS task_id,
       COALESCE(current_local.code_fingerprint, remote.code_fingerprint,
                report.code_fingerprint, code.code_fingerprint) AS code_fingerprint,
       COALESCE(current_local.head_sha, remote.head_sha,
                report.head_sha, code.local_head_sha) AS head_sha,
       COALESCE(current_local.base_sha, remote.base_sha,
                report.base_sha, code.base_sha) AS base_sha
FROM tasks task
JOIN task_code_identity code ON code.task_id = task.id
LEFT JOIN dev_report report ON report.id = (
    SELECT candidate.id FROM dev_report candidate
    WHERE candidate.workflow_version = 'V2' AND candidate.task_id = task.id
    ORDER BY candidate.created_at_ms DESC, candidate.id DESC LIMIT 1)
LEFT JOIN remote_code_subject remote ON remote.id = (
    SELECT candidate.id FROM remote_code_subject candidate
    WHERE candidate.task_id = task.id AND candidate.task_epoch = task.epoch
    ORDER BY candidate.created_at_ms DESC, candidate.id DESC LIMIT 1)
LEFT JOIN task_code_subject_revision_v320 current_local
  ON current_local.revision = (
    SELECT candidate.revision
    FROM task_code_subject_revision_v320 candidate
    WHERE candidate.task_id = task.id
      AND candidate.task_epoch = task.epoch
      AND (candidate.subject_kind <> 'LOCAL_BASE_SYNC' OR EXISTS (
          SELECT 1
          FROM local_publish_base_sync_operation operation
          JOIN local_publish_base_sync_episode episode
            ON episode.id = operation.episode_id
          JOIN local_publish_base_sync_delivery_receipt receipt
            ON receipt.operation_row_id = operation.id
          WHERE operation.id = candidate.subject_id
            AND episode.task_id = candidate.task_id
            AND episode.task_epoch = candidate.task_epoch
            AND operation.kind = 'MECHANICAL_REBASE'
            AND operation.status = 'SUCCEEDED'
            AND operation.result_disposition = 'REBASED'
            AND receipt.acceptance IN ('ACCEPTED', 'PARKED')
            AND NOT EXISTS (
                SELECT 1
                FROM local_stage_turn_request request
                JOIN dev_report completed
                  ON completed.stage_turn_id = request.stage_turn_id
                WHERE request.base_sync_episode_id = episode.id)))
    ORDER BY candidate.revision DESC LIMIT 1)
WHERE task.workflow_version = 'V2';
