-- One-shot quick reviews belong to the unified PR aggregate. Keep the
-- legacy integer pr_id for old review/publish flows while new quick-review
-- rows use this UUID-style identifier.
ALTER TABLE pr_review_draft ADD COLUMN unified_pr_id TEXT;

CREATE INDEX pr_review_draft_unified_pr_id_idx
    ON pr_review_draft(unified_pr_id);
