-- Reset PR caches and lay groundwork for incremental fetching.
--
-- Wipe every PR-related table (credentials / teams / watched_repos / app
-- settings stay intact). The next sync will repopulate from GitHub. We
-- do this because (1) earlier code paths left rows with stale shape from
-- before the `outdated`, `review_id`, etc. plumbing landed, and (2) the
-- new incremental fetch wants a clean baseline before it starts adding
-- to existing rows instead of replacing them wholesale.
DELETE FROM pr_check_runs;
DELETE FROM pr_files;
DELETE FROM pr_linked_issue;
DELETE FROM pr_review_thread_message;
DELETE FROM pr_reviews;
DELETE FROM pr_timeline;
DELETE FROM pr_detail;
DELETE FROM pull_requests;

-- Add github_id so timeline rows have a stable identity across incremental
-- fetches. UNIQUE(pr_id, github_id) lets us "INSERT OR IGNORE" on subsequent
-- syncs and have GitHub's row id be the source of truth — duplicates from
-- overlapping `since=` windows simply no-op. Nullable so we don't fail on
-- the rare timeline event that doesn't carry a stable id.
ALTER TABLE pr_timeline ADD COLUMN github_id INTEGER;
CREATE UNIQUE INDEX pr_timeline_pr_github_id_uniq
    ON pr_timeline(pr_id, github_id)
    WHERE github_id IS NOT NULL;
