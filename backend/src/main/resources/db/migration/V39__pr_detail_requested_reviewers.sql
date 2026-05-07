-- Persist the actual list of pending reviewers on the PR detail row,
-- not just the count. The frontend's reviewer sidebar surfaces each
-- pending login (with a re-request button); without the names it can
-- only render the stale list-page snapshot, which can miss reviewers
-- requested between syncs. Stored as a comma-separated TEXT blob via
-- StringListConverter; null on legacy pre-V39 rows.
ALTER TABLE pr_detail ADD COLUMN requested_reviewers TEXT;
