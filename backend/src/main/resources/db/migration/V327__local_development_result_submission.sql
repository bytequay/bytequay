-- A Local Development Turn reports its result by calling
-- record_development_result, not by formatting JSON into its final message.
-- The submission lands here at tool-call time; delivery reads it instead of
-- parsing prose, so a Turn can no longer be discarded for how it phrased
-- its last sentence. One row per Turn: the primary key is the guarantee.
CREATE TABLE stage_turn_development_submission (
    stage_turn_id       TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage_turn(id) ON DELETE CASCADE,
    operation_id        TEXT    NOT NULL UNIQUE,
    task_id             TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    implemented_intent  TEXT    NOT NULL,
    commit_summary      TEXT    NOT NULL,
    file_summary        TEXT    NOT NULL,
    validation_summary  TEXT    NOT NULL,
    known_risks         TEXT    NOT NULL,
    unresolved_concerns TEXT    NOT NULL,
    context_refs        TEXT    NOT NULL,
    pr_description      TEXT    NOT NULL,
    submitted_at_ms     INTEGER NOT NULL
);

-- A submission is the Turn's reported result: once accepted it is evidence,
-- and a correction has to arrive as a new Turn rather than an overwrite.
CREATE TRIGGER stage_turn_development_submission_immutable
BEFORE UPDATE ON stage_turn_development_submission
BEGIN SELECT RAISE(ABORT, 'Development submission is immutable'); END;
