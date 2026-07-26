-- Durable response-round coordination checkpoints. Status values stay in
-- their existing lower-case representation; only the application boundary
-- changes from raw strings to ReviewRoundState.
ALTER TABLE response_round ADD COLUMN paused_from TEXT;
ALTER TABLE response_round ADD COLUMN code_fingerprint TEXT;
ALTER TABLE response_round ADD COLUMN enqueue_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE response_round ADD COLUMN kick_attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE response_round ADD COLUMN gate_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE response_round ADD COLUMN active_gate_token TEXT;
ALTER TABLE response_round ADD COLUMN closed_at_ms INTEGER;

-- Do not let the partial index fail later with an opaque constraint error.
-- Multiple live coordinators have no safe automatic winner: fail the
-- upgrade visibly so guided recovery can inspect the exact historical rows.
CREATE TEMP TABLE response_round_coordinator_preflight (marker INTEGER);
CREATE TEMP TRIGGER response_round_coordinator_preflight_guard
BEFORE INSERT ON response_round_coordinator_preflight
WHEN EXISTS (
    SELECT 1
    FROM response_round
    WHERE status IN ('triaging', 'addressing', 'awaiting_gate', 'paused')
    GROUP BY task_id
    HAVING COUNT(*) > 1
)
BEGIN
    SELECT RAISE(ABORT, 'duplicate coordinator response rounds require guided recovery');
END;
INSERT INTO response_round_coordinator_preflight(marker) VALUES (1);
DROP TRIGGER response_round_coordinator_preflight_guard;
DROP TABLE response_round_coordinator_preflight;

-- A gated timestamp is authoritative evidence that a legacy paused round
-- was waiting for approval. Other PAUSED rows remain deliberately
-- ambiguous (paused_from NULL) and cannot be auto-resumed.
UPDATE response_round
SET paused_from = 'awaiting_gate'
WHERE status = 'paused'
  AND gated_at_ms IS NOT NULL
  AND posted_at_ms IS NULL;

-- A task has one coordinator-owned response round at a time. Posted history
-- does not block the next external reviewer batch.
CREATE UNIQUE INDEX idx_response_round_one_coordinator
    ON response_round(task_id)
    WHERE status IN ('triaging', 'addressing', 'awaiting_gate', 'paused');
