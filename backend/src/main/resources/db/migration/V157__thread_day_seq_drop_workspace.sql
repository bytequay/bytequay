--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--

-- Thread ids no longer embed the workspace slug (see IdGenerator), so
-- the counter driving their seq segment no longer needs to be scoped
-- by workspace either -- one global per-day counter is enough now
-- that the id's whole tail is just <ymd>-<seq>-<rand2>. workspace_id
-- was part of the primary key, so the table is recreated rather than
-- altered; the old per-workspace counts aren't worth preserving --
-- losing them just means today's counter restarts at 1, and existing
-- thread ids (which still carry their old "ws-<slug>." prefix) can't
-- collide with new ones (which don't).
DROP TABLE workspace_thread_day_seq;

CREATE TABLE thread_day_seq (
    ymd            TEXT    PRIMARY KEY,
    next_seq       INTEGER NOT NULL,
    updated_at_ms  INTEGER NOT NULL
);
