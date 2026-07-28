-- Typed remote inbox, feedback authorization, readiness, and merge evidence.
-- Remote observations remain facts. No record in this migration grants an
-- agent or automation authority to publish reviewer-visible text.

ALTER TABLE remote_pr_snapshot ADD COLUMN merge_queue_capability TEXT NOT NULL
    DEFAULT 'UNKNOWN' CHECK (merge_queue_capability IN (
        'UNKNOWN', 'UNSUPPORTED', 'SUPPORTED'));

CREATE TABLE remote_inbox_item (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id        TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    remote_pr_snapshot_id       TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    kind                        TEXT    NOT NULL CHECK (kind IN (
        'INLINE_COMMENT', 'TOP_LEVEL_COMMENT', 'REVIEW_BODY',
        'REVIEW_VERDICT', 'REQUESTED_REVIEW', 'THREAD_RESOLVED',
        'THREAD_REOPENED', 'HEAD_CHANGED')),
    external_key                TEXT    NOT NULL,
    external_revision           INTEGER NOT NULL CHECK (external_revision > 0),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    actor_login                 TEXT,
    provenance                  TEXT    NOT NULL CHECK (provenance IN (
        'EXTERNAL', 'OWN_REPLY')),
    ignored                     INTEGER NOT NULL CHECK (ignored IN (0, 1)),
    thread_id                   TEXT,
    comment_id                  TEXT,
    review_id                   TEXT,
    requested_reviewer          TEXT,
    body                        TEXT,
    body_digest                 TEXT,
    verdict                     TEXT CHECK (verdict IN (
        'APPROVED', 'CHANGES_REQUESTED', 'COMMENTED', 'DISMISSED')),
    previous_head_sha           TEXT,
    new_head_sha                TEXT,
    observed_at_ms              INTEGER NOT NULL,
    raw_evidence                TEXT,
    UNIQUE (remote_pr_binding_id, external_key, external_revision),
    UNIQUE (id, remote_development_stage_id, head_sha, base_sha),
    CHECK ((provenance = 'OWN_REPLY' AND ignored = 1)
        OR (provenance = 'EXTERNAL' AND ignored = 0)),
    CHECK ((body IS NULL AND body_digest IS NULL)
        OR (body IS NOT NULL AND body_digest IS NOT NULL)),
    CHECK (
        (kind = 'INLINE_COMMENT' AND thread_id IS NOT NULL
            AND comment_id IS NOT NULL AND body IS NOT NULL
            AND review_id IS NULL AND requested_reviewer IS NULL
            AND verdict IS NULL AND previous_head_sha IS NULL
            AND new_head_sha IS NULL)
        OR (kind = 'TOP_LEVEL_COMMENT' AND thread_id IS NULL
            AND comment_id IS NOT NULL AND body IS NOT NULL
            AND review_id IS NULL AND requested_reviewer IS NULL
            AND verdict IS NULL AND previous_head_sha IS NULL
            AND new_head_sha IS NULL)
        OR (kind = 'REVIEW_BODY' AND review_id IS NOT NULL
            AND thread_id IS NULL
            AND body IS NOT NULL AND comment_id IS NULL
            AND requested_reviewer IS NULL AND verdict IS NULL
            AND previous_head_sha IS NULL AND new_head_sha IS NULL)
        OR (kind = 'REVIEW_VERDICT' AND review_id IS NOT NULL
            AND thread_id IS NULL
            AND verdict IS NOT NULL AND comment_id IS NULL
            AND requested_reviewer IS NULL
            AND previous_head_sha IS NULL AND new_head_sha IS NULL)
        OR (kind = 'REQUESTED_REVIEW' AND requested_reviewer IS NOT NULL
            AND thread_id IS NULL AND comment_id IS NULL AND review_id IS NULL
            AND body IS NULL AND verdict IS NULL
            AND previous_head_sha IS NULL AND new_head_sha IS NULL)
        OR (kind IN ('THREAD_RESOLVED', 'THREAD_REOPENED')
            AND thread_id IS NOT NULL AND comment_id IS NULL
            AND review_id IS NULL AND body IS NULL
            AND requested_reviewer IS NULL AND verdict IS NULL
            AND previous_head_sha IS NULL AND new_head_sha IS NULL)
        OR (kind = 'HEAD_CHANGED' AND previous_head_sha IS NOT NULL
            AND new_head_sha IS NOT NULL
            AND previous_head_sha <> new_head_sha
            AND head_sha = new_head_sha AND thread_id IS NULL
            AND comment_id IS NULL AND review_id IS NULL
            AND requested_reviewer IS NULL AND body IS NULL
            AND verdict IS NULL))
);

CREATE TRIGGER remote_inbox_item_insert
BEFORE INSERT ON remote_inbox_item
WHEN NOT EXISTS (
    SELECT 1 FROM remote_pr_snapshot snapshot
    JOIN remote_development_stage remote
      ON remote.stage_id = snapshot.remote_development_stage_id
    JOIN tasks task ON task.id = remote.task_id
    WHERE snapshot.id = NEW.remote_pr_snapshot_id
      AND snapshot.remote_development_stage_id =
            NEW.remote_development_stage_id
      AND snapshot.task_id = NEW.task_id
      AND snapshot.stage_generation = NEW.stage_generation
      AND snapshot.remote_pr_binding_id = NEW.remote_pr_binding_id
      AND snapshot.head_sha = NEW.head_sha
      AND snapshot.base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch)
BEGIN SELECT RAISE(ABORT, 'Remote inbox item subject fence is invalid'); END;

CREATE TRIGGER remote_inbox_item_immutable
BEFORE UPDATE ON remote_inbox_item
BEGIN SELECT RAISE(ABORT, 'Remote inbox item is immutable'); END;

CREATE TABLE remote_feedback_batch (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id        TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    source_snapshot_id          TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    sequence                    INTEGER NOT NULL CHECK (sequence > 0),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'BUILDING', 'FROZEN', 'ADDRESSING', 'AWAITING_APPROVAL',
        'AUTHORIZED', 'APPLYING', 'COMPLETED', 'SUPERSEDED')),
    brain_review_required       INTEGER NOT NULL CHECK (brain_review_required IN (0, 1)),
    item_count                  INTEGER NOT NULL CHECK (item_count > 0),
    content_digest              TEXT,
    result_head_sha             TEXT,
    result_snapshot_id          TEXT REFERENCES remote_pr_snapshot(id),
    created_at_ms               INTEGER NOT NULL,
    frozen_at_ms                INTEGER,
    completed_at_ms             INTEGER,
    CHECK ((status = 'BUILDING' AND content_digest IS NULL
            AND frozen_at_ms IS NULL)
        OR (status <> 'BUILDING' AND content_digest IS NOT NULL
            AND frozen_at_ms IS NOT NULL)),
    CHECK ((status IN ('COMPLETED', 'SUPERSEDED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK ((result_head_sha IS NULL AND result_snapshot_id IS NULL)
        OR (result_head_sha IS NOT NULL AND result_snapshot_id IS NOT NULL)),
    UNIQUE (remote_development_stage_id, sequence),
    UNIQUE (id, remote_development_stage_id, head_sha, base_sha)
);

CREATE TRIGGER remote_feedback_batch_insert
BEFORE INSERT ON remote_feedback_batch
BEGIN
    SELECT CASE
        WHEN NEW.sequence <> COALESCE((
            SELECT MAX(batch.sequence) + 1 FROM remote_feedback_batch batch
            WHERE batch.remote_development_stage_id =
                NEW.remote_development_stage_id), 1)
            THEN RAISE(ABORT, 'Remote feedback batch sequence must be next')
        WHEN NOT EXISTS (
            SELECT 1 FROM remote_development_stage remote
            JOIN tasks task ON task.id = remote.task_id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.remote_development_stage_id = remote.stage_id
            WHERE remote.stage_id = NEW.remote_development_stage_id
              AND remote.task_id = NEW.task_id
              AND remote.generation = NEW.stage_generation
              AND remote.remote_pr_binding_id = NEW.remote_pr_binding_id
              AND remote.current_head_sha = NEW.head_sha
              AND remote.current_base_sha = NEW.base_sha
              AND remote.accepted_snapshot_id = snapshot.id
              AND task.epoch = NEW.task_epoch
              AND snapshot.id = NEW.source_snapshot_id
              AND snapshot.head_sha = NEW.head_sha
              AND snapshot.base_sha = NEW.base_sha)
            THEN RAISE(ABORT, 'Remote feedback batch is not for the current head')
    END;
END;

CREATE TRIGGER remote_feedback_batch_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_binding_id, source_snapshot_id, sequence,
        head_sha, base_sha, brain_review_required, item_count, created_at_ms
        ON remote_feedback_batch
WHEN NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.remote_pr_binding_id IS NOT OLD.remote_pr_binding_id
  OR NEW.source_snapshot_id IS NOT OLD.source_snapshot_id
  OR NEW.sequence IS NOT OLD.sequence
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.brain_review_required IS NOT OLD.brain_review_required
  OR NEW.item_count IS NOT OLD.item_count
  OR NEW.created_at_ms IS NOT OLD.created_at_ms
BEGIN SELECT RAISE(ABORT, 'Remote feedback batch identity is immutable'); END;

CREATE TRIGGER remote_feedback_batch_result_fence
BEFORE UPDATE OF result_head_sha, result_snapshot_id ON remote_feedback_batch
WHEN NEW.status <> 'COMPLETED'
BEGIN SELECT RAISE(ABORT, 'Remote feedback result may only be recorded at completion'); END;

CREATE TRIGGER remote_feedback_batch_transition
BEFORE UPDATE OF status ON remote_feedback_batch
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status = 'BUILDING' AND NEW.status = 'FROZEN')
    OR (OLD.status = 'FROZEN'
        AND NEW.status IN ('ADDRESSING', 'AWAITING_APPROVAL', 'SUPERSEDED'))
    OR (OLD.status = 'ADDRESSING'
        AND NEW.status IN ('AWAITING_APPROVAL', 'SUPERSEDED'))
    OR (OLD.status = 'AWAITING_APPROVAL'
        AND NEW.status IN ('AUTHORIZED', 'SUPERSEDED'))
    OR (OLD.status = 'AUTHORIZED'
        AND NEW.status IN ('APPLYING', 'SUPERSEDED'))
    OR (OLD.status = 'APPLYING'
        AND NEW.status IN ('COMPLETED', 'SUPERSEDED')))
BEGIN SELECT RAISE(ABORT, 'Remote feedback batch transition is invalid'); END;

CREATE TRIGGER remote_feedback_batch_freeze_identity
BEFORE UPDATE OF content_digest, frozen_at_ms ON remote_feedback_batch
WHEN OLD.status <> 'BUILDING' OR NEW.status <> 'FROZEN'
BEGIN SELECT RAISE(ABORT, 'Remote feedback batch freeze identity is immutable'); END;

CREATE TRIGGER remote_feedback_batch_terminal_immutable
BEFORE UPDATE ON remote_feedback_batch
WHEN OLD.status IN ('COMPLETED', 'SUPERSEDED')
BEGIN SELECT RAISE(ABORT, 'Terminal remote feedback batch is immutable'); END;

CREATE TABLE remote_feedback_batch_item (
    remote_feedback_batch_id TEXT    NOT NULL
        REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    ordinal                 INTEGER NOT NULL CHECK (ordinal > 0),
    remote_inbox_item_id    TEXT    NOT NULL UNIQUE REFERENCES remote_inbox_item(id),
    external_revision       INTEGER NOT NULL CHECK (external_revision > 0),
    kind                    TEXT    NOT NULL,
    frozen_body             TEXT,
    body_digest             TEXT,
    external_target         TEXT,
    selected_by             TEXT    NOT NULL,
    selected_at_ms          INTEGER NOT NULL,
    PRIMARY KEY (remote_feedback_batch_id, ordinal),
    CHECK ((frozen_body IS NULL AND body_digest IS NULL)
        OR (frozen_body IS NOT NULL AND body_digest IS NOT NULL))
);

CREATE TRIGGER remote_feedback_batch_item_insert
BEFORE INSERT ON remote_feedback_batch_item
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_batch batch
    JOIN remote_inbox_item item ON item.id = NEW.remote_inbox_item_id
    WHERE batch.id = NEW.remote_feedback_batch_id
      AND batch.status = 'BUILDING'
      AND item.remote_development_stage_id =
            batch.remote_development_stage_id
      AND item.task_id = batch.task_id
      AND item.task_epoch = batch.task_epoch
      AND item.stage_generation = batch.stage_generation
      AND item.remote_pr_binding_id = batch.remote_pr_binding_id
      AND item.head_sha = batch.head_sha
      AND item.base_sha = batch.base_sha
      AND item.external_revision = NEW.external_revision
      AND item.kind = NEW.kind
      AND item.kind NOT IN ('HEAD_CHANGED', 'THREAD_RESOLVED')
      AND item.ignored = 0
      AND item.body IS NEW.frozen_body
      AND item.body_digest IS NEW.body_digest)
