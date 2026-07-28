-- Result delivery is durable infrastructure work, but it must not consume a
-- workflow capacity lane or an executing-Task lease. V223 represented
-- execution claims directly on dispatch_ticket. Keep that representation for
-- EXECUTE and RECONCILE, and use this exact, separately-leased claim for a
-- RESULT_PENDING callback.

CREATE TABLE dispatch_delivery_claim (
    ticket_id          TEXT    NOT NULL PRIMARY KEY
        REFERENCES dispatch_ticket(id) ON DELETE CASCADE,
    ticket_version     INTEGER NOT NULL CHECK (ticket_version >= 0),
    claim_owner        TEXT    NOT NULL,
    claimed_at_ms      INTEGER NOT NULL,
    heartbeat_at_ms    INTEGER NOT NULL,
    expires_at_ms      INTEGER NOT NULL,
    CHECK (heartbeat_at_ms >= claimed_at_ms),
    CHECK (expires_at_ms > heartbeat_at_ms)
);

CREATE INDEX idx_dispatch_delivery_claim_expiry
    ON dispatch_delivery_claim(expires_at_ms);

CREATE TRIGGER dispatch_delivery_claim_insert_fence
BEFORE INSERT ON dispatch_delivery_claim
WHEN NOT EXISTS (
    SELECT 1
    FROM dispatch_ticket d
    WHERE d.id = NEW.ticket_id
      AND d.version = NEW.ticket_version
      AND d.status = 'RESULT_PENDING'
      AND d.pending_result_outcome IS NOT NULL
      AND d.claim_owner IS NULL
      AND d.capacity_lease_id IS NULL)
BEGIN
    SELECT RAISE(ABORT, 'delivery claim requires the exact result-pending ticket version');
END;

CREATE TRIGGER dispatch_delivery_claim_identity_immutable
BEFORE UPDATE OF ticket_id, ticket_version, claim_owner, claimed_at_ms
ON dispatch_delivery_claim
WHEN NEW.ticket_id IS NOT OLD.ticket_id
  OR NEW.ticket_version IS NOT OLD.ticket_version
  OR NEW.claim_owner IS NOT OLD.claim_owner
  OR NEW.claimed_at_ms IS NOT OLD.claimed_at_ms
BEGIN
    SELECT RAISE(ABORT, 'delivery claim identity is immutable');
END;

CREATE TRIGGER dispatch_delivery_claim_heartbeat_shape
BEFORE UPDATE OF heartbeat_at_ms, expires_at_ms ON dispatch_delivery_claim
WHEN NEW.heartbeat_at_ms < OLD.heartbeat_at_ms
  OR NEW.expires_at_ms < OLD.expires_at_ms
  OR NEW.expires_at_ms <= NEW.heartbeat_at_ms
BEGIN
    SELECT RAISE(ABORT, 'delivery claim heartbeat must advance with a future expiry');
END;

-- Completion or retry releases the exact delivery claim first in the same
-- transaction. This prevents an expired worker from completing a ticket after
-- a replacement delivery worker has claimed the same durable result.
CREATE TRIGGER dispatch_ticket_delivery_claim_guard
BEFORE UPDATE ON dispatch_ticket
WHEN OLD.status = 'RESULT_PENDING'
  AND EXISTS (
      SELECT 1 FROM dispatch_delivery_claim c WHERE c.ticket_id = OLD.id)
BEGIN
    SELECT RAISE(ABORT, 'release the exact delivery claim before changing its ticket');
END;
