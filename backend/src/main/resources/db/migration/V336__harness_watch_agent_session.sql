-- One agent session spans a whole sync run: the picks in phase 1, every CI
-- round in phase 2, and the retrospective at merge. The cherry-pick job opens
-- it; the watch carries it on, so a compile error in the pull request is
-- diagnosed by the session that made the conflict resolution which caused it.
ALTER TABLE ci_harness_watch ADD COLUMN agent_session_id TEXT;
