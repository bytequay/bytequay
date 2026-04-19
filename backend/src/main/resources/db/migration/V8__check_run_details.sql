-- Expand the check-run cache to carry the human-readable name and a link to
-- the details page on GitHub. Needed so the PR details screen can list
-- failing checks by name and let the user jump to the build output.
ALTER TABLE pr_check_runs ADD COLUMN name TEXT;
ALTER TABLE pr_check_runs ADD COLUMN html_url TEXT;
