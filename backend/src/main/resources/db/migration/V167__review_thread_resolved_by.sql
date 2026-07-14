-- Login of whoever resolved a review thread, from GitHub's GraphQL
-- `reviewThreads.resolvedBy`. Stored on the thread root row alongside
-- `resolved` so the diff can render the "X marked this conversation as
-- resolved" attribution from the cached detail without a network round-trip.
-- NULL when the thread is open or only a REST pass has populated the row.
ALTER TABLE pr_review_thread_message ADD COLUMN resolved_by VARCHAR;
