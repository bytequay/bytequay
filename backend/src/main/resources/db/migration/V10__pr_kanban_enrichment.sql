-- Surfaces the data the new Kanban cards display directly on each pr row,
-- so we don't have to fetch pr_detail just to render a list. The sync job
-- populates these from the same detail fetch it already does.
--
-- See docs/design/settings-redesign.md §3 Phase B for the rationale.
ALTER TABLE pull_requests ADD COLUMN ci_status TEXT;
ALTER TABLE pull_requests ADD COLUMN comment_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pull_requests ADD COLUMN additions INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pull_requests ADD COLUMN deletions INTEGER NOT NULL DEFAULT 0;

-- attention_reason is one of: CI_FAILING | MENTIONED | BLOCKING | STALE
-- (NULL when the PR isn't promoted to "Needs attention"). See AttentionReason.java.
ALTER TABLE pull_requests ADD COLUMN attention_reason TEXT;
