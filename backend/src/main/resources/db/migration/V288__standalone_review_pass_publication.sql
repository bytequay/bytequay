-- Standalone ReviewPass publication is a durable GitHub effect.  The user
-- command freezes the exact reviewed subject, verdict, finding revisions and
-- payload before ExecutionDispatcher may touch GitHub.  TASK_PHASE-hosted
-- passes remain readable history and are never eligible for this protocol.

ALTER TABLE review_passes ADD COLUMN base_repository_id TEXT;
ALTER TABLE review_passes ADD COLUMN head_repository_id TEXT;
ALTER TABLE review_passes ADD COLUMN head_ref TEXT;

-- Best-effort compatibility backfill for local development databases. New
-- passes persist these values directly when the reviewed PR is seated.
UPDATE review_passes
SET base_repository_id = COALESCE(base_repository_id, (
        SELECT detail.base_repo
        FROM pull_requests pr
        JOIN pr_detail detail ON detail.pr_id = pr.id
        WHERE lower(pr.repo) = lower(review_passes.repo_full_name)
          AND pr.number = review_passes.pr_number
        LIMIT 1)),
    head_repository_id = COALESCE(head_repository_id, (
        SELECT detail.head_repo
        FROM pull_requests pr
        JOIN pr_detail detail ON detail.pr_id = pr.id
        WHERE lower(pr.repo) = lower(review_passes.repo_full_name)
          AND pr.number = review_passes.pr_number
        LIMIT 1)),
    head_ref = COALESCE(head_ref, (
        SELECT detail.head_ref
        FROM pull_requests pr
        JOIN pr_detail detail ON detail.pr_id = pr.id
        WHERE lower(pr.repo) = lower(review_passes.repo_full_name)
          AND pr.number = review_passes.pr_number
        LIMIT 1));

CREATE TABLE review_pass_publication_v288 (
    id                    TEXT    NOT NULL PRIMARY KEY,
    operation_id          TEXT    NOT NULL UNIQUE,
    review_pass_id        TEXT    NOT NULL UNIQUE
        REFERENCES review_passes(id) ON DELETE RESTRICT,
    thread_id             TEXT    NOT NULL REFERENCES threads(id) ON DELETE RESTRICT,
    command_id            TEXT    NOT NULL,
    workspace_id          TEXT    NOT NULL REFERENCES workspaces(id),
    remote_repository_id  TEXT    NOT NULL,
    head_repository_id    TEXT    NOT NULL,
    remote_pr_number      INTEGER NOT NULL CHECK (remote_pr_number > 0),
    branch_name           TEXT    NOT NULL,
    expected_head_sha     TEXT    NOT NULL,
    review_action         TEXT    NOT NULL CHECK (review_action IN (
        'COMMENT', 'APPROVE', 'REQUEST_CHANGES')),
    finding_ids_json      TEXT    NOT NULL CHECK (json_valid(finding_ids_json)
        AND json_type(finding_ids_json) = 'array'),
    request_digest        TEXT    NOT NULL CHECK (length(request_digest) = 64),
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
    posted_count          INTEGER CHECK (posted_count >= 0),
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
    CHECK ((finalized_at_ms IS NULL AND posted_count IS NULL)
        OR (finalized_at_ms IS NOT NULL AND posted_count IS NOT NULL)),
    CHECK (attempt_count <= attempt_limit),
    CHECK (observation_count <= observation_limit),
    CHECK ((observation_started_at_ms IS NULL
                AND observation_deadline_ms IS NULL)
        OR (observation_started_at_ms IS NOT NULL
                AND observation_deadline_ms > observation_started_at_ms)),
    UNIQUE (thread_id, command_id),
    UNIQUE (id, thread_id, expected_head_sha)
);

CREATE INDEX idx_review_pass_publication_finalize_v288
    ON review_pass_publication_v288(status, finalized_at_ms);

