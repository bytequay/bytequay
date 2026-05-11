-- Per-account client-side mute list. Inboxes filter out threads whose
-- latest sender matches a row here so recurring noise (newsletters,
-- system notifications, etc.) can be hidden without round-tripping to
-- Gmail to set up a server-side filter. Local-mode-only by design: the
-- list doesn't propagate to gmail.com or other clients.
--
-- sender_email is the normalised address part of the From header
-- (lowercased, "<>" stripped, no display name), so we can do a cheap
-- equality compare against extracted addresses in the join.

CREATE TABLE email_muted_senders (
    account_email  TEXT    NOT NULL,
    sender_email   TEXT    NOT NULL,
    muted_at_ms    INTEGER NOT NULL,
    PRIMARY KEY (account_email, sender_email)
);

CREATE INDEX idx_email_muted_senders_account
    ON email_muted_senders(account_email);
