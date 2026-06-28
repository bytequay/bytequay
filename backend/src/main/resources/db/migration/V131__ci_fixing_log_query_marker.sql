--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--

-- Per-task "last queried the CI-fixing log" marker for the get_new_updated_
-- ci_fixing_log read tool. The Comments-addressing stage agent polls that
-- tool to see what the CI-fixing loop did since it last looked; this records
-- the timestamp of the newest CI-fixing iteration summary it has already
-- seen, so a later call returns only genuinely newer summaries (or an empty
-- result when nothing changed).
--
-- Kept in its own table (not a tasks column) because it is auxiliary tool
-- state, not core task identity. task_id mirrors the TEXT task ids used
-- elsewhere; timestamps are TEXT via the Instant converter, like every other
-- timestamp column in this schema.
CREATE TABLE ci_fixing_log_query_marker (
    task_id TEXT PRIMARY KEY,
    last_queried_at TEXT,
    internal_created_at TEXT NOT NULL,
    internal_updated_at TEXT NOT NULL
);
