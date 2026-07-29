-- A Trunk route switch must wait for live Trunk-owned turns, but Task and
-- Stage siblings keep their immutable workflow version and may drain while
-- the parent Trunk accepts new V2 work.
DROP TRIGGER trunk_turn_version_quiescent;

CREATE TRIGGER trunk_turn_version_quiescent
BEFORE UPDATE OF turn_version ON threads
WHEN NEW.turn_version IS NOT OLD.turn_version
  AND (
      EXISTS (
          SELECT 1 FROM thread_turns legacy
          WHERE legacy.thread_id = OLD.id
            AND legacy.task_id IS NULL
            AND (legacy.scope = 'TRUNK' OR legacy.scope IS NULL)
            AND legacy.status IN ('QUEUED', 'RUNNING'))
      OR EXISTS (
          SELECT 1 FROM thread_turn typed
          WHERE typed.trunk_id = OLD.id
            AND typed.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')))
BEGIN
    SELECT RAISE(ABORT, 'Trunk Turn version can change only while quiescent');
END;