BEGIN SELECT RAISE(ABORT, 'Remote feedback item is stale, ignored, or outside its batch'); END;

CREATE TRIGGER remote_feedback_batch_item_immutable
BEFORE UPDATE ON remote_feedback_batch_item
BEGIN SELECT RAISE(ABORT, 'Remote feedback batch item is immutable'); END;

CREATE TRIGGER remote_feedback_batch_freeze
BEFORE UPDATE OF status ON remote_feedback_batch
WHEN NEW.status = 'FROZEN'
  AND (OLD.status <> 'BUILDING'
    OR NEW.content_digest IS NULL OR NEW.frozen_at_ms IS NULL
    OR NEW.item_count <> (
        SELECT COUNT(*) FROM remote_feedback_batch_item item
        WHERE item.remote_feedback_batch_id = NEW.id))
BEGIN SELECT RAISE(ABORT, 'Frozen remote feedback batch lacks its exact item set'); END;

CREATE TABLE remote_feedback_validation_evidence (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id   TEXT    NOT NULL UNIQUE
        REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    remote_development_stage_id TEXT   NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    repair_stage_turn_id        TEXT    NOT NULL UNIQUE REFERENCES stage_turn(id),
    validation_pass_id          INTEGER NOT NULL UNIQUE REFERENCES validation_pass(id),
    validation_operation_id     TEXT    NOT NULL UNIQUE,
    validation_attempt          INTEGER NOT NULL CHECK (validation_attempt > 0),
    subject_head_sha            TEXT    NOT NULL,
    proposed_head_sha           TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    code_fingerprint            TEXT    NOT NULL,
    passed                      INTEGER NOT NULL CHECK (passed IN (0, 1)),
    evidence                    TEXT    NOT NULL,
    completed_at_ms             INTEGER NOT NULL
);

CREATE TRIGGER remote_feedback_validation_evidence_insert
BEFORE INSERT ON remote_feedback_validation_evidence
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_batch batch
    JOIN remote_development_stage remote
      ON remote.stage_id = batch.remote_development_stage_id
    JOIN tasks task ON task.id = batch.task_id
    JOIN stage_turn turn ON turn.id = NEW.repair_stage_turn_id
    JOIN validation_pass validation ON validation.id = NEW.validation_pass_id
    WHERE batch.id = NEW.remote_feedback_batch_id
      AND batch.status IN ('ADDRESSING', 'AWAITING_APPROVAL')
      AND batch.remote_development_stage_id = NEW.remote_development_stage_id
      AND batch.task_id = NEW.task_id
      AND batch.task_epoch = NEW.task_epoch
      AND batch.stage_generation = NEW.stage_generation
      AND batch.head_sha = NEW.subject_head_sha
      AND batch.base_sha = NEW.base_sha
      AND remote.current_head_sha = NEW.subject_head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch
      AND turn.stage_id = NEW.remote_development_stage_id
      AND turn.stage_generation = NEW.stage_generation
      AND turn.task_epoch = NEW.task_epoch
      AND turn.purpose = 'ADDRESS_REMOTE_FEEDBACK'
      AND turn.status = 'SUCCEEDED'
      AND turn.expected_code_fingerprint = NEW.code_fingerprint
      AND turn.expected_head_sha = NEW.proposed_head_sha
      AND turn.expected_base_sha = NEW.base_sha
      AND validation.task_id = NEW.task_id
      AND validation.workflow_version = 'V2'
      AND validation.task_epoch = NEW.task_epoch
      AND validation.stage_id = NEW.remote_development_stage_id
      AND validation.stage_generation = NEW.stage_generation
      AND validation.operation_id = NEW.validation_operation_id
      AND validation.semantic_attempt = NEW.validation_attempt
      AND validation.code_fingerprint = NEW.code_fingerprint
      AND validation.expected_head_sha = NEW.proposed_head_sha
      AND validation.expected_base_sha = NEW.base_sha
      AND validation.ended_at_ms = NEW.completed_at_ms
      AND validation.passed = NEW.passed)
BEGIN SELECT RAISE(ABORT, 'Remote feedback validation lacks exact succeeded StageTurn evidence'); END;

CREATE TRIGGER remote_feedback_validation_evidence_immutable
BEFORE UPDATE ON remote_feedback_validation_evidence
BEGIN SELECT RAISE(ABORT, 'Remote feedback validation evidence is immutable'); END;

CREATE TABLE remote_feedback_brain_review_evidence (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id   TEXT    NOT NULL UNIQUE
        REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    validation_evidence_id      TEXT    NOT NULL UNIQUE
        REFERENCES remote_feedback_validation_evidence(id),
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    task_turn_id                TEXT    NOT NULL UNIQUE REFERENCES task_turn(id),
    proposed_head_sha           TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    code_fingerprint            TEXT    NOT NULL,
    verdict                     TEXT    NOT NULL CHECK (verdict IN (
        'APPROVED', 'CHANGES_REQUESTED', 'BLOCKED')),
    unresolved_finding_count    INTEGER NOT NULL CHECK (unresolved_finding_count >= 0),
    evidence                    TEXT    NOT NULL,
    completed_at_ms             INTEGER NOT NULL,
    CHECK (verdict <> 'APPROVED' OR unresolved_finding_count = 0)
);

CREATE TRIGGER remote_feedback_brain_review_evidence_insert
BEFORE INSERT ON remote_feedback_brain_review_evidence
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_validation_evidence validation
    JOIN remote_feedback_batch batch
      ON batch.id = validation.remote_feedback_batch_id
    JOIN task_turn turn ON turn.id = NEW.task_turn_id
    WHERE validation.id = NEW.validation_evidence_id
      AND validation.remote_feedback_batch_id = NEW.remote_feedback_batch_id
      AND validation.passed = 1
      AND validation.task_id = NEW.task_id
      AND validation.task_epoch = NEW.task_epoch
      AND validation.remote_development_stage_id = NEW.remote_development_stage_id
      AND validation.stage_generation = NEW.stage_generation
      AND validation.proposed_head_sha = NEW.proposed_head_sha
      AND validation.base_sha = NEW.base_sha
      AND validation.code_fingerprint = NEW.code_fingerprint
      AND batch.status IN ('ADDRESSING', 'AWAITING_APPROVAL')
      AND turn.task_id = NEW.task_id
      AND turn.task_epoch = NEW.task_epoch
      AND turn.trigger_stage_id = NEW.remote_development_stage_id
      AND turn.trigger_stage_generation = NEW.stage_generation
      AND turn.purpose = 'REMOTE_FEEDBACK_BRAIN_REVIEW'
      AND turn.status = 'SUCCEEDED'
      AND turn.expected_code_fingerprint = NEW.code_fingerprint
      AND turn.expected_head_sha = NEW.proposed_head_sha
      AND turn.expected_base_sha = NEW.base_sha)
BEGIN SELECT RAISE(ABORT, 'Remote feedback Brain evidence lacks exact green validation and TaskTurn'); END;

CREATE TRIGGER remote_feedback_brain_review_evidence_immutable
BEFORE UPDATE ON remote_feedback_brain_review_evidence
BEGIN SELECT RAISE(ABORT, 'Remote feedback Brain review evidence is immutable'); END;

CREATE TABLE remote_feedback_authorization (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_batch_id   TEXT    NOT NULL UNIQUE
        REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    validation_evidence_id      TEXT    NOT NULL UNIQUE
        REFERENCES remote_feedback_validation_evidence(id),
    brain_review_evidence_id    TEXT    UNIQUE
        REFERENCES remote_feedback_brain_review_evidence(id),
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    item_count                  INTEGER NOT NULL CHECK (item_count > 0),
    content_digest              TEXT    NOT NULL,
    effect_count                INTEGER NOT NULL CHECK (effect_count > 0),
    authority_kind              TEXT    NOT NULL CHECK (authority_kind = 'USER_ACTION'),
    authorized_by               TEXT    NOT NULL,
    reason                      TEXT,
    authorized_at_ms            INTEGER NOT NULL,
    UNIQUE (id, remote_feedback_batch_id)
);

CREATE TRIGGER remote_feedback_authorization_insert
BEFORE INSERT ON remote_feedback_authorization
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_batch batch
    JOIN remote_feedback_validation_evidence validation
      ON validation.remote_feedback_batch_id = batch.id
    JOIN remote_development_stage remote
      ON remote.stage_id = batch.remote_development_stage_id
    JOIN tasks task ON task.id = batch.task_id
    WHERE batch.id = NEW.remote_feedback_batch_id
      AND batch.remote_development_stage_id =
            NEW.remote_development_stage_id
      AND batch.task_id = NEW.task_id
      AND batch.task_epoch = NEW.task_epoch
      AND batch.stage_generation = NEW.stage_generation
      AND batch.head_sha = NEW.head_sha
      AND batch.base_sha = NEW.base_sha
      AND batch.item_count = NEW.item_count
      AND batch.content_digest = NEW.content_digest
      AND batch.status = 'AWAITING_APPROVAL'
      AND validation.id = NEW.validation_evidence_id
      AND validation.passed = 1
      AND validation.task_id = NEW.task_id
      AND validation.task_epoch = NEW.task_epoch
      AND validation.remote_development_stage_id = NEW.remote_development_stage_id
      AND validation.stage_generation = NEW.stage_generation
      AND validation.subject_head_sha = NEW.head_sha
      AND validation.base_sha = NEW.base_sha
      AND ((batch.brain_review_required = 0
            AND NEW.brain_review_evidence_id IS NULL)
        OR EXISTS (
            SELECT 1 FROM remote_feedback_brain_review_evidence brain
            WHERE brain.id = NEW.brain_review_evidence_id
              AND brain.remote_feedback_batch_id = batch.id
              AND brain.validation_evidence_id = validation.id
              AND brain.verdict = 'APPROVED'))
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch)
BEGIN SELECT RAISE(ABORT, 'Remote feedback authorization is stale or incomplete'); END;

CREATE TRIGGER remote_feedback_authorization_immutable
BEFORE UPDATE ON remote_feedback_authorization
BEGIN SELECT RAISE(ABORT, 'Remote feedback authorization is immutable'); END;

CREATE TABLE remote_feedback_effect_step (
    id                           TEXT    NOT NULL PRIMARY KEY,
    remote_feedback_authorization_id TEXT NOT NULL
        REFERENCES remote_feedback_authorization(id) ON DELETE CASCADE,
    remote_feedback_batch_id     TEXT    NOT NULL
        REFERENCES remote_feedback_batch(id) ON DELETE CASCADE,
    ordinal                      INTEGER NOT NULL CHECK (ordinal > 0),
    kind                         TEXT    NOT NULL CHECK (kind IN (
        'POST_INLINE_REPLY', 'POST_TOP_LEVEL_REPLY', 'SUBMIT_REVIEW',
        'REQUEST_REVIEWER', 'POST_MAINTAINER_NUDGE',
        'RESOLVE_THREAD', 'PUSH_COMMITS')),
    remote_inbox_item_id         TEXT REFERENCES remote_inbox_item(id),
    external_target              TEXT,
    review_action                TEXT CHECK (review_action IN (
        'COMMENT', 'APPROVE', 'REQUEST_CHANGES')),
    payload_digest               TEXT    NOT NULL,
    idempotency_key              TEXT    NOT NULL UNIQUE,
    status                       TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'INDETERMINATE')),
    attempt_count                INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit                INTEGER NOT NULL CHECK (attempt_limit > 0),
    claim_mode                   TEXT CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    claim_owner                  TEXT,
    claimed_at_ms                INTEGER,
    lease_until_ms               INTEGER,
    external_effect_id           TEXT,
    evidence                     TEXT,
    last_error                   TEXT,
    completed_at_ms              INTEGER,
    UNIQUE (remote_feedback_batch_id, ordinal),
    CHECK ((kind IN ('PUSH_COMMITS', 'SUBMIT_REVIEW', 'REQUEST_REVIEWER',
            'POST_MAINTAINER_NUDGE')
            AND remote_inbox_item_id IS NULL)
        OR (kind NOT IN ('PUSH_COMMITS', 'SUBMIT_REVIEW', 'REQUEST_REVIEWER',
            'POST_MAINTAINER_NUDGE')
            AND remote_inbox_item_id IS NOT NULL)),
    CHECK (kind NOT IN ('REQUEST_REVIEWER', 'POST_MAINTAINER_NUDGE')
        OR external_target IS NOT NULL),
    CHECK ((kind = 'SUBMIT_REVIEW') = (review_action IS NOT NULL)),
    CHECK ((status = 'CLAIMED') = (claim_mode IS NOT NULL
        AND claim_owner IS NOT NULL AND claimed_at_ms IS NOT NULL
        AND lease_until_ms IS NOT NULL)),
    CHECK (status <> 'CLAIMED' OR lease_until_ms > claimed_at_ms),
    CHECK (attempt_count <= attempt_limit),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED'
        OR (external_effect_id IS NOT NULL AND evidence IS NOT NULL))
);

