-- V172 shipped before live rounds were marked for startup reconciliation.
UPDATE review_round
SET lifecycle_finalized = 0
WHERE status IN ('QUEUED', 'RUNNING');
