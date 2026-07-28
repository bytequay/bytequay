-- Exact ownership for user-requested V2 AgentReview work and imported local
-- findings. The existing private review threads, append-only revisions, and
-- frozen LocalFeedbackBatch rows remain the content owners; these rows bind
-- the read-only review artifact to one current Local Development subject.

CREATE TABLE local_review_agent_request (
    id                         TEXT    NOT NULL PRIMARY KEY,
    review_id                  TEXT    NOT NULL
        REFERENCES review_session(id) ON DELETE CASCADE,
    review_round_id            TEXT    NOT NULL UNIQUE
        REFERENCES review_round(id) ON DELETE CASCADE,
    pr_id                      TEXT    NOT NULL REFERENCES pr(id) ON DELETE CASCADE,
    task_id                    TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                 INTEGER NOT NULL CHECK (task_epoch > 0),
    local_development_stage_id TEXT    NOT NULL
        REFERENCES local_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation           INTEGER NOT NULL CHECK (stage_generation > 0),
    dev_report_id              TEXT    NOT NULL REFERENCES dev_report(id),
    code_fingerprint           TEXT    NOT NULL,
    head_sha                   TEXT    NOT NULL,
    base_sha                   TEXT    NOT NULL,
    mode                       TEXT    NOT NULL CHECK (mode IN ('ADVISORY', 'BLOCKING')),
    task_blocker_id            TEXT    UNIQUE REFERENCES task_blocker(id),
    status                     TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'IMPORTED', 'CANCELED', 'STALE')),
    requested_by               TEXT    NOT NULL,
    requested_at_ms            INTEGER NOT NULL,
    completed_at_ms            INTEGER,
    completion_evidence        TEXT,
    CHECK (length(requested_by) > 0),
    CHECK ((mode = 'BLOCKING') = (task_blocker_id IS NOT NULL)),
    CHECK ((status = 'REQUESTED') = (completed_at_ms IS NULL))
);

CREATE INDEX idx_local_review_agent_request_task_status
    ON local_review_agent_request(task_id, status, requested_at_ms);
CREATE INDEX idx_local_review_agent_request_review_status
    ON local_review_agent_request(review_id, status, requested_at_ms);