CREATE TRIGGER remote_feedback_effect_step_insert
BEFORE INSERT ON remote_feedback_effect_step
WHEN NOT EXISTS (
    SELECT 1 FROM remote_feedback_authorization authorization
    JOIN remote_feedback_batch batch
      ON batch.id = authorization.remote_feedback_batch_id
    WHERE authorization.id = NEW.remote_feedback_authorization_id
      AND authorization.remote_feedback_batch_id =
            NEW.remote_feedback_batch_id
      AND NEW.status = 'REQUESTED'
      AND NEW.attempt_count = 0
      AND NEW.ordinal <= authorization.effect_count
      AND batch.status = 'AWAITING_APPROVAL'
      AND (NEW.kind IN ('PUSH_COMMITS', 'SUBMIT_REVIEW', 'REQUEST_REVIEWER',
            'POST_MAINTAINER_NUDGE')
        OR EXISTS (
          SELECT 1 FROM remote_feedback_batch_item batch_item
          JOIN remote_inbox_item inbox
            ON inbox.id = batch_item.remote_inbox_item_id
          WHERE batch_item.remote_feedback_batch_id = batch.id
            AND batch_item.remote_inbox_item_id = NEW.remote_inbox_item_id
            AND ((NEW.kind = 'POST_INLINE_REPLY'
                    AND inbox.kind = 'INLINE_COMMENT')
              OR (NEW.kind = 'POST_TOP_LEVEL_REPLY'
                    AND inbox.kind IN ('TOP_LEVEL_COMMENT', 'REVIEW_BODY',
                        'REVIEW_VERDICT'))
              OR (NEW.kind = 'RESOLVE_THREAD'
                    AND inbox.kind IN ('INLINE_COMMENT', 'THREAD_REOPENED'))))))
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect is outside its authorization'); END;

CREATE TRIGGER remote_feedback_effect_step_identity_immutable
BEFORE UPDATE OF remote_feedback_authorization_id, remote_feedback_batch_id,
        ordinal, kind, remote_inbox_item_id, external_target, payload_digest,
        review_action, idempotency_key, attempt_limit ON remote_feedback_effect_step
WHEN NEW.remote_feedback_authorization_id IS NOT OLD.remote_feedback_authorization_id
  OR NEW.remote_feedback_batch_id IS NOT OLD.remote_feedback_batch_id
  OR NEW.ordinal IS NOT OLD.ordinal
  OR NEW.kind IS NOT OLD.kind
  OR NEW.remote_inbox_item_id IS NOT OLD.remote_inbox_item_id
  OR NEW.external_target IS NOT OLD.external_target
  OR NEW.review_action IS NOT OLD.review_action
  OR NEW.payload_digest IS NOT OLD.payload_digest
  OR NEW.idempotency_key IS NOT OLD.idempotency_key
  OR NEW.attempt_limit IS NOT OLD.attempt_limit
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect identity is immutable'); END;

CREATE TRIGGER remote_feedback_effect_step_claim
BEFORE UPDATE OF status ON remote_feedback_effect_step
WHEN NEW.status = 'CLAIMED'
  AND (NEW.attempt_count <> OLD.attempt_count + 1
    OR OLD.status NOT IN ('REQUESTED', 'CLAIMED', 'FAILED', 'INDETERMINATE')
    OR (OLD.status = 'CLAIMED'
        AND (NEW.claim_mode <> 'PROBE'
          OR NEW.claimed_at_ms < OLD.lease_until_ms))
    OR (OLD.status = 'INDETERMINATE' AND NEW.claim_mode <> 'PROBE')
    OR (OLD.status NOT IN ('CLAIMED', 'INDETERMINATE')
        AND NEW.claim_mode <> 'EXECUTE')
    OR NEW.external_effect_id IS NOT NULL OR NEW.evidence IS NOT NULL
    OR NEW.last_error IS NOT NULL OR NEW.completed_at_ms IS NOT NULL
    OR NOT EXISTS (
        SELECT 1 FROM remote_feedback_authorization authorization
        JOIN remote_feedback_batch batch
          ON batch.id = authorization.remote_feedback_batch_id
        JOIN remote_development_stage remote
          ON remote.stage_id = batch.remote_development_stage_id
        JOIN tasks task ON task.id = batch.task_id
        WHERE authorization.id = NEW.remote_feedback_authorization_id
          AND authorization.remote_feedback_batch_id = NEW.remote_feedback_batch_id
          AND batch.status IN ('AUTHORIZED', 'APPLYING')
          AND remote.current_head_sha = authorization.head_sha
          AND remote.current_base_sha = authorization.base_sha
          AND task.epoch = authorization.task_epoch)
    OR EXISTS (
        SELECT 1 FROM remote_feedback_effect_step previous
        WHERE previous.remote_feedback_batch_id = NEW.remote_feedback_batch_id
          AND previous.ordinal < NEW.ordinal
          AND previous.status <> 'SUCCEEDED'))
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect claim is unordered or unsafe'); END;

CREATE TRIGGER remote_feedback_effect_step_transition
BEFORE UPDATE OF status ON remote_feedback_effect_step
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status = 'CLAIMED')
    OR (OLD.status = 'CLAIMED'
        AND NEW.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')))
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect transition is invalid'); END;

CREATE TRIGGER remote_feedback_effect_step_attempt
BEFORE UPDATE OF attempt_count ON remote_feedback_effect_step
WHEN NEW.status <> 'CLAIMED'
  OR NEW.attempt_count <> OLD.attempt_count + 1
BEGIN SELECT RAISE(ABORT, 'Remote feedback effect attempt must be an exact claim'); END;

CREATE TRIGGER remote_feedback_effect_step_claim_fields
BEFORE UPDATE OF claim_mode, claim_owner, claimed_at_ms, lease_until_ms
        ON remote_feedback_effect_step
WHEN NOT ((NEW.status = 'CLAIMED'
            AND NEW.attempt_count = OLD.attempt_count + 1)
        OR (OLD.status = 'CLAIMED' AND NEW.status <> 'CLAIMED'
            AND NEW.attempt_count = OLD.attempt_count
            AND NEW.claim_mode IS NULL AND NEW.claim_owner IS NULL
            AND NEW.claimed_at_ms IS NULL AND NEW.lease_until_ms IS NULL))
BEGIN SELECT RAISE(ABORT, 'Remote feedback claim fields change only with an attempt boundary'); END;

CREATE TRIGGER remote_feedback_effect_step_result_fields
BEFORE UPDATE OF external_effect_id, evidence, last_error, completed_at_ms
        ON remote_feedback_effect_step
WHEN (NEW.external_effect_id IS NOT OLD.external_effect_id
    OR NEW.evidence IS NOT OLD.evidence
    OR NEW.last_error IS NOT OLD.last_error
    OR NEW.completed_at_ms IS NOT OLD.completed_at_ms)
  AND NEW.status IS OLD.status
  AND NOT (NEW.status = 'CLAIMED'
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.external_effect_id IS NULL AND NEW.evidence IS NULL
    AND NEW.last_error IS NULL AND NEW.completed_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT, 'Remote feedback result changes only at a result or claim boundary'); END;

CREATE TRIGGER remote_feedback_effect_step_terminal_immutable
BEFORE UPDATE ON remote_feedback_effect_step
WHEN OLD.status = 'SUCCEEDED'
BEGIN SELECT RAISE(ABORT, 'Succeeded remote feedback effect is immutable'); END;

CREATE TRIGGER remote_feedback_batch_authorized
BEFORE UPDATE OF status ON remote_feedback_batch
WHEN NEW.status = 'AUTHORIZED'
  AND (NOT EXISTS (
        SELECT 1 FROM remote_feedback_authorization authorization
        WHERE authorization.remote_feedback_batch_id = NEW.id
          AND authorization.item_count = NEW.item_count
          AND authorization.content_digest = NEW.content_digest
          AND authorization.effect_count = (
              SELECT COUNT(*) FROM remote_feedback_effect_step step
              WHERE step.remote_feedback_batch_id = NEW.id))
    OR EXISTS (
        SELECT 1 FROM remote_feedback_effect_step step
        WHERE step.remote_feedback_batch_id = NEW.id
          AND step.status <> 'REQUESTED')
    OR (SELECT COUNT(*) FROM remote_feedback_effect_step step
        WHERE step.remote_feedback_batch_id = NEW.id
          AND step.kind = 'PUSH_COMMITS') > 1
    OR EXISTS (
        SELECT 1 FROM remote_feedback_effect_step step
        WHERE step.remote_feedback_batch_id = NEW.id
          AND step.kind = 'PUSH_COMMITS'
          AND step.ordinal <> (
              SELECT authorization.effect_count
              FROM remote_feedback_authorization authorization
              WHERE authorization.remote_feedback_batch_id = NEW.id)))
BEGIN SELECT RAISE(ABORT, 'Authorized remote batch lacks its complete immutable effect set'); END;

CREATE TRIGGER remote_feedback_batch_complete
BEFORE UPDATE OF status ON remote_feedback_batch
WHEN NEW.status = 'COMPLETED'
  AND (OLD.status <> 'APPLYING'
    OR EXISTS (
        SELECT 1 FROM remote_feedback_effect_step step
        WHERE step.remote_feedback_batch_id = NEW.id
          AND step.status <> 'SUCCEEDED')
    OR (EXISTS (
            SELECT 1 FROM remote_feedback_effect_step step
            WHERE step.remote_feedback_batch_id = NEW.id
              AND step.kind = 'PUSH_COMMITS')
        AND (NEW.result_head_sha IS NULL
          OR NEW.result_head_sha = NEW.head_sha
          OR NOT EXISTS (
              SELECT 1 FROM remote_feedback_effect_step push
              JOIN remote_pr_snapshot snapshot
                ON snapshot.id = NEW.result_snapshot_id
              JOIN remote_development_stage remote
                ON remote.stage_id = snapshot.remote_development_stage_id
              WHERE push.remote_feedback_batch_id = NEW.id
                AND push.kind = 'PUSH_COMMITS'
                AND push.status = 'SUCCEEDED'
                AND push.external_effect_id = NEW.result_head_sha
                AND snapshot.remote_development_stage_id =
                    NEW.remote_development_stage_id
                AND snapshot.task_id = NEW.task_id
                AND snapshot.stage_generation = NEW.stage_generation
                AND snapshot.remote_pr_binding_id = NEW.remote_pr_binding_id
                AND snapshot.head_sha = NEW.result_head_sha
                AND snapshot.base_sha = NEW.base_sha
                AND remote.accepted_snapshot_id = snapshot.id
                AND remote.current_head_sha = NEW.result_head_sha
                AND remote.current_base_sha = NEW.base_sha)))
    OR (NOT EXISTS (
            SELECT 1 FROM remote_feedback_effect_step step
            WHERE step.remote_feedback_batch_id = NEW.id
              AND step.kind = 'PUSH_COMMITS')
        AND (NEW.result_head_sha IS NOT NULL OR NEW.result_snapshot_id IS NOT NULL)))
BEGIN SELECT RAISE(ABORT, 'Remote feedback batch cannot complete with partial effects'); END;

CREATE TABLE task_automation_policy (
    id                          TEXT    NOT NULL PRIMARY KEY,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    revision                    INTEGER NOT NULL CHECK (revision > 0),
    source                      TEXT    NOT NULL,
    auto_approve                INTEGER NOT NULL CHECK (auto_approve IN (0, 1)),
    auto_merge                  INTEGER NOT NULL CHECK (auto_merge IN (0, 1)),
    keep_draft                  INTEGER NOT NULL CHECK (keep_draft IN (0, 1)),
    minimum_write_approvals     INTEGER NOT NULL CHECK (minimum_write_approvals >= 0),
    max_merge_queue_reenqueues  INTEGER NOT NULL CHECK (max_merge_queue_reenqueues >= 0),
    require_low_risk            INTEGER NOT NULL CHECK (require_low_risk IN (0, 1)),
    require_small_effort        INTEGER NOT NULL CHECK (require_small_effort IN (0, 1)),
    stewardship_exception       INTEGER NOT NULL CHECK (stewardship_exception IN (0, 1)),
    created_by                  TEXT    NOT NULL,
    created_at_ms               INTEGER NOT NULL,
    UNIQUE (task_id, revision),
    CHECK (auto_merge = 0 OR auto_approve = 1),
    CHECK (stewardship_exception = 0
        OR (auto_approve = 0 AND auto_merge = 0))
);

CREATE TRIGGER task_automation_policy_insert
BEFORE INSERT ON task_automation_policy
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks task
            WHERE task.id = NEW.task_id AND task.workflow_version = 'V2')
            THEN RAISE(ABORT, 'Automation policy requires V2 Task')
        WHEN NEW.revision <> COALESCE((
            SELECT MAX(policy.revision) + 1
            FROM task_automation_policy policy
            WHERE policy.task_id = NEW.task_id), 1)
            THEN RAISE(ABORT, 'Automation policy revision must be next')
    END;
