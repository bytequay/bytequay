-- Production cutover metadata for immutable V2 Task creation. The display
-- fields remain compatibility data, but they are frozen by the same Trunk
-- authorization that owns the Task assignment and policy.

ALTER TABLE trunk_task_creation_authorization ADD COLUMN task_name TEXT;
ALTER TABLE trunk_task_creation_authorization ADD COLUMN task_type TEXT;
ALTER TABLE trunk_task_creation_authorization ADD COLUMN linked_issue_number INTEGER;
ALTER TABLE trunk_task_creation_authorization ADD COLUMN opening_prompt TEXT;
ALTER TABLE trunk_task_creation_authorization ADD COLUMN task_origin TEXT;

ALTER TABLE task_creation_context ADD COLUMN task_name TEXT;
ALTER TABLE task_creation_context ADD COLUMN task_type TEXT;
ALTER TABLE task_creation_context ADD COLUMN linked_issue_number INTEGER;
ALTER TABLE task_creation_context ADD COLUMN opening_prompt TEXT;
ALTER TABLE task_creation_context ADD COLUMN task_origin TEXT;

CREATE TRIGGER v2_task_creation_authorization_presentation_insert
BEFORE INSERT ON trunk_task_creation_authorization
WHEN NEW.task_name IS NULL OR length(trim(NEW.task_name)) = 0
  OR NEW.task_type IS NULL OR length(trim(NEW.task_type)) = 0
  OR NEW.task_origin IS NULL OR length(trim(NEW.task_origin)) = 0
  OR (NEW.linked_issue_number IS NOT NULL AND NEW.linked_issue_number < 1)
  OR (NEW.opening_prompt IS NOT NULL AND length(trim(NEW.opening_prompt)) = 0)
BEGIN SELECT RAISE(ABORT, 'V2 Task presentation must be exact'); END;

CREATE TRIGGER v2_task_creation_context_presentation_insert
BEFORE INSERT ON task_creation_context
WHEN NOT EXISTS (
    SELECT 1
    FROM tasks task
    JOIN trunk_task_creation_authorization authorization
      ON authorization.id = NEW.authorization_id
    WHERE task.id = NEW.task_id
      AND task.workflow_version = 'V2'
      AND task.assignment_id = NEW.assignment_id
      AND task.policy_revision_id = NEW.policy_revision_id
      AND task.name = NEW.task_name
      AND task.task_type = NEW.task_type
      AND task.linked_issue_number IS NEW.linked_issue_number
      AND task.opening_prompt IS NEW.opening_prompt
      AND task.origin = NEW.task_origin
      AND authorization.assignment_id = NEW.assignment_id
      AND authorization.policy_revision_id = NEW.policy_revision_id
      AND authorization.task_name = NEW.task_name
      AND authorization.task_type = NEW.task_type
      AND authorization.linked_issue_number IS NEW.linked_issue_number
      AND authorization.opening_prompt IS NEW.opening_prompt
      AND authorization.task_origin = NEW.task_origin)
BEGIN SELECT RAISE(ABORT, 'V2 Task presentation differs from Trunk authority'); END;

CREATE TRIGGER v2_task_creation_context_presentation_immutable
BEFORE UPDATE OF task_name, task_type, linked_issue_number, opening_prompt, task_origin
ON task_creation_context
WHEN NEW.task_name IS NOT OLD.task_name
  OR NEW.task_type IS NOT OLD.task_type
  OR NEW.linked_issue_number IS NOT OLD.linked_issue_number
  OR NEW.opening_prompt IS NOT OLD.opening_prompt
  OR NEW.task_origin IS NOT OLD.task_origin
BEGIN SELECT RAISE(ABORT, 'V2 Task presentation is immutable'); END;

-- Aggregate versioning protects lifecycle commands. Ordinary title/model and
-- accounting updates on a V2 Trunk are metadata, not lifecycle transitions;
-- they must not be forced to masquerade as a TrunkManager command.
DROP TRIGGER v2_trunk_version_monotonic;
CREATE TRIGGER v2_trunk_version_monotonic
BEFORE UPDATE OF lifecycle_state, aggregate_version ON threads
WHEN OLD.turn_version = 'V2'
  AND (NEW.lifecycle_state IS NOT OLD.lifecycle_state
       OR NEW.aggregate_version IS NOT OLD.aggregate_version)
  AND NEW.aggregate_version <> OLD.aggregate_version + 1
BEGIN
    SELECT RAISE(ABORT, 'V2 trunk aggregate version must advance once');
END;
