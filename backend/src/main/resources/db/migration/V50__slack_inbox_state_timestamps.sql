-- Slice 5 — drives the four-state machine (Unread → Expanded → Responded
-- → Bumped) on slack_inbox_state. The V49 table only carried archived_at
-- + bumped_at; the inbox view also needs to know when the user replied
-- (so the 4h auto-archive sweep has a countdown anchor) and when they
-- first opened the item (informational, used later by analytics + the
-- "New since you last read" divider on DM expanded view).
ALTER TABLE slack_inbox_state ADD COLUMN responded_at TEXT;
ALTER TABLE slack_inbox_state ADD COLUMN expanded_at TEXT;
