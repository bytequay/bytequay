-- Per-thread passive signal feed (the Notifications tab). Distinct from
-- the actionable `notifications` gate table: these rows are inert agent /
-- system / github signals the user can browse, with no
-- UNREAD→RESOLVING→RESOLVED lifecycle. read_at_ms flips when the user
-- opens the row.
CREATE TABLE thread_signal (
    id            TEXT    NOT NULL PRIMARY KEY,
    thread_id     TEXT    NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    task_id       TEXT    REFERENCES tasks(id) ON DELETE SET NULL,
    source_kind   TEXT    NOT NULL CHECK (source_kind IN ('agent', 'system', 'github')),
    icon_kind     TEXT    NOT NULL CHECK (icon_kind IN ('info', 'success', 'warn', 'alert')),
    title         TEXT    NOT NULL,
    body          TEXT,
    source_url    TEXT,
    created_at_ms INTEGER NOT NULL,
    read_at_ms    INTEGER
);

CREATE INDEX idx_thread_signal_thread ON thread_signal(thread_id, created_at_ms);
