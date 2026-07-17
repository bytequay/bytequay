-- V181 assigned workspace_id and item_path in one SQLite UPDATE. Right-hand
-- expressions see the pre-update row, so legacy notifications need a second
-- pass after workspace_id has been populated.
UPDATE notifications
SET item_path = '#/workspace/' || workspace_id || '/trunks/' || thread_id
WHERE thread_id IS NOT NULL
  AND workspace_id IS NOT NULL
  AND item_path IS NULL;