CREATE TABLE review_pass_publication_item_v288 (
    publication_id      TEXT    NOT NULL
        REFERENCES review_pass_publication_v288(id) ON DELETE CASCADE,
    position            INTEGER NOT NULL CHECK (position > 0),
    finding_id          TEXT    NOT NULL,
    finding_revision    INTEGER NOT NULL CHECK (finding_revision > 0),
    content_digest      TEXT    NOT NULL CHECK (length(content_digest) = 64),
    kind                TEXT    NOT NULL CHECK (kind IN ('INLINE', 'TOP_LEVEL')),
    path                TEXT,
    line                INTEGER,
    severity            TEXT    NOT NULL,
    body                TEXT    NOT NULL,
    PRIMARY KEY (publication_id, position),
    UNIQUE (publication_id, finding_id),
    CHECK (length(trim(finding_id)) > 0 AND length(trim(body)) > 0),
    CHECK ((kind = 'INLINE' AND path IS NOT NULL
                AND length(trim(path)) > 0 AND line > 0)
        OR (kind = 'TOP_LEVEL' AND line IS NULL))
);

CREATE TRIGGER review_pass_publication_insert_v288
BEFORE INSERT ON review_pass_publication_v288
WHEN NOT EXISTS (
    SELECT 1
    FROM review_passes pass
    JOIN threads trunk ON trunk.id = pass.thread_id
    WHERE pass.id = NEW.review_pass_id
      AND pass.host_kind = 'THREAD'
      AND pass.host_id = pass.thread_id
      AND pass.phase = 'terminate'
      AND pass.thread_id = NEW.thread_id
      AND pass.repo_full_name = NEW.remote_repository_id COLLATE NOCASE
      AND pass.base_repository_id = NEW.remote_repository_id COLLATE NOCASE
      AND pass.head_repository_id = NEW.head_repository_id COLLATE NOCASE
      AND pass.pr_number = NEW.remote_pr_number
      AND pass.head_ref = NEW.branch_name
      AND pass.head_sha = NEW.expected_head_sha
      AND trunk.workspace_id = NEW.workspace_id
      AND trunk.flow = 'review'
      AND trunk.turn_version = 'V2'
      AND trunk.lifecycle_state IN ('ACTIVE', 'IDLE')
      AND NOT EXISTS (
          SELECT 1 FROM tasks task WHERE task.thread_id = trunk.id))
BEGIN SELECT RAISE(ABORT,
    'review pass publication lacks its exact standalone subject'); END;

CREATE TRIGGER review_pass_publication_item_insert_v288
BEFORE INSERT ON review_pass_publication_item_v288
WHEN NOT EXISTS (
    SELECT 1
    FROM review_pass_publication_v288 publication
    JOIN review_findings finding ON finding.id = NEW.finding_id
    WHERE publication.id = NEW.publication_id
      AND finding.review_pass_id = publication.review_pass_id
      AND finding.revision = NEW.finding_revision
      AND finding.status IN ('agreed', 'resolved', 'arbitrated')
      AND finding.path IS NEW.path
      AND finding.line IS NEW.line
      AND finding.severity = NEW.severity
      AND finding.body = NEW.body)
BEGIN SELECT RAISE(ABORT,
    'review pass publication item differs from its frozen finding'); END;

CREATE TRIGGER review_pass_publication_identity_v288
BEFORE UPDATE OF id, operation_id, review_pass_id, thread_id, command_id,
        workspace_id, remote_repository_id, head_repository_id,
        remote_pr_number, branch_name, expected_head_sha, review_action,
        finding_ids_json, request_digest, payload_json, payload_digest,
        semantic_attempt, attempt_limit, observation_limit, authorized_at_ms
        ON review_pass_publication_v288
BEGIN SELECT RAISE(ABORT,
    'review pass publication identity is immutable'); END;

CREATE TRIGGER review_pass_publication_transition_v288
BEFORE UPDATE OF status ON review_pass_publication_v288
WHEN NOT (
    (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status IN ('CLAIMED', 'CANCELED', 'ABANDONED'))
    OR (OLD.status = 'CLAIMED' AND NEW.status IN (
        'SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED', 'ABANDONED'))
    OR OLD.status = NEW.status)
BEGIN SELECT RAISE(ABORT,
    'review pass publication transition is invalid'); END;

CREATE TRIGGER review_pass_publication_baseline_v288
BEFORE UPDATE OF recovery_baseline_json ON review_pass_publication_v288
WHEN OLD.recovery_baseline_json IS NOT NULL
  OR NEW.recovery_baseline_json IS NULL
  OR OLD.status <> 'CLAIMED'
