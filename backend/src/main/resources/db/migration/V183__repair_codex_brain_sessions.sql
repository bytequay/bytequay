-- Codex-configured brain threads were previously dispatched through Claude
-- Code. Their saved session ids therefore cannot be resumed by Codex, and a
-- missing Codex model was incorrectly represented by the Claude Haiku default.
-- Preserve the conversation while forcing the corrected agent to start a
-- fresh provider-native session with its configured model (or CLI default).
UPDATE threads
   SET agent_session_id = NULL,
       model = COALESCE(json_extract(work_model_json, '$.model'), '')
 WHERE kind = 'BRAIN_AGENT'
   AND provider = 'codex'
   AND json_extract(work_model_json, '$.kind') = 'CLI'
   AND json_extract(work_model_json, '$.agentOrProvider') = 'codex';
