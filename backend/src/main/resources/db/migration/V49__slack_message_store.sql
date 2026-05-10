-- Slice 4 — local message store backing the (still-unbuilt) Slack inbox
-- and channel-feed views. Populated by the 30-second polling loop in
-- SlackPollingService and the 24-hour bootstrap that runs on first
-- connect. Treated as throwaway cache: every row can be reconstructed
-- from Slack via conversations.history, so a future schema change can
-- drop and rebuild rather than migrate in place.
--
-- workspace_id is Slack's team_id (matches followed_channels and the
-- label SlackOAuthService.ConnectionInfo carries).

-- One row per Slack message. Composite PK on (workspace_id, channel_id,
-- ts) — Slack guarantees ts is unique per channel. inbox_kind is
-- precomputed by SlackInboxCategorizer at write time so the inbox view
-- can do a cheap WHERE filter rather than re-categorising on every read.
CREATE TABLE slack_messages (
    workspace_id   TEXT    NOT NULL,
    channel_id     TEXT    NOT NULL,
    ts             TEXT    NOT NULL,
    user_id        TEXT,
    text           TEXT,
    thread_ts      TEXT,
    has_at_you     INTEGER NOT NULL DEFAULT 0,
    inbox_kind     TEXT    NOT NULL,
    raw_json       TEXT    NOT NULL,
    fetched_at     TEXT    NOT NULL,
    PRIMARY KEY (workspace_id, channel_id, ts)
);

-- Inbox queries scan by (workspace, kind, ts desc); thread expansion
-- joins child messages by (workspace, channel, thread_ts).
CREATE INDEX idx_slack_messages_inbox ON slack_messages(workspace_id, inbox_kind, ts);
CREATE INDEX idx_slack_messages_thread ON slack_messages(workspace_id, channel_id, thread_ts);

-- DM and group-DM conversations the user has open with Slack. Discovered
-- via users.conversations(types=im,mpim) on each poll tick. The peer
-- field is informational for the sidebar — for IMs it's the other user
-- id; for MPIMs we store a comma-separated list rendered as "Bob, Alice
-- + 1" upstream.
CREATE TABLE slack_dm_conversations (
    workspace_id      TEXT    NOT NULL,
    conversation_id   TEXT    NOT NULL,
    is_group          INTEGER NOT NULL DEFAULT 0,
    peer_user_ids     TEXT    NOT NULL,
    latest_ts         TEXT,
    last_seen_at      TEXT    NOT NULL,
    PRIMARY KEY (workspace_id, conversation_id)
);

-- Per-channel high-water mark: the ts of the most recent message we've
-- ingested. The polling loop calls conversations.history?oldest=<this>
-- to fetch only what's new. NULL == bootstrap hasn't run yet; the
-- bootstrap path treats that as "fetch the last 24h then set the
-- watermark to the latest ts seen". last_polled_at is purely
-- informational (debugging stale watermarks).
CREATE TABLE slack_channel_watermarks (
    workspace_id      TEXT NOT NULL,
    channel_id        TEXT NOT NULL,
    last_ts           TEXT NOT NULL,
    last_polled_at    TEXT NOT NULL,
    PRIMARY KEY (workspace_id, channel_id)
);

-- Per-message inbox state machine consumed by Slice 5's inbox view
-- (Unread → Expanded → Responded → Bumped). No rows written by Slice 4
-- itself — the table exists so the polling loop never has to migrate it
-- in place once Slice 5 starts populating it. archived_at + bumped_at
-- power the asymmetric-resurface rule called out in the design doc.
CREATE TABLE slack_inbox_state (
    workspace_id   TEXT    NOT NULL,
    channel_id     TEXT    NOT NULL,
    ts             TEXT    NOT NULL,
    state          TEXT    NOT NULL,
    archived_at    TEXT,
    bumped_at      TEXT,
    PRIMARY KEY (workspace_id, channel_id, ts)
);
