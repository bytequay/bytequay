--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--

-- Who set a turn in motion. Stamped at enqueue and carried for the
-- turn's life so the tool-approval gate can tell whether a human is
-- watching: an unattended turn (the CI auto-fix coordinator) has no
-- one to click Allow, so an out-of-bounds request escalates instead
-- of stalling on a prompt.
--
--   initiator_attended  1 when a person drove the turn, 0 when an
--                       automated trigger did
--   initiator_source    names the trigger ('user', 'auto-fix-ci-fail')
--
-- Existing rows predate automation, so they backfill to an attended
-- user turn.
ALTER TABLE thread_turns ADD COLUMN initiator_attended INTEGER NOT NULL DEFAULT 1;
ALTER TABLE thread_turns ADD COLUMN initiator_source TEXT NOT NULL DEFAULT 'user';
