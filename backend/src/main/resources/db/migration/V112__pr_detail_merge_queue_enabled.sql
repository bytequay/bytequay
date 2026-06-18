--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--

-- Whether the PR's base branch has a GitHub merge queue configured, sourced
-- from the GraphQL `pullRequest.mergeQueue { id }` field (non-null id => true).
-- Distinct from merge_queue_state, which is the PR's merge-queue ENTRY state
-- (only set when the PR is already queued). True here means "it's possible to
-- add this PR to a merge queue".
--
-- Existing rows stay null until the next detail refresh re-fetches.

ALTER TABLE pr_detail ADD COLUMN merge_queue_enabled BOOLEAN;
