-- Old versions permitted one task id on multiple backlog rows. Do not choose
-- which historic link to keep silently: stop the upgrade with a diagnostic
-- column name so the duplicate rows can be repaired deliberately.
CREATE TEMP TABLE v197_backlog_link_preflight (
    duplicate_linked_task_id_must_be_repaired INTEGER NOT NULL
);

INSERT INTO v197_backlog_link_preflight
SELECT NULL
FROM backlog_item
WHERE linked_task_id IS NOT NULL
GROUP BY linked_task_id
HAVING COUNT(*) > 1;

DROP TABLE v197_backlog_link_preflight;

-- A task is cut from at most one backlog item. Service validation gives callers
-- a useful 409; this index is the race-condition and legacy-write backstop.
CREATE UNIQUE INDEX idx_backlog_item_linked_task
    ON backlog_item(linked_task_id)
    WHERE linked_task_id IS NOT NULL;

-- A stale whole-row save must not erase or move a link after the atomic task
-- cut wins. Normal resolved -> shipped/closed reconciliation keeps both the
-- task id and resolved timestamp and therefore remains allowed.
CREATE TRIGGER protect_resolved_backlog_link
BEFORE UPDATE ON backlog_item
WHEN OLD.linked_task_id IS NOT NULL
    AND (
        (
            NEW.linked_task_id IS NOT OLD.linked_task_id
            AND NOT (
                NEW.linked_task_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM tasks WHERE id = OLD.linked_task_id
                )
            )
        )
        OR NEW.resolved_at_ms IS NULL
        OR NEW.status NOT IN ('resolved', 'shipped', 'closed')
    )
BEGIN
    SELECT RAISE(ABORT, 'resolved_backlog_link_is_immutable');
END;
