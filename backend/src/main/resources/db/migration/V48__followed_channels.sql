-- Per-workspace selection of Slack channels to follow in full inside the
-- ByteQuay Slack tab. Backs the channel-selection screen (slice 3) and
-- feeds slice 4's real-time delivery + local message store: only messages
-- in followed channels get fetched + cached locally. The user picks 2-3
-- channels; the rest of Slack stays in Slack.
--
-- workspace_id is Slack's team_id (the same value SlackOAuthService.
-- ConnectionInfo carries in its label). channel_id is Slack's channel
-- id (Cxxxx for public, Gxxxx for private). channel_name + is_private
-- are denormalised at write time so reads can render the sidebar
-- without re-fetching the channel list every paint.
--
-- Treated as throwaway: a clean rebuild from Slack's conversations.list
-- repopulates the table from scratch if a schema migration ever needs it.
CREATE TABLE followed_channels (
    workspace_id   TEXT    NOT NULL,
    channel_id     TEXT    NOT NULL,
    channel_name   TEXT    NOT NULL,
    is_private     INTEGER NOT NULL DEFAULT 0,
    selected_at    TEXT    NOT NULL,
    PRIMARY KEY (workspace_id, channel_id)
);
