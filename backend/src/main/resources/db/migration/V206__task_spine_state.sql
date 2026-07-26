-- The task spine's durable state: stop/recovery checkpoints on tasks,
-- the current-liveness turn pointer, an explicit turn-liveness flag and
-- idempotent kick key on turns, a status audit trail, and the durable
-- local-review submission rows that own the submitted-review loop.

ALTER TABLE tasks ADD COLUMN paused_status TEXT;
ALTER TABLE tasks ADD COLUMN recovery_phase TEXT;
ALTER TABLE tasks ADD COLUMN recovery_context_json TEXT;
ALTER TABLE tasks ADD COLUMN resume_requested_at_ms INTEGER;
ALTER TABLE tasks ADD COLUMN recovery_request_id TEXT;
ALTER TABLE tasks ADD COLUMN recovery_requested_kind TEXT;
ALTER TABLE tasks ADD COLUMN recovery_request_payload_json TEXT;
ALTER TABLE tasks ADD COLUMN recovery_requested_at_ms INTEGER;
ALTER TABLE tasks ADD COLUMN legacy_recovery_kind TEXT;
ALTER TABLE tasks ADD COLUMN legacy_recovery_requested_at_ms INTEGER;
ALTER TABLE tasks ADD COLUMN current_liveness_turn_id TEXT;

-- The Java backfill migration (V202) classifies existing task-owned
-- turns from persisted coordinator/run-role evidence; new rows must
-- always state liveness explicitly, so the column is NOT NULL with a
-- safe default of 0 for the historical rows this ALTER touches.
ALTER TABLE thread_turns ADD COLUMN affects_task_liveness INTEGER NOT NULL DEFAULT 0;
ALTER TABLE thread_turns ADD COLUMN kick_key TEXT;
CREATE UNIQUE INDEX thread_turns_kick_key_idx
    ON thread_turns(kick_key) WHERE kick_key IS NOT NULL;

CREATE TABLE task_status_event (
    id             INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    task_id        TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    from_status    TEXT    NOT NULL,
    to_status      TEXT    NOT NULL,
    actor          TEXT    NOT NULL,
    reason         TEXT,
    occurred_at_ms INTEGER NOT NULL
);
CREATE INDEX task_status_event_task_idx ON task_status_event(task_id, occurred_at_ms);

ALTER TABLE pr ADD COLUMN local_review_epoch INTEGER NOT NULL DEFAULT 0;

CREATE TABLE local_review_submission (
    id                    TEXT    NOT NULL PRIMARY KEY,
    timeline_event_id     TEXT    UNIQUE,
    task_id               TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    pr_id                 TEXT    NOT NULL,
    agent_run_id          TEXT,
    submission_seq        INTEGER NOT NULL,
    root_ids_json         TEXT    NOT NULL,
    root_snapshot_json    TEXT    NOT NULL,
    submitted_through_ms  INTEGER NOT NULL,
    addressed_through_ms  INTEGER,
    attempt               INTEGER NOT NULL DEFAULT 0,
    failures              INTEGER NOT NULL DEFAULT 0,
    created_at_ms         INTEGER NOT NULL,
    activated_at_ms       INTEGER,
    completed_at_ms       INTEGER,
    canceled_at_ms        INTEGER,
    cancel_reason         TEXT
);
CREATE UNIQUE INDEX local_review_submission_seq_idx
    ON local_review_submission(task_id, submission_seq);

CREATE TABLE local_review_brain_handoff (
    validation_claim_key TEXT    NOT NULL PRIMARY KEY,
    task_id              TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    through_sequence     INTEGER NOT NULL,
    code_fingerprint     TEXT    NOT NULL,
    created_at_ms        INTEGER NOT NULL,
    consumed_at_ms       INTEGER,
    delivery_failures    INTEGER NOT NULL DEFAULT 0
);
