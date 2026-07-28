-- DispatchTicket is the durable correctness state. This exact outbox record
-- is only a committed, restart-safe hint to the delivery-only dispatcher.

CREATE UNIQUE INDEX idx_outbox_v2_dispatch_ticket_wake
    ON outbox(aggregate_id)
    WHERE aggregate_kind = 'DISPATCH_TICKET';

CREATE TRIGGER outbox_v2_dispatch_ticket_wake_insert
BEFORE INSERT ON outbox
WHEN NEW.aggregate_kind = 'DISPATCH_TICKET'
  OR NEW.topic = 'V2_DISPATCH_TICKET_REQUESTED'
  OR NEW.id GLOB 'V2_DISPATCH_TICKET_REQUESTED:*'
  OR NEW.dedup_key GLOB 'V2_DISPATCH_TICKET_REQUESTED:*'
BEGIN
    SELECT CASE WHEN
        NEW.id <> 'V2_DISPATCH_TICKET_REQUESTED:' || NEW.aggregate_id
        OR NEW.dedup_key <> NEW.id
        OR NEW.aggregate_kind <> 'DISPATCH_TICKET'
        OR NEW.topic <> 'V2_DISPATCH_TICKET_REQUESTED'
        OR NEW.payload <> NEW.aggregate_id
        OR length(NEW.aggregate_id) = 0
        OR NEW.status <> 'PENDING'
        OR NEW.attempts <> 0
        OR NEW.available_at_ms <> NEW.created_at_ms
        OR NEW.claim_owner IS NOT NULL
        OR NEW.lease_until_ms IS NOT NULL
        OR NEW.delivered_at_ms IS NOT NULL
        OR NEW.last_error IS NOT NULL
        OR NOT EXISTS (
            SELECT 1 FROM dispatch_ticket ticket
            WHERE ticket.id = NEW.aggregate_id
              AND ticket.status = 'REQUESTED'
              AND ticket.created_at_ms = NEW.created_at_ms)
    THEN RAISE(ABORT, 'V2 DispatchTicket wake is not exact') END;
END;

CREATE TRIGGER outbox_v2_dispatch_ticket_wake_update
BEFORE UPDATE ON outbox
WHEN OLD.aggregate_kind = 'DISPATCH_TICKET'
BEGIN
    SELECT CASE WHEN
        NEW.id <> OLD.id
        OR NEW.dedup_key <> OLD.dedup_key
        OR NEW.aggregate_kind <> OLD.aggregate_kind
        OR NEW.aggregate_id <> OLD.aggregate_id
        OR NEW.topic <> OLD.topic
        OR NEW.payload <> OLD.payload
        OR NEW.created_at_ms <> OLD.created_at_ms
        OR NEW.id <> 'V2_DISPATCH_TICKET_REQUESTED:' || NEW.aggregate_id
        OR NEW.dedup_key <> NEW.id
        OR NEW.topic <> 'V2_DISPATCH_TICKET_REQUESTED'
        OR NEW.payload <> NEW.aggregate_id
        OR NOT (
        (OLD.status = 'PENDING'
         AND NEW.status = 'CLAIMED'
         AND NEW.attempts = OLD.attempts + 1
         AND NEW.available_at_ms >= OLD.available_at_ms
         AND NEW.claim_owner IS NOT NULL
         AND length(NEW.claim_owner) > 0
         AND NEW.lease_until_ms > NEW.available_at_ms
         AND NEW.delivered_at_ms IS NULL
         AND NEW.last_error IS NULL)
        OR
        (OLD.status = 'CLAIMED'
         AND NEW.status = 'CLAIMED'
         AND NEW.attempts = OLD.attempts + 1
         AND NEW.available_at_ms >= OLD.lease_until_ms
         AND NEW.claim_owner IS NOT NULL
         AND length(NEW.claim_owner) > 0
         AND NEW.lease_until_ms > NEW.available_at_ms
         AND NEW.delivered_at_ms IS NULL
         AND NEW.last_error IS NULL)
        OR
        (OLD.status = 'CLAIMED'
         AND NEW.status = 'DELIVERED'
         AND NEW.attempts = OLD.attempts
         AND NEW.available_at_ms = OLD.available_at_ms
         AND NEW.claim_owner IS NULL
         AND NEW.lease_until_ms IS NULL
         AND NEW.delivered_at_ms >= OLD.available_at_ms
         AND NEW.last_error IS NULL)
        )
    THEN RAISE(ABORT, 'V2 DispatchTicket wake state evidence is invalid') END;
