-- Purge inline (file-line) comments that were mirrored from GitHub review
-- threads into the local pr_comment table. That mirroring path was removed —
-- GitHub review threads now render live from the fetched PR detail, not from
-- copies in this table — so any such rows are orphans that would double every
-- thread on the diff. No supported path writes origin='remote' file-line
-- comments, so this matches only those orphans (a no-op on clean databases).
DELETE FROM pr_comment WHERE origin = 'remote' AND scope = 'file-line';
