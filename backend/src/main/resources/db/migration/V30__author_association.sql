-- Author association is a per-comment field GitHub returns on every
-- issue / review-thread / timeline payload (OWNER, COLLABORATOR, MEMBER,
-- CONTRIBUTOR, FIRST_TIME_CONTRIBUTOR, FIRST_TIMER, MANNEQUIN, NONE).
-- The PR detail page renders it as a small role pill next to the
-- comment author so reviewers can tell at a glance whether a commenter
-- is part of the project. Existing rows get NULL — the UI treats NULL
-- as "no pill", which matches GitHub's NONE semantics.
ALTER TABLE pr_review_thread_message ADD COLUMN author_association VARCHAR;
ALTER TABLE pr_timeline ADD COLUMN author_association VARCHAR;
