-- Brain adversarial review (plan-rail-runs.md R20-R24): a brain review is
-- modeled as a review_round with origin='brain', or as a brain-verification
-- sub-pass tacked onto an origin='external' round before its gate arms.
-- iteration/budget bound the review-fix-review loop (default budget 3).
ALTER TABLE review_round ADD COLUMN origin TEXT NOT NULL DEFAULT 'external';
ALTER TABLE review_round ADD COLUMN brain_verdict TEXT;
ALTER TABLE review_round ADD COLUMN iteration INTEGER NOT NULL DEFAULT 0;
ALTER TABLE review_round ADD COLUMN budget INTEGER NOT NULL DEFAULT 3;
