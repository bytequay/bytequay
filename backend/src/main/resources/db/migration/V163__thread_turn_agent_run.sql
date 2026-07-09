ALTER TABLE thread_turns ADD COLUMN agent_run_id TEXT;

CREATE INDEX idx_thread_turns_agent_run ON thread_turns(agent_run_id);
