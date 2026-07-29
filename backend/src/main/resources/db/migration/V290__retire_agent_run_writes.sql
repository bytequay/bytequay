-- AgentRun is immutable history after the V2 execution cutover.  The only
-- remaining insert is an inert, already-terminal header required by the
-- historical investigation-review foreign keys.  ReviewAssignmentTurn and
-- its typed Operation own the actual execution and lifecycle.

CREATE TRIGGER agent_run_insert_retired_v290
BEFORE INSERT ON agent_run
WHEN NOT (
    NEW.task_id IS NULL
    AND NEW.kind = 'review_compatibility_header'
    AND NEW.source = 'v2_review_assignment_turn_fk'
    AND NEW.parent_stage_id IS NULL
    AND NEW.review_round_id IS NOT NULL
    AND length(trim(NEW.review_round_id)) > 0
    AND NEW.stage_id IS NULL
    AND NEW.status = 'succeeded'
    AND NEW.iterations = 0
    AND (NEW.budget IS NULL OR NEW.budget >= 0)
    AND NEW.headline IS NULL
    AND NEW.metrics_json IS NULL
    AND NEW.finished_at_ms = NEW.started_at_ms
    AND NEW.workspace_id IS NULL
    AND NEW.thread_id IS NULL
    AND NEW.provider IS NULL
    AND NEW.model IS NULL
    AND NEW.cost_usd_milli = 0
    AND NEW.tokens_in = 0
    AND NEW.tokens_out = 0
    AND NEW.step_cursor = 0
    AND NEW.launch_input IS NULL
    AND NEW.pause_reason IS NULL
    AND NEW.outcome = 'completed'
)
BEGIN SELECT RAISE(ABORT,
    'AgentRun creation is retired; only an inert review compatibility header is allowed'); END;

CREATE TRIGGER agent_run_update_retired_v290
BEFORE UPDATE ON agent_run
BEGIN SELECT RAISE(ABORT, 'AgentRun rows are immutable after V2 cutover'); END;
