-- Durable authorization and effect cursor for the first local task push.
-- The task remains at AWAITING_PUSH until both external effects have durable
-- completion evidence and the final handoff commits.

CREATE TABLE task_push_authorization (
    token             TEXT    NOT NULL PRIMARY KEY,
    task_id           TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    pr_id             TEXT    NOT NULL,
    run_id            TEXT,
    head_sha          TEXT    NOT NULL,
    code_fingerprint  TEXT    NOT NULL,
    actor              TEXT    NOT NULL,
    basis_kind         TEXT    NOT NULL,
    basis_id           TEXT,
    override_reason    TEXT,
    payload_json       TEXT    NOT NULL,
    payload_digest     TEXT    NOT NULL,
    created_at_ms      INTEGER NOT NULL,
    revoked_at_ms      INTEGER,
    consumed_at_ms     INTEGER,
    outcome            TEXT
);

CREATE UNIQUE INDEX task_push_authorization_one_active
    ON task_push_authorization(task_id)
    WHERE revoked_at_ms IS NULL AND consumed_at_ms IS NULL;

CREATE TABLE task_push_effect (
    id                 INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    token              TEXT    NOT NULL REFERENCES task_push_authorization(token) ON DELETE CASCADE,
    effect_key         TEXT    NOT NULL,
    status             TEXT    NOT NULL,
    attempts           INTEGER NOT NULL DEFAULT 0,
    attempt_limit      INTEGER NOT NULL,
    first_claimed_at_ms INTEGER,
    last_claimed_at_ms INTEGER,
    claim_owner        TEXT,
    lease_until_ms     INTEGER,
    last_error_class   TEXT,
    last_error         TEXT,
    next_attempt_at_ms INTEGER,
    evidence_json      TEXT,
    completed_at_ms    INTEGER,
    UNIQUE(token, effect_key)
);

CREATE INDEX task_push_effect_recovery
    ON task_push_effect(status, next_attempt_at_ms, lease_until_ms);
