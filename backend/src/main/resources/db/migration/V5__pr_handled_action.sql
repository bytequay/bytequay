-- Action the user took when a PR was handled: APPROVED, MERGED, COMMENTED,
-- CHANGES_REQUESTED, DISMISSED, or MANUAL (hover-click "Handled" button).
-- Null means the PR has not been handled yet.
ALTER TABLE pr_view_state ADD COLUMN handled_action TEXT;
