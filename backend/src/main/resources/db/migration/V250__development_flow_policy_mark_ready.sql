-- Initial green CI may mark a Draft ready unless the current policy explicitly
-- keeps it Draft. This action is independent of auto-approve/auto-merge.

DROP TRIGGER remote_mark_ready_authorization_insert;

CREATE TRIGGER remote_mark_ready_authorization_insert
BEFORE INSERT ON remote_mark_ready_authorization
WHEN NEW.status <> 'ACTIVE'
  OR NOT EXISTS (
    SELECT 1 FROM remote_development_stage remote
    JOIN tasks task ON task.id = remote.task_id
    JOIN remote_pr_snapshot snapshot ON snapshot.id = NEW.remote_pr_snapshot_id
    JOIN remote_ci_evaluation ci ON ci.id = NEW.ci_evaluation_id
    JOIN task_automation_policy policy ON policy.id = NEW.automation_policy_id
    WHERE remote.stage_id = NEW.remote_development_stage_id
      AND remote.task_id = NEW.task_id
      AND remote.generation = NEW.stage_generation
      AND remote.accepted_snapshot_id = snapshot.id
      AND remote.current_head_sha = NEW.head_sha
      AND remote.current_base_sha = NEW.base_sha
      AND task.epoch = NEW.task_epoch
      AND snapshot.remote_development_stage_id = remote.stage_id
      AND snapshot.task_id = NEW.task_id
      AND snapshot.stage_generation = NEW.stage_generation
      AND snapshot.head_sha = NEW.head_sha
      AND snapshot.base_sha = NEW.base_sha
      AND snapshot.pr_state = 'DRAFT'
      AND ci.remote_pr_snapshot_id = snapshot.id
      AND ci.head_sha = NEW.head_sha
      AND ci.base_sha = NEW.base_sha
      AND ci.policy_outcome = 'ACCEPTED'
      AND policy.task_id = NEW.task_id
      AND policy.revision = (
          SELECT MAX(current_policy.revision)
          FROM task_automation_policy current_policy
          WHERE current_policy.task_id = NEW.task_id)
      AND (NEW.authority_kind = 'MANUAL'
        OR (policy.keep_draft = 0
            AND policy.stewardship_exception = 0)))
BEGIN SELECT RAISE(ABORT, 'Mark-ready authorization lacks current Draft and green exact-head proof'); END;
