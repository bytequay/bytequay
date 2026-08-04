-- A Development Brain review reports its verdict by calling
-- record_development_verdict, not by formatting JSON into its final message.
-- The Brain reads code and forms an opinion; asking it to also be a JSON
-- formatter is what turned a completed review into a parked Task when it
-- wrote prose instead. One row per Turn: the primary key is the guarantee.
CREATE TABLE task_turn_brain_verdict (
    task_turn_id  TEXT    NOT NULL PRIMARY KEY REFERENCES task_turn(id) ON DELETE CASCADE,
    operation_id  TEXT    NOT NULL UNIQUE,
    task_id       TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    verdict       TEXT    NOT NULL CHECK (verdict IN ('APPROVED', 'CHANGES_REQUESTED')),
    summary       TEXT    NOT NULL,
    findings_json TEXT    NOT NULL,
    submitted_at_ms INTEGER NOT NULL,
    -- The two rules the verdict has always carried, now held by the database
    -- rather than by four copies of the same Java check.
    CHECK ((verdict = 'APPROVED' AND findings_json = '[]')
        OR (verdict = 'CHANGES_REQUESTED' AND findings_json <> '[]'))
);

CREATE TRIGGER task_turn_brain_verdict_immutable
BEFORE UPDATE ON task_turn_brain_verdict
BEGIN SELECT RAISE(ABORT, 'Brain verdict is immutable'); END;
