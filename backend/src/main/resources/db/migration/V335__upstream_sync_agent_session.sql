-- One agent session per run, resumed across attempts and across picks.
--
-- A repair is rarely independent of the ones before it: the fork's conventions,
-- the shape of upstream's refactor, and what a previous attempt already tried
-- are all context the next conflict needs. Starting a fresh session per attempt
-- threw that away and paid to rebuild it from the prompt every time.
ALTER TABLE upstream_cherry_pick_job ADD COLUMN agent_session_id TEXT;