END;

CREATE TRIGGER task_automation_policy_immutable
BEFORE UPDATE ON task_automation_policy
BEGIN SELECT RAISE(ABORT, 'Task automation policy is immutable'); END;

CREATE TABLE remote_mark_ready_authorization (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_snapshot_id       TEXT    NOT NULL UNIQUE REFERENCES remote_pr_snapshot(id),
    ci_evaluation_id            TEXT    NOT NULL UNIQUE REFERENCES remote_ci_evaluation(id),
    automation_policy_id        TEXT    NOT NULL REFERENCES task_automation_policy(id),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    authority_kind              TEXT    NOT NULL CHECK (authority_kind IN (
        'MANUAL', 'AUTO_APPROVE_POLICY')),
    actor_id                    TEXT,
    status                      TEXT    NOT NULL CHECK (status IN (
        'ACTIVE', 'CONSUMED', 'REVOKED')),
    authorized_at_ms            INTEGER NOT NULL,
    terminal_at_ms              INTEGER,
    CHECK ((authority_kind = 'MANUAL' AND actor_id IS NOT NULL)
        OR (authority_kind = 'AUTO_APPROVE_POLICY' AND actor_id IS NULL)),
    CHECK ((status = 'ACTIVE') = (terminal_at_ms IS NULL))
);

CREATE TRIGGER remote_mark_ready_authorization_insert
BEFORE INSERT ON remote_mark_ready_authorization
WHEN NEW.status <> 'ACTIVE'
  OR NOT EXISTS (
    SELECT 1 FROM remote_development_stage remote
    JOIN tasks task ON task.id = remote.task_id
    JOIN remote_pr_snapshot snapshot ON snapshot.id = NEW.remote_pr_snapshot_id
    JOIN remote_ci_evaluation ci ON ci.id = NEW.ci_evaluation_id
    JOIN task_automation_policy policy ON policy.id = NEW.automation_policy_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND remote.accepted_snapshot_id = snapshot.id
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch
      AND snapshot.remote_development_stage_id = remote.stage_id
      AND snapshot.task_id = NEW.task_id
      AND snapshot.stage_generation = NEW.stage_generation
      AND snapshot.head_sha = NEW.head_sha
      AND snapshot.base_sha = NEW.base_sha
      AND snapshot.pr_state = 'DRAFT'
      AND ci.remote_pr_snapshot_id = snapshot.id
      AND ci.head_sha = NEW.head_sha
      AND ci.base_sha = NEW.base_sha
      AND ci.policy_outcome = 'ACCEPTED'
      AND policy.task_id = NEW.task_id
      AND policy.revision = (
          SELECT MAX(current_policy.revision)
          FROM task_automation_policy current_policy
          WHERE current_policy.task_id = NEW.task_id)
      AND (NEW.authority_kind = 'MANUAL'
        OR (policy.auto_approve = 1 AND policy.keep_draft = 0
            AND policy.stewardship_exception = 0)))
BEGIN SELECT RAISE(ABORT, 'Mark-ready authorization lacks current Draft and green exact-head proof'); END;

CREATE TRIGGER remote_mark_ready_authorization_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, remote_pr_snapshot_id, ci_evaluation_id,
        automation_policy_id, head_sha, base_sha, authority_kind, actor_id,
        authorized_at_ms ON remote_mark_ready_authorization
WHEN NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.remote_pr_snapshot_id IS NOT OLD.remote_pr_snapshot_id
  OR NEW.ci_evaluation_id IS NOT OLD.ci_evaluation_id
  OR NEW.automation_policy_id IS NOT OLD.automation_policy_id
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.authority_kind IS NOT OLD.authority_kind
  OR NEW.actor_id IS NOT OLD.actor_id
  OR NEW.authorized_at_ms IS NOT OLD.authorized_at_ms
BEGIN SELECT RAISE(ABORT, 'Mark-ready authorization identity is immutable'); END;

CREATE TABLE remote_mark_ready_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    mark_ready_authorization_id TEXT    NOT NULL UNIQUE
        REFERENCES remote_mark_ready_authorization(id),
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'AWAITING_OBSERVATION', 'SUCCEEDED',
        'FAILED', 'INDETERMINATE', 'CANCELED')),
    attempt_count               INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit               INTEGER NOT NULL CHECK (attempt_limit > 0),
    claim_mode                  TEXT CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    claim_owner                 TEXT,
    claimed_at_ms               INTEGER,
    lease_until_ms              INTEGER,
    result_snapshot_id          TEXT REFERENCES remote_pr_snapshot(id),
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    evidence                    TEXT,
    last_error                  TEXT,
    CHECK ((status = 'CLAIMED') = (claim_mode IS NOT NULL
        AND claim_owner IS NOT NULL AND claimed_at_ms IS NOT NULL
        AND lease_until_ms IS NOT NULL)),
    CHECK (status <> 'CLAIMED' OR lease_until_ms > claimed_at_ms),
    CHECK (attempt_count <= attempt_limit),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (result_snapshot_id IS NOT NULL))
);

CREATE TRIGGER remote_mark_ready_operation_insert
BEFORE INSERT ON remote_mark_ready_operation
WHEN NEW.status <> 'REQUESTED' OR NEW.attempt_count <> 0
  OR NOT EXISTS (
    SELECT 1 FROM remote_mark_ready_authorization authorization
    JOIN remote_development_stage remote
      ON remote.stage_id = authorization.remote_development_stage_id
    WHERE authorization.id = NEW.mark_ready_authorization_id
      AND authorization.status = 'ACTIVE'
      AND authorization.remote_development_stage_id = NEW.remote_development_stage_id
      AND authorization.task_id = NEW.task_id
      AND authorization.task_epoch = NEW.task_epoch
      AND authorization.stage_generation = NEW.stage_generation
      AND authorization.head_sha = NEW.head_sha
      AND authorization.base_sha = NEW.base_sha
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = NEW.base_sha)
BEGIN SELECT RAISE(ABORT, 'Mark-ready operation lacks active exact-head authorization'); END;

CREATE TRIGGER remote_mark_ready_authorization_transition
BEFORE UPDATE OF status ON remote_mark_ready_authorization
WHEN NEW.status IS NOT OLD.status
  AND (OLD.status <> 'ACTIVE'
    OR NEW.status NOT IN ('CONSUMED', 'REVOKED')
    OR (NEW.status = 'CONSUMED' AND NOT EXISTS (
        SELECT 1 FROM remote_mark_ready_operation operation
        WHERE operation.mark_ready_authorization_id = NEW.id)))
BEGIN SELECT RAISE(ABORT, 'Mark-ready authorization transition is invalid'); END;

CREATE TRIGGER remote_mark_ready_authorization_terminal_immutable
BEFORE UPDATE ON remote_mark_ready_authorization
WHEN OLD.status IN ('CONSUMED', 'REVOKED')
BEGIN SELECT RAISE(ABORT, 'Terminal mark-ready authorization is immutable'); END;

CREATE TRIGGER remote_mark_ready_operation_identity_immutable
BEFORE UPDATE OF mark_ready_authorization_id, remote_development_stage_id,
        task_id, task_epoch, stage_generation, operation_id, semantic_attempt,
        head_sha, base_sha, attempt_limit, requested_at_ms
        ON remote_mark_ready_operation
WHEN NEW.mark_ready_authorization_id IS NOT OLD.mark_ready_authorization_id
  OR NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.attempt_limit IS NOT OLD.attempt_limit
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'Mark-ready operation subject is immutable'); END;

