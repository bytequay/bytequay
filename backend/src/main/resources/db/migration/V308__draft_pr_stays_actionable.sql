-- A draft pull request is still live on GitHub: it takes comments, reviewers,
-- title/body edits, a close, and the mark-ready flip itself. Only MERGED and
-- CLOSED are terminal, so the user-remote-action guard accepts both live
-- states instead of OPEN alone.

DROP TRIGGER v2_user_remote_action_insert_v270;

CREATE TRIGGER v2_user_remote_action_insert_v270
BEFORE INSERT ON v2_user_remote_action_v270
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM tasks task
            JOIN threads trunk ON trunk.id = task.thread_id
            JOIN remote_pr_binding binding
              ON binding.id = NEW.remote_pr_binding_id
            JOIN pr pull_request ON pull_request.id = NEW.pr_id
            JOIN remote_development_stage remote
              ON remote.stage_id = NEW.remote_stage_id
            JOIN stage owner ON owner.id = remote.stage_id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.id = remote.accepted_snapshot_id
            WHERE task.id = NEW.task_id
              AND task.workflow_version = 'V2'
              AND task.epoch = NEW.task_epoch
              AND trunk.id = task.thread_id
              AND binding.task_id = task.id
              AND binding.pr_id = pull_request.id
              AND binding.remote_repository_id = NEW.remote_repository_id
              AND binding.head_repository_id = NEW.head_repository_id
              AND binding.remote_pr_number = NEW.remote_pr_number
              AND pull_request.task_id = task.id
              AND pull_request.origin = 'task'
              AND pull_request.repo = NEW.remote_repository_id
              AND pull_request.remote_pr_number = NEW.remote_pr_number
              AND pull_request.branch_name = NEW.branch_name
              AND remote.task_id = task.id
              AND remote.generation = NEW.stage_generation
              AND owner.task_id = task.id
              AND owner.kind = 'REMOTE_DEVELOPMENT'
              AND owner.generation = remote.generation
              AND snapshot.task_id = task.id
              AND snapshot.remote_development_stage_id = remote.stage_id
              AND snapshot.stage_generation = remote.generation
              AND snapshot.remote_pr_binding_id = binding.id
              AND snapshot.head_sha = NEW.expected_head_sha
              AND snapshot.base_sha = NEW.expected_base_sha
              AND remote.current_head_sha = NEW.expected_head_sha
              AND remote.current_base_sha = NEW.expected_base_sha)
            THEN RAISE(ABORT,
                'V2 user remote action lacks its exact Task/PR/Stage subject')
        WHEN NEW.kind NOT IN (
                'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT')
          AND NOT EXISTS (
            SELECT 1
            FROM tasks task
            JOIN task_current_stage current ON current.task_id = task.id
            JOIN stage owner ON owner.id = current.stage_id
            JOIN remote_development_stage remote ON remote.stage_id = owner.id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.id = remote.accepted_snapshot_id
            WHERE task.id = NEW.task_id
              AND task.lifecycle_state = 'ACTIVE'
              AND current.stage_id = NEW.remote_stage_id
              AND current.stage_generation = NEW.stage_generation
              AND owner.completed_at_ms IS NULL
              AND remote.current_head_sha = NEW.expected_head_sha
              AND remote.current_base_sha = NEW.expected_base_sha
              AND snapshot.pr_state IN ('OPEN', 'DRAFT'))
            THEN RAISE(ABORT,
                'V2 user remote action requires the current open Remote Stage')
        WHEN NEW.kind = 'POST_TOP_LEVEL_COMMENT' AND NOT EXISTS (
            SELECT 1
            FROM tasks task
            JOIN pr pull_request ON pull_request.id = NEW.pr_id
            JOIN remote_development_stage remote
              ON remote.stage_id = NEW.remote_stage_id
            JOIN stage owner ON owner.id = remote.stage_id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.id = remote.accepted_snapshot_id
            LEFT JOIN task_current_stage current ON current.task_id = task.id
            WHERE task.id = NEW.task_id
              AND remote.task_id = task.id
              AND remote.generation = NEW.stage_generation
              AND remote.current_head_sha = NEW.expected_head_sha
              AND remote.current_base_sha = NEW.expected_base_sha
              AND snapshot.head_sha = NEW.expected_head_sha
              AND snapshot.base_sha = NEW.expected_base_sha
              AND (
                  (task.lifecycle_state = 'ACTIVE'
                    AND current.stage_id = NEW.remote_stage_id
                    AND current.stage_generation = NEW.stage_generation
                    AND owner.completed_at_ms IS NULL
                    AND snapshot.pr_state IN ('OPEN', 'DRAFT'))
                  OR
                  (pull_request.status IN ('merged', 'closed')
                    AND snapshot.pr_state = CASE pull_request.status
                        WHEN 'merged' THEN 'MERGED' ELSE 'CLOSED' END
                    AND NOT EXISTS (
                        SELECT 1
                        FROM remote_development_stage newer
                        WHERE newer.task_id = NEW.task_id
                          AND newer.generation > NEW.stage_generation))))
            THEN RAISE(ABORT,
                'V2 comment requires the current open or exact terminal PR')
        WHEN NEW.kind = 'DELETE_REMOTE_BRANCH' AND NOT EXISTS (
            SELECT 1
            FROM pr pull_request
            JOIN remote_development_stage remote
              ON remote.stage_id = NEW.remote_stage_id
            JOIN remote_pr_snapshot snapshot
              ON snapshot.id = remote.accepted_snapshot_id
            WHERE pull_request.id = NEW.pr_id
              AND pull_request.status = 'merged'
              AND snapshot.pr_state = 'MERGED'
              AND snapshot.head_sha = NEW.expected_head_sha
              AND snapshot.base_sha = NEW.expected_base_sha
              AND NOT EXISTS (
                  SELECT 1
                  FROM remote_development_stage newer
                  WHERE newer.task_id = NEW.task_id
                    AND newer.generation > NEW.stage_generation))
            THEN RAISE(ABORT,
                'V2 remote branch deletion requires preserved merged proof')
    END;
END;