CREATE TRIGGER local_review_agent_request_insert
BEFORE INSERT ON local_review_agent_request
BEGIN
    SELECT CASE
        WHEN NEW.status <> 'REQUESTED'
            THEN RAISE(ABORT, 'Local AgentReview request must start REQUESTED')
        WHEN NOT EXISTS (
            SELECT 1
            FROM review_session review
            JOIN review_round requested_round
              ON requested_round.id = NEW.review_round_id
            JOIN pr pull_request ON pull_request.id = review.pr_id
            JOIN tasks task ON task.id = NEW.task_id
            JOIN task_current_stage current ON current.task_id = task.id
            JOIN stage owner ON owner.id = current.stage_id
            JOIN local_development_stage local ON local.stage_id = owner.id
            JOIN dev_report report ON report.id = NEW.dev_report_id
            JOIN task_current_code_subject_v230 code ON code.task_id = task.id
            WHERE review.id = NEW.review_id
              AND review.pr_id = NEW.pr_id
              AND review.owner_task_id = NEW.task_id
              AND review.status = 'ACTIVE'
              AND review.reviewed_head_commit = NEW.head_sha
              AND requested_round.session_id = review.id
              AND requested_round.start_commit = NEW.head_sha
              AND requested_round.status IN ('QUEUED', 'RUNNING')
              AND pull_request.id = NEW.pr_id
              AND pull_request.task_id = NEW.task_id
              AND pull_request.origin = 'task'
              AND task.workflow_version = 'V2'
              AND task.lifecycle_state = 'ACTIVE'
              AND task.epoch = NEW.task_epoch
              AND current.stage_id = NEW.local_development_stage_id
              AND current.stage_generation = NEW.stage_generation
              AND owner.kind = 'LOCAL_DEVELOPMENT'
              AND owner.generation = NEW.stage_generation
              AND owner.checkpoint = 'LOCAL_REVIEW'
              AND owner.completed_at_ms IS NULL
              AND local.task_id = NEW.task_id
              AND local.generation = NEW.stage_generation
              AND local.opened_for_epoch = NEW.task_epoch
              AND report.workflow_version = 'V2'
              AND report.task_id = NEW.task_id
              AND report.local_development_stage_id = NEW.local_development_stage_id
              AND report.task_epoch = NEW.task_epoch
              AND report.stage_generation = NEW.stage_generation
              AND report.code_fingerprint = NEW.code_fingerprint
              AND report.head_sha = NEW.head_sha
              AND report.base_sha = NEW.base_sha
              AND code.code_fingerprint = NEW.code_fingerprint
              AND code.head_sha = NEW.head_sha
              AND code.base_sha = NEW.base_sha
              AND NOT EXISTS (
                  SELECT 1 FROM dev_report newer
                  WHERE newer.workflow_version = 'V2'
                    AND newer.local_development_stage_id = report.local_development_stage_id
                    AND newer.revision > report.revision)
              AND EXISTS (
                  SELECT 1 FROM brain_review_episode brain
                  WHERE brain.task_id = NEW.task_id
                    AND brain.local_development_stage_id = NEW.local_development_stage_id
                    AND brain.task_epoch = NEW.task_epoch
                    AND brain.stage_generation = NEW.stage_generation
                    AND brain.dev_report_id = NEW.dev_report_id
                    AND brain.code_fingerprint = NEW.code_fingerprint
                    AND brain.expected_head_sha = NEW.head_sha
                    AND brain.expected_base_sha = NEW.base_sha
                    AND ((brain.status = 'SUCCEEDED'
                          AND brain.verdict = 'APPROVED'
                          AND brain.unresolved_finding_count = 0)
                      OR brain.status = 'BUDGET_EXHAUSTED')))
            THEN RAISE(ABORT, 'Local AgentReview request subject is stale')
        WHEN NEW.mode = 'BLOCKING' AND NOT EXISTS (
            SELECT 1 FROM task_blocker blocker
            WHERE blocker.id = NEW.task_blocker_id
              AND blocker.task_id = NEW.task_id
              AND blocker.stage_id = NEW.local_development_stage_id
              AND blocker.owner_kind = 'STAGE'
              AND blocker.owner_id = NEW.local_development_stage_id
              AND blocker.subject_revision = NEW.id
              AND blocker.blocker_type = 'LOCAL_AGENT_REVIEW_BLOCKING'
              AND blocker.status = 'OPEN')
            THEN RAISE(ABORT, 'blocking Local AgentReview lacks its exact blocker')
    END;
END;

CREATE TRIGGER local_review_agent_request_identity_immutable
BEFORE UPDATE OF id, review_id, review_round_id, pr_id, task_id, task_epoch,
        local_development_stage_id, stage_generation, dev_report_id,
        code_fingerprint, head_sha, base_sha, mode, task_blocker_id,
        requested_by, requested_at_ms
ON local_review_agent_request
BEGIN SELECT RAISE(ABORT, 'Local AgentReview request subject is immutable'); END;

CREATE TRIGGER local_review_agent_request_transition
BEFORE UPDATE OF status ON local_review_agent_request
WHEN OLD.status <> 'REQUESTED'
  OR NEW.status NOT IN ('IMPORTED', 'CANCELED', 'STALE')
  OR NEW.completed_at_ms IS NULL
  OR NEW.completed_at_ms < OLD.requested_at_ms
  OR NEW.completion_evidence IS NULL
  OR length(trim(NEW.completion_evidence)) = 0
BEGIN SELECT RAISE(ABORT, 'illegal Local AgentReview request transition'); END;