BEGIN SELECT RAISE(ABORT,
    'review pass publication recovery baseline is append-only'); END;

CREATE TRIGGER review_pass_publication_observation_v288
BEFORE UPDATE OF observation_started_at_ms, observation_deadline_ms
        ON review_pass_publication_v288
WHEN OLD.observation_started_at_ms IS NOT NULL
  OR OLD.observation_deadline_ms IS NOT NULL
  OR NEW.observation_started_at_ms IS NULL
  OR NEW.observation_deadline_ms <= NEW.observation_started_at_ms
  OR OLD.status <> 'CLAIMED'
BEGIN SELECT RAISE(ABORT,
    'review pass publication observation window is immutable'); END;

CREATE TABLE review_pass_publication_dispatch_v288 (
    publication_id      TEXT NOT NULL PRIMARY KEY
        REFERENCES review_pass_publication_v288(id) ON DELETE CASCADE,
    dispatch_ticket_id  TEXT NOT NULL UNIQUE
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    operation_id        TEXT NOT NULL UNIQUE,
    dispatched_at_ms    INTEGER NOT NULL CHECK (dispatched_at_ms >= 0)
);

CREATE TRIGGER dispatch_ticket_review_pass_publication_v288
BEFORE INSERT ON dispatch_ticket
WHEN NEW.operation_kind = 'PUBLISH_STANDALONE_REVIEW_PASS'
  OR NEW.callback_route = 'STANDALONE_REVIEW_PASS_PUBLICATION_RESULT'
BEGIN
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM review_pass_publication_v288 publication
        WHERE publication.operation_id = NEW.operation_id
          AND publication.thread_id = NEW.owner_id
          AND publication.thread_id = NEW.trunk_id
          AND publication.workspace_id = NEW.workspace_id
          AND publication.semantic_attempt = NEW.attempt
          AND publication.expected_head_sha = NEW.expected_head_sha
          AND NEW.operation_kind = 'PUBLISH_STANDALONE_REVIEW_PASS'
          AND NEW.callback_route = 'STANDALONE_REVIEW_PASS_PUBLICATION_RESULT'
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
        'review pass publication dispatch differs from authorization') END;
END;

CREATE TRIGGER review_pass_publication_dispatch_insert_v288
BEFORE INSERT ON review_pass_publication_dispatch_v288
WHEN NOT EXISTS (
    SELECT 1
    FROM review_pass_publication_v288 publication
    JOIN dispatch_ticket ticket ON ticket.id = NEW.dispatch_ticket_id
    WHERE publication.id = NEW.publication_id
      AND publication.operation_id = NEW.operation_id
      AND ticket.operation_id = NEW.operation_id
      AND ticket.owner_kind = 'TRUNK'
      AND ticket.owner_id = publication.thread_id
      AND ticket.trunk_id = publication.thread_id
      AND ticket.operation_kind = 'PUBLISH_STANDALONE_REVIEW_PASS'
      AND ticket.callback_route = 'STANDALONE_REVIEW_PASS_PUBLICATION_RESULT'
      AND json_array_length(publication.finding_ids_json) = (
          SELECT COUNT(*) FROM review_pass_publication_item_v288 item
          WHERE item.publication_id = publication.id))
BEGIN SELECT RAISE(ABORT,
    'review pass publication dispatch map is not exact'); END;

CREATE TRIGGER review_pass_publication_finalize_v288
BEFORE UPDATE OF finalized_at_ms, posted_count
        ON review_pass_publication_v288
WHEN NEW.finalized_at_ms IS NOT NULL
  AND (OLD.finalized_at_ms IS NOT NULL
    OR NEW.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
    OR NOT EXISTS (
        SELECT 1
        FROM review_pass_publication_dispatch_v288 dispatch
        JOIN dispatch_ticket ticket ON ticket.id = dispatch.dispatch_ticket_id
        WHERE dispatch.publication_id = OLD.id
          AND dispatch.operation_id = OLD.operation_id
          AND ticket.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
          AND ticket.delivery_acceptance = 'ACCEPTED'))
BEGIN SELECT RAISE(ABORT,
    'review pass publication finalization requires accepted delivery'); END;