CREATE TRIGGER remote_mark_ready_operation_transition
BEFORE UPDATE OF status ON remote_mark_ready_operation
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
        AND NEW.status = 'CLAIMED')
    OR (OLD.status = 'CLAIMED'
        AND NEW.status IN ('AWAITING_OBSERVATION', 'FAILED',
            'INDETERMINATE', 'CANCELED'))
    OR (OLD.status = 'AWAITING_OBSERVATION'
        AND NEW.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE', 'CANCELED')))
BEGIN SELECT RAISE(ABORT, 'Mark-ready operation transition is invalid'); END;

CREATE TRIGGER remote_mark_ready_operation_claim
BEFORE UPDATE OF status ON remote_mark_ready_operation
WHEN NEW.status = 'CLAIMED'
  AND (NEW.attempt_count <> OLD.attempt_count + 1
    OR OLD.status NOT IN ('REQUESTED', 'CLAIMED', 'FAILED', 'INDETERMINATE')
    OR (OLD.status = 'CLAIMED'
        AND (NEW.claim_mode <> 'PROBE'
          OR NEW.claimed_at_ms < OLD.lease_until_ms))
    OR (OLD.status = 'INDETERMINATE' AND NEW.claim_mode <> 'PROBE')
    OR (OLD.status NOT IN ('CLAIMED', 'INDETERMINATE')
        AND NEW.claim_mode <> 'EXECUTE')
    OR NEW.result_snapshot_id IS NOT NULL OR NEW.evidence IS NOT NULL
    OR NEW.last_error IS NOT NULL OR NEW.completed_at_ms IS NOT NULL
    OR NOT EXISTS (
        SELECT 1 FROM remote_mark_ready_authorization authorization
        JOIN remote_development_stage remote
          ON remote.stage_id = authorization.remote_development_stage_id
        WHERE authorization.id = NEW.mark_ready_authorization_id
          AND authorization.status = 'CONSUMED'
          AND remote.accepted_snapshot_id = authorization.remote_pr_snapshot_id
          AND remote.current_head_sha = NEW.head_sha
          AND remote.current_base_sha = NEW.base_sha))
BEGIN SELECT RAISE(ABORT, 'Mark-ready claim is stale or lacks consumed consent'); END;

CREATE TRIGGER remote_mark_ready_operation_attempt
BEFORE UPDATE OF attempt_count ON remote_mark_ready_operation
WHEN NEW.status <> 'CLAIMED' OR NEW.attempt_count <> OLD.attempt_count + 1
BEGIN SELECT RAISE(ABORT, 'Mark-ready attempt must be an exact claim'); END;

CREATE TRIGGER remote_mark_ready_operation_claim_fields
BEFORE UPDATE OF claim_mode, claim_owner, claimed_at_ms, lease_until_ms
        ON remote_mark_ready_operation
WHEN NOT ((NEW.status = 'CLAIMED'
            AND NEW.attempt_count = OLD.attempt_count + 1)
        OR (OLD.status = 'CLAIMED' AND NEW.status <> 'CLAIMED'
            AND NEW.attempt_count = OLD.attempt_count
            AND NEW.claim_mode IS NULL AND NEW.claim_owner IS NULL
            AND NEW.claimed_at_ms IS NULL AND NEW.lease_until_ms IS NULL))
BEGIN SELECT RAISE(ABORT, 'Mark-ready claim fields change only with an attempt boundary'); END;

CREATE TRIGGER remote_mark_ready_operation_result_fields
BEFORE UPDATE OF result_snapshot_id, evidence, last_error, completed_at_ms
        ON remote_mark_ready_operation
WHEN (NEW.result_snapshot_id IS NOT OLD.result_snapshot_id
    OR NEW.evidence IS NOT OLD.evidence
    OR NEW.last_error IS NOT OLD.last_error
    OR NEW.completed_at_ms IS NOT OLD.completed_at_ms)
  AND NEW.status IS OLD.status
  AND NOT (NEW.status = 'CLAIMED'
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.result_snapshot_id IS NULL AND NEW.evidence IS NULL
    AND NEW.last_error IS NULL AND NEW.completed_at_ms IS NULL)
BEGIN SELECT RAISE(ABORT, 'Mark-ready result changes only at a result or claim boundary'); END;

CREATE TRIGGER remote_mark_ready_operation_success
BEFORE UPDATE OF status ON remote_mark_ready_operation
WHEN NEW.status = 'SUCCEEDED'
  AND NOT EXISTS (
      SELECT 1 FROM remote_pr_snapshot snapshot
      JOIN remote_development_stage remote
        ON remote.stage_id = snapshot.remote_development_stage_id
      WHERE snapshot.id = NEW.result_snapshot_id
        AND snapshot.remote_development_stage_id = NEW.remote_development_stage_id
        AND snapshot.task_id = NEW.task_id
        AND snapshot.stage_generation = NEW.stage_generation
        AND snapshot.head_sha = NEW.head_sha
        AND snapshot.base_sha = NEW.base_sha
        AND snapshot.pr_state = 'OPEN'
        AND remote.accepted_snapshot_id = snapshot.id
        AND remote.current_head_sha = NEW.head_sha
        AND remote.current_base_sha = NEW.base_sha)
BEGIN SELECT RAISE(ABORT, 'Mark-ready success requires accepted non-Draft remote truth'); END;

CREATE TRIGGER remote_mark_ready_operation_terminal_immutable
BEFORE UPDATE ON remote_mark_ready_operation
WHEN OLD.status IN ('SUCCEEDED', 'CANCELED')
BEGIN SELECT RAISE(ABORT, 'Terminal mark-ready operation is immutable'); END;

CREATE TABLE task_automation_eligibility_evidence (
    id                  TEXT    NOT NULL PRIMARY KEY,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch          INTEGER NOT NULL CHECK (task_epoch > 0),
    plan_revision_id    TEXT    NOT NULL UNIQUE REFERENCES plan_revision(id),
    risk_level          TEXT    NOT NULL CHECK (risk_level IN (
        'LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    effort              TEXT    NOT NULL CHECK (effort IN (
        'SMALL', 'MEDIUM', 'LARGE', 'UNKNOWN')),
    low_risk_eligible   INTEGER NOT NULL CHECK (low_risk_eligible IN (0, 1)),
    small_effort_eligible INTEGER NOT NULL CHECK (small_effort_eligible IN (0, 1)),
    evidence            TEXT    NOT NULL,
    content_digest      TEXT    NOT NULL,
    recorded_at_ms      INTEGER NOT NULL,
    CHECK (low_risk_eligible = CASE risk_level WHEN 'LOW' THEN 1 ELSE 0 END),
    CHECK (small_effort_eligible = CASE effort WHEN 'SMALL' THEN 1 ELSE 0 END)
);

CREATE TRIGGER task_automation_eligibility_evidence_insert
BEFORE INSERT ON task_automation_eligibility_evidence
WHEN NOT EXISTS (
    SELECT 1 FROM plan_revision revision
    JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
    JOIN tasks task ON task.id = plan.task_id
    WHERE revision.id = NEW.plan_revision_id
      AND plan.task_id = NEW.task_id
      AND plan.opened_for_epoch = NEW.task_epoch
      AND task.epoch = NEW.task_epoch
      AND task.workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'Automation eligibility lacks exact Task Plan evidence'); END;

CREATE TRIGGER task_automation_eligibility_evidence_immutable
BEFORE UPDATE ON task_automation_eligibility_evidence
BEGIN SELECT RAISE(ABORT, 'Task automation eligibility evidence is immutable'); END;

CREATE TABLE remote_readiness_evidence (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_snapshot_id       TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    ci_evaluation_id            TEXT    NOT NULL REFERENCES remote_ci_evaluation(id),
    automation_policy_id        TEXT    NOT NULL REFERENCES task_automation_policy(id),
    automation_eligibility_evidence_id TEXT
        REFERENCES task_automation_eligibility_evidence(id),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    pr_open                     INTEGER NOT NULL CHECK (pr_open IN (0, 1)),
    non_draft                   INTEGER NOT NULL CHECK (non_draft IN (0, 1)),
    ci_accepted                 INTEGER NOT NULL CHECK (ci_accepted IN (0, 1)),
    write_approval_count        INTEGER NOT NULL CHECK (write_approval_count >= 0),
    required_write_approval_count INTEGER NOT NULL CHECK (required_write_approval_count >= 0),
    changes_requested_count     INTEGER NOT NULL CHECK (changes_requested_count >= 0),
    unresolved_thread_count     INTEGER NOT NULL CHECK (unresolved_thread_count >= 0),
    unresolved_comment_count    INTEGER NOT NULL CHECK (unresolved_comment_count >= 0),
    open_feedback_batch_count   INTEGER NOT NULL CHECK (open_feedback_batch_count >= 0),
    blocking_gate_count         INTEGER NOT NULL CHECK (blocking_gate_count >= 0),
    low_risk_required           INTEGER NOT NULL CHECK (low_risk_required IN (0, 1)),
    small_effort_required       INTEGER NOT NULL CHECK (small_effort_required IN (0, 1)),
    low_risk_eligible           INTEGER NOT NULL CHECK (low_risk_eligible IN (0, 1)),
    small_effort_eligible       INTEGER NOT NULL CHECK (small_effort_eligible IN (0, 1)),
    mergeability                TEXT    NOT NULL CHECK (mergeability IN (
        'UNKNOWN', 'MERGEABLE', 'CONFLICTING', 'BLOCKED')),
    merge_queue_capability      TEXT    NOT NULL CHECK (merge_queue_capability IN (
        'UNKNOWN', 'UNSUPPORTED', 'SUPPORTED')),
    ready                       INTEGER NOT NULL CHECK (ready IN (0, 1)),
    evidence                    TEXT    NOT NULL,
    observed_at_ms              INTEGER NOT NULL,
    UNIQUE (remote_pr_snapshot_id, automation_policy_id),
    UNIQUE (id, remote_development_stage_id, head_sha),
    CHECK (ready = CASE WHEN pr_open = 1 AND non_draft = 1
            AND ci_accepted = 1
            AND write_approval_count >= required_write_approval_count
            AND changes_requested_count = 0
            AND unresolved_thread_count = 0
            AND unresolved_comment_count = 0
            AND open_feedback_batch_count = 0
            AND blocking_gate_count = 0
            AND (low_risk_required = 0 OR low_risk_eligible = 1)
            AND (small_effort_required = 0 OR small_effort_eligible = 1)
            AND mergeability = 'MERGEABLE'
        THEN 1 ELSE 0 END)
);

CREATE TRIGGER remote_readiness_evidence_insert
BEFORE INSERT ON remote_readiness_evidence
WHEN NOT EXISTS (
    SELECT 1 FROM remote_development_stage remote
    JOIN tasks task ON task.id = remote.task_id
    JOIN remote_pr_snapshot snapshot ON snapshot.id = NEW.remote_pr_snapshot_id
    JOIN remote_ci_evaluation ci ON ci.id = NEW.ci_evaluation_id
    JOIN task_automation_policy policy ON policy.id = NEW.automation_policy_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND remote.accepted_snapshot_id = NEW.remote_pr_snapshot_id
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch
      AND snapshot.remote_development_stage_id = remote.stage_id
      AND snapshot.task_id = NEW.task_id
      AND snapshot.head_sha = NEW.head_sha
      AND snapshot.base_sha = NEW.base_sha
      AND NEW.pr_open = CASE WHEN snapshot.pr_state = 'OPEN' THEN 1 ELSE 0 END
      AND NEW.non_draft = CASE WHEN snapshot.pr_state = 'OPEN' THEN 1 ELSE 0 END
      AND NEW.write_approval_count = snapshot.write_approval_count
      AND NEW.changes_requested_count = snapshot.changes_requested_count
      AND NEW.unresolved_thread_count = snapshot.unresolved_thread_count
      AND NEW.unresolved_comment_count = snapshot.unresolved_comment_count
      AND NEW.mergeability = snapshot.mergeability
      AND NEW.merge_queue_capability = snapshot.merge_queue_capability
      AND NEW.observed_at_ms = snapshot.observed_at_ms
      AND ci.remote_pr_snapshot_id = snapshot.id
      AND ci.head_sha = NEW.head_sha
      AND ci.base_sha = NEW.base_sha
      AND NEW.ci_accepted = CASE WHEN ci.policy_outcome = 'ACCEPTED' THEN 1 ELSE 0 END
      AND policy.task_id = NEW.task_id
      AND policy.revision = (
          SELECT MAX(current_policy.revision)
          FROM task_automation_policy current_policy
          WHERE current_policy.task_id = NEW.task_id)
      AND NEW.required_write_approval_count = policy.minimum_write_approvals
      AND NEW.low_risk_required = policy.require_low_risk
      AND NEW.small_effort_required = policy.require_small_effort
      AND ((policy.require_low_risk = 0 AND policy.require_small_effort = 0
                AND NEW.automation_eligibility_evidence_id IS NULL
                AND NEW.low_risk_eligible = 0
                AND NEW.small_effort_eligible = 0)
        OR EXISTS (
            SELECT 1 FROM task_automation_eligibility_evidence eligibility
            WHERE eligibility.id = NEW.automation_eligibility_evidence_id
              AND eligibility.task_id = NEW.task_id
              AND eligibility.task_epoch = NEW.task_epoch
              AND NEW.low_risk_eligible = eligibility.low_risk_eligible
              AND NEW.small_effort_eligible = eligibility.small_effort_eligible))
      AND NEW.open_feedback_batch_count = (
          SELECT COUNT(*) FROM remote_feedback_batch batch
          WHERE batch.remote_development_stage_id = remote.stage_id
            AND batch.head_sha = NEW.head_sha
            AND batch.status NOT IN ('COMPLETED', 'SUPERSEDED'))
      AND NEW.blocking_gate_count = (
          SELECT COUNT(*) FROM task_blocker blocker
          WHERE blocker.task_id = NEW.task_id
            AND (blocker.stage_id IS NULL OR blocker.stage_id = remote.stage_id)
            AND blocker.status = 'OPEN'))
BEGIN SELECT RAISE(ABORT, 'Readiness evidence does not match current exact-head truth'); END;

CREATE TRIGGER remote_readiness_evidence_immutable
BEFORE UPDATE ON remote_readiness_evidence
BEGIN SELECT RAISE(ABORT, 'Readiness evidence is immutable'); END;

CREATE TABLE remote_merge_authorization (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    readiness_evidence_id       TEXT    NOT NULL UNIQUE REFERENCES remote_readiness_evidence(id),
    automation_policy_id        TEXT    NOT NULL REFERENCES task_automation_policy(id),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    authority_kind              TEXT    NOT NULL CHECK (authority_kind IN (
        'MANUAL', 'AUTO_MERGE_POLICY')),
    actor_id                    TEXT,
    status                      TEXT    NOT NULL CHECK (status IN (
        'ACTIVE', 'CONSUMED', 'REVOKED')),
    authorized_at_ms            INTEGER NOT NULL,
    terminal_at_ms              INTEGER,
    CHECK ((authority_kind = 'MANUAL' AND actor_id IS NOT NULL)
        OR (authority_kind = 'AUTO_MERGE_POLICY' AND actor_id IS NULL)),
    CHECK ((status = 'ACTIVE') = (terminal_at_ms IS NULL))
);

CREATE UNIQUE INDEX idx_remote_merge_authorization_live_head
    ON remote_merge_authorization(remote_development_stage_id, head_sha)
    WHERE status = 'ACTIVE';

CREATE TRIGGER remote_merge_authorization_insert
BEFORE INSERT ON remote_merge_authorization
WHEN NOT EXISTS (
    SELECT 1 FROM remote_readiness_evidence readiness
    JOIN remote_development_stage remote
      ON remote.stage_id = readiness.remote_development_stage_id
    JOIN task_automation_policy policy
      ON policy.id = readiness.automation_policy_id
    JOIN tasks task ON task.id = readiness.task_id
    WHERE readiness.id = NEW.readiness_evidence_id
      AND readiness.ready = 1
      AND readiness.remote_development_stage_id =
            NEW.remote_development_stage_id
      AND readiness.task_id = NEW.task_id
      AND readiness.task_epoch = NEW.task_epoch
      AND readiness.stage_generation = NEW.stage_generation
      AND readiness.automation_policy_id = NEW.automation_policy_id
      AND policy.revision = (
          SELECT MAX(current_policy.revision)
          FROM task_automation_policy current_policy
          WHERE current_policy.task_id = NEW.task_id)
      AND readiness.head_sha = NEW.head_sha
      AND readiness.base_sha = NEW.base_sha
      AND remote.accepted_snapshot_id = readiness.remote_pr_snapshot_id
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch
      AND (NEW.authority_kind = 'MANUAL'
        OR (policy.auto_merge = 1 AND policy.auto_approve = 1
            AND policy.stewardship_exception = 0)))
BEGIN SELECT RAISE(ABORT, 'Merge authorization lacks fresh exact-head readiness'); END;

CREATE TRIGGER remote_merge_authorization_identity_immutable
BEFORE UPDATE OF remote_development_stage_id, task_id, task_epoch,
        stage_generation, readiness_evidence_id, automation_policy_id,
        head_sha, base_sha, authority_kind, actor_id, authorized_at_ms
        ON remote_merge_authorization
WHEN NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.readiness_evidence_id IS NOT OLD.readiness_evidence_id
  OR NEW.automation_policy_id IS NOT OLD.automation_policy_id
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.authority_kind IS NOT OLD.authority_kind
  OR NEW.actor_id IS NOT OLD.actor_id
  OR NEW.authorized_at_ms IS NOT OLD.authorized_at_ms
BEGIN SELECT RAISE(ABORT, 'Merge authorization identity is immutable'); END;

CREATE TRIGGER remote_merge_authorization_transition
BEFORE UPDATE OF status ON remote_merge_authorization
WHEN NEW.status IS NOT OLD.status
  AND (OLD.status <> 'ACTIVE'
    OR NEW.status NOT IN ('CONSUMED', 'REVOKED')
    OR (NEW.status = 'CONSUMED' AND NOT EXISTS (
        SELECT 1 FROM remote_merge_operation operation
        WHERE operation.merge_authorization_id = NEW.id)))
BEGIN SELECT RAISE(ABORT, 'Merge authorization transition is invalid'); END;

CREATE TRIGGER remote_merge_authorization_terminal_immutable
BEFORE UPDATE ON remote_merge_authorization
WHEN OLD.status IN ('CONSUMED', 'REVOKED')
BEGIN SELECT RAISE(ABORT, 'Terminal merge authorization is immutable'); END;

CREATE TABLE remote_merge_operation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    merge_authorization_id      TEXT    NOT NULL UNIQUE
        REFERENCES remote_merge_authorization(id),
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    operation_id                TEXT    NOT NULL UNIQUE,
    semantic_attempt            INTEGER NOT NULL CHECK (semantic_attempt > 0),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    mode                        TEXT    NOT NULL CHECK (mode IN (
        'DIRECT', 'MERGE_QUEUE')),
    merge_queue_capability      TEXT    NOT NULL CHECK (merge_queue_capability IN (
        'UNSUPPORTED', 'SUPPORTED')),
    status                      TEXT    NOT NULL CHECK (status IN (
        'REQUESTED', 'CLAIMED', 'QUEUE_ENTERED', 'AWAITING_OBSERVATION',
        'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')),
    attempt_count               INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    attempt_limit               INTEGER NOT NULL CHECK (attempt_limit > 0),
    queue_bounce_count          INTEGER NOT NULL DEFAULT 0 CHECK (queue_bounce_count >= 0),
    max_queue_reenqueues        INTEGER NOT NULL CHECK (max_queue_reenqueues >= 0),
    terminal_observation_id     TEXT REFERENCES remote_terminal_observation(id)
        DEFERRABLE INITIALLY DEFERRED,
    requested_at_ms             INTEGER NOT NULL,
    completed_at_ms             INTEGER,
    block_reason                TEXT CHECK (block_reason IN (
        'QUEUE_REENQUEUE_EXHAUSTED', 'PERMISSION_DENIED',
        'MERGEABILITY_REGRESSED', 'MANUAL_INTERVENTION')),
    last_error                  TEXT,
    CHECK (attempt_count <= attempt_limit),
    CHECK (queue_bounce_count <= max_queue_reenqueues),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED' OR terminal_observation_id IS NOT NULL),
    CHECK ((status = 'BLOCKED') = (block_reason IS NOT NULL)),
    CHECK (mode = 'MERGE_QUEUE' OR max_queue_reenqueues = 0),
    CHECK ((mode = 'MERGE_QUEUE' AND merge_queue_capability = 'SUPPORTED')
        OR (mode = 'DIRECT' AND merge_queue_capability = 'UNSUPPORTED'))
);

