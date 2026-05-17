-- Subject-based classification rules ("tags") for the email surface.
-- Each tag matches a case-insensitive substring against an inbox
-- thread's display subject and assigns one of three actions:
--   FOCUS   — pass through; clickable in the left nav as a saved view
--   ARCHIVE — remove INBOX label on Gmail, record in archive log
--   IGNORE  — local-only hide from the app (consistent with mute-sender)
--
-- Precedence between matching rules is fixed in code (ignore > focus >
-- archive), so no per-rule ordering is stored. Per-account scope to
-- match email_muted_senders.

CREATE TABLE email_tags (
    id                  TEXT    NOT NULL PRIMARY KEY,
    account_email       TEXT    NOT NULL,
    name                TEXT    NOT NULL,
    subject_contains    TEXT    NOT NULL,
    action              TEXT    NOT NULL CHECK (action IN ('FOCUS', 'ARCHIVE', 'IGNORE')),
    created_at_ms       INTEGER NOT NULL,
    updated_at_ms       INTEGER NOT NULL
);

CREATE INDEX idx_email_tags_account
    ON email_tags(account_email);

-- Audit log of tag-driven Gmail archives. One row per
-- (account, gmail_thread_id) — the thread is no longer in Gmail's
-- INBOX after the archive, so this is what the app's "Archived" view
-- reads to render rows without re-querying Gmail. Reopening a thread
-- from the Archived view still fetches messages live via the existing
-- thread-detail path.

CREATE TABLE email_tag_archive_log (
    account_email       TEXT    NOT NULL,
    gmail_thread_id     TEXT    NOT NULL,
    tag_id              TEXT    NOT NULL,
    subject             TEXT,
    from_addr           TEXT,
    snippet             TEXT,
    received_at_ms      INTEGER NOT NULL,
    archived_at_ms      INTEGER NOT NULL,
    PRIMARY KEY (account_email, gmail_thread_id)
);

CREATE INDEX idx_email_tag_archive_log_account_archived
    ON email_tag_archive_log(account_email, archived_at_ms DESC);
