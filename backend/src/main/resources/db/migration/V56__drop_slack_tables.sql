-- Drop the Slack integration tables. The OAuth-based Slack feature
-- (V48-V50) was removed; users will reconnect Slack via a different
-- approach later, at which point a fresh schema will be added.
--
-- Forward-only: we don't try to preserve existing rows. The data was
-- a local cache of Slack messages + per-workspace inbox state; any
-- future Slack integration will rebuild from Slack's API on connect.
DROP TABLE IF EXISTS slack_inbox_state;
DROP TABLE IF EXISTS slack_channel_watermarks;
DROP TABLE IF EXISTS slack_dm_conversations;
DROP TABLE IF EXISTS slack_messages;
DROP TABLE IF EXISTS followed_channels;