CREATE TRIGGER review_pass_publication_finding_freeze_v288
BEFORE UPDATE ON review_findings
WHEN EXISTS (
    SELECT 1
    FROM review_pass_publication_item_v288 item
    JOIN review_pass_publication_v288 publication
      ON publication.id = item.publication_id
    WHERE item.finding_id = OLD.id
      AND publication.finalized_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT,
    'a finding in an authorized review publication is frozen'); END;

CREATE TRIGGER review_pass_publication_pass_freeze_v288
BEFORE UPDATE OF thread_id, repo_full_name, pr_number, head_sha, phase,
        verdict, host_kind, host_id, base_repository_id,
        head_repository_id, head_ref ON review_passes
WHEN EXISTS (
    SELECT 1 FROM review_pass_publication_v288 publication
    WHERE publication.review_pass_id = OLD.id
      AND publication.finalized_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT,
    'an authorized standalone review pass is frozen'); END;

CREATE TRIGGER review_pass_published_requires_v288
BEFORE UPDATE OF phase ON review_passes
WHEN NEW.phase = 'published' AND OLD.phase <> 'published'
  AND NEW.host_kind = 'THREAD'
  AND NOT EXISTS (
      SELECT 1 FROM review_pass_publication_v288 publication
      WHERE publication.review_pass_id = OLD.id
        AND publication.status = 'SUCCEEDED'
        AND publication.finalized_at_ms IS NOT NULL)
BEGIN SELECT RAISE(ABORT,
    'standalone review publication requires accepted durable evidence'); END;

CREATE TRIGGER review_pass_publication_item_update_v288
BEFORE UPDATE ON review_pass_publication_item_v288
BEGIN SELECT RAISE(ABORT,
    'review pass publication item is immutable'); END;

CREATE TRIGGER review_pass_publication_item_delete_v288
BEFORE DELETE ON review_pass_publication_item_v288
WHEN NOT EXISTS (
    SELECT 1
    FROM review_pass_publication_v288 publication
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = publication.thread_id
    WHERE publication.id = OLD.publication_id)
BEGIN SELECT RAISE(ABORT,
    'review pass publication item cannot be deleted'); END;

CREATE TRIGGER review_pass_publication_dispatch_update_v288
BEFORE UPDATE ON review_pass_publication_dispatch_v288
BEGIN SELECT RAISE(ABORT,
    'review pass publication dispatch is immutable'); END;

CREATE TRIGGER review_pass_publication_dispatch_delete_v288
BEFORE DELETE ON review_pass_publication_dispatch_v288
WHEN NOT EXISTS (
    SELECT 1
    FROM review_pass_publication_v288 publication
    JOIN v2_trunk_purge_authorization_v269 authorization
      ON authorization.trunk_id = publication.thread_id
    WHERE publication.id = OLD.publication_id)
BEGIN SELECT RAISE(ABORT,
    'review pass publication dispatch cannot be deleted'); END;

CREATE TRIGGER review_pass_publication_delete_v288
BEFORE DELETE ON review_pass_publication_v288
WHEN NOT EXISTS (
    SELECT 1 FROM v2_trunk_purge_authorization_v269 authorization
    WHERE authorization.trunk_id = OLD.thread_id)
BEGIN SELECT RAISE(ABORT,
    'review pass publication history is immutable'); END;

CREATE TRIGGER v2_trunk_purge_review_pass_publication_guard_v288
BEFORE INSERT ON v2_trunk_purge_authorization_v269
WHEN EXISTS (
    SELECT 1
    FROM review_pass_publication_v288 publication
    LEFT JOIN review_pass_publication_dispatch_v288 dispatch
      ON dispatch.publication_id = publication.id
    LEFT JOIN dispatch_ticket ticket
      ON ticket.id = dispatch.dispatch_ticket_id
    WHERE publication.thread_id = NEW.trunk_id
      AND (publication.status NOT IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
        OR publication.finalized_at_ms IS NULL
        OR COALESCE(ticket.status, '') NOT IN (
            'SUCCEEDED', 'FAILED', 'CANCELED')
        OR COALESCE(ticket.delivery_acceptance, '') <> 'ACCEPTED'))
BEGIN SELECT RAISE(ABORT,
    'V2 Trunk purge cannot race a standalone review publication'); END;