END;

-- Every newly requested V2 ticket commits its one advisory wake in the same
-- transaction. Domain code may also call the idempotent store explicitly.
CREATE TRIGGER dispatch_ticket_requested_wake_insert
AFTER INSERT ON dispatch_ticket
WHEN NEW.status = 'REQUESTED'
BEGIN
    INSERT INTO outbox(
        id, dedup_key, aggregate_kind, aggregate_id, topic, payload,
        status, attempts, available_at_ms, claim_owner, lease_until_ms,
        created_at_ms, delivered_at_ms, last_error)
    VALUES (
        'V2_DISPATCH_TICKET_REQUESTED:' || NEW.id,
        'V2_DISPATCH_TICKET_REQUESTED:' || NEW.id,
        'DISPATCH_TICKET', NEW.id, 'V2_DISPATCH_TICKET_REQUESTED', NEW.id,
        'PENDING', 0, NEW.created_at_ms, NULL, NULL,
        NEW.created_at_ms, NULL, NULL);
END;

-- Preserve every still-eligible request, including validation/publish records
-- from partial deployments and the exact V229 provision bundle.
INSERT INTO outbox(
    id, dedup_key, aggregate_kind, aggregate_id, topic, payload,
    status, attempts, available_at_ms, claim_owner, lease_until_ms,
    created_at_ms, delivered_at_ms, last_error)
SELECT
    'V2_DISPATCH_TICKET_REQUESTED:' || ticket.id,
    'V2_DISPATCH_TICKET_REQUESTED:' || ticket.id,
    'DISPATCH_TICKET', ticket.id, 'V2_DISPATCH_TICKET_REQUESTED', ticket.id,
    'PENDING', 0, ticket.created_at_ms, NULL, NULL,
    ticket.created_at_ms, NULL, NULL
FROM dispatch_ticket ticket
WHERE ticket.status = 'REQUESTED'
  AND NOT EXISTS (
    SELECT 1 FROM outbox wake
    WHERE wake.dedup_key = 'V2_DISPATCH_TICKET_REQUESTED:' || ticket.id);

-- Supplemental to V229: a creation receipt is valid only after the exact
-- provision ticket wake has joined the same transaction.
CREATE TRIGGER task_creation_receipt_dispatch_wake_insert
BEFORE INSERT ON task_creation_receipt
WHEN NOT EXISTS (
    SELECT 1
    FROM outbox wake
    JOIN dispatch_ticket ticket ON ticket.id = wake.aggregate_id
    WHERE ticket.id = NEW.dispatch_ticket_id
      AND ticket.operation_id = NEW.operation_id
      AND ticket.status = 'REQUESTED'
      AND wake.id = 'V2_DISPATCH_TICKET_REQUESTED:' || ticket.id
      AND wake.dedup_key = wake.id
      AND wake.aggregate_kind = 'DISPATCH_TICKET'
      AND wake.aggregate_id = ticket.id
      AND wake.topic = 'V2_DISPATCH_TICKET_REQUESTED'
      AND wake.payload = ticket.id
      AND wake.status = 'PENDING'
      AND wake.attempts = 0
      AND wake.available_at_ms = ticket.created_at_ms
      AND wake.created_at_ms = ticket.created_at_ms
      AND wake.claim_owner IS NULL
      AND wake.lease_until_ms IS NULL
      AND wake.delivered_at_ms IS NULL
      AND wake.last_error IS NULL)
BEGIN
    SELECT RAISE(ABORT, 'Task creation receipt lacks exact DispatchTicket wake');
END;
