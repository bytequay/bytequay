-- Immutable, machine-readable backlog provenance. Existing broad source /
-- creator fields remain for display and compatibility; origin records the
-- creation path once so later tag edits cannot rewrite history.
ALTER TABLE backlog_item ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'
    CHECK (origin IN ('user', 'agent', 'issue-monitor', 'quality-scan'));

UPDATE backlog_item
SET origin = CASE
    WHEN source = 'agent'
            AND json_valid(tags_json)
            AND EXISTS (SELECT 1 FROM json_each(tags_json) WHERE value = 'quality-scan')
        THEN 'quality-scan'
    WHEN source = 'agent'
            AND json_valid(tags_json)
            AND EXISTS (SELECT 1 FROM json_each(tags_json) WHERE value = 'bytequay-intake')
        THEN 'issue-monitor'
    WHEN source = 'agent' OR created_by IN ('agent', 'trunk-agent')
        THEN 'agent'
    ELSE 'user'
END;

CREATE TRIGGER backlog_item_origin_immutable
BEFORE UPDATE OF origin ON backlog_item
WHEN NEW.origin <> OLD.origin
BEGIN
    SELECT RAISE(ABORT, 'backlog item origin is immutable');
END;
