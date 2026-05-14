-- Per-PR merge-queue entry state, sourced from the GraphQL
-- `pullRequest.mergeQueueEntry { state }` field (REST doesn't expose
-- this). Null when the PR isn't in the queue, or when the repo
-- doesn't have a merge queue at all. Powers the "Queued" status
-- pill on the PR detail page — same affordance github.com renders.
--
-- Existing rows stay null until the next detail refresh re-fetches.

ALTER TABLE pr_detail
    ADD COLUMN merge_queue_state TEXT;
