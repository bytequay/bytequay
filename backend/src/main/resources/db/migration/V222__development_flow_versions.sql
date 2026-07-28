-- Route each Task through one workflow implementation for its entire lifetime.
-- Trunks may switch future Turn creation independently once runtime routing lands.
ALTER TABLE tasks ADD COLUMN workflow_version TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (workflow_version IN ('LEGACY', 'V2'));

ALTER TABLE threads ADD COLUMN turn_version TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (turn_version IN ('LEGACY', 'V2'));

CREATE TRIGGER task_workflow_version_immutable
BEFORE UPDATE OF workflow_version ON tasks
WHEN NEW.workflow_version IS NOT OLD.workflow_version
BEGIN
    SELECT RAISE(ABORT, 'task workflow version is immutable');
END;
