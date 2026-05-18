-- Manual archives (user-initiated, not driven by a tag rule) also get
-- a row in email_tag_archive_log so the Archived left-nav view shows
-- them — previously the log was only written from EmailService's
-- tag-driven archiveSweep. tag_id is now optional because manual
-- archives have no rule to attribute them to.
--
-- SQLite has no ALTER COLUMN, so we recreate the table preserving
-- existing rows and the index.

CREATE TABLE email_tag_archive_log_new (
    account_email       TEXT    NOT NULL,
    gmail_thread_id     TEXT    NOT NULL,
    tag_id              TEXT,
    subject             TEXT,
    from_addr           TEXT,
    snippet             TEXT,
    received_at_ms      INTEGER NOT NULL,
    archived_at_ms      INTEGER NOT NULL,
    PRIMARY KEY (account_email, gmail_thread_id)
);

INSERT INTO email_tag_archive_log_new
    (account_email, gmail_thread_id, tag_id, subject, from_addr, snippet, received_at_ms, archived_at_ms)
SELECT account_email, gmail_thread_id, tag_id, subject, from_addr, snippet, received_at_ms, archived_at_ms
FROM email_tag_archive_log;

DROP TABLE email_tag_archive_log;

ALTER TABLE email_tag_archive_log_new RENAME TO email_tag_archive_log;

CREATE INDEX idx_email_tag_archive_log_account_archived
    ON email_tag_archive_log(account_email, archived_at_ms DESC);
