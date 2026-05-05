-- Snooze support for the PR dashboard. The base snoozed_until column
-- has been on pr_view_state since V2 but was never user-facing; this
-- migration adds the bookkeeping needed to wire it up:
--
--   snoozed_at         — when the snooze was set. Used for "did this
--                        urgent thing happen *after* the snooze
--                        started" comparisons in the auto-wake check.
--   snooze_wake_reason — populated when an auto-wake fires; cleared
--                        once the user has seen the just-woke alert.
--                        Values: 'TIME', 'CI_FAILING', 'CHANGES_REQUESTED',
--                        'MERGE_CONFLICT', 'MANUAL'.
ALTER TABLE pr_view_state ADD COLUMN snoozed_at         TEXT;
ALTER TABLE pr_view_state ADD COLUMN snooze_wake_reason TEXT;
