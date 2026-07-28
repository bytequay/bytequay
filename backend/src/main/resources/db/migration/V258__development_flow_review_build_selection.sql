-- Freeze the exact standalone-review findings selected when a build Trunk is
-- spawned. Task creation later consumes this snapshot instead of re-reading
-- mutable review rows or downgrading the handoff to EXISTING_OWN_PR.

-- Standalone review findings predate optimistic revisions. Give every mutable
-- source row a monotonic revision so a frozen selection can prove that the
-- row did not change, even if its visible content is later changed back.
ALTER TABLE review_findings ADD COLUMN revision INTEGER NOT NULL DEFAULT 1
    CHECK (revision > 0);

CREATE TRIGGER review_finding_revision_advance
AFTER UPDATE OF review_pass_id, path, line, severity, status, body, resolution,
        posted_comment_id, created_at_ms, debate_status, debate_rounds
        ON review_findings
WHEN NEW.review_pass_id IS NOT OLD.review_pass_id
  OR NEW.path IS NOT OLD.path
  OR NEW.line IS NOT OLD.line
  OR NEW.severity IS NOT OLD.severity
  OR NEW.status IS NOT OLD.status
  OR NEW.body IS NOT OLD.body
  OR NEW.resolution IS NOT OLD.resolution
  OR NEW.posted_comment_id IS NOT OLD.posted_comment_id
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
  OR NEW.debate_status IS NOT OLD.debate_status
  OR NEW.debate_rounds IS NOT OLD.debate_rounds
BEGIN
    UPDATE review_findings
    SET revision = OLD.revision + 1
    WHERE id = OLD.id AND revision = OLD.revision;
END;

CREATE TABLE review_build_selection (
    thread_id          TEXT    PRIMARY KEY
        REFERENCES threads(id) ON DELETE RESTRICT,
    review_pass_id     TEXT    NOT NULL UNIQUE
        REFERENCES review_passes(id) ON DELETE RESTRICT,
    repo_full_name     TEXT    NOT NULL,
    pr_number          INTEGER NOT NULL CHECK (pr_number > 0),
    reviewed_head_sha  TEXT    NOT NULL,
    workspace_id       TEXT    NOT NULL REFERENCES workspaces(id),
    opening_title      TEXT    NOT NULL,
    selection_policy   TEXT    NOT NULL CHECK (selection_policy IN (
        'ALL_ELIGIBLE', 'EXPLICIT')),
    spawn_mode         TEXT    NOT NULL CHECK (spawn_mode IN (
        'author_is_reviewer', 'suggested_change')),
    base_repository_id TEXT    NOT NULL,
    head_repository_id TEXT    NOT NULL,
    base_ref           TEXT    NOT NULL,
    head_ref           TEXT    NOT NULL,
    selection_digest  TEXT    NOT NULL,
    frozen_at_ms       INTEGER NOT NULL CHECK (frozen_at_ms >= 0),
    CHECK (length(trim(repo_full_name)) > 0
        AND length(trim(reviewed_head_sha)) > 0
        AND length(trim(workspace_id)) > 0
        AND length(trim(opening_title)) > 0
        AND length(trim(base_repository_id)) > 0
        AND length(trim(head_repository_id)) > 0
        AND length(trim(base_ref)) > 0
        AND length(trim(head_ref)) > 0
        AND length(trim(selection_digest)) > 0)
);

CREATE TABLE review_build_selection_item (
    thread_id          TEXT    NOT NULL
        REFERENCES review_build_selection(thread_id) ON DELETE RESTRICT,
    position           INTEGER NOT NULL CHECK (position > 0),
    review_pass_id     TEXT    NOT NULL,
    finding_id         TEXT    NOT NULL
        REFERENCES review_findings(id) ON DELETE RESTRICT,
    finding_revision   INTEGER NOT NULL CHECK (finding_revision > 0),
    content_json       TEXT    NOT NULL,
    content_digest     TEXT    NOT NULL,
    PRIMARY KEY (thread_id, position),
    UNIQUE (thread_id, finding_id),
    CHECK (length(trim(review_pass_id)) > 0
        AND length(trim(finding_id)) > 0
        AND length(trim(content_json)) > 0
        AND length(trim(content_digest)) > 0)
);

CREATE TRIGGER review_build_selection_item_insert
BEFORE INSERT ON review_build_selection_item
WHEN NEW.position <> COALESCE((
        SELECT MAX(item.position) + 1
        FROM review_build_selection_item item
        WHERE item.thread_id = NEW.thread_id), 1)
  OR NOT EXISTS (
      SELECT 1
      FROM review_build_selection selection
      JOIN review_findings finding ON finding.id = NEW.finding_id
      WHERE selection.thread_id = NEW.thread_id
        AND selection.review_pass_id = NEW.review_pass_id
        AND finding.review_pass_id = NEW.review_pass_id)
BEGIN
    SELECT RAISE(ABORT, 'review build finding does not match its frozen selection');
END;