CREATE TRIGGER remote_merge_operation_insert
BEFORE INSERT ON remote_merge_operation
WHEN NOT EXISTS (
    SELECT 1 FROM remote_merge_authorization authorization
    JOIN remote_development_stage remote
      ON remote.stage_id = authorization.remote_development_stage_id
    JOIN task_automation_policy policy
      ON policy.id = authorization.automation_policy_id
    JOIN remote_readiness_evidence readiness
      ON readiness.id = authorization.readiness_evidence_id
    WHERE authorization.id = NEW.merge_authorization_id
      AND NEW.status = 'REQUESTED'
      AND NEW.attempt_count = 0
      AND NEW.queue_bounce_count = 0
      AND NEW.terminal_observation_id IS NULL
      AND authorization.status = 'ACTIVE'
      AND authorization.remote_development_stage_id =
            NEW.remote_development_stage_id
      AND authorization.task_id = NEW.task_id
      AND authorization.task_epoch = NEW.task_epoch
      AND authorization.stage_generation = NEW.stage_generation
      AND authorization.head_sha = NEW.head_sha
      AND authorization.base_sha = NEW.base_sha
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND remote.accepted_snapshot_id = readiness.remote_pr_snapshot_id
      AND readiness.merge_queue_capability = NEW.merge_queue_capability
      AND NEW.max_queue_reenqueues = CASE WHEN NEW.mode = 'MERGE_QUEUE'
          THEN policy.max_merge_queue_reenqueues ELSE 0 END)
BEGIN SELECT RAISE(ABORT, 'Merge operation lacks active exact-head authorization'); END;

CREATE TRIGGER remote_merge_operation_identity_immutable
BEFORE UPDATE OF merge_authorization_id, remote_development_stage_id,
        task_id, task_epoch, stage_generation, operation_id, semantic_attempt,
        head_sha, base_sha, mode, merge_queue_capability,
        attempt_limit, max_queue_reenqueues, requested_at_ms
        ON remote_merge_operation
WHEN NEW.merge_authorization_id IS NOT OLD.merge_authorization_id
  OR NEW.remote_development_stage_id IS NOT OLD.remote_development_stage_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.task_epoch IS NOT OLD.task_epoch
  OR NEW.stage_generation IS NOT OLD.stage_generation
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.semantic_attempt IS NOT OLD.semantic_attempt
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.base_sha IS NOT OLD.base_sha
  OR NEW.mode IS NOT OLD.mode
  OR NEW.merge_queue_capability IS NOT OLD.merge_queue_capability
  OR NEW.attempt_limit IS NOT OLD.attempt_limit
  OR NEW.max_queue_reenqueues IS NOT OLD.max_queue_reenqueues
  OR NEW.requested_at_ms IS NOT OLD.requested_at_ms
BEGIN SELECT RAISE(ABORT, 'Merge operation subject is immutable'); END;

CREATE UNIQUE INDEX idx_remote_merge_operation_one_live_head
    ON remote_merge_operation(remote_development_stage_id, head_sha)
    WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED');

