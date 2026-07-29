-- Existing LEGACY rows remain immutable historical input.  New work must be
-- created through the typed TaskCreationHandoff, which writes V2 explicitly.
CREATE TRIGGER legacy_task_creation_retired
BEFORE INSERT ON tasks
WHEN NEW.workflow_version <> 'V2'
BEGIN
    SELECT RAISE(ABORT, 'LEGACY Task creation is retired');
END;
