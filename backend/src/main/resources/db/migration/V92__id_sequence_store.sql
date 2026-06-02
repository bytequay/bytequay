--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--

-- Per-workspace-per-day counter that drives the seq segment of the
-- new human-readable thread id format
--   ws-<slug>.t<YYMMDD>-<seq>-<rand2>
-- (see service/ids/IdGenerator).
--
-- The (workspace_id, ymd) primary key keeps the counter scope tight:
-- the value resets at UTC midnight rollover within each workspace, so
-- it stays small (rarely past two or three digits) and the resulting
-- id is short. ymd is stored as a TEXT YYMMDD string rather than a
-- DATE/Instant so the lookup is a plain equality check and the row
-- order is naturally chronological under lexicographic sort.
--
-- next_seq holds the value the next allocate call will hand out, and
-- is bumped in the same transaction as the read so a crash between
-- the read and the increment cannot re-issue a value already issued.
CREATE TABLE workspace_thread_day_seq (
    workspace_id   TEXT    NOT NULL,
    ymd            TEXT    NOT NULL,
    next_seq       INTEGER NOT NULL,
    updated_at_ms  INTEGER NOT NULL,
    PRIMARY KEY (workspace_id, ymd)
);
