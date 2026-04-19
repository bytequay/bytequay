-- Review-thread resolution state — only exposed via GitHub's GraphQL
-- API, never via REST. Stored on the thread root row so the UI can
-- render the "Resolved" pill from the cached detail without a
-- network round-trip, and so the resolve / unresolve mutations can
-- look up the GraphQL node id without re-querying.
--
-- graphql_node_id is the opaque base64 id GraphQL uses ("PRRT_…"),
-- joined back to the REST root by databaseId in the GraphQL query.
-- resolved is a 3-state: NULL = unknown (legacy rows / not yet
-- fetched), 0 = open, 1 = resolved.
ALTER TABLE pr_review_thread_message ADD COLUMN graphql_node_id VARCHAR;
ALTER TABLE pr_review_thread_message ADD COLUMN resolved BOOLEAN;
