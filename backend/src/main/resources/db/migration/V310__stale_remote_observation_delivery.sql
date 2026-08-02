-- Preserve provider-successful Remote observation evidence when the Task
-- epoch advanced before delivery. The old epoch is forbidden from inserting
-- a RemotePrSnapshot, so its exact result terminates SUPERSEDED with a digest
-- only instead of remaining RESULT_PENDING forever.

DROP TRIGGER remote_observation_delivery_receipt_immutable;
DROP TRIGGER remote_observation_delivery_receipt_insert;

ALTER TABLE remote_observation_delivery_receipt
    RENAME TO remote_observation_delivery_receipt_v309;

CREATE TABLE remote_observation_delivery_receipt (
    remote_observation_operation_id TEXT NOT NULL PRIMARY KEY
        REFERENCES remote_observation_operation(id) ON DELETE CASCADE,
    operation_id       TEXT    NOT NULL UNIQUE,
    raw_outcome        TEXT    NOT NULL CHECK (raw_outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED', 'INDETERMINATE')),
    raw_result_digest  TEXT    NOT NULL CHECK (length(raw_result_digest) = 64),
    acceptance         TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    snapshot_id        TEXT REFERENCES remote_pr_snapshot(id),
    ci_evaluation_id   TEXT REFERENCES remote_ci_evaluation(id),
    recorded_at_ms     INTEGER NOT NULL,
    CHECK ((raw_outcome = 'SUCCEEDED'
            AND (snapshot_id IS NOT NULL OR acceptance = 'SUPERSEDED'))
        OR (raw_outcome <> 'SUCCEEDED' AND snapshot_id IS NULL)),
    CHECK (ci_evaluation_id IS NULL OR snapshot_id IS NOT NULL)
);

INSERT INTO remote_observation_delivery_receipt(
    remote_observation_operation_id, operation_id, raw_outcome,
    raw_result_digest, acceptance, snapshot_id, ci_evaluation_id,
    recorded_at_ms)
SELECT remote_observation_operation_id, operation_id, raw_outcome,
       raw_result_digest, acceptance, snapshot_id, ci_evaluation_id,
       recorded_at_ms
FROM remote_observation_delivery_receipt_v309;

DROP TABLE remote_observation_delivery_receipt_v309;

CREATE TRIGGER remote_observation_delivery_receipt_immutable
BEFORE UPDATE ON remote_observation_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Remote observation delivery receipt is immutable'); END;

CREATE TRIGGER remote_observation_delivery_receipt_insert
BEFORE INSERT ON remote_observation_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM remote_observation_operation operation
    JOIN dispatch_ticket ticket ON ticket.operation_id = operation.operation_id
    WHERE operation.id = NEW.remote_observation_operation_id
      AND operation.operation_id = NEW.operation_id
      AND operation.status IN ('ACCEPTED', 'SUPERSEDED', 'FAILED', 'CANCELED')
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.raw_outcome
      AND (NEW.snapshot_id IS NOT NULL
        OR NEW.raw_outcome <> 'SUCCEEDED'
        OR (NEW.acceptance = 'SUPERSEDED'
          AND operation.status = 'CANCELED'
          AND operation.snapshot_id IS NULL
          AND operation.ci_evaluation_id IS NULL))
      AND (NEW.snapshot_id IS NULL OR operation.snapshot_id = NEW.snapshot_id)
      AND (NEW.ci_evaluation_id IS NULL
          OR operation.ci_evaluation_id = NEW.ci_evaluation_id))
BEGIN SELECT RAISE(ABORT, 'Remote observation delivery receipt is not exact'); END;
