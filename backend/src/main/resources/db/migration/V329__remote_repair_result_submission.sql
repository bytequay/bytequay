-- A Remote repair StageTurn reports its result by calling a result tool, not
-- by formatting JSON into its final message. Same reasoning as the Local
-- Development Turn: the repair is real work already committed to the worktree,
-- and discarding it because the last sentence opened with prose throws away the
-- work, not the formatting. One row per Turn: the primary key is the guarantee.
--
-- Two shapes share one table because one is the other plus a list. A CI or
-- branch-conflict repair reports a summary; a Remote feedback repair reports a
-- summary and the reply drafts it prepared for the user gate. Their tools are
-- distinct, so a Turn cannot report the wrong shape by accident, and a repair
-- that has no replies stores the empty array rather than NULL.
CREATE TABLE stage_turn_repair_submission (
    stage_turn_id   TEXT    NOT NULL PRIMARY KEY
        REFERENCES stage_turn(id) ON DELETE CASCADE,
    operation_id    TEXT    NOT NULL UNIQUE,
    task_id         TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    summary         TEXT    NOT NULL CHECK (length(trim(summary)) > 0),
    replies_json    TEXT    NOT NULL CHECK (
        json_valid(replies_json) AND json_type(replies_json) = 'array'),
    submitted_at_ms INTEGER NOT NULL CHECK (submitted_at_ms >= 0)
);

-- A submission is the Turn's reported result: once accepted it is evidence,
-- and a correction has to arrive as a new Turn rather than an overwrite.
CREATE TRIGGER stage_turn_repair_submission_immutable
BEFORE UPDATE ON stage_turn_repair_submission
BEGIN SELECT RAISE(ABORT, 'Remote repair submission is immutable'); END;
