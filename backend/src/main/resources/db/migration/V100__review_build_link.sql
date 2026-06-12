-- Two-way link between a review pass and the build thread it spawned
-- via "→ Spawn build thread". The build thread carries the parent
-- pass id (powers the "← from review of PR #N" breadcrumb and lets the
-- resolver flip the parent's AGREED findings to RESOLVED when the work
-- ships); the pass carries the spawned thread id (powers the
-- "5 AGREED → 3 resolved (build thread #M)" strip, one spawn per pass).
--
-- threads.id / review_passes.id are TEXT uuids. SQLite ADD COLUMN can't
-- add an enforced foreign key after the fact, so these are plain TEXT
-- columns and the app owns referential integrity.
--
-- review_finding.status already includes 'resolved'
-- (ReviewFindingStatus.RESOLVED) — no schema change needed there.
ALTER TABLE threads ADD COLUMN parent_review_pass_id TEXT;
ALTER TABLE review_passes ADD COLUMN spawned_build_thread_id TEXT;
CREATE INDEX threads_parent_review_pass_id_idx
    ON threads(parent_review_pass_id);
