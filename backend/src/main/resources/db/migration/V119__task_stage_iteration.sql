-- Loop iterations within a monitor stage. In the async-turn model one
-- monitor-enqueued turn IS one iteration: the row is created when a driver
-- enqueues the turn and closed when that turn finishes. summary_text is
-- filled by the record_iteration_summary tool (or a synthetic placeholder
-- when the agent never records one).
--
-- TEXT uuid ids + epoch-ms timestamps, matching the rest of the schema.
-- turn_id binds the iteration to its monitor turn; summary_request_turn_id
-- binds the one follow-up turn that solicits the summary.
CREATE TABLE task_stage_iteration (
    id                      TEXT    NOT NULL PRIMARY KEY,
    stage_id                TEXT    NOT NULL REFERENCES task_stage(id) ON DELETE CASCADE,
    task_id                 TEXT    NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    turn_id                 TEXT    NOT NULL,           -- the monitor turn this iteration tracks
    iteration_number        INTEGER NOT NULL,           -- 1, 2, 3... within the stage
    trigger                 TEXT    NOT NULL,           -- red_ci | new_comments | budget_exhausted_resume
    started_at_ms           INTEGER NOT NULL,
    ended_at_ms             INTEGER,                    -- null while in-progress
    ended_reason            TEXT,                       -- push_completed | failed | needs_attention
    summary_text            TEXT,                       -- null until a summary is recorded (<=280 chars, enforced in code)
    summarized_at_ms        INTEGER,
    summary_request_turn_id TEXT,                       -- the follow-up turn soliciting the summary
    CONSTRAINT task_stage_iteration_number_unique UNIQUE (stage_id, iteration_number)
);

CREATE INDEX idx_iter_stage_started ON task_stage_iteration(stage_id, started_at_ms);
CREATE INDEX idx_iter_task_started ON task_stage_iteration(task_id, started_at_ms);
CREATE INDEX idx_iter_turn ON task_stage_iteration(turn_id);
CREATE INDEX idx_iter_summary_request_turn ON task_stage_iteration(summary_request_turn_id);
