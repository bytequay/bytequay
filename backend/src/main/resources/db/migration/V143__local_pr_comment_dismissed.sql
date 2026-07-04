-- The other terminal state alongside resolved_at_ms: a thread closed without
-- the agent addressing it (you dismissed it).
ALTER TABLE local_pr_comment ADD COLUMN dismissed_at_ms INTEGER;
