-- Fingerprinted, leased ownership for validation passes. A claim names
-- what one pass validates (task + initiator context + optional round /
-- local-review submission scope + the exact code fingerprint); the
-- owner/lease columns let exactly one executor run it while checks stay
-- outside any database transaction, and the cancel/supersede columns are
-- the durable primitives the stop reconcilers act on.
ALTER TABLE validation_pass ADD COLUMN claim_key TEXT;
ALTER TABLE validation_pass ADD COLUMN context TEXT;
ALTER TABLE validation_pass ADD COLUMN round_id TEXT;
ALTER TABLE validation_pass ADD COLUMN code_fingerprint TEXT;
ALTER TABLE validation_pass ADD COLUMN through_sequence INTEGER;
ALTER TABLE validation_pass ADD COLUMN root_set_digest TEXT;
ALTER TABLE validation_pass ADD COLUMN cancel_requested_at_ms INTEGER;
ALTER TABLE validation_pass ADD COLUMN cancel_deadline_at_ms INTEGER;
ALTER TABLE validation_pass ADD COLUMN cancel_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE validation_pass ADD COLUMN superseded_at_ms INTEGER;
ALTER TABLE validation_pass ADD COLUMN owner_id TEXT;
ALTER TABLE validation_pass ADD COLUMN executor_identity TEXT;
ALTER TABLE validation_pass ADD COLUMN lease_until_ms INTEGER;
ALTER TABLE validation_pass ADD COLUMN heartbeat_at_ms INTEGER;

CREATE UNIQUE INDEX validation_pass_claim_idx
    ON validation_pass(claim_key) WHERE claim_key IS NOT NULL;
