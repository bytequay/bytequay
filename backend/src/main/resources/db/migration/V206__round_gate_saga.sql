-- Durable authorization and ordered external effects for posting a review
-- round. The task remains at AWAITING_REMOTE_REVIEW until every effect has
-- completion evidence and the final round/run/task handoff commits.

CREATE TABLE round_gate_authorization (
    token              TEXT    NOT NULL PRIMARY KEY,
    task_id            TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    round_id           TEXT    NOT NULL REFERENCES response_round(id) ON DELETE CASCADE,
    gate_revision      INTEGER NOT NULL,
    attempt            INTEGER NOT NULL,
    actor              TEXT    NOT NULL,
    code_fingerprint   TEXT    NOT NULL,
    payload_json       TEXT    NOT NULL,
    payload_digest     TEXT    NOT NULL,
    effect_keys_json   TEXT    NOT NULL,
    approved_at_ms     INTEGER NOT NULL,
    revoked_at_ms      INTEGER,
    consumed_at_ms     INTEGER,
    outcome            TEXT
);

CREATE UNIQUE INDEX round_gate_authorization_one_active
    ON round_gate_authorization(round_id)
    WHERE revoked_at_ms IS NULL AND consumed_at_ms IS NULL;

CREATE TABLE round_gate_effect (
    id                  INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    token               TEXT    NOT NULL REFERENCES round_gate_authorization(token) ON DELETE CASCADE,
    effect_key          TEXT    NOT NULL,
    status              TEXT    NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    attempt_limit       INTEGER NOT NULL,
    first_claimed_at_ms INTEGER,
    last_claimed_at_ms  INTEGER,
    claim_owner         TEXT,
    lease_until_ms      INTEGER,
    last_error_class    TEXT,
    last_error          TEXT,
    next_attempt_at_ms  INTEGER,
    evidence_json       TEXT,
    completed_at_ms     INTEGER,
    UNIQUE(token, effect_key)
);

CREATE INDEX round_gate_effect_recovery
    ON round_gate_effect(status, next_attempt_at_ms, lease_until_ms);