CREATE TRIGGER review_build_selection_immutable
BEFORE UPDATE ON review_build_selection
BEGIN
    SELECT RAISE(ABORT, 'Review build selection is immutable');
END;

CREATE TRIGGER review_build_selection_delete_guard
BEFORE DELETE ON review_build_selection
BEGIN
    SELECT RAISE(ABORT, 'Review build selection cannot be deleted');
END;

CREATE TRIGGER review_build_selection_item_immutable
BEFORE UPDATE ON review_build_selection_item
BEGIN
    SELECT RAISE(ABORT, 'Review build selection item is immutable');
END;

CREATE TRIGGER review_build_selection_item_delete_guard
BEFORE DELETE ON review_build_selection_item
BEGIN
    SELECT RAISE(ABORT, 'Review build selection item cannot be deleted');
END;

-- TaskOutcome is the only V2 authority allowed to resolve a frozen review
-- selection. This receipt makes the synchronous review-owned command
-- idempotent and gives startup recovery a durable missing-work query.
CREATE TABLE review_build_outcome_receipt (
    task_outcome_id     TEXT    PRIMARY KEY
        REFERENCES task_outcome(id) ON DELETE RESTRICT,
    task_id             TEXT    NOT NULL UNIQUE REFERENCES tasks(id),
    thread_id           TEXT    NOT NULL
        REFERENCES review_build_selection(thread_id) ON DELETE RESTRICT,
    review_pass_id      TEXT    NOT NULL REFERENCES review_passes(id),
    terminal_reason     TEXT    NOT NULL CHECK (terminal_reason IN (
        'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')),
    disposition         TEXT    NOT NULL CHECK (disposition IN (
        'RESOLVED', 'IGNORED_TERMINAL', 'STALE_SELECTION')),
    selection_digest    TEXT    NOT NULL,
    resolved_count      INTEGER NOT NULL CHECK (resolved_count >= 0),
    detail              TEXT    NOT NULL,
    recorded_at_ms      INTEGER NOT NULL CHECK (recorded_at_ms >= 0),
    CHECK (length(trim(review_pass_id)) > 0
        AND length(trim(selection_digest)) > 0
        AND length(trim(detail)) > 0
        AND ((disposition = 'RESOLVED' AND terminal_reason = 'COMPLETED')
          OR (disposition = 'IGNORED_TERMINAL'
                AND terminal_reason <> 'COMPLETED')
          OR disposition = 'STALE_SELECTION'))
);

CREATE TRIGGER review_build_outcome_receipt_insert
BEFORE INSERT ON review_build_outcome_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM task_outcome outcome
    JOIN tasks task ON task.id = outcome.task_id
    JOIN task_assignment assignment ON assignment.id = task.assignment_id
    JOIN review_build_selection selection ON selection.thread_id = task.thread_id
    WHERE outcome.id = NEW.task_outcome_id
      AND outcome.task_id = NEW.task_id
      AND outcome.terminal_reason = NEW.terminal_reason
      AND task.workflow_version = 'V2'
      AND assignment.kind = 'REVIEW_FINDINGS'
      AND assignment.source_id = NEW.review_pass_id
      AND selection.thread_id = NEW.thread_id
      AND selection.review_pass_id = NEW.review_pass_id
      AND selection.selection_digest = NEW.selection_digest)
BEGIN
    SELECT RAISE(ABORT, 'Review build outcome receipt lacks exact TaskOutcome ownership');
END;

CREATE TRIGGER review_build_outcome_receipt_immutable
BEFORE UPDATE ON review_build_outcome_receipt
BEGIN
    SELECT RAISE(ABORT, 'Review build outcome receipt is immutable');
END;

CREATE TRIGGER review_build_outcome_receipt_delete_guard
BEFORE DELETE ON review_build_outcome_receipt
BEGIN
    SELECT RAISE(ABORT, 'Review build outcome receipt cannot be deleted');
END;

-- V2 lifecycle_state, not the legacy phase projection, decides whether a Task
-- still owns a PR. Canonical lower-case base-repository refs fence mixed
-- LEGACY/V2 Tasks with one database constraint.
UPDATE tasks
SET linked_pr_ref = lower((
        SELECT assignment.base_repository_id || '#' || assignment.pr_number
        FROM task_assignment assignment
        WHERE assignment.id = tasks.assignment_id))
WHERE workflow_version = 'V2'
  AND assignment_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM task_assignment assignment
      WHERE assignment.id = tasks.assignment_id
        AND assignment.pr_number IS NOT NULL
        AND assignment.base_repository_id IS NOT NULL);

DROP INDEX task_active_pr_idx;
CREATE UNIQUE INDEX task_active_pr_idx
    ON tasks(lower(linked_pr_ref))
    WHERE linked_pr_ref IS NOT NULL
      AND ((workflow_version = 'V2'
              AND lifecycle_state NOT IN ('CANCELED', 'COMPLETED', 'REMOTE_CLOSED'))
        OR (workflow_version = 'LEGACY' AND phase <> 'COMPLETED'));
