-- Phase 1 of the kanban refactor (docs/design/kanban-refactor.md).
-- These columns let the redesigned kanban derive every column / banner from
-- a single list fetch instead of hitting the per-PR detail endpoint per card.
--
-- All columns default NULL so existing rows survive untouched until the next
-- sync touches them — at which point the populator either reads the value
-- from the GitHub list response (state/closedAt/mergedAt) or copies it out
-- of the detail blob we already cache (mergeable, mergeableState,
-- headPushedAt, reviewerVerdicts).
--
-- reviewer_verdicts is a JSON map of login -> state, e.g.
-- {"alice":"APPROVED","bob":"CHANGES_REQUESTED"}, persisted via the existing
-- StringMapConverter the same way label_colors already is.

ALTER TABLE pull_requests ADD COLUMN state VARCHAR;
ALTER TABLE pull_requests ADD COLUMN closed_at TEXT;
ALTER TABLE pull_requests ADD COLUMN merged_at TEXT;
ALTER TABLE pull_requests ADD COLUMN mergeable INTEGER;
ALTER TABLE pull_requests ADD COLUMN mergeable_state VARCHAR;
ALTER TABLE pull_requests ADD COLUMN head_pushed_at TEXT;
ALTER TABLE pull_requests ADD COLUMN reviewer_verdicts TEXT;
