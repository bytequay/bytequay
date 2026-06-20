-- Link a review pass to the REVIEW_STAGE task_stage row it was spawned for
-- (a callable sub-stage hosted by an internal-review context). Null for
-- standalone (THREAD-hosted) review-of-others'-PRs passes. Written once via
-- setPassTaskStage and never by savePass (same discipline as host_kind/host_id),
-- so a reconstructed pass can't clobber it. The terminate hook reads it to
-- close the linked stage.
--
-- No FK clause: SQLite's ALTER TABLE ADD COLUMN can't carry a REFERENCES
-- clause (same as V118/V122); the value is always a valid task_stage id.
ALTER TABLE review_passes ADD COLUMN task_stage_id TEXT;
