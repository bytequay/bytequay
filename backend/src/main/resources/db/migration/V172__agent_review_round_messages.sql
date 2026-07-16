-- User guidance and the commit list shown for an AgentReview round must be
-- durable parts of the frozen round aggregate.
ALTER TABLE review_round ADD COLUMN message_gate_open INTEGER NOT NULL DEFAULT 1;
ALTER TABLE review_round ADD COLUMN lifecycle_finalized INTEGER NOT NULL DEFAULT 1;

UPDATE review_round
SET message_gate_open = 0
WHERE status <> 'RUNNING';

CREATE TABLE review_round_message (
    id              TEXT    NOT NULL PRIMARY KEY,
    round_id        TEXT    NOT NULL REFERENCES review_round(id) ON DELETE CASCADE,
    assignment_id   TEXT             REFERENCES review_assignment(id) ON DELETE SET NULL,
    target          TEXT    NOT NULL,
    sender          TEXT    NOT NULL,
    body            TEXT    NOT NULL,
    status          TEXT    NOT NULL,
    response        TEXT,
    created_at_ms   INTEGER NOT NULL,
    completed_at_ms INTEGER
);
CREATE INDEX idx_review_round_message_pending
    ON review_round_message(round_id, status, created_at_ms);
CREATE UNIQUE INDEX idx_review_round_message_assignment
    ON review_round_message(assignment_id)
    WHERE assignment_id IS NOT NULL;

CREATE TABLE review_round_commit (
    round_id TEXT    NOT NULL REFERENCES review_round(id) ON DELETE CASCADE,
    sha      TEXT    NOT NULL,
    message  TEXT    NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (round_id, sha),
    UNIQUE (round_id, position)
);

-- Planner messages are real bounded turns with a planner-specific persona,
-- even when they reuse the active panel's configured provider.
INSERT OR IGNORE INTO reviewer_def
    (id, name, description, runner, runner_json, persona, eligible_kinds, enabled)
VALUES
    ('review-planner', 'Review planner', 'Replans the active frozen review scope',
     'api', '{}',
     'Act as the review planner. Re-evaluate objectives, hypotheses, and coverage; do not impersonate an investigator or verifier.',
     '["trivial","standard","high-risk"]', 1);