CREATE TRIGGER local_review_agent_request_state_fields
BEFORE UPDATE OF completed_at_ms, completion_evidence
ON local_review_agent_request
WHEN NEW.status IS OLD.status
BEGIN SELECT RAISE(ABORT, 'Local AgentReview completion requires a transition'); END;

CREATE TRIGGER local_review_agent_request_terminal_immutable
BEFORE UPDATE ON local_review_agent_request
WHEN OLD.status IN ('IMPORTED', 'CANCELED', 'STALE')
BEGIN SELECT RAISE(ABORT, 'terminal Local AgentReview request is immutable'); END;

CREATE TABLE local_review_imported_finding (
    request_id          TEXT    NOT NULL
        REFERENCES local_review_agent_request(id) ON DELETE CASCADE,
    finding_id          TEXT    NOT NULL UNIQUE REFERENCES finding(id),
    thread_id           TEXT    NOT NULL UNIQUE REFERENCES local_review_thread(id),
    comment_revision_id TEXT    NOT NULL UNIQUE
        REFERENCES local_review_comment_revision(id),
    imported_by         TEXT    NOT NULL,
    imported_at_ms      INTEGER NOT NULL,
    PRIMARY KEY (request_id, finding_id),
    CHECK (length(imported_by) > 0)
);

CREATE TRIGGER local_review_imported_finding_insert
BEFORE INSERT ON local_review_imported_finding
WHEN NOT EXISTS (
    SELECT 1
    FROM local_review_agent_request request
    JOIN review_round requested_round
      ON requested_round.id = request.review_round_id
    JOIN finding finding ON finding.id = NEW.finding_id
    JOIN review_round round ON round.id = finding.round_id
    JOIN local_review_thread thread ON thread.id = NEW.thread_id
    JOIN local_review_comment_revision revision
      ON revision.id = NEW.comment_revision_id
    WHERE request.id = NEW.request_id
      AND request.status = 'REQUESTED'
      AND requested_round.session_id = request.review_id
      AND requested_round.status LIKE 'COMPLETED%'
      AND requested_round.end_commit = request.head_sha
      AND finding.session_id = request.review_id
      AND lower(finding.lifecycle_status) <> 'dropped'
      AND finding.last_checked_commit = request.head_sha
      AND round.session_id = request.review_id
      AND round.status LIKE 'COMPLETED%'
      AND round.end_commit = request.head_sha
      AND thread.pr_id = request.pr_id
      AND thread.task_id = request.task_id
      AND thread.local_development_stage_id
            = request.local_development_stage_id
      AND thread.task_epoch = request.task_epoch
      AND thread.stage_generation = request.stage_generation
      AND thread.source = CASE request.mode
          WHEN 'BLOCKING' THEN 'BLOCKING_REVIEW'
          ELSE 'ADVISORY_REVIEW' END
      AND revision.thread_id = thread.id
      AND revision.task_id = request.task_id
      AND revision.local_development_stage_id
            = request.local_development_stage_id
      AND revision.task_epoch = request.task_epoch
      AND revision.stage_generation = request.stage_generation
      AND revision.dev_report_id = request.dev_report_id
      AND revision.code_fingerprint = request.code_fingerprint
      AND revision.head_sha = request.head_sha
      AND revision.base_sha = request.base_sha
      AND revision.author_kind = thread.source
      AND revision.state = 'PENDING')
BEGIN SELECT RAISE(ABORT, 'imported finding does not match its exact completed review'); END;

CREATE TRIGGER local_review_imported_finding_immutable
BEFORE UPDATE ON local_review_imported_finding
BEGIN SELECT RAISE(ABORT, 'imported Local review finding is immutable'); END;

CREATE TRIGGER local_review_imported_finding_delete_guard
BEFORE DELETE ON local_review_imported_finding
WHEN EXISTS (SELECT 1 FROM local_review_agent_request request
             WHERE request.id = OLD.request_id)
BEGIN SELECT RAISE(ABORT, 'imported Local review finding cannot be deleted'); END;
