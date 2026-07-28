-- Durable owner receipt for the Local-to-Remote publish boundary. The raw
-- GitHub executor result is persisted by DispatchTicket first; this receipt
-- proves the Local Stage either accepted it or found its exact subject stale.

CREATE TABLE publish_delivery_receipt (
    operation_id       TEXT    NOT NULL PRIMARY KEY
        REFERENCES publish_operation(operation_id),
    raw_result_digest  TEXT    NOT NULL,
    outcome            TEXT    NOT NULL CHECK (outcome IN (
        'SUCCEEDED', 'FAILED', 'CANCELED')),
    acceptance         TEXT    NOT NULL CHECK (acceptance IN (
        'ACCEPTED', 'SUPERSEDED')),
    remote_stage_id    TEXT REFERENCES stage(id),
    delivered_at_ms    INTEGER NOT NULL,
    CHECK ((outcome = 'SUCCEEDED' AND acceptance = 'ACCEPTED')
        = (remote_stage_id IS NOT NULL))
);

CREATE TRIGGER publish_delivery_receipt_insert
BEFORE INSERT ON publish_delivery_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM publish_operation operation
    JOIN dispatch_ticket ticket ON ticket.operation_id = operation.operation_id
    WHERE operation.operation_id = NEW.operation_id
      AND ticket.status = 'RESULT_PENDING'
      AND ticket.pending_result_outcome = NEW.outcome
      AND ticket.pending_result_operation_id = operation.operation_id
      AND ticket.pending_result_task_epoch = operation.task_epoch
      AND ticket.pending_result_stage_id = operation.local_development_stage_id
      AND ticket.pending_result_stage_generation = operation.stage_generation
      AND ticket.pending_result_attempt = operation.semantic_attempt
      AND ticket.pending_result_expected_code_fingerprint = operation.code_fingerprint
      AND ticket.pending_result_expected_head_sha = operation.expected_head_sha
      AND ticket.pending_result_expected_base_sha = operation.expected_base_sha
      AND ((NEW.outcome = 'SUCCEEDED'
              AND operation.status = 'SUCCEEDED'
              AND EXISTS (
                  SELECT 1 FROM remote_pr_binding binding
                  WHERE binding.publish_operation_id = operation.id)
              AND ((NEW.acceptance = 'SUPERSEDED'
                      AND NEW.remote_stage_id IS NULL)
                  OR (NEW.acceptance = 'ACCEPTED'
                      AND EXISTS (
                          SELECT 1
                          FROM remote_development_stage remote
                          JOIN task_current_stage current
                            ON current.task_id = remote.task_id
                          WHERE remote.stage_id = NEW.remote_stage_id
                            AND remote.task_id = operation.task_id
                            AND current.stage_id = remote.stage_id
                            AND current.stage_generation = remote.generation))))
          OR (NEW.outcome = 'FAILED'
              AND operation.status = 'FAILED'
              AND NEW.remote_stage_id IS NULL)
          OR (NEW.outcome = 'CANCELED'
              AND operation.status = 'CANCELED'
              AND NEW.remote_stage_id IS NULL)))
BEGIN SELECT RAISE(ABORT,
    'Publish delivery receipt lacks exact operation, raw result, or owner proof'); END;

CREATE TRIGGER publish_delivery_receipt_immutable
BEFORE UPDATE ON publish_delivery_receipt
BEGIN SELECT RAISE(ABORT, 'Publish delivery receipt is immutable'); END;
