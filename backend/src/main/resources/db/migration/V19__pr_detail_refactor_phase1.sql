-- Phase 1 of the PR detail refactor (docs/mockups/v2/detail/bytequay-pr-conversation.html).
-- Three additive changes; everything existing keeps working unchanged.
--
-- 1) PR created_at — powers "opened N days ago" on the description card,
--    the "Days open" stat in the right-meta sidebar, and date dividers
--    in the conversation timeline. We already get the field on every
--    GitHub PR detail / list response — just hadn't been persisting it.
ALTER TABLE pull_requests ADD COLUMN created_at TEXT;

-- 2) Force-push trail on the timeline. The mockup needs to show
--    "force-pushed · 12 → 14 commits · 2 commits since X's review".
--    GitHub's head_ref_force_pushed event carries before_sha + after_sha;
--    we'll start storing both on pr_timeline rows for that event type
--    (NULL for every other event).
ALTER TABLE pr_timeline ADD COLUMN before_sha TEXT;
ALTER TABLE pr_timeline ADD COLUMN after_sha  TEXT;

-- 3) Label colors as a side-table on pull_requests. Stored as a JSON map
--    {name: hexColor} alongside the existing labels list of names so
--    every existing consumer (PrAttention.hasBlockingLabel, the kanban
--    filter, anywhere that just wants names) keeps working untouched.
--    The frontend chip renderer reads from this map for tinting.
ALTER TABLE pull_requests ADD COLUMN label_colors TEXT;
