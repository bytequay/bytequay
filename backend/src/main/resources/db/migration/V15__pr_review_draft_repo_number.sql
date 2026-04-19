-- AI review used to assume the target PR was already in the local
-- pull_requests table and looked up its (repo, number) from there. That
-- table only holds the user's own + review-requested PRs, so running
-- review on any other PR (watched-repo browse, team filter) failed with
-- a 404. Persist repo + number on the draft itself so the run path is
-- self-sufficient and the publish path doesn't need the local-DB lookup
-- either.
--
-- Both columns are nullable for backfill purposes — older rows backfill
-- via JOIN, but if the originating PR has since dropped out of
-- pull_requests we leave it NULL rather than guessing. The publish path
-- prefers the stored values and falls back to the local lookup if both
-- are NULL.
ALTER TABLE pr_review_draft ADD COLUMN repo TEXT;
ALTER TABLE pr_review_draft ADD COLUMN pr_number INTEGER;

UPDATE pr_review_draft
   SET repo = (SELECT pr.repo   FROM pull_requests pr WHERE pr.id = pr_review_draft.pr_id),
       pr_number = (SELECT pr.number FROM pull_requests pr WHERE pr.id = pr_review_draft.pr_id)
 WHERE EXISTS (SELECT 1 FROM pull_requests pr WHERE pr.id = pr_review_draft.pr_id);
