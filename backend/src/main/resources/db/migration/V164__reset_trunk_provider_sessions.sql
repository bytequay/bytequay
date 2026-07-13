-- The trunk role changed from an execution-capable conversational identity
-- to a strictly planning-only identity. Provider resume sessions retain their
-- old system context, so restarting the app alone would keep reviving the old
-- developer responsibility. Drop only trunk-level CLI resume ids; task agent
-- sessions live on tasks.agent_session_id and are intentionally untouched.
UPDATE threads
   SET agent_session_id = NULL
 WHERE kind = 'CLI_AGENT'
   AND agent_session_id IS NOT NULL;