-- One immutable row is one committed claim. A recovery probe creates a new
-- attempt row with the same external idempotency key; it never turns an
-- expired claim into another execution of the remote effect.
CREATE TABLE remote_merge_effect_attempt (
    id                       TEXT    NOT NULL PRIMARY KEY,
    merge_operation_id       TEXT    NOT NULL
        REFERENCES remote_merge_operation(id) ON DELETE CASCADE,
    ordinal                  INTEGER NOT NULL CHECK (ordinal > 0),
    effect_kind              TEXT    NOT NULL CHECK (effect_kind IN (
        'DIRECT_MERGE', 'ENTER_QUEUE')),
    effect_ordinal           INTEGER CHECK (effect_ordinal > 0),
    readiness_evidence_id    TEXT    NOT NULL REFERENCES remote_readiness_evidence(id),
    idempotency_key          TEXT    NOT NULL,
    attempt_key              TEXT    NOT NULL UNIQUE,
    claim_mode               TEXT    NOT NULL CHECK (claim_mode IN ('EXECUTE', 'PROBE')),
    status                   TEXT    NOT NULL CHECK (status IN (
        'CLAIMED', 'AWAITING_OBSERVATION', 'SUCCEEDED', 'FAILED',
        'INDETERMINATE')),
    claim_owner              TEXT    NOT NULL,
    claimed_at_ms            INTEGER NOT NULL,
    lease_until_ms           INTEGER NOT NULL,
    observed_snapshot_id     TEXT REFERENCES remote_pr_snapshot(id),
    external_effect_id       TEXT,
    evidence                 TEXT,
    last_error               TEXT,
    completed_at_ms          INTEGER,
    UNIQUE (merge_operation_id, ordinal),
    CHECK (lease_until_ms > claimed_at_ms),
    CHECK ((effect_kind = 'DIRECT_MERGE') = (effect_ordinal IS NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE'))
        = (completed_at_ms IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED'
        OR (observed_snapshot_id IS NOT NULL AND evidence IS NOT NULL)),
    CHECK (status NOT IN ('FAILED', 'INDETERMINATE')
        OR evidence IS NOT NULL)
);

CREATE UNIQUE INDEX idx_remote_merge_effect_execute_once
    ON remote_merge_effect_attempt(idempotency_key)
    WHERE claim_mode = 'EXECUTE';

CREATE TRIGGER remote_merge_effect_attempt_insert
BEFORE INSERT ON remote_merge_effect_attempt
WHEN NEW.status <> 'CLAIMED'
  OR NEW.attempt_key <> NEW.idempotency_key || ':attempt:' || NEW.ordinal
  OR NEW.observed_snapshot_id IS NOT NULL OR NEW.external_effect_id IS NOT NULL
  OR NEW.evidence IS NOT NULL OR NEW.last_error IS NOT NULL
  OR NEW.completed_at_ms IS NOT NULL
  OR NOT EXISTS (
      SELECT 1 FROM remote_merge_operation operation
      JOIN remote_merge_authorization authorization
        ON authorization.id = operation.merge_authorization_id
      JOIN remote_development_stage remote
        ON remote.stage_id = operation.remote_development_stage_id
      JOIN tasks task ON task.id = operation.task_id
      WHERE operation.id = NEW.merge_operation_id
        AND authorization.status = 'CONSUMED'
        AND operation.status IN ('REQUESTED', 'CLAIMED', 'AWAITING_OBSERVATION')
        AND NEW.ordinal = operation.attempt_count + 1
        AND NEW.ordinal <= operation.attempt_limit
        AND remote.current_head_sha = operation.head_sha
        AND remote.current_base_sha = operation.base_sha
        AND task.epoch = operation.task_epoch)
BEGIN SELECT RAISE(ABORT, 'Merge effect attempt lacks a current consumed operation claim'); END;

CREATE TRIGGER remote_merge_effect_attempt_execute
BEFORE INSERT ON remote_merge_effect_attempt
WHEN NEW.claim_mode = 'EXECUTE'
  AND NOT EXISTS (
      SELECT 1 FROM remote_merge_operation operation
      JOIN remote_merge_authorization authorization
        ON authorization.id = operation.merge_authorization_id
      JOIN remote_readiness_evidence readiness
        ON readiness.id = NEW.readiness_evidence_id
      JOIN remote_pr_snapshot snapshot
        ON snapshot.id = readiness.remote_pr_snapshot_id
      JOIN remote_development_stage remote
        ON remote.stage_id = operation.remote_development_stage_id
      WHERE operation.id = NEW.merge_operation_id
        AND readiness.remote_development_stage_id = operation.remote_development_stage_id
        AND readiness.task_id = operation.task_id
        AND readiness.task_epoch = operation.task_epoch
        AND readiness.stage_generation = operation.stage_generation
        AND readiness.automation_policy_id = authorization.automation_policy_id
        AND readiness.head_sha = operation.head_sha
        AND readiness.base_sha = operation.base_sha
        AND readiness.merge_queue_capability = operation.merge_queue_capability
        AND readiness.ready = 1
        AND snapshot.head_sha = operation.head_sha
        AND snapshot.base_sha = operation.base_sha
        AND remote.accepted_snapshot_id = snapshot.id
        AND remote.current_head_sha = operation.head_sha
        AND remote.current_base_sha = operation.base_sha
        AND ((operation.mode = 'DIRECT'
              AND operation.status = 'REQUESTED'
              AND NEW.effect_kind = 'DIRECT_MERGE'
              AND NEW.effect_ordinal IS NULL
              AND NEW.readiness_evidence_id = authorization.readiness_evidence_id
              AND NEW.idempotency_key = operation.operation_id || ':direct')
          OR (operation.mode = 'MERGE_QUEUE'
              AND NEW.effect_kind = 'ENTER_QUEUE'
              AND NEW.idempotency_key = operation.operation_id || ':queue:' || NEW.effect_ordinal
              AND ((operation.status = 'REQUESTED'
                    AND NEW.effect_ordinal = 1
                    AND snapshot.merge_queue_state IN ('NONE', 'DEQUEUED')
                    AND NEW.readiness_evidence_id = authorization.readiness_evidence_id)
                OR (operation.status = 'AWAITING_OBSERVATION'
                    AND NEW.effect_ordinal = operation.queue_bounce_count + 1
                    AND NEW.effect_ordinal > 1
                    AND NEW.effect_ordinal <= operation.max_queue_reenqueues + 1
                    AND snapshot.merge_queue_state = 'DEQUEUED'
                    AND EXISTS (
                        SELECT 1 FROM remote_merge_queue_entry previous
                        WHERE previous.merge_operation_id = operation.id
                          AND previous.ordinal = NEW.effect_ordinal - 1
                          AND previous.status = 'BOUNCED'
                          AND previous.observed_snapshot_id = snapshot.id))))))
BEGIN SELECT RAISE(ABORT, 'Merge execution claim lacks fresh pre-effect readiness'); END;

CREATE TRIGGER remote_merge_effect_attempt_probe
BEFORE INSERT ON remote_merge_effect_attempt
WHEN NEW.claim_mode = 'PROBE'
  AND NOT EXISTS (
      SELECT 1 FROM remote_merge_effect_attempt previous
      JOIN remote_merge_operation operation
        ON operation.id = previous.merge_operation_id
      WHERE previous.merge_operation_id = NEW.merge_operation_id
        AND previous.ordinal = NEW.ordinal - 1
        AND previous.effect_kind = NEW.effect_kind
        AND previous.effect_ordinal IS NEW.effect_ordinal
        AND previous.readiness_evidence_id = NEW.readiness_evidence_id
        AND previous.idempotency_key = NEW.idempotency_key
        AND operation.status IN ('CLAIMED', 'AWAITING_OBSERVATION')
        AND (previous.status = 'INDETERMINATE'
          OR (previous.status IN ('CLAIMED', 'AWAITING_OBSERVATION')
              AND NEW.claimed_at_ms >= previous.lease_until_ms)))
BEGIN SELECT RAISE(ABORT, 'Merge recovery must probe the expired or indeterminate effect'); END;

CREATE TRIGGER remote_merge_effect_attempt_identity_immutable
BEFORE UPDATE OF merge_operation_id, ordinal, effect_kind, effect_ordinal,
        readiness_evidence_id, idempotency_key, attempt_key, claim_mode,
        claim_owner, claimed_at_ms, lease_until_ms
        ON remote_merge_effect_attempt
WHEN NEW.merge_operation_id IS NOT OLD.merge_operation_id
  OR NEW.ordinal IS NOT OLD.ordinal
  OR NEW.effect_kind IS NOT OLD.effect_kind
  OR NEW.effect_ordinal IS NOT OLD.effect_ordinal
  OR NEW.readiness_evidence_id IS NOT OLD.readiness_evidence_id
  OR NEW.idempotency_key IS NOT OLD.idempotency_key
  OR NEW.attempt_key IS NOT OLD.attempt_key
  OR NEW.claim_mode IS NOT OLD.claim_mode
  OR NEW.claim_owner IS NOT OLD.claim_owner
  OR NEW.claimed_at_ms IS NOT OLD.claimed_at_ms
  OR NEW.lease_until_ms IS NOT OLD.lease_until_ms
BEGIN SELECT RAISE(ABORT, 'Merge effect attempt claim is immutable'); END;

CREATE TRIGGER remote_merge_effect_attempt_result_fields
BEFORE UPDATE OF observed_snapshot_id, external_effect_id, evidence,
        last_error, completed_at_ms ON remote_merge_effect_attempt
WHEN NEW.status IS OLD.status
  AND (NEW.observed_snapshot_id IS NOT OLD.observed_snapshot_id
    OR NEW.external_effect_id IS NOT OLD.external_effect_id
    OR NEW.evidence IS NOT OLD.evidence
    OR NEW.last_error IS NOT OLD.last_error
    OR NEW.completed_at_ms IS NOT OLD.completed_at_ms)
BEGIN SELECT RAISE(ABORT, 'Merge effect result changes only with its status transition'); END;

CREATE TRIGGER remote_merge_effect_attempt_transition
BEFORE UPDATE OF status ON remote_merge_effect_attempt
WHEN NEW.status IS NOT OLD.status
  AND NOT ((OLD.status = 'CLAIMED'
              AND NEW.status IN ('AWAITING_OBSERVATION', 'SUCCEEDED',
                  'FAILED', 'INDETERMINATE'))
        OR (OLD.status = 'AWAITING_OBSERVATION'
              AND NEW.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')))
BEGIN SELECT RAISE(ABORT, 'Merge effect attempt transition is invalid'); END;

CREATE TRIGGER remote_merge_effect_attempt_success
BEFORE UPDATE OF status ON remote_merge_effect_attempt
WHEN NEW.status = 'SUCCEEDED'
  AND NOT EXISTS (
      SELECT 1 FROM remote_pr_snapshot snapshot
      JOIN remote_merge_operation operation
        ON operation.id = NEW.merge_operation_id
      JOIN remote_development_stage remote
        ON remote.stage_id = operation.remote_development_stage_id
      WHERE snapshot.id = NEW.observed_snapshot_id
        AND snapshot.remote_development_stage_id = operation.remote_development_stage_id
        AND snapshot.task_id = operation.task_id
        AND snapshot.stage_generation = operation.stage_generation
        AND snapshot.head_sha = operation.head_sha
        AND snapshot.base_sha = operation.base_sha
        AND remote.accepted_snapshot_id = snapshot.id
        AND remote.current_head_sha = operation.head_sha
        AND remote.current_base_sha = operation.base_sha
        AND ((NEW.effect_kind = 'DIRECT_MERGE' AND snapshot.pr_state = 'MERGED')
          OR (NEW.effect_kind = 'ENTER_QUEUE'
              AND snapshot.pr_state = 'OPEN'
              AND snapshot.merge_queue_state = 'QUEUED')))
BEGIN SELECT RAISE(ABORT, 'Merge effect completion lacks exact observed remote truth'); END;

CREATE TRIGGER remote_merge_effect_attempt_terminal_immutable
BEFORE UPDATE ON remote_merge_effect_attempt
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE')
BEGIN SELECT RAISE(ABORT, 'Terminal merge effect attempt is immutable'); END;

CREATE TRIGGER remote_merge_operation_bounce_monotonic
BEFORE UPDATE OF queue_bounce_count ON remote_merge_operation
WHEN NEW.queue_bounce_count < OLD.queue_bounce_count
  OR NEW.queue_bounce_count > OLD.queue_bounce_count + 1
  OR (NEW.queue_bounce_count = OLD.queue_bounce_count + 1
    AND (NEW.mode <> 'MERGE_QUEUE' OR NOT EXISTS (
        SELECT 1 FROM remote_merge_queue_entry entry
        WHERE entry.merge_operation_id = NEW.id
          AND entry.ordinal = NEW.queue_bounce_count
          AND entry.status = 'BOUNCED')))
BEGIN SELECT RAISE(ABORT, 'Merge queue bounce count must advance by at most one'); END;

CREATE TRIGGER remote_merge_operation_transition
BEFORE UPDATE OF status ON remote_merge_operation
WHEN NEW.status IS NOT OLD.status
  AND NOT (
    (OLD.status = 'REQUESTED' AND NEW.status IN ('CLAIMED', 'CANCELED'))
    OR (OLD.status = 'CLAIMED'
        AND NEW.status IN ('QUEUE_ENTERED', 'AWAITING_OBSERVATION',
            'FAILED', 'BLOCKED', 'CANCELED'))
    OR (OLD.status = 'QUEUE_ENTERED'
        AND NEW.status IN ('AWAITING_OBSERVATION', 'BLOCKED', 'CANCELED'))
    OR (OLD.status = 'AWAITING_OBSERVATION'
        AND NEW.status IN ('CLAIMED', 'QUEUE_ENTERED', 'SUCCEEDED',
            'FAILED', 'BLOCKED', 'CANCELED')))
BEGIN SELECT RAISE(ABORT, 'Merge operation transition is invalid'); END;

CREATE TRIGGER remote_merge_operation_claim
BEFORE UPDATE OF status ON remote_merge_operation
WHEN NEW.status = 'CLAIMED'
  AND (NEW.attempt_count <> OLD.attempt_count + 1
    OR NOT EXISTS (
      SELECT 1 FROM remote_merge_effect_attempt attempt
      WHERE attempt.merge_operation_id = NEW.id
        AND attempt.ordinal = NEW.attempt_count
        AND attempt.status = 'CLAIMED'
        AND attempt.ordinal = (
            SELECT MAX(latest.ordinal)
            FROM remote_merge_effect_attempt latest
            WHERE latest.merge_operation_id = NEW.id))
    OR NOT EXISTS (
      SELECT 1 FROM remote_merge_authorization authorization
      JOIN remote_development_stage remote
        ON remote.stage_id = authorization.remote_development_stage_id
      JOIN tasks task ON task.id = authorization.task_id
      WHERE authorization.id = NEW.merge_authorization_id
        AND authorization.status = 'CONSUMED'
        AND authorization.head_sha = NEW.head_sha
        AND authorization.base_sha = NEW.base_sha
        AND remote.current_head_sha = NEW.head_sha
        AND remote.current_base_sha = NEW.base_sha
        AND task.epoch = NEW.task_epoch))
BEGIN SELECT RAISE(ABORT, 'Merge operation claim is stale or lacks consumed consent'); END;

CREATE TRIGGER remote_merge_operation_attempt
BEFORE UPDATE OF attempt_count ON remote_merge_operation
WHEN NEW.status <> 'CLAIMED'
  OR NEW.attempt_count <> OLD.attempt_count + 1
BEGIN SELECT RAISE(ABORT, 'Merge operation attempt must be an exact committed claim'); END;

CREATE TRIGGER remote_merge_operation_queue_entered
BEFORE UPDATE OF status ON remote_merge_operation
WHEN NEW.status = 'QUEUE_ENTERED'
  AND (NEW.mode <> 'MERGE_QUEUE' OR NOT EXISTS (
      SELECT 1 FROM remote_merge_queue_entry entry
      JOIN remote_merge_effect_attempt attempt
        ON attempt.id = entry.merge_effect_attempt_id
      WHERE entry.merge_operation_id = NEW.id
        AND entry.ordinal = NEW.queue_bounce_count + 1
        AND entry.status = 'ENTERED'
        AND attempt.merge_operation_id = NEW.id
        AND attempt.ordinal = NEW.attempt_count
        AND attempt.status = 'SUCCEEDED'))
BEGIN SELECT RAISE(ABORT, 'Merge queue operation lacks its current entered fact'); END;

CREATE TRIGGER remote_merge_operation_blocked
BEFORE UPDATE OF status ON remote_merge_operation
WHEN NEW.status = 'BLOCKED'
  AND (NOT EXISTS (
        SELECT 1 FROM task_blocker blocker
        WHERE blocker.task_id = NEW.task_id
          AND blocker.stage_id = NEW.remote_development_stage_id
          AND blocker.owner_kind = 'OPERATION'
          AND blocker.owner_id = NEW.id
          AND blocker.subject_revision = NEW.head_sha
          AND blocker.status = 'OPEN')
    OR (NEW.block_reason = 'QUEUE_REENQUEUE_EXHAUSTED'
        AND NOT EXISTS (
            SELECT 1 FROM remote_merge_queue_entry entry
            WHERE entry.merge_operation_id = NEW.id
              AND entry.ordinal = NEW.max_queue_reenqueues + 1
              AND entry.status = 'BOUNCED')))
BEGIN SELECT RAISE(ABORT, 'Blocked merge operation lacks its exact durable blocker'); END;

CREATE TRIGGER remote_merge_operation_terminal_immutable
BEFORE UPDATE ON remote_merge_operation
WHEN OLD.status IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')
BEGIN SELECT RAISE(ABORT, 'Terminal merge operation is immutable'); END;

CREATE TABLE remote_merge_queue_entry (
    id                       TEXT    NOT NULL PRIMARY KEY,
    merge_operation_id       TEXT    NOT NULL REFERENCES remote_merge_operation(id) ON DELETE CASCADE,
    merge_effect_attempt_id  TEXT    NOT NULL UNIQUE
        REFERENCES remote_merge_effect_attempt(id),
    ordinal                  INTEGER NOT NULL CHECK (ordinal > 0),
    queue_entry_id           TEXT    NOT NULL UNIQUE,
    head_sha                 TEXT    NOT NULL,
    status                   TEXT    NOT NULL CHECK (status IN (
        'ENTERED', 'BOUNCED', 'MERGED', 'REMOVED')),
    entered_snapshot_id      TEXT    NOT NULL REFERENCES remote_pr_snapshot(id),
    readiness_evidence_id    TEXT    NOT NULL REFERENCES remote_readiness_evidence(id),
    observed_snapshot_id     TEXT REFERENCES remote_pr_snapshot(id),
    entered_at_ms            INTEGER NOT NULL,
    observed_at_ms           INTEGER,
    evidence                 TEXT    NOT NULL,
    UNIQUE (merge_operation_id, ordinal),
    CHECK ((status = 'ENTERED' AND observed_snapshot_id IS NULL
            AND observed_at_ms IS NULL)
        OR (status <> 'ENTERED' AND observed_snapshot_id IS NOT NULL
            AND observed_at_ms IS NOT NULL))
);

CREATE TRIGGER remote_merge_queue_entry_insert
BEFORE INSERT ON remote_merge_queue_entry
WHEN NOT EXISTS (
    SELECT 1 FROM remote_merge_operation operation
    JOIN remote_merge_authorization authorization
      ON authorization.id = operation.merge_authorization_id
    JOIN remote_merge_effect_attempt attempt
      ON attempt.id = NEW.merge_effect_attempt_id
    JOIN remote_pr_snapshot snapshot ON snapshot.id = NEW.entered_snapshot_id
    JOIN remote_readiness_evidence readiness
      ON readiness.id = NEW.readiness_evidence_id
    JOIN remote_development_stage remote
      ON remote.stage_id = operation.remote_development_stage_id
    WHERE operation.id = NEW.merge_operation_id
      AND operation.mode = 'MERGE_QUEUE'
      AND operation.status IN ('CLAIMED', 'AWAITING_OBSERVATION')
      AND operation.merge_queue_capability = 'SUPPORTED'
      AND NEW.status = 'ENTERED'
      AND operation.head_sha = NEW.head_sha
      AND attempt.merge_operation_id = operation.id
      AND attempt.ordinal = operation.attempt_count
      AND attempt.effect_kind = 'ENTER_QUEUE'
      AND attempt.effect_ordinal = NEW.ordinal
      AND attempt.readiness_evidence_id = NEW.readiness_evidence_id
      AND attempt.status IN ('CLAIMED', 'AWAITING_OBSERVATION')
      AND snapshot.remote_development_stage_id =
            operation.remote_development_stage_id
      AND snapshot.head_sha = NEW.head_sha
      AND snapshot.base_sha = operation.base_sha
      AND snapshot.pr_state = 'OPEN'
      AND snapshot.merge_queue_state = 'QUEUED'
      AND snapshot.merge_queue_capability = 'SUPPORTED'
      AND readiness.remote_development_stage_id = operation.remote_development_stage_id
      AND readiness.task_id = operation.task_id
      AND readiness.task_epoch = operation.task_epoch
      AND readiness.stage_generation = operation.stage_generation
      AND readiness.automation_policy_id = authorization.automation_policy_id
      AND readiness.head_sha = NEW.head_sha
      AND readiness.base_sha = operation.base_sha
      AND readiness.merge_queue_capability = 'SUPPORTED'
      AND readiness.ready = 1
      AND remote.accepted_snapshot_id = snapshot.id
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = operation.base_sha
      AND NEW.ordinal = COALESCE((
          SELECT MAX(entry.ordinal) + 1 FROM remote_merge_queue_entry entry
          WHERE entry.merge_operation_id = operation.id), 1)
      AND NEW.ordinal <= operation.max_queue_reenqueues + 1
      AND operation.queue_bounce_count = NEW.ordinal - 1
      AND (NEW.ordinal = 1 OR EXISTS (
          SELECT 1 FROM remote_merge_queue_entry previous
          WHERE previous.merge_operation_id = operation.id
            AND previous.ordinal = NEW.ordinal - 1
            AND previous.status = 'BOUNCED')))
BEGIN SELECT RAISE(ABORT, 'Merge queue entry exceeds consent or lacks bounce proof'); END;

CREATE TRIGGER remote_merge_queue_entry_identity_immutable
BEFORE UPDATE OF merge_operation_id, merge_effect_attempt_id, ordinal,
        queue_entry_id, head_sha,
        entered_snapshot_id, readiness_evidence_id, entered_at_ms
        ON remote_merge_queue_entry
WHEN NEW.merge_operation_id IS NOT OLD.merge_operation_id
  OR NEW.merge_effect_attempt_id IS NOT OLD.merge_effect_attempt_id
  OR NEW.ordinal IS NOT OLD.ordinal
  OR NEW.queue_entry_id IS NOT OLD.queue_entry_id
  OR NEW.head_sha IS NOT OLD.head_sha
  OR NEW.entered_snapshot_id IS NOT OLD.entered_snapshot_id
  OR NEW.readiness_evidence_id IS NOT OLD.readiness_evidence_id
  OR NEW.entered_at_ms IS NOT OLD.entered_at_ms
BEGIN SELECT RAISE(ABORT, 'Merge queue entry identity is immutable'); END;

CREATE TRIGGER remote_merge_queue_entry_observation
BEFORE UPDATE OF status, observed_snapshot_id, observed_at_ms
        ON remote_merge_queue_entry
WHEN NEW.status <> 'ENTERED'
  AND (OLD.status <> 'ENTERED'
    OR NEW.status NOT IN ('BOUNCED', 'MERGED', 'REMOVED')
    OR NOT EXISTS (
      SELECT 1 FROM remote_pr_snapshot snapshot
      JOIN remote_merge_operation operation
        ON operation.id = NEW.merge_operation_id
      JOIN remote_development_stage remote
        ON remote.stage_id = operation.remote_development_stage_id
      WHERE snapshot.id = NEW.observed_snapshot_id
        AND snapshot.remote_development_stage_id =
            operation.remote_development_stage_id
        AND snapshot.head_sha = NEW.head_sha
        AND snapshot.base_sha = operation.base_sha
        AND remote.accepted_snapshot_id = snapshot.id
        AND remote.current_head_sha = NEW.head_sha
        AND remote.current_base_sha = operation.base_sha
        AND NEW.observed_at_ms = snapshot.observed_at_ms
        AND ((NEW.status = 'BOUNCED'
                AND snapshot.pr_state = 'OPEN'
                AND snapshot.merge_queue_state = 'DEQUEUED')
          OR (NEW.status = 'MERGED'
                AND snapshot.pr_state = 'MERGED'
                AND snapshot.merge_queue_state IN ('NONE', 'MERGED'))
          OR (NEW.status = 'REMOVED'
                AND snapshot.pr_state = 'OPEN'
                AND snapshot.merge_queue_state = 'NONE'))))
BEGIN SELECT RAISE(ABORT, 'Merge queue outcome lacks exact observed remote truth'); END;

CREATE TRIGGER remote_merge_queue_entry_terminal_immutable
BEFORE UPDATE ON remote_merge_queue_entry
WHEN OLD.status IN ('BOUNCED', 'MERGED', 'REMOVED')
BEGIN SELECT RAISE(ABORT, 'Terminal merge queue entry is immutable'); END;

-- Existing TaskTerminalIntent is the durable Task-owned acceptance. This
-- remote fact proves that its source was a merged/closed snapshot rather than
-- a scheduler or optimistic merge effect.
CREATE TABLE remote_terminal_observation (
    id                          TEXT    NOT NULL PRIMARY KEY,
    remote_development_stage_id TEXT    NOT NULL
        REFERENCES remote_development_stage(stage_id) ON DELETE CASCADE,
    task_id                     TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_epoch                  INTEGER NOT NULL CHECK (task_epoch > 0),
    stage_generation            INTEGER NOT NULL CHECK (stage_generation > 0),
    remote_pr_binding_id        TEXT    NOT NULL REFERENCES remote_pr_binding(id),
    remote_pr_snapshot_id       TEXT    NOT NULL UNIQUE REFERENCES remote_pr_snapshot(id),
    task_terminal_intent_id     TEXT    NOT NULL UNIQUE REFERENCES task_terminal_intent(id),
    kind                        TEXT    NOT NULL CHECK (kind IN ('MERGED', 'CLOSED')),
    head_sha                    TEXT    NOT NULL,
    base_sha                    TEXT    NOT NULL,
    observed_at_ms              INTEGER NOT NULL,
    evidence                    TEXT    NOT NULL
);

CREATE TRIGGER remote_terminal_observation_insert
BEFORE INSERT ON remote_terminal_observation
WHEN NOT EXISTS (
    SELECT 1 FROM remote_pr_snapshot snapshot
    JOIN remote_development_stage remote
      ON remote.stage_id = snapshot.remote_development_stage_id
    JOIN tasks task ON task.id = remote.task_id
    JOIN task_terminal_intent intent ON intent.id = NEW.task_terminal_intent_id
    WHERE snapshot.id = NEW.remote_pr_snapshot_id
      AND snapshot.remote_development_stage_id =
            NEW.remote_development_stage_id
      AND snapshot.task_id = NEW.task_id
      AND snapshot.stage_generation = NEW.stage_generation
      AND snapshot.remote_pr_binding_id = NEW.remote_pr_binding_id
      AND snapshot.head_sha = NEW.head_sha
      AND snapshot.base_sha = NEW.base_sha
      AND snapshot.pr_state = NEW.kind
      AND NEW.observed_at_ms = snapshot.observed_at_ms
      AND remote.accepted_snapshot_id = snapshot.id
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch
      AND intent.task_id = NEW.task_id
      AND intent.accepted = 1
      AND intent.source = 'REMOTE_OBSERVATION'
      AND intent.source_id = snapshot.id
      AND intent.observed_head_sha = NEW.head_sha
      AND intent.kind = CASE NEW.kind
          WHEN 'MERGED' THEN 'COMPLETED' ELSE 'REMOTE_CLOSED' END)
BEGIN SELECT RAISE(ABORT, 'Remote terminal fact lacks exact observed Task intent'); END;

CREATE TRIGGER remote_terminal_observation_immutable
BEFORE UPDATE ON remote_terminal_observation
BEGIN SELECT RAISE(ABORT, 'Remote terminal observation is immutable'); END;

CREATE TRIGGER remote_merge_operation_success
BEFORE UPDATE OF status ON remote_merge_operation
WHEN NEW.status = 'SUCCEEDED'
  AND (NOT EXISTS (
      SELECT 1 FROM remote_terminal_observation terminal
      WHERE terminal.id = NEW.terminal_observation_id
        AND terminal.remote_development_stage_id =
            NEW.remote_development_stage_id
        AND terminal.task_id = NEW.task_id
        AND terminal.task_epoch = NEW.task_epoch
        AND terminal.stage_generation = NEW.stage_generation
        AND terminal.kind = 'MERGED'
        AND terminal.head_sha = NEW.head_sha)
    OR (NEW.mode = 'DIRECT' AND NOT EXISTS (
        SELECT 1 FROM remote_merge_effect_attempt attempt
        JOIN remote_terminal_observation terminal
          ON terminal.id = NEW.terminal_observation_id
        WHERE attempt.merge_operation_id = NEW.id
          AND attempt.ordinal = NEW.attempt_count
          AND attempt.effect_kind = 'DIRECT_MERGE'
          AND attempt.status = 'SUCCEEDED'
          AND attempt.observed_snapshot_id = terminal.remote_pr_snapshot_id))
    OR (NEW.mode = 'MERGE_QUEUE' AND NOT EXISTS (
        SELECT 1 FROM remote_merge_queue_entry entry
        JOIN remote_terminal_observation terminal
          ON terminal.id = NEW.terminal_observation_id
        WHERE entry.merge_operation_id = NEW.id
          AND entry.status = 'MERGED'
          AND entry.observed_snapshot_id = terminal.remote_pr_snapshot_id)))
BEGIN SELECT RAISE(ABORT, 'Merge completion requires observed merged truth'); END;

-- Extend typed blocker ownership for remote feedback and merge operations.
DROP TRIGGER task_blocker_owner_insert;
CREATE TRIGGER task_blocker_owner_insert
BEFORE INSERT ON task_blocker
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM tasks task
            WHERE task.id = NEW.task_id AND task.workflow_version = 'V2')
            THEN RAISE(ABORT, 'Task blocker requires a V2 Task')
        WHEN NEW.owner_kind = 'TASK'
                AND (NEW.owner_id <> NEW.task_id OR NEW.stage_id IS NOT NULL)
            THEN RAISE(ABORT, 'Task blocker Task owner is invalid')
        WHEN NEW.owner_kind = 'STAGE'
                AND (NEW.stage_id IS NULL OR NEW.owner_id <> NEW.stage_id
                    OR NOT EXISTS (
                        SELECT 1 FROM stage owner
                        WHERE owner.id = NEW.stage_id
                          AND owner.task_id = NEW.task_id))
            THEN RAISE(ABORT, 'Task blocker Stage owner is invalid')
        WHEN NEW.owner_kind = 'EPISODE'
                AND (NEW.stage_id IS NULL OR NOT EXISTS (
                    SELECT 1 FROM ci_repair_episode episode
                    WHERE episode.id = NEW.owner_id
                      AND episode.task_id = NEW.task_id
                      AND episode.remote_development_stage_id = NEW.stage_id
                    UNION ALL
                    SELECT 1 FROM branch_sync_episode episode
                    WHERE episode.id = NEW.owner_id
                      AND episode.task_id = NEW.task_id
                      AND episode.remote_development_stage_id = NEW.stage_id
                    UNION ALL
                    SELECT 1 FROM remote_feedback_batch episode
                    WHERE episode.id = NEW.owner_id
                      AND episode.task_id = NEW.task_id
                      AND episode.remote_development_stage_id = NEW.stage_id))
            THEN RAISE(ABORT, 'Task blocker Episode owner is invalid')
        WHEN NEW.owner_kind = 'OPERATION'
                AND (NEW.stage_id IS NULL OR NOT EXISTS (
                    SELECT 1 FROM remote_merge_operation operation
                    WHERE operation.id = NEW.owner_id
                      AND operation.task_id = NEW.task_id
                      AND operation.remote_development_stage_id = NEW.stage_id
                    UNION ALL
                    SELECT 1 FROM remote_mark_ready_operation operation
                    WHERE operation.id = NEW.owner_id
                      AND operation.task_id = NEW.task_id
                      AND operation.remote_development_stage_id = NEW.stage_id))
            THEN RAISE(ABORT, 'Task blocker Operation owner is invalid')
    END;
END;
