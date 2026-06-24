-- Per-thread backlog: a JIRA-like parking lot of future-work items the
-- user sits on until they decide to cut a task from one. "Start
-- development" appends the item (title + body as the seed prompt) to the
-- thread's task queue and stamps started_at_ms; linked_task_id is set
-- when the queued entry materialises immediately on an idle thread (it
-- stays NULL when the entry is queued behind a running task).
--
-- tags_json carries a JSON array of strings (SQLite has no array type).
CREATE TABLE backlog_item (
    id              TEXT    NOT NULL PRIMARY KEY,
    thread_id       TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    title           TEXT    NOT NULL,
    body            TEXT    NOT NULL DEFAULT '',
    tags_json       TEXT    NOT NULL DEFAULT '[]',
    created_at_ms   INTEGER NOT NULL,
    started_at_ms   INTEGER,
    linked_task_id  TEXT    REFERENCES tasks(id) ON DELETE SET NULL
);

CREATE INDEX idx_backlog_item_thread ON backlog_item(thread_id, created_at_ms);
