-- Local mirror of Gmail messages so the inbox list renders without
-- a round trip to Google on every nav. Populated by EmailSyncService
-- on first connect (full inbox crawl) and refreshed by the 60s
-- polling job via users.history.list deltas.
--
-- account_email matches the credential row's instance_name (the
-- connected Gmail address). Treated as throwaway cache: every row
-- can be reconstructed from Gmail, so a future schema change can
-- drop and rebuild rather than migrate in place.

-- One row per Gmail message. We aggregate to threads at query time
-- via a window function so the source of truth stays per-message
-- (which matches Gmail's history.list event grain).
CREATE TABLE email_messages (
    account_email     TEXT    NOT NULL,
    gmail_message_id  TEXT    NOT NULL,
    gmail_thread_id   TEXT    NOT NULL,
    from_addr         TEXT,
    subject           TEXT,
    snippet           TEXT,
    received_at_ms    INTEGER NOT NULL,
    is_unread         INTEGER NOT NULL DEFAULT 0,
    is_in_inbox       INTEGER NOT NULL DEFAULT 1,
    cached_at_ms      INTEGER NOT NULL,
    PRIMARY KEY (account_email, gmail_message_id)
);

-- Inbox aggregation reads scan by (account, in_inbox, received_at desc);
-- thread expansion reads join by (account, thread_id).
CREATE INDEX idx_email_messages_inbox
    ON email_messages(account_email, is_in_inbox, received_at_ms DESC);
CREATE INDEX idx_email_messages_thread
    ON email_messages(account_email, gmail_thread_id);

-- Per-account sync watermark. last_history_id seeds the next
-- users.history.list call; null = full re-sync needed.
CREATE TABLE email_account_sync (
    account_email    TEXT NOT NULL PRIMARY KEY,
    last_history_id  TEXT,
    last_sync_at_ms  INTEGER
);
