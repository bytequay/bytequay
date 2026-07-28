-- Keep LEGACY workflow writers from acquiring authority over a V2 Task.
-- The V2 aggregate tables remain the only lifecycle source; old tables stay
-- readable for historical LEGACY Tasks throughout the drain window.

CREATE TRIGGER v2_task_reject_legacy_authority_update
BEFORE UPDATE OF
    status, phase, process_pid, log_path, agent_session_id,
    pr_number, pr_state, ci_state, linked_pr_number, pushed_at_ms,
    ended_at_ms, error_message, current_liveness_turn_id,
    paused_status, resume_requested_at_ms, recovery_phase,
    recovery_context_json, recovery_request_id, recovery_requested_kind,
    recovery_request_payload_json, recovery_requested_at_ms,
    consecutive_auto_pushes, merge_notification_sent_at_ms,
    ready_gate_sent_at_ms, merge_authorized_at_ms, merge_queue_retries,
    pending_completion_summary_turn_id, auto_approve, auto_merge,
    min_approvals, agenda_json, opening_prompt
ON tasks
WHEN OLD.workflow_version = 'V2' AND (
    NEW.status IS NOT OLD.status
    OR NEW.phase IS NOT OLD.phase
    OR NEW.process_pid IS NOT OLD.process_pid
    OR NEW.log_path IS NOT OLD.log_path
    OR NEW.agent_session_id IS NOT OLD.agent_session_id
    OR NEW.pr_number IS NOT OLD.pr_number
    OR NEW.pr_state IS NOT OLD.pr_state
    OR NEW.ci_state IS NOT OLD.ci_state
    OR NEW.linked_pr_number IS NOT OLD.linked_pr_number
    OR NEW.pushed_at_ms IS NOT OLD.pushed_at_ms
    OR NEW.ended_at_ms IS NOT OLD.ended_at_ms
    OR NEW.error_message IS NOT OLD.error_message
    OR NEW.current_liveness_turn_id IS NOT OLD.current_liveness_turn_id
    OR NEW.paused_status IS NOT OLD.paused_status
    OR NEW.resume_requested_at_ms IS NOT OLD.resume_requested_at_ms
    OR NEW.recovery_phase IS NOT OLD.recovery_phase
    OR NEW.recovery_context_json IS NOT OLD.recovery_context_json
    OR NEW.recovery_request_id IS NOT OLD.recovery_request_id
    OR NEW.recovery_requested_kind IS NOT OLD.recovery_requested_kind
    OR NEW.recovery_request_payload_json IS NOT OLD.recovery_request_payload_json
    OR NEW.recovery_requested_at_ms IS NOT OLD.recovery_requested_at_ms
    OR NEW.consecutive_auto_pushes IS NOT OLD.consecutive_auto_pushes
    OR NEW.merge_notification_sent_at_ms IS NOT OLD.merge_notification_sent_at_ms
    OR NEW.ready_gate_sent_at_ms IS NOT OLD.ready_gate_sent_at_ms
    OR NEW.merge_authorized_at_ms IS NOT OLD.merge_authorized_at_ms
    OR NEW.merge_queue_retries IS NOT OLD.merge_queue_retries
    OR NEW.pending_completion_summary_turn_id IS NOT OLD.pending_completion_summary_turn_id
    OR NEW.auto_approve IS NOT OLD.auto_approve
    OR NEW.auto_merge IS NOT OLD.auto_merge
    OR NEW.min_approvals IS NOT OLD.min_approvals
    OR NEW.agenda_json IS NOT OLD.agenda_json
    OR NEW.opening_prompt IS NOT OLD.opening_prompt)
BEGIN SELECT RAISE(ABORT, 'LEGACY Task authority cannot mutate a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_stage_insert
BEFORE INSERT ON task_stage
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY Stage cannot own a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_stage_update
BEFORE UPDATE ON task_stage
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = OLD.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY Stage cannot mutate a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_stage_event_insert
BEFORE INSERT ON task_stage_event
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY Stage event cannot target a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_turn_insert
BEFORE INSERT ON thread_turns
WHEN (NEW.task_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2'))
  OR (NEW.scope = 'TRUNK' AND NEW.task_id IS NULL AND EXISTS (
        SELECT 1 FROM threads WHERE id = NEW.thread_id AND turn_version = 'V2')
     )
BEGIN SELECT RAISE(ABORT, 'LEGACY Turn cannot target a V2 owner'); END;

CREATE TRIGGER v2_task_reject_legacy_turn_update
BEFORE UPDATE ON thread_turns
WHEN (OLD.task_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM tasks WHERE id = OLD.task_id AND workflow_version = 'V2'))
  OR (OLD.scope = 'TRUNK' AND OLD.task_id IS NULL AND EXISTS (
        SELECT 1 FROM threads WHERE id = OLD.thread_id AND turn_version = 'V2')
     )
BEGIN SELECT RAISE(ABORT, 'LEGACY Turn cannot mutate a V2 owner'); END;

CREATE TRIGGER v2_task_reject_legacy_agent_run_insert
BEFORE INSERT ON agent_run
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY AgentRun cannot target a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_agent_run_update
BEFORE UPDATE ON agent_run
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = OLD.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY AgentRun cannot mutate a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_phase_event_insert
BEFORE INSERT ON task_phase_event
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY phase event cannot target a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_status_event_insert
BEFORE INSERT ON task_status_event
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY status event cannot target a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_review_comment_insert
BEFORE INSERT ON review_comment
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY review comment cannot target a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_branch_guard_insert
BEFORE INSERT ON branch_guard
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY BranchGuard cannot target a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_push_authorization_insert
BEFORE INSERT ON task_push_authorization
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY push authorization cannot target a V2 Task'); END;

CREATE TRIGGER v2_task_reject_legacy_round_gate_insert
BEFORE INSERT ON round_gate_authorization
WHEN EXISTS (SELECT 1 FROM tasks WHERE id = NEW.task_id AND workflow_version = 'V2')
BEGIN SELECT RAISE(ABORT, 'LEGACY round gate cannot target a V2 Task'); END;

-- One read-only surface for drain/canary diagnostics. It never drives state.
CREATE VIEW development_flow_task_route AS
SELECT
    task.id AS task_id,
    task.thread_id AS trunk_id,
    trunk.workspace_id,
    task.workflow_version,
    task.lifecycle_state,
    task.epoch,
    task.aggregate_version,
    current.stage_id,
    current.stage_generation
FROM tasks task
JOIN threads trunk ON trunk.id = task.thread_id
LEFT JOIN task_current_stage current ON current.task_id = task.id;
