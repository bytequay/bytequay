-- ReviewPass hosting: a pass is hosted either by a review THREAD (the
-- existing "Assign review" flow) or by a dev task's TASK_PHASE (the
-- internal / re-review the dev lifecycle runs). One column discriminates
-- many downstream branches (e.g. only THREAD-hosted passes carry the
-- spawn-build affordance — the dev task IS the build). `kind` separates a
-- FRESH pass from a RE_REVIEW (Loop D) pass.
--
-- host_id is TEXT (ids in this schema are TEXT, not numeric): the review
-- thread id for THREAD, the task id for TASK_PHASE.
ALTER TABLE review_passes ADD COLUMN host_kind TEXT NOT NULL DEFAULT 'THREAD';
ALTER TABLE review_passes ADD COLUMN host_id   TEXT NOT NULL DEFAULT '';
ALTER TABLE review_passes ADD COLUMN kind      TEXT NOT NULL DEFAULT 'FRESH';

-- Existing passes are all thread-hosted fresh reviews.
UPDATE review_passes SET host_kind = 'THREAD', host_id = thread_id, kind = 'FRESH';
