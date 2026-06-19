--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--

-- Per-task "last addressed review comment" marker for the post-ship
-- address-comments loop. Records the timestamp of the newest reviewer
-- comment we've already surfaced for addressing, so the lifecycle
-- reconciler detects *new* comments on a later review round rather than
-- re-triggering on the same ones every 60s poll.
--
-- Kept in its own table (not a tasks column) because it is auxiliary
-- loop-state, not core task identity: this isolates the address-loop
-- bookkeeping from the heavily-shared tasks row. task_id mirrors the
-- TEXT task ids used elsewhere; timestamps are TEXT via the Instant
-- converter, like every other timestamp column in this schema.
CREATE TABLE task_review_addressed_marker (
    task_id TEXT PRIMARY KEY,
    last_addressed_review_at TEXT,
    internal_created_at TEXT NOT NULL,
    internal_updated_at TEXT NOT NULL
);
