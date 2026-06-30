-- A clarification an agent (trunk / brain / dev) asked the user via the
-- ask_user_question tool. Persisted so the question survives a reload and so
-- the frontend can render the amber question card from durable state. The
-- tool is non-blocking: it records the question + ends the turn; the user's
-- answer is recorded here AND posted as the next message, which the agent
-- reads on its next turn.
--
-- options_json is a JSON array of {id, label, extra?}. task_id routes the
-- answer to a task's turn (null = the thread's trunk).
CREATE TABLE agent_question (
    id                TEXT    NOT NULL PRIMARY KEY,
    thread_id         TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    task_id           TEXT,
    tool_call_id      TEXT,
    question          TEXT    NOT NULL,
    context           TEXT,
    options_json      TEXT    NOT NULL DEFAULT '[]',
    allow_free_form   INTEGER NOT NULL DEFAULT 1,
    status            TEXT    NOT NULL DEFAULT 'open',
    answer_option_id  TEXT,
    answer_free_form  TEXT,
    created_at_ms     INTEGER NOT NULL,
    answered_at_ms    INTEGER
);

CREATE INDEX idx_agent_question_thread ON agent_question(thread_id, status, created_at_ms);
